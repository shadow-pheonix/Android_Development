package com.shadowphoenix.dietlog.discipline;

import static org.junit.Assert.*;

import java.time.*;
import org.junit.Test;

public class ReminderScheduleTest {
  private Reminder r(int h, int m, int days, boolean enabled) {
    return new Reminder("a", "Routine", "", Reminder.Category.CUSTOM, h, m, days, enabled, "");
  }

  @Test
  public void mondayBitAndCompletedTodaySelectNextWeek() {
    ZoneId zone = ZoneId.of("UTC");
    Instant now = Instant.parse("2026-10-19T07:00:00Z");
    assertEquals(
        Instant.parse("2026-10-19T08:00:00Z"),
        ReminderSchedule.next(r(8, 0, 1, true), now, zone, d -> false).toInstant());
    assertEquals(
        Instant.parse("2026-10-26T08:00:00Z"),
        ReminderSchedule.next(
                r(8, 0, 1, true), now, zone, d -> d.equals(LocalDate.of(2026, 10, 19)))
            .toInstant());
  }

  @Test
  public void midnightAndEqualityDoNotRepeatOccurrence() {
    ZoneId zone = ZoneId.of("Asia/Kolkata");
    assertEquals(
        Instant.parse("2026-10-19T18:30:00Z"),
        ReminderSchedule.next(
                r(0, 0, 127, true), Instant.parse("2026-10-19T18:29:59Z"), zone, d -> false)
            .toInstant());
    assertEquals(
        Instant.parse("2026-10-20T18:30:00Z"),
        ReminderSchedule.next(
                r(0, 0, 127, true), Instant.parse("2026-10-19T18:30:00Z"), zone, d -> false)
            .toInstant());
  }

  @Test
  public void dstGapShiftsForwardAndOverlapFiresOnce() {
    ZoneId zone = ZoneId.of("America/New_York");
    ZonedDateTime gap =
        ReminderSchedule.next(
            r(2, 30, 127, true), Instant.parse("2026-03-08T00:00:00Z"), zone, d -> false);
    assertEquals(3, gap.getHour());
    assertEquals(30, gap.getMinute());
    assertEquals(Instant.parse("2026-03-08T07:30:00Z"), gap.toInstant());
    ZonedDateTime overlap =
        ReminderSchedule.next(
            r(1, 30, 127, true), Instant.parse("2026-11-01T00:00:00Z"), zone, d -> false);
    assertEquals(Instant.parse("2026-11-01T05:30:00Z"), overlap.toInstant());
    assertEquals(
        LocalDate.of(2026, 11, 2),
        ReminderSchedule.next(r(1, 30, 127, true), overlap.toInstant(), zone, d -> false)
            .toLocalDate());
  }

  @Test
  public void disabledAndSundayMask() {
    assertNull(
        ReminderSchedule.next(r(8, 0, 127, false), Instant.now(), ZoneId.of("UTC"), d -> false));
    assertEquals(
        LocalDate.of(2026, 10, 25),
        ReminderSchedule.next(
                r(8, 0, 64, true),
                Instant.parse("2026-10-19T00:00:00Z"),
                ZoneId.of("UTC"),
                d -> false)
            .toLocalDate());
  }

  @Test
  public void timezoneChangesPreserveLocalHour() {
    Instant now = Instant.parse("2026-10-19T00:00:00Z");
    assertEquals(
        8,
        ReminderSchedule.next(r(8, 0, 127, true), now, ZoneId.of("Asia/Tokyo"), d -> false)
            .getHour());
    assertNotEquals(
        ReminderSchedule.next(r(8, 0, 127, true), now, ZoneId.of("Asia/Tokyo"), d -> false)
            .toInstant(),
        ReminderSchedule.next(r(8, 0, 127, true), now, ZoneId.of("UTC"), d -> false).toInstant());
  }
}
