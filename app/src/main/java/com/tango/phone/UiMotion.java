package com.tango.phone;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/** A small, shared motion vocabulary. Motion always accompanies a change of state. */
final class UiMotion {
    static final long ENTER = 300;
    static final long EXIT = 210;
    static final long STATE = 180;
    static final Interpolator EASE = new PathInterpolator(.2f, .8f, .2f, 1f);

    private UiMotion() {}

    static boolean enabled() { return ValueAnimator.areAnimatorsEnabled(); }
    static long duration(long milliseconds) { return enabled() ? milliseconds : 0; }

    /** Uses drawable states, so cancellation, keyboard input and accessibility clicks stay native. */
    static void attachPress(View view) {
        StateListAnimator states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed}, scale(view, .97f, 90));
        states.addState(new int[]{android.R.attr.state_enabled}, scale(view, 1f, STATE));
        states.addState(new int[]{}, scale(view, 1f, 0));
        view.setStateListAnimator(states);

        // A foreground ripple survives callers replacing a button's surface or selection color.
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(24 * view.getResources().getDisplayMetrics().density);
        view.setForeground(new RippleDrawable(ColorStateList.valueOf(0x26A9BFFF), null, mask));
    }

    private static ObjectAnimator scale(View view, float value, long milliseconds) {
        ObjectAnimator animation = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.SCALE_X, value),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, value));
        animation.setDuration(duration(milliseconds));
        animation.setInterpolator(EASE);
        return animation;
    }

    static void confirm(View view) {
        if (view != null) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
    }

    static void tick(View view) {
        if (view != null) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Call only for newly inserted content, never from an unchanged polling refresh. */
    static void reveal(View view, long delay) {
        view.animate().cancel();
        if (!enabled()) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(10 * view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(120, Math.max(0, delay)))
                .setDuration(ENTER).setInterpolator(EASE).withEndAction(() -> view.animate().setStartDelay(0)).start();
    }

    /** The newest value is available to accessibility immediately, with a restrained arrival fade. */
    static void crossfade(TextView view, CharSequence text) {
        if (android.text.TextUtils.equals(view.getText(), text)) return;
        view.animate().cancel();
        view.setText(text);
        view.setAlpha(enabled() ? .45f : 1f);
        if (enabled()) view.animate().alpha(1f).setStartDelay(0).setDuration(STATE).setInterpolator(EASE).start();
    }
}
