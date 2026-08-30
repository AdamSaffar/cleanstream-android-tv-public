package com.cleanstream.engine;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TTML / SRT / WebVTT parser — port of the proven Python parser from
 * caption_sync.py. Format auto-detected, extension ignored.
 * Produces cues in CONTENT time (ms).
 */
public class SubtitleParser {

    private static final Pattern SRT_TS = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*" +
        "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    public static List<Cue> parseFile(String path) throws Exception {
        FileInputStream fis = new FileInputStream(new File(path));
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
            new InputStreamReader(fis, Charset.forName("UTF-8")));
        try {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            r.close();
        }
        String raw = sb.toString();
        if (raw.length() > 0 && raw.charAt(0) == '﻿') raw = raw.substring(1);
        return parse(raw);
    }

    public static List<Cue> parse(String raw) throws Exception {
        String head = raw.substring(0, Math.min(400, raw.length())).trim();
        if (head.startsWith("<") &&
            (head.contains("<tt") || head.toLowerCase().contains("ttml"))) {
            return parseTtml(raw);
        }
        return parseSrtVtt(raw);
    }

    // ------------------------------------------------------------- TTML
    private static class RawCue {
        long start; long end; double regionY; String text;
    }

    private static List<Cue> parseTtml(String raw) throws Exception {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(true);
        XmlPullParser xp = f.newPullParser();
        xp.setInput(new StringReader(raw));

        double tickRate = 10000000.0;
        Double frameRate = null;
        Map<String, Double> regionY = new HashMap<String, Double>();
        List<RawCue> rawCues = new ArrayList<RawCue>();

        // current <p> being read
        String pBegin = null, pEnd = null, pRegion = null;
        StringBuilder pText = null;
        int pDepth = 0;

        int ev = xp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String name = xp.getName();
                if ("tt".equals(name)) {
                    for (int i = 0; i < xp.getAttributeCount(); i++) {
                        String an = xp.getAttributeName(i);
                        if ("tickRate".equals(an)) {
                            tickRate = Double.parseDouble(xp.getAttributeValue(i));
                        } else if ("frameRate".equals(an)) {
                            frameRate = Double.valueOf(xp.getAttributeValue(i));
                        }
                    }
                } else if ("region".equals(name)) {
                    String rid = null, origin = null;
                    for (int i = 0; i < xp.getAttributeCount(); i++) {
                        String an = xp.getAttributeName(i);
                        if ("id".equals(an)) rid = xp.getAttributeValue(i);
                        else if ("origin".equals(an)) origin = xp.getAttributeValue(i);
                    }
                    if (rid != null) {
                        double y = 999.0;
                        if (origin != null) {
                            Matcher m = Pattern.compile(
                                "[\\d.]+%\\s+([\\d.]+)%").matcher(origin);
                            if (m.find()) y = Double.parseDouble(m.group(1));
                        }
                        regionY.put(rid, Double.valueOf(y));
                    }
                } else if ("p".equals(name) && pText == null) {
                    pBegin = null; pEnd = null; pRegion = null;
                    for (int i = 0; i < xp.getAttributeCount(); i++) {
                        String an = xp.getAttributeName(i);
                        if ("begin".equals(an)) pBegin = xp.getAttributeValue(i);
                        else if ("end".equals(an)) pEnd = xp.getAttributeValue(i);
                        else if ("region".equals(an)) pRegion = xp.getAttributeValue(i);
                    }
                    pText = new StringBuilder();
                    pDepth = 1;
                } else if (pText != null) {
                    pDepth++;
                    if ("br".equals(name)) pText.append('\n');
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (pText != null) pText.append(xp.getText());
            } else if (ev == XmlPullParser.END_TAG) {
                if (pText != null) {
                    pDepth--;
                    if (pDepth == 0) {
                        if (pBegin != null && pEnd != null) {
                            String text = pText.toString()
                                .replaceAll("[ \\t]+", " ").trim();
                            if (text.length() > 0) {
                                RawCue rc = new RawCue();
                                rc.start = parseTime(pBegin, tickRate, frameRate);
                                rc.end = parseTime(pEnd, tickRate, frameRate);
                                Double y = pRegion == null ? null : regionY.get(pRegion);
                                rc.regionY = y == null ? 999.0 : y.doubleValue();
                                rc.text = text;
                                rawCues.add(rc);
                            }
                        }
                        pText = null;
                    }
                }
            }
            ev = xp.next();
        }

        // merge <p> elements sharing the same time window (multi-line captions)
        Map<String, List<RawCue>> merged = new HashMap<String, List<RawCue>>();
        List<String> order = new ArrayList<String>();
        for (RawCue rc : rawCues) {
            String key = rc.start + ":" + rc.end;
            List<RawCue> l = merged.get(key);
            if (l == null) { l = new ArrayList<RawCue>(); merged.put(key, l); order.add(key); }
            l.add(rc);
        }
        List<Cue> cues = new ArrayList<Cue>();
        for (String key : order) {
            List<RawCue> parts = merged.get(key);
            Collections.sort(parts, new Comparator<RawCue>() {
                public int compare(RawCue a, RawCue b) {
                    return Double.compare(a.regionY, b.regionY);
                }
            });
            StringBuilder t = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) t.append('\n');
                t.append(parts.get(i).text);
            }
            cues.add(new Cue(parts.get(0).start, parts.get(0).end, t.toString()));
        }
        sortCues(cues);
        return cues;
    }

    /** TTML time expression -> milliseconds (port of _parse_time). */
    private static long parseTime(String value, double tickRate, Double frameRate) {
        String v = value.trim();
        try {
            if (v.endsWith("ms")) return (long) Double.parseDouble(v.substring(0, v.length() - 2));
            if (v.endsWith("t")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) / tickRate * 1000);
            if (v.endsWith("h")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 3600000);
            if (v.endsWith("m") && !v.contains(":")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 60000);
            if (v.endsWith("s") && !v.contains(":")) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) * 1000);
            if (v.endsWith("f") && frameRate != null) return (long) (Double.parseDouble(v.substring(0, v.length() - 1)) / frameRate.doubleValue() * 1000);
            if (v.contains(":")) {
                String[] parts = v.split(":");
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                long ms;
                int s;
                if (parts.length == 4) {
                    s = Integer.parseInt(parts[2]);
                    double frames = Double.parseDouble(parts[3]);
                    double fr = frameRate == null ? 25.0 : frameRate.doubleValue();
                    ms = (long) (frames / fr * 1000);
                } else if (parts[2].contains(".")) {
                    double sec = Double.parseDouble(parts[2]);
                    s = (int) sec;
                    ms = Math.round((sec - s) * 1000);
                } else {
                    s = Integer.parseInt(parts[2]);
                    ms = 0;
                }
                return ((h * 3600L + m * 60L + s) * 1000L) + ms;
            }
            return (long) (Double.parseDouble(v) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------- SRT / WebVTT
    private static List<Cue> parseSrtVtt(String raw) {
        raw = raw.replace("\r\n", "\n").replace("\r", "\n");
        List<Cue> cues = new ArrayList<Cue>();
        String[] blocks = raw.split("\n\n");
        for (String block : blocks) {
            Matcher m = SRT_TS.matcher(block);
            if (!m.find()) continue;
            long start = (Long.parseLong(m.group(1)) * 3600 +
                          Long.parseLong(m.group(2)) * 60 +
                          Long.parseLong(m.group(3))) * 1000 + Long.parseLong(m.group(4));
            long end   = (Long.parseLong(m.group(5)) * 3600 +
                          Long.parseLong(m.group(6)) * 60 +
                          Long.parseLong(m.group(7))) * 1000 + Long.parseLong(m.group(8));
            StringBuilder text = new StringBuilder();
            for (String line : block.split("\n")) {
                String s = line.trim();
                if (s.length() == 0) continue;
                if (SRT_TS.matcher(s).find()) continue;
                if (s.matches("\\d+")) continue;
                if (s.equals("WEBVTT")) continue;
                if (text.length() > 0) text.append('\n');
                text.append(s);
            }
            String t = TAG.matcher(text.toString()).replaceAll("");
            t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                 .replace("&quot;", "\"").replace("&#39;", "'").trim();
            if (t.length() > 0) cues.add(new Cue(start, end, t));
        }
        sortCues(cues);
        return cues;
    }

    private static void sortCues(List<Cue> cues) {
        Collections.sort(cues, new Comparator<Cue>() {
            public int compare(Cue a, Cue b) {
                if (a.start != b.start) return a.start < b.start ? -1 : 1;
                return 0;
            }
        });
    }
}
