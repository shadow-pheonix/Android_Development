package com.shadowphoenix.dietlog.progress;

import com.shadowphoenix.dietlog.progress.ProgressData.DailyNutrition;
import com.shadowphoenix.dietlog.progress.ProgressData.WeightPoint;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ProgressStatsTest {
    @Test
    public void averagesOnlyLoggedDaysAndCalculatesAdherence() {
        List<DailyNutrition> days = Arrays.asList(
                new DailyNutrition(LocalDate.of(2026,10,1), 2000, 150, 250, 70, 4),
                new DailyNutrition(LocalDate.of(2026,10,2), 1800, 140, 220, 60, 3),
                new DailyNutrition(LocalDate.of(2026,10,3), 0, 0, 0, 0, 0));
        ProgressStats.NutritionSummary s = ProgressStats.summarize(days, 2000, 150);
        assertEquals(2, s.loggedDays);
        assertEquals(1900, s.averageCalories, 0.001);
        assertEquals(145, s.averageProtein, 0.001);
        assertEquals(100, s.calorieAdherencePercent, 0.001);
        assertEquals(100, s.proteinAdherencePercent, 0.001);
        assertEquals(100, s.combinedAdherencePercent, 0.001);
    }

    @Test
    public void weeklyWeightRateUsesElapsedDays() {
        List<WeightPoint> points = Arrays.asList(
                new WeightPoint(LocalDate.of(2026,10,1), 55.0),
                new WeightPoint(LocalDate.of(2026,10,8), 55.3));
        assertEquals(0.3, ProgressStats.weeklyWeightRate(points), 0.0001);
    }

    @Test
    public void weeklyWeightRateNeedsTwoDates() {
        assertNull(ProgressStats.weeklyWeightRate(Arrays.asList(
                new WeightPoint(LocalDate.of(2026,10,1), 55.0))));
    }

    @Test
    public void paceDetectsOnTargetAndOppositeDirection() {
        assertEquals("On pace with your selected weekly goal.",
                ProgressStats.paceLabel(0.24, 0.25));
        assertEquals("Weight is moving opposite to the selected goal.",
                ProgressStats.paceLabel(-0.20, 0.25));
    }
}
