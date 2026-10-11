package com.shadowphoenix.dietlog.planner;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MealDataTest {
    static PlannerFood food(String id, String tag, boolean known) {
        return new PlannerFood(
                id,
                id,
                "raw",
                new double[] {100, 10, 10, 2, Double.NaN, 0, 1, 0},
                new HashSet<>(Arrays.asList(tag)),
                known,
                10);
    }

    static MealRecipe recipe(PlannerFood f) {
        return new MealRecipe(
                "r",
                "Meal",
                15,
                10,
                true,
                "Indian",
                "Cook",
                200,
                Arrays.asList(new MealRecipe.Ingredient(f, 50)));
    }

    @Test
    public void allergyAndDietFiltersFailClosedForUnknownIngredients() {
        PlannerProfile p = new PlannerProfile();
        p.allergies.add("dairy");
        assertFalse(recipe(food("milk", "dairy", true)).allowed(p, 0));
        assertFalse(recipe(food("dish", "vegetable", false)).allowed(p, 0));
        assertTrue(recipe(food("rice", "grain", true)).allowed(p, 0));
        p.allergies.clear();
        p.diet = PlannerProfile.Diet.VEGAN;
        assertFalse(recipe(food("egg", "egg", true)).allowed(p, 0));
        p.diet = PlannerProfile.Diet.EGGS_ALLOWED;
        assertTrue(recipe(food("egg", "egg", true)).allowed(p, 0));
        assertFalse(recipe(food("fish", "meat", true)).allowed(p, 0));
    }

    @Test
    public void neverRatedIngredientBlocksTheEntireRecipe() {
        PlannerProfile p = new PlannerProfile();
        p.ratings.put("rice", FoodPreference.NEVER);
        assertFalse(recipe(food("rice", "grain", true)).allowed(p, 0));
    }

    @Test
    public void coreTotalsScaleButUnknownFibreIsNotZero() {
        double[] n = recipe(food("rice", "grain", true)).nutrition(2);
        assertEquals(100, n[0], 0);
        assertEquals(10, n[1], 0);
        assertTrue(Double.isNaN(n[4]));
    }

    @Test
    public void costUsesAmountAndShoppingUsesWholePacks() {
        MealRecipe r = recipe(food("rice", "grain", true));
        PlannerProfile p = new PlannerProfile();
        assertEquals(5, PriceModel.consumed(r, 1, p).amount, 0);
        assertFalse(PriceModel.consumed(r, 1, p).allPricesEntered);
        p.prices.put("rice", 12.0);
        assertEquals(6, PriceModel.consumed(r, 1, p).amount, 0);
        assertTrue(PriceModel.consumed(r, 1, p).allPricesEntered);
        assertEquals(80, PriceModel.shoppingCost(600, 100, 1000, 80), 0);
        assertEquals(0, PriceModel.shoppingCost(100, 200, 1000, 80), 0);
    }
}
