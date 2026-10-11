package com.shadowphoenix.dietlog.planner;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Nutrition and prices use the same explicitly weighed state, raw or cooked. */
public final class PlannerFood {
    public final String id, name, state;
    public final double[] nutrients; // kcal, protein, carbs, fat, fibre, sodium, sugars, saturated fat
    public final Set<String> tags;
    public final boolean ingredientsKnown;
    public final double referencePrice;
    public PlannerFood(String id, String name, String state, double[] nutrients, Set<String> tags,
                       boolean ingredientsKnown, double referencePrice) {
        if (id == null || id.isEmpty() || nutrients.length != 8) throw new IllegalArgumentException("Invalid planner food");
        for (int i=0;i<4;i++) if (!Double.isFinite(nutrients[i]) || nutrients[i]<0) throw new IllegalArgumentException("Incomplete core nutrition");
        if (!Double.isFinite(referencePrice) || referencePrice<0) throw new IllegalArgumentException("Invalid price");
        this.id=id; this.name=name; this.state=state; this.nutrients=nutrients.clone();
        this.tags=Collections.unmodifiableSet(new LinkedHashSet<>(tags));
        this.ingredientsKnown=ingredientsKnown; this.referencePrice=referencePrice;
    }
}
