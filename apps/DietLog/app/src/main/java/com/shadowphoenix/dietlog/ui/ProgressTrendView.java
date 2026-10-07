package com.shadowphoenix.dietlog.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.shadowphoenix.dietlog.R;

import java.util.Locale;

public final class ProgressTrendView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint referencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String[] labels = new String[0];
    private double[][] series = new double[0][];
    private int[] colors = new int[0];
    private double referenceValue = Double.NaN;
    private boolean zeroBaseline;
    private String unit = "";

    public ProgressTrendView(Context context) {
        this(context, null);
    }

    public ProgressTrendView(Context context, AttributeSet attrs) {
        super(context, attrs);
        gridPaint.setColor(context.getColor(R.color.outline_soft));
        gridPaint.setStrokeWidth(dp(1));

        textPaint.setColor(context.getColor(R.color.text_tertiary));
        textPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);

        referencePaint.setColor(context.getColor(R.color.text_secondary));
        referencePaint.setStrokeWidth(dp(1));
        referencePaint.setStyle(Paint.Style.STROKE);
        referencePaint.setPathEffect(new DashPathEffect(new float[]{dp(5), dp(5)}, 0));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.5f));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        pointPaint.setStyle(Paint.Style.FILL);
    }

    public void setData(String[] labels, double[][] series, int[] colors,
                        double referenceValue, boolean zeroBaseline, String unit) {
        this.labels = labels == null ? new String[0] : labels;
        this.series = series == null ? new double[0][] : series;
        this.colors = colors == null ? new int[0] : colors;
        this.referenceValue = referenceValue;
        this.zeroBaseline = zeroBaseline;
        this.unit = unit == null ? "" : unit;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (labels.length == 0 || series.length == 0) {
            drawEmpty(canvas);
            return;
        }

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int finiteCount = 0;
        for (double[] values : series) {
            if (values == null) continue;
            for (double value : values) {
                if (!Double.isFinite(value)) continue;
                finiteCount++;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        if (Double.isFinite(referenceValue)) {
            min = Math.min(min, referenceValue);
            max = Math.max(max, referenceValue);
        }
        if (finiteCount == 0) {
            drawEmpty(canvas);
            return;
        }
        if (zeroBaseline) min = Math.min(0, min);
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            drawEmpty(canvas);
            return;
        }
        if (Math.abs(max - min) < 0.001) {
            double pad = Math.max(1.0, Math.abs(max) * 0.05);
            min -= pad;
            max += pad;
        } else if (!zeroBaseline) {
            double pad = (max - min) * 0.12;
            min -= pad;
            max += pad;
        } else {
            max *= 1.08;
        }

        float axisWidth = Math.max(textPaint.measureText(formatAxis(min)), textPaint.measureText(formatAxis(max)));
        float left = Math.max(dp(42), axisWidth + dp(8));
        float right = getWidth() - dp(10);
        float top = dp(10);
        float bottom = getHeight() - Math.max(dp(26), textPaint.getTextSize() + dp(12));
        float chartWidth = Math.max(1, right - left);
        float chartHeight = Math.max(1, bottom - top);

        for (int i = 0; i < 4; i++) {
            float y = top + chartHeight * i / 3f;
            canvas.drawLine(left, y, right, y, gridPaint);
            double value = max - (max - min) * i / 3.0;
            canvas.drawText(formatAxis(value), dp(2), y + dp(4), textPaint);
        }

        if (Double.isFinite(referenceValue)) {
            float y = mapY(referenceValue, min, max, top, bottom);
            canvas.drawLine(left, y, right, y, referencePaint);
        }

        int pointCount = labels.length;
        for (int s = 0; s < series.length; s++) {
            double[] values = series[s];
            if (values == null || values.length == 0) continue;
            int color = colors.length > s ? colors[s] : getContext().getColor(R.color.accent);
            linePaint.setColor(color);
            pointPaint.setColor(color);
            if (series.length == 1) {
                // Fill each continuous segment separately so missing days retain their gaps.
                fillPaint.setShader(new LinearGradient(0, top, 0, bottom,
                        new int[]{Color.argb(48, Color.red(color), Color.green(color), Color.blue(color)),
                                Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
                Path area = new Path();
                float firstX = 0, lastX = 0;
                boolean segment = false;
                for (int i = 0; i <= Math.min(pointCount, values.length); i++) {
                    if (i == Math.min(pointCount, values.length) || !Double.isFinite(values[i])) {
                        if (segment) {
                            area.lineTo(lastX, bottom);
                            area.lineTo(firstX, bottom);
                            area.close();
                            canvas.drawPath(area, fillPaint);
                            area.reset();
                            segment = false;
                        }
                        continue;
                    }
                    float x = pointCount <= 1 ? left + chartWidth / 2f
                            : left + chartWidth * i / (pointCount - 1f);
                    float y = mapY(values[i], min, max, top, bottom);
                    if (!segment) {
                        firstX = x;
                        area.moveTo(x, y);
                        segment = true;
                    } else area.lineTo(x, y);
                    lastX = x;
                }
            }
            Path path = new Path();
            boolean hasPath = false;
            for (int i = 0; i < Math.min(pointCount, values.length); i++) {
                double value = values[i];
                if (!Double.isFinite(value)) {
                    hasPath = false;
                    continue;
                }
                float x = pointCount <= 1 ? left + chartWidth / 2f
                        : left + chartWidth * i / (pointCount - 1f);
                float y = mapY(value, min, max, top, bottom);
                if (!hasPath) {
                    path.moveTo(x, y);
                    hasPath = true;
                } else {
                    path.lineTo(x, y);
                }
                canvas.drawCircle(x, y, dp(2.5f), pointPaint);
            }
            canvas.drawPath(path, linePaint);
        }

        int labelSlots = Math.min(chartWidth < dp(280) ? 3 : 5, labels.length);
        for (int slot = 0; slot < labelSlots; slot++) {
            int index = labelSlots == 1 ? 0 :
                    Math.round((labels.length - 1) * slot / (float) (labelSlots - 1));
            float x = labels.length <= 1 ? left + chartWidth / 2f : left + chartWidth * index / (labels.length - 1f);
            String label = labels[index];
            float width = textPaint.measureText(label);
            canvas.drawText(label, Math.max(left, Math.min(right - width, x - width / 2f)),
                    getHeight() - dp(6), textPaint);
        }
    }

    private float mapY(double value, double min, double max, float top, float bottom) {
        double fraction = (value - min) / (max - min);
        return (float) (bottom - fraction * (bottom - top));
    }

    private String formatAxis(double value) {
        if ("%".equals(unit)) return String.format(Locale.getDefault(), "%.0f%%", value);
        if ("kg".equals(unit)) return String.format(Locale.getDefault(), "%.1f", value);
        if (Math.abs(value) >= 1000) return String.format(Locale.getDefault(), "%.0fk", value / 1000.0);
        return String.format(Locale.getDefault(), "%.0f", value);
    }

    private void drawEmpty(Canvas canvas) {
        String text = "No data yet";
        float width = textPaint.measureText(text);
        canvas.drawText(text, (getWidth() - width) / 2f, getHeight() / 2f, textPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
