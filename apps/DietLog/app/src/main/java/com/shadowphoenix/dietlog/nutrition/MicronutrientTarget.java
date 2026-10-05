package com.shadowphoenix.dietlog.nutrition;

public final class MicronutrientTarget {
    public final Micronutrient nutrient;
    public final double amount;
    public final boolean upperLimit;
    public final String basis;

    public MicronutrientTarget(Micronutrient nutrient, double amount, boolean upperLimit, String basis) {
        this.nutrient = nutrient;
        this.amount = amount;
        this.upperLimit = upperLimit;
        this.basis = basis;
    }
}
