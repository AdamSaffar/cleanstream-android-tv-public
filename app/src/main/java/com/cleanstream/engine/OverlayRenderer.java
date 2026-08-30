package com.cleanstream.engine;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Caption overlay renderer — port of the proven com.captionfilter.overlay
 * renderer recovered from the Phase-0 APK.
 *
 * Window recipe (proven over DRM playback on TCL Android 9 + Fire OS 8):
 *   type  = TYPE_APPLICATION_OVERLAY (2038) on O+, TYPE_SYSTEM_ALERT (2003) below
 *   flags = NOT_FOCUSABLE | NOT_TOUCHABLE | LAYOUT_IN_SCREEN | LAYOUT_NO_LIMITS (0x318)
 *   format= TRANSLUCENT
 *
 * Clock model: content_ms = clockBasePosition + (elapsedRealtime - clockBaseRealtime)
 * Cue loop: background thread, 40ms tick.
 *
 * v0.13: the cue loop also drives an optional FilterSchedule (mute windows +
 * skip ranges) off the SAME position() clock, so redaction, muting, and skipping
 * all fire in lockstep and inherit ad handling (paused during ads => frozen).
 */
public class OverlayRenderer {

    private static final int TYPE_APPLICATION_OVERLAY = 2038; // API 26 constant
    private static final int TYPE_SYSTEM_ALERT = 2003;
    private static final int WINDOW_FLAGS = 0x318;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView caption;
    private WindowManager.LayoutParams capLp;
    private boolean capAttached = false;

    private int bottomMarginPct = 8;
    private int textSizeSp = 28;
    private int textColor = Color.WHITE;
    private int textBg = 0x99000000;

    private final List<Cue> cues = new ArrayList<Cue>();
    private volatile boolean playing = false;
    private long clockBaseRealtime = 0;
    private long clockBasePosition = 0;
    private long pausedPosition = 0;
    private volatile int shownIndex = -1;
    private volatile boolean running = true;
    private Thread cueThread;

    // v0.13: optional mute/skip schedule, driven off the same clock as captions.
    private volatile FilterSchedule schedule = null;

    public OverlayRenderer(Context ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------ lifecycle
    public void start() {
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        ui.post(new Runnable() {
            public void run() { attachCaption(); }
        });
    }

    public void shutdown() {
        running = false;
        playing = false;
        ui.post(new Runnable() {
            public void run() {
                try {
                    if (capAttached && caption != null) wm.removeView(caption);
                } catch (Exception ignored) {}
                capAttached = false;
            }
        });
    }

    private void attachCaption() {
        if (capAttached) return;
        caption = new TextView(ctx);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        int type = Build.VERSION.SDK_INT >= 26 ? TYPE_APPLICATION_OVERLAY : TYPE_SYSTEM_ALERT;
        capLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, WINDOW_FLAGS, PixelFormat.TRANSLUCENT);
        capLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        applyStyle();
        try {
            wm.addView(caption, capLp);
            capAttached = true;
        } catch (Exception e) {
            // overlay permission missing — MainActivity surfaces this
        }
    }

    private void applyStyle() {
        if (caption == null) return;
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        caption.setTextColor(textColor);
        caption.setBackgroundColor(textBg);
        int pad = (int) (textSizeSp * 0.4f *
                ctx.getResources().getDisplayMetrics().density);
        caption.setPadding(pad, pad / 2, pad, pad / 2);
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
        if (capLp != null) {
            capLp.y = screenH * bottomMarginPct / 100;
            if (capAttached) {
                try { wm.updateViewLayout(caption, capLp); } catch (Exception ignored) {}
            }
        }
    }

    // ------------------------------------------------------------ cue store
    public void clearCues() {
        synchronized (cues) { cues.clear(); }
        shownIndex = -1;
    }

    public void addCue(Cue c) {
        synchronized (cues) { cues.add(c); }
    }

    public void sortCues() {
        synchronized (cues) {
            Collections.sort(cues, new Comparator<Cue>() {
                public int compare(Cue a, Cue b) {
                    if (a.start != b.start) return a.start < b.start ? -1 : 1;
                    return 0;
                }
            });
        }
    }

    public int cueCount() {
        synchronized (cues) { return cues.size(); }
    }

    public void setCues(List<Cue> newCues) {
        synchronized (cues) {
            cues.clear();
            cues.addAll(newCues);
        }
        sortCues();
        shownIndex = -1;
    }

    // v0.13: mute/skip schedule
    public void setSchedule(FilterSchedule s) { this.schedule = s; }
    public FilterSchedule getSchedule() { return schedule; }

    // ------------------------------------------------------------ clock
    public void play(long positionMs) {
        clockBasePosition = positionMs;
        clockBaseRealtime = SystemClock.elapsedRealtime();
        playing = true;
        shownIndex = -1;
        ensureCueThread();
    }

    public void pause() {
        pausedPosition = position();
        playing = false;
        FilterSchedule s = schedule;
        if (s != null) s.onPauseOrStop();   // don't leave audio muted mid-ad
    }

    public void resume() {
        play(pausedPosition);
    }

    public void stop() {
        playing = false;
        pausedPosition = 0;
        shownIndex = -1;
        setCaptionText("");
        FilterSchedule s = schedule;
        if (s != null) s.onPauseOrStop();
    }

    // ---- caption blanking for an in-flight engine skip ----
    // While the engine skips a scene, we want the caption area blank WITHOUT
    // touching playback state or the FilterSchedule (so skips aren't disarmed /
    // re-fired). captionSuppressed makes the cue loop paint nothing; the caption
    // clock keeps running harmlessly. endBlankCaption() lifts it.
    private volatile boolean captionSuppressed = false;
    public void blankCaption() {
        captionSuppressed = true;
        shownIndex = -1;
        setCaptionText("");
    }
    public void endBlankCaption() {
        captionSuppressed = false;
        shownIndex = -1;   // force a repaint of whatever cue is current now
    }

    public void seek(long ms) {
        if (playing) play(ms);
        else pausedPosition = ms;
        shownIndex = -1;
    }

    public long position() {
        if (!playing) return pausedPosition;
        return clockBasePosition + (SystemClock.elapsedRealtime() - clockBaseRealtime);
    }

    public boolean isPlaying() { return playing; }

    /** Transient one-off message (SAY). */
    public void say(final String text, long forMs) {
        setCaptionText(text);
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!playing) setCaptionText("");
            }
        }, forMs);
    }

    // ------------------------------------------------------------ styling API
    public void setBottomMarginPct(int pct) {
        bottomMarginPct = pct;
        ui.post(new Runnable() { public void run() { applyStyle(); } });
    }

    public void setTextSizeSp(int sp) {
        textSizeSp = sp;
        ui.post(new Runnable() { public void run() { applyStyle(); } });
    }

    public void setColors(int fg, int bg) {
        textColor = fg;
        textBg = bg;
        ui.post(new Runnable() { public void run() { applyStyle(); } });
    }

    public boolean isAttached() { return capAttached; }

    // ------------------------------------------------------------ cue loop
    private void ensureCueThread() {
        if (cueThread != null && cueThread.isAlive()) return;
        cueThread = new Thread(new Runnable() {
            public void run() { cueLoop(); }
        }, "cue-loop");
        cueThread.setDaemon(true);
        cueThread.start();
    }

    private void cueLoop() {
        while (running) {
            try {
                if (playing) {
                    long pos = position();

                    // v0.13: drive mute/skip off the same clock as captions.
                    // BUT: while a skip is in flight (captionSuppressed), do NOT
                    // evaluate the schedule. The playhead is mid-seek and, if the
                    // skip landed in a mid-roll ad, position() is unreliable —
                    // running onTick here re-fires skips against a bad position
                    // (the cascade that broke captions after a mid-roll). Skip
                    // evaluation resumes once the engine lifts the suppression.
                    FilterSchedule sch = schedule;
                    if (sch != null && !captionSuppressed) {
                        boolean skipped = sch.onTick(pos);
                        if (skipped) {
                            // a seek was issued; let the engine re-anchor before
                            // we evaluate captions/mute against a stale position.
                            Thread.sleep(40);
                            continue;
                        }
                    }

                    if (captionSuppressed) {
                        // engine skip in flight — keep the caption blank and do
                        // not evaluate cues against the (about-to-change) position
                        if (shownIndex != -1) { shownIndex = -1; setCaptionText(""); }
                    } else {
                        int idx = -1;
                        String showText = null;
                        synchronized (cues) {
                            for (int i = 0; i < cues.size(); i++) {
                                Cue c = cues.get(i);
                                if (c.start <= pos && pos < c.end) {
                                    // Subtitle sources can represent simultaneous lines as
                                    // separate cues with the same time interval. Treat that
                                    // group as one multi-line caption instead of dropping
                                    // every line after the first matching cue.
                                    idx = i;
                                    StringBuilder lines = new StringBuilder();
                                    for (int j = i; j < cues.size(); j++) {
                                        Cue line = cues.get(j);
                                        if (line.start != c.start) break;
                                        if (line.end != c.end) continue;
                                        if (lines.length() > 0) lines.append('\n');
                                        lines.append(line.text);
                                    }
                                    showText = lines.toString();
                                    break;
                                }
                                if (c.start > pos) break;
                            }
                        }
                        if (idx != shownIndex) {
                            shownIndex = idx;
                            setCaptionText(showText == null ? "" : showText);
                        }
                    }
                }
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {}
        }
    }

    private void setCaptionText(final String text) {
        ui.post(new Runnable() {
            public void run() {
                if (caption == null) return;
                if (text == null || text.length() == 0) {
                    caption.setText("");
                    caption.setVisibility(TextView.GONE);
                } else {
                    caption.setText(text);
                    caption.setVisibility(TextView.VISIBLE);
                }
            }
        });
    }
}
