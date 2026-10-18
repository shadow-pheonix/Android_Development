package com.shadowphoenix.dietlog.discipline;

import java.time.*;
import java.util.function.Predicate;

/** Wall-clock recurrence. DST gaps shift forward; overlaps use the earlier offset once. */
public final class ReminderSchedule {
  private ReminderSchedule() {}

  public static ZonedDateTime next(
      Reminder reminder, Instant after, ZoneId zone, Predicate<LocalDate> completed) {
    if (!reminder.enabled) return null;
    LocalDate start = after.atZone(zone).toLocalDate();
    for (int i = 0; i <= 370; i++) {
      LocalDate day = start.plusDays(i);
      if ((reminder.daysMask & (1 << (day.getDayOfWeek().getValue() - 1))) == 0
          || completed.test(day)) continue;
      ZonedDateTime candidate = day.atTime(reminder.hour, reminder.minute).atZone(zone);
      if (candidate.toInstant().isAfter(after)) return candidate;
    }
    return null;
  }
}
