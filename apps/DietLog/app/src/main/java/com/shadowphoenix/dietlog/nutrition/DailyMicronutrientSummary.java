package com.shadowphoenix.dietlog.nutrition;

import java.util.EnumMap;

public final class DailyMicronutrientSummary {
    private final EnumMap<Micronutrient, Double> totals = new EnumMap<>(Micronutrient.class);
    private final EnumMap<Micronutrient, Integer> knownFoods = new EnumMap<>(Micronutrient.class);
    private int totalFoods;

    public void addFood(MicronutrientValues values) {
        totalFoods++;
        if (values == null) return;
        for (Micronutrient nutrient : Micronutrient.values()) {
            Double value = values.get(nutrient);
            if (value == null) continue;
            totals.put(nutrient, totals.getOrDefault(nutrient, 0.0) + value);
            knownFoods.put(nutrient, knownFoods.getOrDefault(nutrient, 0) + 1);
        }
    }

    public int totalFoods() { return totalFoods; }
    public int knownFoods(Micronutrient nutrient) { return knownFoods.getOrDefault(nutrient, 0); }
    public Double total(Micronutrient nutrient) { return totals.get(nutrient); }
    public boolean hasData(Micronutrient nutrient) { return knownFoods(nutrient) > 0; }
    public boolean hasFullCoverage(Micronutrient nutrient) {
        return totalFoods > 0 && knownFoods(nutrient) == totalFoods;
    }
}
