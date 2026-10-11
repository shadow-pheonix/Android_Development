package com.shadowphoenix.dietlog.planner;

import java.util.*;

public final class PriceModel {
    public static final class Cost {
        public final double amount;
        public final boolean allPricesEntered;

        public Cost(double amount, boolean allPricesEntered) {
            this.amount = amount;
            this.allPricesEntered = allPricesEntered;
        }
    }

    private PriceModel() {}

    public static Cost consumed(MealRecipe recipe, double scale, PlannerProfile p) {
        double total = 0;
        boolean entered = true;
        for (MealRecipe.Ingredient i : recipe.ingredients) {
            Double price = p.prices.get(i.food.id);
            entered &= price != null;
            total += (price == null ? i.food.referencePrice : price) * i.grams / 100 * scale;
        }
        return new Cost(total, entered);
    }

    /** Shopping packs are rounded once after consolidating all recipe ingredient needs. */
    public static double shoppingCost(
            double neededGrams, double pantryGrams, double packGrams, double packPrice) {
        if (!Double.isFinite(neededGrams)
                || !Double.isFinite(pantryGrams)
                || !Double.isFinite(packGrams)
                || !Double.isFinite(packPrice)
                || neededGrams < 0
                || pantryGrams < 0
                || packGrams <= 0
                || packPrice < 0)
            throw new IllegalArgumentException("Invalid shopping quantity or price");
        return Math.ceil(Math.max(0, neededGrams - pantryGrams) / packGrams) * packPrice;
    }
}
