package com.cleanstream.engine;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * NotificationListenerService — its only job is to hold the notification-
 * listener grant, which is what authorizes MediaSessionManager.getActiveSessions.
 * This replaces `adb shell dumpsys media_session` from the Phase-0 rig.
 *
 * Grant once via:
 *   adb shell cmd notification allow_listener com.cleanstream.engine/.MediaWatcher
 */
public class MediaWatcher extends NotificationListenerService {

    private static volatile MediaWatcher instance;

    public static MediaWatcher get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /**
     * PROBE: dump every piece of title/episode/duration metadata we can see for
     * a package, from BOTH the active media notification (extras) AND the
     * MediaController metadata. This is the title-detection investigation — on
     * Fire, MediaController.getMetadata() is null, so the media notification's
     * EXTRA_TITLE / EXTRA_TEXT / EXTRA_SUB_TEXT are the likely source.
     */
    public String probe(android.content.Context ctx, String pkg) {
        StringBuilder sb = new StringBuilder();

        // ---- media notification extras ----
        try {
            android.service.notification.StatusBarNotification[] active = getActiveNotifications();
            int shown = 0;
            if (active != null) {
                for (android.service.notification.StatusBarNotification sbn : active) {
                    if (!pkg.equals(sbn.getPackageName())) continue;
                    android.os.Bundle x = sbn.getNotification().extras;
                    if (x == null) continue;
                    CharSequence title = x.getCharSequence(android.app.Notification.EXTRA_TITLE);
                    CharSequence text = x.getCharSequence(android.app.Notification.EXTRA_TEXT);
                    CharSequence sub = x.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT);
                    CharSequence big = x.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT);
                    CharSequence info = x.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT);
                    sb.append("NOTIF[").append(sbn.getId()).append("] title=").append(title)
                      .append(" | text=").append(text).append(" | sub=").append(sub)
                      .append(" | big=").append(big).append(" | info=").append(info).append('\n');
                    shown++;
                }
            }
            if (shown == 0) sb.append("NOTIF: none for ").append(pkg)
                .append(" (playing? listener granted?)\n");
        } catch (SecurityException e) {
            sb.append("NOTIF: listener not granted\n");
        } catch (Exception e) {
            sb.append("NOTIF: error ").append(e.getClass().getSimpleName()).append('\n');
        }

        // ---- MediaController metadata ----
        try {
            MediaController c = findController(ctx, pkg);
            if (c == null) sb.append("META: no controller\n");
            else {
                MediaMetadata md = c.getMetadata();
                if (md == null) sb.append("META: null (Netflix-on-Fire returns null here)\n");
                else {
                    sb.append("META title=").append(md.getString(MediaMetadata.METADATA_KEY_TITLE))
                      .append(" | displayTitle=").append(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
                      .append(" | subtitle=").append(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
                      .append(" | artist=").append(md.getString(MediaMetadata.METADATA_KEY_ARTIST))
                      .append(" | album=").append(md.getString(MediaMetadata.METADATA_KEY_ALBUM))
                      .append(" | duration=").append(md.getLong(MediaMetadata.METADATA_KEY_DURATION))
                      .append(" | mediaId=").append(md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
                      .append('\n');
                }
                sb.append("SESSION pkg=").append(c.getPackageName());
                android.media.session.MediaController.PlaybackInfo pi = c.getPlaybackInfo();
                sb.append(" hasPlaybackInfo=").append(pi != null).append('\n');

                // ---- every other session field that might carry the title ----
                try { sb.append("queueTitle=").append(c.getQueueTitle()).append('\n'); }
                catch (Exception e) { sb.append("queueTitle: err\n"); }

                try {
                    java.util.List<android.media.session.MediaSession.QueueItem> q = c.getQueue();
                    if (q == null) sb.append("queue: null\n");
                    else {
                        sb.append("queue: ").append(q.size()).append(" items\n");
                        int n = 0;
                        for (android.media.session.MediaSession.QueueItem qi : q) {
                            if (n++ > 5) break;
                            android.media.MediaDescription d = qi.getDescription();
                            sb.append("  q[").append(qi.getQueueId()).append("] title=").append(d.getTitle())
                              .append(" sub=").append(d.getSubtitle())
                              .append(" desc=").append(d.getDescription())
                              .append(" id=").append(d.getMediaId()).append('\n');
                        }
                    }
                } catch (Exception e) { sb.append("queue: err\n"); }

                try {
                    android.os.Bundle ex = c.getExtras();
                    if (ex == null) sb.append("extras: null\n");
                    else {
                        sb.append("extras keys: ");
                        for (String k : ex.keySet()) sb.append(k).append("=").append(ex.get(k)).append("; ");
                        sb.append('\n');
                    }
                } catch (Exception e) { sb.append("extras: err\n"); }

                try {
                    android.media.session.PlaybackState ps = c.getPlaybackState();
                    if (ps != null && ps.getExtras() != null) {
                        sb.append("pbState extras: ");
                        for (String k : ps.getExtras().keySet())
                            sb.append(k).append("=").append(ps.getExtras().get(k)).append("; ");
                        sb.append('\n');
                    } else sb.append("pbState extras: none\n");
                } catch (Exception e) { sb.append("pbState extras: err\n"); }

                // sessionInfo is API 29 — reflection since we build against API 23 stubs
                try {
                    Object si = MediaController.class.getMethod("getSessionInfo").invoke(c);
                    sb.append("sessionInfo=").append(si).append('\n');
                } catch (Throwable t) { sb.append("sessionInfo: n/a\n"); }
            }
        } catch (Exception e) {
            sb.append("META: error ").append(e.getClass().getSimpleName()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Find the target app's MediaController (e.g. com.netflix.ninja).
     * Returns null if the exact package has no active session (or permission
     * not granted yet).
     *
     * NOTE (fixed): the earlier version built a `fallback` reference but never
     * returned it — harmless for our use (we always pass an explicit package),
     * but the dead variable is removed to avoid confusion. If you ever want a
     * "first available session" fallback, return `fallback` instead of null.
     */
    public static MediaController findController(android.content.Context ctx, String pkg) {
        try {
            MediaSessionManager msm = (MediaSessionManager)
                ctx.getSystemService("media_session");
            ComponentName cn = new ComponentName(ctx, MediaWatcher.class);
            List<MediaController> sessions = msm.getActiveSessions(cn);
            for (MediaController c : sessions) {
                if (pkg.equals(c.getPackageName())) return c;
            }
            return null;
        } catch (SecurityException e) {
            return null;   // listener not granted yet
        } catch (Exception e) {
            return null;
        }
    }
}
