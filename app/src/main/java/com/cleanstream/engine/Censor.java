package com.cleanstream.engine;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profanity censor — now driven by the SAME profanity.txt the backend uses,
 * with matching rules that mirror ttml_profanity.compile_profanity_regex:
 *
 *   - WHOLE-WORD membership (Scunthorpe-safe): a word is censored only if it
 *     (or a de-obfuscated / de-elongated form) is literally in profanity.txt.
 *     "bullshit","dumbass","motherfucker" are caught because they are LISTED;
 *     "assassinate","therapist","grapefruit" are NOT caught (not listed).
 *   - SUFFIX tolerance: listed base + s/es/ed/er/ers/ing/in  (fuck -> fucker).
 *   - OBFUSCATION tolerance: f-u-c-k, f*ck, s#it  (separators between letters).
 *   - ELONGATION tolerance (NEW): shiiiit -> shit, fuuuuck -> fuck.
 *   - ALLOWLIST: an explicit safety net of innocent words that must never be
 *     censored even if a future list edit would otherwise catch them.
 *
 * On a hit, the ORIGINAL characters are preserved but replaced with '*' for the
 * matched core, keeping any trailing suffix readable (proven rule: fucking ->
 * ****ing). Elongated hits star every original character of the core so
 * "Shiiiiiit" -> "*********".
 *
 * Load order: call Censor.init(context) once at startup. It reads
 * assets/profanity.txt. If that asset is missing, it falls back to a small
 * built-in root list so the app still censors something.
 */
public final class Censor {

    private Censor() {}

    // ---- built-in fallback (only used if assets/profanity.txt is missing) ----
    private static final String[] FALLBACK_ROOTS = {
            "fuck", "shit", "bitch", "bastard", "asshole", "cunt",
            "dick", "piss", "whore", "slut", "goddamn"
    };

    // Innocent words that must NEVER be censored (Scunthorpe safety net).
    // Extend freely. Lowercased; matched as whole words.
    private static final String[] ALLOWLIST = {
            // ass-containing
            "assassin","assassinate","assassination","assess","assessment","asset",
            "assets","assign","assignment","assist","assistant","assume","assumption",
            "assure","assurance","associate","association","assemble","assembly",
            "bass","brass","class","classic","glass","grass","mass","massive","pass",
            "passage","password","passenger","embarrass","harass","compass","canvass",
            "molasses","sass","lass","amass","carcass","potassium","ассеt",
            // shit / hit
            "shiitake","shitake",
            // rape-containing
            "grape","grapes","grapefruit","scrape","scraped","drape","draped","therapy",
            "therapist","therapeutic","aperapt",
            // cum-containing
            "cucumber","circumstance","document","documents","accumulate","cumulative",
            "scum","succumb","circumference",
            // cock-containing
            "cockpit","cocktail","peacock","cockroach","shuttlecock","hancock","cockney",
            // tit-containing
            "title","titles","titan","titanic","competitive","repetitive","constitution",
            "substitute","attitude","altitude","latitude","institute","quantities",
            // hell-containing
            "hello","shell","shelly","hellos","othello","hellenic","seashell",
            // damn / dam
            "dame","dames",
            // analyst / anal-
            "analysis","analyst","analytics","analyze","analog","analogy","canal",
            // misc
            "button","buttons","assampled"
    };

    private static volatile Pattern PATTERN;      // matches candidate profane tokens
    private static volatile Set<String> WORDSET;  // normalized listed bases
    private static volatile Set<String> ALLOWSET; // normalized allowlist
    private static volatile boolean INITED = false;

    // A token candidate = a run of letters possibly containing embedded
    // separators/obfuscation. We scan token-by-token so we can preserve
    // original characters and apply elongation/whole-word logic per token.
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}0-9][\\p{L}0-9'’*#@$._\\-]*");

    /** Call once at startup with a Context to load assets/profanity.txt. */
    public static synchronized void init(Context ctx) {
        if (INITED) return;
        List<String> words = null;
        try {
            InputStream is = ctx.getAssets().open("profanity.txt");
            words = readWords(is);
        } catch (Exception e) {
            words = null; // asset missing -> fallback below
        }
        buildFrom(words);
        INITED = true;
    }

    /** Build matcher from a word list (or fallback if null/empty). */
    private static void buildFrom(List<String> words) {
        if (words == null || words.isEmpty()) {
            words = new ArrayList<String>();
            for (String r : FALLBACK_ROOTS) words.add(r);
        }
        Set<String> bases = new HashSet<String>();
        for (String w : words) {
            if (w == null) continue;
            w = w.trim();
            if (w.isEmpty() || w.startsWith("#")) continue;
            boolean star = w.endsWith("*");
            String base = normalize(star ? w.substring(0, w.length() - 1) : w);
            if (base.length() >= 3) bases.add(base);
        }
        Set<String> allow = new HashSet<String>();
        for (String a : ALLOWLIST) allow.add(normalize(a));

        WORDSET = bases;
        ALLOWSET = allow;
        // The PATTERN is just the token scanner; membership is decided per-token.
        PATTERN = TOKEN;
    }

    /** Strip to [a-z0-9] lowercased — mirrors backend _normalize_to_base. */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        String low = s.toLowerCase();
        for (int i = 0; i < low.length(); i++) {
            char c = low.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    // Collapse runs of the SAME char of length 3+ down to a single char.
    // Only real elongation (3+) is collapsed; normal English doubles (oo, ee,
    // ll, ss) are left intact so "good" != "god", "shell" != "shel", etc.
    // "Shiiiiiit" -> "Shit", "Fuuuuck" -> "Fuck", "good" -> "good".
    private static String collapseElong(String s) {
        if (s.length() < 3) return s;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            int j = i + 1;
            while (j < n && s.charAt(j) == c) j++;
            int runLen = j - i;
            if (runLen >= 3) {
                b.append(c);            // 3+ elongation -> single
            } else {
                for (int k = 0; k < runLen; k++) b.append(c);  // 1 or 2 -> keep
            }
            i = j;
        }
        return b.toString();
    }

    // common inflectional suffixes the backend tolerates
    private static final String[] SUFFIXES = {
            "", "s", "es", "ed", "er", "ers", "ing", "in"
    };

    /** Does this normalized core (after de-obfuscation) resolve to a listed word? */
    private static boolean coreIsProfane(String norm) {
        if (norm.isEmpty()) return false;
        if (ALLOWSET.contains(norm)) return false;      // hard allow
        // try direct form, then an elongation-collapsed form (3+ runs -> 1)
        List<String> forms = new ArrayList<String>(2);
        forms.add(norm);
        String ce = collapseElong(norm); if (!ce.equals(norm)) forms.add(ce);

        for (String f : forms) {
            if (ALLOWSET.contains(f)) return false;      // allow applies to forms too
            // whole-word membership
            if (WORDSET.contains(f)) return true;
            // listed base + suffix: strip a known suffix and re-test membership
            for (String suf : SUFFIXES) {
                if (suf.isEmpty()) continue;
                if (f.endsWith(suf) && f.length() - suf.length() >= 3) {
                    String stem = f.substring(0, f.length() - suf.length());
                    if (WORDSET.contains(stem)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Censor a line: scan tokens, star any token that resolves to profanity,
     * preserving a readable inflectional suffix where possible.
     */
    public static String censor(String text) {
        if (text == null || text.isEmpty()) return text;
        if (!INITED || PATTERN == null) {
            // not initialized (no context yet) — be safe, do nothing
            return text;
        }
        Matcher m = PATTERN.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String token = m.group();
            String replacement = censorToken(token);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Decide + build the starred replacement for a single token. */
    private static String censorToken(String token) {
        String norm = normalize(token);
        if (!coreIsProfane(norm)) return token;         // leave innocent tokens

        // We know the token is profane. Figure out how much to star:
        // star the "core" letters; keep a trailing readable suffix if the
        // normalized form ends with one AND the base (minus suffix) is listed.
        String keepSuffix = "";
        String ce = collapseElong(norm);
        for (String suf : SUFFIXES) {
            if (suf.isEmpty()) continue;
            // check against collapsed form so "shiiiing" keeps "ing"
            if (ce.endsWith(suf) && ce.length() - suf.length() >= 3) {
                String stem = ce.substring(0, ce.length() - suf.length());
                if (WORDSET.contains(stem)) { keepSuffix = suf; break; }
            }
            if (norm.endsWith(suf) && norm.length() - suf.length() >= 3) {
                String stem = norm.substring(0, norm.length() - suf.length());
                if (WORDSET.contains(stem)) { keepSuffix = suf; break; }
            }
        }

        // Map the kept suffix back onto the ORIGINAL token's trailing chars.
        // We preserve the last N original characters that correspond to the
        // suffix letters (case/þ preserved), star everything before them.
        if (!keepSuffix.isEmpty()) {
            int keep = matchTrailing(token, keepSuffix);
            if (keep > 0 && keep < token.length()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < token.length() - keep; i++) {
                    char c = token.charAt(i);
                    sb.append(isWordChar(c) ? '*' : c);
                }
                sb.append(token.substring(token.length() - keep));
                return sb.toString();
            }
        }
        // no readable suffix — star all word characters, keep punctuation
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            sb.append(isWordChar(c) ? '*' : c);
        }
        return sb.toString();
    }

    // How many trailing ORIGINAL chars correspond to `suffix` (letter-wise)?
    private static int matchTrailing(String token, String suffix) {
        int ti = token.length() - 1;
        int si = suffix.length() - 1;
        int count = 0;
        while (ti >= 0 && si >= 0) {
            char tc = Character.toLowerCase(token.charAt(ti));
            if (!isWordChar(tc)) { ti--; count++; continue; }
            if (tc == suffix.charAt(si)) { ti--; si--; count++; }
            else break;
        }
        return (si < 0) ? count : 0;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static List<String> readWords(InputStream is) throws Exception {
        List<String> words = new ArrayList<String>();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(is, Charset.forName("UTF-8")));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String s = line.trim();
                if (!s.isEmpty() && !s.startsWith("#")) words.add(s);
            }
        } finally {
            r.close();
        }
        return words;
    }
}