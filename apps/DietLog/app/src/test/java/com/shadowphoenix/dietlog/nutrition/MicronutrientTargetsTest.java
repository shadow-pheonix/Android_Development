package com.shadowphoenix.dietlog.nutrition;

import com.shadowphoenix.dietlog.profile.ProfileSettings;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MicronutrientTargetsTest {
    @Test public void adultFemaleGetsSexSpecificIronAndSodiumCeiling() {
        ProfileSettings s = new ProfileSettings();
        s.sex = ProfileSettings.Sex.FEMALE;
        s.ageYears = 30;
        Map<Micronutrient, MicronutrientTarget> targets = MicronutrientTargets.forProfile(s, LocalDate.of(2026, 1, 1));
        assertEquals(29.0, targets.get(Micronutrient.IRON).amount, 0.0001);
        assertEquals(13.2, targets.get(Micronutrient.ZINC).amount, 0.0001);
        assertEquals(2000.0, targets.get(Micronutrient.SODIUM).amount, 0.0001);
        assertTrue(targets.get(Micronutrient.SODIUM).upperLimit);
    }

    @Test public void olderWomanUsesElderlyCalciumVitaminDAndIron() {
        ProfileSettings s = new ProfileSettings();
        s.sex = ProfileSettings.Sex.FEMALE;
        s.ageYears = 65;
        Map<Micronutrient, MicronutrientTarget> targets = MicronutrientTargets.forProfile(s, LocalDate.of(2026, 1, 1));
        assertEquals(1200.0, targets.get(Micronutrient.CALCIUM).amount, 0.0001);
        assertEquals(20.0, targets.get(Micronutrient.VITAMIN_D).amount, 0.0001);
        assertEquals(19.0, targets.get(Micronutrient.IRON).amount, 0.0001);
    }

    @Test public void teenageBoyUsesAgeSpecificIndianValues() {
        ProfileSettings s = new ProfileSettings();
        s.sex = ProfileSettings.Sex.MALE;
        s.ageYears = 16;
        Map<Micronutrient, MicronutrientTarget> targets = MicronutrientTargets.forProfile(s, LocalDate.of(2026, 1, 1));
        assertEquals(1050.0, targets.get(Micronutrient.CALCIUM).amount, 0.0001);
        assertEquals(26.0, targets.get(Micronutrient.IRON).amount, 0.0001);
        assertEquals(1000.0, targets.get(Micronutrient.VITAMIN_A).amount, 0.0001);
    }
    @Test public void stricterUserSodiumLimitIsRespectedButLegacyHigherLimitIsCapped() {
        ProfileSettings s = new ProfileSettings();
        s.sex = ProfileSettings.Sex.MALE;
        s.ageYears = 30;
        s.sodiumLimitMg = 1500.0;
        Map<Micronutrient, MicronutrientTarget> targets = MicronutrientTargets.forProfile(s, LocalDate.of(2026, 1, 1));
        assertEquals(1500.0, targets.get(Micronutrient.SODIUM).amount, 0.0001);
        s.sodiumLimitMg = 2300.0;
        targets = MicronutrientTargets.forProfile(s, LocalDate.of(2026, 1, 1));
        assertEquals(2000.0, targets.get(Micronutrient.SODIUM).amount, 0.0001);
    }

}
