package com.shadowphoenix.dietlog.planner;

import android.content.*;
import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

/**
 * Real JSON with in-memory preferences; exercises the persisted schema and interruption recovery.
 */
public class PlannerStoreTest {
    private static final class Memory implements SharedPreferences {
        final Map<String, Object> data = new LinkedHashMap<>();
        boolean failCommit;

        public Map<String, ?> getAll() {
            return new HashMap<>(data);
        }

        public String getString(String k, String d) {
            return (String) data.getOrDefault(k, d);
        }

        public Set<String> getStringSet(String k, Set<String> d) {
            return d;
        }

        public int getInt(String k, int d) {
            return d;
        }

        public long getLong(String k, long d) {
            return d;
        }

        public float getFloat(String k, float d) {
            return d;
        }

        public boolean getBoolean(String k, boolean d) {
            return d;
        }

        public boolean contains(String k) {
            return data.containsKey(k);
        }

        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {}

        public Editor edit() {
            return new Editor() {
                final Map<String, Object> changes = new LinkedHashMap<>();
                final Set<String> removed = new HashSet<>();
                boolean clear;

                public Editor putString(String k, String v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putStringSet(String k, Set<String> v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putInt(String k, int v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putLong(String k, long v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putFloat(String k, float v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putBoolean(String k, boolean v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor remove(String k) {
                    removed.add(k);
                    return this;
                }

                public Editor clear() {
                    clear = true;
                    return this;
                }

                public boolean commit() {
                    if (failCommit) return false;
                    if (clear) data.clear();
                    for (String k : removed) data.remove(k);
                    data.putAll(changes);
                    return true;
                }

                public void apply() {
                    commit();
                }
            };
        }
    }

    private PlannerStore store(Memory memory) {
        return new PlannerStore(
                new ContextWrapper(null) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return memory;
                    }
                });
    }

    @Test
    public void ratingsAndResumePositionPersistAtomically() throws Exception {
        Memory m = new Memory();
        PlannerStore store = store(m);
        PlannerProfile p = new PlannerProfile();
        PreferenceSetup state = new PreferenceSetup();
        state.batches.add(new PreferenceSetup.Batch(true, 0, Arrays.asList("meal:a")));
        state.page = 4;
        p.ratings.put("meal:a", FoodPreference.LOVE);
        store.saveSetup(p, state);
        assertEquals(4, store(m).setup().page);
        assertEquals(FoodPreference.LOVE, store(m).profile().rating("meal:a"));
        m.failCommit = true;
        state.page = 5;
        p.ratings.put("meal:a", FoodPreference.NEVER);
        try {
            store.saveSetup(p, state);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(4, store(m).setup().page);
        assertEquals(FoodPreference.LOVE, store(m).profile().rating("meal:a"));
    }

    @Test
    public void explicitChoicesUseStringNamesAndSurviveRecreation() throws Exception {
        Memory memory = new Memory();
        PlannerStore store = store(memory);
        PlannerProfile p = new PlannerProfile();
        p.diet = PlannerProfile.Diet.VEGAN;
        p.ratings.put("rice", FoodPreference.NEVER);
        p.ratings.put("banana", FoodPreference.LOVE);
        p.allergies.add("gluten");
        p.aliases.put("cooked-rice", "rice");
        p.dailyBudget = 150;
        p.prices.put("rice", 5.0);
        p.meals[2] = false;
        store.saveProfile(p);
        JSONObject raw = new JSONObject((String) memory.data.get("profile"));
        assertEquals("NEVER", raw.getJSONObject("ratings").getString("rice"));
        PlannerProfile loaded = store(memory).profile();
        assertEquals(FoodPreference.NEVER, loaded.rating("cooked-rice"));
        assertEquals(FoodPreference.LOVE, loaded.rating("banana"));
        assertEquals(PlannerProfile.Diet.VEGAN, loaded.diet);
        assertFalse(loaded.meals[2]);
        assertEquals(150, loaded.dailyBudget, 0);
        assertTrue(loaded.allergies.contains("gluten"));
    }

    @Test
    public void interruptedConsumptionRecoversOnceAndPreservesPackMetadata() throws Exception {
        Memory memory = new Memory();
        PlannerStore store = store(memory);
        store.savePantry(
                new JSONObject()
                        .put(
                                "rice",
                                new JSONObject()
                                        .put("grams", 250)
                                        .put("revision", "stock-1")
                                        .put("pack_grams", 500)
                                        .put("pack_price", 60)));
        store.saveLeftovers(
                new JSONObject()
                        .put(
                                "recipe",
                                new JSONObject()
                                        .put("servings", 2)
                                        .put("through", "2026-10-12")
                                        .put("revision", "batch-1")));
        store.stageUsage("plan:day:meal", Collections.singletonMap("rice", 100.0), "recipe", 0.5);
        assertEquals(250, store.pantry().getJSONObject("rice").getDouble("grams"), 0);
        assertEquals(1, store.pendingTokens().size());
        store = store(memory);
        store.finishUsage("plan:day:meal", true);
        assertEquals(150, store.pantry().getJSONObject("rice").getDouble("grams"), 0);
        assertEquals(1.5, store.leftovers().getJSONObject("recipe").getDouble("servings"), 0);
        assertEquals(60, store.pantry().getJSONObject("rice").getDouble("pack_price"), 0);
        assertTrue(store.pendingTokens().isEmpty());
        store.stageUsage("plan:day:meal", Collections.singletonMap("rice", 100.0), "recipe", 0.5);
        store.finishUsage("plan:day:meal", true);
        assertEquals(150, store.pantry().getJSONObject("rice").getDouble("grams"), 0);
    }

    @Test
    public void failedDiaryInsertDoesNotConsumeStockAndManualRestockWins() throws Exception {
        PlannerStore store = store(new Memory());
        store.savePantry(
                new JSONObject()
                        .put("rice", new JSONObject().put("grams", 250).put("revision", "a")));
        store.stageUsage("failed", Collections.singletonMap("rice", 100.0), "recipe", 0);
        store.finishUsage("failed", false);
        assertEquals(250, store.pantry().getJSONObject("rice").getDouble("grams"), 0);
        store.stageUsage("recorded", Collections.singletonMap("rice", 100.0), "recipe", 0);
        store.savePantry(
                new JSONObject()
                        .put("rice", new JSONObject().put("grams", 500).put("revision", "b")));
        store.finishUsage("recorded", true);
        assertEquals(500, store.pantry().getJSONObject("rice").getDouble("grams"), 0);
    }

    @Test
    public void resettingFeedbackPreservesExplicitPreferences() throws Exception {
        PlannerStore store = store(new Memory());
        PlannerProfile p = new PlannerProfile();
        p.ratings.put("recipe", FoodPreference.NEVER);
        store.saveProfile(p);
        store.recordFeedback("recipe", IntentModel.Feedback.DISLIKED);
        assertEquals(
                Integer.valueOf(1),
                store.feedback().get("recipe").get(IntentModel.Feedback.DISLIKED));
        store.resetLearning();
        assertTrue(store.feedback().isEmpty());
        assertEquals(FoodPreference.NEVER, store.profile().rating("recipe"));
    }
}
