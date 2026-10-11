package com.shadowphoenix.dietlog.planner;

import org.json.*;

import java.util.*;

/** Reusable selections, independent of any day's consumption or finite prepared stock. */
public final class StandardPlan {
    public static final int ALTERNATIVES = 4;
    public final Map<Integer, DayPlanner.Choice> selected = new LinkedHashMap<>();
    public final Map<Integer, List<DayPlanner.Choice>> alternatives = new LinkedHashMap<>();
    public final Map<Integer, String> unavailable = new LinkedHashMap<>();
    public DayPlanner.Result result;

    public static DayPlanner.Request templateRequest(DayPlanner.Request original) {
        DayPlanner.Request r = new DayPlanner.Request();
        r.profile = original.profile.copy();
        r.targets = original.targets.clone();
        r.remaining = r.profile.meals.clone();
        r.intent = original.intent;
        r.feedback.putAll(original.feedback);
        r.cancelled = original.cancelled;
        r.costWeight = original.costWeight;
        r.effortWeight = original.effortWeight;
        r.preferMealVariety = true;
        r.preferCuisine = true;
        return r;
    }

    public static StandardPlan generate(List<MealRecipe> recipes, DayPlanner.Request source) {
        DayPlanner.Request request = templateRequest(source);
        StandardPlan plan = new StandardPlan();
        plan.result = new DayPlanner().plan(recipes, request);
        for (DayPlanner.Choice choice : plan.result.choices) plan.selected.put(choice.meal, choice);
        for (int meal = 0; meal < 4; meal++)
            if (request.profile.meals[meal]) plan.refreshMeal(meal, recipes, request);
        plan.updateUnavailable(request.profile);
        return plan;
    }

    /** Only alternatives for this slot change; all other selected meals stay exactly as chosen. */
    public void refreshMeal(int meal, List<MealRecipe> recipes, DayPlanner.Request source) {
        DayPlanner.Request r = templateRequest(source);
        if (meal < 0 || meal > 3 || !r.profile.meals[meal])
            throw new IllegalArgumentException("This meal is disabled in your preferences");
        List<DayPlanner.Choice> seed = new ArrayList<>();
        DayPlanner.Choice current = selected.get(meal);
        if (suitable(current, meal, r.profile)) seed.add(current);
        alternatives.put(meal, fillAlternatives(meal, recipes, r, seed));
        updateUnavailable(r.profile);
    }

    private List<DayPlanner.Choice> fillAlternatives(
            int meal,
            List<MealRecipe> recipes,
            DayPlanner.Request r,
            List<DayPlanner.Choice> seed) {
        Arrays.fill(r.remaining, false);
        r.remaining[meal] = true;
        for (DayPlanner.Choice choice : selected.values())
            if (choice.meal != meal && suitable(choice, choice.meal, r.profile)) {
                r.remaining[choice.meal] = true;
                r.locked.put(choice.meal, choice);
            }
        List<DayPlanner.Choice> options = new ArrayList<>(seed);
        Set<String> excluded = new HashSet<>();
        Set<String> families = new HashSet<>();
        r.rejected.put(meal, excluded);
        r.rejectedFamilies.put(meal, families);
        for (DayPlanner.Choice option : options) {
            excluded.add(option.recipe.id);
            families.add(option.recipe.family);
        }
        avoidPairedFamily(meal, recipes, r, families, ALTERNATIVES - options.size());
        while (options.size() < ALTERNATIVES) {
            if (r.cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            DayPlanner.Result candidate = new DayPlanner().plan(recipes, r);
            DayPlanner.Choice option = null;
            for (DayPlanner.Choice choice : candidate.choices)
                if (choice.meal == meal) option = choice;
            if (option == null
                    || !excluded.add(option.recipe.id)
                    || !families.add(option.recipe.family)) break;
            options.add(option);
        }
        return options;
    }

    private void avoidPairedFamily(
            int meal,
            List<MealRecipe> recipes,
            DayPlanner.Request request,
            Set<String> excluded,
            int minimumDistinct) {
        DayPlanner.Choice paired = selected.get((meal + 2) % 4);
        if (paired == null || !suitable(paired, paired.meal, request.profile)) return;
        Set<String> availableFamilies = new HashSet<>();
        for (MealRecipe recipe :
                DayPlanner.candidateRecipes(recipes, request, meal, request.preferCuisine))
            if (recipe.allowed(request.profile, meal)
                    && !excluded.contains(recipe.family)
                    && !paired.recipe.family.equals(recipe.family))
                availableFamilies.add(recipe.family);
        if (availableFamilies.size() >= Math.max(1, minimumDistinct))
            excluded.add(paired.recipe.family);
    }

    private void updateUnavailable(PlannerProfile profile) {
        unavailable.clear();
        for (int meal = 0; meal < 4; meal++)
            if (profile.meals[meal] && !suitable(selected.get(meal), meal, profile))
                unavailable.put(
                        meal,
                        alternatives.getOrDefault(meal, Collections.emptyList()).isEmpty()
                                ? DayPlanner.unavailableMeal(meal)
                                : "Choose a suitable "
                                        + DayPlanner.mealName(meal)
                                        + " alternative.");
    }

    public void choose(
            DayPlanner.Choice choice, List<MealRecipe> recipes, DayPlanner.Request request) {
        if (choice == null || !suitable(choice, choice.meal, request.profile))
            throw new IllegalArgumentException(
                    "This recipe conflicts with your eating or cooking preferences");
        StandardPlan candidate = new StandardPlan();
        candidate.selected.putAll(selected);
        for (Map.Entry<Integer, List<DayPlanner.Choice>> entry : alternatives.entrySet())
            candidate.alternatives.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        candidate.selected.put(choice.meal, choice);
        List<DayPlanner.Choice> options =
                candidate.alternatives.computeIfAbsent(choice.meal, slot -> new ArrayList<>());
        int existing = -1;
        for (int i = 0; i < options.size(); i++)
            if (options.get(i).recipe.id.equals(choice.recipe.id)) {
                existing = i;
                break;
            }
        if (existing >= 0) options.set(existing, choice);
        else {
            options.add(0, choice);
            if (options.size() > ALTERNATIVES) options.remove(options.size() - 1);
        }
        candidate.evaluate(recipes, request);
        replaceWith(candidate);
    }

    private static boolean suitable(DayPlanner.Choice choice, int meal, PlannerProfile profile) {
        return choice != null
                && choice.meal == meal
                && profile.meals[meal]
                && choice.preparedScale == 0
                && choice.recipe.allowed(profile, meal);
    }

    private void replaceWith(StandardPlan other) {
        selected.clear();
        selected.putAll(other.selected);
        alternatives.clear();
        alternatives.putAll(other.alternatives);
        unavailable.clear();
        unavailable.putAll(other.unavailable);
        result = other.result;
    }

    public void evaluate(List<MealRecipe> recipes, DayPlanner.Request source) {
        DayPlanner.Request request = templateRequest(source);
        StandardPlan evaluated = new StandardPlan();
        for (int meal = 0; meal < 4; meal++) {
            if (!request.profile.meals[meal]) continue;
            DayPlanner.Choice choice = selected.get(meal);
            if (suitable(choice, meal, request.profile)) {
                evaluated.selected.put(meal, choice);
                request.locked.put(meal, choice);
            }
            List<DayPlanner.Choice> options = alternatives.get(meal);
            List<DayPlanner.Choice> valid = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (options != null)
                for (DayPlanner.Choice option : options)
                    if (suitable(option, meal, request.profile)
                            && (!suitable(choice, meal, request.profile)
                                    || !choice.recipe.family.equals(option.recipe.family)
                                    || choice.recipe.id.equals(option.recipe.id))
                            && seen.add(option.recipe.family)) {
                        valid.add(option);
                        if (valid.size() == ALTERNATIVES) break;
                    }
            evaluated.alternatives.put(meal, valid);
        }
        evaluated.result = new DayPlanner().plan(recipes, request);
        for (DayPlanner.Choice choice : evaluated.result.choices)
            evaluated.selected.putIfAbsent(choice.meal, choice);
        for (int meal = 0; meal < 4; meal++)
            if (request.profile.meals[meal]) {
                List<DayPlanner.Choice> options = evaluated.alternatives.get(meal);
                DayPlanner.Choice current = evaluated.selected.get(meal);
                boolean included = false;
                if (current != null)
                    for (DayPlanner.Choice option : options)
                        included |= option.recipe.id.equals(current.recipe.id);
                if (current != null && !included) {
                    options.removeIf(option -> option.recipe.family.equals(current.recipe.family));
                    options.add(0, current);
                    if (options.size() > ALTERNATIVES) options.remove(options.size() - 1);
                }
                if (options.size() < ALTERNATIVES)
                    evaluated.alternatives.put(
                            meal,
                            evaluated.fillAlternatives(
                                    meal, recipes, templateRequest(source), options));
            }
        evaluated.updateUnavailable(request.profile);
        replaceWith(evaluated);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject rows = new JSONObject();
        JSONArray chosen = new JSONArray();
        for (DayPlanner.Choice c : selected.values()) chosen.put(json(c));
        for (Map.Entry<Integer, List<DayPlanner.Choice>> e : alternatives.entrySet()) {
            JSONArray options = new JSONArray();
            for (DayPlanner.Choice c : e.getValue()) options.put(json(c));
            rows.put(String.valueOf(e.getKey()), options);
        }
        return new JSONObject().put("schema", 1).put("selected", chosen).put("alternatives", rows);
    }

    private static JSONObject json(DayPlanner.Choice c) throws JSONException {
        return new JSONObject()
                .put("meal", c.meal)
                .put("recipe", c.recipe.id)
                .put("scale", c.scale);
    }

    public static StandardPlan fromJson(
            JSONObject json, List<MealRecipe> recipes, DayPlanner.Request request)
            throws JSONException {
        StandardPlan plan = new StandardPlan();
        if (json.optInt("schema", 0) != 1) return null;
        Map<String, MealRecipe> byId = new HashMap<>();
        for (MealRecipe r : recipes) byId.put(r.id, r);
        JSONArray chosen = json.optJSONArray("selected");
        if (chosen != null && chosen.length() <= 4)
            for (int i = 0; i < chosen.length(); i++) {
                DayPlanner.Choice c = read(chosen.getJSONObject(i), byId);
                if (c != null) plan.selected.put(c.meal, c);
            }
        JSONObject groups = json.optJSONObject("alternatives");
        if (groups != null)
            for (int meal = 0; meal < 4; meal++) {
                JSONArray options = groups.optJSONArray(String.valueOf(meal));
                List<DayPlanner.Choice> values = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                if (options != null)
                    for (int i = 0; i < Math.min(ALTERNATIVES, options.length()); i++) {
                        DayPlanner.Choice c = read(options.getJSONObject(i), byId);
                        if (c != null && c.meal == meal && seen.add(c.recipe.id)) values.add(c);
                    }
                plan.alternatives.put(meal, values);
            }
        plan.evaluate(recipes, request);
        return plan;
    }

    private static DayPlanner.Choice read(JSONObject row, Map<String, MealRecipe> recipes) {
        MealRecipe recipe = recipes.get(row.optString("recipe"));
        if (recipe == null) return null;
        try {
            return new DayPlanner.Choice(row.optInt("meal", -1), recipe, row.optDouble("scale", 0));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
