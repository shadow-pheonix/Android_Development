package com.shadowphoenix.dietlog.food;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ServingOptionTest {
    private static CatalogFood food(String name, String source, String quantity, String serving, Double servingG) {
        return new CatalogFood("code", null, name, null, quantity, null, source,
                100.0, 10.0, 20.0, 5.0, null, null, null, null, null,
                serving, servingG, null, null, null, null, null, null, null);
    }

    @Test
    public void sourceServingIsPreferred() {
        List<ServingOption> options = ServingOption.forFood(
                food("Idli", "INDB", null, "2 pieces", 84.0));
        assertEquals("2 pieces", options.get(0).label);
        assertEquals(84.0, options.get(0).grams, 0.001);
    }

    @Test
    public void commonFoodsGetUsefulFallbackServing() {
        List<ServingOption> options = ServingOption.forFood(
                food("Idli", "INDB", null, null, null));
        assertEquals("1 idli", options.get(0).label);
        assertEquals(50.0, options.get(0).grams, 0.001);
    }

    @Test
    public void packagedGramQuantityAddsPackOption() {
        List<ServingOption> options = ServingOption.forFood(
                food("Oats", "OFF", "250 g", "30 g", 30.0));
        assertTrue(options.stream().anyMatch(o -> "1 pack".equals(o.label) && o.grams == 250.0));
    }
}
