package com.shadowphoenix.dietlog.profile;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CalorieCalculatorTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    @Test public void mifflinMaleModerateLeanBulkUsesWeeklyRate() {
        ProfileSettings s = new ProfileSettings();
        s.sex = ProfileSettings.Sex.MALE; s.ageYears = 30; s.heightCm = 180; s.currentWeightKg = 80;
        s.activityLevel = ProfileSettings.ActivityLevel.MODERATE; s.goal = ProfileSettings.Goal.LEAN_BULK;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(1780.0, e.bmr, 0.1); assertEquals(2759.0, e.tdee, 0.1);
        assertEquals(2980.0, e.calorieTarget, 0.1); assertEquals(0.20, e.weeklyWeightChangeKg, 0.01);
    }

    @Test public void cutIsBoundedByGoalDeficitCap() {
        ProfileSettings s = new ProfileSettings();
        s.sex = ProfileSettings.Sex.FEMALE; s.ageYears = 30; s.heightCm = 165; s.currentWeightKg = 60;
        s.activityLevel = ProfileSettings.ActivityLevel.SEDENTARY; s.goal = ProfileSettings.Goal.CUT;
        s.weeklyWeightChangeKg = 1.5;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(1320.3, e.bmr, 0.1); assertEquals(1584.3, e.tdee, 0.1);
        assertEquals(1200.0, e.calorieTarget, 0.1); assertTrue(e.dailyAdjustment < 0);
    }

    @Test public void allGoalDirectionsAndDefaultsAreConsistent() {
        ProfileSettings s = new ProfileSettings(); s.currentWeightKg = 80;
        s.goal = ProfileSettings.Goal.LOSE_WEIGHT; assertTrue(CalorieCalculator.recommendedWeeklyChange(s) < 0);
        s.goal = ProfileSettings.Goal.CUT; assertTrue(CalorieCalculator.recommendedWeeklyChange(s) < 0);
        s.goal = ProfileSettings.Goal.GAIN_WEIGHT; assertTrue(CalorieCalculator.recommendedWeeklyChange(s) > 0);
        s.goal = ProfileSettings.Goal.LEAN_BULK; assertEquals(0.20, CalorieCalculator.recommendedWeeklyChange(s), 0.001);
        s.goal = ProfileSettings.Goal.DIRTY_BULK; assertEquals(0.60, CalorieCalculator.recommendedWeeklyChange(s), 0.001);
        s.goal = ProfileSettings.Goal.MAINTAIN; assertEquals(0.0, CalorieCalculator.recommendedWeeklyChange(s), 0.001);
        s.goal = ProfileSettings.Goal.CUSTOM; assertEquals(0.0, CalorieCalculator.recommendedWeeklyChange(s), 0.001);
    }

    @Test public void customSignedRateWorksBothDirections() {
        ProfileSettings s = new ProfileSettings(); s.goal = ProfileSettings.Goal.CUSTOM;
        s.weeklyWeightChangeKg = -0.25;
        assertTrue(CalorieCalculator.calculate(s, TODAY).dailyAdjustment < 0);
        s.weeklyWeightChangeKg = 0.25;
        assertTrue(CalorieCalculator.calculate(s, TODAY).dailyAdjustment > 0);
    }

    @Test public void manualOverrideWins() {
        ProfileSettings s = new ProfileSettings(); s.manualCalorieOverride = true; s.manualCalorieTarget = 2347;
        CalorieCalculator.Estimate e = CalorieCalculator.calculate(s, TODAY);
        assertEquals(2350.0, e.calorieTarget, 0.1); assertTrue(e.manualOverride);
    }

    @Test(expected = IllegalArgumentException.class) public void invalidAgeIsRejectedWithoutSilentClamping() {
        ProfileSettings s = new ProfileSettings(); s.ageYears = 12; CalorieCalculator.calculate(s, TODAY);
    }

    @Test public void percentagesConvertToGrams() {
        double[] grams = NutritionGoalCalculator.gramsFromPercent(2400, 30, 40, 30);
        assertEquals(180.0, grams[0], 0.01); assertEquals(240.0, grams[1], 0.01); assertEquals(80.0, grams[2], 0.01);
    }
}
