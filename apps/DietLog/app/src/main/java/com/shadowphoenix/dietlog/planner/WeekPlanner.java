package com.shadowphoenix.dietlog.planner;

import java.util.*;

/** Seven bounded daily searches with cumulative budget, stock and repeat evidence. */
public final class WeekPlanner {
    public static final class Result {
        public final List<DayPlanner.Result> days;
        public final List<DayPlanner.Request> requests;
        public final double consumedCost;
        public final boolean complete, budgetMet;

        Result(
                List<DayPlanner.Result> days,
                List<DayPlanner.Request> requests,
                double cost,
                boolean complete,
                boolean budget) {
            this.days = Collections.unmodifiableList(days);
            this.requests = Collections.unmodifiableList(requests);
            consumedCost = cost;
            this.complete = complete;
            budgetMet = budget;
        }

        public List<DayPlanner.Choice> meals() {
            List<DayPlanner.Choice> all = new ArrayList<>();
            for (DayPlanner.Result day : days) all.addAll(day.choices);
            return all;
        }
    }

    public Result plan(List<MealRecipe> meals, List<DayPlanner.Request> inputs) {
        if (inputs.size() != 7)
            throw new IllegalArgumentException("A weekly plan needs seven daily target profiles");
        List<DayPlanner.Result> days = new ArrayList<>();
        List<DayPlanner.Request> requests = new ArrayList<>();
        Map<String, Integer> repeats = new HashMap<>();
        Map<String, Double> preparedUsed = new HashMap<>();
        Map<String, Double> stock = new HashMap<>(inputs.get(0).pantryGrams);
        double weekly = inputs.get(0).profile.weeklyBudget, cost = 0;
        boolean complete = true, budget = true;
        for (int d = 0; d < 7; d++) {
            DayPlanner.Request r = inputs.get(d);
            PlannerProfile p = r.profile.copy();
            double allocation = weekly > 0 ? Math.max(0.01, (weekly - cost) / (7 - d)) : 0;
            if (allocation > 0)
                p.dailyBudget =
                        p.dailyBudget > 0 ? Math.min(p.dailyBudget, allocation) : allocation;
            p.weeklyBudget = 0;
            r.profile = p;
            for (String id : new ArrayList<>(r.readyServings.keySet()))
                r.readyServings.put(
                        id,
                        Math.max(0, r.readyServings.get(id) - preparedUsed.getOrDefault(id, 0.0)));
            r.recentRecipes.putAll(repeats);
            r.pantryGrams.clear();
            r.pantryGrams.putAll(stock);
            DayPlanner.Result plan = new DayPlanner().plan(meals, r);
            days.add(plan);
            requests.add(r);
            cost += plan.cost;
            budget &= plan.budgetMet;
            int count = 0;
            for (int m = 0; m < 4; m++) if (r.remaining[m] && p.meals[m]) count++;
            complete &= plan.choices.size() == count;
            for (DayPlanner.Choice c : plan.choices) {
                preparedUsed.merge(c.recipe.id, c.preparedScale, Double::sum);
                repeats.merge(c.recipe.id, 1, Integer::sum);
                for (MealRecipe.Ingredient i : c.recipe.ingredients)
                    stock.compute(
                            i.food.id,
                            (k, v) ->
                                    Math.max(
                                            0,
                                            (v == null ? 0 : v)
                                                    - i.grams * (c.scale - c.preparedScale)));
            }
        }
        return new Result(
                days, requests, cost, complete, budget && (weekly <= 0 || cost <= weekly + 0.01));
    }
}
