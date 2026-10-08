package com.shadowphoenix.dietlog.profile;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public final class RecompositionCalculatorTest {
    private final LocalDate today = LocalDate.of(2026, 10, 8);

    private ProfileSettings profile() {
        ProfileSettings s = new ProfileSettings();
        s.goal = ProfileSettings.Goal.RECOMPOSITION;
        return s;
    }

    @Test public void recompositionUsesMifflinStJeorAndActualActivityAtMaintenance() {
        ProfileSettings s = profile();
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, today);
        assertEquals(1642.5, e.bmr, 0.001);
        assertEquals(2545.9, e.tdee, 0.001);
        assertEquals(2550, e.calorieTarget, 0.001);
        assertEquals(0, e.dailyAdjustment, 0.001);
        s.activityLevel = ProfileSettings.ActivityLevel.SEDENTARY;
        assertEquals(1970, CalorieCalculator.calculate(s, today).calorieTarget, 0.001);
        s.activityLevel = ProfileSettings.ActivityLevel.EXTREME;
        assertEquals(3120, CalorieCalculator.calculate(s, today).calorieTarget, 0.001);
    }

    @Test public void sexAndDateOfBirthAreUsedInTheEstimate() {
        ProfileSettings s = profile();
        s.sex = ProfileSettings.Sex.FEMALE;
        assertEquals(2290, CalorieCalculator.calculate(s, today).calorieTarget, 0.001);
        s.sex = ProfileSettings.Sex.MALE;
        s.ageYears = 99;
        s.dateOfBirthIso = "2008-10-08";
        assertEquals(2600, CalorieCalculator.calculate(s, today).calorieTarget, 0.001);
    }

    @Test public void staleWeightLossRateDoesNotCreateARecompositionDeficit() {
        ProfileSettings s = profile();
        s.weeklyWeightChangeKg = -1.0;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, today);
        assertEquals(2550, e.calorieTarget, 0.001);
        assertEquals(0, e.weeklyWeightChangeKg, 0.001);
        assertEquals(0, CalorieCalculator.recommendedWeeklyChange(s), 0.001);
    }

    @Test public void recompositionRaisesProteinAndFitsTheCalorieBudget() {
        NutritionGoalCalculator.Goals g = NutritionGoalCalculator.recommend(profile(), 2550);
        assertEquals(140, g.proteinG, 0.001);
        assertEquals(57, g.fatG, 0.001);
        assertEquals(369, g.carbsG, 0.001);
        assertEquals(2550, NutritionGoalCalculator.macroCalories(g.proteinG, g.carbsG, g.fatG), 2.0);
    }

    @Test public void recommendationsFitAtHighWeightsAndLowManualTargetsForEveryGoal() {
        for (ProfileSettings.Goal goal : ProfileSettings.Goal.values()) {
            for (double weight : new double[]{25, 70, 150, 300, 350}) {
                for (double calories : new double[]{800, 1200, 2550, 6000, 10000}) {
                    ProfileSettings s = profile(); s.goal = goal; s.currentWeightKg = weight;
                    NutritionGoalCalculator.Goals g = NutritionGoalCalculator.recommend(s, calories);
                    assertTrue(g.proteinG > 0 && g.fatG > 0 && g.carbsG >= 50);
                    assertEquals(calories, NutritionGoalCalculator.macroCalories(g.proteinG, g.carbsG, g.fatG), 2.0);
                    s.proteinGoalG = g.proteinG; s.carbsGoalG = g.carbsG; s.fatGoalG = g.fatG;
                    NutritionGoalCalculator.validateMacroGrams(s, calories);
                }
            }
        }
    }

    @Test public void explicitManualCalorieOverrideIsRespected() {
        ProfileSettings s = profile(); s.manualCalorieOverride = true; s.manualCalorieTarget = 1800;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, today);
        assertTrue(e.manualOverride);
        assertEquals(1800, e.calorieTarget, 0.001);
        assertEquals(-745.9, e.dailyAdjustment, 0.001);
    }

    @Test public void existingMaintenanceAndWeightLossTargetsRemainConsistent() {
        ProfileSettings s = profile(); s.goal = ProfileSettings.Goal.MAINTAIN;
        assertEquals(2550, CalorieCalculator.calculate(s, today).calorieTarget, 0.001);
        assertEquals(112, NutritionGoalCalculator.recommend(s, 2550).proteinG, 0.001);
        s.goal = ProfileSettings.Goal.LOSE_WEIGHT;
        assertEquals(2160, CalorieCalculator.calculate(s, today).calorieTarget, 0.001);
        s.currentWeightKg = 300; s.goal = ProfileSettings.Goal.DIRTY_BULK;
        assertEquals(2.0, CalorieCalculator.recommendedWeeklyChange(s), 0.001);
        CalorieCalculator.calculate(s, today);
    }

    @Test(expected = IllegalArgumentException.class) public void nonFiniteBodyValuesAreRejected() {
        ProfileSettings s = profile(); s.currentWeightKg = Double.NaN;
        CalorieCalculator.calculate(s, today);
    }

    @Test(expected = IllegalArgumentException.class) public void nonFiniteMacroTargetsAreRejected() {
        NutritionGoalCalculator.recommend(profile(), Double.POSITIVE_INFINITY);
    }
}
