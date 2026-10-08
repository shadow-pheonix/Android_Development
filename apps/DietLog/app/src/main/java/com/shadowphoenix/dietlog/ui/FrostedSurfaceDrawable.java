package com.shadowphoenix.dietlog.ui;

import android.content.Context;
import android.content.res.Resources;
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

import com.shadowphoenix.dietlog.R;

/** Smooth tinted frost and a directional rim. Foreground content is never blurred. */
public final class FrostedSurfaceDrawable extends Drawable {
    public enum Material { CARD, FEATURE, CONTROL, INPUT, NAVIGATION, TAB, SELECTION, ACTION, BADGE, THUMBNAIL }

    private final Material material;
    private final Resources resources;
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
        resources = context.getResources();
        density = resources.getDisplayMetrics().density;
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
        int top = color(R.color.glass_card_top), bottom = color(R.color.glass_card_bottom);
        switch (material) {
            case FEATURE: top = color(R.color.glass_feature_top); bottom = color(R.color.glass_feature_bottom); break;
            case CONTROL: top = color(R.color.glass_control_top); bottom = color(R.color.glass_control_bottom); break;
            case INPUT: top = color(R.color.glass_input_top); bottom = color(R.color.glass_input_bottom); break;
            case NAVIGATION: top = color(R.color.glass_navigation_top); bottom = color(R.color.glass_navigation_bottom); break;
            case SELECTION: top = color(R.color.glass_selection_top); bottom = color(R.color.glass_selection_bottom); break;
            case TAB:
                top = color(selected ? R.color.glass_selection_top : R.color.glass_tab_top);
                bottom = color(selected ? R.color.glass_selection_bottom : R.color.glass_tab_bottom);
                break;
            case ACTION: top = color(R.color.glass_action_top); bottom = color(R.color.glass_action_bottom); break;
            case BADGE: top = color(R.color.glass_badge_top); bottom = color(R.color.glass_badge_bottom); break;
            default: break;
        }
        frost.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{top, bottom}, null, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{color(pressed ? R.color.glass_rim_pressed : R.color.glass_rim_top),
                        color(R.color.glass_rim_middle), color(R.color.glass_rim_bottom)},
                new float[]{0, 0.56f, 1}, Shader.TileMode.CLAMP));
    }

    private int color(int resourceId) { return resources.getColor(resourceId, null); }

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
