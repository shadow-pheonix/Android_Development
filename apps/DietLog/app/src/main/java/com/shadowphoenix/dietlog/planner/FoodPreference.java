package com.shadowphoenix.dietlog.planner;

/** Explicit intent is kept separate from inferred familiarity. */
public enum FoodPreference {
    UNSET("Not rated", 0),
    LOVE("Love", 5),
    OKAY("Okay / manageable", 1),
    AVOID("Avoid", -10),
    NEVER("Never recommend", -100);
    public final String label;
    public final double score;

    FoodPreference(String label, double score) {
        this.label = label;
        this.score = score;
    }

    public boolean excludes() {
        return this == AVOID || this == NEVER;
    }
}
