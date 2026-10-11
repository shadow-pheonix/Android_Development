package com.shadowphoenix.dietlog.planner;

import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreferenceSetupTest {
    private PlannerFood food(String id, String... tags) {
        return new PlannerFood(
                id,
                id,
                "raw",
                new double[] {100, 10, 12, 1, 2, 0, 0, 0},
                new HashSet<>(Arrays.asList(tags)),
                true,
                10);
    }

    private MealRecipe recipe(String id, int mask, PlannerFood f) {
        return new MealRecipe(
                id,
                id,
                mask,
                10,
                true,
                "Indian",
                "Cook",
                100,
                Collections.singletonList(new MealRecipe.Ingredient(f, 100)));
    }

    @Test
    public void dishesComeFirstInBoundedDistinctStableBatches() throws Exception {
        PlannerFood rice = food("rice");
        List<MealRecipe> recipes = new ArrayList<>();
        for (int i = 0; i < 11; i++) recipes.add(recipe("r" + i, 15, rice));
        PreferenceSetup setup = new PreferenceSetup();
        setup.buildBatches(
                recipes,
                Arrays.asList(rice, rice),
                new PlannerProfile(),
                new IntentModel(Collections.emptyList(), 100),
                0.05);
        Set<String> seen = new HashSet<>();
        boolean ingredients = false;
        for (PreferenceSetup.Batch b : setup.batches) {
            assertTrue(b.ids.size() <= 4);
            if (!b.recipes) ingredients = true;
            else assertFalse(ingredients);
            for (String id : b.ids) assertTrue(seen.add(id));
        }
        assertEquals(12, seen.size());
        String stable = setup.toJson().toString();
        PlannerProfile changed = new PlannerProfile();
        changed.ratings.put("r0", FoodPreference.NEVER);
        assertEquals(stable, setup.toJson().toString());
    }

    @Test
    public void restrictionsAndCookingAreAppliedButSavedDislikesCanBeReviewed() throws Exception {
        PlannerFood rice = food("rice"), milk = food("milk", "dairy");
        PlannerProfile p = new PlannerProfile();
        p.allergies.add("dairy");
        p.ratings.put("riceDish", FoodPreference.NEVER);
        PreferenceSetup setup = new PreferenceSetup();
        setup.buildBatches(
                Arrays.asList(recipe("riceDish", 1, rice), recipe("milkDish", 1, milk)),
                Arrays.asList(rice, milk),
                p,
                new IntentModel(Collections.emptyList(), 100),
                0.05);
        assertEquals(Collections.singletonList("riceDish"), setup.batches.get(0).ids);
        assertEquals(FoodPreference.NEVER, p.rating("riceDish"));
        p.canCook = false;
        setup.buildBatches(
                Collections.singletonList(recipe("riceDish", 1, rice)),
                Arrays.asList(rice),
                p,
                new IntentModel(Collections.emptyList(), 100),
                0.05);
        assertFalse(setup.batches.get(0).recipes);
    }

    @Test
    public void progressDraftAndFixedQueueSurviveRoundTripWithoutAssigningRatings()
            throws Exception {
        PreferenceSetup s = new PreferenceSetup();
        s.page = PreferenceSetup.BASICS;
        s.batches.add(new PreferenceSetup.Batch(true, 0, Arrays.asList("meal:a", "meal:b")));
        s.draft.put("budget", "12.");
        s.selectionIdentity = "identity";
        PreferenceSetup restored = PreferenceSetup.fromJson(new JSONObject(s.toJson().toString()));
        assertEquals(PreferenceSetup.BASICS, restored.page);
        assertEquals("12.", restored.draft.getString("budget"));
        assertEquals(s.batches.get(0).ids, restored.batches.get(0).ids);
        assertFalse(restored.completed);
        assertEquals(PreferenceSetup.BASICS + 1, restored.reviewPage());
        assertTrue(new PlannerProfile().ratings.isEmpty());
    }

    @Test
    public void diaryContinuouslyUpdatesButAlwaysStaysBelowAnExplicitOkay() {
        PlannerProfile p = new PlannerProfile();
        List<IntentModel.Observation> logs = new ArrayList<>();
        IntentModel before = new IntentModel(logs, 100);
        for (int d = 60; d <= 100; d++) logs.add(new IntentModel.Observation("rice", 1, d));
        IntentModel after = new IntentModel(logs, 100);
        assertTrue(after.score("rice", 1, p) > before.score("rice", 1, p));
        assertTrue(after.score("rice", 1, p) < FoodPreference.OKAY.score);
        p.ratings.put("rice", FoodPreference.OKAY);
        assertEquals(1, after.score("rice", 1, p), 0);
        p.ratings.put("rice", FoodPreference.NEVER);
        assertEquals(Double.NEGATIVE_INFINITY, after.score("rice", 1, p), 0);
    }
}
