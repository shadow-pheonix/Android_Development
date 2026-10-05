package com.shadowphoenix.dietlog.nutrition;

public enum Micronutrient {
    CALCIUM("calcium_mg", "Calcium", "mg", Group.MINERAL),
    PHOSPHORUS("phosphorus_mg", "Phosphorus", "mg", Group.MINERAL),
    MAGNESIUM("magnesium_mg", "Magnesium", "mg", Group.MINERAL),
    SODIUM("sodium_mg", "Sodium", "mg", Group.MINERAL),
    POTASSIUM("potassium_mg", "Potassium", "mg", Group.MINERAL),
    IRON("iron_mg", "Iron", "mg", Group.MINERAL),
    COPPER("copper_mg", "Copper", "mg", Group.MINERAL),
    SELENIUM("selenium_ug", "Selenium", "µg", Group.MINERAL),
    CHROMIUM("chromium_ug", "Chromium", "µg", Group.MINERAL),
    MANGANESE("manganese_mg", "Manganese", "mg", Group.MINERAL),
    ZINC("zinc_mg", "Zinc", "mg", Group.MINERAL),

    VITAMIN_A("vitamin_a_ug", "Vitamin A", "µg", Group.VITAMIN),
    VITAMIN_E("vitamin_e_mg", "Vitamin E", "mg", Group.VITAMIN),
    VITAMIN_D("vitamin_d_ug", "Vitamin D", "µg", Group.VITAMIN),
    VITAMIN_K("vitamin_k_ug", "Vitamin K", "µg", Group.VITAMIN),
    THIAMINE("thiamine_mg", "Vitamin B1", "mg", Group.VITAMIN),
    RIBOFLAVIN("riboflavin_mg", "Vitamin B2", "mg", Group.VITAMIN),
    NIACIN("niacin_mg", "Vitamin B3", "mg", Group.VITAMIN),
    PANTOTHENIC_ACID("pantothenic_acid_mg", "Vitamin B5", "mg", Group.VITAMIN),
    VITAMIN_B6("vitamin_b6_mg", "Vitamin B6", "mg", Group.VITAMIN),
    BIOTIN("biotin_ug", "Vitamin B7", "µg", Group.VITAMIN),
    FOLATE("folate_ug", "Folate", "µg", Group.VITAMIN),
    VITAMIN_C("vitamin_c_mg", "Vitamin C", "mg", Group.VITAMIN);

    public enum Group { MINERAL, VITAMIN }

    public final String key;
    public final String label;
    public final String unit;
    public final Group group;

    Micronutrient(String key, String label, String unit, Group group) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.group = group;
    }

    public static Micronutrient fromKey(String key) {
        if (key == null) return null;
        for (Micronutrient nutrient : values()) {
            if (nutrient.key.equals(key)) return nutrient;
        }
        return null;
    }
}
