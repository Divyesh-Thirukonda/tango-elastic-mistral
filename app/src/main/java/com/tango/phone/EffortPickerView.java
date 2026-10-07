package com.tango.phone;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** The effort control is drawn in the conversation instead of opening a system slider. */
public final class EffortPickerView extends View {
    public interface OnModeChangedListener {
        void onModeChanged(int mode);
    }

    private static final String[] LABELS = {"Instant", "Medium", "High"};
    private static final int BLUE = 0xFF3766EF;
    private static final int LABEL_BLUE = 0xFFAEC5FF;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final RectF fill = new RectF();
    private final RectF progress = new RectF();
    private final float density;
    private final float scaledDensity;
    private int mode = 1;
    private float position = 1f;
    private boolean dragging;
    private boolean dragMoved;
    private float downX;
    private final int touchSlop;
    private ValueAnimator snapAnimation;
    private OnModeChangedListener listener;

    public EffortPickerView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription("Model effort");
        setStateDescription(LABELS[mode]);
    }

    /** Synchronize a stored mode without treating it as a user selection. */
    public void setMode(int value) {
        cancelAnimation();
        mode = clampMode(value);
        position = mode;
        setStateDescription(LABELS[mode]);
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    public void setOnModeChangedListener(OnModeChangedListener listener) {
        this.listener = listener;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = resolveSize(Math.round(dp(360)) + getPaddingLeft() + getPaddingRight(), widthMeasureSpec);
        int height = resolveSize(Math.round(dp(146)) + getPaddingTop() + getPaddingBottom(), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float bottom = h - getPaddingBottom() - dp(8);
        track.set(getPaddingLeft() + dp(4), bottom - dp(84), w - getPaddingRight() - dp(4), bottom);
        fill.set(track);
        fill.inset(dp(13), dp(13));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(25 * scaledDensity);
        String level = LABELS[mode];
        String suffix = " effort";
        float textWidth = paint.measureText(level + suffix);
        float textX = track.centerX() - textWidth / 2f;
        Paint.FontMetrics fontMetrics = paint.getFontMetrics();
        float labelBaseline = track.top - dp(21) - (fontMetrics.ascent + fontMetrics.descent) / 2f;
        paint.setColor(LABEL_BLUE);
        canvas.drawText(level, textX, labelBaseline, paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(suffix, textX + paint.measureText(level), labelBaseline, paint);

        paint.setColor(0xFF303030);
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(isFocused() ? 0xFF809DD6 : 0xFF454545);
        canvas.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint);
        paint.setStyle(Paint.Style.FILL);

        float thumbX = xForPosition(position);
        progress.set(fill.left, fill.top, thumbX + fill.height() / 2f, fill.bottom);
        paint.setColor(BLUE);
        canvas.drawRoundRect(progress, fill.height() / 2f, fill.height() / 2f, paint);

        for (int i = 0; i < LABELS.length; i++) {
            paint.setColor(i <= position ? 0xFF6488F5 : 0xFF707070);
            canvas.drawCircle(xForPosition(i), fill.centerY(), dp(7), paint);
        }
        paint.setColor(Color.WHITE);
        canvas.drawCircle(thumbX, fill.centerY(), dp(23), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getY() < track.top - dp(10) || event.getY() > track.bottom + dp(10)) return false;
                cancelAnimation();
                dragging = true;
                dragMoved = false;
                downX = event.getX();
                setPressed(true);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                selectMode(Math.round(positionForX(event.getX())));
                animateToMode();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                if (dragMoved || Math.abs(event.getX() - downX) > touchSlop) {
                    dragMoved = true;
                    cancelAnimation();
                    moveTo(event.getX());
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                if (dragMoved) moveTo(event.getX());
                finishDrag();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                finishDrag();
                return true;
            default:
                return dragging;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void moveTo(float x) {
        position = positionForX(x);
        selectMode(Math.round(position));
        invalidate();
    }

    private float positionForX(float x) {
        float first = xForPosition(0);
        float last = xForPosition(LABELS.length - 1);
        if (last <= first) return mode;
        return Math.max(0, Math.min(LABELS.length - 1, (x - first) * (LABELS.length - 1) / (last - first)));
    }

    private void finishDrag() {
        dragging = false;
        setPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        animateToMode();
    }

    private boolean selectMode(int value) {
        int nextMode = clampMode(value);
        if (nextMode == mode) return false;
        mode = nextMode;
        UiMotion.tick(this);
        setStateDescription(LABELS[mode]);
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        if (listener != null) listener.onModeChanged(mode);
        return true;
    }

    private void animateToMode() {
        cancelAnimation();
        if (!UiMotion.enabled() || Math.abs(position - mode) < .001f) {
            position = mode;
            invalidate();
            return;
        }
        snapAnimation = ValueAnimator.ofFloat(position, mode);
        snapAnimation.setDuration(UiMotion.STATE);
        snapAnimation.setInterpolator(UiMotion.EASE);
        snapAnimation.addUpdateListener(animation -> {
            position = (Float) animation.getAnimatedValue();
            invalidate();
        });
        snapAnimation.start();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // TalkBack gets normal range semantics while all visuals remain our own.
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, LABELS.length - 1, mode));
        info.setScrollable(isEnabled());
        if (!isEnabled()) return;
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        if (mode < LABELS.length - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        if (mode > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (isEnabled()) {
            int target;
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) target = mode + 1;
            else if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) target = mode - 1;
            else if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                    && arguments != null && arguments.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                target = Math.round(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE));
            } else return super.performAccessibilityAction(action, arguments);
            boolean changed = selectMode(target);
            animateToMode();
            return changed;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled()) {
            int target;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_UP) target = mode + 1;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) target = mode - 1;
            else if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) target = 0;
            else if (keyCode == KeyEvent.KEYCODE_MOVE_END) target = LABELS.length - 1;
            else return super.onKeyDown(keyCode, event);
            selectMode(target);
            animateToMode();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDetachedFromWindow() {
        cancelAnimation();
        super.onDetachedFromWindow();
    }

    private void cancelAnimation() {
        if (snapAnimation == null) return;
        snapAnimation.cancel();
        snapAnimation = null;
    }

    private float xForPosition(float value) {
        float inset = fill.height() / 2f;
        return fill.left + inset + (fill.width() - inset * 2f) * value / (LABELS.length - 1);
    }

    private static int clampMode(int value) {
        return Math.max(0, Math.min(LABELS.length - 1, value));
    }

    private float dp(float value) {
        return value * density;
    }
}
