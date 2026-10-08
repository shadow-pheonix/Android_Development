package com.shadowphoenix.dietlog.food;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public final class MealSuggestionCacheTest {
    @Test public void switchingBetweenCachedMealsRequiresTheCorrectRows() {
        MealSuggestionCache<String> cache = new MealSuggestionCache<>();
        cache.store("breakfast", Arrays.asList("idli", "milk", "egg"));
        cache.store("lunch", Arrays.asList("annam", "pappu", "curry"));
        cache.markDisplayed("breakfast");
        assertFalse(cache.needsRender("breakfast"));
        assertTrue(cache.needsRender("lunch"));
        assertEquals("annam", cache.get("lunch").get(0));
        cache.markDisplayed("lunch");
        assertFalse(cache.needsRender("lunch"));
        assertTrue(cache.needsRender("breakfast"));
        assertEquals("idli", cache.get("breakfast").get(0));
    }

    @Test public void typedSearchOrHistoryInvalidatesRowsButKeepsTheMealCache() {
        MealSuggestionCache<String> cache = new MealSuggestionCache<>();
        cache.store("dinner", Collections.singletonList("chapati"));
        cache.markDisplayed("dinner");
        cache.invalidateDisplayed();
        assertTrue(cache.needsRender("dinner"));
        assertEquals(Collections.singletonList("chapati"), cache.get("dinner"));
    }

    @Test public void workerResultsAreCopiedAndCannotBeModifiedByTheCaller() {
        MealSuggestionCache<String> cache = new MealSuggestionCache<>();
        ArrayList<String> result = new ArrayList<>(Arrays.asList("banana", "peanuts"));
        cache.store("snacks", result);
        result.clear();
        assertEquals(Arrays.asList("banana", "peanuts"), cache.get("snacks"));
        try { cache.get("snacks").add("rice"); fail("Mutable cache result"); }
        catch (UnsupportedOperationException expected) { /* Snapshot remains immutable. */ }
    }

    @Test public void warmingAnOldMealDoesNotChangeWhatIsDisplayed() {
        MealSuggestionCache<String> cache = new MealSuggestionCache<>();
        assertNull(cache.get("breakfast"));
        assertTrue(cache.needsRender("breakfast"));
        cache.store("lunch", Collections.singletonList("rice"));
        cache.markDisplayed("lunch");
        // A cancelled breakfast request may finish caching, but cannot mark its rows displayed.
        cache.store("breakfast", Collections.singletonList("idli"));
        assertFalse(cache.needsRender("lunch"));
        assertTrue(cache.needsRender("breakfast"));
        assertEquals(Collections.singletonList("rice"), cache.get("lunch"));
    }
}
