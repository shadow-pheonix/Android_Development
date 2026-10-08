package com.shadowphoenix.dietlog.profile;

import java.time.LocalDate;

/** Completion and validation shared by first-run setup and personal-profile saving. */
public final class ProfileSetup {
    private ProfileSetup() {}

    public static boolean needsSetup(Boolean personalComplete, boolean legacySaved,
                                     ProfileSettings settings, LocalDate today) {
        boolean marked = personalComplete == null ? legacySaved : personalComplete;
        if (!marked) return true;
        try {
            validatePersonal(settings, today);
            return false;
        } catch (IllegalArgumentException ignored) {
            return true;
        }
    }

    public static void validateBasics(ProfileSettings settings, LocalDate today) {
        if (settings.dateOfBirthIso != null && !settings.dateOfBirthIso.isEmpty()) {
            LocalDate birth;
            try { birth = LocalDate.parse(settings.dateOfBirthIso); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Choose a valid date of birth"); }
            if (birth.isAfter(today)) throw new IllegalArgumentException("Date of birth cannot be in the future");
        }
        int age = settings.effectiveAge(today);
        if (age < 13 || age > 100) throw new IllegalArgumentException("Age must be between 13 and 100 years");
        if (settings.sex == null) throw new IllegalArgumentException("Select sex for your nutrition estimate");
    }

    public static void validateMeasurements(ProfileSettings settings) {
        range(settings.heightCm, 120, 230, "Height", "cm");
        range(settings.currentWeightKg, 25, 350, "Current weight", "kg");
    }

    public static void validatePersonal(ProfileSettings settings, LocalDate today) {
        validateBasics(settings, today);
        validateMeasurements(settings);
        range(settings.targetWeightKg, 25, 350, "Target weight", "kg");
        if (settings.activityLevel == null) throw new IllegalArgumentException("Select your activity level");
        if (settings.goal == null) throw new IllegalArgumentException("Select your goal");
    }

    private static void range(double value, int min, int max, String label, String unit) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(label + " must be between " + min + " and " + max + " " + unit);
        }
    }
}
