package com.shadowphoenix.dietlog.shared;

import android.content.Context;

import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.data.FoodEntry;
import com.shadowphoenix.dietlog.discipline.Reminder;
import com.shadowphoenix.dietlog.discipline.ReminderStore;
import com.shadowphoenix.dietlog.planner.PlannerStore;
import com.shadowphoenix.dietlog.profile.CalorieCalculator;
import com.shadowphoenix.dietlog.profile.ProfilePreferences;
import com.shadowphoenix.dietlog.profile.ProfileSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Both sections read the same local records. These methods belong on a background worker. */
public final class SharedAppData {
    private SharedAppData() {}

    public static final class FuelNoteSnapshot {
        public final LocalDate date;
        public final String displayName, goalLabel;
        public final boolean profileReady;
        public final double calories, protein, carbs, fat;
        public final double calorieTarget, proteinTarget, carbsTarget, fatTarget;
        public final int entryCount;
        public final Map<String, Integer> mealEntries;
        public final Map<String, String> plannedMeals;

        FuelNoteSnapshot(
                LocalDate date,
                ProfileSettings profile,
                boolean ready,
                List<FoodEntry> entries,
                Map<String, String> planned) {
            this.date = date;
            displayName = profile.displayName == null ? "" : profile.displayName;
            double target = 0;
            if (ready) {
                try {
                    target = CalorieCalculator.calculate(profile, date).calorieTarget;
                } catch (IllegalArgumentException invalid) {
                    ready = false;
                }
            }
            goalLabel = ready ? profile.goal.label : "";
            profileReady = ready;
            calorieTarget = target;
            proteinTarget = ready ? Math.max(0, profile.proteinGoalG) : 0;
            carbsTarget = ready ? Math.max(0, profile.carbsGoalG) : 0;
            fatTarget = ready ? Math.max(0, profile.fatGoalG) : 0;
            double[] totals = new double[4];
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String meal : new String[] {"breakfast", "lunch", "snacks", "dinner"})
                counts.put(meal, 0);
            for (FoodEntry entry : entries) {
                totals[0] += entry.calories;
                totals[1] += entry.protein;
                totals[2] += entry.carbs;
                totals[3] += entry.fat;
                counts.put(entry.mealType, counts.getOrDefault(entry.mealType, 0) + 1);
            }
            calories = totals[0];
            protein = totals[1];
            carbs = totals[2];
            fat = totals[3];
            entryCount = entries.size();
            mealEntries = Collections.unmodifiableMap(counts);
            plannedMeals = Collections.unmodifiableMap(new LinkedHashMap<>(planned));
        }

        public String mealStatus(String mealKey) {
            int count = mealEntries.getOrDefault(mealKey, 0);
            return count == 0
                    ? "No food logged"
                    : count + (count == 1 ? " food entry logged" : " food entries logged");
        }
    }

    public static FuelNoteSnapshot loadFuelNote(Context context, LocalDate date) throws Exception {
        ProfilePreferences preferences = new ProfilePreferences(context);
        ProfileSettings profile = preferences.load();
        boolean ready = preferences.hasCompletedPersonalProfile();
        List<FoodEntry> entries;
        try (DiaryDbHelper diary = new DiaryDbHelper(context)) {
            entries = diary.getEntriesForDate(date);
        }
        Map<String, String> planned = new LinkedHashMap<>();
        PlannerStore store = new PlannerStore(context);
        JSONArray selected;
        try {
            selected = store.standardPlan().optJSONArray("selected");
        } catch (org.json.JSONException invalidPlan) {
            selected = null;
        }
        if (selected != null && selected.length() <= 4) {
            Map<String, String> names = new LinkedHashMap<>();
            try (InputStream input = context.getAssets().open("planner_meals.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                addNames(
                        names,
                        new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8))
                                .getJSONArray("recipes"));
            }
            addNames(names, store.personalRecipes());
            String[] meals = {"breakfast", "lunch", "snacks", "dinner"};
            for (int i = 0; i < selected.length(); i++) {
                JSONObject choice = selected.getJSONObject(i);
                int meal = choice.optInt("meal", -1);
                String name = names.get(choice.optString("recipe"));
                if (meal >= 0 && meal < 4 && name != null) planned.put(meals[meal], name);
            }
        }
        return new FuelNoteSnapshot(date, profile, ready, entries, planned);
    }

    private static void addNames(Map<String, String> names, JSONArray rows) throws Exception {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            names.put(row.getString("id"), row.getString("name"));
        }
    }

    public static final class DisciplineSnapshot {
        public final int scheduled, completed, enabled;
        public final List<Reminder> reminders;

        private DisciplineSnapshot(
                int scheduled, int completed, int enabled, List<Reminder> reminders) {
            this.scheduled = scheduled;
            this.completed = completed;
            this.enabled = enabled;
            this.reminders = Collections.unmodifiableList(new ArrayList<>(reminders));
        }
    }

    public static DisciplineSnapshot loadDiscipline(Context context, LocalDate date) {
        ReminderStore store = new ReminderStore(context);
        List<Reminder> reminders = store.list();
        int scheduled = 0, completed = 0, enabled = 0;
        int weekday = 1 << (date.getDayOfWeek().getValue() - 1);
        java.util.Set<String> completedIds = store.completedForDate(date);
        for (Reminder reminder : reminders) {
            if (!reminder.enabled) continue;
            enabled++;
            if ((reminder.daysMask & weekday) == 0) continue;
            scheduled++;
            if (completedIds.contains(reminder.id)) completed++;
        }
        return new DisciplineSnapshot(scheduled, completed, enabled, reminders);
    }
}
