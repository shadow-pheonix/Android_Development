package com.shadowphoenix.dietlog;

import com.shadowphoenix.dietlog.data.FoodEntry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class FoodEntryMathTest {
    @Test
    public void scalesPer100gValues() {
        FoodEntry entry = FoodEntry.fromPer100g("Rice", 250, 130, 2.7, 28.0, 0.3, 1L);
        assertEquals(325.0, entry.calories, 0.0001);
        assertEquals(6.75, entry.protein, 0.0001);
        assertEquals(70.0, entry.carbs, 0.0001);
        assertEquals(0.75, entry.fat, 0.0001);
    }
}
