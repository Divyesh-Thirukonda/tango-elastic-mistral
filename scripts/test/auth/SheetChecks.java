package com.tango.authsmoke;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Exercises modal action ordering and interruption without starting external flows. */
public final class SheetChecks {
    private final Instrumentation test;
    private final Context context;
    private final Activity activity;
    private final JSONObject checks;
    private final Class<?> builderType;
    private final ArrayList<Object> sheets = new ArrayList<>();

    private SheetChecks(Instrumentation test, Context context, Activity activity, JSONObject checks) throws Exception {
        this.test = test; this.context = context; this.activity = activity; this.checks = checks;
        builderType = context.getClassLoader().loadClass("com.tango.phone.TangoSheet$Builder");
    }
    private interface Checked { void run() throws Exception; }
    private interface Condition { boolean ready() throws Exception; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private void main(Checked work) throws Exception {
        Throwable[] error = {null};
        test.runOnMainSync(() -> { try { work.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] instanceof Exception) throw (Exception) error[0];
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private static Object call(Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = target.getClass().getMethod(name, parameters);
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw new AssertionError(error.getCause());
        }
    }
    private static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[]{}); }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private Object builder(String title) throws Exception {
        Object builder = builderType.getConstructor(Activity.class).newInstance(activity);
        return call(builder, "setTitle", new Class<?>[]{String.class}, title);
    }
    private Object show(Object builder) throws Exception {
        Object sheet = call(builder, "create"); sheets.add(sheet); call(sheet, "show"); return sheet;
    }
    private View view(Object sheet) throws Exception { return (View) call(sheet, "getView"); }
    private static Button button(View view, String label) {
        if (view instanceof Button && (label.contentEquals(((Button) view).getText()) || label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), label); if (found != null) return found;
        }
        return null;
    }
    private static ScrollView scroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ScrollView found = scroll(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private void await(Condition condition, String message) throws Exception {
        long until = SystemClock.uptimeMillis() + 4000;
        boolean[] ready = {false};
        while (SystemClock.uptimeMillis() < until) {
            main(() -> ready[0] = condition.ready()); if (ready[0]) return;
            SystemClock.sleep(25);
        }
        throw new AssertionError(message);
    }
    private void layout(Object sheet) throws Exception {
        await(() -> view(sheet).isAttachedToWindow() && ((View) field(sheet, "surface")).getHeight() > 0 && ((View) field(sheet, "surface")).getVisibility() == View.VISIBLE,
            "In-app sheet never acquired a visible measured surface");
        // Transition trajectories are covered by UiStateChecks; settle only the entrance
        // here so callback ordering tests do not depend on device animation speed.
        main(() -> call(sheet, "settleForInterruption"));
    }
    private static Rect visibleBounds(View view) {
        Rect bounds = new Rect(); require(view.getGlobalVisibleRect(bounds), "Expected sheet control is not visible"); return bounds;
    }

    private void completedActionOrdering() throws Exception {
        AtomicInteger actions = new AtomicInteger(), dismissals = new AtomicInteger();
        AtomicBoolean detachedAtAction = new AtomicBoolean(false);
        Object[] active = {null};
        main(() -> {
            Object spec = builder("Apply a setting");
            DialogInterface.OnClickListener action = (dialog, which) -> {
                try { detachedAtAction.set(!view(active[0]).isAttachedToWindow() && !(boolean) call(active[0], "isShowing")); }
                catch (Exception failure) { throw new AssertionError(failure); }
                actions.incrementAndGet();
            };
            call(spec, "setPositiveButton", new Class<?>[]{String.class, DialogInterface.OnClickListener.class}, "Apply fixture", action);
            active[0] = call(spec, "create"); sheets.add(active[0]);
            call(active[0], "setOnDismissListener", new Class<?>[]{DialogInterface.OnDismissListener.class}, (DialogInterface.OnDismissListener) dialog -> dismissals.incrementAndGet());
            call(active[0], "show");
        });
        layout(active[0]);
        main(() -> {
            Button apply = button(view(active[0]), "Apply fixture"); require(apply != null, "Sheet lost its primary action");
            require(apply.performClick(), "Sheet primary action cannot be clicked");
            if (ValueAnimator.areAnimatorsEnabled()) require(actions.get() == 0 && (boolean) call(active[0], "isShowing"), "Sheet committed its action before its exit finished");
            apply.performClick();
        });
        await(() -> !(boolean) call(active[0], "isShowing"), "Action sheet did not finish dismissing");
        require(actions.get() == 1 && dismissals.get() == 1 && detachedAtAction.get(), "Sheet action did not run exactly once after removal");
        main(() -> { call(active[0], "cancel"); call(active[0], "dismissImmediately"); });
        require(actions.get() == 1 && dismissals.get() == 1, "Repeated dismissal delivered callbacks again");
        checks.put("sheetActionCommitsOnceAfterExitAndDetachment", true);
    }

    private void interruptedAction() throws Exception {
        AtomicInteger actions = new AtomicInteger(), dismissals = new AtomicInteger();
        boolean animated = ValueAnimator.areAnimatorsEnabled();
        Object[] active = {null};
        main(() -> {
            Object spec = builder("Leave a flow");
            call(spec, "setPositiveButton", new Class<?>[]{String.class, DialogInterface.OnClickListener.class}, "Continue fixture", (DialogInterface.OnClickListener) (dialog, which) -> actions.incrementAndGet());
            active[0] = call(spec, "create"); sheets.add(active[0]);
            call(active[0], "setOnDismissListener", new Class<?>[]{DialogInterface.OnDismissListener.class}, (DialogInterface.OnDismissListener) dialog -> dismissals.incrementAndGet());
            call(active[0], "show");
        });
        layout(active[0]);
        main(() -> {
            require(button(view(active[0]), "Continue fixture").performClick(), "Interrupted action cannot be clicked");
            call(active[0], "onHostPaused");
            require(!(boolean) call(active[0], "isShowing"), "Exiting sheet remained attached when its host paused");
            require(actions.get() == (animated ? 0 : 1), "Background interruption ran a pending sheet action");
            call(active[0], "onHostResumed");
        });
        SystemClock.sleep(260); test.waitForIdleSync();
        require(actions.get() == (animated ? 0 : 1) && dismissals.get() == 1, "Cancelled sheet exit delivered a late action or duplicate dismissal");
        checks.put("sheetInterruptionNeverRunsDelayedActions", true);
    }

    private void interruptedEditor() throws Exception {
        Object[] active = {null}; EditText[] editor = {null};
        main(() -> {
            editor[0] = new EditText(activity); editor[0].setSingleLine(true); editor[0].setText("A draft across an interruption");
            Object spec = builder("Rename fixture"); call(spec, "setView", new Class<?>[]{View.class}, editor[0]);
            call(spec, "setNegativeButton", new Class<?>[]{String.class, DialogInterface.OnClickListener.class}, "Cancel", null);
            active[0] = show(spec);
        });
        layout(active[0]);
        main(() -> {
            editor[0].setSelection(7);
            call(active[0], "onHostPaused");
            require((boolean) call(active[0], "isShowing") && view(active[0]).isAttachedToWindow(), "Pausing discarded an open editor");
            call(active[0], "onHostResumed");
            require("A draft across an interruption".contentEquals(editor[0].getText()) && editor[0].getSelectionStart() == 7, "Returning to a sheet lost its draft or cursor position");
            call(active[0], "dismissImmediately");
        });
        checks.put("sheetOpenEditorPreservesDraftAndCursorAcrossInterruption", true);
    }

    private void longContentWithKeyboard() throws Exception {
        Object[] active = {null}; EditText[] editor = {null};
        main(() -> {
            editor[0] = new EditText(activity); editor[0].setSingleLine(true); editor[0].setHint("Keep editing here");
            Object spec = builder("Details with an editor");
            call(spec, "setMessage", new Class<?>[]{String.class}, "A longer explanation remains readable without hiding the way out.\n\n".repeat(45));
            call(spec, "setView", new Class<?>[]{View.class}, editor[0]);
            call(spec, "setPositiveButton", new Class<?>[]{String.class, DialogInterface.OnClickListener.class}, "Save fixture", null);
            call(spec, "setNegativeButton", new Class<?>[]{String.class, DialogInterface.OnClickListener.class}, "Cancel", null);
            active[0] = show(spec);
        });
        layout(active[0]);
        main(() -> {
            editor[0].requestFocus();
            ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor[0], InputMethodManager.SHOW_IMPLICIT);
        });
        await(() -> view(active[0]).getRootWindowInsets() != null && view(active[0]).getRootWindowInsets().isVisible(WindowInsets.Type.ime()), "Sheet editor never opened the keyboard");
        // Insets and ScrollView focus relocation finish on subsequent layout frames.
        SystemClock.sleep(200); test.waitForIdleSync();
        main(() -> {
            View overlay = view(active[0]), surface = (View) field(active[0], "surface");
            WindowInsets window = overlay.getRootWindowInsets();
            Insets bars = window.getInsets(WindowInsets.Type.systemBars()), ime = window.getInsets(WindowInsets.Type.ime());
            int available = overlay.getHeight() - bars.top - Math.max(bars.bottom, ime.bottom);
            require(surface.getHeight() <= available + 2, "Long sheet content extends beneath the keyboard or status bar");
            Rect content = visibleBounds(overlay), sheet = visibleBounds(surface);
            require(sheet.bottom <= content.bottom - Math.max(bars.bottom, ime.bottom) + 2, "Sheet actions overlap the keyboard");
            Button close = button(overlay, "Close sheet"), cancel = button(overlay, "Cancel");
            require(close != null && cancel != null, "Long sheet lost a visible escape hatch");
            require(visibleBounds(close).height() == close.getHeight() && visibleBounds(cancel).height() == cancel.getHeight(), "Keyboard clipped Close or Cancel");
            ScrollView body = scroll(surface);
            require(body != null && body.getHeight() > 0 && (body.canScrollVertically(-1) || body.canScrollVertically(1)), "Long sheet body cannot scroll independently of its actions");
            require("Close sheet".contentEquals(close.getContentDescription()), "Icon-only sheet escape has no accessible label");
            require(close.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null), "Accessible Close cannot dismiss the sheet");
        });
        await(() -> !(boolean) call(active[0], "isShowing"), "Accessible Close did not escape a keyboard-open sheet");
        checks.put("sheetLongContentKeepsCloseCancelAndScrollingAboveKeyboard", true);
        checks.put("sheetKeyboardFlowHasAccessibleImmediateEscape", true);
    }

    private void run() throws Exception {
        try {
            completedActionOrdering(); interruptedAction(); interruptedEditor(); longContentWithKeyboard();
        } finally {
            main(() -> {
                for (int i = sheets.size() - 1; i >= 0; i--) call(sheets.get(i), "dismissImmediately");
                ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
            });
            test.waitForIdleSync();
        }
    }
    public static void run(Instrumentation test, Context context, Activity activity, JSONObject checks) throws Exception {
        new SheetChecks(test, context, activity, checks).run();
    }
}
