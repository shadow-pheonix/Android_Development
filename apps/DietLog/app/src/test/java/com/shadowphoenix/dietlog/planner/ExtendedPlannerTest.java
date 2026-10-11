package com.shadowphoenix.dietlog.planner;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class ExtendedPlannerTest {
    private PlannerFood food() {
        return new PlannerFood(
                "rice",
                "Rice",
                "raw",
                new double[] {350, 10, 75, 2, 3, 0, 0, 0},
                new HashSet<>(Arrays.asList("grain")),
                true,
                10);
    }

    private MealRecipe meal(String id) {
        return new MealRecipe(
                id,
                id,
                15,
                20,
                true,
                "Indian",
                "Cook",
                300,
                Arrays.asList(new MealRecipe.Ingredient(food(), 100)));
    }

    private DayPlanner.Request request() {
        DayPlanner.Request r = new DayPlanner.Request();
        r.targets = new double[] {1400, 40, 300, 8, 12, 2000, 50, 0};
        return r;
    }

    @Test
    public void shoppingConsolidatesBeforeRoundingAndDeductsOnlyTheWeighedStock() {
        List<DayPlanner.Choice> choices =
                Arrays.asList(
                        new DayPlanner.Choice(0, meal("a"), 1),
                        new DayPlanner.Choice(1, meal("b"), 1));
        ShoppingPlanner.Result list =
                new ShoppingPlanner()
                        .list(
                                choices,
                                new PlannerProfile(),
                                Collections.singletonMap("rice", 50.0),
                                Collections.singletonMap(
                                        "rice", new ShoppingPlanner.Pack(100, 20)));
        assertEquals(1, list.rows.size());
        assertEquals(200, list.rows.get(0).needed, 0);
        assertEquals(2, list.rows.get(0).packs);
        assertEquals(40, list.cash, 0);
        assertTrue(list.pricesEntered);
        list =
                new ShoppingPlanner()
                        .list(
                                choices,
                                new PlannerProfile(),
                                Collections.singletonMap("rice", 250.0),
                                Collections.emptyMap());
        assertEquals(0, list.cash, 0);
        assertTrue(list.allPacksKnown);
    }

    @Test
    public void preparedPortionsContributeNutritionButDoNotEnterShoppingTwice() {
        DayPlanner.Choice c = new DayPlanner.Choice(0, meal("a"), 1.5, 1);
        ShoppingPlanner.Result list =
                new ShoppingPlanner()
                        .list(
                                Arrays.asList(c),
                                new PlannerProfile(),
                                Collections.emptyMap(),
                                Collections.emptyMap());
        assertEquals(50, list.rows.get(0).needed, 0);
        assertEquals(5, list.cash, 0);
        assertFalse(list.pricesEntered);
        assertFalse(list.allPacksKnown);
    }

    @Test
    public void preparedFoodCannotBypassRestrictionsAndCannotBeAllocatedTwice() {
        DayPlanner.Request r = request();
        r.profile.canCook = false;
        r.profile.maxPrepMinutes = 0;
        r.readyServings.put("a", 2.0);
        r.remaining = new boolean[] {true, true, false, false};
        DayPlanner.Result plan = new DayPlanner().plan(Arrays.asList(meal("a")), r);
        assertEquals(2, plan.choices.size());
        double used = 0;
        for (DayPlanner.Choice c : plan.choices) {
            assertEquals(c.scale, c.preparedScale, 0);
            used += c.preparedScale;
        }
        assertTrue(used <= 2);
        r.readyServings.put("a", 0.5);
        DayPlanner.Result partial = new DayPlanner().plan(Arrays.asList(meal("a")), r);
        assertEquals(1, partial.choices.size());
        assertEquals(0, partial.choices.get(0).meal);
        assertEquals(0.5, partial.choices.get(0).scale, 0);
        assertEquals(0.5, partial.choices.get(0).preparedScale, 0);
        assertEquals(175, partial.totals[0], 0);
        assertTrue(partial.notes.stream().anyMatch(n -> n.contains("Lunch")));
        assertFalse(partial.targetClose);
        assertFalse(partial.budgetMet);
        r.readyServings.put("a", 2.0);
        r.profile.ratings.put("rice", FoodPreference.NEVER);
        assertTrue(new DayPlanner().plan(Arrays.asList(meal("a")), r).choices.isEmpty());
    }

    @Test
    public void linksAreExplicitAndExclusionsSurviveLinkedPositiveRatings() {
        PlannerProfile p = new PlannerProfile();
        p.aliases.put("cooked-rice", "rice");
        p.ratings.put("cooked-rice", FoodPreference.NEVER);
        p.ratings.put("rice", FoodPreference.LOVE);
        p.validate();
        assertEquals(FoodPreference.NEVER, p.rating("rice"));
        assertFalse(meal("a").allowed(p, 0));
        p.aliases.put("rice", "other");
        try {
            p.validate();
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void weekAccountsForCombinedBudgetAndFinitePreparedStock() {
        List<DayPlanner.Request> inputs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            DayPlanner.Request r = request();
            r.profile.weeklyBudget = 280;
            r.readyServings.put("a", 2.0);
            inputs.add(r);
        }
        WeekPlanner.Result week =
                new WeekPlanner().plan(Arrays.asList(meal("a"), meal("b"), meal("c")), inputs);
        assertTrue(week.complete);
        assertEquals(7, week.days.size());
        assertTrue(week.budgetMet);
        assertTrue(week.consumedCost <= 280.01);
        double ready = 0;
        Set<String> variety = new HashSet<>();
        for (DayPlanner.Choice c : week.meals()) {
            ready += c.preparedScale;
            variety.add(c.recipe.id);
        }
        assertTrue(ready <= 2.0);
        assertTrue(variety.size() > 1);
    }

    @Test
    public void impossibleWeeklyBudgetIsReportedWithoutErasingRestrictions() {
        List<DayPlanner.Request> inputs = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            DayPlanner.Request r = request();
            r.profile.weeklyBudget = 1;
            inputs.add(r);
        }
        WeekPlanner.Result week = new WeekPlanner().plan(Arrays.asList(meal("a")), inputs);
        assertTrue(week.complete);
        assertFalse(week.budgetMet);
    }

    @Test
    public void stockRecoveryIsIdempotentAndDoesNotDeductFromAReplacedBatch() {
        Map<String, InventoryLedger.Item> stock = new HashMap<>(), usage = new HashMap<>();
        Set<String> complete = new HashSet<>();
        stock.put("food:rice", new InventoryLedger.Item(250, "batch-a"));
        usage.put("food:rice", new InventoryLedger.Item(100, "batch-a"));
        assertTrue(InventoryLedger.apply("meal-1", stock, usage, complete));
        assertEquals(150, stock.get("food:rice").amount, 0);
        assertFalse(InventoryLedger.apply("meal-1", stock, usage, complete));
        assertEquals(150, stock.get("food:rice").amount, 0);
        stock.put("food:rice", new InventoryLedger.Item(500, "batch-b"));
        assertTrue(InventoryLedger.apply("meal-2", stock, usage, complete));
        assertEquals(500, stock.get("food:rice").amount, 0);
    }

    @Test
    public void everyPersonalRecipeCanCompeteAtTheMaximumCatalogueSize() {
        List<MealRecipe> meals = new ArrayList<>();
        for (int i = 0; i < 131; i++) meals.add(meal("recipe-" + i));
        DayPlanner.Request r = request();
        r.targets = new double[] {2800, 80, 600, 16, 24, 2000, 50, 0};
        r.profile.ratings.put("recipe-130", FoodPreference.LOVE);
        DayPlanner.Result result = new DayPlanner().plan(meals, r);
        assertEquals(4, result.choices.size());
        assertTrue(result.choices.stream().anyMatch(c -> c.recipe.id.equals("recipe-130")));
    }

    @Test
    public void cancellingAPlannerRequestStopsTheSearch() {
        DayPlanner.Request r = request();
        r.cancelled = () -> true;
        try {
            new DayPlanner().plan(Arrays.asList(meal("a")), r);
            fail();
        } catch (java.util.concurrent.CancellationException expected) {
        }
    }

    @Test
    public void partiallyEatenMealUsesOnlyItsRemainingRoutineShare() {
        DayPlanner.Request r = request();
        r.profile.largestMeal = -1;
        r.targets[0] = 2000;
        r.consumed[0] = 700;
        r.consumedByMeal[1] = 700;
        double[] shares = DayPlanner.shares(r);
        assertEquals(0, shares[1], 0);
        assertTrue(shares[0] > shares[2]);
        double sum = 0;
        for (double share : shares) sum += share;
        assertEquals(1, sum, 0.000001);
    }

    @Test
    public void finalPlanUsesTheExplicitLargestMealPreference() {
        PlannerFood base =
                new PlannerFood(
                        "base",
                        "Base",
                        "ready",
                        new double[] {500, 25, 62.5, 12.5, 5, 0, 0, 0},
                        new HashSet<>(Arrays.asList("grain")),
                        true,
                        10);
        MealRecipe recipe =
                new MealRecipe(
                        "balanced",
                        "Balanced",
                        15,
                        0,
                        false,
                        "Any",
                        "Ready",
                        100,
                        Arrays.asList(new MealRecipe.Ingredient(base, 100)));
        DayPlanner.Request r = new DayPlanner.Request();
        r.targets = new double[] {2000, 100, 250, 50, 20, 2000, 50, 0};
        r.profile.largestMeal = 0;
        DayPlanner.Result breakfast = new DayPlanner().plan(Arrays.asList(recipe), r);
        r.profile.largestMeal = 3;
        DayPlanner.Result dinner = new DayPlanner().plan(Arrays.asList(recipe), r);
        assertTrue(breakfast.choices.get(0).scale > breakfast.choices.get(3).scale);
        assertTrue(dinner.choices.get(3).scale > dinner.choices.get(0).scale);
        assertTrue(breakfast.targetClose);
        assertTrue(dinner.targetClose);
    }

    @Test
    public void explicitlySelectingNoRemainingMealsShowsDiaryTotalsWithoutInventingConsumption() {
        DayPlanner.Request r = request();
        Arrays.fill(r.remaining, false);
        r.consumed[0] = 1000;
        r.consumed[1] = 35;
        r.consumed[2] = 150;
        r.consumed[3] = 25;
        DayPlanner.Result result = new DayPlanner().plan(Arrays.asList(meal("a")), r);
        assertTrue(result.choices.isEmpty());
        assertArrayEquals(r.consumed, result.totals, 0);
        assertTrue(
                result.notes.stream()
                        .anyMatch(note -> note.contains("No remaining meals selected")));
    }
}
