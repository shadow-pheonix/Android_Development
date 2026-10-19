package com.shadowphoenix.dietlog.discipline;

import java.time.LocalTime;
import java.util.Arrays;

/** Device-local reminder; completing one never writes a diary entry. */
public final class Reminder {
    public enum Category {
        FOOD,
        SKIN,
        HAIR,
        CUSTOM,
        SUPPLEMENTS
    }

    public final String id, title, notes, mealKey, routine;
    public final Category category;
    public final int hour, minute, daysMask;
    public final boolean enabled;
    public final ReminderPlan plan;

    public Reminder(
            String id,
            String title,
            String notes,
            Category category,
            int hour,
            int minute,
            int daysMask,
            boolean enabled,
            String mealKey) {
        this(
                id,
                title,
                notes,
                category,
                hour,
                minute,
                daysMask,
                enabled,
                mealKey,
                ReminderPlan.daily());
    }

    public Reminder(
            String id,
            String title,
            String notes,
            Category category,
            int hour,
            int minute,
            int daysMask,
            boolean enabled,
            String mealKey,
            ReminderPlan plan) {
        this(id, title, notes, category, hour, minute, daysMask, enabled, mealKey, plan, "");
    }

    public Reminder(
            String id,
            String title,
            String notes,
            Category category,
            int hour,
            int minute,
            int daysMask,
            boolean enabled,
            String mealKey,
            ReminderPlan plan,
            String routine) {
        this.routine = routineName(routine);
        if (id == null || id.trim().isEmpty() || id.length() > 100)
            throw new IllegalArgumentException("Invalid reminder ID");
        if (title == null || title.trim().isEmpty() || title.length() > 120)
            throw new IllegalArgumentException("Use a title of 1–120 characters");
        if (notes != null && notes.length() > 1000)
            throw new IllegalArgumentException("Keep notes under 1000 characters");
        if (category == null) throw new IllegalArgumentException("Choose a category");
        LocalTime.of(hour, minute);
        if (daysMask < 1 || daysMask > 127)
            throw new IllegalArgumentException("Choose at least one repeat day");
        if (mealKey != null
                && !mealKey.isEmpty()
                && !Arrays.asList("breakfast", "lunch", "snacks", "dinner").contains(mealKey))
            throw new IllegalArgumentException("Invalid meal link");
        this.plan = java.util.Objects.requireNonNull(plan);
        this.id = id;
        this.title = title.trim();
        this.notes = notes == null ? "" : notes;
        this.category = category;
        this.hour = hour;
        this.minute = minute;
        this.daysMask = daysMask;
        this.enabled = enabled;
        this.mealKey = mealKey == null ? "" : mealKey;
    }

    /**
     * Extra wall-clock times have deterministic independent IDs; the original ID stays compatible.
     */
    public java.util.List<Reminder> occurrences() {
        java.util.List<Reminder> values = new java.util.ArrayList<>();
        values.add(this);
        for (LocalTime time : plan.extraTimes) {
            if (time.equals(LocalTime.of(hour, minute))) continue;
            values.add(
                    new Reminder(
                            id + "~" + plan.timeIds.get(time),
                            title,
                            notes,
                            category,
                            time.getHour(),
                            time.getMinute(),
                            daysMask,
                            enabled,
                            mealKey,
                            new ReminderPlan(
                                    plan.mode,
                                    plan.start,
                                    plan.end,
                                    plan.intervalDays,
                                    plan.dates,
                                    java.util.Collections.emptyList(),
                                    plan.followupMinutes),
                            routine));
        }
        return values;
    }

    public static String routineName(String name) {
        String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 60)
            throw new IllegalArgumentException("Keep routine names under 60 characters");
        return normalized;
    }

    public static String routineKey(String name) {
        return routineName(name).toLowerCase(java.util.Locale.ROOT);
    }
}
