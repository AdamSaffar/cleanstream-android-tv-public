package com.cleanstream.engine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Status screen: shows permission state and engine status.
 * Starting the activity starts the EngineService.
 */
public class MainActivity extends Activity {

    private TextView status;
    private final Handler ui = new Handler();
    private final Runnable refresher = new Runnable() {
        public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = new TextView(this);
        status.setTextSize(20);
        status.setTextColor(Color.WHITE);
        status.setPadding(48, 48, 48, 48);
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(0xFF101820);
        root.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(status);
        root.addView(sv);
        setContentView(root);
        startService(new Intent(this, EngineService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("CleanStream Engine (Phase 1)\n\n");

        boolean overlay = canDrawOverlays();
        sb.append("Overlay permission: ").append(overlay ? "GRANTED" : "MISSING").append('\n');
        if (!overlay) {
            sb.append("  -> adb shell appops set com.cleanstream.engine SYSTEM_ALERT_WINDOW allow\n");
        }

        boolean listener = listenerGranted();
        sb.append("Notification listener: ").append(listener ? "GRANTED" : "MISSING").append('\n');
        if (!listener) {
            sb.append("  -> adb shell cmd notification allow_listener com.cleanstream.engine/.MediaWatcher\n");
        }

        boolean readLogs = checkCallingOrSelfPermission("android.permission.READ_LOGS")
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
        sb.append("READ_LOGS (ad detection): ").append(readLogs ? "GRANTED" : "MISSING").append('\n');
        if (!readLogs) {
            sb.append("  -> adb shell pm grant com.cleanstream.engine android.permission.READ_LOGS\n");
        }

        EngineService svc = EngineService.get();
        sb.append("Engine service: ").append(svc != null ? "RUNNING" : "STARTING...").append('\n');
        if (svc != null) {
            sb.append("Cues loaded: ").append(svc.getRenderer().cueCount()).append('\n');
            SyncEngine.Playhead ph = svc.getSync().readPlayhead();
            if (ph == null) {
                sb.append("Media session: none visible")
                  .append(listener ? " (start Netflix playback)" : " (grant listener first)")
                  .append('\n');
            } else {
                sb.append("Media session: state=").append(ph.state)
                  .append(" pos=").append(ph.estPos).append("ms\n");
            }
            sb.append('\n').append(svc.getSync().stats()).append('\n');
        }
        status.setText(sb.toString());
    }

    private boolean canDrawOverlays() {
        try {
            return Settings.canDrawOverlays(this);
        } catch (Throwable t) {
            return true; // pre-M
        }
    }

    private boolean listenerGranted() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
