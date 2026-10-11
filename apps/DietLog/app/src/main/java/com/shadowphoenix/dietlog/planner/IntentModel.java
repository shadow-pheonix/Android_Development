package com.shadowphoenix.dietlog.planner;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded evidence: distinct days, recency, meal context, and separate feedback reasons. */
public final class IntentModel {
    public static final class Observation {
        public final String foodId;
        public final int meal;
        public final long day;
        public final double calories;

        public Observation(String foodId, int meal, long day) {
            this(foodId, meal, day, 0);
        }

        public Observation(String foodId, int meal, long day, double calories) {
            this.foodId = foodId;
            this.meal = meal;
            this.day = day;
            this.calories = calories;
        }
    }

    public enum Feedback {
        LIKED,
        DISLIKED,
        EXPENSIVE,
        TOO_MUCH,
        TOO_SLOW,
        UNSUITABLE,
        SELECTED,
        SWAPPED
    }

    public static final class Evidence {
        public double familiarity;
        public int days;
    }

    private final Map<String, Evidence> evidence = new HashMap<>();
    private final double[] routine = {0.25, 0.30, 0.15, 0.30};
    private int routineDays;

    public IntentModel(List<Observation> observations, long today) {
        Set<String> seen = new HashSet<>();
        Map<Long, double[]> daily = new java.util.TreeMap<>();
        List<Observation> ordered = new java.util.ArrayList<>(observations);
        ordered.sort(
                java.util.Comparator.comparingLong((Observation o) -> o.day)
                        .thenComparingInt(o -> o.meal)
                        .thenComparing(
                                o -> o.foodId,
                                java.util.Comparator.nullsFirst(
                                        java.util.Comparator.naturalOrder()))
                        .thenComparingDouble(o -> o.calories));
        for (Observation o : ordered) {
            long age = today - o.day;
            if (o.foodId == null
                    || o.foodId.isEmpty()
                    || o.meal < 0
                    || o.meal > 3
                    || age < 0
                    || age > 42) continue;
            if (Double.isFinite(o.calories) && o.calories > 0) {
                double[] meals = daily.computeIfAbsent(o.day, k -> new double[4]);
                meals[o.meal] = Math.min(3000, meals[o.meal] + Math.min(1500, o.calories));
            }
            if (!seen.add(o.foodId + "|" + o.meal + "|" + o.day)) continue;
            Evidence e = evidence.computeIfAbsent(o.foodId + "|" + o.meal, k -> new Evidence());
            e.familiarity += Math.pow(0.5, age / 14.0);
            e.days++;
        }
        double weight = 0;
        double[] learned = new double[4];
        for (Map.Entry<Long, double[]> day : daily.entrySet()) {
            double sum = 0;
            int slots = 0;
            for (double amount : day.getValue()) {
                sum += amount;
                if (amount > 0) slots++;
            }
            if (slots < 3 || sum < 600 || sum > 8000) continue;
            routineDays++;
            double w = Math.pow(0.5, (today - day.getKey()) / 14.0);
            weight += w;
            for (int m = 0; m < 4; m++) learned[m] += w * day.getValue()[m] / sum;
        }
        if (routineDays >= 4) {
            double sum = 0;
            for (int m = 0; m < 4; m++) {
                double blend = 0.6 * routine[m] + 0.4 * learned[m] / weight;
                routine[m] = Math.max(routine[m] - 0.08, Math.min(routine[m] + 0.08, blend));
                sum += routine[m];
            }
            for (int m = 0; m < 4; m++) routine[m] /= sum;
        }
    }

    public boolean hasLearnedRoutine() {
        return routineDays >= 4;
    }

    public double[] routineShares() {
        return routine.clone();
    }

    public String identity() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, Evidence> e : new java.util.TreeMap<>(evidence).entrySet())
                digest.update(
                        (e.getKey()
                                        + ":"
                                        + e.getValue().days
                                        + ":"
                                        + Double.toString(e.getValue().familiarity)
                                        + ";")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(
                    (routineDays + java.util.Arrays.toString(routine))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : digest.digest())
                value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public double score(String foodId, int meal, PlannerProfile profile) {
        FoodPreference preference = profile.rating(foodId);
        if (preference.excludes()) return Double.NEGATIVE_INFINITY;
        // An explicit choice cannot be outweighed by frequent logging.
        if (preference != FoodPreference.UNSET) return preference.score;
        Evidence e = evidence.get(foodId + "|" + meal);
        return profile.learnFromHistory && e != null
                ? Math.min(1.5, Math.log1p(e.familiarity) * 0.65)
                : 0;
    }

    public int distinctDays(String foodId, int meal) {
        Evidence e = evidence.get(foodId + "|" + meal);
        return e == null ? 0 : e.days;
    }

    public static double feedbackPenalty(
            Map<Feedback, Integer> counts, int prepMinutes, double portionScale, double cost) {
        return feedbackPenalty(counts, prepMinutes, portionScale, cost, true);
    }

    public static double feedbackPenalty(
            Map<Feedback, Integer> counts,
            int prepMinutes,
            double portionScale,
            double cost,
            boolean learnSelections) {
        // Keep/swap evidence is deliberately weaker than an explicit taste rating or feedback
        // reason.
        return Math.min(5, counts.getOrDefault(Feedback.DISLIKED, 0) * 1.0)
                + Math.min(2, counts.getOrDefault(Feedback.EXPENSIVE, 0) * Math.min(1, cost / 100))
                + Math.min(2, counts.getOrDefault(Feedback.TOO_SLOW, 0) * prepMinutes / 45.0)
                + Math.min(
                        2,
                        counts.getOrDefault(Feedback.TOO_MUCH, 0) * Math.max(0, portionScale - 0.5))
                - Math.min(2, counts.getOrDefault(Feedback.LIKED, 0) * 0.5)
                + (learnSelections
                        ? Math.min(1, counts.getOrDefault(Feedback.SWAPPED, 0) * 0.15)
                                - Math.min(1, counts.getOrDefault(Feedback.SELECTED, 0) * 0.25)
                        : 0);
    }
}
