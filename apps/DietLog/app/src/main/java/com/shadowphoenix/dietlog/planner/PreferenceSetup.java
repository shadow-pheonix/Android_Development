package com.shadowphoenix.dietlog.planner;

import java.util.*;
import org.json.*;

/** Stable, resumable pages. Rating a card never reshuffles the page beneath the user. */
public final class PreferenceSetup {
    public static final int BASICS = 6, BATCH_SIZE = 4;

    public static final class Batch {
        public final boolean recipes;
        public final int meal;
        public final List<String> ids;

        public Batch(boolean recipes, int meal, List<String> ids) {
            this.recipes = recipes;
            this.meal = meal;
            this.ids = Collections.unmodifiableList(new ArrayList<>(ids));
        }
    }

    public int page;
    public boolean completed;
    public String selectionIdentity = "";
    public JSONObject draft = new JSONObject();
    public final List<Batch> batches = new ArrayList<>();

    public int reviewPage() {
        return BASICS + batches.size();
    }

    public PreferenceSetup copy() throws JSONException {
        return fromJson(new JSONObject(toJson().toString()));
    }

    /** A completed worker save wins; otherwise preserve the form visible before interruption. */
    public PreferenceSetup resumeDraft(PreferenceSetup visible, String persistedBefore)
            throws JSONException {
        return persistedBefore != null && !toJson().toString().equals(persistedBefore)
                ? this
                : visible;
    }

    public JSONObject toJson() throws JSONException {
        JSONArray pages = new JSONArray();
        for (Batch b : batches)
            pages.put(
                    new JSONObject()
                            .put("recipes", b.recipes)
                            .put("meal", b.meal)
                            .put("ids", new JSONArray(b.ids)));
        return new JSONObject()
                .put("schema", 1)
                .put("page", page)
                .put("completed", completed)
                .put("identity", selectionIdentity)
                .put("draft", draft)
                .put("batches", pages);
    }

    public static PreferenceSetup fromJson(JSONObject j) throws JSONException {
        PreferenceSetup state = new PreferenceSetup();
        if (j.optInt("schema", 1) != 1) return state;
        JSONArray pages = j.optJSONArray("batches");
        if (pages != null && pages.length() <= 120) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject b = pages.getJSONObject(i);
                JSONArray ids = b.getJSONArray("ids");
                if (ids.length() > BATCH_SIZE) throw new JSONException("Invalid preference batch");
                List<String> values = new ArrayList<>();
                for (int n = 0; n < ids.length(); n++) values.add(ids.getString(n));
                state.batches.add(new Batch(b.getBoolean("recipes"), b.getInt("meal"), values));
            }
        }
        state.page = Math.max(0, Math.min(state.reviewPage(), j.optInt("page", 0)));
        state.completed = j.optBoolean("completed", false);
        state.selectionIdentity = j.optString("identity", "");
        JSONObject draft = j.optJSONObject("draft");
        if (draft != null) state.draft = draft;
        return state;
    }

    /** Taste prompts are not recommendations to eat: saved dislikes remain editable. */
    public void buildBatches(
            List<MealRecipe> recipes,
            Collection<PlannerFood> foods,
            PlannerProfile p,
            IntentModel diary,
            double proteinPerCalorie)
            throws JSONException {
        batches.clear();
        PlannerProfile suitability = p.copy();
        suitability.ratings.clear();
        suitability.aliases.clear();
        Map<Integer, List<MealRecipe>> groups = new LinkedHashMap<>();
        for (int meal : new int[] {0, 1, 3, 2})
            if (p.meals[meal]) groups.put(meal, new ArrayList<>());
        for (MealRecipe recipe : recipes) {
            int category = recipe.primaryMeal;
            if (!groups.containsKey(category) || !recipe.allowed(suitability, category)) {
                category = -1;
                for (int slot : groups.keySet())
                    if (recipe.allowed(suitability, slot)
                            && (category == -1
                                    || groups.get(slot).size() < groups.get(category).size()))
                        category = slot;
            }
            if (category >= 0) groups.get(category).add(recipe);
        }
        for (Map.Entry<Integer, List<MealRecipe>> entry : groups.entrySet()) {
            int meal = entry.getKey();
            List<MealRecipe> group = entry.getValue();
            group.sort(
                    Comparator.comparingInt(
                                    (MealRecipe r) ->
                                            p.rating(r.id) == FoodPreference.UNSET ? 0 : 1)
                            .thenComparingInt(
                                    r ->
                                            !"Any".equals(p.cuisine) && p.cuisine.equals(r.cuisine)
                                                    ? 0
                                                    : 1)
                            .thenComparing(
                                    Comparator.comparingDouble(
                                                    (MealRecipe r) ->
                                                            relevance(
                                                                    r,
                                                                    meal,
                                                                    p,
                                                                    diary,
                                                                    proteinPerCalorie))
                                            .reversed())
                            .thenComparing(r -> r.id));
            // Cycle dish families, so a page explores different foods instead of four rice
            // variants.
            List<String> discovery = new ArrayList<>();
            for (boolean unrated : new boolean[] {true, false}) {
                Map<String, ArrayDeque<MealRecipe>> families = new LinkedHashMap<>();
                for (MealRecipe recipe : group)
                    if ((p.rating(recipe.id) == FoodPreference.UNSET) == unrated)
                        families.computeIfAbsent(recipe.family, key -> new ArrayDeque<>())
                                .add(recipe);
                if ("Any".equals(p.cuisine)) {
                    ArrayDeque<String> regional = new ArrayDeque<>(), other = new ArrayDeque<>();
                    for (Map.Entry<String, ArrayDeque<MealRecipe>> family : families.entrySet())
                        ("Andhra".equals(family.getValue().peekFirst().cuisine) ? regional : other)
                                .add(family.getKey());
                    Map<String, ArrayDeque<MealRecipe>> mixed = new LinkedHashMap<>();
                    while (!regional.isEmpty() || !other.isEmpty()) {
                        if (!regional.isEmpty()) {
                            String id = regional.removeFirst();
                            mixed.put(id, families.get(id));
                        }
                        if (!other.isEmpty()) {
                            String id = other.removeFirst();
                            mixed.put(id, families.get(id));
                        }
                    }
                    families = mixed;
                }
                while (!families.isEmpty()) {
                    Iterator<ArrayDeque<MealRecipe>> queues = families.values().iterator();
                    while (queues.hasNext()) {
                        ArrayDeque<MealRecipe> queue = queues.next();
                        discovery.add(queue.removeFirst().id);
                        if (queue.isEmpty()) queues.remove();
                    }
                }
            }
            add(true, meal, discovery);
        }
        // Recipe ingredients follow dishes, with ingredients from explicitly loved recipes first.
        Set<String> lovedParts = new HashSet<>();
        for (MealRecipe recipe : recipes)
            if (p.rating(recipe.id) == FoodPreference.LOVE)
                for (MealRecipe.Ingredient ingredient : recipe.ingredients)
                    lovedParts.add(ingredient.food.id);
        Map<String, PlannerFood> unique = new LinkedHashMap<>();
        for (PlannerFood food : foods) unique.putIfAbsent(food.id, food);
        List<PlannerFood> ordered = new ArrayList<>(unique.values());
        ordered.sort(
                Comparator.comparingInt(
                                (PlannerFood f) -> p.rating(f.id) == FoodPreference.UNSET ? 0 : 1)
                        .thenComparingInt(f -> lovedParts.contains(f.id) ? 0 : 1)
                        .thenComparing(f -> f.name));
        add(
                false,
                -1,
                ordered.stream().map(f -> f.id).collect(java.util.stream.Collectors.toList()));
        selectionIdentity = selectionIdentity(p);
    }

    private static double relevance(
            MealRecipe r, int meal, PlannerProfile p, IntentModel diary, double ratio) {
        double score = !"Any".equals(p.cuisine) && p.cuisine.equals(r.cuisine) ? 2 : 0;
        for (MealRecipe.Ingredient i : r.ingredients) {
            FoodPreference choice = i.food.rating(p);
            if (choice == FoodPreference.LOVE) score += 3.0 / r.ingredients.size();
            if (choice.excludes()) score -= 6.0 / r.ingredients.size();
        }
        double[] n = r.nutrition(1);
        if (Double.isFinite(ratio) && ratio > 0 && n[0] > 0)
            score += Math.max(0, 1 - Math.abs(n[1] / n[0] - ratio) / ratio);
        double budget = p.effectiveDailyBudget();
        if (budget > 0 && PriceModel.consumed(r, 1, p).amount <= budget / 4) score += 1;
        score += 0.5 * (1 - Math.min(1, r.prepMinutes / 60.0));
        // History is only a small tie-breaker; it never sets a rating.
        if (p.learnFromHistory && p.rating(r.id) == FoodPreference.UNSET)
            score += Math.max(0, diary.score(r.id, meal, p)) * 0.2;
        return score;
    }

    private void add(boolean recipes, int meal, List<String> ids) {
        for (int i = 0; i < ids.size(); i += BATCH_SIZE)
            batches.add(
                    new Batch(recipes, meal, ids.subList(i, Math.min(i + BATCH_SIZE, ids.size()))));
    }

    public static String selectionIdentity(PlannerProfile p) {
        return "diverse-regional-v2:"
                + p.diet.name()
                + new TreeSet<>(p.allergies)
                + new TreeSet<>(p.excludedIngredients)
                + Arrays.toString(p.meals)
                + p.canCook
                + ":"
                + p.maxPrepMinutes
                + ":"
                + p.cuisine;
    }
}
