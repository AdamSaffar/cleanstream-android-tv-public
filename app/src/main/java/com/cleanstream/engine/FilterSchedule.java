package com.cleanstream.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The active filter for one title: mute windows + skip ranges, all in CONTENT
 * time (ms) — the same timeline as the caption cues.
 *
 * Driven by the OverlayRenderer's cue loop (same 40ms clock as captions), so
 * mute/skip fire in lockstep with the redacted captions the viewer sees, and
 * inherit ad handling for free (during an ad the renderer is paused, so
 * position() freezes and nothing fires).
 *
 * The schedule owns NO Android APIs. It only decides "we are now inside mute
 * window X" / "we just entered skip range Y" and calls back into the engine,
 * which performs the actual volume-mute or seekTo. This keeps it testable and
 * keeps the Android-specific bits (AudioManager, MediaController) in one place.
 */
public class FilterSchedule {

    /** A [start,end) interval in content-ms. */
    public static class Range {
        public final long start;
        public final long end;
        public Range(long start, long end) { this.start = start; this.end = end; }
    }

    /** The engine implements this to actually perform the effects. */
    public interface Effects {
        void mute();                 // duck/mute audio now
        void unmute();               // restore audio now
        void skipTo(long contentMs); // seek so content resumes at contentMs
        void log(String msg);
    }

    private final List<Range> muteWindows = new ArrayList<Range>();
    private final List<Range> skipRanges = new ArrayList<Range>();
    private final Effects fx;

    private volatile boolean enabled = false;
    private volatile boolean muteEnabled = true;
    private volatile boolean skipEnabled = true;

    // runtime state
    private boolean currentlyMuted = false;
    private long lastSkipEnd = -1;   // guard against re-skipping the same range

    // small lead so the mute fade completes as the word starts (fade is ~100-150ms)
    public volatile long muteLeadMs = 120;
    // skip a hair early so we never show the first flagged frame
    public volatile long skipLeadMs = 150;

    public FilterSchedule(Effects fx) { this.fx = fx; }

    // ------------------------------------------------------------ loading
    public synchronized void setWindows(List<Range> mutes, List<Range> skips) {
        muteWindows.clear();
        if (mutes != null) muteWindows.addAll(mutes);
        Collections.sort(muteWindows, BY_START);

        skipRanges.clear();
        if (skips != null) skipRanges.addAll(skips);
        Collections.sort(skipRanges, BY_START);

        currentlyMuted = false;
        lastSkipEnd = -1;
        fx.log("FilterSchedule loaded: " + muteWindows.size() + " mute windows, "
            + skipRanges.size() + " skip ranges");
    }

    public void enable() { enabled = true; }
    public void disable() {
        enabled = false;
        if (currentlyMuted) { fx.unmute(); currentlyMuted = false; }
    }
    public boolean isEnabled() { return enabled; }
    public void setMuteEnabled(boolean b) { muteEnabled = b; if (!b && currentlyMuted) { fx.unmute(); currentlyMuted = false; } }
    public void setSkipEnabled(boolean b) { skipEnabled = b; }

    public int muteCount() { synchronized (this) { return muteWindows.size(); } }
    public int skipCount() { synchronized (this) { return skipRanges.size(); } }

    // ------------------------------------------------------------ the tick
    /**
     * Called every cue-loop tick with the current CONTENT position (ms).
     * Returns true if a skip was issued (so the renderer can shortcut).
     */
    public boolean onTick(long contentMs) {
        if (!enabled) return false;

        // ---- SKIP first: if we're inside (or about to enter) a flagged range,
        //      jump past it. Checked first so we don't bother muting a scene
        //      we're about to skip anyway.
        if (skipEnabled) {
            Range hit = null;
            synchronized (this) {
                for (Range r : skipRanges) {
                    // enter slightly early; don't re-fire a range we just left
                    if (contentMs >= r.start - skipLeadMs && contentMs < r.end
                        && r.end != lastSkipEnd) {
                        hit = r;
                        break;
                    }
                    if (r.start - skipLeadMs > contentMs) break; // sorted: no later range matches
                }
            }
            if (hit != null) {
                lastSkipEnd = hit.end;
                fx.log("SKIP range [" + hit.start + "," + hit.end + "] at content "
                    + contentMs + " -> jumping to " + hit.end);
                if (currentlyMuted) { fx.unmute(); currentlyMuted = false; }
                fx.skipTo(hit.end);
                return true;
            }
        }

        // ---- MUTE: are we inside a mute window (with a small lead)?
        if (muteEnabled) {
            boolean shouldMute = false;
            synchronized (this) {
                for (Range r : muteWindows) {
                    if (contentMs >= r.start - muteLeadMs && contentMs < r.end) {
                        shouldMute = true;
                        break;
                    }
                    if (r.start - muteLeadMs > contentMs) break; // sorted
                }
            }
            if (shouldMute && !currentlyMuted) {
                currentlyMuted = true;
                fx.mute();
            } else if (!shouldMute && currentlyMuted) {
                currentlyMuted = false;
                fx.unmute();
            }
        }
        return false;
    }

    /** Called when playback pauses/stops/ad-freezes so we don't leave audio muted. */
    public void onPauseOrStop() {
        if (currentlyMuted) { fx.unmute(); currentlyMuted = false; }
        // Netflix briefly reports PAUSED while an engine-issued seek settles.
        // Re-arming the just-completed range here makes the cue loop issue the
        // same seek again while Netflix is still reporting just before its end.
        // New filters reset this guard in setWindows(); a pause must not act as
        // an implicit re-arm.
    }

    private static final Comparator<Range> BY_START = new Comparator<Range>() {
        public int compare(Range a, Range b) {
            return a.start < b.start ? -1 : (a.start > b.start ? 1 : 0);
        }
    };
}
