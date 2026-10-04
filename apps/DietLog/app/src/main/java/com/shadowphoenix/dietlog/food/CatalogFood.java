package com.shadowphoenix.dietlog.food;

public final class CatalogFood {
    public final String code, barcode, name, brand, quantity, category, source;
    public final Double calories, protein, carbs, fat, sugars, fiber, saturatedFat, salt, sodium, servingG;
    public final String servingSize, ingredients, allergens, labels, nutriscore, productUrl, extraJson;
    public final Integer nova;

    public CatalogFood(String code, String barcode, String name, String brand, String quantity,
                       String category, String source, Double calories, Double protein, Double carbs,
                       Double fat, Double sugars, Double fiber, Double saturatedFat, Double salt,
                       Double sodium, String servingSize, Double servingG, String ingredients,
                       String allergens, String labels, String nutriscore, Integer nova,
                       String productUrl, String extraJson) {
        this.code = code; this.barcode = barcode; this.name = name; this.brand = brand;
        this.quantity = quantity; this.category = category; this.source = source;
        this.calories = calories; this.protein = protein; this.carbs = carbs; this.fat = fat;
        this.sugars = sugars; this.fiber = fiber; this.saturatedFat = saturatedFat;
        this.salt = salt; this.sodium = sodium; this.servingSize = servingSize;
        this.servingG = servingG; this.ingredients = ingredients; this.allergens = allergens;
        this.labels = labels; this.nutriscore = nutriscore; this.nova = nova;
        this.productUrl = productUrl; this.extraJson = extraJson;
    }
}
