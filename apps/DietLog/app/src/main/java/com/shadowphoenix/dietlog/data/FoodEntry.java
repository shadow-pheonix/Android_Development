package com.shadowphoenix.dietlog.data;

import java.util.Locale;

public final class FoodEntry {
    public static final String MEAL_BREAKFAST = "breakfast";
    public static final String MEAL_LUNCH = "lunch";
    public static final String MEAL_SNACKS = "snacks";
    public static final String MEAL_DINNER = "dinner";

    public final long id;
    public final String name;
    public final double grams, calories, protein, carbs, fat;
    public final long loggedAt;
    public final String mealType, source, sourceFoodId, sourceServingId;

    public FoodEntry(long id, String name, double grams, double calories, double protein,
                     double carbs, double fat, long loggedAt, String mealType, String source,
                     String sourceFoodId, String sourceServingId) {
        this.id = id;
        this.name = name;
        this.grams = grams;
        this.calories = calories;
        this.protein = protein;
        this.carbs = carbs;
        this.fat = fat;
        this.loggedAt = loggedAt;
        this.mealType = normalizeMealType(mealType);
        this.source = source;
        this.sourceFoodId = sourceFoodId;
        this.sourceServingId = sourceServingId;
    }

    public FoodEntry(long id, String name, double grams, double calories, double protein,
                     double carbs, double fat, long loggedAt, String source,
                     String sourceFoodId, String sourceServingId) {
        this(id, name, grams, calories, protein, carbs, fat, loggedAt, MEAL_SNACKS, source, sourceFoodId, sourceServingId);
    }

    public static FoodEntry fromPer100g(String name, double grams, double caloriesPer100g,
                                        double proteinPer100g, double carbsPer100g, double fatPer100g,
                                        long loggedAt, String mealType, String source, String sourceFoodId) {
        double factor = grams / 100.0;
        return new FoodEntry(0, name.trim(), grams, caloriesPer100g * factor, proteinPer100g * factor,
                carbsPer100g * factor, fatPer100g * factor, loggedAt, mealType,
                source == null ? "manual" : source, sourceFoodId, null);
    }

    public static FoodEntry fromPer100g(String name, double grams, double caloriesPer100g,
                                        double proteinPer100g, double carbsPer100g, double fatPer100g,
                                        long loggedAt, String source, String sourceFoodId) {
        return fromPer100g(name, grams, caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g,
                loggedAt, MEAL_SNACKS, source, sourceFoodId);
    }

    public static FoodEntry fromPer100g(String name, double grams, double caloriesPer100g,
                                        double proteinPer100g, double carbsPer100g, double fatPer100g,
                                        long loggedAt, String mealType) {
        return fromPer100g(name, grams, caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g,
                loggedAt, mealType, "manual", null);
    }

    public static FoodEntry fromPer100g(String name, double grams, double caloriesPer100g,
                                        double proteinPer100g, double carbsPer100g, double fatPer100g, long loggedAt) {
        return fromPer100g(name, grams, caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g,
                loggedAt, MEAL_SNACKS, "manual", null);
    }

    public FoodEntry withAmount(double newGrams) {
        if (!Double.isFinite(newGrams) || newGrams <= 0.0) throw new IllegalArgumentException("Amount must be greater than zero");
        if (grams <= 0.0) throw new IllegalStateException("Existing amount must be greater than zero");
        double factor = newGrams / grams;
        return new FoodEntry(id, name, newGrams, calories * factor, protein * factor,
                carbs * factor, fat * factor, loggedAt, mealType, source, sourceFoodId, sourceServingId);
    }

    public FoodEntry withMealType(String newMealType) {
        return new FoodEntry(id, name, grams, calories, protein, carbs, fat, loggedAt,
                newMealType, source, sourceFoodId, sourceServingId);
    }

    public FoodEntry copyForLog(long newLoggedAt, String newMealType) {
        return new FoodEntry(0, name, grams, calories, protein, carbs, fat, newLoggedAt,
                newMealType, source, sourceFoodId, sourceServingId);
    }

    public String foodKey() {
        return foodKey(source, sourceFoodId, name);
    }

    public static String foodKey(String source, String sourceFoodId, String name) {
        String normalizedSource = source == null || source.trim().isEmpty()
                ? "manual" : source.trim().toLowerCase(Locale.ROOT);
        if (sourceFoodId != null && !sourceFoodId.trim().isEmpty()) {
            return normalizedSource + "|id|" + sourceFoodId.trim().toLowerCase(Locale.ROOT);
        }
        String normalizedName = name == null ? "" :
                name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalizedSource + "|name|" + normalizedName;
    }

    public static String normalizeMealType(String mealType) {
        if (mealType == null) return MEAL_SNACKS;
        switch (mealType.trim().toLowerCase(Locale.ROOT)) {
            case MEAL_BREAKFAST: return MEAL_BREAKFAST;
            case MEAL_LUNCH: return MEAL_LUNCH;
            case MEAL_DINNER: return MEAL_DINNER;
            case MEAL_SNACKS:
            default: return MEAL_SNACKS;
        }
    }

    public static String mealForHour(int hour) {
        int normalizedHour = Math.floorMod(hour, 24);
        if (normalizedHour < 11) return MEAL_BREAKFAST;
        if (normalizedHour < 15) return MEAL_LUNCH;
        if (normalizedHour < 18) return MEAL_SNACKS;
        return MEAL_DINNER;
    }

    public static String mealDisplayName(String mealType) {
        switch (normalizeMealType(mealType)) {
            case MEAL_BREAKFAST: return "Breakfast";
            case MEAL_LUNCH: return "Lunch";
            case MEAL_DINNER: return "Dinner";
            default: return "Snacks";
        }
    }
}
