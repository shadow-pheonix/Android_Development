package com.shadowphoenix.dietlog.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;

import com.shadowphoenix.dietlog.R;

/** Applies one frost material consistently to XML and dynamically created views. */
public final class FrostedUi {
    private FrostedUi() {}

    public static void applyTree(View view) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith("glass:")) {
            String name = ((String) tag).substring(6);
            if (!name.equals(view.getTag(R.id.frostedAppliedMaterial))) {
                surface(view, FrostedSurfaceDrawable.Material.valueOf(name));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i));
        }
    }

    public static void surface(View view, FrostedSurfaceDrawable.Material material) {
        view.setTag("glass:" + material.name());
        view.setTag(R.id.frostedAppliedMaterial, material.name());
        // Navigation already has one moving selection pill; touch feedback would overlap it.
        boolean navigationButton = view.getParent() instanceof FrostedNavigationView;
        FrostedSurfaceDrawable frost = new FrostedSurfaceDrawable(view.getContext(), material, !navigationButton);
        Drawable drawable = frost;
        if (!navigationButton && (view.isClickable() || material == FrostedSurfaceDrawable.Material.CONTROL
                || material == FrostedSurfaceDrawable.Material.ACTION
                || material == FrostedSurfaceDrawable.Material.TAB)) {
            drawable = new RippleDrawable(ColorStateList.valueOf(0x2872FFE0), frost, null);
        }
        view.setBackground(drawable);
        view.setClipToOutline(true);
        float density = view.getResources().getDisplayMetrics().density;
        float elevation = material == FrostedSurfaceDrawable.Material.NAVIGATION ? 12
                : material == FrostedSurfaceDrawable.Material.FEATURE ? 4
                : material == FrostedSurfaceDrawable.Material.CARD ? 2
                : material == FrostedSurfaceDrawable.Material.ACTION ? 4 : 0;
        view.setElevation(elevation * density);
        view.setOutlineAmbientShadowColor(Color.BLACK);
        view.setOutlineSpotShadowColor(0x66000000);
    }
}
