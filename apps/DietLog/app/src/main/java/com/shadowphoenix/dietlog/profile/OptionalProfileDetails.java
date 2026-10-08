package com.shadowphoenix.dietlog.profile;

/** Shared validation for optional measured inputs. Zero retains the standard estimate. */
public final class OptionalProfileDetails {
    private OptionalProfileDetails() {}

    public static void validate(ProfileSettings s) {
        optionalRange(s.bodyFatPercent, 3, 65, "Body fat", "%");
        optionalRange(s.measuredRmrKcal, 500, 6000, "Measured resting calories", "kcal/day");
        optionalRange(s.observedMaintenanceKcal, 1000, 10000, "Observed maintenance", "kcal/day");
        if (s.trainingFocus == null) throw new IllegalArgumentException("Choose a training focus or leave it unspecified");
    }

    private static void optionalRange(double value, int low, int high, String label, String unit) {
        if (!Double.isFinite(value) || (value != 0 && (value < low || value > high))) {
            throw new IllegalArgumentException(label + " must be " + low + "–" + high + " " + unit
                    + ", or leave it blank");
        }
    }
}
