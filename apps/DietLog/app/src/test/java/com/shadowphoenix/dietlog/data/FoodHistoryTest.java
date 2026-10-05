package com.shadowphoenix.dietlog.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class FoodHistoryTest {
    private static FoodEntry entry(long id, String name, long time, String sourceId) {
        return new FoodEntry(id, name, 100, 100, 10, 10, 2, time,
                FoodEntry.MEAL_LUNCH, "INDB", sourceId, null);
    }

    @Test
    public void recentKeepsNewestDistinctFood() {
        List<FoodEntry> history = Arrays.asList(
                entry(3, "Idli", 300, "idli"),
                entry(2, "Rice", 200, "rice"),
                entry(1, "Idli", 100, "idli"));
        List<FoodHistory.Item> recent = FoodHistory.recent(history, 10);
        assertEquals("Idli", recent.get(0).entry.name);
        assertEquals(2, recent.get(0).count);
        assertEquals("Rice", recent.get(1).entry.name);
    }

    @Test
    public void frequentSortsByCountThenRecency() {
        List<FoodEntry> history = Arrays.asList(
                entry(5, "Rice", 500, "rice"),
                entry(4, "Idli", 400, "idli"),
                entry(3, "Idli", 300, "idli"),
                entry(2, "Rice", 200, "rice"),
                entry(1, "Idli", 100, "idli"));
        List<FoodHistory.Item> frequent = FoodHistory.frequent(history, 10);
        assertEquals("Idli", frequent.get(0).entry.name);
        assertEquals(3, frequent.get(0).count);
        assertEquals("Rice", frequent.get(1).entry.name);
        assertEquals(2, frequent.get(1).count);
    }
}
