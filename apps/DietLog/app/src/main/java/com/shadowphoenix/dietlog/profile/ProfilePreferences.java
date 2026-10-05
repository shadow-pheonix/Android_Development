package com.shadowphoenix.dietlog.profile;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadowphoenix.dietlog.sync.SyncCoordinator;

public final class ProfilePreferences {
    private static final String FILE = "dietlog_profile";
    private final SharedPreferences prefs;
    private final Context appContext;

    public ProfilePreferences(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean hasSavedProfile() { return prefs.getBoolean("profileSaved", false); }

    public ProfileSettings load() {
        ProfileSettings s = new ProfileSettings();
        s.displayName = prefs.getString("displayName", s.displayName);
        s.avatarUri = prefs.getString("avatarUri", s.avatarUri);
        s.dateOfBirthIso = prefs.getString("dateOfBirthIso", s.dateOfBirthIso);
        s.ageYears = prefs.getInt("ageYears", s.ageYears);
        s.sex = enumValue(ProfileSettings.Sex.class, prefs.getString("sex", s.sex.name()), s.sex);
        s.heightCm = getDouble("heightCm", s.heightCm);
        s.currentWeightKg = getDouble("currentWeightKg", s.currentWeightKg);
        s.targetWeightKg = getDouble("targetWeightKg", s.targetWeightKg);
        s.activityLevel = enumValue(ProfileSettings.ActivityLevel.class,
                prefs.getString("activityLevel", s.activityLevel.name()), s.activityLevel);
        s.goal = enumValue(ProfileSettings.Goal.class, prefs.getString("goal", s.goal.name()), s.goal);
        s.weeklyWeightChangeKg = getDouble("weeklyWeightChangeKg", s.weeklyWeightChangeKg);
        s.manualCalorieOverride = prefs.getBoolean("manualCalorieOverride", s.manualCalorieOverride);
        s.manualCalorieTarget = getDouble("manualCalorieTarget", s.manualCalorieTarget);
        s.macroMode = enumValue(ProfileSettings.MacroMode.class,
                prefs.getString("macroMode", s.macroMode.name()), s.macroMode);
        s.proteinGoalG = getDouble("proteinGoalG", s.proteinGoalG);
        s.carbsGoalG = getDouble("carbsGoalG", s.carbsGoalG);
        s.fatGoalG = getDouble("fatGoalG", s.fatGoalG);
        s.proteinPercent = getDouble("proteinPercent", s.proteinPercent);
        s.carbsPercent = getDouble("carbsPercent", s.carbsPercent);
        s.fatPercent = getDouble("fatPercent", s.fatPercent);
        s.fibreGoalG = getDouble("fibreGoalG", s.fibreGoalG);
        s.sugarLimitG = getDouble("sugarLimitG", s.sugarLimitG);
        s.sodiumLimitMg = getDouble("sodiumLimitMg", s.sodiumLimitMg);
        return s;
    }

    public void save(ProfileSettings s) {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("profileSaved", true);
        e.putString("displayName", s.displayName == null ? "" : s.displayName.trim());
        e.putString("avatarUri", s.avatarUri == null ? "" : s.avatarUri);
        e.putString("dateOfBirthIso", s.dateOfBirthIso == null ? "" : s.dateOfBirthIso);
        e.putInt("ageYears", s.ageYears);
        e.putString("sex", s.sex.name());
        putDouble(e, "heightCm", s.heightCm);
        putDouble(e, "currentWeightKg", s.currentWeightKg);
        putDouble(e, "targetWeightKg", s.targetWeightKg);
        e.putString("activityLevel", s.activityLevel.name());
        e.putString("goal", s.goal.name());
        putDouble(e, "weeklyWeightChangeKg", s.weeklyWeightChangeKg);
        e.putBoolean("manualCalorieOverride", s.manualCalorieOverride);
        putDouble(e, "manualCalorieTarget", s.manualCalorieTarget);
        e.putString("macroMode", s.macroMode.name());
        putDouble(e, "proteinGoalG", s.proteinGoalG);
        putDouble(e, "carbsGoalG", s.carbsGoalG);
        putDouble(e, "fatGoalG", s.fatGoalG);
        putDouble(e, "proteinPercent", s.proteinPercent);
        putDouble(e, "carbsPercent", s.carbsPercent);
        putDouble(e, "fatPercent", s.fatPercent);
        putDouble(e, "fibreGoalG", s.fibreGoalG);
        putDouble(e, "sugarLimitG", s.sugarLimitG);
        putDouble(e, "sodiumLimitMg", s.sodiumLimitMg);
        e.apply();
        SyncCoordinator.markUserDataChanged(appContext, "profile-save");
    }

    private double getDouble(String key, double fallback) {
        return Double.longBitsToDouble(prefs.getLong(key, Double.doubleToRawLongBits(fallback)));
    }

    private static void putDouble(SharedPreferences.Editor e, String key, double value) {
        e.putLong(key, Double.doubleToRawLongBits(value));
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback) {
        if (raw == null) return fallback;
        try { return Enum.valueOf(type, raw); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
}
