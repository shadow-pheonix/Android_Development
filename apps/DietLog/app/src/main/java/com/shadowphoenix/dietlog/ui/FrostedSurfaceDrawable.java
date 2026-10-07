package com.shadowphoenix.dietlog.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Smooth tinted frost and a directional rim. Foreground content is never blurred. */
public final class FrostedSurfaceDrawable extends Drawable {
    public enum Material { CARD, FEATURE, CONTROL, INPUT, NAVIGATION, TAB, SELECTION, ACTION, BADGE, THUMBNAIL }

    private final Material material;
    private final boolean pressFeedbackEnabled;
    private final float density;
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private boolean selected;
    private boolean pressed;
    private int alpha = 255;

    public FrostedSurfaceDrawable(Context context, Material material) {
        this(context, material, true);
    }

    public FrostedSurfaceDrawable(Context context, Material material, boolean pressFeedbackEnabled) {
        this.material = material;
        this.pressFeedbackEnabled = pressFeedbackEnabled;
        density = context.getResources().getDisplayMetrics().density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(density * 0.75f);
    }

    private float radius() {
        switch (material) {
            case NAVIGATION: return 30 * density;
            case ACTION: case BADGE: case SELECTION:
                return Math.max(0, Math.min(rect.width(), rect.height()) / 2);
            case CONTROL: return 24 * density;
            case INPUT: return 18 * density;
            case THUMBNAIL: return 14 * density;
            case TAB: return 18 * density;
            default: return 24 * density;
        }
    }

    @Override protected void onBoundsChange(Rect bounds) {
        rect.set(bounds);
        rect.inset(density * 0.4f, density * 0.4f);
        clip.reset();
        clip.addRoundRect(rect, radius(), radius(), Path.Direction.CW);
        updatePaints();
    }

    private void updatePaints() {
        int top = 0xD32B414B, bottom = 0xD0112029;
        switch (material) {
            case FEATURE: top = 0xD6365058; bottom = 0xCF12272E; break;
            case CONTROL: top = 0xA7354B53; bottom = 0xB91A3038; break;
            case INPUT: top = 0xD2223740; bottom = 0xD016252E; break;
            case NAVIGATION: top = 0xE72B3D46; bottom = 0xEF13242D; break;
            case SELECTION: top = 0xAB426A66; bottom = 0xAA1F4542; break;
            case TAB:
                top = selected ? 0xAB426A66 : 0x102D454E;
                bottom = selected ? 0xAA1F4542 : 0x08203038;
                break;
            case ACTION: top = 0xEE63F5D1; bottom = 0xE51AC2A7; break;
            case BADGE: top = 0xB82E5358; bottom = 0xC118353B; break;
            default: break;
        }
        frost.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{top, bottom}, null, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{pressed ? 0xAFFFFFFF : 0x72E4FFFA, 0x184CE8CE, 0x35D3EEEA},
                new float[]{0, 0.56f, 1}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        if (rect.isEmpty()) return;
        int save = canvas.save();
        canvas.clipPath(clip);
        frost.setAlpha(alpha);
        canvas.drawRect(rect, frost);
        canvas.restoreToCount(save);
        if (material != Material.TAB || selected || pressed) {
            rim.setAlpha(alpha);
            canvas.drawRoundRect(rect, radius(), radius(), rim);
        }
    }

    @Override public boolean isStateful() { return true; }

    @Override protected boolean onStateChange(int[] state) {
        boolean nextSelected = false, nextPressed = false;
        for (int value : state) {
            if (value == android.R.attr.state_selected || value == android.R.attr.state_checked) nextSelected = true;
            if (pressFeedbackEnabled && value == android.R.attr.state_pressed) nextPressed = true;
        }
        if (selected == nextSelected && pressed == nextPressed) return false;
        selected = nextSelected;
        pressed = nextPressed;
        updatePaints();
        invalidateSelf();
        return true;
    }

    @Override public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius());
        outline.setAlpha(material == Material.TAB && !selected ? 0 : 0.3f);
    }

    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) {
        frost.setColorFilter(filter); rim.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
