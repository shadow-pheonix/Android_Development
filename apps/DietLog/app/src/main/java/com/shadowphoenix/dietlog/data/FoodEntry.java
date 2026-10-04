package com.shadowphoenix.dietlog.data;

public final class FoodEntry {
    public final long id; public final String name; public final double grams, calories, protein, carbs, fat;
    public final long loggedAt; public final String source, sourceFoodId, sourceServingId;

    public FoodEntry(long id, String name, double grams, double calories, double protein,
                     double carbs, double fat, long loggedAt, String source,
                     String sourceFoodId, String sourceServingId) {
        this.id=id; this.name=name; this.grams=grams; this.calories=calories; this.protein=protein;
        this.carbs=carbs; this.fat=fat; this.loggedAt=loggedAt; this.source=source;
        this.sourceFoodId=sourceFoodId; this.sourceServingId=sourceServingId;
    }

    public static FoodEntry fromPer100g(String name, double grams, double caloriesPer100g,
                                        double proteinPer100g, double carbsPer100g, double fatPer100g,
                                        long loggedAt, String source, String sourceFoodId) {
        double factor=grams/100.0;
        return new FoodEntry(0,name.trim(),grams,caloriesPer100g*factor,proteinPer100g*factor,
                carbsPer100g*factor,fatPer100g*factor,loggedAt,source==null?"manual":source,sourceFoodId,null);
    }

    public static FoodEntry fromPer100g(String name, double grams, double caloriesPer100g,
                                        double proteinPer100g, double carbsPer100g, double fatPer100g, long loggedAt) {
        return fromPer100g(name, grams, caloriesPer100g, proteinPer100g, carbsPer100g, fatPer100g, loggedAt, "manual", null);
    }
}
