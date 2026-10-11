package com.shadowphoenix.dietlog.planner;

import java.util.*;

/** Deterministic bounded whole-day beam search; exclusions are applied before scoring. */
public final class DayPlanner {
    public static final int BEAM_WIDTH = 192, MAX_OPTIONS = 600;

    public static final class Choice {
        public final int meal;
        public final MealRecipe recipe;
        public final double scale, preparedScale;

        public Choice(int meal, MealRecipe recipe, double scale) {
            this(meal, recipe, scale, 0);
        }

        public Choice(int meal, MealRecipe recipe, double scale, double preparedScale) {
            if (meal < 0
                    || meal > 3
                    || recipe == null
                    || !Double.isFinite(scale)
                    || scale < 0.5
                    || scale > 2) throw new IllegalArgumentException("Invalid meal choice");
            if (!Double.isFinite(preparedScale) || preparedScale < 0 || preparedScale > scale)
                throw new IllegalArgumentException("Invalid prepared portions");
            this.meal = meal;
            this.recipe = recipe;
            this.scale = scale;
            this.preparedScale = preparedScale;
        }
    }

    public static final class Request {
        public PlannerProfile profile = new PlannerProfile();
        public double[] targets = {2000, 100, 250, 65, 30, 2000, 50, 20};
        public double[] consumed = new double[8];
        public double consumedCost;
        public boolean consumedCostKnown = true;
        public boolean[] remaining = {true, true, true, true};
        public final Map<Integer, Choice> locked = new HashMap<>();
        public final Map<Integer, Set<String>> rejected = new HashMap<>();
        public final Map<String, Map<IntentModel.Feedback, Integer>> feedback = new HashMap<>();
        public final Map<String, Integer> recentRecipes = new HashMap<>();
        public IntentModel intent = new IntentModel(Collections.emptyList(), 0);
        public final Map<String, Double> pantryGrams = new HashMap<>();
        public final Map<String, Double> readyServings = new HashMap<>();
        public java.util.function.BooleanSupplier cancelled = () -> false;
        public double costWeight = 1, effortWeight = 1;
    }

    public static final class Result {
        public final List<Choice> choices;
        public final double[] totals;
        public final double cost;
        public final boolean pricesEntered, budgetMet, targetClose;
        public final List<String> notes;

        Result(
                List<Choice> choices,
                double[] totals,
                double cost,
                boolean entered,
                boolean budget,
                boolean close,
                List<String> notes) {
            this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
            this.totals = totals.clone();
            this.cost = cost;
            this.pricesEntered = entered;
            this.budgetMet = budget;
            this.targetClose = close;
            this.notes = Collections.unmodifiableList(notes);
        }
    }

    private static final class State {
        final List<Choice> choices = new ArrayList<>();
        final double[] totals = new double[8];
        final Map<String, Double> usedReady = new HashMap<>(), usedPantry = new HashMap<>();
        double cost, preference, effort, repeat, objective;
        long order;
        boolean vegetable, fruit;
        boolean entered = true;

        State copy() {
            State s = new State();
            s.usedReady.putAll(usedReady);
            s.usedPantry.putAll(usedPantry);
            s.choices.addAll(choices);
            System.arraycopy(totals, 0, s.totals, 0, 8);
            s.vegetable = vegetable;
            s.fruit = fruit;
            s.cost = cost;
            s.preference = preference;
            s.effort = effort;
            s.repeat = repeat;
            s.entered = entered;
            return s;
        }
    }

    public Result plan(List<MealRecipe> recipes, Request r) {
        validate(r);
        if (recipes.size() > 200) throw new IllegalArgumentException("Too many planning recipes");
        List<String> notes = new ArrayList<>();
        double energy = r.targets[1] * 4 + r.targets[2] * 4 + r.targets[3] * 9;
        if (Math.abs(energy - r.targets[0]) > Math.max(100, r.targets[0] * 0.15))
            notes.add(
                    "Your saved calorie and macro targets conflict. Targets are unchanged; review"
                        + " them in Profile.");
        State initial = new State();
        System.arraycopy(r.consumed, 0, initial.totals, 0, 8);
        initial.cost = r.consumedCost;
        initial.entered = r.consumedCostKnown;
        List<State> beam = new ArrayList<>();
        beam.add(initial);
        double[] shares = shares(r);
        double processed = 0;
        for (int meal = 0; meal < 4; meal++) {
            if (r.cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (!r.remaining[meal] || !r.profile.meals[meal]) continue;
            List<Choice> options = new ArrayList<>();
            Choice lock = r.locked.get(meal);
            if (lock != null) {
                if (!lock.recipe.allowed(
                        r.profile,
                        meal,
                        r.readyServings.getOrDefault(lock.recipe.id, 0.0) >= lock.scale))
                    return unavailable(
                            initial,
                            notes,
                            "A kept meal conflicts with your current preferences or restrictions."
                                + " Unlock or edit it.");
                options.add(lock);
            } else {
                for (MealRecipe recipe : recipes) {
                    if (options.size() >= MAX_OPTIONS) break;
                    if (!recipe.allowed(r.profile, meal, true)
                            || r.rejected
                                    .getOrDefault(meal, Collections.emptySet())
                                    .contains(recipe.id)) continue;
                    double max = r.targets[0] > 2600 ? 2 : 1.5;
                    for (double scale : new double[] {0.5, 1, 1.5, 2})
                        if (scale <= max
                                && recipe.allowed(
                                        r.profile,
                                        meal,
                                        r.readyServings.getOrDefault(recipe.id, 0.0) >= scale))
                            options.add(new Choice(meal, recipe, scale));
                }
            }
            if (options.isEmpty())
                return unavailable(
                        initial,
                        notes,
                        "No suitable "
                                + mealName(meal)
                                + " option matches the selected foods, restrictions and cooking"
                                + " access. Change an explicit preference or add a suitable"
                                + " recipe.");
            processed += shares[meal];
            final double progress = processed;
            Comparator<State> objective =
                    Comparator.comparingDouble((State s) -> s.objective)
                            .thenComparingLong(s -> s.order);
            Comparator<State> cheapest =
                    Comparator.comparingDouble((State s) -> s.cost).thenComparingLong(s -> s.order);
            PriorityQueue<State> leaders = new PriorityQueue<>(objective.reversed()),
                    cheap = new PriorityQueue<>(cheapest.reversed());
            Map<Choice, Evaluation> evaluated = new IdentityHashMap<>();
            for (Choice c : options) evaluated.put(c, new Evaluation(c, r));
            long order = 0;
            for (State state : beam)
                for (Choice option : options) {
                    if (order % 256 == 0 && r.cancelled.getAsBoolean())
                        throw new java.util.concurrent.CancellationException();
                    State s = state.copy();
                    if (!add(s, option, r, evaluated.get(option))) continue;
                    s.order = order++;
                    s.objective = score(s, r, progress);
                    retain(leaders, s, BEAM_WIDTH * 3 / 4, objective);
                    retain(cheap, s, BEAM_WIDTH, cheapest);
                }
            if (leaders.isEmpty())
                return unavailable(
                        initial,
                        notes,
                        "Prepared portions are insufficient for the selected meals. Add cooking"
                            + " access or change the plan.");
            List<State> keep = new ArrayList<>(leaders);
            keep.sort(objective);
            List<State> economical = new ArrayList<>(cheap);
            economical.sort(cheapest);
            Set<State> seen = new HashSet<>(keep);
            for (State state : economical) {
                if (keep.size() >= BEAM_WIDTH) break;
                if (seen.add(state)) keep.add(state);
            }

            beam = keep;
        }
        final double budget = r.profile.effectiveDailyBudget();
        List<State> within = new ArrayList<>();
        for (State s : beam) if (budget <= 0 || s.cost <= budget + 0.01) within.add(s);
        List<State> eligible = within.isEmpty() ? beam : within;
        eligible.sort(Comparator.comparingDouble(s -> score(s, r, 1)));
        State best = eligible.get(0);
        boolean met = budget <= 0 || best.cost <= budget + 0.01;
        boolean close =
                Math.abs(best.totals[0] - r.targets[0]) <= Math.max(100, r.targets[0] * 0.1)
                        && Math.abs(best.totals[1] - r.targets[1])
                                <= Math.max(10, r.targets[1] * 0.15);
        if (!met)
            notes.add(
                    "No budget-feasible combination was found within the available meals and search"
                        + " limits. This estimate exceeds your daily allocation.");
        if (!best.entered)
            notes.add(
                    "Cost uses illustrative prices or unknown logged-food cost. Enter local prices;"
                        + " this is not a guaranteed spending limit.");
        if (!close)
            notes.add(
                    "Closest available plan: calories or protein remain outside the target range."
                        + " No targets or restrictions were changed.");
        if (Double.isNaN(best.totals[4])
                || Double.isNaN(best.totals[5])
                || Double.isNaN(best.totals[6]))
            notes.add(
                    "Some fibre, sugar or sodium values are unknown. Missing data is not zero and"
                        + " those limits cannot be verified.");
        if (r.consumed[0] > r.targets[0])
            notes.add(
                    "Logged calories already exceed your target. Remaining meals are still offered;"
                        + " there is no compensatory meal-skipping instruction.");
        return new Result(best.choices, best.totals, best.cost, best.entered, met, close, notes);
    }

    private static Result unavailable(State s, List<String> notes, String reason) {
        notes.add(reason);
        return new Result(
                Collections.emptyList(), s.totals, s.cost, s.entered, false, false, notes);
    }

    private static double[] shares(Request r) {
        double[] shares =
                r.profile.largestMeal < 0 && r.profile.learnFromHistory
                        ? r.intent.routineShares()
                        : new double[] {0.25, 0.30, 0.15, 0.30};
        if (r.profile.largestMeal >= 0) shares[r.profile.largestMeal] += 0.15;
        double sum = 0;
        for (int i = 0; i < 4; i++)
            if (r.remaining[i] && r.profile.meals[i]) sum += shares[i];
            else shares[i] = 0;
        if (sum > 0) for (int i = 0; i < 4; i++) shares[i] /= sum;
        return shares;
    }

    private static void retain(
            PriorityQueue<State> queue, State state, int size, Comparator<State> ordering) {
        if (queue.size() < size) queue.add(state);
        else if (ordering.compare(state, queue.peek()) < 0) {
            queue.remove();
            queue.add(state);
        }
    }

    private static final class Evaluation {
        final double[] nutrition;
        final PriceModel.Cost cost;
        final double preference;
        final boolean vegetable, fruit;

        Evaluation(Choice c, Request r) {
            nutrition = c.recipe.nutrition(c.scale);
            cost = PriceModel.consumed(c.recipe, c.scale, r.profile);
            double taste = r.intent.score(c.recipe.id, c.meal, r.profile), ingredients = 0;
            for (MealRecipe.Ingredient i : c.recipe.ingredients)
                ingredients += r.intent.score(i.food.id, c.meal, r.profile);
            taste += ingredients / c.recipe.ingredients.size();
            if (!"Any".equals(r.profile.cuisine) && r.profile.cuisine.equals(c.recipe.cuisine))
                taste += 0.5;
            preference =
                    taste
                            - IntentModel.feedbackPenalty(
                                    r.feedback.getOrDefault(c.recipe.id, Collections.emptyMap()),
                                    c.recipe.prepMinutes,
                                    c.scale,
                                    cost.amount,
                                    r.profile.learnFromHistory)
                            - IntentModel.feedbackPenalty(
                                    r.feedback.getOrDefault(
                                            c.recipe.id + "|" + c.meal, Collections.emptyMap()),
                                    c.recipe.prepMinutes,
                                    c.scale,
                                    cost.amount,
                                    r.profile.learnFromHistory);
            Set<String> tags = c.recipe.tags();
            vegetable = tags.contains("vegetable");
            fruit = tags.contains("fruit");
        }
    }

    private static boolean add(State s, Choice c, Request r, Evaluation evaluated) {
        double prepared =
                Math.min(
                        c.scale,
                        Math.max(
                                0,
                                r.readyServings.getOrDefault(c.recipe.id, 0.0)
                                        - s.usedReady.getOrDefault(c.recipe.id, 0.0)));
        if (prepared < c.scale
                && (c.recipe.prepMinutes > r.profile.maxPrepMinutes
                        || (c.recipe.requiresCooking && !r.profile.canCook))) return false;
        c = new Choice(c.meal, c.recipe, c.scale, prepared);
        s.usedReady.merge(c.recipe.id, prepared, Double::sum);
        for (int i = 0; i < 8; i++) s.totals[i] += evaluated.nutrition[i];
        s.cost += evaluated.cost.amount;
        s.entered &= evaluated.cost.allPricesEntered;
        s.preference += evaluated.preference;
        s.vegetable |= evaluated.vegetable;
        s.fruit |= evaluated.fruit;
        s.effort += prepared >= c.scale ? 0 : c.recipe.prepMinutes;
        double coverage = 0;
        for (MealRecipe.Ingredient i : c.recipe.ingredients) {
            double needed = i.grams * (c.scale - prepared),
                    available =
                            Math.max(
                                    0,
                                    r.pantryGrams.getOrDefault(i.food.id, 0.0)
                                            - s.usedPantry.getOrDefault(i.food.id, 0.0));
            if (needed > 0) coverage += Math.min(1, available / needed);
            s.usedPantry.merge(i.food.id, needed, Double::sum);
        }
        s.preference += 0.35 * coverage / c.recipe.ingredients.size();
        s.repeat += r.recentRecipes.getOrDefault(c.recipe.id, 0) * 0.6;
        for (Choice prior : s.choices) if (prior.recipe.id.equals(c.recipe.id)) s.repeat += 3;
        s.choices.add(c);
        return true;
    }

    private static double score(State s, Request r, double progress) {
        double value = 0;
        double[] weights = {16, 9, 3, 3};
        for (int i = 0; i < 4; i++) {
            double goal = r.consumed[i] + Math.max(0, r.targets[i] - r.consumed[i]) * progress;
            double error = (s.totals[i] - goal) / Math.max(i == 0 ? 300 : 20, r.targets[i]);
            value += weights[i] * error * error;
        }
        if (Double.isFinite(s.totals[4]) && r.targets[4] > 0)
            value += Math.pow(Math.max(0, r.targets[4] * progress - s.totals[4]) / r.targets[4], 2);
        for (int i = 5; i < 8; i++)
            if (Double.isFinite(s.totals[i]) && r.targets[i] > 0)
                value += Math.pow(Math.max(0, s.totals[i] - r.targets[i]) / r.targets[i], 2) * 3;
        double budget = r.profile.effectiveDailyBudget();
        if (budget > 0) value += 12 * Math.pow(Math.max(0, s.cost - budget * progress) / budget, 2);
        value +=
                r.costWeight * s.cost / 250
                        + r.effortWeight * s.effort / 120
                        + s.repeat * 0.25
                        - s.preference * 0.15;
        if (progress >= 0.99) {
            if (!s.vegetable) value += 1;
            if (!s.fruit) value += 0.4;
        }
        return value;
    }

    private static void validate(Request r) {
        r.profile.validate();
        for (Map<String, Double> quantities : Arrays.asList(r.pantryGrams, r.readyServings))
            for (double quantity : quantities.values())
                if (!Double.isFinite(quantity) || quantity < 0 || quantity > 100000)
                    throw new IllegalArgumentException("Invalid pantry or prepared portions");
        if (r.targets.length != 8 || r.consumed.length != 8 || r.remaining.length != 4)
            throw new IllegalArgumentException("Invalid planner inputs");
        for (int i = 0; i < 4; i++)
            if (!Double.isFinite(r.targets[i])
                    || r.targets[i] < 0
                    || !Double.isFinite(r.consumed[i])
                    || r.consumed[i] < 0)
                throw new IllegalArgumentException(
                        "Invalid saved nutrition targets or diary totals");
        if (r.targets[0] < 300 || r.targets[0] > 10000)
            throw new IllegalArgumentException("Review your calorie target before planning");
        if (!Double.isFinite(r.consumedCost) || r.consumedCost < 0)
            throw new IllegalArgumentException("Invalid consumed cost");
    }

    public static String mealName(int meal) {
        return new String[] {"Breakfast", "Lunch", "Snacks", "Dinner"}[meal];
    }
}
