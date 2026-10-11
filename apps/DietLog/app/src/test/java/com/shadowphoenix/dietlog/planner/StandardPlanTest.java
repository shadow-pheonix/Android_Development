package com.shadowphoenix.dietlog.planner;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.util.*;

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
        assertEquals(4, restored.selected.size());
        for (DayPlanner.Choice c : restored.selected.values())
            assertNotEquals(removed, c.recipe.id);
        for (DayPlanner.Choice c : plan.selected.values())
            if (!c.recipe.id.equals(removed)) {
                assertSame(c.recipe, restored.selected.get(c.meal).recipe);
                assertEquals(c.scale, restored.selected.get(c.meal).scale, 0);
            }
        assertTrue(restored.unavailable.isEmpty());
        assertFalse(restored.result.notes.stream().anyMatch(n -> n.contains("incomplete")));
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

    private MealRecipe familyMeal(String id, String family, int mask) {
        PlannerFood food =
                new PlannerFood(
                        id,
                        id,
                        "cooked",
                        new double[] {500, 30, 65, 12, 5, 50, 3, 1},
                        Collections.singleton("vegetable"),
                        true,
                        10);
        return new MealRecipe(
                "meal:" + id,
                id,
                mask,
                5,
                false,
                "Andhra",
                "Serve",
                100,
                Collections.singletonList(new MealRecipe.Ingredient(food, 100)),
                family,
                Integer.numberOfTrailingZeros(mask));
    }

    @Test
    public void unavailableLunchKeepsOtherMealsAndExplainsItsActualConstraints() {
        List<MealRecipe> recipes =
                Arrays.asList(
                        familyMeal("breakfast", "idli", 1),
                        familyMeal("lunch", "pappu", 2),
                        familyMeal("snack", "fruit", 4),
                        familyMeal("dinner", "chapati", 8));
        DayPlanner.Request r = request();
        r.profile.ratings.put("lunch", FoodPreference.NEVER);
        StandardPlan plan = StandardPlan.generate(recipes, r);
        assertEquals(new HashSet<>(Arrays.asList(0, 2, 3)), plan.selected.keySet());
        assertTrue(plan.alternatives.get(1).isEmpty());
        assertEquals(Collections.singleton(1), plan.unavailable.keySet());
        assertTrue(plan.unavailable.get(1).contains("No suitable Lunch"));
        assertTrue(plan.result.notes.stream().anyMatch(n -> n.contains("No suitable Lunch")));
        assertTrue(plan.result.notes.stream().anyMatch(n -> n.contains("incomplete")));
        assertFalse(plan.result.targetClose);
        assertFalse(plan.result.budgetMet);
        double calories = 0;
        for (DayPlanner.Choice choice : plan.selected.values()) {
            calories += choice.recipe.nutrition(choice.scale)[0];
            assertFalse(plan.alternatives.get(choice.meal).isEmpty());
        }
        assertEquals(calories, plan.result.totals[0], 0);
    }

    @Test
    public void restoringOldPartialPlanRepairsBothMissingMealsAndKeepsEditedPortions()
            throws Exception {
        List<MealRecipe> recipes =
                Arrays.asList(
                        familyMeal("breakfast", "idli", 1),
                        familyMeal("lunch", "pappu", 2),
                        familyMeal("snack", "fruit", 4),
                        familyMeal("dinner", "chapati", 8));
        StandardPlan old = new StandardPlan();
        old.selected.put(0, new DayPlanner.Choice(0, recipes.get(0), 0.5));
        old.selected.put(2, new DayPlanner.Choice(2, recipes.get(2), 1.5));
        StandardPlan restored = StandardPlan.fromJson(old.toJson(), recipes, request());
        assertEquals(4, restored.selected.size());
        assertEquals(0.5, restored.selected.get(0).scale, 0);
        assertEquals(1.5, restored.selected.get(2).scale, 0);
        for (int meal = 0; meal < 4; meal++) {
            assertSame(recipes.get(meal), restored.selected.get(meal).recipe);
            assertFalse(restored.alternatives.get(meal).isEmpty());
        }
        assertTrue(restored.unavailable.isEmpty());
        assertFalse(restored.result.notes.stream().anyMatch(n -> n.contains("incomplete")));
    }

    @Test
    public void alternativesUseDishFamiliesAndPairedMealsChooseDifferentFamilies() {
        List<MealRecipe> recipes =
                Arrays.asList(
                        familyMeal("oats_a", "oats", 15),
                        familyMeal("oats_b", "oats", 15),
                        familyMeal("oats_c", "oats", 15),
                        familyMeal("idli", "idli", 15),
                        familyMeal("pesarattu", "pesarattu", 15),
                        familyMeal("pappu", "pappu", 15),
                        familyMeal("chapati", "chapati", 15));
        StandardPlan plan = StandardPlan.generate(recipes, request());
        assertNotEquals(plan.selected.get(0).recipe.family, plan.selected.get(2).recipe.family);
        assertNotEquals(plan.selected.get(1).recipe.family, plan.selected.get(3).recipe.family);
        for (int meal = 0; meal < 4; meal++) {
            Set<String> families = new HashSet<>();
            assertEquals(4, plan.alternatives.get(meal).size());
            for (DayPlanner.Choice option : plan.alternatives.get(meal)) {
                assertTrue(families.add(option.recipe.family));
                assertNotEquals(
                        plan.selected.get((meal + 2) % 4).recipe.family, option.recipe.family);
            }
        }
    }

    @Test
    public void scarceDishFamiliesDoNotCreateFakeOptionsOrPreventAvailableMeals() {
        List<MealRecipe> recipes =
                Arrays.asList(
                        familyMeal("oats_a", "oats", 15),
                        familyMeal("oats_b", "oats", 15),
                        familyMeal("oats_c", "oats", 15));
        StandardPlan plan = StandardPlan.generate(recipes, request());
        assertEquals(4, plan.selected.size());
        assertTrue(plan.unavailable.isEmpty());
        for (List<DayPlanner.Choice> options : plan.alternatives.values()) {
            assertEquals(1, options.size());
            assertEquals("oats", options.get(0).recipe.family);
        }
        List<MealRecipe> excluded = new ArrayList<>(recipes);
        excluded.add(familyMeal("idli", "idli", 15));
        DayPlanner.Request r = request();
        r.profile.ratings.put("idli", FoodPreference.NEVER);
        StandardPlan constrained = StandardPlan.generate(excluded, r);
        for (List<DayPlanner.Choice> options : constrained.alternatives.values()) {
            assertEquals(1, options.size());
            assertEquals("oats", options.get(0).recipe.family);
        }
    }

    @Test
    public void evaluateRepairsMissingEarlierMealWithoutRepeatingKeptPairedFamily() {
        List<MealRecipe> recipes =
                Arrays.asList(familyMeal("oats", "oats", 5), familyMeal("idli", "idli", 5));
        DayPlanner.Request r = request();
        r.profile.meals[1] = r.profile.meals[3] = false;
        StandardPlan plan = new StandardPlan();
        DayPlanner.Choice kept = new DayPlanner.Choice(2, recipes.get(0), 0.5);
        plan.selected.put(2, kept);
        plan.evaluate(recipes, r);
        assertSame(kept, plan.selected.get(2));
        assertEquals("idli", plan.selected.get(0).recipe.family);
        assertTrue(plan.unavailable.isEmpty());
    }

    private MealRecipe otherCuisine(String id, double price) {
        MealRecipe base = familyMeal(id, id, 15);
        PlannerFood original = base.ingredients.get(0).food;
        PlannerFood food =
                new PlannerFood(
                        original.id,
                        original.name,
                        original.state,
                        original.nutrients,
                        original.tags,
                        original.ingredientsKnown,
                        price);
        return new MealRecipe(
                base.id,
                base.name,
                base.mealMask,
                base.prepMinutes,
                false,
                "Indian",
                base.instructions,
                base.yieldGrams,
                Collections.singletonList(new MealRecipe.Ingredient(food, 100)),
                id,
                0);
    }

    @Test
    public void requestedCuisineLeadsWhenItHasCoverageAndExplicitLovedRecipesRemainAvailable() {
        List<MealRecipe> recipes = new ArrayList<>();
        for (String family : Arrays.asList("idli", "pesarattu", "pappu", "chapati", "upma"))
            recipes.add(familyMeal(family, family, 15));
        MealRecipe outsider = otherCuisine("salad", 10);
        recipes.add(outsider);
        DayPlanner.Request r = request();
        r.profile.cuisine = "Andhra";
        StandardPlan regional = StandardPlan.generate(recipes, r);
        for (DayPlanner.Choice choice : regional.selected.values())
            assertEquals("Andhra", choice.recipe.cuisine);
        for (List<DayPlanner.Choice> options : regional.alternatives.values())
            for (DayPlanner.Choice choice : options) assertEquals("Andhra", choice.recipe.cuisine);
        r.profile.ratings.put(outsider.id, FoodPreference.LOVE);
        StandardPlan loved = StandardPlan.generate(recipes, r);
        assertTrue(loved.selected.values().stream().anyMatch(c -> c.recipe == outsider));
        r.profile.ratings.put(outsider.ingredients.get(0).food.id, FoodPreference.NEVER);
        StandardPlan forbidden = StandardPlan.generate(recipes, r);
        for (List<DayPlanner.Choice> options : forbidden.alternatives.values())
            for (DayPlanner.Choice choice : options) assertNotSame(outsider, choice.recipe);
    }

    @Test
    public void cuisinePreferenceFallsBackForCoverageAndAFeasibleBudget() {
        List<MealRecipe> sparse =
                Arrays.asList(
                        familyMeal("idli", "idli", 15),
                        otherCuisine("salad", 10),
                        otherCuisine("fruit", 10),
                        otherCuisine("sandwich", 10));
        DayPlanner.Request r = request();
        r.profile.cuisine = "Andhra";
        StandardPlan covered = StandardPlan.generate(sparse, r);
        assertEquals(4, covered.selected.size());
        for (List<DayPlanner.Choice> options : covered.alternatives.values())
            assertEquals(4, options.size());

        List<MealRecipe> costly = new ArrayList<>();
        for (String family : Arrays.asList("idli", "pesarattu", "pappu", "chapati"))
            costly.add(familyMeal(family, family, 15));
        MealRecipe cheap = otherCuisine("cheap", 0.1);
        costly.add(cheap);
        Arrays.fill(r.profile.meals, false);
        r.profile.meals[0] = true;
        r.profile.dailyBudget = 1;
        double[] targets = r.targets.clone();
        StandardPlan affordable = StandardPlan.generate(costly, r);
        assertSame(cheap, affordable.selected.get(0).recipe);
        assertTrue(affordable.result.budgetMet);
        assertTrue(affordable.result.cost <= 1);
        assertArrayEquals(targets, r.targets, 0);
        assertTrue(affordable.result.notes.stream().anyMatch(n -> n.contains("Other cuisines")));
    }

    @Test
    public void oldAndPersonalRecipesDeriveFamilyFromIngredientsAndPrimaryMealFromMask() {
        MealRecipe base = familyMeal("ingredient", "unused", 10);
        MealRecipe a =
                new MealRecipe(
                        "personal:a", "A", 10, 0, false, "Indian", "Serve", 100, base.ingredients);
        MealRecipe b =
                new MealRecipe(
                        "personal:b",
                        "B",
                        10,
                        0,
                        false,
                        "Indian",
                        "Serve",
                        150,
                        Collections.singletonList(
                                new MealRecipe.Ingredient(base.ingredients.get(0).food, 150)));
        assertEquals(1, a.primaryMeal);
        assertEquals(a.family, b.family);
        DayPlanner.Request r = request();
        r.profile.meals[0] = r.profile.meals[2] = false;
        StandardPlan plan = StandardPlan.generate(Arrays.asList(a, b), r);
        assertEquals(2, plan.selected.size());
        assertEquals(1, plan.alternatives.get(1).size());
        assertEquals(1, plan.alternatives.get(3).size());
    }

    @Test
    public void tightBudgetMayRepeatFamiliesButNeverBypassesExplicitExclusions() throws Exception {
        List<MealRecipe> recipes = new ArrayList<>();
        for (String family : Arrays.asList("idli", "pesarattu", "pappu", "chapati"))
            recipes.add(familyMeal(family, family, 15));
        MealRecipe cheap = otherCuisine("cheap", 0.1);
        recipes.add(cheap);
        DayPlanner.Request r = request();
        r.profile.cuisine = "Andhra";
        r.profile.dailyBudget = 1;
        StandardPlan affordable = StandardPlan.generate(recipes, r);
        assertEquals(4, affordable.selected.size());
        assertTrue(affordable.result.budgetMet);
        for (DayPlanner.Choice choice : affordable.selected.values())
            assertSame(cheap, choice.recipe);
        assertTrue(affordable.result.notes.get(0).contains("meal families repeat"));
        Map<Integer, DayPlanner.Choice> before = new HashMap<>(affordable.selected);
        affordable.selected.remove(1);
        StandardPlan restored = StandardPlan.fromJson(affordable.toJson(), recipes, r);
        assertTrue(restored.result.budgetMet);
        assertSame(cheap, restored.selected.get(1).recipe);
        for (int meal : new int[] {0, 2, 3})
            assertEquals(before.get(meal).scale, restored.selected.get(meal).scale, 0);
        r.profile.ratings.put(cheap.id, FoodPreference.NEVER);
        StandardPlan forbidden = StandardPlan.generate(recipes, r);
        assertEquals(4, forbidden.selected.size());
        assertFalse(forbidden.result.budgetMet);
        for (DayPlanner.Choice choice : forbidden.selected.values())
            assertNotSame(cheap, choice.recipe);
        for (List<DayPlanner.Choice> options : forbidden.alternatives.values())
            for (DayPlanner.Choice choice : options) assertNotSame(cheap, choice.recipe);
    }

    @Test
    public void broadIndianCuisineRetainsAndhraDishes() {
        MealRecipe andhra = familyMeal("pesarattu", "pesarattu", 15);
        List<MealRecipe> recipes =
                Arrays.asList(
                        andhra,
                        otherCuisine("sandwich", 10),
                        otherCuisine("salad", 10),
                        otherCuisine("fruit", 10),
                        otherCuisine("rice", 10));
        DayPlanner.Request r = request();
        r.profile.cuisine = "Indian";
        StandardPlan plan = StandardPlan.generate(recipes, r);
        assertTrue(plan.selected.values().stream().anyMatch(c -> c.recipe == andhra));
        assertTrue(
                plan.alternatives.values().stream()
                        .flatMap(Collection::stream)
                        .anyMatch(c -> c.recipe == andhra));
    }
}
