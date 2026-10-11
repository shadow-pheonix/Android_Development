package com.shadowphoenix.dietlog.planner;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Device-local, user-controlled preferences. Nutritional targets live in the existing profile. */
public final class PlannerProfile {
    public enum Diet { ANY, VEGETARIAN, EGGS_ALLOWED, VEGAN }
    public Diet diet = Diet.ANY;
    public final Set<String> excludedIngredients = new LinkedHashSet<>();
    public final Set<String> allergies = new LinkedHashSet<>();
    public final Map<String, FoodPreference> ratings = new LinkedHashMap<>();
    public final Map<String, String> ratingNames = new LinkedHashMap<>();
    public final Map<String, Double> prices = new LinkedHashMap<>(); // rupees / 100g, or per recipe serving
    public double dailyBudget = 0; // zero = unspecified
    public double weeklyBudget = 0;
    public int maxPrepMinutes = 45;
    public boolean canCook = true;
    public boolean learnFromHistory = true;
    public String cuisine = "Any";
    public final boolean[] meals = {true, true, true, true};
    public int largestMeal = 1;
    public FoodPreference rating(String foodId) { return ratings.getOrDefault(foodId, FoodPreference.UNSET); }
    public void validate() {
        if (!Double.isFinite(dailyBudget) || dailyBudget < 0 || dailyBudget > 100000
                || !Double.isFinite(weeklyBudget) || weeklyBudget < 0 || weeklyBudget > 700000)
            throw new IllegalArgumentException("Enter a valid food budget");
        if (maxPrepMinutes < 0 || maxPrepMinutes > 240) throw new IllegalArgumentException("Cooking time must be 0–240 minutes");
        boolean any = false; for (boolean meal : meals) any |= meal;
        if (!any) throw new IllegalArgumentException("Choose at least one meal");
        if (largestMeal < 0 || largestMeal > 3) throw new IllegalArgumentException("Choose a valid largest meal");
        for (double price : prices.values()) if (!Double.isFinite(price) || price < 0 || price > 100000)
            throw new IllegalArgumentException("Enter a valid food price");
    }
    public double effectiveDailyBudget() {
        if (weeklyBudget <= 0) return dailyBudget;
        return dailyBudget <= 0 ? weeklyBudget / 7 : Math.min(dailyBudget, weeklyBudget / 7);
    }
}
