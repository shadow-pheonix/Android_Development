package com.shadowphoenix.dietlog.planner;

import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StandardPlanTest {
    private List<MealRecipe> recipes() {
        List<MealRecipe> recipes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            PlannerFood food =
                    new PlannerFood(
                            "food" + i,
                            "Food " + i,
                            "raw",
                            new double[] {300 + i * 20, 20 + i, 40, 6, 4, 20, 2, 1},
                            Collections.emptySet(),
                            true,
                            5 + i * 3);
            recipes.add(
                    new MealRecipe(
                            "meal:" + i,
                            "Meal " + i,
                            15,
                            10,
                            true,
                            "Indian",
                            "Cook",
                            200,
                            Collections.singletonList(new MealRecipe.Ingredient(food, 100))));
        }
        return recipes;
    }

    private DayPlanner.Request request() {
        DayPlanner.Request r = new DayPlanner.Request();
        r.targets = new double[] {2000, 120, 280, 45, 30, 2000, 50, 0};
        return r;
    }

    @Test
    public void standardPlanIgnoresDiaryConsumptionAndFinitePreparedInventory() {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan clean = StandardPlan.generate(recipes, r);
        r.consumed[0] = 1800;
        r.consumedCost = 300;
        r.readyServings.put("meal:0", 10.0);
        r.pantryGrams.put("food0", 500.0);
        StandardPlan withLogs = StandardPlan.generate(recipes, r);
        assertArrayEquals(clean.result.totals, withLogs.result.totals, 0);
        assertEquals(clean.result.cost, withLogs.result.cost, 0);
        for (DayPlanner.Choice c : withLogs.selected.values()) assertEquals(0, c.preparedScale, 0);
    }

    @Test
    public void alternativesAreDistinctBoundedSuitableAndExplicitExclusionsWin() {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        r.profile.ratings.put("meal:0", FoodPreference.NEVER);
        StandardPlan plan = StandardPlan.generate(recipes, r);
        assertEquals(4, plan.selected.size());
        for (int meal = 0; meal < 4; meal++) {
            List<DayPlanner.Choice> alternatives = plan.alternatives.get(meal);
            assertEquals(4, alternatives.size());
            Set<String> ids = new HashSet<>();
            for (DayPlanner.Choice c : alternatives) {
                assertTrue(ids.add(c.recipe.id));
                assertNotEquals("meal:0", c.recipe.id);
                assertTrue(c.recipe.allowed(r.profile, meal));
            }
        }
    }

    @Test
    public void changingOneMealPreservesOtherSelectionsAndRecalculatesExactTotals()
            throws Exception {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        Map<Integer, DayPlanner.Choice> before = new HashMap<>(plan.selected);
        DayPlanner.Choice changed = plan.alternatives.get(1).get(1);
        plan.choose(changed, recipes, r);
        for (int meal : new int[] {0, 2, 3}) assertSame(before.get(meal), plan.selected.get(meal));
        double[] expected = new double[8];
        for (DayPlanner.Choice c : plan.selected.values()) {
            double[] n = c.recipe.nutrition(c.scale);
            for (int i = 0; i < 8; i++) expected[i] += n[i];
        }
        assertArrayEquals(expected, plan.result.totals, 0.000001);
        StandardPlan restored =
                StandardPlan.fromJson(new JSONObject(plan.toJson().toString()), recipes, r);
        assertEquals(changed.recipe.id, restored.selected.get(1).recipe.id);
        assertArrayEquals(plan.result.totals, restored.result.totals, 0);
    }

    @Test
    public void singleMealRefreshPreservesEverySelectedMeal() {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        Map<Integer, DayPlanner.Choice> before = new HashMap<>(plan.selected);
        plan.refreshMeal(2, recipes, r);
        for (int m = 0; m < 4; m++) assertSame(before.get(m), plan.selected.get(m));
    }

    @Test
    public void restoringRevalidatesChangedRestrictionsWithoutRewritingOtherMeals()
            throws Exception {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        String removed = plan.selected.get(0).recipe.id;
        r.profile.ratings.put(removed, FoodPreference.NEVER);
        StandardPlan restored = StandardPlan.fromJson(plan.toJson(), recipes, r);
        for (DayPlanner.Choice c : restored.selected.values())
            assertNotEquals(removed, c.recipe.id);
        assertFalse(restored.result.targetClose);
        assertTrue(restored.result.notes.stream().anyMatch(n -> n.contains("incomplete")));
    }

    @Test
    public void editedMealCannotBypassRestrictionsAndBudgetWarningUsesSelectedCombination() {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        r.profile.ratings.put("meal:0", FoodPreference.NEVER);
        try {
            plan.choose(new DayPlanner.Choice(1, recipes.get(0), 1), recipes, r);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        r.profile.dailyBudget = 1;
        plan.evaluate(recipes, r);
        assertFalse(plan.result.budgetMet);
        assertTrue(plan.result.notes.stream().anyMatch(n -> n.contains("exceeds")));
    }

    @Test
    public void failedChoiceLeavesSelectionsAlternativesAndResultUnchanged() throws Exception {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        String before = plan.toJson().toString();
        DayPlanner.Result beforeResult = plan.result;
        DayPlanner.Choice choice = plan.alternatives.get(1).get(1);
        r.targets[0] = 10;
        try {
            plan.choose(choice, recipes, r);
            fail("Invalid targets must reject evaluation");
        } catch (IllegalArgumentException expected) {
        }
        assertEquals(before, plan.toJson().toString());
        assertSame(beforeResult, plan.result);
        r.targets[0] = 2000;
        r.cancelled = () -> true;
        try {
            plan.choose(choice, recipes, r);
            fail("Cancellation must stop evaluation");
        } catch (java.util.concurrent.CancellationException expected) {
        }
        assertEquals(before, plan.toJson().toString());
        assertSame(beforeResult, plan.result);
    }

    @Test
    public void selectingExistingAlternativeKeepsCarouselOrderAndEditedPortion() {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        List<DayPlanner.Choice> before = new ArrayList<>(plan.alternatives.get(1));
        DayPlanner.Choice option = before.get(2);
        DayPlanner.Choice edited = new DayPlanner.Choice(1, option.recipe, 0.5);
        plan.choose(edited, recipes, r);
        for (int i = 0; i < before.size(); i++)
            assertEquals(before.get(i).recipe.id, plan.alternatives.get(1).get(i).recipe.id);
        assertSame(edited, plan.selected.get(1));
        assertSame(edited, plan.alternatives.get(1).get(2));
        double cost = 0;
        for (DayPlanner.Choice choice : plan.selected.values())
            cost += PriceModel.consumed(choice.recipe, choice.scale, r.profile).amount;
        assertEquals(cost, plan.result.cost, 0.000001);
    }

    @Test
    public void refreshDoesNotOfferForbiddenCurrentMealOrPreparedOnlyRecipes() {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        Map<Integer, DayPlanner.Choice> before = new HashMap<>(plan.selected);
        String forbidden = before.get(2).recipe.id;
        r.profile.ratings.put(forbidden, FoodPreference.NEVER);
        plan.refreshMeal(2, recipes, r);
        for (int meal = 0; meal < 4; meal++) assertSame(before.get(meal), plan.selected.get(meal));
        assertEquals(4, plan.alternatives.get(2).size());
        for (DayPlanner.Choice choice : plan.alternatives.get(2)) {
            assertNotEquals(forbidden, choice.recipe.id);
            assertEquals(0, choice.preparedScale, 0);
            assertTrue(choice.recipe.allowed(r.profile, 2));
        }
        r.profile.canCook = false;
        for (MealRecipe recipe : recipes) r.readyServings.put(recipe.id, 10.0);
        plan.refreshMeal(2, recipes, r);
        assertTrue(plan.alternatives.get(2).isEmpty());
    }

    @Test
    public void restoreDropsDisabledMealsAndRechecksIngredientAndCookingRestrictions()
            throws Exception {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request();
        StandardPlan plan = StandardPlan.generate(recipes, r);
        JSONObject saved = plan.toJson();
        r.profile.meals[3] = false;
        String forbiddenFood = plan.selected.get(0).recipe.ingredients.get(0).food.id;
        r.profile.ratings.put(forbiddenFood, FoodPreference.NEVER);
        StandardPlan restored = StandardPlan.fromJson(saved, recipes, r);
        assertFalse(restored.selected.containsKey(3));
        assertFalse(restored.alternatives.containsKey(3));
        for (DayPlanner.Choice choice : restored.selected.values())
            assertTrue(choice.recipe.allowed(r.profile, choice.meal));
        for (List<DayPlanner.Choice> options : restored.alternatives.values())
            for (DayPlanner.Choice choice : options)
                assertTrue(choice.recipe.allowed(r.profile, choice.meal));
        r.profile.canCook = false;
        for (MealRecipe recipe : recipes) r.readyServings.put(recipe.id, 10.0);
        restored = StandardPlan.fromJson(saved, recipes, r);
        assertTrue(restored.selected.isEmpty());
        for (List<DayPlanner.Choice> options : restored.alternatives.values())
            assertTrue(options.isEmpty());
        assertFalse(restored.result.targetClose);
    }
}
