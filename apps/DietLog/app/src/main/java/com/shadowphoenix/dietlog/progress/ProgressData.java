package com.shadowphoenix.dietlog.progress;

import java.time.LocalDate;

public final class ProgressData {
    private ProgressData() {}

    public static final class DailyNutrition {
        public final LocalDate date;
        public final double calories;
        public final double protein;
        public final double carbs;
        public final double fat;
        public final int entryCount;

        public DailyNutrition(LocalDate date, double calories, double protein,
                              double carbs, double fat, int entryCount) {
            this.date = date;
            this.calories = calories;
            this.protein = protein;
            this.carbs = carbs;
            this.fat = fat;
            this.entryCount = entryCount;
        }
    }

    public static final class WeightPoint {
        public final LocalDate date;
        public final double weightKg;

        public WeightPoint(LocalDate date, double weightKg) {
            this.date = date;
            this.weightKg = weightKg;
        }
    }
}
