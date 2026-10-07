package com.tango.phone;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small native tool API used by the agent. Blocking methods require a worker thread. */
public final class PhoneController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Executor SCREEN_CALLBACK = Executors.newSingleThreadExecutor(command -> new Thread(command,"tango-screenshot"));
    private static final Object APPS_LOCK=new Object(), CAPTURE_LOCK=new Object();
    private static final long APPS_CACHE_MS=5*60*1000L, CAPTURE_INTERVAL_MS=350;
    private static String cachedApps="";
    private static long appsCachedAt,lastCaptureRequest;
    private static boolean appChangesRegistered;
    private static final BroadcastReceiver APP_CHANGES=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) { synchronized(APPS_LOCK) { cachedApps=""; appsCachedAt=0; } }
    };

    private PhoneController() {}

    public static void triggerListen(Context context) {
        try {
            context.startActivity(listenIntent(context));
        } catch (RuntimeException ignored) {
            // OEM background-launch restrictions may require using the assistant role or tile.
        }
    }

    static Intent listenIntent(Context context) {
        return new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("listen", true);
    }

    /** Wait for a short quiet accessibility interval; the next observation always takes fresh nodes. */
    public static long awaitSettled(String actionType,int ticket) {
        long started=SystemClock.uptimeMillis();
        if(Looper.myLooper()==Looper.getMainLooper() || !AgentService.canAct(ticket)) return 0;
        PhoneAccessibilityService service=PhoneAccessibilityService.instance;
        if(service==null || actionType.equals("wait")) return 0;
        boolean navigation=Set.of("start","home","back","launch","open_url").contains(actionType);
        long minimum=navigation?180:actionType.equals("failed")?40:actionType.equals("type")?40:80;
        long quiet=navigation?140:actionType.equals("failed")?60:actionType.equals("type")?80:120;
        long maximum=navigation?1200:actionType.equals("failed")?300:800;
        long lastChanged=started;
        try {
            long revision=onMain(service::revision,Math.min(150,maximum));
            while(AgentService.canAct(ticket) && PhoneAccessibilityService.instance==service) {
                long now=SystemClock.uptimeMillis();
                if(now-started>=maximum) break;
                long nextRevision=onMain(service::revision,Math.min(150,Math.max(1,maximum-(now-started))));
                if(nextRevision!=revision) { revision=nextRevision; lastChanged=now; }
                if(now-started>=minimum && now-lastChanged>=quiet) break;
                Thread.sleep(Math.min(35,Math.max(1,maximum-(now-started))));
            }
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch(Exception ignored) { /* Observation reports disconnection or a stalled main thread. */ }
        return SystemClock.uptimeMillis()-started;
    }

    /** Fresh tree for guarded plan steps; vision is fetched again whenever replanning is needed. */
    public static JSONObject observeNodes() {
        if(Looper.myLooper()==Looper.getMainLooper()) return errorObservation("Observation needs a worker thread.");
        PhoneAccessibilityService service=PhoneAccessibilityService.instance;
        if(service==null) return errorObservation("Accessibility disconnected.");
        try { return onMain(() -> {JSONObject obs=service.snapshot();if(service.hasPasswordWindow())obs.put("password",true);return obs;}); }
        catch(Exception error) {return errorObservation("Could not read the current screen.");}
    }

    public static JSONObject observe(Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return errorObservation("observe must be called from a worker thread.");
        }
        PhoneAccessibilityService service = PhoneAccessibilityService.instance;
        if (service == null) return errorObservation("Enable Tango's accessibility service in Android Settings.");
        try {
            awaitCaptureInterval();
            JSONObject observation = onMain(() -> service.snapshot());
            if (observation.optBoolean("locked")) {
                return put(observation, "screenshotError", "Unlock the phone before screen capture.");
            }
            if (onMain(() -> service.hasPasswordWindow())) {
                observation.put("password", true).put("passwordVisible", true);
                return put(observation, "screenshotError", "Screenshot withheld while a password field is visible. Complete sign-in yourself.");
            }
            // Use the revision captured with the nodes, before worker-thread app enumeration.
            final long revision = observation.optLong("screenRevision", -1);
            CountDownLatch completed = new CountDownLatch(1);
            ScreenshotCapture capture = new ScreenshotCapture();
            onMain(() -> {
                synchronized(CAPTURE_LOCK) { lastCaptureRequest=SystemClock.uptimeMillis(); }
                service.takeScreenshot(Display.DEFAULT_DISPLAY, SCREEN_CALLBACK,
                        new AccessibilityService.TakeScreenshotCallback() {
                            @Override public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                                Bitmap hardware = null;
                                Bitmap software = null;
                                Bitmap scaled = null;
                                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                                try {
                                    // Recheck after capture; discard frames taken during a changing/secret screen.
                                    boolean safe = onMain(() -> {
                                        capture.locked = service.locked();
                                        capture.password = service.hasPasswordWindow();
                                        return PhoneAccessibilityService.instance == service
                                                && !capture.locked && !capture.password
                                                && service.isSnapshotCurrent() && service.revision() == revision;
                                    });
                                    if (!safe) {
                                        capture.error = "Screen changed during capture, or a password field appeared. Observe again.";
                                        return;
                                    }
                                    hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                                    if (hardware == null) {
                                        capture.error = "Android returned an unreadable screenshot.";
                                        return;
                                    }
                                    software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                    if (software == null) {
                                        capture.error = "Could not copy screenshot pixels.";
                                        return;
                                    }
                                    double scale = Math.min(1.0, 1280.0 / Math.max(software.getWidth(), software.getHeight()));
                                    int width = Math.max(1, (int) Math.round(software.getWidth() * scale));
                                    int height = Math.max(1, (int) Math.round(software.getHeight() * scale));
                                    scaled = Bitmap.createScaledBitmap(software, width, height, true);
                                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, 75, bytes)) {
                                        capture.error = "Screenshot encoding failed.";
                                        return;
                                    }
                                    capture.width = width;
                                    capture.height = height;
                                    capture.base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                                } catch (Exception error) {
                                    capture.error = "Screenshot unavailable: " + error.getClass().getSimpleName();
                                } finally {
                                    if (scaled != null && scaled != software) scaled.recycle();
                                    if (software != null) software.recycle();
                                    if (hardware != null) hardware.recycle();
                                    buffer.close();
                                    completed.countDown();
                                }
                            }

                            @Override public void onFailure(int errorCode) {
                                capture.error = "Android screenshot error " + errorCode
                                        + " (secure windows cannot be captured; wait briefly and observe again).";
                                completed.countDown();
                            }
                        });
                return null;
            });
            // First inventory enumeration and screenshot encoding can run concurrently.
            observation.put("installedApps",installedApps(context));
            if (!completed.await(5000, TimeUnit.MILLISECONDS)) {
                return put(observation, "screenshotError", "Screenshot timed out; accessible screen text is still available.");
            }
            if (capture.password) observation.put("password", true).put("passwordVisible", true);
            if (capture.locked) observation.put("locked", true);
            if (capture.base64 != null) {
                observation.put("screenshot", capture.base64).put("screenshotMimeType", "image/jpeg")
                        .put("screenshotWidth", capture.width).put("screenshotHeight", capture.height);
            } else observation.put("screenshotError", capture.error == null ? "Screenshot unavailable." : capture.error);
            return observation;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return errorObservation("Observation interrupted.");
        } catch (Exception error) {
            return errorObservation("Could not observe the phone: " + error.getClass().getSimpleName());
        }
    }

    public static JSONObject execute(Context context, JSONObject action) {
        if (Looper.myLooper() == Looper.getMainLooper()) return result(false, "execute must be called from a worker thread.");
        if (action == null) return result(false, "Missing action.");
        if (!canAct(action)) return result(false, "Task was stopped or paused. No phone action was dispatched.");
        String verb = action.optString("type", action.optString("action", "")).toLowerCase(java.util.Locale.ROOT);
        if (verb.equals("wait")) {
            long milliseconds = boundedDuration(action, 1000, 5000);
            try {
                long deadline = android.os.SystemClock.uptimeMillis() + milliseconds;
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    if (!canAct(action)) return result(false, "Task was stopped or paused.");
                    Thread.sleep(Math.min(100, Math.max(1, deadline - android.os.SystemClock.uptimeMillis())));
                }
                if (!canAct(action)) return result(false, "Task was stopped or paused.");
                return result(true, "Waited " + milliseconds + " ms.");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return result(false, "Wait interrupted.");
            }
        }
        PhoneAccessibilityService service = PhoneAccessibilityService.instance;
        if (service == null) return result(false, "Enable Tango's accessibility service before controlling the phone.");
        try {
            ActionOutcome outcome = onMain(() -> executeOnMain(context, service, verb, action));
            if (outcome.gesture == null) return outcome.result;
            if (!outcome.gesture.await(5000, TimeUnit.MILLISECONDS)) return result(false, "Gesture timed out; observe before retrying.");
            return outcome.result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return result(false, "Phone action interrupted.");
        } catch (Exception error) {
            return result(false, "Phone action failed: " + error.getClass().getSimpleName() + ". Observe again before retrying.");
        }
    }

    private static ActionOutcome executeOnMain(Context context, PhoneAccessibilityService service,
                                               String verb, JSONObject action) throws JSONException {
        if (!canAct(action)) return stopped();
        if (PhoneAccessibilityService.instance != service) return immediate(false, "Accessibility service disconnected.");
        if (service.locked()) return immediate(false, "Unlock the phone to continue.");
        switch (verb) {
            case "back":
                if (!canAct(action)) return stopped();
                return immediate(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK), "Back requested.");
            case "home":
                if (!canAct(action)) return stopped();
                return immediate(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME), "Home requested.");
            case "launch": {
                String packageName = action.optString("packageName", action.optString("package", ""));
                if (!packageName.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")) {
                    return immediate(false, "Provide an installed Android package name.");
                }
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent == null) return immediate(false, "App is unavailable or has no launcher: " + packageName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!canAct(action)) return stopped();
                service.startActivity(intent);
                return immediate(true, "Launched " + packageName + ".");
            }
            case "open_url": {
                Uri uri = Uri.parse(action.optString("url", ""));
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getHost().isEmpty()) {
                    return immediate(false, "Only valid http or https URLs can be opened.");
                }
                if (!canAct(action)) return stopped();
                service.startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return immediate(true, "Opened webpage.");
            }
            case "tap": {
                String targetId = target(action);
                if (!targetId.isEmpty()) {
                    AccessibilityNodeInfo node = service.resolve(targetId);
                    if (node == null) return stale();
                    if (!canAct(action)) return stopped();
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return immediate(true, "Tapped target.");
                    AccessibilityNodeInfo parent = node.getParent();
                    int depth = 0;
                    while (parent != null && depth++ < 6) {
                        AccessibilityNodeInfo next = null;
                        try {
                            if (!canAct(action)) return stopped();
                            if (parent.isVisibleToUser() && parent.isEnabled() && parent.isClickable()
                                    && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                return immediate(true, "Tapped target's clickable container.");
                            }
                            next = parent.getParent();
                        } finally { parent.recycle(); }
                        parent = next;
                    }
                    if (parent != null) parent.recycle();
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    int[] size = service.displaySize();
                    if (bounds.isEmpty() || !bounds.intersect(0, 0, size[0], size[1])) {
                        return immediate(false, "Target is outside the display.");
                    }
                    return gesture(service, action, bounds.exactCenterX(), bounds.exactCenterY(),
                            bounds.exactCenterX(), bounds.exactCenterY(), 80);
                }
                if (!service.isSnapshotCurrent()) return stale();
                int[] size = service.displaySize();
                float x = coordinate(action, "x", size[0]);
                float y = coordinate(action, "y", size[1]);
                return gesture(service, action, x, y, x, y, 80);
            }
            case "type": {
                AccessibilityNodeInfo node = service.resolve(target(action));
                if (node == null) return stale();
                if (!node.isEditable()) return immediate(false, "Target is not an editable text field.");
                if (node.isPassword()) return immediate(false, "Enter passwords yourself; Tango does not type into password fields.");
                String text = action.optString("text", "");
                if (text.length() > 12000) return immediate(false, "Text exceeds the 12,000-character limit.");
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                if (!canAct(action)) return stopped();
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                return immediate(ok, ok ? "Replaced text in target field." : "App rejected text entry. Observe and choose an editable field.");
            }
            case "swipe": {
                if (!service.isSnapshotCurrent()) return stale();
                int[] size = service.displaySize();
                String xStart = action.has("x1") ? "x1" : "x";
                String yStart = action.has("y1") ? "y1" : "y";
                return gesture(service, action, coordinate(action, xStart, size[0]), coordinate(action, yStart, size[1]),
                        coordinate(action, "x2", size[0]), coordinate(action, "y2", size[1]),
                        boundedDuration(action, 400, 2500));
            }
            case "scroll": {
                String direction = action.optString("direction", "down").toLowerCase(java.util.Locale.ROOT);
                int scrollAction;
                switch (direction) {
                    case "up": scrollAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(); break;
                    case "down": scrollAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(); break;
                    case "left": scrollAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId(); break;
                    case "right": scrollAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId(); break;
                    default: return immediate(false, "Scroll direction must be up, down, left, or right.");
                }
                String targetId = target(action);
                Rect bounds = new Rect();
                if (!targetId.isEmpty()) {
                    AccessibilityNodeInfo node = service.resolve(targetId);
                    if (node == null) return stale();
                    if (!canAct(action)) return stopped();
                    if (node.performAction(scrollAction)) return immediate(true, "Scrolled " + direction + ".");
                    int fallback = direction.equals("up") || direction.equals("left")
                            ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                    if (!canAct(action)) return stopped();
                    if (node.performAction(fallback)) return immediate(true, "Scrolled " + direction + ".");
                    node.getBoundsInScreen(bounds);
                } else {
                    if (!service.isSnapshotCurrent()) return stale();
                    int[] size = service.displaySize();
                    bounds.set(0, 0, size[0], size[1]);
                }
                int[] size = service.displaySize();
                if (bounds.isEmpty() || !bounds.intersect(0, 0, size[0], size[1])) return immediate(false, "Scroll area is outside the display.");
                float cx = bounds.exactCenterX(), cy = bounds.exactCenterY();
                float left = bounds.left + bounds.width() * .25f, right = bounds.left + bounds.width() * .75f;
                float top = bounds.top + bounds.height() * .25f, bottom = bounds.top + bounds.height() * .75f;
                if (direction.equals("down")) return gesture(service, action, cx, bottom, cx, top, 400);
                if (direction.equals("up")) return gesture(service, action, cx, top, cx, bottom, 400);
                if (direction.equals("right")) return gesture(service, action, right, cy, left, cy, 400);
                return gesture(service, action, left, cy, right, cy, 400);
            }
            default: return immediate(false, "Unknown phone action: " + verb);
        }
    }

    private static String target(JSONObject action) {
        String target = action.optString("target", action.optString("id", ""));
        return target.equals("null") ? "" : target;
    }

    /** Launcher query is visible through the manifest's MAIN/LAUNCHER package-visibility declaration. */
    private static JSONArray installedApps(Context context) throws JSONException {
        synchronized(APPS_LOCK) {
            registerAppChanges(context);
            if(!cachedApps.isBlank() && SystemClock.uptimeMillis()-appsCachedAt<APPS_CACHE_MS) return new JSONArray(cachedApps);
            JSONArray apps=queryInstalledApps(context);
            cachedApps=apps.toString(); appsCachedAt=SystemClock.uptimeMillis();
            return apps;
        }
    }

    private static void registerAppChanges(Context context) {
        if(appChangesRegistered) return;
        try {
            IntentFilter filter=new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED); filter.addAction(Intent.ACTION_PACKAGE_CHANGED); filter.addDataScheme("package");
            Context application=context.getApplicationContext();
            if(application==null)application=context;
            if(Build.VERSION.SDK_INT>=33)application.registerReceiver(APP_CHANGES,filter,Context.RECEIVER_NOT_EXPORTED);
            else application.registerReceiver(APP_CHANGES,filter);
            appChangesRegistered=true;
        } catch(RuntimeException ignored) { /* The time limit still refreshes package inventory. */ }
    }

    private static JSONArray queryInstalledApps(Context context) throws JSONException {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppEntry> available = new ArrayList<>();
        for(ResolveInfo app:context.getPackageManager().queryIntentActivities(launcher,0)) {
            if(app.activityInfo==null || !app.activityInfo.enabled)continue;
            String name=String.valueOf(app.loadLabel(context.getPackageManager()));
            if(name.length()>80)name=name.substring(0,80);
            available.add(new AppEntry(name,app.activityInfo.packageName));
        }
        available.sort((left,right) -> left.name.compareToIgnoreCase(right.name));
        Set<String> seen = new HashSet<>();
        JSONArray apps = new JSONArray();
        for (AppEntry app : available) {
            String packageName = app.packageName;
            if (packageName.equals(context.getPackageName()) || !seen.add(packageName)) continue;
            apps.put(new JSONObject().put("name", app.name).put("packageName", packageName));
            if (apps.length() >= 150) break;
        }
        return apps;
    }

    private static void awaitCaptureInterval() throws InterruptedException {
        long remaining;
        synchronized(CAPTURE_LOCK) { remaining=lastCaptureRequest+CAPTURE_INTERVAL_MS-SystemClock.uptimeMillis(); }
        if(remaining>0)Thread.sleep(Math.min(CAPTURE_INTERVAL_MS,remaining));
    }

    private static float coordinate(JSONObject action, String key, int dimension) throws JSONException {
        double value = action.getDouble(key);
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new JSONException("Coordinates must be normalized between 0 and 1.");
        return (float) Math.min(dimension - 1, Math.max(0, value * dimension));
    }

    private static long boundedDuration(JSONObject action, long fallback, long max) {
        long duration = action.optLong("durationMs", action.optLong("duration_ms", action.optLong("milliseconds", fallback)));
        return Math.max(1, Math.min(max, duration));
    }

    private static ActionOutcome gesture(PhoneAccessibilityService service, JSONObject action, float x1, float y1,
                                         float x2, float y2, long duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) path.lineTo(x2, y2);
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        ActionOutcome outcome = new ActionOutcome();
        outcome.gesture = new CountDownLatch(1);
        // Guard at the final Android dispatch, after main-thread queuing and path construction.
        if (!canAct(action)) return stopped();
        boolean accepted = service.dispatchGesture(description, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                outcome.result = result(true, "Gesture completed.");
                outcome.gesture.countDown();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                outcome.result = result(false, "Gesture was cancelled; observe before retrying.");
                outcome.gesture.countDown();
            }
        }, MAIN);
        if (!accepted) {
            outcome.result = result(false, "Android rejected the gesture. Check Tango accessibility permissions.");
            outcome.gesture.countDown();
        }
        return outcome;
    }

    private static <T> T onMain(Callable<T> callable) throws Exception {
        return onMain(callable,5000);
    }

    private static <T> T onMain(Callable<T> callable,long timeoutMillis) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return callable.call();
        FutureTask<T> task = new FutureTask<>(callable);
        MAIN.post(task);
        try { return task.get(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (Exception error) {
            task.cancel(false);
            throw error;
        }
    }

    private static ActionOutcome stale() { return immediate(false, "Target or screen changed. Observe again before choosing the next action."); }
    private static ActionOutcome stopped() { return immediate(false, "Task was stopped or paused. No phone action was dispatched."); }
    private static boolean canAct(JSONObject action) {
        return action.has("_generation") && AgentService.canAct(action.optInt("_generation", -1));
    }
    private static ActionOutcome immediate(boolean ok, String message) {
        ActionOutcome outcome = new ActionOutcome();
        outcome.result = result(ok, message);
        return outcome;
    }
    private static JSONObject result(boolean ok, String message) {
        JSONObject result = new JSONObject();
        put(result, "ok", ok);
        return put(result, "message", message);
    }
    private static JSONObject errorObservation(String message) {
        JSONObject result = new JSONObject();
        put(result, "nodes", new org.json.JSONArray());
        put(result, "packageName", "");
        return put(result, "observationError", message);
    }
    private static JSONObject put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException ignored) { }
        return object;
    }
    private static final class ScreenshotCapture {
        volatile String base64, error;
        volatile int width, height;
        volatile boolean password, locked;
    }
    private static final class AppEntry {
        final String name,packageName;
        AppEntry(String name,String packageName) { this.name=name; this.packageName=packageName; }
    }
    private static final class ActionOutcome {
        volatile JSONObject result;
        CountDownLatch gesture;
    }
}
