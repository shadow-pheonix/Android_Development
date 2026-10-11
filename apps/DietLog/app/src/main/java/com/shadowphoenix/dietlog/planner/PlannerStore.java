package com.shadowphoenix.dietlog.planner;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.time.LocalDate;
import java.util.*;

/** Planner state is private to this device; existing diary/cloud formats remain compatible. */
public final class PlannerStore {
    private final SharedPreferences prefs;

    public PlannerStore(Context context) {
        prefs = context.getSharedPreferences("dietlog_planner", Context.MODE_PRIVATE);
    }

    public PlannerProfile profile() throws JSONException {
        PlannerProfile p = new PlannerProfile();
        JSONObject j = new JSONObject(prefs.getString("profile", "{}"));
        p.diet = PlannerProfile.Diet.valueOf(j.optString("diet", "ANY"));
        p.dailyBudget = j.optDouble("dailyBudget", 0);
        p.weeklyBudget = j.optDouble("weeklyBudget", 0);
        p.maxPrepMinutes = j.optInt("prep", 45);
        p.canCook = j.optBoolean("cook", true);
        p.learnFromHistory = j.optBoolean("learn", true);
        p.cuisine = j.optString("cuisine", "Any");
        p.largestMeal = j.optInt("largestMeal", -1);
        JSONArray meals = j.optJSONArray("meals");
        if (meals != null && meals.length() == 4)
            for (int i = 0; i < 4; i++) p.meals[i] = meals.getBoolean(i);
        readSet(j.optJSONArray("allergies"), p.allergies);
        readSet(j.optJSONArray("excluded"), p.excludedIngredients);
        JSONObject ratings = j.optJSONObject("ratings");
        if (ratings != null)
            for (Iterator<String> it = ratings.keys(); it.hasNext(); ) {
                String id = it.next();
                p.ratings.put(id, FoodPreference.valueOf(ratings.getString(id)));
            }
        JSONObject names = j.optJSONObject("names");
        if (names != null)
            for (Iterator<String> it = names.keys(); it.hasNext(); ) {
                String id = it.next();
                p.ratingNames.put(id, names.getString(id));
            }
        JSONObject prices = j.optJSONObject("prices");
        if (prices != null)
            for (Iterator<String> it = prices.keys(); it.hasNext(); ) {
                String id = it.next();
                p.prices.put(id, prices.getDouble(id));
            }
        JSONObject aliases = j.optJSONObject("aliases");
        if (aliases != null)
            for (Iterator<String> it = aliases.keys(); it.hasNext(); ) {
                String id = it.next();
                p.aliases.put(id, aliases.getString(id));
            }
        p.validate();
        return p;
    }

    public void saveProfile(PlannerProfile p) throws JSONException {
        put("profile", profileJson(p).toString());
    }

    private static JSONObject profileJson(PlannerProfile p) throws JSONException {
        p.validate();
        JSONObject ratings = new JSONObject();
        for (Map.Entry<String, FoodPreference> e : p.ratings.entrySet())
            ratings.put(e.getKey(), e.getValue().name());
        JSONObject j = new JSONObject();
        j.put("schema", 1)
                .put("diet", p.diet.name())
                .put("dailyBudget", p.dailyBudget)
                .put("weeklyBudget", p.weeklyBudget)
                .put("prep", p.maxPrepMinutes)
                .put("cook", p.canCook)
                .put("learn", p.learnFromHistory)
                .put("cuisine", p.cuisine)
                .put("largestMeal", p.largestMeal)
                .put("allergies", new JSONArray(p.allergies))
                .put("excluded", new JSONArray(p.excludedIngredients))
                .put("aliases", new JSONObject(p.aliases))
                .put("ratings", ratings)
                .put("names", new JSONObject(p.ratingNames))
                .put("prices", new JSONObject(p.prices));
        JSONArray meals = new JSONArray();
        for (boolean m : p.meals) meals.put(m);
        j.put("meals", meals);
        return j;
    }

    public PreferenceSetup setup() throws JSONException {
        return PreferenceSetup.fromJson(new JSONObject(prefs.getString("setup", "{}")));
    }

    /** A rating and its progress checkpoint either both persist or neither does. */
    public void saveSetup(PlannerProfile profile, PreferenceSetup setup) throws JSONException {
        if (!prefs.edit()
                .putString("profile", profileJson(profile).toString())
                .putString("setup", setup.toJson().toString())
                .commit())
            throw new IllegalStateException("Unable to save your preferences. Please retry.");
    }

    public void saveSetupDraft(PreferenceSetup setup) throws JSONException {
        put("setup", setup.toJson().toString());
    }

    public String learningIdentity() {
        return prefs.getString("feedback", "{}");
    }

    public String profileIdentity() {
        return prefs.getString("profile", "{}");
    }

    public JSONObject extraFoods() throws JSONException {
        return new JSONObject(prefs.getString("ingredients", "{}"));
    }

    public void saveExtraFoods(JSONObject j) {
        if (j.length() > 200)
            throw new IllegalArgumentException("Keep at most 200 personal ingredients");
        put("ingredients", j.toString());
    }

    public JSONObject week() throws JSONException {
        return new JSONObject(prefs.getString("week", "{}"));
    }

    public void saveWeek(JSONObject j) {
        put("week", j.toString());
    }

    public String inventoryIdentity() {
        return prefs.getString("pantry", "{}") + prefs.getString("leftovers", "{}");
    }

    public JSONArray personalRecipes() throws JSONException {
        return new JSONArray(prefs.getString("recipes", "[]"));
    }

    public void saveRecipes(JSONArray recipes) {
        if (recipes.length() > 100)
            throw new IllegalArgumentException("Keep at most 100 personal recipes");
        put("recipes", recipes.toString());
    }

    public JSONObject savedPlan(LocalDate day) throws JSONException {
        return new JSONObject(prefs.getString("day:" + day, "{}"));
    }

    public void savePlan(
            LocalDate day, DayPlanner.Result result, String signature, Set<Integer> locks)
            throws JSONException {
        JSONArray choices = new JSONArray();
        for (DayPlanner.Choice c : result.choices)
            choices.put(
                    new JSONObject()
                            .put("meal", c.meal)
                            .put("recipe", c.recipe.id)
                            .put("scale", c.scale)
                            .put("locked", locks.contains(c.meal)));
        JSONObject j = new JSONObject().put("signature", signature).put("choices", choices);
        SharedPreferences.Editor edit = prefs.edit().putString("day:" + day, j.toString());
        for (String key : prefs.getAll().keySet())
            if (key.startsWith("day:")) {
                LocalDate old = LocalDate.parse(key.substring(4));
                if (old.isBefore(LocalDate.now().minusDays(35))
                        || old.isAfter(LocalDate.now().plusDays(14))) edit.remove(key);
            }
        if (!edit.commit()) throw new IllegalStateException("Unable to save your plan");
    }

    public Map<String, Map<IntentModel.Feedback, Integer>> feedback() throws JSONException {
        Map<String, Map<IntentModel.Feedback, Integer>> result = new LinkedHashMap<>();
        JSONObject j = new JSONObject(prefs.getString("feedback", "{}"));
        for (Iterator<String> keys = j.keys(); keys.hasNext(); ) {
            String id = keys.next();
            Map<IntentModel.Feedback, Integer> counts = new EnumMap<>(IntentModel.Feedback.class);
            JSONObject v = j.getJSONObject(id);
            for (IntentModel.Feedback f : IntentModel.Feedback.values())
                counts.put(f, Math.min(20, v.optInt(f.name(), 0)));
            result.put(id, counts);
        }
        return result;
    }

    public void recordFeedback(String id, IntentModel.Feedback f) throws JSONException {
        JSONObject j = new JSONObject(prefs.getString("feedback", "{}"));
        if (!j.has(id) && j.length() >= 200) j.remove(j.keys().next());
        JSONObject v = j.optJSONObject(id);
        if (v == null) v = new JSONObject();
        v.put(f.name(), Math.min(20, v.optInt(f.name(), 0) + 1));
        j.put(id, v);
        put("feedback", j.toString());
    }

    public void resetLearning() {
        put("feedback", "{}");
    }

    public JSONObject leftovers() throws JSONException {
        return new JSONObject(prefs.getString("leftovers", "{}"));
    }

    public void saveLeftovers(JSONObject j) {
        put("leftovers", j.toString());
    }

    public void clearPlans() {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith("day:")) edit.remove(key);
        if (!edit.commit()) throw new IllegalStateException("Unable to clear saved plans");
    }

    public JSONObject pantry() throws JSONException {
        return new JSONObject(prefs.getString("pantry", "{}"));
    }

    public void savePantry(JSONObject j) {
        put("pantry", j.toString());
    }

    public List<String> pendingTokens() throws JSONException {
        JSONObject j = new JSONObject(prefs.getString("pending", "{}"));
        List<String> tokens = new ArrayList<>();
        for (Iterator<String> it = j.keys(); it.hasNext(); ) tokens.add(it.next());
        return tokens;
    }

    public void stageUsage(
            String token, Map<String, Double> ingredients, String recipe, double prepared)
            throws JSONException {
        JSONObject pending = new JSONObject(prefs.getString("pending", "{}")),
                stock = pantry(),
                ready = leftovers(),
                usage = new JSONObject();
        for (Map.Entry<String, Double> e : ingredients.entrySet()) {
            JSONObject row = stock.optJSONObject(e.getKey());
            if (row != null && e.getValue() > 0)
                usage.put(
                        "food:" + e.getKey(),
                        new JSONObject()
                                .put("amount", e.getValue())
                                .put("revision", row.optString("revision")));
        }
        JSONObject row = ready.optJSONObject(recipe);
        if (row != null && prepared > 0)
            usage.put(
                    "meal:" + recipe,
                    new JSONObject()
                            .put("amount", prepared)
                            .put("revision", row.optString("revision")));
        if (pending.length() >= 20 && !pending.has(token))
            throw new IllegalStateException(
                    "Recover pending meal confirmations before logging more meals");
        pending.put(token, usage);
        put("pending", pending.toString());
    }

    public void finishUsage(String token, boolean recorded) throws JSONException {
        JSONObject pending = new JSONObject(prefs.getString("pending", "{}"));
        JSONObject use = pending.optJSONObject(token);
        if (use == null) return;
        JSONObject pantry = pantry(),
                ready = leftovers(),
                done = new JSONObject(prefs.getString("consumedTokens", "{}"));
        if (recorded) {
            Map<String, InventoryLedger.Item> stock = new HashMap<>(), usage = new HashMap<>();
            for (Iterator<String> keys = use.keys(); keys.hasNext(); ) {
                String key = keys.next();
                JSONObject u = use.getJSONObject(key);
                usage.put(
                        key,
                        new InventoryLedger.Item(u.getDouble("amount"), u.getString("revision")));
                JSONObject row =
                        (key.startsWith("food:") ? pantry : ready).optJSONObject(key.substring(5));
                if (row != null)
                    stock.put(
                            key,
                            new InventoryLedger.Item(
                                    row.optDouble(
                                            key.startsWith("food:") ? "grams" : "servings", 0),
                                    row.optString("revision")));
            }
            Set<String> complete = new HashSet<>();
            for (Iterator<String> it = done.keys(); it.hasNext(); ) complete.add(it.next());
            if (InventoryLedger.apply(token, stock, usage, complete))
                for (Map.Entry<String, InventoryLedger.Item> e : stock.entrySet()) {
                    boolean food = e.getKey().startsWith("food:");
                    JSONObject row = (food ? pantry : ready).optJSONObject(e.getKey().substring(5));
                    if (row != null) row.put(food ? "grams" : "servings", e.getValue().amount);
                }
            done.put(token, true);
            while (done.length() > 500) done.remove(done.keys().next());
        }
        pending.remove(token);
        if (!prefs.edit()
                .putString("pending", pending.toString())
                .putString("pantry", pantry.toString())
                .putString("leftovers", ready.toString())
                .putString("consumedTokens", done.toString())
                .commit())
            throw new IllegalStateException(
                    "Meal saved; inventory needs recovery on the next planner visit");
    }

    private void put(String key, String value) {
        if (!prefs.edit().putString(key, value).commit())
            throw new IllegalStateException("Unable to save planner settings");
    }

    private static void readSet(JSONArray a, Set<String> out) throws JSONException {
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
    }
}
