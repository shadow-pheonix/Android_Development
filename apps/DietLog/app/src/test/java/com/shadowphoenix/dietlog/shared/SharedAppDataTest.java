package com.shadowphoenix.dietlog.shared;

import static org.junit.Assert.*;
import com.shadowphoenix.dietlog.data.FoodEntry;
import com.shadowphoenix.dietlog.profile.ProfileSettings;
import java.time.LocalDate;
import java.util.*;
import org.junit.Test;

public class SharedAppDataTest {
    private ProfileSettings profile() {
        ProfileSettings p = new ProfileSettings();
        p.displayName = "Test user";
        p.manualCalorieOverride = true;
        p.manualCalorieTarget = 2300;
        p.proteinGoalG = 130;
        return p;
    }

    private FoodEntry entry(long id, String meal, double kcal) {
        return new FoodEntry(id, "Food", 100, kcal, 20, 40, 8, 0, meal, "manual", null, null);
    }

    @Test
    public void disciplineReadsActualProfileDiaryAndSavedMealNamesWithoutChangingThem() {
        Map<String, String> plans = new HashMap<>();
        plans.put("lunch", "Tomato pappu with rice");
        List<FoodEntry> entries =
                new ArrayList<>(
                        Arrays.asList(
                                entry(1, "lunch", 500),
                                entry(2, "lunch", 250),
                                entry(3, "breakfast", 300)));
        ProfileSettings p = profile();
        SharedAppData.FuelNoteSnapshot s =
                new SharedAppData.FuelNoteSnapshot(
                        LocalDate.of(2026, 10, 18), p, true, entries, plans);
        assertEquals(1050, s.calories, 0);
        assertEquals(60, s.protein, 0);
        assertEquals(2300, s.calorieTarget, 0);
        assertEquals(130, s.proteinTarget, 0);
        assertEquals("Test user", s.displayName);
        assertEquals(3, s.entryCount);
        assertEquals(Integer.valueOf(2), s.mealEntries.get("lunch"));
        assertEquals("2 food entries logged", s.mealStatus("lunch"));
        assertEquals("No food logged", s.mealStatus("dinner"));
        assertEquals("Tomato pappu with rice", s.plannedMeals.get("lunch"));
        assertEquals(3, entries.size());
        assertEquals(2300, p.manualCalorieTarget, 0);
        plans.clear();
        entries.clear();
        assertEquals(3, s.entryCount);
        assertEquals("Tomato pappu with rice", s.plannedMeals.get("lunch"));
    }

    @Test
    public void disciplineDoesNotInventTargetsBeforeFuelNoteSetup() {
        SharedAppData.FuelNoteSnapshot s =
                new SharedAppData.FuelNoteSnapshot(
                        LocalDate.of(2026, 10, 18),
                        profile(),
                        false,
                        Collections.singletonList(entry(1, "snacks", 200)),
                        Collections.emptyMap());
        assertFalse(s.profileReady);
        assertEquals(0, s.calorieTarget, 0);
        assertEquals(0, s.proteinTarget, 0);
        assertEquals(200, s.calories, 0);
        assertEquals("", s.goalLabel);
    }

    @Test
    public void incompleteRestoredProfileDoesNotBlockDisciplineReminders() {
        ProfileSettings p = profile();
        p.heightCm = -1;
        SharedAppData.FuelNoteSnapshot s =
                new SharedAppData.FuelNoteSnapshot(
                        LocalDate.of(2026, 10, 18),
                        p,
                        true,
                        Collections.emptyList(),
                        Collections.emptyMap());
        assertFalse(s.profileReady);
        assertEquals(0, s.calorieTarget, 0);
    }
}
