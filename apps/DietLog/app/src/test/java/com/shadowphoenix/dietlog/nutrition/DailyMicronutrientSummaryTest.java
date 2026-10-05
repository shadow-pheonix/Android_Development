package com.shadowphoenix.dietlog.nutrition;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DailyMicronutrientSummaryTest {
    @Test public void totalsKnownFoodsWithoutTurningMissingDataIntoZero() {
        DailyMicronutrientSummary summary = new DailyMicronutrientSummary();
        summary.addFood(MicronutrientValues.empty()
                .with(Micronutrient.CALCIUM, 120.0)
                .with(Micronutrient.IRON, 2.0));
        summary.addFood(MicronutrientValues.empty().with(Micronutrient.CALCIUM, 80.0));
        summary.addFood(MicronutrientValues.empty());

        assertEquals(3, summary.totalFoods());
        assertEquals(2, summary.knownFoods(Micronutrient.CALCIUM));
        assertEquals(200.0, summary.total(Micronutrient.CALCIUM), 0.0001);
        assertFalse(summary.hasFullCoverage(Micronutrient.CALCIUM));
        assertEquals(1, summary.knownFoods(Micronutrient.IRON));
        assertTrue(summary.hasData(Micronutrient.IRON));
        assertFalse(summary.hasData(Micronutrient.VITAMIN_C));
    }

    @Test public void knownZeroCountsAsCoverage() {
        DailyMicronutrientSummary summary = new DailyMicronutrientSummary();
        summary.addFood(MicronutrientValues.empty().with(Micronutrient.SODIUM, 0.0));
        assertEquals(1, summary.knownFoods(Micronutrient.SODIUM));
        assertEquals(0.0, summary.total(Micronutrient.SODIUM), 0.0001);
        assertTrue(summary.hasFullCoverage(Micronutrient.SODIUM));
    }
}
