package com.tango.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** A modal surface within Tango's own view hierarchy, with interruptible motion. */
public final class TangoSheet implements DialogInterface {
    private static final int SURFACE = 0xFF202020;
    private static final int TEXT = 0xFFF5F5F5;
    private static final int MUTED = 0xFFB2B2B2;
    private static final PathInterpolator ENTER = new PathInterpolator(.22f, 1f, .36f, 1f);
    private static final PathInterpolator EXIT = new PathInterpolator(.4f, 0f, 1f, 1f);
    private final Activity activity;
    private final Builder spec;
    private final List<View> hiddenSiblings = new ArrayList<>();
    private final List<Integer> previousImportance = new ArrayList<>();
    private FrameLayout host, overlay;
    private View scrim;
    private SheetLayout surface;
    private View previousFocus;
    private ValueAnimator animator;
    private OnDismissListener dismissListener;
    private Runnable actionAfterDismiss;
    private boolean showing, exiting, hostPaused, dismissDelivered, accessibilityAnnounced;
    private long shownAt;
    private float dragStartY, dragStartOffset;
    private VelocityTracker velocity;

    public static final class Builder {
        private final Activity activity;
        private String title = "", message = "";
        private View view;
        private String[] items;
        private OnClickListener itemClick;
        private String positive, negative, neutral;
        private boolean destructivePositive;
        private OnClickListener positiveClick, negativeClick, neutralClick;

        public Builder(Activity activity) { this.activity = activity; }
        public Builder setTitle(String title) { this.title = title == null ? "" : title; return this; }
        public Builder setMessage(String message) { this.message = message == null ? "" : message; return this; }
        public Builder setView(View view) { this.view = view; return this; }
        public Builder setItems(String[] items, OnClickListener listener) { this.items = items == null ? null : items.clone(); this.itemClick = listener; return this; }
        public Builder setPositiveButton(String label, OnClickListener listener) { positive = label; positiveClick = listener; destructivePositive = false; return this; }
        public Builder setDestructiveButton(String label, OnClickListener listener) { positive = label; positiveClick = listener; destructivePositive = true; return this; }
        public Builder setNegativeButton(String label, OnClickListener listener) { negative = label; negativeClick = listener; return this; }
        public Builder setNeutralButton(String label, OnClickListener listener) { neutral = label; neutralClick = listener; return this; }
        public TangoSheet create() { return new TangoSheet(this); }
    }

    private TangoSheet(Builder spec) { this.activity = spec.activity; this.spec = spec; }
    public void setOnDismissListener(OnDismissListener listener) { dismissListener = listener; }
    public boolean isShowing() { return showing; }
    /** The in-app surface, useful for focus and UI inspection without a separate window. */
    public View getView() { return overlay; }

    public void show() {
        if (showing || activity.isFinishing() || activity.isDestroyed()) return;
        host = activity.findViewById(android.R.id.content);
        if (host == null) return;
        showing = true; exiting = false; dismissDelivered = false; hostPaused = false; accessibilityAnnounced = false; shownAt = System.nanoTime();
        previousFocus = activity.getCurrentFocus();
        if (previousFocus != null) {
            if (findInput(spec.view) == null) ((InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(previousFocus.getWindowToken(), 0);
            previousFocus.clearFocus();
        }
        for (int i = 0; i < host.getChildCount(); i++) {
            View sibling = host.getChildAt(i);
            hiddenSiblings.add(sibling); previousImportance.add(sibling.getImportantForAccessibility());
            sibling.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        overlay = new FrameLayout(activity); overlay.setTag(this);
        overlay.setClickable(true); overlay.setFocusableInTouchMode(true);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        overlay.setAccessibilityPaneTitle(spec.title);
        overlay.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsets.Type.ime());
            // The scrim remains edge-to-edge; only the surface avoids bars and the keyboard.
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
            int bottom = Math.max(bars.bottom, ime.bottom);
            if (params.bottomMargin != bottom || params.topMargin != bars.top + dp(10)) {
                params.bottomMargin = bottom; params.topMargin = bars.top + dp(10);
                surface.setLayoutParams(params);
            }
            surface.requestLayout();
            return insets;
        });
        scrim = new View(activity); scrim.setBackgroundColor(0x99000000); scrim.setAlpha(0);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(v -> cancel());
        overlay.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        surface = buildSurface();
        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        // A restrained maximum width keeps the same composition on tablets and landscape.
        placement.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        overlay.addView(surface, placement);
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.requestFocus(); overlay.requestApplyInsets();
        surface.setVisibility(View.INVISIBLE);
        notifyAccessibilityHierarchy();
        surface.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                surface.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!showing || exiting) return true;
                surface.setVisibility(View.VISIBLE);
                if (hostPaused || !ValueAnimator.areAnimatorsEnabled()) {
                    surface.setTranslationY(0); scrim.setAlpha(1); focusInput(); notifyAccessibleSurface();
                } else {
                    surface.setTranslationY(surface.getHeight() + dp(16));
                    animate(false);
                }
                return true;
            }
        });
    }

    private SheetLayout buildSurface() {
        SheetLayout layout = new SheetLayout(); layout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable(); background.setColor(SURFACE);
        background.setCornerRadii(new float[]{dp(30), dp(30), dp(30), dp(30), 0, 0, 0, 0});
        background.setStroke(dp(1), 0xFF373737); layout.setBackground(background);
        layout.setElevation(dp(24)); layout.setClipToOutline(true); layout.setClickable(true);

        FrameLayout handleTarget = new FrameLayout(activity);
        handleTarget.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        View handle = new View(activity); handle.setBackground(shape(0xFF666666, 3));
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(dp(34), dp(4), Gravity.CENTER);
        handleTarget.addView(handle, handleParams);
        layout.addView(handleTarget, new LinearLayout.LayoutParams(-1, dp(24)));
        handleTarget.setOnTouchListener(this::dragHandle);

        LinearLayout header = new LinearLayout(activity); header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), 0, dp(12), dp(8));
        TextView title = label(spec.title, 21, TEXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = new Button(activity); close.setText("×"); close.setTextSize(27); close.setAllCaps(false);
        close.setTextColor(MUTED); close.setPadding(0, 0, 0, dp(2)); close.setMinWidth(0); close.setMinimumWidth(0);
        close.setMinHeight(0); close.setMinimumHeight(0); close.setBackground(ripple(0xFF303030, 24));
        close.setContentDescription("Close sheet"); feedback(close);
        close.setOnClickListener(v -> cancel()); header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        layout.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll;
        if (spec.view instanceof ScrollView && spec.message.isBlank() && (spec.items == null || spec.items.length == 0)) {
            scroll = (ScrollView) spec.view; detach(scroll);
        } else {
            scroll = new ScrollView(activity);
            LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(24), 0, dp(24), dp(8));
            if (!spec.message.isBlank()) {
                TextView message = label(spec.message, 16, MUTED); message.setLineSpacing(dp(4), 1); message.setTextIsSelectable(true);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(18);
                body.addView(message, p);
            }
            if (spec.view != null) {
                detach(spec.view);
                if (spec.view instanceof EditText) {
                    EditText input = (EditText) spec.view;
                    input.setBackground(ripple(0xFF2D2D2D, 15)); input.setTextColor(TEXT);
                    input.setHintTextColor(0xFF929292); input.setPadding(dp(16), dp(14), dp(16), dp(14));
                    input.setSelectAllOnFocus(false);
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12);
                body.addView(spec.view, p);
            }
            if (spec.items != null) for (int i = 0; i < spec.items.length; i++) {
                final int index = i;
                Button row = actionButton(spec.items[i], false);
                row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(12), dp(16), dp(12));
                row.setBackground(ripple(Color.TRANSPARENT, 14));
                if (destructive(spec.items[i])) row.setTextColor(0xFFFF9696);
                row.setOnClickListener(v -> choose(spec.itemClick, index, v));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(3);
                body.addView(row, p);
            }
            scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        }
        scroll.setClipToPadding(false); scroll.setFillViewport(false); scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setVerticalScrollBarEnabled(false);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));

        if (spec.positive != null || spec.neutral != null || spec.negative != null) {
            LinearLayout actions = new LinearLayout(activity); actions.setOrientation(LinearLayout.VERTICAL);
            actions.setPadding(dp(24), dp(8), dp(24), dp(16));
            addAction(actions, spec.positive, spec.positiveClick, BUTTON_POSITIVE, true);
            addAction(actions, spec.neutral, spec.neutralClick, BUTTON_NEUTRAL, false);
            addAction(actions, spec.negative, spec.negativeClick, BUTTON_NEGATIVE, false);
            layout.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        } else layout.setPadding(0, 0, 0, dp(16));
        return layout;
    }

    private void addAction(LinearLayout parent, String text, OnClickListener listener, int which, boolean primary) {
        if (text == null) return;
        Button button = actionButton(text, primary);
        if (primary && (spec.destructivePositive || destructive(text))) { button.setBackground(ripple(0xFF442626, 16)); button.setTextColor(0xFFFFA6A6); }
        button.setOnClickListener(v -> choose(listener, which, v));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(6);
        parent.addView(button, p);
    }
    private Button actionButton(String text, boolean primary) {
        Button button = new Button(activity); button.setText(text); button.setAllCaps(false);
        button.setTextSize(16); button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? Color.BLACK : TEXT); button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52)); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setBackground(ripple(primary ? 0xFFF3F3F3 : 0xFF2C2C2C, 16)); feedback(button); return button;
    }
    private void feedback(View view) { UiMotion.attachPress(view); }
    private void choose(OnClickListener listener, int which, View source) {
        if (!showing || exiting || hostPaused) return;
        source.performHapticFeedback(which == BUTTON_POSITIVE ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
        actionAfterDismiss = listener == null ? null : () -> listener.onClick(this, which);
        beginDismiss();
    }

    @Override public void dismiss() { actionAfterDismiss = null; beginDismiss(); }
    @Override public void cancel() { actionAfterDismiss = null; beginDismiss(); }
    public void dismissImmediately() { actionAfterDismiss = null; finishDismiss(false); }
    public void onHostPaused() { hostPaused = true; settleForInterruption(); }
    public void onHostResumed() { hostPaused = false; notifyAccessibleSurface(); }
    /** Preserve typed input when interrupted, but never run a queued action in the background. */
    public void settleForInterruption() {
        actionAfterDismiss = null;
        if (!showing) return;
        cancelAnimation(); recycleVelocity();
        if (exiting) finishDismiss(false);
        else { surface.setVisibility(View.VISIBLE); surface.setTranslationY(0); scrim.setAlpha(1); notifyAccessibleSurface(); }
    }
    private void beginDismiss() {
        if (!showing || exiting) return;
        exiting = true; surface.setEnabled(false);
        View focus = activity.getCurrentFocus();
        if (focus != null && isDescendant(focus, surface)) {
            ((InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
        overlay.clearFocus();
        if (hostPaused || !ValueAnimator.areAnimatorsEnabled() || surface.getHeight() == 0) finishDismiss(!hostPaused);
        else animate(true);
    }
    private void animate(boolean exit) {
        cancelAnimation();
        float startY = surface.getTranslationY(), endY = exit ? surface.getHeight() + dp(16) : 0;
        float startAlpha = scrim.getAlpha();
        ValueAnimator current = ValueAnimator.ofFloat(0, 1); animator = current;
        current.setDuration(exit ? 180 : 280); current.setInterpolator(exit ? EXIT : ENTER);
        current.addUpdateListener(a -> {
            float progress = (float) a.getAnimatedValue();
            surface.setTranslationY(startY + (endY - startY) * progress);
            scrim.setAlpha(startAlpha + ((exit ? 0 : 1) - startAlpha) * progress);
        });
        current.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (cancelled || animator != a) return;
                animator = null;
                if (exit) finishDismiss(!hostPaused); else { focusInput(); notifyAccessibleSurface(); }
            }
        });
        current.start();
    }
    private void finishDismiss(boolean allowAction) {
        if (!showing) return;
        cancelAnimation(); recycleVelocity();
        Runnable action = allowAction ? actionAfterDismiss : null; actionAfterDismiss = null;
        showing = false; exiting = false;
        if (overlay != null) { overlay.setAccessibilityPaneTitle(null); detach(overlay); }
        for (int i = 0; i < hiddenSiblings.size(); i++) {
            View sibling = hiddenSiblings.get(i);
            if (sibling.getParent() != host) continue;
            boolean covered = false;
            for (int j = 0; j < host.getChildCount(); j++) {
                Object tag = host.getChildAt(j).getTag();
                if (!(tag instanceof TangoSheet)) continue;
                TangoSheet other = (TangoSheet) tag;
                int index = other.hiddenSiblings.indexOf(sibling);
                if (other.showing && index >= 0) {
                    covered = true;
                    // Out-of-order dismissal must not expose the screen beneath another sheet.
                    if (shownAt < other.shownAt) other.previousImportance.set(index, previousImportance.get(i));
                }
            }
            if (!covered) sibling.setImportantForAccessibility(previousImportance.get(i));
        }
        hiddenSiblings.clear(); previousImportance.clear();
        notifyAccessibilityHierarchy();
        if (previousFocus != null && previousFocus.isAttachedToWindow() && !(previousFocus instanceof EditText)) previousFocus.requestFocus();
        if (!dismissDelivered) { dismissDelivered = true; if (dismissListener != null) dismissListener.onDismiss(this); }
        if (action != null && !activity.isFinishing() && !activity.isDestroyed()) action.run();
    }
    private void notifyAccessibilityHierarchy() {
        if (host == null || !host.isAttachedToWindow()) return;
        AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        event.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE);
        host.sendAccessibilityEventUnchecked(event);
    }
    private void notifyAccessibleSurface() {
        if (!showing || exiting || hostPaused || surface == null || !surface.isAttachedToWindow()) return;
        // Translation can bring a previously clipped subtree on screen without a layout
        // change. Invalidate the accessibility tree after the surface is actually visible.
        notifyAccessibilityHierarchy();
        if (!accessibilityAnnounced) {
            accessibilityAnnounced = true;
            overlay.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
    }
    private void focusInput() {
        if (!showing || exiting || hostPaused) return;
        EditText input = findInput(spec.view);
        if (input != null && !input.hasFocus()) {
            input.requestFocus(); if(input.getSelectionStart()<0)input.setSelection(input.length());
            ((InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }
    private boolean dragHandle(View view, MotionEvent event) {
        if (exiting || hostPaused) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelAnimation(); dragStartY = event.getRawY(); dragStartOffset = surface.getTranslationY();
                recycleVelocity(); velocity = VelocityTracker.obtain(); velocity.addMovement(event); return true;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(event);
                float offset = Math.max(0, dragStartOffset + event.getRawY() - dragStartY);
                surface.setTranslationY(offset); scrim.setAlpha(Math.max(.2f, 1 - offset / Math.max(1, surface.getHeight()))); return true;
            case MotionEvent.ACTION_UP:
                float speed = 0;
                if (velocity != null) { velocity.addMovement(event); velocity.computeCurrentVelocity(1000); speed = velocity.getYVelocity(); }
                boolean close = surface.getTranslationY() > Math.min(dp(110), surface.getHeight() * .3f) || (speed > dp(750) && surface.getTranslationY() > dp(12));
                recycleVelocity(); if (close) cancel(); else animate(false); view.performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                recycleVelocity(); animate(false); return true;
            default: return true;
        }
    }
    private void cancelAnimation() { if (animator != null) { ValueAnimator old = animator; animator = null; old.cancel(); } }
    private void recycleVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }
    private int dp(float value) { return (int) (activity.getResources().getDisplayMetrics().density * value + .5f); }
    private TextView label(String value, int size, int color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(color == 0xFFF3F3F3 ? 0x22000000 : 0x22FFFFFF), shape(color, radius), shape(Color.WHITE, radius)); }
    private static boolean destructive(String text) { return text != null && (text.equalsIgnoreCase("Delete") || text.equalsIgnoreCase("Clear") || text.equalsIgnoreCase("Sign out")); }
    private static void detach(View view) { if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view); }
    private static boolean isDescendant(View child, View ancestor) { for (ViewParent p = child.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true; return child == ancestor; }
    private static EditText findInput(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { EditText found = findInput(((ViewGroup) view).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private final class SheetLayout extends LinearLayout {
        SheetLayout() { super(activity); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int parentHeight = MeasureSpec.getSize(heightMeasureSpec);
            int cap = parentHeight;
            int width = Math.min(MeasureSpec.getSize(widthMeasureSpec), dp(520));
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
}
