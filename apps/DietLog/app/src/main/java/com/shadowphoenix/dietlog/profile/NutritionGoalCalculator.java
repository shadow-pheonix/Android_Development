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
        if (!Double.isFinite(calorieTarget) || calorieTarget < 800
                || !Double.isFinite(settings.currentWeightKg) || settings.currentWeightKg < 25
                || settings.currentWeightKg > 350 || settings.goal == null) {
            throw new IllegalArgumentException("Complete valid body details and a calorie target before generating macros");
        }
        double proteinPerKg;
        switch (settings.goal) {
            case CUT: proteinPerKg = 2.2; break;
            case RECOMPOSITION:
            case LOSE_WEIGHT: proteinPerKg = 2.0; break;
            case LEAN_BULK:
            case GAIN_WEIGHT: proteinPerKg = 1.8; break;
            case DIRTY_BULK:
            case MAINTAIN: proteinPerKg = 1.6; break;
            default: proteinPerKg = 1.8; break;
        }
        // Keep weight-based recommendations inside the energy budget, even
        // with high body weights or a low manual calorie override. Reserve
        // carbohydrate energy before allocating fat; carbs use the remainder.
        double protein = Math.min(round(settings.currentWeightKg * proteinPerKg), Math.floor(calorieTarget * 0.40 / 4.0));
        double reservedCarbs = Math.max(200.0, calorieTarget * 0.20);
        double fatBudgetG = Math.floor((calorieTarget - protein * 4.0 - reservedCarbs) / 9.0);
        double fat = Math.min(round(Math.max(settings.currentWeightKg * 0.8, calorieTarget * 0.20 / 9.0)), fatBudgetG);
        double carbs = round((calorieTarget - protein * 4.0 - fat * 9.0) / 4.0);
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
