package com.cleanstream.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * One title's complete filter, as a single JSON file. This is the artifact the
 * backend pipeline produces per catalog title; the launcher pre-arms the engine
 * with it before deep-linking into Netflix.
 *
 * Captions are already CLEANED here (redaction done at build time), but the
 * engine still runs Censor over them as a safety net.
 */
public class FilterFile {

    public String movieID = "";
    public String title = "";
    public String episode = "";
    public long durationMs = 0;
    public final List<Cue> captions = new ArrayList<Cue>();
    public final List<FilterSchedule.Range> muteWindows = new ArrayList<FilterSchedule.Range>();
    public final List<FilterSchedule.Range> skipRanges = new ArrayList<FilterSchedule.Range>();

    public static FilterFile parseFile(String path) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new FileReader(path));
        try {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } finally {
            r.close();
        }
        return parse(sb.toString());
    }

    public static FilterFile parse(String json) throws Exception {
        FilterFile f = new FilterFile();
        JSONObject root = new JSONObject(json);

        f.movieID = root.optString("movieID", "");
        f.title = root.optString("title", "");
        f.episode = root.optString("episode", "");
        f.durationMs = root.optLong("duration_ms", 0);

        JSONArray caps = root.optJSONArray("captions");
        if (caps != null) {
            for (int i = 0; i < caps.length(); i++) {
                JSONObject c = caps.getJSONObject(i);
                long st = c.getLong("start");
                long en = c.getLong("end");
                String text = c.optString("text", "");
                f.captions.add(new Cue(st, en, text));
            }
        }

        JSONArray mutes = root.optJSONArray("mute_windows");
        if (mutes != null) {
            for (int i = 0; i < mutes.length(); i++) {
                JSONObject m = mutes.getJSONObject(i);
                f.muteWindows.add(new FilterSchedule.Range(m.getLong("start"), m.getLong("end")));
            }
        }

        JSONArray skips = root.optJSONArray("skip_ranges");
        if (skips != null) {
            for (int i = 0; i < skips.length(); i++) {
                JSONObject s = skips.getJSONObject(i);
                f.skipRanges.add(new FilterSchedule.Range(s.getLong("start"), s.getLong("end")));
            }
        }
        return f;
    }

    public String summary() {
        return "FilterFile[" + title + " " + episode + " movieID=" + movieID
            + " caps=" + captions.size() + " mutes=" + muteWindows.size()
            + " skips=" + skipRanges.size() + " dur=" + durationMs + "ms]";
    }
}
