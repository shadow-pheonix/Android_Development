package com.shadowphoenix.dietlog.progress;

import com.shadowphoenix.dietlog.progress.ProgressData.DailyNutrition;
import com.shadowphoenix.dietlog.progress.ProgressData.WeightPoint;

import java.time.LocalDate;
import java.util.List;

public final class ProgressStats {
    public static final class NutritionSummary {
        public final int loggedDays;
        public final double averageCalories;
        public final double averageProtein;
        public final double averageCarbs;
        public final double averageFat;
        public final double calorieAdherencePercent;
        public final double proteinAdherencePercent;
        public final double combinedAdherencePercent;

        NutritionSummary(int loggedDays, double averageCalories, double averageProtein,
                         double averageCarbs, double averageFat,
                         double calorieAdherencePercent, double proteinAdherencePercent,
                         double combinedAdherencePercent) {
            this.loggedDays = loggedDays;
            this.averageCalories = averageCalories;
            this.averageProtein = averageProtein;
            this.averageCarbs = averageCarbs;
            this.averageFat = averageFat;
            this.calorieAdherencePercent = calorieAdherencePercent;
            this.proteinAdherencePercent = proteinAdherencePercent;
            this.combinedAdherencePercent = combinedAdherencePercent;
        }
    }

    private ProgressStats() {}

    public static NutritionSummary summarize(List<DailyNutrition> days,
                                             double calorieGoal,
                                             double proteinGoal) {
        return summarize(days, calorieGoal, proteinGoal, null);
    }

    public static NutritionSummary summarizeCompletedDays(List<DailyNutrition> days,
                                                          LocalDate today,
                                                          double calorieGoal,
                                                          double proteinGoal) {
        return summarize(days, calorieGoal, proteinGoal, today);
    }

    private static NutritionSummary summarize(List<DailyNutrition> days,
                                              double calorieGoal,
                                              double proteinGoal,
                                              LocalDate excludeFromDate) {
        int logged = 0;
        int calorieHits = 0;
        int proteinHits = 0;
        int combinedHits = 0;
        double calories = 0;
        double protein = 0;
        double carbs = 0;
        double fat = 0;

        for (DailyNutrition day : days) {
            if (day.entryCount <= 0) continue;
            if (excludeFromDate != null && (day.date == null || !day.date.isBefore(excludeFromDate))) {
                continue;
            }
            logged++;
            calories += day.calories;
            protein += day.protein;
            carbs += day.carbs;
            fat += day.fat;

            boolean calorieHit = calorieGoal > 0 &&
                    day.calories >= calorieGoal * 0.90 &&
                    day.calories <= calorieGoal * 1.10;
            boolean proteinHit = proteinGoal > 0 &&
                    day.protein >= proteinGoal * 0.90;
            if (calorieHit) calorieHits++;
            if (proteinHit) proteinHits++;
            if (calorieHit && proteinHit) combinedHits++;
        }

        if (logged == 0) {
            return new NutritionSummary(0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new NutritionSummary(
                logged,
                calories / logged,
                protein / logged,
                carbs / logged,
                fat / logged,
                calorieHits * 100.0 / logged,
                proteinHits * 100.0 / logged,
                combinedHits * 100.0 / logged
        );
    }

    public static Double weeklyWeightRate(List<WeightPoint> points) {
        if (points == null || points.size() < 2) return null;
        WeightPoint first = points.get(0);
        WeightPoint last = points.get(points.size() - 1);
        long days = java.time.temporal.ChronoUnit.DAYS.between(first.date, last.date);
        if (days <= 0) return null;
        return (last.weightKg - first.weightKg) * 7.0 / days;
    }

    public static String paceLabel(Double actualWeeklyKg, double targetWeeklyKg) {
        if (actualWeeklyKg == null) return "Log weight on at least two different days to measure pace.";
        double actual = actualWeeklyKg;
        if (Math.abs(targetWeeklyKg) < 0.01) {
            if (Math.abs(actual) <= 0.10) return "On pace for maintenance.";
            return actual > 0 ? "Weight is trending up vs maintenance."
                    : "Weight is trending down vs maintenance.";
        }
        if (Math.signum(actual) != Math.signum(targetWeeklyKg) && Math.abs(actual) > 0.05) {
            return "Weight is moving opposite to the selected goal.";
        }
        double ratio = Math.abs(actual) / Math.abs(targetWeeklyKg);
        if (ratio >= 0.70 && ratio <= 1.30) return "On pace with your selected weekly goal.";
        if (ratio < 0.70) return "Weight change is slower than your selected weekly goal.";
        return "Weight change is faster than your selected weekly goal.";
    }
}
