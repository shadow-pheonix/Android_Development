package com.shadowphoenix.dietlog.profile;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public final class ProfileSetupTest {
    private final LocalDate today = LocalDate.of(2026, 10, 8);

    @Test public void defaultsAloneDoNotCompleteSetup() {
        assertTrue(ProfileSetup.needsSetup(null, false, new ProfileSettings(), today));
        assertTrue(ProfileSetup.needsSetup(false, true, new ProfileSettings(), today));
    }

    @Test public void validLegacyAndCompletedProfilesAreNotPromptedAgain() {
        assertFalse(ProfileSetup.needsSetup(null, true, new ProfileSettings(), today));
        assertFalse(ProfileSetup.needsSetup(true, true, new ProfileSettings(), today));
    }

    @Test public void invalidStoredMeasurementsRequireRepair() {
        ProfileSettings s = new ProfileSettings();
        s.heightCm = Double.NaN;
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
        s.heightCm = 170;
        s.currentWeightKg = Double.POSITIVE_INFINITY;
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
        s.currentWeightKg = 70;
        s.targetWeightKg = 0;
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
    }

    @Test public void dateOfBirthOverridesAgeAndRejectsInvalidDates() {
        ProfileSettings s = new ProfileSettings();
        s.ageYears = 25;
        s.dateOfBirthIso = "2020-10-08";
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
        s.dateOfBirthIso = "2027-01-01";
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
        s.dateOfBirthIso = "not-a-date";
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
        s.dateOfBirthIso = "2000-10-08";
        assertFalse(ProfileSetup.needsSetup(true, true, s, today));
    }

    @Test public void missingSelectionsAndAgeOutsideSupportedRangeRequireSetup() {
        ProfileSettings s = new ProfileSettings();
        s.ageYears = 12;
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
        s.ageYears = 25;
        s.activityLevel = null;
        assertTrue(ProfileSetup.needsSetup(true, true, s, today));
    }
}
