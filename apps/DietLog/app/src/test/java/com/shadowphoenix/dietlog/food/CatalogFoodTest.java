package com.shadowphoenix.dietlog.food;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CatalogFoodTest {
    private static CatalogFood food(Double calories, Double protein, Double carbs, Double fat) {
        return new CatalogFood("code", null, "Food", null, null, null, "TEST",
                calories, protein, carbs, fat, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    public void completeCoreMacrosRequiresAllFourValues() {
        assertTrue(food(100.0, 10.0, 20.0, 5.0).hasCompleteCoreMacros());
        assertFalse(food(null, 10.0, 20.0, 5.0).hasCompleteCoreMacros());
        assertFalse(food(100.0, null, 20.0, 5.0).hasCompleteCoreMacros());
        assertFalse(food(100.0, 10.0, null, 5.0).hasCompleteCoreMacros());
        assertFalse(food(100.0, 10.0, 20.0, null).hasCompleteCoreMacros());
    }

    @Test
    public void zeroMacroValuesAreStillComplete() {
        assertTrue(food(0.0, 0.0, 0.0, 0.0).hasCompleteCoreMacros());
    }
}
