package com.shadowphoenix.dietlog.planner;

import java.util.*;

public final class MealRecipe {
    public static final class Ingredient {
        public final PlannerFood food;
        public final double grams;

        public Ingredient(PlannerFood food, double grams) {
            if (food == null || !Double.isFinite(grams) || grams <= 0 || grams > 5000)
                throw new IllegalArgumentException("Invalid ingredient amount");
            this.food = food;
            this.grams = grams;
        }
    }

    public final String id, name, instructions, cuisine, family;
    public final int mealMask, prepMinutes, primaryMeal;
    public final boolean requiresCooking;
    public final List<Ingredient> ingredients;
    public final double yieldGrams;

    public MealRecipe(
            String id,
            String name,
            int mealMask,
            int prepMinutes,
            boolean requiresCooking,
            String cuisine,
            String instructions,
            double yieldGrams,
            List<Ingredient> ingredients) {
        this(
                id,
                name,
                mealMask,
                prepMinutes,
                requiresCooking,
                cuisine,
                instructions,
                yieldGrams,
                ingredients,
                null,
                Integer.numberOfTrailingZeros(mealMask));
    }

    public MealRecipe(
            String id,
            String name,
            int mealMask,
            int prepMinutes,
            boolean requiresCooking,
            String cuisine,
            String instructions,
            double yieldGrams,
            List<Ingredient> ingredients,
            String family,
            int primaryMeal) {
        if (id == null
                || id.isEmpty()
                || name == null
                || name.trim().isEmpty()
                || ingredients.isEmpty()
                || ingredients.size() > 20
                || mealMask <= 0
                || mealMask > 15
                || primaryMeal < 0
                || primaryMeal > 3
                || (mealMask & (1 << primaryMeal)) == 0
                || prepMinutes < 0
                || prepMinutes > 240
                || !Double.isFinite(yieldGrams)
                || yieldGrams <= 0
                || yieldGrams > 10000) throw new IllegalArgumentException("Invalid meal recipe");
        this.id = id;
        this.name = name;
        this.mealMask = mealMask;
        this.prepMinutes = prepMinutes;
        this.requiresCooking = requiresCooking;
        this.cuisine = cuisine;
        this.instructions = instructions;
        this.yieldGrams = yieldGrams;
        this.ingredients = Collections.unmodifiableList(new ArrayList<>(ingredients));
        this.primaryMeal = primaryMeal;
        // Old and personal recipes have no family metadata. Identical ingredient sets still
        // count as one dish, independently of recipe IDs or edited quantities.
        Set<String> identities = new TreeSet<>();
        for (Ingredient ingredient : ingredients) identities.add(ingredient.food.id);
        this.family =
                family == null || family.trim().isEmpty()
                        ? "ingredients:" + String.join("|", identities)
                        : family.trim();
    }

    public double[] nutrition(double scale) {
        if (!Double.isFinite(scale) || scale <= 0 || scale > 3)
            throw new IllegalArgumentException("Invalid serving amount");
        double[] result = new double[8];
        for (Ingredient i : ingredients)
            for (int n = 0; n < 8; n++) {
                if (Double.isNaN(i.food.nutrients[n])) result[n] = Double.NaN;
                else result[n] += i.food.nutrients[n] * i.grams / 100 * scale;
            }
        return result;
    }

    public boolean allowed(PlannerProfile p, int meal) {
        return allowed(p, meal, false);
    }

    public boolean allowed(PlannerProfile p, int meal, boolean alreadyPrepared) {
        if ((mealMask & (1 << meal)) == 0
                || (!alreadyPrepared
                        && (prepMinutes > p.maxPrepMinutes || (requiresCooking && !p.canCook)))
                || p.rating(id).excludes()) return false;
        for (Ingredient i : ingredients) {
            if (i.food.rating(p).excludes()) return false;
            Set<String> tags = i.food.tags;
            if (!Collections.disjoint(tags, p.excludedIngredients)
                    || !Collections.disjoint(tags, p.allergies)) return false;
            // Unknown composition cannot establish suitability for an exclusion or a dietary
            // pattern.
            if (!i.food.ingredientsKnown
                    && (!p.allergies.isEmpty()
                            || !p.excludedIngredients.isEmpty()
                            || p.diet != PlannerProfile.Diet.ANY)) return false;
            if (p.diet != PlannerProfile.Diet.ANY
                    && (tags.contains("meat")
                            || tags.contains("fish")
                            || tags.contains("shellfish"))) return false;
            if ((p.diet == PlannerProfile.Diet.VEGETARIAN || p.diet == PlannerProfile.Diet.VEGAN)
                    && tags.contains("egg")) return false;
            if (p.diet == PlannerProfile.Diet.VEGAN
                    && (tags.contains("dairy") || tags.contains("honey"))) return false;
        }
        return true;
    }

    public Set<String> tags() {
        Set<String> result = new HashSet<>();
        for (Ingredient i : ingredients) result.addAll(i.food.tags);
        return result;
    }
}
