package com.shadowphoenix.dietlog.planner;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.json.*;
import org.junit.Test;

/**
 * Exercises the shipped recipes with pinned real nutrition, rather than invented one-food dishes.
 */
public class ProductionCatalogTest {
    private Path testPath(String relative) {
        Path path = Path.of(relative);
        return Files.exists(path) ? path : Path.of("app").resolve(path);
    }

    private List<MealRecipe> recipes() throws Exception {
        JSONObject root =
                new JSONObject(
                        new String(
                                Files.readAllBytes(testPath("src/main/assets/planner_meals.json")),
                                java.nio.charset.StandardCharsets.UTF_8));
        JSONObject nutrients =
                new JSONObject(
                        new String(
                                Files.readAllBytes(
                                        testPath(
                                                "src/test/resources/planner_fixture_nutrition.json")),
                                java.nio.charset.StandardCharsets.UTF_8));
        JSONObject definitions = root.getJSONObject("foods");
        Map<String, PlannerFood> foods = new LinkedHashMap<>();
        for (Iterator<String> keys = definitions.keys(); keys.hasNext(); ) {
            String slug = keys.next();
            JSONObject food = definitions.getJSONObject(slug);
            JSONArray values = nutrients.getJSONArray(food.getString("code"));
            double[] n = new double[8];
            for (int i = 0; i < 8; i++) n[i] = values.isNull(i) ? Double.NaN : values.getDouble(i);
            Set<String> tags = new HashSet<>();
            JSONArray a = food.getJSONArray("tags");
            for (int i = 0; i < a.length(); i++) tags.add(a.getString(i));
            List<String> preferenceIds = new ArrayList<>();
            JSONArray identity = food.optJSONArray("preference_ids");
            if (identity != null)
                for (int i = 0; i < identity.length(); i++)
                    preferenceIds.add(identity.getString(i));
            foods.put(
                    slug,
                    new PlannerFood(
                            food.getString("code"),
                            food.getString("name"),
                            food.getString("state"),
                            n,
                            tags,
                            food.getBoolean("ingredients_known"),
                            food.getDouble("reference_price_per_100g"),
                            preferenceIds));
        }
        List<MealRecipe> recipes = new ArrayList<>();
        JSONArray rows = root.getJSONArray("recipes");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            List<MealRecipe.Ingredient> parts = new ArrayList<>();
            JSONArray ingredients = row.getJSONArray("ingredients");
            for (int j = 0; j < ingredients.length(); j++) {
                JSONObject part = ingredients.getJSONObject(j);
                parts.add(
                        new MealRecipe.Ingredient(
                                foods.get(part.getString("food")), part.getDouble("grams")));
            }
            recipes.add(
                    new MealRecipe(
                            row.getString("id"),
                            row.getString("name"),
                            row.getInt("meal_mask"),
                            row.getInt("prep_minutes"),
                            row.getBoolean("requires_cooking"),
                            row.getString("cuisine"),
                            row.getString("instructions"),
                            row.getDouble("yield_grams"),
                            parts,
                            row.getString("family"),
                            row.getInt("primary_meal")));
        }
        return recipes;
    }

    private DayPlanner.Request request(PlannerProfile.Diet diet, boolean canCook, int minutes) {
        DayPlanner.Request r = new DayPlanner.Request();
        r.profile.diet = diet;
        r.profile.cuisine = "Andhra";
        r.profile.canCook = canCook;
        r.profile.maxPrepMinutes = minutes;
        return r;
    }

    private void completeAndDistinct(StandardPlan plan, DayPlanner.Request r) {
        assertEquals("Every enabled meal must have a recommendation", 4, plan.selected.size());
        Set<String> selected = new HashSet<>();
        for (int meal = 0; meal < 4; meal++) {
            DayPlanner.Choice choice = plan.selected.get(meal);
            assertNotNull(DayPlanner.mealName(meal), choice);
            assertTrue(choice.recipe.allowed(r.profile, meal));
            assertTrue(
                    "Selected meals must differ: " + choice.recipe.name,
                    selected.add(choice.recipe.id));
            List<DayPlanner.Choice> options = plan.alternatives.get(meal);
            assertEquals("Four alternatives for " + DayPlanner.mealName(meal), 4, options.size());
            Set<String> families = new HashSet<>();
            for (DayPlanner.Choice option : options) {
                assertTrue(option.recipe.allowed(r.profile, meal));
                assertTrue(
                        "Repeated dish family in " + DayPlanner.mealName(meal),
                        families.add(option.recipe.family));
            }
        }
    }

    @Test
    public void actualAndhraCatalogueSupportsEveryDietAndMeal() throws Exception {
        List<MealRecipe> recipes = recipes();
        for (PlannerProfile.Diet diet : PlannerProfile.Diet.values()) {
            DayPlanner.Request r = request(diet, true, 45);
            StandardPlan plan = StandardPlan.generate(recipes, r);
            completeAndDistinct(plan, r);
            for (DayPlanner.Choice choice : plan.selected.values())
                assertEquals(
                        "Selected cuisine should lead when suitable regional meals exist",
                        "Andhra",
                        choice.recipe.cuisine);
            for (List<DayPlanner.Choice> options : plan.alternatives.values())
                for (DayPlanner.Choice option : options)
                    assertEquals("Andhra", option.recipe.cuisine);
        }
    }

    @Test
    public void purchasedPreparedMealsCoverLunchAndDinnerWithoutCooking() throws Exception {
        List<MealRecipe> recipes = recipes();
        for (PlannerProfile.Diet diet :
                Arrays.asList(PlannerProfile.Diet.ANY, PlannerProfile.Diet.VEGAN)) {
            DayPlanner.Request r = request(diet, false, 10);
            completeAndDistinct(StandardPlan.generate(recipes, r), r);
        }
    }

    @Test
    public void missingSavedMealsAreRepairedWithoutChangingBreakfastOrSnack() throws Exception {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request(PlannerProfile.Diet.ANY, true, 45);
        StandardPlan saved = StandardPlan.generate(recipes, r);
        DayPlanner.Choice breakfast = saved.selected.get(0), snack = saved.selected.get(2);
        saved.selected.remove(1);
        saved.selected.remove(3);
        saved.alternatives.remove(1);
        saved.alternatives.remove(3);
        StandardPlan restored = StandardPlan.fromJson(saved.toJson(), recipes, r);
        completeAndDistinct(restored, r);
        assertEquals(breakfast.recipe.id, restored.selected.get(0).recipe.id);
        assertEquals(breakfast.scale, restored.selected.get(0).scale, 0);
        assertEquals(snack.recipe.id, restored.selected.get(2).recipe.id);
        assertEquals(snack.scale, restored.selected.get(2).scale, 0);
    }

    @Test
    public void newRatingPagesAreRegionalDiverseAndIncludeDinner() throws Exception {
        List<MealRecipe> recipes = recipes();
        PlannerProfile p = request(PlannerProfile.Diet.ANY, true, 45).profile;
        PreferenceSetup setup = new PreferenceSetup();
        setup.buildBatches(
                recipes,
                Collections.emptyList(),
                p,
                new IntentModel(Collections.emptyList(), 0),
                .05);
        Map<String, MealRecipe> byId = new HashMap<>();
        for (MealRecipe recipe : recipes) byId.put(recipe.id, recipe);
        Set<String> seen = new HashSet<>();
        Set<Integer> meals = new HashSet<>();
        for (PreferenceSetup.Batch batch : setup.batches) {
            if (!batch.recipes) continue;
            boolean first = meals.add(batch.meal);
            Set<String> families = new HashSet<>();
            for (String id : batch.ids) {
                assertTrue("Recipe repeated across rating lists", seen.add(id));
                MealRecipe recipe = byId.get(id);
                assertTrue(recipe.allowed(p, batch.meal));
                if (first) {
                    assertEquals("Andhra choices should lead discovery", "Andhra", recipe.cuisine);
                    assertTrue(
                            "First list must explore distinct dish families",
                            families.add(recipe.family));
                }
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3)), meals);
        assertEquals(recipes.size(), seen.size());
    }

    @Test
    public void neverRiceAlsoExcludesReadyCookedRiceWithoutInventingIdentity() throws Exception {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request(PlannerProfile.Diet.ANY, true, 45);
        r.profile.ratings.put("IFCT-A015", FoodPreference.NEVER);
        StandardPlan plan = StandardPlan.generate(recipes, r);
        for (DayPlanner.Choice choice : plan.selected.values())
            for (MealRecipe.Ingredient ingredient : choice.recipe.ingredients) {
                assertFalse(ingredient.food.preferenceIds.contains("IFCT-A015"));
                assertTrue(
                        "Unknown dishes cannot establish compliance with Never rice",
                        ingredient.food.ingredientsKnown);
            }
        for (List<DayPlanner.Choice> alternatives : plan.alternatives.values())
            for (DayPlanner.Choice choice : alternatives)
                for (MealRecipe.Ingredient ingredient : choice.recipe.ingredients) {
                    assertFalse(ingredient.food.preferenceIds.contains("IFCT-A015"));
                    assertTrue(ingredient.food.ingredientsKnown);
                }
    }

    @Test
    public void undecidedCuisineStillDiscoversRegionalFoodsInTheFirstLists() throws Exception {
        List<MealRecipe> recipes = recipes();
        PlannerProfile p = new PlannerProfile();
        PreferenceSetup setup = new PreferenceSetup();
        setup.buildBatches(
                recipes,
                Collections.emptyList(),
                p,
                new IntentModel(Collections.emptyList(), 0),
                .05);
        Map<String, MealRecipe> byId = new HashMap<>();
        for (MealRecipe recipe : recipes) byId.put(recipe.id, recipe);
        Set<Integer> visited = new HashSet<>();
        for (PreferenceSetup.Batch batch : setup.batches) {
            if (!batch.recipes || !visited.add(batch.meal)) continue;
            assertTrue(
                    "Explore Andhra dishes even when cuisine is undecided",
                    batch.ids.stream().anyMatch(id -> "Andhra".equals(byId.get(id).cuisine)));
        }
        assertEquals(4, visited.size());
    }

    @Test
    public void regionalPlanningKeepsExplicitAllergiesAndVeganRules() throws Exception {
        List<MealRecipe> recipes = recipes();
        DayPlanner.Request r = request(PlannerProfile.Diet.VEGAN, true, 45);
        r.profile.allergies.addAll(Arrays.asList("gluten", "soy", "peanut"));
        r.profile.excludedIngredients.add("mustard");
        StandardPlan plan = StandardPlan.generate(recipes, r);
        assertEquals(4, plan.selected.size());
        for (List<DayPlanner.Choice> options : plan.alternatives.values())
            for (DayPlanner.Choice option : options) {
                assertTrue(option.recipe.allowed(r.profile, option.meal));
                for (MealRecipe.Ingredient part : option.recipe.ingredients) {
                    assertTrue(part.food.ingredientsKnown);
                    assertTrue(Collections.disjoint(part.food.tags, r.profile.allergies));
                    assertTrue(Collections.disjoint(part.food.tags, r.profile.excludedIngredients));
                }
            }
    }
}
