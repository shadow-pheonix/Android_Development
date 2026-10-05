package com.shadowphoenix.dietlog.nutrition;

import com.shadowphoenix.dietlog.profile.CalorieCalculator;
import com.shadowphoenix.dietlog.profile.ProfileSettings;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;

public final class MicronutrientTargets {
    private static final String ICMR_2020 = "ICMR-NIN 2020";
    private static final String ICMR_AI = "ICMR-NIN adequate intake";

    private MicronutrientTargets() {}

    public static Map<Micronutrient, MicronutrientTarget> forProfile(ProfileSettings settings, LocalDate today) {
        EnumMap<Micronutrient, MicronutrientTarget> out = new EnumMap<>(Micronutrient.class);
        if (settings == null || settings.sex == null) return out;
        int age = settings.effectiveAge(today);
        if (age < 13 || age > 100) return out;

        boolean male = settings.sex == ProfileSettings.Sex.MALE;
        if (age <= 15) {
            if (male) {
                core(out, 1000, 345, 22, 14.3, 1.9, 2.7, 19, 2.6, 285, 70, 930, 15);
            } else {
                core(out, 1000, 340, 30, 12.8, 1.6, 2.2, 16, 2.2, 245, 65, 890, 15);
            }
            adolescentExtras(out);
            return out;
        }
        if (age <= 18) {
            if (male) {
                core(out, 1050, 440, 26, 17.6, 2.2, 3.1, 22, 3.0, 340, 85, 1000, 15);
            } else {
                core(out, 1050, 380, 32, 14.2, 1.7, 2.3, 17, 2.3, 270, 70, 860, 15);
            }
            adolescentExtras(out);
            return out;
        }

        if (age >= 60) {
            core(out,
                    1200,
                    male ? 440 : 370,
                    19,
                    male ? 17 : 13.2,
                    1.4,
                    male ? 2.0 : 1.9,
                    male ? 14 : 11,
                    1.9,
                    male ? 300 : 200,
                    male ? 80 : 65,
                    male ? 1000 : 840,
                    20);
        } else {
            double tdee = estimateTdee(settings, today);
            core(out,
                    1000,
                    male ? 440 : 370,
                    male ? 19 : 29,
                    male ? 17 : 13.2,
                    energyScaled(tdee, male ? 0.66 : 0.80),
                    energyScaled(tdee, male ? 0.92 : 1.14),
                    energyScaled(tdee, 6.65),
                    energyScaled(tdee, male ? 0.89 : 0.90),
                    male ? 300 : 220,
                    male ? 80 : 65,
                    male ? 1000 : 840,
                    15);
        }
        adultExtras(out);
        return out;
    }

    private static void core(Map<Micronutrient, MicronutrientTarget> out,
                             double calcium, double magnesium, double iron, double zinc,
                             double b1, double b2, double niacin, double b6, double folate,
                             double vitaminC, double vitaminA, double vitaminD) {
        target(out, Micronutrient.CALCIUM, calcium, false, ICMR_2020);
        target(out, Micronutrient.MAGNESIUM, magnesium, false, ICMR_2020);
        target(out, Micronutrient.IRON, iron, false, ICMR_2020);
        target(out, Micronutrient.ZINC, zinc, false, ICMR_2020);
        target(out, Micronutrient.THIAMINE, b1, false, ICMR_2020);
        target(out, Micronutrient.RIBOFLAVIN, b2, false, ICMR_2020);
        target(out, Micronutrient.NIACIN, niacin, false, ICMR_2020);
        target(out, Micronutrient.VITAMIN_B6, b6, false, ICMR_2020);
        target(out, Micronutrient.FOLATE, folate, false, ICMR_2020);
        target(out, Micronutrient.VITAMIN_C, vitaminC, false, ICMR_2020);
        target(out, Micronutrient.VITAMIN_A, vitaminA, false, ICMR_2020);
        target(out, Micronutrient.VITAMIN_D, vitaminD, false, ICMR_2020);
    }

    private static void adultExtras(Map<Micronutrient, MicronutrientTarget> out) {
        target(out, Micronutrient.PHOSPHORUS, 1000, false, ICMR_AI);
        target(out, Micronutrient.SODIUM, 2000, true, "ICMR-NIN upper daily intake goal");
        target(out, Micronutrient.POTASSIUM, 3500, false, ICMR_AI);
        target(out, Micronutrient.COPPER, 2.0, false, ICMR_AI);
        target(out, Micronutrient.SELENIUM, 40, false, ICMR_AI);
        target(out, Micronutrient.CHROMIUM, 50, false, ICMR_AI);
        target(out, Micronutrient.MANGANESE, 4.0, false, ICMR_AI);
        target(out, Micronutrient.VITAMIN_E, 10, false, ICMR_AI);
        target(out, Micronutrient.VITAMIN_K, 55, false, ICMR_AI);
        target(out, Micronutrient.PANTOTHENIC_ACID, 5, false, ICMR_AI);
        target(out, Micronutrient.BIOTIN, 30, false, ICMR_AI);
    }

    private static void adolescentExtras(Map<Micronutrient, MicronutrientTarget> out) {
        target(out, Micronutrient.PHOSPHORUS, 800, false, "Indian age-specific reference intake");
        target(out, Micronutrient.SODIUM, 2000, true, "ICMR-NIN adult ceiling applied conservatively");
        target(out, Micronutrient.SELENIUM, 40, false, ICMR_AI);
        target(out, Micronutrient.CHROMIUM, 50, false, ICMR_AI);
        target(out, Micronutrient.MANGANESE, 4.0, false, ICMR_AI);
        target(out, Micronutrient.VITAMIN_E, 10, false, ICMR_AI);
        target(out, Micronutrient.VITAMIN_K, 55, false, ICMR_AI);
        target(out, Micronutrient.PANTOTHENIC_ACID, 5, false, ICMR_AI);
        target(out, Micronutrient.BIOTIN, 30, false, ICMR_AI);
        // Potassium and copper are intentionally omitted here because the available Indian
        // age-specific source does not provide a defensible 13-18 year target.
    }

    private static double estimateTdee(ProfileSettings settings, LocalDate today) {
        try {
            return CalorieCalculator.calculate(settings, today).tdee;
        } catch (RuntimeException ignored) {
            return settings.sex == ProfileSettings.Sex.MALE ? 2710 : 2130;
        }
    }

    private static double energyScaled(double energyKcal, double amountPer1000Kcal) {
        double value = Math.max(0.0, energyKcal) * amountPer1000Kcal / 1000.0;
        return Math.round(value * 10.0) / 10.0;
    }

    private static void target(Map<Micronutrient, MicronutrientTarget> out,
                               Micronutrient nutrient, double amount, boolean upperLimit, String basis) {
        out.put(nutrient, new MicronutrientTarget(nutrient, amount, upperLimit, basis));
    }
}
