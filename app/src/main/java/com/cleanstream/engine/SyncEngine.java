package com.cleanstream.engine;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * On-device sync engine — v0.6.
 *
 * content_position = stream_position - offset, playhead read in-app at 50Hz.
 *
 * AD-BOUNDARY TIMING (the v0.6 change):
 * A new stream period (ad OR content) boots the decoder at LOW resolution
 * (480x270) and ramps to full res (1920x1080) ~1s later. The low-res COLLAPSE
 * is when DECODE of the new period starts; it leads the on-screen appearance of
 * that period by ~1s (ramp) + panel lag. Anchoring the caption offset on the
 * collapse therefore made every caption ~1.3s early for the rest of the episode
 * (a constant offset error, invisible to the internal drift stat).
 *
 * v0.6 anchors the content-resume on the full-res RESTORE (+ tunable displayLead)
 * instead of the collapse. The collapse still marks "a boundary happened"
 * (enter-ad / pod rollback); the restore times the resume near display-time.
 * Because every period restores to full res (ads included), a mid-pod ad restore
 * can trigger a premature resume — caught and undone by the pod-window rollback
 * on the next collapse (may briefly flash a caption during a multi-ad pod).
 *
 * Steady-state / seek / pause were already correct; this only touches ad exits.
 */
public class SyncEngine {

    public interface Sink { void log(String msg); }
    private long lastPosSampleWall = 0;
    private long lastPosSampleRaw = -1;
    // ---- tunables (TCP-settable) ----
    public volatile int pollMs = 20;              // 50Hz
    public volatile int driftToleranceMs = 150;   // re-anchor threshold
    public volatile int jumpThresholdMs = 5000;   // discontinuity = seek
    // ---- engine-issued skip guard ----
    // When WE issue a skip (seekTo on Netflix), the seek is asynchronous: for a
    // second or two the MediaSession playhead still reports the OLD position.
    // Without a guard, the sync loop sees that stale position as a huge drift
    // and yanks the caption clock BACK to the pre-skip spot (captions "behind").
    // These fields let skipTo() announce an in-flight seek so the loop holds
    // captions blank at the destination until the playhead actually arrives.
    public volatile int skipArriveToleranceMs = 1500; // playhead within this of
    // target => seek landed
    public volatile int skipGuardTimeoutMs = 12000;   // give up waiting after this
    // (raised from 6s: a seek
    // to near end-of-episode can
    // pause several seconds while
    // buffering before it lands)
    private volatile boolean seekPending = false;     // a skip seek is in flight
    private volatile long seekTargetContentMs = 0;    // destination (content ms)
    private volatile long seekGuardDeadlineWall = 0;  // when to stop waiting
    private volatile boolean skipLandedInAd = false;  // skip target hit a mid-roll
    private volatile boolean skipPostAdTargetRetryIssued = false;
    private volatile boolean skipAdExitObserved = false;
    private volatile long skipAdExitRaw = -1;
    // Monotonic skip guard: skips only ever move FORWARD through an episode, so a
    // skip whose target is at or below the highest target we've already skipped
    // to is a stale re-fire (e.g. a transient low-position glitch at end-of-
    // episode making an early skip range look active again). We reject those.
    private volatile long maxSkipTargetMs = -1;
    public volatile long skipRefireMarginMs = 5000;   // tolerance below max = stale
    // If a skip's destination is intercepted by a mid-roll ad, Netflix reports
    // the AD's own clock (~0), then resumes at the break's real content boundary
    // rather than necessarily at our requested destination. We therefore hold
    // captions through the ad and reissue the exact destination only after the
    // real ad exit. The ordinary seek-arrival path then anchors from Netflix's
    // confirmed landing; no guessed content-time offset is introduced here.
    public volatile long skipAdLandMarginMs = 60000;  // target-raw gap => ad land
    // A real seeked-into ad collapses the playhead to its own clock starting at
    // ~0. A seek that simply hasn't landed yet leaves the playhead at the
    // PRE-SEEK position (much larger). Only treat raw at/below this as an
    // ad-land, so a clean skip isn't misread as landing in an ad while its seek
    // is still in flight.
    public volatile long skipAdClockZeroMs = 10000;   // raw <= this = ad clock
    public volatile int skipAdMaxWaitMs = 90000;      // max wait for a mid-roll
    // A seeked-into mid-roll ad's own clock runs ~0..ad_length. Once the
    // playhead climbs above this, we've passed the ad and are in content again.
    // (Real mid-roll pods are well under this; tune up if pods are longer.)
    public volatile long skipAdClockMaxMs = 200000;   // 200s: ad-clock ceiling
    // A seeked-into ad briefly flashes full-res during its OWN startup bootstrap
    // at a very low playhead (~2s). Real content resume happens later, at
    // playhead ~= ad_length (many seconds). Only treat a full-res rise as
    // "content resumed" once the playhead is past this floor, so the ad's own
    // bootstrap flash doesn't trigger a premature recovery.
    public volatile long skipAdResumeFloorMs = 8000;  // ignore full-res below this
    // recovery-tracking state (steady-forward detection during skip-in-ad)
    private long recTrackWall = 0;
    private long recTrackRaw = -1;
    public volatile int freezeMs = 2500;          // frozen-while-playing = ad (TCL path)
    public volatile int extrapolateCapMs = 1500;
    public volatile boolean autoAds = true;
    public volatile boolean assumePreroll = true;
    public volatile int prerollTimeoutMs = 45000;
    // --- no-preroll fast-anchor: fixes the ~40s caption stall on content-resume.
    // A real pre-roll AD's clock climbs linearly from ~0 and never gets far. RESUMING
    // INTO CONTENT makes the raw playhead JUMP to the real position (e.g. 9:47) within
    // ~1s. That jump is an unambiguous "no pre-roll" signal -> anchor immediately.
    // Jump-ONLY by design: a sustained-motion signal was tried and REMOVED because
    // pre-roll ads also play at full res with normal motion (it falsely fired during
    // ads). Pure start-from-0-with-no-ad falls back to prerollTimeout (unchanged).
    public volatile boolean fastAnchorNoPreroll = true;
    public volatile long noPrerollJumpMs = 120000;   // raw beyond this => content-resume, not an ad
    public volatile String targetPkg = "com.netflix.ninja";
    /** Position frozen this long while state=PLAYING => treat as PAUSE (not ad). */
    public volatile int pauseFreezeMs = 700;
    /** Auto-anchor content-zero to stream position on playback start (no manual Z). */
    public volatile boolean autoAnchor = true;
    /** Lead of the full-res RESTORE log line over the content actually being
     *  visible on screen (ramp tail + panel). Added to the resume anchor.
     *  Positive pushes captions later. Calibrate live with TRIM. */
    public volatile int displayLeadMs = 0;   // was 400; entry-ramp correction replaces the guess
    /** Persistent global caption trim; positive = captions later. Folded into
     *  every ad offset. Set live with TRIM once, and it sticks for the session. */
    public volatile long globalTrimMs = 0;
    public volatile int adPodWindowMs = 45000;
    /** Ignore a restore as a content-resume until the ad has played this long —
     *  suppresses the ad's OWN low-res bootstrap restore (~1s after ad start)
     *  from prematurely ending the ad. Real ads are longer than this. */
    public volatile int minAdMs = 4000;
    /** Resolution width at/below which a drop means "an ad period started"
     *  (ads bootstrap at 480x270; content ABR steps stay above this). */
    public volatile int adStartW = 540;
    /** Resolution width at/above which a rise means "full content is presenting"
     *  (1920x1080). Intermediate ABR ramp steps (608, 960) are below this and
     *  are ignored, so a mid-pod ad ramp can't be mistaken for content resume. */
    public volatile int fullResW = 1280;

    // ---- state ----
    private final Context ctx;
    private final OverlayRenderer renderer;
    private final Sink sink;
    private Thread thread;
    private volatile boolean running = false;
    public volatile boolean enabled = false;
    public volatile int exitResumeDelayMs = 230;
    private long offset = 0;
    private boolean inAd = false;
    private long frozenContent = 0;
    private long adStartStream = 0;
    private boolean prerollPending = false;
    private long prerollDeadline = 0;
    private boolean frozeDuringAd = false;
    private long frozenRawPos = -1;
    private long prevOffset = 0;
    private long resumedAtWall = 0;
    private long adEnteredWall = 0;
    private long adEntryDropRaw = -1;   // rawPos at the 320x240 that started the ad
    private boolean entryRampMeasured = false;
    private long adExitDropRaw = -1;    // rawPos at the 320x240 that starts the ad EXIT ramp
    private long pendingResumeAtWall = 0;   // wall-clock time to fire the delayed caption resume
    private boolean pendingResume = false;  // a delayed resume is armed
    private int lastState = -1;
    private long lastRawPos = -1;
    private long lastRawAdvanceWall = 0;   // wall time the RAW playhead last moved forward
    private long prevRawForAdvance = -1;    // previous rawPos, to detect real forward motion
    // Jitter-immune pause detection: ring of (wall, rawPos) samples so we can measure
    // NET forward progress over a window. The playhead jitters +/- a few hundred ms
    // around a fixed point during a pause; net progress over a window is ~0, while
    // real playback shows sustained progress. Tick-to-tick deltas can't tell these
    // apart (jitter's upward wobble looks like advance); a windowed net does.
    private final java.util.ArrayDeque<long[]> rawSamples = new java.util.ArrayDeque<long[]>();
    public volatile int progressWindowMs = 800;   // window to measure net progress over
    public volatile int progressMinMs = 350;       // min net content-advance in that window to count as "playing"
    private long lastUpdateTime = -1;
    private long lastChangeWall = 0;
    private boolean controllerSeen = false;

    // diagnostics
    private long lastActions = -1;
    private long lastMetaDuration = -2;
    private String lastMetaTitle = null;
    private long lastRefreshWall = 0;
    private long lastRefreshRaw = -1;
    private final ArrayDeque<Long> refreshIntervals = new ArrayDeque<Long>();
    private int metaCheckCounter = 0;

    // stats
    private final ArrayDeque<Long> drifts = new ArrayDeque<Long>();
    private long corrections = 0;
    private long adBreaks = 0;
    private long samples = 0;
    private final ArrayDeque<String> events = new ArrayDeque<String>();

    public SyncEngine(Context ctx, OverlayRenderer renderer, Sink sink) {
        this.ctx = ctx;
        this.renderer = renderer;
        this.sink = sink;
    }

    // ------------------------------------------------------------ control
    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, "sync-engine");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void shutdown() { running = false; }

    public void enable() { enabled = true; lastState = -1; event("sync enabled"); }
    public void disable() { enabled = false; event("sync disabled"); }

    /** Manual "content starts NOW" — the Z command. */
    public void zeroContent() {
        Playhead ph = readPlayhead();
        if (ph != null) {
            offset = ph.estPos;
            inAd = false; prerollPending = false;
            renderer.play(0);
            event("content zeroed: stream " + ph.estPos + " = content 0, offset " + offset);
        }
    }

    /**
     * Engine-issued SKIP entry point. Call this INSTEAD of directly seeking +
     * re-anchoring when the filter schedule skips a scene. It:
     *   1. issues the seekTo on the media controller (content + current offset),
     *   2. blanks captions immediately (we are leaving the skipped scene), and
     *   3. arms the seek guard so the sync loop will NOT drag captions back to
     *      the stale (pre-seek) playhead. The loop holds blank until Netflix
     *      playhead actually arrives at the destination, then resumes tracking.
     * Returns false if there is no controller to seek.
     */
    public boolean beginSkip(long contentMs) {
        // Monotonic guard: reject a skip that targets a position we've already
        // skipped past. Skips move forward only; a target below the highest one
        // we've issued is a spurious re-fire (the end-of-episode cascade tried to
        // re-run the first skip because a transient position read as ~0). Ignore
        // it so already-played skips never fire twice.
        if (maxSkipTargetMs >= 0 && contentMs <= maxSkipTargetMs - skipRefireMarginMs) {
            event("skip to content " + contentMs + " ignored (already past "
                    + maxSkipTargetMs + " — stale re-fire)");
            return false;
        }
        MediaController c = controller();
        if (c == null) { event("beginSkip: no controller"); return false; }
        if (contentMs > maxSkipTargetMs) maxSkipTargetMs = contentMs;
        // Netflix's seekTo operates in CONTENT time: once you seek past the
        // pre-roll, the ad drops out of the seekable timeline, so seekTo(content
        // + offset) overshoots by the ad length. Seek to the content position
        // directly. (The caption clock still uses offset for its own tracking;
        // only the SEEK target must be offset-free.) Proven from the EVENTS log:
        // target 114000+offset 30894 landed the video ~31s late at 2:21.
        long target = contentMs;                          // Netflix seeks in content time
        seekPending = true;
        skipLandedInAd = false;
        skipPostAdTargetRetryIssued = false;
        skipAdExitObserved = false;
        skipAdExitRaw = -1;
        seekTargetContentMs = contentMs;
        seekGuardDeadlineWall = SystemClock.elapsedRealtime() + skipGuardTimeoutMs;
        renderer.blankCaption();                          // Option A: blank captions during the seek
        // (does NOT touch playback state or the
        // FilterSchedule, so skips stay armed)
        try {
            c.getTransportControls().seekTo(target);
        } catch (Exception e) {
            event("beginSkip: seekTo threw " + e.getClass().getSimpleName());
            // fall back to immediate anchor so we don't hang blank forever
            seekPending = false;
            renderer.endBlankCaption();
            renderer.play(contentMs);
            return false;
        }
        event("SKIP -> seek to content " + contentMs + " (stream " + target
                + "), captions held until playhead arrives");
        return true;
    }

    /** True while an engine skip's seek is still in flight (used by the loop). */
    private boolean skipGuardActive(Playhead ph, long now, boolean progressing) {
        if (!seekPending) return false;

        // ---- sub-state: the skip landed inside a mid-roll ad ----
        // We are waiting out the ad. Netflix plays the ad with its own 0-based
        // clock, and its eventual content resume can be at the ad boundary rather
        // than at our requested skip destination. onResolution() detects that
        // real exit and reissues the destination; normal arrival handling below
        // remains the sole place that confirms and anchors a successful skip.
        if (skipLandedInAd) {
            // A full-resolution decoder event only says the visual period changed.
            // Wait until the MediaSession also proves a stable forward content
            // clock before asking Netflix to honor the destination again. This is
            // a readiness observation, not a time or content-offset guess.
            if (skipAdExitObserved && ph != null
                    && ph.state == PlaybackState.STATE_PLAYING
                    && ph.estPos > skipAdExitRaw
                    && progressing
                    && trackAdRecovery(ph.estPos, now) != 0) {
                retrySkipAfterAdExit(ph.estPos);
                return true;
            }
            if (now >= seekGuardDeadlineWall) {
                // Never manufacture a content anchor from a timeout. That would
                // turn Netflix's period-local/reset clock into unrelated episode
                // captions. Keep the overlay blank and make the failure explicit
                // for diagnostics instead of resuming on false content time.
                seekGuardDeadlineWall = Long.MAX_VALUE;
                event("skip-in-ad: recovery timeout without verified target; captions remain held");
                return true;
            }
            return true;   // still in the mid-roll ad — hold blank, block re-fire
        }

        if (now >= seekGuardDeadlineWall) {
            if (skipPostAdTargetRetryIssued) {
                // Netflix did not confirm the post-ad target request. Do not
                // expose its reset/period-local clock as content time; return to
                // the observed-ready state and try the exact target again only
                // after another verified forward-progress sample.
                skipLandedInAd = true;
                skipPostAdTargetRetryIssued = false;
                skipAdExitObserved = true;
                skipAdExitRaw = ph != null ? ph.estPos : -1;
                recTrackWall = 0;
                recTrackRaw = -1;
                seekGuardDeadlineWall = now + skipAdMaxWaitMs;
                event("skip-in-ad: target retry unconfirmed; captions still held, awaiting verified retry");
                return true;
            }
            event("skip guard timeout — releasing, resuming normal tracking");
            seekPending = false;
            renderer.endBlankCaption();   // don't leave captions stuck blank
            return false;
        }
        if (ph != null) {
            long rawContent = ph.estPos;                 // post-collapse: raw ~= content
            long legacyContent = ph.estPos - offset;     // pre-collapse interpretation
            boolean arrivedRaw = Math.abs(rawContent - seekTargetContentMs) <= skipArriveToleranceMs;
            boolean arrivedLegacy = Math.abs(legacyContent - seekTargetContentMs) <= skipArriveToleranceMs;
            if (arrivedRaw || arrivedLegacy) {
                seekPending = false;
                if (arrivedRaw && !arrivedLegacy) {
                    event("timeline collapsed after seek — offset " + offset + " -> 0");
                    offset = 0;
                }
                renderer.endBlankCaption();
                renderer.play(seekTargetContentMs);
                event("skip landed: playhead raw=" + ph.estPos + " target content "
                        + seekTargetContentMs + " — captions resumed, offset=" + offset);
                return false;   // guard done this tick; normal tracking next tick
            }
            // ---- detect landing INSIDE a mid-roll ad ----
            // The seek target is far ahead, but the playhead has dropped to a
            // small value (the ad's own clock starting near 0). That means the
            // skip destination sat on a mid-roll break. Switch to the wait state:
            // Keep captions blank and extend the deadline to cover the ad. Once
            // its real content exit is observed, onResolution() will reissue the
            // requested target instead of treating the exit as the target itself.
            if (raw_is_ad_land(ph)) {
                skipLandedInAd = true;
                seekGuardDeadlineWall = now + skipAdMaxWaitMs;
                event("skip landed in a MID-ROLL ad (raw=" + ph.estPos + " target "
                        + seekTargetContentMs + ") — holding captions, waiting out ad");
                return true;
            }
        }
        return true;   // still waiting for the seek to land — hold blank
    }

    /** Heuristic: while seeking to a far-ahead target, has the playhead collapsed
     *  to the mid-roll ad's own 0-based clock? The discriminator vs a seek that
     *  simply hasn't landed yet: a real ad-land drops raw to NEAR ZERO (the ad
     *  clock starts at 0), whereas a seek-in-flight leaves raw at the PRE-SEEK
     *  position (tens of thousands of ms). So we require raw to be near zero,
     *  not merely "below the target". This prevents a clean skip (where raw is
     *  briefly still at the old position before the seek lands) from being
     *  mistaken for landing in an ad. */
    private boolean raw_is_ad_land(Playhead ph) {
        // raw collapsed to ~0 (ad clock) AND the target is far above it.
        return ph.estPos <= skipAdClockZeroMs
                && (seekTargetContentMs - ph.estPos) >= skipAdLandMarginMs;
    }

    /** During skip-in-ad recovery, track playhead velocity to know when steady
     *  forward playback (content) has resumed. Returns 1 if the playhead is
     *  advancing at ~real-time (content playing), 0 otherwise. Resets tracking
     *  whenever the playhead jumps (ad boundaries). */
    private int trackAdRecovery(long raw, long now) {
        int result = 0;
        if (recTrackRaw >= 0 && recTrackWall > 0) {
            long dWall = now - recTrackWall;
            long dRaw = raw - recTrackRaw;
            if (dWall > 0) {
                double v = (double) dRaw / dWall;
                // ~1.0 = steady forward playback. Allow a generous band.
                if (v > 0.6 && v < 1.5) result = 1;
            }
        }
        recTrackWall = now;
        recTrackRaw = raw;
        return result;
    }

    /** Record the observed visual transition out of the ad. The target is not
     *  retried here: the following stable MediaSession progress is the proof that
     *  Netflix is ready to receive a new seek request. */
    private void observeSkipAdExit(long rawAtResume) {
        if (!skipLandedInAd || skipAdExitObserved) return;
        skipAdExitObserved = true;
        skipAdExitRaw = rawAtResume;
        recTrackWall = 0;
        recTrackRaw = -1;
        event("skip-in-ad: content exit observed raw=" + rawAtResume
                + "; waiting for stable MediaSession progress before target retry");
    }

    /**
     * Netflix has visibly resumed content after a skip was intercepted by an ad.
     * That resume proves only that the ad ended, not that content is already at
     * the requested destination. Reissue the exact target now that the ad gate
     * has cleared; ordinary seek-arrival checks will anchor from Netflix's actual
     * reported landing position.
     */
    private boolean retrySkipAfterAdExit(long rawAtResume) {
        if (!skipLandedInAd) return false;
        if (skipPostAdTargetRetryIssued) {
            event("skip-in-ad: target retry already issued; ignoring duplicate ad-exit signal raw="
                    + rawAtResume);
            return true;
        }

        MediaController c = controller();
        if (c == null) {
            event("skip-in-ad: content exit seen but no controller to retry target");
            return false;
        }

        skipPostAdTargetRetryIssued = true;
        skipLandedInAd = false;
        skipAdExitObserved = false;
        skipAdExitRaw = -1;
        seekPending = true;
        seekGuardDeadlineWall = SystemClock.elapsedRealtime() + skipGuardTimeoutMs;
        recTrackWall = 0;
        recTrackRaw = -1;
        try {
            c.getTransportControls().seekTo(seekTargetContentMs);
        } catch (Exception e) {
            // Restore the hold state so a controller failure does not become a
            // false confirmed landing.
            skipLandedInAd = true;
            event("skip-in-ad: target retry threw " + e.getClass().getSimpleName());
            return false;
        }

        event("skip-in-ad: stable content progress raw=" + rawAtResume
                + "; reissuing target " + seekTargetContentMs
                + " and waiting for confirmed landing");
        return true;
    }

    /** Sync to a known content position (mid-episode joins). */
    public void anchorContent(long contentMs) {
        Playhead ph = readPlayhead();
        if (ph != null) {
            offset = ph.estPos - contentMs;
            inAd = false; prerollPending = false;
            renderer.play(contentMs);
            event("anchored: stream " + ph.estPos + " = content " + contentMs + ", offset " + offset);
        }
    }

    public void setOffset(long ms) { offset = ms; event("offset set to " + ms); }
    public long getOffset() { return offset; }

    /** Live nudge of the CURRENT offset only (one-off). +later / -earlier. */
    public void nudge(long deltaMs) {
        offset += deltaMs;
        renderer.play(effectiveContent());
        event("nudge " + deltaMs + " -> offset " + offset);
    }

    /** Persistent trim: fixes a constant early/late bias for the whole session.
     *  Positive = captions later. Applies now AND to every future ad offset. */
    public void trim(long deltaMs) {
        globalTrimMs += deltaMs;
        offset += deltaMs;
        renderer.play(effectiveContent());
        event("TRIM " + deltaMs + " -> globalTrim " + globalTrimMs + ", offset " + offset);
    }

    public void forceAdStart() {
        Playhead ph = readPlayhead();
        if (!inAd && ph != null) enterAd(ph, "manual", ph.rawPos);
    }
    public void forceAdEnd() {
        Playhead ph = readPlayhead();
        if (inAd && ph != null) exitAd(ph, "manual", ph.rawPos);
    }

    private long effectiveContent() {
        Playhead ph = readPlayhead();
        return ph == null ? renderer.position() : ph.estPos - offset;
    }

    // ------------------------------------------------------------ resolution signal
    private int lastResW = -1;

    /**
     * Every decoder resolution change. Thresholds (v0.7):
     *   AD START  : drop to <= adStartW (ad periods bootstrap at 480x270).
     *   AD RESUME : rise to >= fullResW (content presents at full 1920x1080),
     *               but only after the ad has run >= minAdMs (so the ad's OWN
     *               startup ramp to full res doesn't count).
     * Intermediate ABR steps (608, 960...) are ignored entirely — those were
     * what caused the false mid-pod resume in v0.6.
     */
    public void onResolution(int w, int h, long lagMs) {
        {
            Playhead _p = readPlayhead();
            event("RES " + w + "x" + h + " lag=" + lagMs + " rawPos=" + (_p != null ? _p.rawPos : -1)
                    + " state=" + (_p != null ? _p.state : -1) + (enabled ? "" : " (sync off)"));
        }
        if (!enabled || !autoAds) { lastResW = w; return; }
        // While a skip landed in a mid-roll and the guard is holding captions,
        // the guard owns recovery. A rise back to FULL resolution means the ad
        // ended and content resumed, but that is not proof of the requested skip
        // position. Reissue the target and wait for its actual arrival instead.
        // Other resolution changes during the hold are ad-internal.
        if (skipLandedInAd) {
            Playhead _ph = readPlayhead();
            // Only a full-res rise ABOVE the resume floor means content actually
            // resumed. A full-res flash at a very low playhead is the ad's own
            // startup bootstrap — ignore it and keep holding.
            if (w >= fullResW && _ph != null && _ph.estPos >= skipAdResumeFloorMs) {
                observeSkipAdExit(_ph.estPos);
            }
            lastResW = w;
            return;
        }
        Playhead ph = readPlayhead();
        long now = SystemClock.elapsedRealtime();
        if (ph == null) { lastResW = w; return; }
        long streamNow = ph.rawPos - (long) (lagMs * ph.speed);

        // a seek also reconfigures the decoder: position discontinuity = seek
        if (lastRefreshRaw >= 0 && lastRefreshWall > 0) {
            long expected = lastRefreshRaw + (long) ((now - lastRefreshWall) * ph.speed);
            if (Math.abs(ph.rawPos - expected) > 10000) {
                event("res ignored: position jumped (SEEK) raw=" + ph.rawPos);
                lastResW = w;
                return;
            }
        }

        boolean droppedLow = w <= adStartW && (lastResW < 0 || lastResW > adStartW);
        boolean roseFull = w >= fullResW && lastResW < fullResW;

        if (!inAd) {
            if (droppedLow) {
                if (resumedAtWall > 0 && (now - resumedAtWall) < adPodWindowMs) {
                    // ad->ad: the previous "resume" was actually another ad.
                    event("drop " + ((now - resumedAtWall) / 1000)
                            + "s after resume -> ad->ad, rollback offset " + offset + "->" + prevOffset);
                    offset = prevOffset;
                    inAd = true;
                    resumedAtWall = 0;
                    renderer.pause();
                    renderer.say("", 1);
                    // do NOT reset adEnteredWall — keep original ad start so the
                    // real content resume is not blocked by the minAd guard.
                } else {
                    adEntryDropRaw = streamNow;   // ramp START position, for entry-ramp correction
                    entryRampMeasured = false;
                    enterAd(ph, "res-drop", streamNow);
                }
            }
            // rises while in content = ABR recovery; ignore.
        } else {
            if (droppedLow) {
                // While in the ad, a drop to low-res marks the START of a ramp.
                // The LAST such drop before content resumes = the exit ramp start.
                adExitDropRaw = streamNow;
            }
            if (roseFull) {
                if ((now - adEnteredWall) >= minAdMs) {
                    // Content resumes at the full-res (content-visible) frame.
                    // No displayLead guess — the full-res frame IS content.
                    long resumeStream = streamNow + displayLeadMs; // displayLeadMs defaults 0 now
                    exitAd(ph, "res-full", resumeStream);
                    resumedAtWall = now;
                } else {
                    // The ad's OWN bootstrap ramp to full res. Content already froze
                    // at the 320-drop; this ramp is the AD decoder starting up, not
                    // content continuing. So we do NOT touch frozenContent here —
                    // the raw freeze position is correct. (An earlier version added
                    // this ramp and overcorrected the offset by ~1s.)
                    event("full-res but only " + (now - adEnteredWall)
                            + "ms into ad (< minAd) - ad's own bootstrap, ignoring");
                }
            }
            // drops / intermediate steps while in ad = ad-internal; ignore.
        }
        lastResW = w;
    }

    // ------------------------------------------------------------ playhead
    public static class Playhead {
        public int state; public long rawPos; public long updateTime;
        public float speed; public long ageMs; public long estPos;
        public boolean stale; public long actions;
    }

    public Playhead readPlayhead() {
        MediaController c = controller();
        if (c == null) return null;
        PlaybackState ps = c.getPlaybackState();
        if (ps == null) return null;
        Playhead ph = new Playhead();
        ph.state = ps.getState();
        ph.rawPos = ps.getPosition();
        ph.updateTime = ps.getLastPositionUpdateTime();
        ph.speed = ps.getPlaybackSpeed();
        ph.actions = ps.getActions();
        long now = SystemClock.elapsedRealtime();
        long age = Math.max(0, now - ph.updateTime);
        ph.ageMs = age;
        long usedAge = Math.min(age, extrapolateCapMs);
        ph.estPos = ph.rawPos + (ph.state == PlaybackState.STATE_PLAYING
                ? (long) (usedAge * ph.speed) : 0);
        ph.stale = age > extrapolateCapMs;
        return ph;
    }

    private MediaController controller() { return MediaWatcher.findController(ctx, targetPkg); }

    // ------------------------------------------------------------ main loop
    private void loop() {
        while (running) {
            try { if (enabled) tick(); }
            catch (Exception e) { event("error " + e.getClass().getSimpleName() + ": " + e.getMessage()); }
            try { Thread.sleep(pollMs); } catch (InterruptedException e) { return; }
        }
    }

    // Returns true if the RAW playhead is making sustained forward progress
    // (i.e. genuinely playing), false if it's frozen/jittering (paused). Immune to
    // the position jitter Netflix produces at a pause because it measures NET
    // advance over a wall-time window rather than tick-to-tick deltas.
    private boolean playheadProgressing(long nowWall, long rawPos) {
        rawSamples.addLast(new long[]{nowWall, rawPos});
        // drop samples older than 2x the window (keep the ring small)
        long cutoff = nowWall - (progressWindowMs * 2L);
        while (!rawSamples.isEmpty() && rawSamples.peekFirst()[0] < cutoff) {
            rawSamples.removeFirst();
        }
        // find the oldest sample that is at least progressWindowMs old
        long targetWall = nowWall - progressWindowMs;
        long refRaw = -1;
        for (long[] sample : rawSamples) {
            if (sample[0] <= targetWall) refRaw = sample[1];
            else break;
        }
        if (refRaw < 0) return true;   // not enough history yet -> assume playing (safe default)
        long netAdvance = rawPos - refRaw;
        return netAdvance >= progressMinMs;
    }

    private void tick() {
        Playhead ph = readPlayhead();
        long now = SystemClock.elapsedRealtime();

        if (ph == null) {
            if (controllerSeen) { controllerSeen = false; event("controller lost"); renderer.stop(); }
            return;
        }
        if (!controllerSeen) {
            controllerSeen = true; lastActions = -1; lastMetaDuration = -2;
            event("controller found: " + targetPkg);
        }
        samples++;
        // Fire a delayed caption resume armed by exitAd (waits out the exit ramp
        // so captions align with the picture instead of leading it).
        if (pendingResume && now >= pendingResumeAtWall) {
            Playhead p2 = readPlayhead();
            if (p2 != null) {
                long c = p2.estPos - offset;
                renderer.play(c);
                event("delayed resume fired: content=" + c);
            }
            pendingResume = false;
        }
        // DIAGNOSTIC: log raw position velocity every ~250ms to see content pause/resume
        if (lastPosSampleWall == 0 || (now - lastPosSampleWall) >= 250) {
            if (lastPosSampleRaw >= 0) {
                long wallDelta = now - lastPosSampleWall;
                long posDelta = ph.rawPos - lastPosSampleRaw;
                // velocity: how many position-ms advanced per wall-ms. ~1.0 = playing,
                // ~0 = frozen. This is the TRUE content play/pause signal.
                double vel = wallDelta > 0 ? (double) posDelta / wallDelta : 0;
                if (Math.abs(vel - 1.0) > 0.15 || vel < 0.15) {
                    event("POSVEL " + String.format("%.2f", vel) + " raw=" + ph.rawPos
                            + " state=" + ph.state + " age=" + ph.ageMs + " resW=" + lastResW
                            + " inAd=" + inAd);
                }
            }
            lastPosSampleWall = now; lastPosSampleRaw = ph.rawPos;
        }

        boolean refreshed = ph.updateTime != lastUpdateTime || ph.rawPos != lastRawPos;
        if (refreshed) {
            if (lastRefreshWall > 0) {
                long interval = now - lastRefreshWall;
                if (interval > 0 && interval < 60000) {
                    synchronized (refreshIntervals) {
                        refreshIntervals.add(Long.valueOf(interval));
                        while (refreshIntervals.size() > 200) refreshIntervals.poll();
                    }
                }
            }
            lastRefreshWall = now; lastRefreshRaw = ph.rawPos; lastChangeWall = now;
        }

        // Track RAW PLAYHEAD forward motion specifically (independent of updateTime).
        if (prevRawForAdvance >= 0 && ph.rawPos > prevRawForAdvance) {
            lastRawAdvanceWall = now;
        }
        prevRawForAdvance = ph.rawPos;

        // Jitter-immune "is the playhead really progressing" signal: NET forward
        // advance over a wall-time window. True = genuinely playing; false = paused
        // (frozen or jittering). Computed once per tick and used by all the pause/
        // resume/drift guards below so Netflix's pause-time position jitter can't
        // masquerade as playback.
        boolean progressing = playheadProgressing(now, ph.rawPos);

        // state transitions
        boolean started = lastState != PlaybackState.STATE_PLAYING
                && ph.state == PlaybackState.STATE_PLAYING && ph.rawPos < 5000;
        if (ph.state != lastState) {
            event("state -> " + ph.state + " raw=" + ph.rawPos + " content=" + (ph.estPos - offset));
            lastState = ph.state;
        }
        if (started && assumePreroll && autoAds) {
            inAd = true; frozenContent = 0; offset = 0; adStartStream = ph.estPos;
            frozeDuringAd = false; frozenRawPos = -1; adEnteredWall = now;
            prerollPending = true; prerollDeadline = now + prerollTimeoutMs;
            renderer.stop();
            event("playback started - assuming PRE-ROLL, captions held");
            saveLast(ph);
            return;
        }

        // FAST-ANCHOR (jump-only): if the raw playhead jumps past noPrerollJumpMs
        // while holding for a presumed pre-roll, we resumed into content deep in the
        // title (an ad's clock never reaches that far). Anchor now, skip the ~40s
        // stall. A genuine pre-roll never triggers this (its clock stays small).
        if (fastAnchorNoPreroll && prerollPending
                && ph.rawPos >= noPrerollJumpMs
                && ph.state == PlaybackState.STATE_PLAYING) {
            prerollPending = false; inAd = false; offset = 0;
            renderer.play(ph.estPos);
            event("no pre-roll: playhead jumped to " + ph.rawPos
                    + " (content-resume) - anchoring now, stall skipped");
            saveLast(ph);
            return;
        }

        if (prerollPending && now > prerollDeadline) {
            prerollPending = false; inAd = false; offset = 0;
            renderer.play(ph.estPos);
            event("preroll timeout, no boundary seen - captions on, offset 0 (Z to correct)");
        }

        if (ph.state != PlaybackState.STATE_PLAYING) {
            if (ph.state == PlaybackState.STATE_STOPPED || ph.state == PlaybackState.STATE_NONE) {
                renderer.stop();
                if (inAd && !prerollPending) inAd = false;
            } else {
                if (renderer.isPlaying()) renderer.pause();
            }
            saveLast(ph);
            return;
        }

        // FLICKER GUARD (definitive pause fix): Netflix briefly flips state to
        // PLAYING during a real pause while the playhead stays frozen. The CSRender
        // log proved this reaches the resume/drift code (play() CALLER=tick:769)
        // and restarts the caption clock, so captions "run" during pause. Here we
        // catch it: if the RAW PLAYHEAD has not advanced for pauseFreezeMs (the
        // time-based frozen signal, correct even though rawPos is stale between
        // Netflix refreshes), we are paused regardless of the state flag — freeze
        // captions and return BEFORE any resume/drift/play. Real playback advances
        // the playhead within pauseFreezeMs, so this never fires during genuine
        // play. Ads and pre-roll are excluded (separate handling).
        if (!inAd && !prerollPending && !pendingResume && !progressing) {
            // Playhead is NOT making net forward progress (frozen or jittering) =>
            // we're paused, even if Netflix flickers state to PLAYING. Freeze the
            // caption clock and return BEFORE any resume/drift/play can restart it.
            if (renderer.isPlaying()) {
                renderer.pause();
                event("pause held (state=PLAYING but playhead not progressing)");
            }
            saveLast(ph);
            return;
        }

        // PLAYING — TCL freeze-based ad path (Fire uses splice/restore instead)
        if (autoAds && !inAd) {
            long frozenFor = now - lastChangeWall;
            if (lastChangeWall > 0 && frozenFor >= freezeMs) {
                enterAd(ph, "freeze", ph.rawPos);
                saveLast(ph);
                return;
            }
        }

        if (inAd) {
            if (!frozeDuringAd && (now - lastChangeWall) >= freezeMs) {
                frozeDuringAd = true; frozenRawPos = ph.rawPos;
                event("session froze during ad at raw=" + ph.rawPos);
            }
            if (frozeDuringAd && refreshed && frozenRawPos >= 0 && ph.rawPos > frozenRawPos + 100) {
                exitAd(ph, "freeze-edge", ph.rawPos);
                resumedAtWall = now;
            }
            saveLast(ph);
            return;
        }

        // pause detection: Netflix freezes position but may keep state=PLAYING.
        // Pause detection based on the RAW PLAYHEAD, not updateTime: if rawPos has
        // not advanced for pauseFreezeMs, playback is paused — even if Netflix keeps
        // ticking updateTime and flickering state 2<->3 (the cause of captions
        // running during pause: those flickers used to clear the old refresh-based
        // frozen flag and fire spurious resumes).
        long sinceRawAdvance = (lastRawAdvanceWall > 0) ? (now - lastRawAdvanceWall) : 0;
        boolean positionFrozen = lastRawAdvanceWall > 0 && sinceRawAdvance >= pauseFreezeMs;
        if (positionFrozen && !inAd) {
            if (renderer.isPlaying()) {
                renderer.pause();
                event("PAUSE detected (frozen " + sinceRawAdvance + "ms) content=" + (ph.estPos - offset));
            }
            saveLast(ph);
            return;
        }
        if (progressing
                && !renderer.isPlaying() && !inAd && !prerollPending && !pendingResume) {
            renderer.resume();
            event("RESUME detected content=" + (ph.estPos - offset));
        }

        // Engine-issued skip in flight? Hold captions blank at the destination
        // and do NOT re-anchor to the stale (pre-seek) playhead. This is the fix
        // for captions jumping "behind" during an auto-skip: the seek is async,
        // so for ~1-2s the playhead still reads the old spot; without this guard
        // the drift logic below would drag captions back there.
        if (skipGuardActive(ph, now, progressing)) { saveLast(ph); return; }

        // normal content tracking
        long content = ph.estPos - offset;
        long ours = renderer.position();
        if (!renderer.isPlaying()) {
            // only (re)start the clock if the playhead is genuinely progressing; a
            // frozen/jittering playhead means a state flicker during a pause, which
            // must NOT restart captions.
            if (!progressing) { saveLast(ph); return; }
            renderer.play(content); saveLast(ph); return;
        }

        long drift = content - ours;
        synchronized (drifts) { drifts.add(Long.valueOf(drift)); while (drifts.size() > 200) drifts.poll(); }

        // Guard the drift re-anchor against pause flicker: the CSRender log proved
        // play() CALLER=tick:769 (this block) fired during a pause and ran the
        // caption clock. Only re-anchor when the playhead is genuinely advancing;
        // a frozen/rewinding playhead (pause with a state flicker) must not drift.
        if (progressing) {
            if (Math.abs(drift) > jumpThresholdMs) {
                event("discontinuity " + drift + "ms -> re-anchor (seek)");
                renderer.play(content); corrections++;
            } else if (Math.abs(drift) > driftToleranceMs) {
                renderer.play(content); corrections++;
            }
        }
        saveLast(ph);
    }

    private void saveLast(Playhead ph) { lastRawPos = ph.rawPos; lastUpdateTime = ph.updateTime; }

    private void enterAd(Playhead ph, String why, long spliceStream) {
        inAd = true; adBreaks++;
        adStartStream = spliceStream;
        frozeDuringAd = "freeze".equals(why);
        frozenRawPos = frozeDuringAd && ph != null ? ph.rawPos : -1;
        frozenContent = Math.max(0, spliceStream - offset);
        resumedAtWall = 0;
        adEnteredWall = SystemClock.elapsedRealtime();
        renderer.pause();
        renderer.say("", 1);
        event("AD DETECTED (" + why + ") - frozen content " + frozenContent
                + " adStartStream " + adStartStream);
    }

    private void exitAd(Playhead ph, String why, long resumeStream) {
        long impliedAd = resumeStream - adStartStream;
        long newOffset = resumeStream - frozenContent + globalTrimMs;
        if (newOffset < offset) {
            event("offset would shrink (" + offset + "->" + newOffset + "), clamping");
            newOffset = offset;
        }
        long adLen = newOffset - offset;
        prevOffset = offset;
        offset = newOffset;
        inAd = false;
        boolean wasPreroll = prerollPending;
        prerollPending = false;
        frozeDuringAd = false;
        corrections++;

        // Measure the EXIT ramp: content isn't VISIBLE at the full-res event, it's
        // visible ~exitRamp later (decoder ramp + panel). Delay the caption-clock
        // resume by that measured amount so captions align with the picture.
        long exitRamp = 0;
        if (adExitDropRaw >= 0) {
            long r = resumeStream - adExitDropRaw;
            if (r > 0 && r < 4000) exitRamp = r;
        }
        adExitDropRaw = -1;

        if (wasPreroll || exitRamp == 0) {
            // Pre-roll (no continuous content to lead) or no measurable ramp:
            // resume immediately at the correct content position.
            renderer.play(resumeStream - offset);
            event((wasPreroll ? "PRE-ROLL" : "AD") + " ENDED (" + why + ") - ad " + adLen
                    + "ms implied " + impliedAd + "ms, offset now " + offset
                    + " (immediate resume, exitRamp=" + exitRamp + ")");
        } else {
            // Mid-roll: arm a delayed resume. tick() fires it after exitRamp,
            // resuming at the LIVE content position (which by then matches screen).
            pendingResume = true;
            pendingResumeAtWall = SystemClock.elapsedRealtime() + exitResumeDelayMs;
            event("AD ENDED (" + why + ") - ad " + adLen + "ms implied " + impliedAd
                    + "ms, offset now " + offset + " - delaying caption resume by measured exitRamp "
                    + exitRamp + "ms");
        }
    }

    // ------------------------------------------------------------ diagnostics
    public String diag() {
        StringBuilder sb = new StringBuilder();
        List<Long> iv;
        synchronized (refreshIntervals) { iv = new ArrayList<Long>(refreshIntervals); }
        if (iv.isEmpty()) sb.append("refresh: no samples");
        else {
            long sum = 0, min = Long.MAX_VALUE, max = 0;
            for (Long v : iv) { sum += v; min = Math.min(min, v); max = Math.max(max, v); }
            sb.append("refresh: n=").append(iv.size()).append(" avg=").append(sum / iv.size())
                    .append("ms min=").append(min).append(" max=").append(max);
        }
        Playhead ph = readPlayhead();
        if (ph != null) sb.append(" | raw=").append(ph.rawPos).append(" est=").append(ph.estPos)
                .append(" age=").append(ph.ageMs).append(" content=").append(ph.estPos - offset);
        else sb.append(" | no session");
        sb.append(" | offset=").append(offset).append(" inAd=").append(inAd)
                .append(" displayLead=").append(displayLeadMs).append(" trim=").append(globalTrimMs);
        return sb.toString();
    }

    public String stats() {
        List<Long> d;
        synchronized (drifts) { d = new ArrayList<Long>(drifts); }
        StringBuilder sb = new StringBuilder();
        sb.append("samples=").append(samples);
        if (!d.isEmpty()) {
            long sum = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (Long v : d) { sum += v; min = Math.min(min, v); max = Math.max(max, v); }
            sb.append(" last").append(d.size()).append(" avg=").append(sum / d.size())
                    .append("ms min=").append(min).append(" max=").append(max);
        }
        sb.append(" corrections=").append(corrections).append(" adBreaks=").append(adBreaks)
                .append(" offset=").append(offset).append(" inAd=").append(inAd)
                .append(" enabled=").append(enabled).append(" pollMs=").append(pollMs)
                .append("  [NOTE: avg is engine-internal, not captions-vs-screen]");
        return sb.toString();
    }

    public String recentEvents(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (events) {
            int skip = Math.max(0, events.size() - n);
            int i = 0;
            for (String e : events) { if (i++ < skip) continue; sb.append(e).append('\n'); }
        }
        return sb.toString();
    }

    private void event(String msg) {
        String line = "[" + (SystemClock.elapsedRealtime() / 1000) + "s] " + msg;
        synchronized (events) { events.add(line); while (events.size() > 400) events.poll(); }
        if (sink != null) sink.log(line);
    }
}
