package com.shadowphoenix.dietlog.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Nutrition and prices use the same explicitly weighed state, raw or cooked. */
public final class PlannerFood {
    public final String id, name, state;
    public final double[]
            nutrients; // kcal, protein, carbs, fat, fibre, sodium, sugars, saturated fat
    public final Set<String> tags;
    public final boolean ingredientsKnown;
    public final Set<String> preferenceIds;
    public final double referencePrice;

    public PlannerFood(
            String id,
            String name,
            String state,
            double[] nutrients,
            Set<String> tags,
            boolean ingredientsKnown,
            double referencePrice) {
        this(
                id,
                name,
                state,
                nutrients,
                tags,
                ingredientsKnown,
                referencePrice,
                Collections.singleton(id));
    }

    public PlannerFood(
            String id,
            String name,
            String state,
            double[] nutrients,
            Set<String> tags,
            boolean ingredientsKnown,
            double referencePrice,
            java.util.Collection<String> preferenceIds) {
        if (id == null || id.isEmpty() || nutrients.length != 8)
            throw new IllegalArgumentException("Invalid planner food");
        for (int i = 0; i < 4; i++)
            if (!Double.isFinite(nutrients[i]) || nutrients[i] < 0)
                throw new IllegalArgumentException("Incomplete core nutrition");
        if (!Double.isFinite(referencePrice) || referencePrice < 0)
            throw new IllegalArgumentException("Invalid price");
        this.id = id;
        this.name = name;
        this.state = state;
        this.nutrients = nutrients.clone();
        this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
        this.ingredientsKnown = ingredientsKnown;
        Set<String> identities = new LinkedHashSet<>(preferenceIds);
        identities.add(id);
        if (identities.size() > 8 || identities.contains(null) || identities.contains(""))
            throw new IllegalArgumentException("Invalid food preference identities");
        this.preferenceIds = Collections.unmodifiableSet(identities);
        this.referencePrice = referencePrice;
    }

    /** State-specific nutrition stays separate; explicit exclusions cover the same edible food. */
    public FoodPreference rating(PlannerProfile profile) {
        FoodPreference direct = profile.rating(id), best = direct;
        for (String identity : preferenceIds) {
            FoodPreference linked = profile.rating(identity);
            if (linked == FoodPreference.NEVER) return linked;
            if (linked == FoodPreference.AVOID) best = linked;
            else if (!best.excludes()
                    && direct == FoodPreference.UNSET
                    && linked.score > best.score) best = linked;
        }
        return best;
    }
}
