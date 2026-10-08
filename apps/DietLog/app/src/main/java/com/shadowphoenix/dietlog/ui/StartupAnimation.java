package com.shadowphoenix.dietlog.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;

import com.shadowphoenix.dietlog.R;

/** One brief launch reveal. Pauses in the background and never delays reduced-motion users. */
public final class StartupAnimation implements AutoCloseable {
    private final View overlay;
    private final View logo, name, tagline;
    private final Runnable onComplete;
    private final Runnable start = this::start;
    private AnimatorSet animation;
    private boolean foreground, complete, closed;

    public StartupAnimation(View overlay, Runnable onComplete) {
        this.overlay = overlay;
        this.onComplete = onComplete;
        logo = overlay.findViewById(R.id.startupLogo);
        name = overlay.findViewById(R.id.startupName);
        tagline = overlay.findViewById(R.id.startupTagline);
        logo.setAlpha(0); name.setAlpha(0); tagline.setAlpha(0);
    }

    public void resume() {
        if (complete || closed) return;
        foreground = true;
        if (!UiMotion.preferencesAllowMotion(overlay.getContext())) { finish(); return; }
        if (animation != null) animation.resume();
        else { overlay.removeCallbacks(start); overlay.post(start); }
    }

    public void pause() {
        foreground = false;
        overlay.removeCallbacks(start);
        if (animation != null) animation.pause();
    }

    private void start() {
        if (!foreground || closed || complete) return;
        if (!UiMotion.preferencesAllowMotion(overlay.getContext())) { finish(); return; }
        float rise = 12 * overlay.getResources().getDisplayMetrics().density;
        ObjectAnimator logoFade = ObjectAnimator.ofFloat(logo, View.ALPHA, 0, 1);
        logoFade.setDuration(480);
        ObjectAnimator logoRise = ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, rise, 0);
        logoRise.setDuration(600);
        ObjectAnimator nameFade = ObjectAnimator.ofFloat(name, View.ALPHA, 0, 1);
        nameFade.setDuration(400); nameFade.setStartDelay(160);
        ObjectAnimator taglineFade = ObjectAnimator.ofFloat(tagline, View.ALPHA, 0, 1);
        taglineFade.setDuration(400); taglineFade.setStartDelay(280);
        ObjectAnimator exitFade = ObjectAnimator.ofFloat(overlay, View.ALPHA, 1, 0);
        exitFade.setDuration(180); exitFade.setStartDelay(820);
        animation = new AnimatorSet();
        animation.playTogether(logoFade, logoRise, nameFade, taglineFade, exitFade);
        animation.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) { finish(); }
        });
        animation.start();
    }

    private void finish() {
        if (complete || closed) return;
        complete = true;
        clearAnimation();
        overlay.setVisibility(View.GONE);
        onComplete.run();
    }

    private void clearAnimation() {
        overlay.removeCallbacks(start);
        if (animation != null) {
            animation.removeAllListeners();
            animation.cancel();
            animation = null;
        }
    }

    @Override public void close() {
        closed = true;
        clearAnimation();
    }
}
