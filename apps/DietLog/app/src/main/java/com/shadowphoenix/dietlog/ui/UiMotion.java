package com.shadowphoenix.dietlog.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;

import com.shadowphoenix.dietlog.R;

/** Short, interruptible UI motion. Never consumes clicks or scroll gestures. */
public final class UiMotion implements AutoCloseable {
    private final View root;
    private final Context context;
    private final BroadcastReceiver powerReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshPolicy(); }
    };
    private View currentScreen;
    private Runnable pendingEntrance;
    private boolean foreground;
    private boolean closed;
    private boolean pendingPage;
    private int epoch;

    public UiMotion(View root) {
        this.root = root;
        context = root.getContext();
        IntentFilter filter = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(powerReceiver, filter);
        }
    }

    public static boolean preferencesAllowMotion(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return ValueAnimator.areAnimatorsEnabled() && (power == null || !power.isPowerSaveMode());
    }

    private boolean allowed() { return !closed && foreground && preferencesAllowMotion(context); }

    public void setForeground(boolean foreground) {
        this.foreground = foreground;
        refreshPolicy();
        if (foreground && pendingPage && currentScreen != null) startEntrance(currentScreen);
    }

    public void enterScreen(View target) {
        bindPressTree(root);
        refreshPolicy();
        if (target == currentScreen) return;
        epoch++;
        if (pendingEntrance != null) root.removeCallbacks(pendingEntrance);
        if (currentScreen != null) resetTree(currentScreen);
        resetTree(target);
        currentScreen = target;
        pendingPage = true;
        if (foreground) startEntrance(target);
    }

    private void startEntrance(View target) {
        pendingPage = false;
        int token = ++epoch;
        pendingEntrance = () -> {
            pendingEntrance = null;
            if (token != epoch || target != currentScreen || !target.isShown() || !allowed()) return;
            View container = target instanceof ScrollView ? ((ScrollView) target).getChildAt(0) : target;
            if (!(container instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) container;
            int stagger = 0;
            float distance = 10 * context.getResources().getDisplayMetrics().density;
            for (int i = 0; i < group.getChildCount() && stagger < 8; i++) {
                View item = group.getChildAt(i);
                if (item.getVisibility() != View.VISIBLE) continue;
                MotionState state = stateFor(item);
                if (!state.fade) {
                    state.alpha = item.getAlpha(); state.translationY = item.getTranslationY();
                    state.fade = true;
                }
                item.setAlpha(0);
                item.setTranslationY(state.translationY + distance);
                item.animate().alpha(state.alpha).translationY(state.translationY).setStartDelay(stagger++ * 18L)
                        .setDuration(240).setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1f)).start();
            }
            replayIndicators(target);
        };
        root.post(pendingEntrance);
    }

    @SuppressLint("ClickableViewAccessibility") // Listener returns false; native performClick handles accessibility.
    public void bindPressTree(View view) {
        // Bottom navigation has its own selection pill and native click feedback.
        if (view instanceof FrostedNavigationView) return;
        if (view.hasOnClickListeners() && !(view instanceof EditText) && !(view instanceof Spinner)
                && !Boolean.TRUE.equals(view.getTag(R.id.frostedPressBound))) {
            view.setTag(R.id.frostedPressBound, true);
            view.setOnTouchListener((target, event) -> {
                int action = event.getActionMasked();
                if (!allowed() || !target.isEnabled()) {
                    resetView(target);
                    return false;
                }
                if (action == MotionEvent.ACTION_DOWN) {
                    MotionState state = scaleStateFor(target);
                    target.animate().scaleX(state.scaleX * 0.97f).scaleY(state.scaleY * 0.97f).setStartDelay(0).setDuration(90)
                            .setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1)).start();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    MotionState state = scaleStateFor(target);
                    target.animate().scaleX(state.scaleX).scaleY(state.scaleY).setStartDelay(0).setDuration(190)
                            .setInterpolator(new OvershootInterpolator(1.2f)).start();
                }
                return false;
            });
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) { resetView(v); }
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) bindPressTree(group.getChildAt(i));
        }
    }

    public void emphasize(View view) {
        if (!allowed()) return;
        MotionState state = scaleStateFor(view);
        view.animate().cancel();
        view.setScaleX(state.scaleX * 0.88f); view.setScaleY(state.scaleY * 0.88f);
        view.animate().scaleX(state.scaleX).scaleY(state.scaleY).setStartDelay(0).setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.1f)).start();
    }

    private void refreshPolicy() {
        if (!allowed()) {
            epoch++;
            if (pendingEntrance != null) root.removeCallbacks(pendingEntrance);
            pendingEntrance = null;
            resetTree(root);
        }
        setIndicatorPolicy(root, allowed());
    }

    private void setIndicatorPolicy(View view, boolean enabled) {
        if (view instanceof FrostedNavigationView) ((FrostedNavigationView) view).setUiMotionAllowed(enabled);
        if (view instanceof RingProgressView) ((RingProgressView) view).setUiMotionAllowed(enabled);
        if (view instanceof ProgressTrendView) ((ProgressTrendView) view).setUiMotionAllowed(enabled);
        if (view instanceof MealIllustrationView) ((MealIllustrationView) view).setUiMotionAllowed(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setIndicatorPolicy(group.getChildAt(i), enabled);
        }
    }

    private void replayIndicators(View view) {
        if (view instanceof RingProgressView) ((RingProgressView) view).replay();
        if (view instanceof ProgressTrendView) ((ProgressTrendView) view).replay();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) replayIndicators(group.getChildAt(i));
        }
    }

    private static final class MotionState {
        boolean fade, scale;
        float alpha, translationY, scaleX, scaleY;
    }

    private static MotionState stateFor(View view) {
        MotionState state = (MotionState) view.getTag(R.id.frostedMotionState);
        if (state == null) {
            state = new MotionState();
            view.setTag(R.id.frostedMotionState, state);
        }
        return state;
    }

    private static MotionState scaleStateFor(View view) {
        MotionState state = stateFor(view);
        if (!state.scale) {
            state.scaleX = view.getScaleX(); state.scaleY = view.getScaleY(); state.scale = true;
        }
        return state;
    }

    private static void resetView(View view) {
        MotionState state = (MotionState) view.getTag(R.id.frostedMotionState);
        if (state != null) {
            view.animate().cancel();
            if (state.fade) { view.setAlpha(state.alpha); view.setTranslationY(state.translationY); }
            if (state.scale) { view.setScaleX(state.scaleX); view.setScaleY(state.scaleY); }
            view.setTag(R.id.frostedMotionState, null);
        }
        if (view instanceof RingProgressView) ((RingProgressView) view).finishAnimation();
        if (view instanceof ProgressTrendView) ((ProgressTrendView) view).finishAnimation();
    }

    private static void resetTree(View view) {
        resetView(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) resetTree(group.getChildAt(i));
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        refreshPolicy();
        context.unregisterReceiver(powerReceiver);
    }
}
