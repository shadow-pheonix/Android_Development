package com.shadowphoenix.dietlog.discipline;

import java.time.*;
import java.util.function.Predicate;

/** Wall-clock recurrence. DST gaps shift forward; overlaps use the earlier offset once. */
public final class ReminderSchedule {
    private ReminderSchedule() {}

    public static ZonedDateTime scheduledOn(Reminder reminder, LocalDate day, ZoneId zone) {
        return day.atTime(reminder.hour, reminder.minute).atZone(zone);
    }

    /** An elapsed hour, including across midnight and daylight-saving transitions. */
    public static Instant followup(Reminder reminder, LocalDate day, ZoneId zone) {
        return scheduledOn(reminder, day, zone).toInstant().plus(Duration.ofHours(1));
    }

    public static ZonedDateTime next(
            Reminder reminder, Instant after, ZoneId zone, Predicate<LocalDate> completed) {
        if (!reminder.enabled) return null;
        LocalDate start = after.atZone(zone).toLocalDate();
        for (int i = 0; i <= 370; i++) {
            LocalDate day = start.plusDays(i);
            if ((reminder.daysMask & (1 << (day.getDayOfWeek().getValue() - 1))) == 0
                    || completed.test(day)) continue;
            ZonedDateTime candidate = scheduledOn(reminder, day, zone);
            if (candidate.toInstant().isAfter(after)) return candidate;
        }
        return null;
    }
}
