package com.shadowphoenix.dietlog.profile;

import java.time.LocalDate;

public final class CalorieCalculator {
    private static final double KCAL_PER_KG_BODY_MASS = 7700.0;

    public static final class Estimate {
        public final double bmr;
        public final double tdee;
        public final double calorieTarget;
        public final double dailyAdjustment;
        public final double weeklyWeightChangeKg;
        public final boolean manualOverride;

        Estimate(double bmr, double tdee, double calorieTarget, double dailyAdjustment,
                 double weeklyWeightChangeKg, boolean manualOverride) {
            this.bmr = bmr;
            this.tdee = tdee;
            this.calorieTarget = calorieTarget;
            this.dailyAdjustment = dailyAdjustment;
            this.weeklyWeightChangeKg = weeklyWeightChangeKg;
            this.manualOverride = manualOverride;
        }
    }

    private CalorieCalculator() {}

    public static Estimate calculate(ProfileSettings settings, LocalDate today) {
        validate(settings, today);
        int age = settings.effectiveAge(today);
        double sexConstant = settings.sex == ProfileSettings.Sex.MALE ? 5.0 : -161.0;
        double bmr = 10.0 * settings.currentWeightKg + 6.25 * settings.heightCm - 5.0 * age + sexConstant;
        double tdee = bmr * settings.activityLevel.multiplier;

        double weeklyRate = resolveWeeklyRate(settings);
        double requestedDailyAdjustment = weeklyRate * KCAL_PER_KG_BODY_MASS / 7.0;
        double maxAdjustment = tdee * settings.goal.maxTdeeAdjustmentFraction;
        double boundedAdjustment;
        if (settings.goal.isWeightStable()) {
            boundedAdjustment = 0.0;
            weeklyRate = 0.0;
        } else if (settings.goal == ProfileSettings.Goal.CUSTOM) {
            boundedAdjustment = clamp(requestedDailyAdjustment, -maxAdjustment, maxAdjustment);
            weeklyRate = boundedAdjustment * 7.0 / KCAL_PER_KG_BODY_MASS;
        } else {
            double signed = Math.copySign(Math.abs(requestedDailyAdjustment), settings.goal.direction);
            boundedAdjustment = clamp(signed, -maxAdjustment, maxAdjustment);
            weeklyRate = boundedAdjustment * 7.0 / KCAL_PER_KG_BODY_MASS;
        }

        double calculatedTarget = roundToNearest10(Math.max(1200.0, tdee + boundedAdjustment));
        double target = settings.manualCalorieOverride
                ? roundToNearest10(settings.manualCalorieTarget)
                : calculatedTarget;
        double displayedAdjustment = (!settings.manualCalorieOverride && settings.goal.isWeightStable())
                ? 0.0 : target - tdee;
        return new Estimate(
                round1(bmr), round1(tdee), target, round1(displayedAdjustment), round3(weeklyRate),
                settings.manualCalorieOverride);
    }

    public static double recommendedWeeklyChange(ProfileSettings settings) {
        if (settings.goal.isWeightStable() || settings.goal == ProfileSettings.Goal.CUSTOM) return 0.0;
        double magnitude = settings.currentWeightKg * settings.goal.defaultBodyWeightFractionPerWeek;
        return round3(clamp(magnitude * settings.goal.direction, -2.0, 2.0));
    }

    private static double resolveWeeklyRate(ProfileSettings settings) {
        if (settings.goal.isWeightStable()) return 0.0;
        if (Math.abs(settings.weeklyWeightChangeKg) >= 0.01) {
            if (settings.goal == ProfileSettings.Goal.CUSTOM) return settings.weeklyWeightChangeKg;
            return Math.abs(settings.weeklyWeightChangeKg) * settings.goal.direction;
        }
        return recommendedWeeklyChange(settings);
    }

    private static void validate(ProfileSettings settings, LocalDate today) {
        if (!Double.isFinite(settings.currentWeightKg) || settings.currentWeightKg < 25 || settings.currentWeightKg > 350) {
            throw new IllegalArgumentException("Current weight must be between 25 and 350 kg");
        }
        if (!Double.isFinite(settings.heightCm) || settings.heightCm < 120 || settings.heightCm > 230) {
            throw new IllegalArgumentException("Height must be between 120 and 230 cm");
        }
        int age = settings.effectiveAge(today);
        if (age < 13 || age > 100) {
            throw new IllegalArgumentException("Age must be between 13 and 100 years");
        }
        if (settings.activityLevel == null || settings.sex == null || settings.goal == null) {
            throw new IllegalArgumentException("Complete profile, activity and goal fields");
        }
        if (settings.manualCalorieOverride && (!Double.isFinite(settings.manualCalorieTarget)
                || settings.manualCalorieTarget < 800 || settings.manualCalorieTarget > 8000)) {
            throw new IllegalArgumentException("Manual calorie target must be between 800 and 8000 kcal");
        }
        if (!Double.isFinite(settings.weeklyWeightChangeKg) || Math.abs(settings.weeklyWeightChangeKg) > 2.0) {
            throw new IllegalArgumentException("Weekly weight change must be within ±2.0 kg/week");
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double roundToNearest10(double value) { return Math.round(value / 10.0) * 10.0; }
    private static double round1(double value) { return Math.round(value * 10.0) / 10.0; }
    private static double round3(double value) { return Math.round(value * 1000.0) / 1000.0; }
}
