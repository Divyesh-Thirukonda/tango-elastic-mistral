package com.tango.phone;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User-enabled phone control. All node access happens on Android's main thread. */
public final class PhoneAccessibilityService extends AccessibilityService {
    public static volatile PhoneAccessibilityService instance;
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private long snapshotNumber;
    private long lastVolumeDown;
    private boolean consumeVolumePress;
    private long screenRevision;
    private String snapshotPackage = "";
    private int snapshotWindow = -1;
    private long snapshotTime;
    private final AccessibilityButtonController.AccessibilityButtonCallback buttonCallback =
            new AccessibilityButtonController.AccessibilityButtonCallback() {
                @Override public void onClicked(AccessibilityButtonController controller) { listen(); }
            };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED | AccessibilityEvent.TYPE_VIEW_SCROLLED;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                    | AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON;
            setServiceInfo(info);
        }
        getAccessibilityButtonController().registerAccessibilityButtonCallback(buttonCallback);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // Our status overlay updates must not invalidate screenshots of the controlled app.
        if (getPackageName().equals(asString(event.getPackageName()))) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) screenRevision++;
    }

    @Override public void onInterrupt() { clearTargets(); }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        getAccessibilityButtonController().unregisterAccessibilityButtonCallback(buttonCallback);
        clearTargets();
        super.onDestroy();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN
                || !getSharedPreferences("tango", MODE_PRIVATE).getBoolean("volume_shortcut", true)) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            boolean consumed = consumeVolumePress;
            consumeVolumePress = false;
            return consumed;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (event.getRepeatCount() > 0) return consumeVolumePress;
        long now = SystemClock.uptimeMillis();
        if (lastVolumeDown > 0 && now - lastVolumeDown <= 350) {
            lastVolumeDown = 0;
            consumeVolumePress = true;
            listen();
            return true;
        }
        lastVolumeDown = now;
        consumeVolumePress = false;
        // The first press goes through normally; we consume the second complete key pair.
        return false;
    }

    private void listen() {
        PhoneController.triggerListen(this);
    }

    long revision() { return screenRevision; }

    boolean locked() {
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return (keyguard != null && keyguard.isDeviceLocked())
                || (power != null && !power.isInteractive());
    }

    boolean isSnapshotCurrent() {
        if (SystemClock.uptimeMillis() - snapshotTime > 90000) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            return root.getWindowId() == snapshotWindow
                    && snapshotPackage.equals(asString(root.getPackageName()));
        } finally { root.recycle(); }
    }

    int[] displaySize() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (manager != null) manager.getDefaultDisplay().getRealMetrics(metrics);
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics = getResources().getDisplayMetrics();
        }
        return new int[]{Math.max(1, metrics.widthPixels), Math.max(1, metrics.heightPixels)};
    }

    JSONObject snapshot() throws JSONException {
        clearTargets();
        snapshotNumber++;
        snapshotTime = SystemClock.uptimeMillis();
        int[] size = displaySize();
        JSONObject result = new JSONObject();
        JSONArray nodes = new JSONArray();
        result.put("width", size[0]).put("height", size[1]).put("nodes", nodes);
        result.put("snapshotId", "s" + snapshotNumber);
        result.put("screenRevision", screenRevision);
        result.put("locked", locked());
        result.put("password", false).put("passwordVisible", false);
        if (locked()) {
            snapshotPackage = "";
            snapshotWindow = -1;
            return result.put("packageName", "").put("observationError", "Phone is locked or the display is off. Unlock it to continue.");
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            snapshotPackage = "";
            snapshotWindow = -1;
            return result.put("packageName", "").put("observationError", "No active accessibility window. Unlock the phone and open an app.");
        }
        snapshotPackage = asString(root.getPackageName());
        snapshotWindow = root.getWindowId();
        result.put("packageName", snapshotPackage);
        boolean passwords = false;
        boolean textCropped = false;
        int omittedNodes = 0;
        int treeCharacters = 2;
        int visited = 0;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        try {
            while (!pending.isEmpty() && visited++ < 2500) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    boolean password = node.isPassword();
                    passwords |= password && node.isVisibleToUser();
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    boolean textTruncated = !password && asString(node.getText()).length() > 600;
                    boolean descriptionTruncated = !password && asString(node.getContentDescription()).length() > 300;
                    textCropped |= textTruncated || descriptionTruncated;
                    String text = password ? "[password hidden]" : clip(node.getText(), 600);
                    String description = password ? "" : clip(node.getContentDescription(), 300);
                    boolean useful = !text.isEmpty() || !description.isEmpty() || node.isClickable()
                            || node.isEditable() || node.isScrollable() || node.isLongClickable();
                    boolean exposed = !getPackageName().equals(asString(node.getPackageName()))
                            && node.isVisibleToUser() && useful
                            && bounds.intersects(0, 0, size[0], size[1]);
                    if (exposed && nodes.length() < 300) {
                        String id = "s" + snapshotNumber + ":n" + (nodes.length() + 1);
                        JSONObject entry = new JSONObject();
                        entry.put("id", id).put("text", text).put("description", description)
                                .put("viewId", asString(node.getViewIdResourceName()))
                                .put("className", asString(node.getClassName()))
                                .put("clickable", node.isClickable()).put("editable", node.isEditable())
                                .put("scrollable", node.isScrollable()).put("enabled", node.isEnabled())
                                .put("password", password).put("bounds", new JSONArray(new int[]{
                                        bounds.left, bounds.top, bounds.right, bounds.bottom}));
                        if (textTruncated) entry.put("textTruncated", true);
                        if (descriptionTruncated) entry.put("descriptionTruncated", true);
                        // Leave headroom under the bridge's 400k-character screen-tree limit,
                        // including heavily escaped text or unusually long view identifiers.
                        int characters = entry.toString().length() + 1;
                        if (treeCharacters + characters <= 350000) {
                            treeCharacters += characters;
                            nodes.put(entry);
                            targets.put(id, new Target(node, bounds));
                        } else omittedNodes++;
                    } else if (exposed) omittedNodes++;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            if (!pending.isEmpty() || omittedNodes > 0) result.put("truncated", true);
            if (omittedNodes > 0) result.put("omittedNodes", omittedNodes);
            if (textCropped) result.put("textTruncated", true);
        } finally {
            while (!pending.isEmpty()) pending.removeFirst().recycle();
        }
        result.put("passwordVisible", passwords).put("password", passwords);
        return result;
    }

    /** Reject IDs from another observation and nodes whose visible identity has changed. */
    AccessibilityNodeInfo resolve(String id) {
        Target target = targets.get(id);
        if (target == null) return null;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        boolean sameWindow;
        try {
            sameWindow = root.getWindowId() == snapshotWindow
                    && snapshotPackage.equals(asString(root.getPackageName()));
        } finally { root.recycle(); }
        if (!sameWindow) return null;
        AccessibilityNodeInfo node = target.node;
        if (!node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.equals(target.bounds) || node.getWindowId() != snapshotWindow
                || !target.packageName.equals(asString(node.getPackageName()))
                || !target.className.equals(asString(node.getClassName()))
                || !target.viewId.equals(asString(node.getViewIdResourceName()))
                || !target.text.equals(asString(node.getText()))
                || !target.description.equals(asString(node.getContentDescription()))) return null;
        return node;
    }

    /** Screenshots cover the whole display, so inspect every exposed window for password inputs. */
    boolean hasPasswordWindow() {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) pending.add(root);
                window.recycle();
            }
        }
        if (pending.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) pending.add(root);
        }
        int count = 0;
        try {
            while (!pending.isEmpty() && count++ < 5000) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    if (node.isPassword() && node.isVisibleToUser()) return true;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally { node.recycle(); }
            }
            // If the tree is unusually large, conservatively withhold the screenshot.
            return !pending.isEmpty();
        } finally {
            while (!pending.isEmpty()) pending.removeFirst().recycle();
        }
    }

    private void clearTargets() {
        for (Target target : targets.values()) target.node.recycle();
        targets.clear();
    }

    private static String asString(CharSequence value) { return value == null ? "" : value.toString(); }
    private static String clip(CharSequence value, int max) {
        String string = asString(value);
        return string.length() <= max ? string : string.substring(0, max) + "…";
    }

    private static final class Target {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final String packageName, className, viewId, text, description;
        Target(AccessibilityNodeInfo original, Rect bounds) {
            node = AccessibilityNodeInfo.obtain(original);
            this.bounds = new Rect(bounds);
            packageName = asString(original.getPackageName());
            className = asString(original.getClassName());
            viewId = asString(original.getViewIdResourceName());
            text = asString(original.getText());
            description = asString(original.getContentDescription());
        }
    }
}
