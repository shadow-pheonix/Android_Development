package com.shadowphoenix.dietlog.ui;

import android.content.Context;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;

public final class RingProgressView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress = 0.0f;
    private float targetProgress;
    private ValueAnimator motion;
    private boolean uiMotionAllowed = true;
    private final RectF oval = new RectF();
    private SweepGradient gradient;
    private float strokeWidthPx;
    private int startColor = 0xFF00E7B4;
    private int endColor = 0xFF2AB7FF;

    public RingProgressView(Context context) {
        this(context, null);
    }

    public RingProgressView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RingProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        strokeWidthPx = dp(11);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setStrokeWidth(strokeWidthPx);
        trackPaint.setColor(0x603D6770);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(strokeWidthPx);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setProgress(float value) {
        float target = Float.isNaN(value) ? 0 : Math.max(0f, Math.min(1f, value));
        if (target == targetProgress) return;
        targetProgress = target;
        animateToTarget(false);
    }

    public void replay() { animateToTarget(true); }

    public void setUiMotionAllowed(boolean enabled) {
        uiMotionAllowed = enabled;
        if (!enabled) finishAnimation();
    }

    public void finishAnimation() {
        if (motion != null) motion.cancel();
        motion = null;
        progress = targetProgress;
        invalidate();
    }

    private void animateToTarget(boolean replay) {
        if (motion != null) motion.cancel();
        if (!uiMotionAllowed || !isAttachedToWindow() || !isShown()
                || !UiMotion.preferencesAllowMotion(getContext())) {
            finishAnimation();
            return;
        }
        float from = replay ? 0 : progress;
        if (from == targetProgress) { finishAnimation(); return; }
        progress = from;
        motion = ValueAnimator.ofFloat(from, targetProgress);
        motion.setDuration(620);
        motion.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1));
        motion.addUpdateListener(value -> { progress = (float) value.getAnimatedValue(); invalidate(); });
        motion.start();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (!visible) finishAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        finishAnimation();
        super.onDetachedFromWindow();
    }

    public void setColors(int start, int end) {
        startColor = start;
        endColor = end;
        gradient = null;
        invalidate();
    }

    public void setStrokeWidthDp(float dp) {
        strokeWidthPx = dp(dp);
        trackPaint.setStrokeWidth(strokeWidthPx);
        progressPaint.setStrokeWidth(strokeWidthPx);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = strokeWidthPx / 2f + dp(3);
        oval.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(oval, -90f, 360f, false, trackPaint);
        if (progress <= 0f) return;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        if (gradient == null) gradient = new SweepGradient(cx, cy,
                new int[]{startColor, endColor, startColor},
                new float[]{0f, 0.72f, 1f});
        progressPaint.setShader(gradient);
        progressPaint.setShadowLayer(dp(7), 0f, 0f, startColor & 0x88FFFFFF);
        canvas.save();
        canvas.rotate(-90f, cx, cy);
        canvas.drawArc(oval, 0f, 360f * progress, false, progressPaint);
        canvas.restore();
        progressPaint.clearShadowLayer();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        gradient = null;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
