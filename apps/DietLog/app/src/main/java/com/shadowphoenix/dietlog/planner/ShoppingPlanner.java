package com.shadowphoenix.dietlog.planner;

import java.util.*;

/**
 * Ingredient aggregation uses weighed states; pack cost is cash outlay, separate from food
 * consumed.
 */
public final class ShoppingPlanner {
    public static final class Pack {
        public final double grams, price;

        public Pack(double grams, double price) {
            if (!Double.isFinite(grams)
                    || grams <= 0
                    || grams > 100000
                    || !Double.isFinite(price)
                    || price < 0
                    || price > 100000)
                throw new IllegalArgumentException(
                        "Use a positive pack size and non-negative pack price");
            this.grams = grams;
            this.price = price;
        }
    }

    public static final class Row {
        public final PlannerFood food;
        public final double needed, onHand, buyGrams, cost;
        public final int packs;
        public final boolean entered, packConfigured;

        Row(PlannerFood food, double needed, double onHand, Pack pack, PlannerProfile profile) {
            this.food = food;
            this.needed = needed;
            this.onHand = onHand;
            double missing = Math.max(0, needed - onHand);
            packConfigured = pack != null;
            packs = pack == null ? 0 : (int) Math.ceil(missing / pack.grams);
            buyGrams = pack == null ? missing : packs * pack.grams;
            Double price = profile.prices.get(food.id);
            entered = missing == 0 || pack != null || price != null;
            cost =
                    pack != null
                            ? packs * pack.price
                            : buyGrams / 100 * (price == null ? food.referencePrice : price);
        }
    }

    public static final class Result {
        public final List<Row> rows;
        public final double cash;
        public final boolean pricesEntered, allPacksKnown;

        Result(List<Row> rows) {
            this.rows = Collections.unmodifiableList(rows);
            double total = 0;
            boolean entered = true, packs = true;
            for (Row r : rows) {
                total += r.cost;
                entered &= r.entered;
                packs &= r.buyGrams == 0 || r.packConfigured;
            }
            cash = total;
            pricesEntered = entered;
            allPacksKnown = packs;
        }
    }

    public Result list(
            List<DayPlanner.Choice> meals,
            PlannerProfile profile,
            Map<String, Double> pantry,
            Map<String, Pack> packs) {
        Map<String, Double> needed = new LinkedHashMap<>();
        Map<String, PlannerFood> foods = new LinkedHashMap<>();
        for (DayPlanner.Choice c : meals)
            for (MealRecipe.Ingredient i : c.recipe.ingredients) {
                foods.put(i.food.id, i.food);
                needed.merge(i.food.id, i.grams * (c.scale - c.preparedScale), Double::sum);
            }
        List<Row> rows = new ArrayList<>();
        for (String id : needed.keySet()) {
            double stock = pantry.getOrDefault(id, 0.0);
            if (!Double.isFinite(stock) || stock < 0 || stock > 100000)
                throw new IllegalArgumentException("Use valid pantry amounts");
            rows.add(new Row(foods.get(id), needed.get(id), stock, packs.get(id), profile));
        }
        return new Result(rows);
    }
}
