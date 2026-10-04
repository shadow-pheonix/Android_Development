package com.shadowphoenix.dietlog.food;

import java.util.List;

public interface FoodDataProvider {
    List<FoodSearchItem> search(String query, int limit) throws Exception;

    final class FoodSearchItem {
        public final String provider;
        public final String foodId;
        public final String servingId;
        public final String name;
        public final String brand;
        public final String servingDescription;
        public final double calories;
        public final double protein;
        public final double carbs;
        public final double fat;

        public FoodSearchItem(String provider, String foodId, String servingId, String name,
                              String brand, String servingDescription, double calories,
                              double protein, double carbs, double fat) {
            this.provider = provider;
            this.foodId = foodId;
            this.servingId = servingId;
            this.name = name;
            this.brand = brand;
            this.servingDescription = servingDescription;
            this.calories = calories;
            this.protein = protein;
            this.carbs = carbs;
            this.fat = fat;
        }
    }
}
