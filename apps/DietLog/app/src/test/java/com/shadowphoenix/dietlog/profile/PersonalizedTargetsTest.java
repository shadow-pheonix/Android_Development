package com.shadowphoenix.dietlog.profile;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public final class PersonalizedTargetsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    @Test public void omittedDetailsRetainAdultBaselineAndDoNotRequireSetup() {
        ProfileSettings s = new ProfileSettings();
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(1642.5, e.bmr, 0.001);
        assertEquals(2550, e.calorieTarget, 0.001);
        assertEquals(CalorieCalculator.RestingMethod.MIFFLIN, e.restingMethod);
        assertFalse(ProfileSetup.needsSetup(true, true, s, TODAY));
    }

    @Test public void measuredBodyFatUsesCunninghamLeanMass() {
        ProfileSettings s = new ProfileSettings(); s.bodyFatPercent = 20;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(1732, e.bmr, 0.001); // 500 + 22 * (70 * 0.80)
        assertEquals(2684.6, e.tdee, 0.001);
        assertEquals(2680, e.calorieTarget, 0.001);
        assertEquals(CalorieCalculator.RestingMethod.CUNNINGHAM, e.restingMethod);
    }

    @Test public void measuredRestingCaloriesTakePriorityOverBodyFatEquation() {
        ProfileSettings s = new ProfileSettings(); s.bodyFatPercent = 20; s.measuredRmrKcal = 1600;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(1600, e.bmr, 0.001);
        assertEquals(2480, e.tdee, 0.001);
        assertEquals(CalorieCalculator.RestingMethod.MEASURED, e.restingMethod);
    }

    @Test public void observedMaintenanceAlreadyIncludesActivityAndManualTargetStillWins() {
        ProfileSettings s = new ProfileSettings(); s.observedMaintenanceKcal = 2300; s.measuredRmrKcal = 1700;
        for (ProfileSettings.ActivityLevel level : ProfileSettings.ActivityLevel.values()) {
            s.activityLevel = level;
            CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
            assertTrue(e.observedMaintenance);
            assertEquals(2300, e.tdee, 0.001);
            assertEquals(2300, e.calorieTarget, 0.001);
        }
        s.manualCalorieOverride = true; s.manualCalorieTarget = 1900;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(1900, e.calorieTarget, 0.001);
        assertEquals(-400, e.dailyAdjustment, 0.001);
        assertEquals(-400 * 7.0 / 7700, e.weeklyWeightChangeKg, 0.0005);
    }

    @Test public void effectivePaceReflectsFloorCapAndRounding() {
        ProfileSettings s = new ProfileSettings(); s.goal = ProfileSettings.Goal.CUT;
        s.observedMaintenanceKcal = 1400; s.weeklyWeightChangeKg = -2;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertTrue(e.minimumApplied);
        assertEquals(1200, e.calorieTarget, 0.001);
        assertEquals(-200 * 7.0 / 7700, e.weeklyWeightChangeKg, 0.0005);
        s.observedMaintenanceKcal = 2500;
        e = CalorieCalculator.calculate(s, TODAY);
        assertFalse(e.minimumApplied);
        assertEquals(1880, e.calorieTarget, 0.001);
        assertEquals(-620 * 7.0 / 7700, e.weeklyWeightChangeKg, 0.0005);
    }

    @Test public void growingTeenUsesAgeSpecificMaintenanceNotAdultDeficitsOrCalibration() {
        ProfileSettings s = new ProfileSettings(); s.ageYears = 16; s.goal = ProfileSettings.Goal.CUT;
        s.bodyFatPercent = 20; s.measuredRmrKcal = 1200; s.observedMaintenanceKcal = 1800;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(1896.2, e.bmr, 0.1);
        assertEquals(2968.4, e.tdee, 0.1);
        assertEquals(2970, e.calorieTarget, 0.001);
        assertEquals(0, e.weeklyWeightChangeKg, 0.001);
        assertTrue(e.growthMaintenance); assertFalse(e.observedMaintenance);
        assertEquals(0, CalorieCalculator.recommendedWeeklyChange(s, TODAY), 0);
        s.sex = ProfileSettings.Sex.FEMALE;
        assertEquals(1629.5, CalorieCalculator.calculate(s, TODAY).bmr, 0.1);
        s.ageYears = 18;
        assertFalse(CalorieCalculator.calculate(s, TODAY).growthMaintenance);
        assertTrue(CalorieCalculator.calculate(s, TODAY).observedMaintenance);
    }

    @Test public void trainingPersonalizesProteinButNeverAddsActivityCaloriesTwice() {
        ProfileSettings s = new ProfileSettings();
        s.trainingFocus = ProfileSettings.TrainingFocus.NONE;
        assertEquals(58, NutritionGoalCalculator.recommend(s, 2000, TODAY).proteinG, 0.001);
        s.ageYears = 65;
        assertEquals(84, NutritionGoalCalculator.recommend(s, 2000, TODAY).proteinG, 0.001);
        s.ageYears = 25; s.trainingFocus = ProfileSettings.TrainingFocus.ENDURANCE;
        assertEquals(98, NutritionGoalCalculator.recommend(s, 2000, TODAY).proteinG, 0.001);
        double calories = CalorieCalculator.calculate(s, TODAY).calorieTarget;
        s.trainingFocus = ProfileSettings.TrainingFocus.RESISTANCE;
        assertEquals(calories, CalorieCalculator.calculate(s, TODAY).calorieTarget, 0.001);
        s.goal = ProfileSettings.Goal.CUT; s.bodyFatPercent = 20;
        assertEquals(146, NutritionGoalCalculator.recommend(s, 2200, TODAY).proteinG, 0.001);
        s.trainingFocus = ProfileSettings.TrainingFocus.NONE;
        assertEquals(154, NutritionGoalCalculator.recommend(s, 2200, TODAY).proteinG, 0.001);
        s.goal = ProfileSettings.Goal.RECOMPOSITION;
        assertEquals(140, NutritionGoalCalculator.recommend(s, 2200, TODAY).proteinG, 0.001);
    }

    @Test public void macroEnergyRangesAndTotalsHoldAcrossSupportedProfiles() {
        for (int age : new int[]{13, 25, 65}) for (ProfileSettings.Goal goal : ProfileSettings.Goal.values())
            for (double weight : new double[]{25, 70, 150, 350}) for (double calories : new double[]{800, 1200, 2550, 10000}) {
                ProfileSettings s = new ProfileSettings(); s.ageYears = age; s.goal = goal; s.currentWeightKg = weight;
                NutritionGoalCalculator.Goals g = NutritionGoalCalculator.recommend(s, calories, TODAY);
                assertEquals(calories, NutritionGoalCalculator.macroCalories(g.proteinG, g.carbsG, g.fatG), 2);
                assertTrue(g.proteinG * 4 / calories <= (age < 18 ? 0.30 : 0.35) + 0.005);
                assertTrue(g.proteinG * 4 / calories >= 0.10 - 0.005);
                assertTrue(g.carbsG * 4 / calories >= 0.45 - 0.01);
                assertTrue(g.carbsG * 4 / calories <= 0.65 + 0.01);
                assertTrue(g.fatG * 9 / calories >= (age < 18 ? 0.25 : 0.20) - 0.01);
                assertTrue(g.fatG * 9 / calories <= 0.35 + 0.01);
            }
    }

    @Test public void automaticTargetsKeepFreeSugarAdviceSeparateFromTotalSugar() {
        ProfileSettings s = new ProfileSettings();
        NutritionGoalCalculator.applyRecommended(s, 2000, TODAY);
        assertTrue(s.autoNutritionTargets);
        assertEquals(ProfileSettings.MacroMode.GRAMS, s.macroMode);
        assertEquals(0, s.sugarLimitG, 0.001);
        assertEquals(50, NutritionGoalCalculator.recommend(s, 2000, TODAY).freeSugarGuideG, 0.001);
        assertEquals(28, s.fibreGoalG, 0.001);
        assertEquals(2000, s.sodiumLimitMg, 0.001);
        s.manualCalorieOverride = true; s.manualCalorieTarget = 1800;
        NutritionGoalCalculator.applyRecommended(s, CalorieCalculator.calculate(s, TODAY).calorieTarget, TODAY);
        assertEquals(1800, NutritionGoalCalculator.macroCalories(s.proteinGoalG, s.carbsGoalG, s.fatGoalG), 2);
    }

    @Test public void optionalMeasurementsRejectNonFiniteOrOutOfRangeValues() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 2, 66}) {
            ProfileSettings s = new ProfileSettings(); s.bodyFatPercent = invalid;
            assertInvalid(s);
        }
        ProfileSettings s = new ProfileSettings(); s.measuredRmrKcal = 499; assertInvalid(s);
        s.measuredRmrKcal = 0; s.observedMaintenanceKcal = 999; assertInvalid(s);
        s.observedMaintenanceKcal = 0; s.trainingFocus = null; assertInvalid(s);
    }

    @Test public void profileChangesOnlyRecalculateTargetsAfterExplicitOptIn() {
        ProfileSettings s = new ProfileSettings();
        s.proteinGoalG = 123; s.carbsGoalG = 222; s.fatGoalG = 66; s.sugarLimitG = 40;
        s.observedMaintenanceKcal = 2600;
        NutritionGoalCalculator.refreshRecommendedIfEnabled(s, TODAY);
        assertEquals(123, s.proteinGoalG, 0.001); assertEquals(222, s.carbsGoalG, 0.001);
        assertEquals(66, s.fatGoalG, 0.001); assertEquals(40, s.sugarLimitG, 0.001);
        s.autoNutritionTargets = true;
        NutritionGoalCalculator.refreshRecommendedIfEnabled(s, TODAY);
        assertEquals(2600, NutritionGoalCalculator.macroCalories(s.proteinGoalG, s.carbsGoalG, s.fatGoalG), 2);
        s.observedMaintenanceKcal = 2200;
        NutritionGoalCalculator.refreshRecommendedIfEnabled(s, TODAY);
        assertEquals(2200, NutritionGoalCalculator.macroCalories(s.proteinGoalG, s.carbsGoalG, s.fatGoalG), 2);
        s.autoNutritionTargets = false; double carbs = s.carbsGoalG;
        s.observedMaintenanceKcal = 2400;
        NutritionGoalCalculator.refreshRecommendedIfEnabled(s, TODAY);
        assertEquals(carbs, s.carbsGoalG, 0.001);
    }

    private static void assertInvalid(ProfileSettings s) {
        try { CalorieCalculator.calculate(s, TODAY); fail("Invalid optional value accepted"); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }
}
