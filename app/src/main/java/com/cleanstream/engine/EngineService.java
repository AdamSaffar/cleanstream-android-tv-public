package com.cleanstream.engine;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.List;

/**
 * Foreground service hosting the whole Phase-1 engine:
 *   OverlayRenderer (ported proven renderer)
 * + SyncEngine      (on-device 50Hz MediaSession sync)
 */
public class EngineService extends Service implements SyncEngine.Sink {

    private static final String TAG = "CleanStreamEngine";

    private static volatile EngineService instance;
    public static EngineService get() { return instance; }

    private OverlayRenderer renderer;
    private SyncEngine sync;
    private SpliceWatcher splices;

    // ---- MUTETEST2 state (feature-gate testing for no-UI mute) ----
    private int savedMusicVol = -1;
    private int savedSystemVol = -1;
    private boolean haveFocus = false;
    // ---- MUTETEST3 timing state ----
    private long mt3_t0 = 0;
    private long mt3_focusCbAt = 0;
    private String mt3_phase = "";
    // A no-op focus listener; we duck OTHER apps, we don't play audio ourselves.
    // Records the callback timestamp so we can compare "Netflix ACK'd" vs "user heard".
    private final android.media.AudioManager.OnAudioFocusChangeListener afl =
            new android.media.AudioManager.OnAudioFocusChangeListener() {
                public void onAudioFocusChange(int change) {
                    mt3_focusCbAt = System.currentTimeMillis();
                    Log.i(TAG, "afl onAudioFocusChange " + change + " at " + mt3_focusCbAt);
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        Censor.init(this);          // load assets/profanity.txt so captions get censored
        instance = this;
        startForegroundCompat();
        renderer = new OverlayRenderer(this);
        renderer.start();
        sync = new SyncEngine(this, renderer, this);
        sync.start();
        splices = new SpliceWatcher(new SpliceWatcher.Listener() {
            public void onResolution(int w, int h, long lagMs) { sync.onResolution(w, h, lagMs); }
        });
        splices.start();
        Log.i(TAG, "EngineService started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (splices != null) splices.shutdown();
        if (sync != null) sync.shutdown();
        if (renderer != null) renderer.shutdown();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public OverlayRenderer getRenderer() { return renderer; }
    public SyncEngine getSync() { return sync; }

    /**
     * Launcher entry point: arm one title for filtered playback. Turns on the
     * on-device sync engine and loads the title's complete filter (captions +
     * mute windows + skip ranges) from the given path. Called by
     * LauncherActivity right before it deep-links into Netflix. Returns the
     * LOADFILTER result string (or an error) for logging.
     */
    public String armTitle(String filterPath) {
        if (filterPath == null || filterPath.trim().isEmpty()) {
            return "ERR armTitle: empty path";
        }
        command("SYNC on");
        return command("LOADFILTER " + filterPath.trim());
    }

    public void log(String msg) {
        Log.i(TAG, msg);
    }

    /** Foreground notification; NotificationChannel via reflection (built against API-23 stubs). */
    private void startForegroundCompat() {
        try {
            Notification n;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                Class<?> chCls = Class.forName("android.app.NotificationChannel");
                Object ch = chCls.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance("engine", "CleanStream Engine", Integer.valueOf(2));
                nm.getClass().getMethod("createNotificationChannel", chCls).invoke(nm, ch);
                Notification.Builder b = (Notification.Builder) Notification.Builder.class
                        .getConstructor(Context.class, String.class)
                        .newInstance(this, "engine");
                b.setContentTitle("CleanStream filtering active")
                        .setSmallIcon(android.R.drawable.ic_menu_view);
                n = b.build();
            } else {
                Notification.Builder b = new Notification.Builder(this);
                b.setContentTitle("CleanStream filtering active")
                        .setSmallIcon(android.R.drawable.ic_menu_view);
                n = b.build();
            }
            startForeground(1, n);
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed (continuing as background): " + e);
        }
    }

    /** Debug-console protocol: Phase-0-compatible core + engine commands. */
    private String command(String line) {
        if (line.isEmpty()) return "ERR empty";
        String[] t = line.split("\\s+", 2);
        String op = t[0].toUpperCase();
        String rest = t.length > 1 ? t[1] : "";
        String[] a = rest.isEmpty() ? new String[0] : rest.split("\\s+");

        // ---- Phase-0-compatible renderer commands ----
        if (op.equals("PING")) return "PONG";
        if (op.equals("INFO")) {
            return "ENGINE v0.12 CUES " + renderer.cueCount()
                    + " PLAYING " + renderer.isPlaying()
                    + " POS " + renderer.position()
                    + " OVERLAY " + renderer.isAttached()
                    + " SESSION " + (sync.readPlayhead() != null);
        }
        if (op.equals("CLOCK")) return "CLOCK " + renderer.position();
        if (op.equals("CLEARCUES")) { renderer.clearCues(); return "OK cleared"; }
        if (op.equals("CUE")) {
            String[] p = rest.split("\\s+", 3);
            long st = Long.parseLong(p[0]);
            long en = Long.parseLong(p[1]);
            String text = p.length > 2 ? p[2].replace("\\n", "\n") : "";
            renderer.addCue(new Cue(st, en, text));
            return "OK cue";
        }
        if (op.equals("SORTCUES")) { renderer.sortCues(); return "OK sorted " + renderer.cueCount(); }
        if (op.equals("CUECOUNT")) return "CUES " + renderer.cueCount();
        if (op.equals("PLAY")) {
            long ms = a.length > 0 ? Long.parseLong(a[0]) : 0;
            renderer.play(ms);
            return "OK play @" + renderer.position();
        }
        if (op.equals("PAUSE")) { renderer.pause(); return "OK paused @" + renderer.position(); }
        if (op.equals("RESUME")) { renderer.resume(); return "OK resumed"; }
        if (op.equals("SEEK")) { renderer.seek(Long.parseLong(a[0])); return "OK seek " + a[0]; }
        if (op.equals("STOP")) { renderer.stop(); return "OK stopped"; }
        if (op.equals("POS")) { renderer.setBottomMarginPct(Integer.parseInt(a[0])); return "OK pos " + a[0]; }
        if (op.equals("SIZE")) { renderer.setTextSizeSp(Integer.parseInt(a[0])); return "OK size " + a[0]; }
        if (op.equals("STYLE")) {
            int fg = (int) Long.parseLong(a[0], 16);
            int bg = a.length > 1 ? (int) Long.parseLong(a[1], 16) : 0x99000000;
            renderer.setColors(fg, bg);
            return "OK style";
        }
        if (op.equals("SAY")) {
            String[] p = rest.split("\\s+", 2);
            int ms = Integer.parseInt(p[0]);
            renderer.say(p.length > 1 ? p[1].replace("\\n", "\n") : "", ms);
            return "OK say";
        }
        if (op.equals("QUIT")) { stopSelf(); return "OK bye"; }

        // ---- Phase-1 engine commands ----
        if (op.equals("LOADFILTER")) {
            // LOADFILTER <path> — load one title's complete filter (captions +
            // mute windows + skip ranges) and arm the schedule. This is the demo
            // path: one JSON per title, produced by the backend pipeline.
            if (rest.trim().isEmpty()) return "ERR LOADFILTER <path-to-filter.json>";
            FilterFile ff;
            try {
                ff = FilterFile.parseFile(rest.trim());
            } catch (Exception e) {
                return "ERR filter parse " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            // Load (already-cleaned) captions; run Censor as an idempotent safety net.
            java.util.List<Cue> caps = new java.util.ArrayList<Cue>();
            int changed = 0;
            for (Cue c : ff.captions) {
                String cen = Censor.censor(c.text);
                if (!cen.equals(c.text)) changed++;
                caps.add(new Cue(c.start, c.end, cen));
            }
            renderer.setCues(caps);

            // Build the effects impl: mute via the proven instant volume path,
            // skip via seekTo(contentMs + live offset) on the Netflix controller.
            FilterSchedule sched = new FilterSchedule(new FilterSchedule.Effects() {
                private int savedVol = -1;
                public void mute() {
                    android.media.AudioManager am = (android.media.AudioManager)
                            getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) return;
                    int s = android.media.AudioManager.STREAM_MUSIC;
                    if (savedVol < 0) savedVol = am.getStreamVolume(s);
                    am.setStreamVolume(s, 0, 0);   // instant (panel may flash; OEM-dependent)
                }
                public void unmute() {
                    android.media.AudioManager am = (android.media.AudioManager)
                            getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) return;
                    int s = android.media.AudioManager.STREAM_MUSIC;
                    if (savedVol >= 0) { am.setStreamVolume(s, savedVol, 0); savedVol = -1; }
                    else am.adjustStreamVolume(s, android.media.AudioManager.ADJUST_UNMUTE, 0);
                }
                public void skipTo(long contentMs) {
                    // Delegate to SyncEngine.beginSkip: it issues the seekTo,
                    // blanks captions during the (async) seek, and arms a guard
                    // so the sync loop won't drag captions back to the stale
                    // pre-seek playhead. Captions resume cleanly at the
                    // destination once Netflix's playhead actually arrives.
                    if (!sync.beginSkip(contentMs)) {
                        log("skipTo: beginSkip failed (no controller?)");
                    }
                }
                public void log(String m) { EngineService.this.log(m); }
            });
            sched.setWindows(ff.muteWindows, ff.skipRanges);
            sched.enable();
            renderer.setSchedule(sched);

            return "OK " + ff.summary() + " (captions loaded, " + changed
                    + " re-censored, schedule armed)";
        }
        if (op.equals("FILTER")) {
            // FILTER on|off|mute on|off|skip on|off — toggle the active schedule
            FilterSchedule s = renderer.getSchedule();
            if (s == null) return "ERR no filter loaded (LOADFILTER first)";
            if (a.length == 0) return "FILTER enabled=" + s.isEnabled()
                    + " mutes=" + s.muteCount() + " skips=" + s.skipCount();
            String k = a[0].toLowerCase();
            if (k.equals("on")) { s.enable(); return "OK filter on"; }
            if (k.equals("off")) { s.disable(); return "OK filter off"; }
            if (k.equals("mute") && a.length > 1) {
                s.setMuteEnabled(a[1].equalsIgnoreCase("on")); return "OK mute=" + a[1];
            }
            if (k.equals("skip") && a.length > 1) {
                s.setSkipEnabled(a[1].equalsIgnoreCase("on")); return "OK skip=" + a[1];
            }
            return "ERR FILTER on|off | mute on|off | skip on|off";
        }
        if (op.equals("LOADFILE")) {
            // LOADFILE [raw] <path> — parse subtitle file on-device, censor, load
            boolean censor = true;
            String path = rest;
            if (rest.startsWith("raw ")) { censor = false; path = rest.substring(4); }
            List<Cue> cues;
            try {
                cues = SubtitleParser.parseFile(path.trim());
            } catch (Exception e) {
                return "ERR parse " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            int changed = 0;
            if (censor) {
                List<Cue> out = new java.util.ArrayList<Cue>();
                for (Cue c : cues) {
                    String cen = Censor.censor(c.text);
                    if (!cen.equals(c.text)) changed++;
                    out.add(new Cue(c.start, c.end, cen));
                }
                cues = out;
            }
            renderer.setCues(cues);
            return "OK loaded " + cues.size() + " cues, " + changed + " censored";
        }
        if (op.equals("SYNC")) {
            if (a.length > 0 && a[0].equalsIgnoreCase("on")) { sync.enable(); return "OK sync on"; }
            if (a.length > 0 && a[0].equalsIgnoreCase("off")) { sync.disable(); return "OK sync off"; }
            return "ERR SYNC on|off";
        }
        if (op.equals("Z")) { sync.zeroContent(); return "OK zeroed, offset " + sync.getOffset(); }
        if (op.equals("STATS")) return sync.stats();
        if (op.equals("DIAG")) return sync.diag() + " | splicewatcher: "
                + (splices == null ? "none" : splices.status());
        if (op.equals("TRIM")) {
            if (a.length == 0) return "ERR TRIM <ms> (+ later / - earlier)";
            sync.trim(Long.parseLong(a[0]));
            return "OK trim, offset " + sync.getOffset();
        }
        if (op.equals("DUMP")) {
            A11yProbe a11y = A11yProbe.get();
            if (a11y == null) return "ERR accessibility service not enabled - grant it:\n"
                    + "adb shell settings put secure enabled_accessibility_services "
                    + "com.cleanstream.engine/com.cleanstream.engine.A11yProbe ; "
                    + "adb shell settings put secure accessibility_enabled 1";
            return a11y.dump();
        }
        if (op.equals("DUMPEVENTS")) {
            A11yProbe a11y = A11yProbe.get();
            return a11y == null ? "accessibility NOT connected" : a11y.eventLog();
        }
        if (op.equals("CLEAREVENTS")) {
            A11yProbe a11y = A11yProbe.get();
            if (a11y != null) a11y.clearEventLog();
            return "OK cleared event log";
        }
        if (op.equals("A11YSTATUS")) {
            A11yProbe a11y = A11yProbe.get();
            return a11y == null ? "accessibility NOT connected" : "accessibility connected, lastFg=" + a11y.lastForegroundPkg;
        }
        if (op.equals("CAPTURE")) {
            Intent i = new Intent(this, CaptureActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return "OK launched capture probe - approve the dialog on the TV, then run CAPTURERESULT";
        }
        if (op.equals("CAPTURERESULT")) { return CaptureActivity.lastResult; }
        if (op.equals("PROBE")) {
            String pkg = a.length > 0 ? a[0] : sync.targetPkg;
            MediaWatcher mw = MediaWatcher.get();
            if (mw == null) return "ERR notification listener service not running (grant it?)";
            return mw.probe(this, pkg);
        }
        if (op.equals("RES")) { sync.onResolution(Integer.parseInt(a[0]), a.length>1?Integer.parseInt(a[1]):270, 0); return "OK simulated resolution"; }
        if (op.equals("ANCHOR")) { sync.anchorContent(Long.parseLong(a[0])); return "OK anchored, offset " + sync.getOffset(); }
        if (op.equals("EVENTS")) {
            int n = a.length > 0 ? Integer.parseInt(a[0]) : 40;
            return sync.recentEvents(n);
        }
        if (op.equals("W")) {
            SyncEngine.Playhead ph = sync.readPlayhead();
            if (ph == null) return "no session (listener granted? netflix playing?)";
            return "state=" + ph.state + " raw=" + ph.rawPos + " est=" + ph.estPos
                    + " age=" + ph.ageMs + " speed=" + ph.speed + " stale=" + ph.stale
                    + " content=" + (ph.estPos - sync.getOffset());
        }
        if (op.equals("OFFSET")) {
            if (a.length > 0) sync.setOffset(Long.parseLong(a[0]));
            return "OK offset " + sync.getOffset();
        }
        if (op.equals("NUDGE")) { sync.nudge(Long.parseLong(a[0])); return "OK offset " + sync.getOffset(); }
        if (op.equals("ADSTART")) { sync.forceAdStart(); return "OK ad start forced"; }
        if (op.equals("ADEND")) { sync.forceAdEnd(); return "OK ad end forced"; }
        if (op.equals("SET")) {
            if (a.length < 2) return "ERR SET <key> <value>";
            String k = a[0].toUpperCase();
            String v = a[1];
            if (k.equals("POLL")) sync.pollMs = Integer.parseInt(v);
            else if (k.equals("DRIFT")) sync.driftToleranceMs = Integer.parseInt(v);
            else if (k.equals("FREEZE")) sync.freezeMs = Integer.parseInt(v);
            else if (k.equals("AUTOADS")) sync.autoAds = v.equals("1") || v.equalsIgnoreCase("on");
            else if (k.equals("PREROLL")) sync.assumePreroll = v.equals("1") || v.equalsIgnoreCase("on");
            else if (k.equals("PKG")) sync.targetPkg = v;
            else if (k.equals("ADPOD")) sync.adPodWindowMs = Integer.parseInt(v);
            else if (k.equals("DISPLAYLEAD")) sync.displayLeadMs = Integer.parseInt(v);
            else if (k.equals("ADSTARTW")) sync.adStartW = Integer.parseInt(v);
            else if (k.equals("FULLRESW")) sync.fullResW = Integer.parseInt(v);
            else if (k.equals("MINAD")) sync.minAdMs = Integer.parseInt(v);
            else return "ERR unknown key " + k;
            return "OK " + k + "=" + v;
        }

        // ---- Phase-2 feature-gate tests: skip + mute ----
        // SEEKTEST <deltaMs>  e.g. "SEEKTEST 60000" jumps forward 60s, "SEEKTEST -30000" back 30s.
        // Verdict is on the TV (silent jump vs. trick-play UI) + the logcat poll trace.
        if (op.equals("SEEKTEST")) {
            if (a.length == 0) return "ERR SEEKTEST <deltaMs>";
            final long delta;
            try { delta = Long.parseLong(a[0]); }
            catch (NumberFormatException e) { return "ERR SEEKTEST <deltaMs> (integer ms)"; }
            final MediaController c = MediaWatcher.findController(this, sync.targetPkg);
            if (c == null) return "ERR no controller for " + sync.targetPkg
                    + " (is it playing? notification listener granted?)";
            android.media.session.PlaybackState st = c.getPlaybackState();
            if (st == null) return "ERR no playback state (session present but not reporting)";
            final long before = st.getPosition();
            final long target = before + delta;
            log("SEEKTEST before=" + before + " target=" + target + " delta=" + delta
                    + " advertisedActions=" + st.getActions());
            try {
                c.getTransportControls().seekTo(target);
            } catch (Exception e) {
                return "ERR seekTo threw " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            // Poll for 3s on a background thread so the console reply returns immediately.
            new Thread(new Runnable() {
                public void run() {
                    for (int i = 0; i < 15; i++) {
                        try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                        android.media.session.PlaybackState s = c.getPlaybackState();
                        long pos = (s != null) ? s.getPosition() : -1;
                        int state = (s != null) ? s.getState() : -1;
                        log("  SEEKTEST t+" + (i * 200) + "ms state=" + state + " pos=" + pos);
                    }
                    android.media.session.PlaybackState fin = c.getPlaybackState();
                    long landed = (fin != null) ? fin.getPosition() : -1;
                    log("SEEKTEST done. target=" + target + " landed=" + landed
                            + " err=" + (landed - target) + "ms");
                }
            }, "seektest").start();
            return "OK seekTo(" + target + ") fired (from " + before + ", delta " + delta
                    + "). Watch the TV + logcat (tag CleanStreamEngine) for landing.";
        }

        // MUTETEST on|off — adjustStreamVolume with flags=0 → NO on-screen volume UI.
        // This is the exact mechanism the engine will use to duck profanity.
        if (op.equals("MUTETEST")) {
            boolean mute = a.length > 0 && (a[0].equalsIgnoreCase("on") || a[0].equals("1"));
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "ERR no AudioManager";
            int dir = mute ? android.media.AudioManager.ADJUST_MUTE
                    : android.media.AudioManager.ADJUST_UNMUTE;
            try {
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0); // flags=0 → no UI
            } catch (Exception e) {
                return "ERR adjustStreamVolume threw " + e.getClass().getSimpleName()
                        + " " + e.getMessage();
            }
            int vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            log("MUTETEST " + (mute ? "ON" : "OFF") + " flags=0 streamVol=" + vol);
            return "OK mute=" + mute + " streamVol=" + vol
                    + " (expect audio " + (mute ? "silenced" : "restored")
                    + " with NO volume overlay on the TV)";
        }

        // MUTETEST2 <n> — try five different silence/duck methods to find one that
        // is SILENT with NO volume UI on this OEM. Watch the TV for each.
        //   1 = setStreamVolume(0)          (absolute set, flags=0)
        //   2 = adjustStreamVolume MUTE     (baseline; same as MUTETEST on)
        //   3 = audio-focus DUCK            (transient may-duck; ducks, no vol UI)
        //   4 = audio-focus TRANSIENT       (asks Netflix to pause audio; no vol UI)
        //   5 = diagnostic: isVolumeFixed + stream info (not a mute)
        //   0 = RESTORE everything (unmute + abandon focus)
        if (op.equals("MUTETEST2")) {
            if (a.length == 0) return "ERR MUTETEST2 <0-5>  (0=restore, 1-4=methods, 5=diag)";
            int mode;
            try { mode = Integer.parseInt(a[0]); }
            catch (NumberFormatException e) { return "ERR MUTETEST2 <0-5>"; }
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "ERR no AudioManager";
            int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);

            try {
                switch (mode) {
                    case 1: {
                        // remember current level so we can restore
                        savedMusicVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0);
                        log("MUTETEST2 m1 setStreamVolume(0) saved=" + savedMusicVol);
                        return "OK m1: setStreamVolume 0 (saved " + savedMusicVol
                                + "). Silent? Volume UI? Run MUTETEST2 0 to restore.";
                    }
                    case 2: {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_MUTE, 0);
                        log("MUTETEST2 m2 ADJUST_MUTE flags=0");
                        return "OK m2: ADJUST_MUTE (baseline). Silent? Volume UI?";
                    }
                    case 3: {
                        int r = am.requestAudioFocus(afl,
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                        haveFocus = (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                        log("MUTETEST2 m3 requestAudioFocus(MAY_DUCK) granted=" + haveFocus);
                        return "OK m3: focus MAY_DUCK granted=" + haveFocus
                                + ". Audio should DUCK (quieter), NO vol UI. MUTETEST2 0 to release.";
                    }
                    case 4: {
                        int r = am.requestAudioFocus(afl,
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                        haveFocus = (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                        log("MUTETEST2 m4 requestAudioFocus(TRANSIENT) granted=" + haveFocus);
                        return "OK m4: focus TRANSIENT granted=" + haveFocus
                                + ". Netflix audio may PAUSE, NO vol UI. Watch if VIDEO also pauses. MUTETEST2 0 to release.";
                    }
                    case 5: {
                        boolean fixed = am.isVolumeFixed();
                        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                        log("MUTETEST2 m5 diag isVolumeFixed=" + fixed + " vol=" + cur + "/" + max);
                        return "DIAG isVolumeFixed=" + fixed + " musicVol=" + cur + "/" + max
                                + " (if fixed=true, stream-volume methods are why the UI shows / may be ignored)";
                    }
                    case 0: {
                        // restore: unmute, restore saved level, abandon focus
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_UNMUTE, 0);
                        if (savedMusicVol >= 0) {
                            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedMusicVol, 0);
                        }
                        if (haveFocus) { am.abandonAudioFocus(afl); haveFocus = false; }
                        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                        log("MUTETEST2 m0 RESTORE vol=" + cur);
                        return "OK restored: unmuted + focus abandoned, vol=" + cur;
                    }
                    default:
                        return "ERR MUTETEST2 <0-5>";
                }
            } catch (Exception e) {
                return "ERR MUTETEST2 m" + mode + " threw "
                        + e.getClass().getSimpleName() + " " + e.getMessage();
            }
        }

        // MUTETEST3 — measure the REAL fade latency of method 4 (transient focus).
        //   MUTETEST3 arm      -> log t0, request transient focus. LISTEN.
        //   MUTETEST3 mark     -> the instant you HEAR silence, type this. Logs delta.
        //   MUTETEST3 release  -> log t0, abandon focus. LISTEN for sound return.
        //   MUTETEST3 mark     -> when you HEAR sound again, type this. Logs delta.
        // The engine can't hear the TV, so YOUR mark is the ground truth for
        // "audio actually changed". The onAudioFocusChange callback time (logged
        // automatically) tells us when Netflix ACKs vs when you actually hear it.
        if (op.equals("MUTETEST3")) {
            if (a.length == 0) return "ERR MUTETEST3 arm|mark|release";
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "ERR no AudioManager";
            String sub = a[0].toLowerCase();

            if (sub.equals("arm")) {
                mt3_t0 = System.currentTimeMillis();
                mt3_phase = "MUTE";
                mt3_focusCbAt = 0;
                int r = am.requestAudioFocus(afl,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                haveFocus = (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
                log("MUTETEST3 ARM t0=" + mt3_t0 + " focusGranted=" + haveFocus
                        + " -> LISTEN, type 'MUTETEST3 mark' when you HEAR silence");
                return "OK armed (transient focus, granted=" + haveFocus
                        + "). Type MUTETEST3 mark the instant you hear silence.";
            }
            if (sub.equals("release")) {
                mt3_t0 = System.currentTimeMillis();
                mt3_phase = "RESTORE";
                mt3_focusCbAt = 0;
                if (haveFocus) { am.abandonAudioFocus(afl); haveFocus = false; }
                log("MUTETEST3 RELEASE t0=" + mt3_t0
                        + " -> LISTEN, type 'MUTETEST3 mark' when you HEAR sound return");
                return "OK released. Type MUTETEST3 mark the instant you hear sound return.";
            }
            if (sub.equals("mark")) {
                if (mt3_t0 == 0) return "ERR nothing armed; run MUTETEST3 arm first";
                long now = System.currentTimeMillis();
                long heardDelta = now - mt3_t0;
                long cbDelta = (mt3_focusCbAt > 0) ? (mt3_focusCbAt - mt3_t0) : -1;
                log("MUTETEST3 MARK phase=" + mt3_phase
                        + " heardDelta=" + heardDelta + "ms"
                        + " focusCbDelta=" + cbDelta + "ms");
                long saved = mt3_t0;
                mt3_t0 = 0;
                return "MEASURED " + mt3_phase + ": you heard the change "
                        + heardDelta + "ms after the command"
                        + (cbDelta >= 0 ? (" (focus callback fired at " + cbDelta + "ms)") : "")
                        + ". [t0=" + saved + "]";
            }
            return "ERR MUTETEST3 arm|mark|release";
        }

        // MUTELAT — automated pipeline check. Fires focus-mute, logs nanoTime;
        // a background thread fires restore exactly 4000ms later, logs nanoTime.
        // If the internal gap is 4000ms +/-1ms, our code/console/TCP path adds
        // ZERO latency, proving the ~3s you HEAR is entirely Netflix/mixer ramp.
        // Your EAR remains ground truth for the acoustic delay; this isolates
        // whether our pipeline is a contributor (it should not be).
        if (op.equals("MUTELAT")) {
            final android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "ERR no AudioManager";
            final long fireNanos = System.nanoTime();
            final long fireWall = System.currentTimeMillis();
            int r = am.requestAudioFocus(afl,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            final boolean granted = (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            long callReturnNanos = System.nanoTime();
            long callCostUs = (callReturnNanos - fireNanos) / 1000;
            log("MUTELAT MUTE fired wall=" + fireWall + " granted=" + granted
                    + " requestAudioFocus_call_cost=" + callCostUs + "us"
                    + " -> LISTEN: how long until silence? restore auto in 4000ms");
            haveFocus = granted;
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(4000); } catch (InterruptedException e) { return; }
                    long relNanos = System.nanoTime();
                    long gapMs = (relNanos - fireNanos) / 1000000;
                    long relCallStart = System.nanoTime();
                    if (haveFocus) { am.abandonAudioFocus(afl); haveFocus = false; }
                    long relCallCostUs = (System.nanoTime() - relCallStart) / 1000;
                    log("MUTELAT RESTORE fired internal_gap_since_mute=" + gapMs + "ms"
                            + " abandon_call_cost=" + relCallCostUs + "us"
                            + " -> LISTEN: how long until sound returns?");
                    log("MUTELAT SUMMARY: if internal_gap=4000ms then OUR pipeline adds "
                            + "~0 latency; any delay you HEAR is Netflix/mixer ramp.");
                }
            }, "mutelat").start();
            return "OK MUTELAT: muted via focus (granted=" + granted
                    + ", call cost " + callCostUs + "us). Auto-restore in 4s. "
                    + "Time BOTH by ear; internal timings in logcat.";
        }

        // MUTESWEEP <n> — instant-mute variants, to find one that mutes with NO panel.
        //   1 setStreamVolume(MUSIC,0, flags=0)
        //   2 setStreamVolume(MUSIC,0, FLAG_REMOVE_SOUND_AND_VIBRATE)
        //   3 adjustStreamVolume(MUSIC, ADJUST_MUTE, 0)   (baseline w/ UI)
        //   4 setStreamMute(MUSIC, true)                  (deprecated; sometimes no UI)
        //   5 setStreamVolume(SYSTEM,0, flags=0)          (different panel path)
        //   0 restore all (unmute MUSIC+SYSTEM, restore saved levels)
        if (op.equals("MUTESWEEP")) {
            if (a.length == 0) return "ERR MUTESWEEP <0-5>";
            int mode;
            try { mode = Integer.parseInt(a[0]); }
            catch (NumberFormatException e) { return "ERR MUTESWEEP <0-5>"; }
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "ERR no AudioManager";
            int MUSIC = android.media.AudioManager.STREAM_MUSIC;
            int SYSTEM = android.media.AudioManager.STREAM_SYSTEM;
            long t0 = System.nanoTime();
            try {
                switch (mode) {
                    case 1: {
                        if (savedMusicVol < 0) savedMusicVol = am.getStreamVolume(MUSIC);
                        am.setStreamVolume(MUSIC, 0, 0);
                        break;
                    }
                    case 2: {
                        if (savedMusicVol < 0) savedMusicVol = am.getStreamVolume(MUSIC);
                        am.setStreamVolume(MUSIC, 0,
                                android.media.AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                        break;
                    }
                    case 3: {
                        am.adjustStreamVolume(MUSIC, android.media.AudioManager.ADJUST_MUTE, 0);
                        break;
                    }
                    case 4: {
                        // setStreamMute(int,boolean) is hidden/removed in modern SDKs;
                        // call by reflection so it compiles against compileSdk 34.
                        try {
                            am.getClass()
                                    .getMethod("setStreamMute", int.class, boolean.class)
                                    .invoke(am, MUSIC, true);
                        } catch (Throwable th) {
                            return "ERR m4 setStreamMute unavailable: "
                                    + th.getClass().getSimpleName();
                        }
                        break;
                    }
                    case 5: {
                        if (savedSystemVol < 0) savedSystemVol = am.getStreamVolume(SYSTEM);
                        am.setStreamVolume(SYSTEM, 0, 0);
                        break;
                    }
                    case 0: {
                        am.adjustStreamVolume(MUSIC, android.media.AudioManager.ADJUST_UNMUTE, 0);
                        try {
                            am.getClass().getMethod("setStreamMute", int.class, boolean.class)
                                    .invoke(am, MUSIC, false);
                        } catch (Throwable ignored) {}
                        if (savedMusicVol >= 0) { am.setStreamVolume(MUSIC, savedMusicVol, 0); }
                        if (savedSystemVol >= 0) { am.setStreamVolume(SYSTEM, savedSystemVol, 0); }
                        long usR = (System.nanoTime() - t0) / 1000;
                        log("MUTESWEEP RESTORE musicVol=" + am.getStreamVolume(MUSIC)
                                + " cost=" + usR + "us");
                        return "OK restored MUSIC+SYSTEM (cost " + usR + "us)";
                    }
                    default:
                        return "ERR MUTESWEEP <0-5>";
                }
            } catch (Exception e) {
                return "ERR MUTESWEEP m" + mode + " threw "
                        + e.getClass().getSimpleName() + " " + e.getMessage();
            }
            long us = (System.nanoTime() - t0) / 1000;
            log("MUTESWEEP m" + mode + " call_cost=" + us + "us musicVol="
                    + am.getStreamVolume(MUSIC));
            return "OK m" + mode + " fired (call cost " + us + "us). "
                    + "INSTANT silence? Volume PANEL shown? MUTESWEEP 0 to restore.";
        }

        // MUTEBLIP <ms> — the REALISTIC profanity-mute test. Fires an instant
        // volume-mute, holds for <ms>, then auto-restores. Real swear words are
        // ~300-500ms, so the question is: does the volume PANEL even appear for a
        // blip this short? (Panels have their own fade-in; a mute that's over
        // before the panel draws may never show.) Uses setStreamVolume(0)/restore
        // (instant path). Watch the TV: silence audible? panel visible?
        //   MUTEBLIP        -> defaults to 400ms
        //   MUTEBLIP 300    -> 300ms blip
        //   MUTEBLIP 500    -> 500ms blip
        if (op.equals("MUTEBLIP")) {
            final int holdMs = (a.length > 0) ? Integer.parseInt(a[0]) : 400;
            final android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "ERR no AudioManager";
            final int MUSIC = android.media.AudioManager.STREAM_MUSIC;
            final int restoreTo = am.getStreamVolume(MUSIC);
            if (restoreTo == 0) return "ERR music volume already 0 - raise TV volume first";
            final long fireNanos = System.nanoTime();
            am.setStreamVolume(MUSIC, 0, 0);          // instant mute
            long muteCostUs = (System.nanoTime() - fireNanos) / 1000;
            log("MUTEBLIP mute fired hold=" + holdMs + "ms restoreTo=" + restoreTo
                    + " muteCost=" + muteCostUs + "us -> WATCH: panel? silence?");
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(holdMs); } catch (InterruptedException e) { return; }
                    long rStart = System.nanoTime();
                    am.setStreamVolume(MUSIC, restoreTo, 0);   // instant restore
                    long rCostUs = (System.nanoTime() - rStart) / 1000;
                    long totalMs = (System.nanoTime() - fireNanos) / 1000000;
                    log("MUTEBLIP restore fired total_blip=" + totalMs + "ms"
                            + " restoreCost=" + rCostUs + "us vol=" + am.getStreamVolume(MUSIC));
                }
            }, "muteblip").start();
            return "OK MUTEBLIP " + holdMs + "ms (mute cost " + muteCostUs
                    + "us, will restore to vol " + restoreTo + "). "
                    + "WATCH THE TV: did the volume panel appear for a blip this short?";
        }

        return "ERR unknown op " + op
                + " (PING INFO CLOCK CUE CLEARCUES SORTCUES CUECOUNT PLAY PAUSE RESUME SEEK STOP"
                + " POS SIZE STYLE SAY QUIT LOADFILE SYNC Z ANCHOR STATS DIAG EVENTS W OFFSET NUDGE TRIM PROBE CAPTURE CAPTURERESULT DUMP DUMPEVENTS CLEAREVENTS A11YSTATUS ADSTART ADEND SET SEEKTEST MUTETEST MUTETEST2 MUTETEST3 MUTELAT MUTESWEEP MUTEBLIP LOADFILTER FILTER)";
    }
}
