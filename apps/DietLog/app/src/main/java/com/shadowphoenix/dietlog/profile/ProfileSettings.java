package com.shadowphoenix.dietlog.profile;

import java.time.LocalDate;
import java.time.Period;

public final class ProfileSettings {
    public enum Sex {
        MALE("Male"), FEMALE("Female");
        public final String label;
        Sex(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum ActivityLevel {
        SEDENTARY("Sedentary", "Mostly seated, little structured exercise", 1.20),
        LIGHT("Lightly active", "Light exercise 1–3 days/week", 1.375),
        MODERATE("Moderately active", "Moderate exercise 3–5 days/week", 1.55),
        VERY_ACTIVE("Very active", "Hard exercise 6–7 days/week", 1.725),
        EXTREME("Extremely active", "Very hard training or physical job", 1.90);

        public final String label;
        public final String description;
        public final double multiplier;
        ActivityLevel(String label, String description, double multiplier) {
            this.label = label;
            this.description = description;
            this.multiplier = multiplier;
        }
        @Override public String toString() { return label; }
    }

    public enum Goal {
        LOSE_WEIGHT("Lose weight", -1, 0.005, 0.20),
        MAINTAIN("Maintain weight", 0, 0.0, 0.0),
        GAIN_WEIGHT("Gain weight", 1, 0.0035, 0.15),
        LEAN_BULK("Lean bulk", 1, 0.0025, 0.10),
        DIRTY_BULK("Dirty bulk", 1, 0.0075, 0.25),
        CUT("Cut", -1, 0.0070, 0.25),
        CUSTOM("Custom", 0, 0.0, 0.30);

        public final String label;
        public final int direction;
        public final double defaultBodyWeightFractionPerWeek;
        public final double maxTdeeAdjustmentFraction;
        Goal(String label, int direction, double weeklyFraction, double maxAdjustment) {
            this.label = label;
            this.direction = direction;
            this.defaultBodyWeightFractionPerWeek = weeklyFraction;
            this.maxTdeeAdjustmentFraction = maxAdjustment;
        }
        @Override public String toString() { return label; }
    }

    public enum MacroMode {
        GRAMS("Set grams manually"), PERCENT("Set calorie percentages");
        public final String label;
        MacroMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public String displayName = "";
    public String avatarUri = "";
    public String dateOfBirthIso = "";
    public int ageYears = 25;
    public Sex sex = Sex.MALE;
    public double heightCm = 170.0;
    public double currentWeightKg = 70.0;
    public double targetWeightKg = 70.0;
    public ActivityLevel activityLevel = ActivityLevel.MODERATE;
    public Goal goal = Goal.MAINTAIN;
    public double weeklyWeightChangeKg = 0.0;
    public boolean manualCalorieOverride = false;
    public double manualCalorieTarget = 2000.0;
    public MacroMode macroMode = MacroMode.GRAMS;
    public double proteinGoalG = 150.0;
    public double carbsGoalG = 250.0;
    public double fatGoalG = 70.0;
    public double proteinPercent = 30.0;
    public double carbsPercent = 40.0;
    public double fatPercent = 30.0;
    public double fibreGoalG = 30.0;
    public double sugarLimitG = 50.0;
    public double sodiumLimitMg = 2000.0;

    public int effectiveAge(LocalDate today) {
        if (dateOfBirthIso != null && !dateOfBirthIso.trim().isEmpty()) {
            try {
                LocalDate birth = LocalDate.parse(dateOfBirthIso);
                if (!birth.isAfter(today)) return Period.between(birth, today).getYears();
            } catch (RuntimeException ignored) {
                // Fall back to manually entered age.
            }
        }
        return ageYears;
    }
}
