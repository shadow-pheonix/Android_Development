package com.shadowphoenix.dietlog.profile;

import java.time.LocalDate;

public final class NutritionGoalCalculator {
    public static final class Goals {
        public final double proteinG, carbsG, fatG, fibreG, sugarLimitG, sodiumLimitMg;
        public final double freeSugarGuideG;
        Goals(double proteinG, double carbsG, double fatG, double fibreG, double freeSugarGuideG, double sodiumLimitMg) {
            this.proteinG = proteinG;
            this.carbsG = carbsG;
            this.fatG = fatG;
            this.fibreG = fibreG;
            this.sugarLimitG = 0; // No automatic total-sugar ceiling: the guideline concerns free sugars.
            this.sodiumLimitMg = sodiumLimitMg;
            this.freeSugarGuideG = freeSugarGuideG;
        }
    }

    private NutritionGoalCalculator() {}

    public static Goals recommend(ProfileSettings settings, double calorieTarget) {
        return recommend(settings, calorieTarget, LocalDate.now());
    }

    public static Goals recommend(ProfileSettings settings, double calorieTarget, LocalDate today) {
        OptionalProfileDetails.validate(settings);
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
        int age = settings.effectiveAge(today);
        if (age < 18) proteinPerKg = 1.0;
        else if (settings.trainingFocus == ProfileSettings.TrainingFocus.NONE
                && settings.goal == ProfileSettings.Goal.MAINTAIN) proteinPerKg = age >= 65 ? 1.2 : 0.83;
        else if (settings.trainingFocus == ProfileSettings.TrainingFocus.ENDURANCE
                && settings.goal == ProfileSettings.Goal.MAINTAIN) proteinPerKg = 1.4;
        double desiredProtein = settings.currentWeightKg * proteinPerKg;
        if (age >= 18 && settings.bodyFatPercent > 0 && settings.goal == ProfileSettings.Goal.CUT
                && (settings.trainingFocus == ProfileSettings.TrainingFocus.RESISTANCE
                || settings.trainingFocus == ProfileSettings.TrainingFocus.MIXED)) {
            // ISSN: 2.3–3.1 g/kg fat-free mass during a resistance-trained energy deficit.
            desiredProtein = settings.currentWeightKg * (1.0 - settings.bodyFatPercent / 100.0) * 2.6;
        }
        // Keep weight-based recommendations inside the energy budget, even
        // with high body weights or a low manual calorie override. Reserve
        // carbohydrate energy before allocating fat; carbs use the remainder.
        double protein = Math.min(Math.max(round(desiredProtein), Math.ceil(calorieTarget * 0.10 / 4.0)),
                Math.floor(calorieTarget * (age < 18 ? 0.30 : 0.35) / 4.0));
        double reservedCarbs = calorieTarget * 0.45;
        double fatBudgetG = round((calorieTarget - protein * 4.0 - reservedCarbs) / 9.0);
        double fat = Math.min(round(Math.max(Math.max(settings.currentWeightKg * 0.8,
                calorieTarget * (age < 18 ? 0.25 : 0.20) / 9.0),
                (calorieTarget * 0.35 - protein * 4.0) / 9.0)),
                Math.min(round(calorieTarget * 0.35 / 9.0), fatBudgetG));
        double carbs = round((calorieTarget - protein * 4.0 - fat * 9.0) / 4.0);
        double fibre = round(Math.max(25.0, calorieTarget / 1000.0 * 14.0));
        double sugar = round(calorieTarget * 0.10 / 4.0);
        return new Goals(protein, carbs, fat, fibre, sugar, 2000.0);
    }

    public static void applyRecommended(ProfileSettings s, double calories, LocalDate today) {
        Goals g = recommend(s, calories, today);
        s.autoNutritionTargets = true;
        s.macroMode = ProfileSettings.MacroMode.GRAMS;
        s.proteinGoalG = g.proteinG; s.carbsGoalG = g.carbsG; s.fatGoalG = g.fatG;
        s.fibreGoalG = g.fibreG; s.sodiumLimitMg = g.sodiumLimitMg;
        // Catalogue sugar is total sugar. WHO's free-sugar ceiling must not be labelled a total-sugar target.
        s.sugarLimitG = 0;
        s.proteinPercent = g.proteinG * 4 / calories * 100;
        s.carbsPercent = g.carbsG * 4 / calories * 100;
        s.fatPercent = g.fatG * 9 / calories * 100;
    }

    public static double macroCalories(double proteinG, double carbsG, double fatG) {
        return proteinG * 4.0 + carbsG * 4.0 + fatG * 9.0;
    }

    public static void validateMacroGrams(ProfileSettings s, double calorieTarget) {
        // Retain the normal manual-entry limits, while accepting generated
        // targets for supported high-energy profiles. Each individual macro
        // is bounded by the energy budget, rather than an incompatible fixed cap.
        validateGrams(s.proteinGoalG, Math.max(500, Math.ceil(calorieTarget / 4.0)), false, "Protein");
        validateGrams(s.carbsGoalG, Math.max(1000, Math.ceil(calorieTarget / 4.0)), true, "Carb");
        validateGrams(s.fatGoalG, Math.max(300, Math.ceil(calorieTarget / 9.0)), false, "Fat");
    }

    private static void validateGrams(double value, double max, boolean zeroAllowed, String name) {
        if (!Double.isFinite(value) || (zeroAllowed ? value < 0 : value <= 0) || value > max) {
            throw new IllegalArgumentException(name + " goal must be between " + (zeroAllowed ? "0" : "1")
                    + " and " + (int) max + " g");
        }
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
