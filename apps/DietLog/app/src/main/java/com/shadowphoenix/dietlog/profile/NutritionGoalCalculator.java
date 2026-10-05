package com.shadowphoenix.dietlog.profile;

public final class NutritionGoalCalculator {
    public static final class Goals {
        public final double proteinG, carbsG, fatG, fibreG, sugarLimitG, sodiumLimitMg;
        Goals(double proteinG, double carbsG, double fatG, double fibreG, double sugarLimitG, double sodiumLimitMg) {
            this.proteinG = proteinG;
            this.carbsG = carbsG;
            this.fatG = fatG;
            this.fibreG = fibreG;
            this.sugarLimitG = sugarLimitG;
            this.sodiumLimitMg = sodiumLimitMg;
        }
    }

    private NutritionGoalCalculator() {}

    public static Goals recommend(ProfileSettings settings, double calorieTarget) {
        double proteinPerKg;
        switch (settings.goal) {
            case CUT: proteinPerKg = 2.2; break;
            case LOSE_WEIGHT: proteinPerKg = 2.0; break;
            case LEAN_BULK:
            case GAIN_WEIGHT: proteinPerKg = 1.8; break;
            case DIRTY_BULK:
            case MAINTAIN: proteinPerKg = 1.6; break;
            default: proteinPerKg = 1.8; break;
        }
        double protein = round(settings.currentWeightKg * proteinPerKg);
        double fat = round(Math.max(settings.currentWeightKg * 0.8, calorieTarget * 0.20 / 9.0));
        double remainingKcal = calorieTarget - protein * 4.0 - fat * 9.0;
        if (remainingKcal < calorieTarget * 0.20) {
            fat = round(settings.currentWeightKg * 0.7);
            remainingKcal = calorieTarget - protein * 4.0 - fat * 9.0;
        }
        double carbs = round(Math.max(50.0, remainingKcal / 4.0));
        double fibre = round(Math.max(25.0, calorieTarget / 1000.0 * 14.0));
        double sugar = round(calorieTarget * 0.10 / 4.0);
        return new Goals(protein, carbs, fat, fibre, sugar, 2000.0);
    }

    public static double macroCalories(double proteinG, double carbsG, double fatG) {
        return proteinG * 4.0 + carbsG * 4.0 + fatG * 9.0;
    }

    public static double[] gramsFromPercent(double calorieTarget, double proteinPct, double carbsPct, double fatPct) {
        return new double[]{
                calorieTarget * proteinPct / 100.0 / 4.0,
                calorieTarget * carbsPct / 100.0 / 4.0,
                calorieTarget * fatPct / 100.0 / 9.0
        };
    }

    private static double round(double value) { return Math.round(value); }
}
