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
        public Observation(String foodId, int meal, long day) { this.foodId = foodId; this.meal = meal; this.day = day; }
    }
    public enum Feedback { LIKED, DISLIKED, EXPENSIVE, TOO_MUCH, TOO_SLOW, UNSUITABLE }
    public static final class Evidence {
        public double familiarity;
        public int days;
    }
    private final Map<String, Evidence> evidence = new HashMap<>();
    public IntentModel(List<Observation> observations, long today) {
        Set<String> seen = new HashSet<>();
        for (Observation o : observations) {
            long age = today - o.day;
            if (o.foodId == null || o.foodId.isEmpty() || o.meal < 0 || o.meal > 3 || age < 0 || age > 42) continue;
            if (!seen.add(o.foodId + "|" + o.meal + "|" + o.day)) continue;
            Evidence e = evidence.computeIfAbsent(o.foodId + "|" + o.meal, k -> new Evidence());
            e.familiarity += Math.pow(0.5, age / 14.0); e.days++;
        }
    }
    public double score(String foodId, int meal, PlannerProfile profile) {
        FoodPreference preference = profile.rating(foodId);
        if (preference.excludes()) return Double.NEGATIVE_INFINITY;
        // An explicit choice cannot be outweighed by frequent logging.
        if (preference != FoodPreference.UNSET) return preference.score;
        Evidence e = evidence.get(foodId + "|" + meal);
        return profile.learnFromHistory && e != null ? Math.min(1.5, Math.log1p(e.familiarity) * 0.65) : 0;
    }
    public int distinctDays(String foodId, int meal) {
        Evidence e = evidence.get(foodId + "|" + meal); return e == null ? 0 : e.days;
    }
    public static double feedbackPenalty(Map<Feedback, Integer> counts, int prepMinutes, double portionScale, double cost) {
        // Budget and effort feedback never rewrite a taste rating.
        return Math.min(5, counts.getOrDefault(Feedback.DISLIKED, 0) * 1.0)
                + Math.min(2, counts.getOrDefault(Feedback.EXPENSIVE, 0) * Math.min(1, cost / 100))
                + Math.min(2, counts.getOrDefault(Feedback.TOO_SLOW, 0) * prepMinutes / 45.0)
                + Math.min(2, counts.getOrDefault(Feedback.TOO_MUCH, 0) * Math.max(0, portionScale - 0.5))
                - Math.min(2, counts.getOrDefault(Feedback.LIKED, 0) * 0.5);
    }
}
