package com.shadowphoenix.dietlog.planner;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Device-local, user-controlled preferences. Nutritional targets live in the existing profile. */
public final class PlannerProfile {
    public enum Diet {
        ANY,
        VEGETARIAN,
        EGGS_ALLOWED,
        VEGAN
    }

    public Diet diet = Diet.ANY;
    public final Set<String> excludedIngredients = new LinkedHashSet<>();
    public final Set<String> allergies = new LinkedHashSet<>();
    public final Map<String, FoodPreference> ratings = new LinkedHashMap<>();
    public final Map<String, String> ratingNames = new LinkedHashMap<>();
    public final Map<String, Double> prices =
            new LinkedHashMap<>(); // rupees / 100g in the ingredient state
    public final Map<String, String> aliases =
            new LinkedHashMap<>(); // user-confirmed identities only
    public double dailyBudget = 0; // zero = unspecified
    public double weeklyBudget = 0;
    public int maxPrepMinutes = 45;
    public boolean canCook = true;
    public boolean learnFromHistory = true;
    public String cuisine = "Any";
    public final boolean[] meals = {true, true, true, true};
    public int largestMeal = -1; // automatic routine; explicit 0–3 overrides learning

    public String canonicalId(String id) {
        return aliases.getOrDefault(id, id);
    }

    public FoodPreference rating(String id) {
        String canonical = canonicalId(id);
        FoodPreference direct = ratings.getOrDefault(canonical, FoodPreference.UNSET),
                best = direct;
        if (direct == FoodPreference.NEVER) return direct;
        for (Map.Entry<String, String> e : aliases.entrySet())
            if (e.getValue().equals(canonical)) {
                FoodPreference linked = ratings.getOrDefault(e.getKey(), FoodPreference.UNSET);
                if (linked == FoodPreference.NEVER) return FoodPreference.NEVER;
                if (linked == FoodPreference.AVOID) best = FoodPreference.AVOID;
                else if (!best.excludes()
                        && direct == FoodPreference.UNSET
                        && linked.score > best.score) best = linked;
            }
        return best;
    }

    public PlannerProfile copy() {
        PlannerProfile p = new PlannerProfile();
        p.diet = diet;
        p.excludedIngredients.addAll(excludedIngredients);
        p.allergies.addAll(allergies);
        p.ratings.putAll(ratings);
        p.ratingNames.putAll(ratingNames);
        p.prices.putAll(prices);
        p.aliases.putAll(aliases);
        p.dailyBudget = dailyBudget;
        p.weeklyBudget = weeklyBudget;
        p.maxPrepMinutes = maxPrepMinutes;
        p.canCook = canCook;
        p.learnFromHistory = learnFromHistory;
        p.cuisine = cuisine;
        System.arraycopy(meals, 0, p.meals, 0, 4);
        p.largestMeal = largestMeal;
        return p;
    }

    public void validate() {
        if (!Double.isFinite(dailyBudget)
                || dailyBudget < 0
                || dailyBudget > 100000
                || !Double.isFinite(weeklyBudget)
                || weeklyBudget < 0
                || weeklyBudget > 700000)
            throw new IllegalArgumentException("Enter a valid food budget");
        if (maxPrepMinutes < 0 || maxPrepMinutes > 240)
            throw new IllegalArgumentException("Cooking time must be 0–240 minutes");
        if (aliases.size() > 200)
            throw new IllegalArgumentException("Keep at most 200 diary food links");
        for (Map.Entry<String, String> e : aliases.entrySet())
            if (e.getKey() == null
                    || e.getValue() == null
                    || e.getValue().equals(e.getKey())
                    || aliases.containsKey(e.getValue()))
                throw new IllegalArgumentException(
                        "Diary food links must point directly to a planner food or meal");
        boolean any = false;
        for (boolean meal : meals) any |= meal;
        if (!any) throw new IllegalArgumentException("Choose at least one meal");
        if (largestMeal < -1 || largestMeal > 3)
            throw new IllegalArgumentException("Choose a valid largest meal");
        for (double price : prices.values())
            if (!Double.isFinite(price) || price < 0 || price > 100000)
                throw new IllegalArgumentException("Enter a valid food price");
    }

    public double effectiveDailyBudget() {
        if (weeklyBudget <= 0) return dailyBudget;
        return dailyBudget <= 0 ? weeklyBudget / 7 : Math.min(dailyBudget, weeklyBudget / 7);
    }
}
