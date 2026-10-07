package com.shadowphoenix.dietlog.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.PowerManager;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.data.FoodEntry;

/** Small offline vector illustrations; motion runs only while Home is visible and focused. */
public final class MealIllustrationView extends FrameLayout {
    private final ImageView illustration;
    private final ValueAnimator motion;
    private final long phaseOffset;
    private boolean attached;
    private boolean visibleToUser;

    public MealIllustrationView(Context context, String mealType) {
        super(context);
        setBackgroundResource(R.drawable.bg_meal_badge);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setClipChildren(false);
        illustration = new ImageView(context);
        illustration.setImageResource(drawableForMeal(mealType));
        illustration.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = Math.round(2 * getResources().getDisplayMetrics().density);
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        params.setMargins(inset, inset, inset, inset);
        addView(illustration, params);

        phaseOffset = phaseForMeal(mealType);
        motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(3600L);
        motion.setRepeatCount(ValueAnimator.INFINITE);
        motion.setInterpolator(new LinearInterpolator());
        motion.addUpdateListener(value -> {
            float wave = (float) Math.sin((float) value.getAnimatedValue() * Math.PI * 2);
            illustration.setTranslationY(wave * getResources().getDisplayMetrics().density);
            illustration.setRotation(wave * 2.5f);
        });
    }

    private static int drawableForMeal(String mealType) {
        switch (mealType) {
            case FoodEntry.MEAL_LUNCH: return R.drawable.ic_meal_lunch;
            case FoodEntry.MEAL_SNACKS: return R.drawable.ic_meal_snacks;
            case FoodEntry.MEAL_DINNER: return R.drawable.ic_meal_dinner;
            default: return R.drawable.ic_meal_breakfast;
        }
    }

    private static long phaseForMeal(String mealType) {
        switch (mealType) {
            case FoodEntry.MEAL_LUNCH: return 900L;
            case FoodEntry.MEAL_SNACKS: return 1800L;
            case FoodEntry.MEAL_DINNER: return 2700L;
            default: return 0L;
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        visibleToUser = isShown();
        updateMotion();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        visibleToUser = visible;
        updateMotion();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateMotion();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        updateMotion();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        stopMotion();
        super.onDetachedFromWindow();
    }

    private void updateMotion() {
        // Visibility callbacks may arrive during the superclass constructor.
        if (motion == null) return;
        PowerManager power = getContext().getSystemService(PowerManager.class);
        boolean allowed = attached && visibleToUser && isShown() && hasWindowFocus()
                && getWindowVisibility() == View.VISIBLE && ValueAnimator.areAnimatorsEnabled()
                && (power == null || !power.isPowerSaveMode());
        if (allowed && !motion.isStarted()) {
            motion.start();
            motion.setCurrentPlayTime(phaseOffset);
        } else if (!allowed) {
            stopMotion();
        }
    }

    private void stopMotion() {
        if (motion == null) return;
        motion.cancel();
        illustration.setTranslationY(0f);
        illustration.setRotation(0f);
    }
}
