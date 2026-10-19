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

    public final String id, title, notes, mealKey;
    public final Category category;
    public final int hour, minute, daysMask;
    public final boolean enabled;

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
        if (id == null || id.trim().isEmpty() || id.length() > 80)
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
}
