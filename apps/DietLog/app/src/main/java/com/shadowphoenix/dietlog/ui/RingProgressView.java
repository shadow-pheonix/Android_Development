package com.shadowphoenix.dietlog.ui;

import android.content.Context;
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
        trackPaint.setColor(0xFF173038);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(strokeWidthPx);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setProgress(float value) {
        progress = Math.max(0f, Math.min(1f, value));
        invalidate();
    }

    public void setColors(int start, int end) {
        startColor = start;
        endColor = end;
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
        RectF oval = new RectF(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(oval, -90f, 360f, false, trackPaint);
        if (progress <= 0f) return;

        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        SweepGradient gradient = new SweepGradient(cx, cy,
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

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
