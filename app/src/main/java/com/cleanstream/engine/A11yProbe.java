package com.cleanstream.engine;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility node-text probe. FLAG_SECURE blocks pixel capture but NOT the
 * accessibility node tree (screen readers must keep working), so this can read
 * the text of Netflix's UI (title / episode on a detail page) even though the
 * screen captures black.
 *
 * On demand (console DUMP command), walks the active window's node tree and
 * returns every text + contentDescription string, with the source package.
 * This confirms whether the Netflix title is present in the tree at all.
 *
 *
 * adb shell settings put secure enabled_accessibility_services
 * com.cleanstream.engine/com.cleanstream.engine.A11yProbe
 * adb shell settings put secure accessibility_enabled 1
 */
public class A11yProbe extends AccessibilityService {

    private static volatile A11yProbe instance;
    public static A11yProbe get() { return instance; }

    public volatile String lastForegroundPkg = "(none)";

    // ring buffer of spoken/announced event text (what Netflix tells a screen reader)
    private final java.util.ArrayDeque<String> eventLog = new java.util.ArrayDeque<String>();
    private volatile String lastLogged = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        EngineService svc = EngineService.get();
        if (svc != null) svc.log("A11yProbe connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getPackageName() != null
            && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastForegroundPkg = event.getPackageName().toString();
        }
        // Capture any text/description the app announces (focus, selection, etc.).
        // This is the screen-reader channel — present even when the static node
        // tree has no text (surface-rendered TV UIs still announce focus).
        StringBuilder t = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence c : texts) {
                if (c != null && c.length() > 0) t.append(c).append(" | ");
            }
        }
        CharSequence desc = event.getContentDescription();
        if (desc != null && desc.length() > 0) t.append("desc=").append(desc);
        if (t.length() == 0) return;
        String pkg = event.getPackageName() == null ? "?" : event.getPackageName().toString();
        String line = eventTypeName(event.getEventType()) + " [" + pkg + "] " + t;
        if (line.equals(lastLogged)) return;   // dedupe repeats
        lastLogged = line;
        synchronized (eventLog) {
            eventLog.add(line);
            while (eventLog.size() > 120) eventLog.poll();
        }
    }

    public String eventLog() {
        synchronized (eventLog) {
            if (eventLog.isEmpty()) return "no event text captured yet (navigate the app with the remote first)";
            StringBuilder sb = new StringBuilder();
            for (String l : eventLog) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    public void clearEventLog() {
        synchronized (eventLog) { eventLog.clear(); }
        lastLogged = "";
    }

    private String eventTypeName(int t) {
        switch (t) {
            case AccessibilityEvent.TYPE_VIEW_FOCUSED: return "FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_SELECTED: return "SELECTED";
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: return "A11Y_FOCUSED";
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: return "WIN_STATE";
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: return "WIN_CONTENT";
            case AccessibilityEvent.TYPE_ANNOUNCEMENT: return "ANNOUNCE";
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: return "TEXT_CHANGED";
            default: return "type" + t;
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private int totalNodes;
    private int textNodes;

    /** Walk the active window and return all text
     *  plus node counts and a window enumeration so we can tell "empty tree
     *  (surface-rendered UI)" apart from "tree present but no text". */
    public String dump() {
        StringBuilder sb = new StringBuilder();

        // enumerate all windows the service can see
        try {
            List<android.view.accessibility.AccessibilityWindowInfo> wins = getWindows();
            sb.append("windows: ").append(wins == null ? 0 : wins.size());
            if (wins != null) {
                for (android.view.accessibility.AccessibilityWindowInfo win : wins) {
                    AccessibilityNodeInfo wr = null;
                    try { wr = win.getRoot(); } catch (Exception ignored) {}
                    sb.append(" | type=").append(win.getType())
                      .append(" pkg=").append(wr != null ? wr.getPackageName() : "?")
                      .append(" children=").append(wr != null ? wr.getChildCount() : -1);
                    if (wr != null) try { wr.recycle(); } catch (Exception ignored) {}
                }
            }
            sb.append('\n');
        } catch (Exception e) {
            sb.append("windows: err ").append(e.getClass().getSimpleName()).append('\n');
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return sb.append("no active-window root (grant enabled? focused?)").toString();

        totalNodes = 0; textNodes = 0;
        List<String> lines = new ArrayList<String>();
        collect(root, 0, lines);
        sb.append("activeRoot pkg=").append(root.getPackageName())
          .append(" lastFg=").append(lastForegroundPkg)
          .append(" rootChildren=").append(root.getChildCount())
          .append(" totalNodes=").append(totalNodes)
          .append(" textNodes=").append(textNodes).append('\n');
        int shown = 0;
        for (String l : lines) {
            sb.append(l).append('\n');
            if (++shown > 200) { sb.append("...(truncated)\n"); break; }
        }
        try { root.recycle(); } catch (Exception ignored) {}
        return sb.toString();
    }

    private void collect(AccessibilityNodeInfo node, int depth, List<String> out) {
        if (node == null) return;
        totalNodes++;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if ((text != null && text.length() > 0) || (desc != null && desc.length() > 0)) {
            textNodes++;
            StringBuilder ind = new StringBuilder();
            for (int i = 0; i < depth && i < 12; i++) ind.append("  ");
            out.add(ind + "[" + safe(node.getClassName()) + "] "
                + (text != null && text.length() > 0 ? "text=\"" + text + "\" " : "")
                + (desc != null && desc.length() > 0 ? "desc=\"" + desc + "\"" : ""));
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { collect(child, depth + 1, out); try { child.recycle(); } catch (Exception ignored) {} }
        }
    }

    private String safe(CharSequence c) {
        if (c == null) return "?";
        String s = c.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }
}
