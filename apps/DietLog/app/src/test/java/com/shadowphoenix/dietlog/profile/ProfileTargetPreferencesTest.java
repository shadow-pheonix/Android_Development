package com.shadowphoenix.dietlog.profile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class ProfileTargetPreferencesTest {
    @Test public void optionalDetailsAndAutomaticPolicySurviveSavingAndFreshPreferenceRestore() {
        MemoryContext original = new MemoryContext();
        ProfileSettings s = new ProfileSettings();
        s.bodyFatPercent = 22.5; s.measuredRmrKcal = 1615; s.observedMaintenanceKcal = 2475;
        s.trainingFocus = ProfileSettings.TrainingFocus.RESISTANCE;
        s.autoNutritionTargets = true;
        new ProfilePreferences(original).savePersonalProfile(s);
        MemoryContext restored = new MemoryContext();
        restored.values("dietlog_profile").putAll(original.values("dietlog_profile"));
        ProfilePreferences preferences = new ProfilePreferences(restored);
        ProfileSettings copy = preferences.load();
        assertEquals(22.5, copy.bodyFatPercent, 0); assertEquals(1615, copy.measuredRmrKcal, 0);
        assertEquals(2475, copy.observedMaintenanceKcal, 0);
        assertEquals(ProfileSettings.TrainingFocus.RESISTANCE, copy.trainingFocus);
        assertTrue(copy.autoNutritionTargets); assertTrue(preferences.hasCompletedPersonalProfile());
        assertFalse(StartupPreferences.needsDriveRestore(true, true, preferences.hasCompletedPersonalProfile(), false));
        double target = CalorieCalculator.calculate(copy, LocalDate.now()).calorieTarget;
        assertEquals(target, NutritionGoalCalculator.macroCalories(copy.proteinGoalG, copy.carbsGoalG, copy.fatGoalG), 2);
    }

    @Test public void legacyProfilesKeepTheirCustomMacrosAndNeedNoOptionalDetails() {
        MemoryContext context = new MemoryContext();
        Map<String, Object> profile = context.values("dietlog_profile");
        profile.put("profileSaved", true); profile.put("personalProfileComplete", true);
        profile.put("proteinGoalG", Double.doubleToRawLongBits(123));
        profile.put("sugarLimitG", Double.doubleToRawLongBits(40));
        ProfilePreferences preferences = new ProfilePreferences(context);
        ProfileSettings s = preferences.load();
        assertFalse(s.autoNutritionTargets); assertEquals(123, s.proteinGoalG, 0);
        assertEquals(40, s.sugarLimitG, 0); assertEquals(0, s.bodyFatPercent, 0);
        assertEquals(0, s.measuredRmrKcal, 0); assertEquals(0, s.observedMaintenanceKcal, 0);
        assertEquals(ProfileSettings.TrainingFocus.UNSPECIFIED, s.trainingFocus);
        assertTrue(preferences.hasCompletedPersonalProfile());
    }

    @Test public void clearingMeasurementsRemovesCalibrationAndKeepsCustomTargets() {
        MemoryContext context = new MemoryContext();
        ProfilePreferences preferences = new ProfilePreferences(context);
        ProfileSettings s = new ProfileSettings(); s.observedMaintenanceKcal = 2300; s.bodyFatPercent = 18;
        s.proteinGoalG = 130; s.sugarLimitG = 35;
        preferences.savePersonalProfile(s);
        s = preferences.load(); s.bodyFatPercent = 0; s.observedMaintenanceKcal = 0;
        preferences.savePersonalProfile(s);
        ProfileSettings cleared = preferences.load();
        assertEquals(0, cleared.bodyFatPercent, 0); assertEquals(0, cleared.observedMaintenanceKcal, 0);
        assertEquals(130, cleared.proteinGoalG, 0); assertEquals(35, cleared.sugarLimitG, 0);
        assertEquals(CalorieCalculator.RestingMethod.MIFFLIN, CalorieCalculator.calculate(cleared, LocalDate.now()).restingMethod);
    }

    @Test public void unknownFutureTrainingOptionFallsBackWithoutResettingSetup() {
        MemoryContext context = new MemoryContext();
        context.values("dietlog_profile").put("profileSaved", true);
        context.values("dietlog_profile").put("trainingFocus", "FUTURE_OPTION");
        ProfilePreferences preferences = new ProfilePreferences(context);
        assertEquals(ProfileSettings.TrainingFocus.UNSPECIFIED, preferences.load().trainingFocus);
        assertTrue(preferences.hasCompletedPersonalProfile());
    }

    private static final class MemoryContext extends ContextWrapper {
        private final Map<String, Map<String, Object>> files = new HashMap<>();
        MemoryContext() { super(null); }
        Map<String, Object> values(String file) { return files.computeIfAbsent(file, key -> new HashMap<>()); }
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String file, int mode) {
            Map<String, Object> stored = values(file);
            return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("contains")) return stored.containsKey(args[0]);
                        if (name.equals("getAll")) return new HashMap<>(stored);
                        if (name.startsWith("get")) return stored.getOrDefault((String) args[0], args[1]);
                        if (name.equals("edit")) return editor(stored);
                        throw new UnsupportedOperationException(name);
                    });
        }
        private static SharedPreferences.Editor editor(Map<String, Object> stored) {
            Map<String, Object> edited = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("put")) { edited.put((String) args[0], args[1]); return proxy; }
                        if (name.equals("apply") || name.equals("commit")) {
                            stored.putAll(edited); edited.clear(); return name.equals("commit") ? true : null;
                        }
                        throw new UnsupportedOperationException(name);
                    });
        }
    }
}
