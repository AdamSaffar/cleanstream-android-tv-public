package com.cleanstream.engine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches the device log for stream-period splices (SSAI ad boundaries),
 * in-app via READ_LOGS.
 *
 * A new period bootstraps at low resolution, so each boundary shows a
 * collapse (<640 wide) followed ~1s later by a restore. The COLLAPSE is the
 * splice moment.
 *
 * Requires: adb shell pm grant com.cleanstream.engine android.permission.READ_LOGS
 * (development permission — grantable via adb; without it logcat only shows
 * our own process and no splices will ever arrive).
 */
public class SpliceWatcher extends Thread {

    public interface Listener {
        /** Every decoder resolution change. The engine applies all thresholds
         *  (ad-start = drop to very low; content-resume = rise to full res),
         *  because intermediate ABR ramp steps (608, 960...) must be ignored and
         *  only the engine has the full picture. lagMs = logcat pipe delay. */
        void onResolution(int w, int h, long lagMs);
    }

    private static final Pattern FMT =
        Pattern.compile("updateFormatChanged width = (\\d+) height = (\\d+)");
    private static final Pattern MI =
        Pattern.compile("media-info,\\s*(\\d+)x(\\d+)");
    private static final Pattern TS =
        Pattern.compile("^\\d\\d-\\d\\d (\\d\\d):(\\d\\d):(\\d\\d)\\.(\\d\\d\\d)");

    private final Listener listener;
    private volatile boolean running = true;
    private Process proc;
    private volatile long lastLineWall = 0;
    private volatile long spliceCount = 0;
    private volatile long lineCount = 0;

    public SpliceWatcher(Listener l) {
        super("splice-watcher");
        setDaemon(true);
        this.listener = l;
    }

    @Override
    public void run() {
        int lastW = -1, lastH = -1;
        try {
            proc = new ProcessBuilder(
                "logcat", "-T", "1", "-v", "time",
                "MediaCodecLogger:I", "OMX_VDEC:I", "*:S")
                .redirectErrorStream(true)
                .start();
            BufferedReader r = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));
            String line;
            while (running && (line = r.readLine()) != null) {
                lineCount++;
                lastLineWall = android.os.SystemClock.elapsedRealtime();
                Matcher m = FMT.matcher(line);
                if (!m.find()) {
                    m = MI.matcher(line);
                    if (!m.find()) continue;
                }
                int w = Integer.parseInt(m.group(1));
                int h = Integer.parseInt(m.group(2));
                if (w != lastW || h != lastH) {     // report every change
                    spliceCount++;
                    listener.onResolution(w, h, lineLag(line));
                    lastW = w; lastH = h;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Pipe lag: device wall-clock time in the log line vs now. Same device,
     * same clock — no skew (unlike the PC-tethered rig). Clamped 0..5000ms.
     */
    private long lineLag(String line) {
        try {
            Matcher t = TS.matcher(line);
            if (!t.find()) return 0;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            long nowMs = cal.getTimeInMillis();
            cal.set(java.util.Calendar.HOUR_OF_DAY, Integer.parseInt(t.group(1)));
            cal.set(java.util.Calendar.MINUTE, Integer.parseInt(t.group(2)));
            cal.set(java.util.Calendar.SECOND, Integer.parseInt(t.group(3)));
            cal.set(java.util.Calendar.MILLISECOND, Integer.parseInt(t.group(4)));
            long lag = nowMs - cal.getTimeInMillis();
            if (lag < 0 || lag > 5000) return 0;   // midnight edge / nonsense -> ignore
            return lag;
        } catch (Exception e) {
            return 0;
        }
    }

    public void shutdown() {
        running = false;
        try { if (proc != null) proc.destroy(); } catch (Exception ignored) {}
    }

    public String status() {
        return "lines=" + lineCount + " splices=" + spliceCount
            + " lastLineAge=" + (lastLineWall == 0 ? -1
                : android.os.SystemClock.elapsedRealtime() - lastLineWall) + "ms";
    }
}
