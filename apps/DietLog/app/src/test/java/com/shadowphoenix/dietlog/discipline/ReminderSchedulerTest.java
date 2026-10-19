package com.shadowphoenix.dietlog.discipline;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.time.*;

/** Exercises the production planner and durable stage transitions without Android alarms. */
public class ReminderSchedulerTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    private JSONObject row(int hour, int minute, int days) throws Exception {
        return new JSONObject()
                .put("id", "a")
                .put("title", "Routine")
                .put("notes", "")
                .put("category", "CUSTOM")
                .put("hour", hour)
                .put("minute", minute)
                .put("days", days)
                .put("enabled", true)
                .put("revision", "revision1")
                .put("done", new JSONArray());
    }

    private JSONObject at(JSONObject row, String instant) {
        return ReminderScheduler.nextOccurrence(row, Instant.parse(instant), UTC);
    }

    private void deliver(JSONObject row, JSONObject occurrence, String actual) throws Exception {
        ReminderScheduler.recordDelivery(row, occurrence, Instant.parse(actual));
        row.put("displayed", occurrence);
        row.remove("pending");
    }

    @Test
    public void primaryThenExactlyOneFollowupAndNoMoreOnReopen() throws Exception {
        JSONObject row = row(8, 0, 127);
        JSONObject first = at(row, "2026-10-19T07:00:00Z");
        assertEquals("PRIMARY", first.getString("kind"));
        row.put("pending", first);
        deliver(row, first, "2026-10-19T08:07:00Z");
        JSONObject followup = at(row, "2026-10-19T08:07:00Z");
        assertEquals("FOLLOWUP", followup.getString("kind"));
        assertEquals(
                Instant.parse("2026-10-19T09:00:00Z").toEpochMilli(), followup.getLong("epoch"));
        row.put("pending", followup);
        assertEquals(
                followup.getString("token"), at(row, "2026-10-19T08:15:00Z").getString("token"));
        deliver(row, followup, "2026-10-19T09:00:00Z");
        row.remove("displayed"); // Notification dismissal cannot erase delivery evidence.
        assertEquals("2026-10-20", at(row, "2026-10-19T09:01:00Z").getString("date"));
        assertEquals(3, ReminderScheduler.deliveredStages(row, MONDAY));
    }

    @Test
    public void mondayLateNightFollowupRetainsMondayDespiteTuesdayMask() throws Exception {
        JSONObject row = row(23, 30, 1);
        JSONObject first = at(row, "2026-10-19T23:00:00Z");
        deliver(row, first, "2026-10-19T23:30:00Z");
        JSONObject second = at(row, "2026-10-20T00:05:00Z");
        assertEquals("2026-10-19", second.getString("date"));
        assertEquals(Instant.parse("2026-10-20T00:30:00Z").toEpochMilli(), second.getLong("epoch"));
        deliver(row, second, "2026-10-20T00:30:00Z");
        assertEquals("2026-10-26", at(row, "2026-10-20T00:31:00Z").getString("date"));
    }

    @Test
    public void completionCancelsFollowupAndUndoDoesNotRepeatPrimary() throws Exception {
        JSONObject row = row(8, 0, 127);
        deliver(row, at(row, "2026-10-19T07:00:00Z"), "2026-10-19T08:00:00Z");
        ReminderStore.complete(row, MONDAY, true);
        assertEquals("2026-10-20", at(row, "2026-10-19T08:10:00Z").getString("date"));
        ReminderStore.complete(row, MONDAY, false);
        assertEquals("FOLLOWUP", at(row, "2026-10-19T08:10:00Z").getString("kind"));
    }

    @Test
    public void delayedPrimaryPastFollowupOnlyProducesOneDelivery() throws Exception {
        JSONObject row = row(8, 0, 127);
        JSONObject first = at(row, "2026-10-19T07:00:00Z");
        row.put("pending", first);
        assertEquals(first.getString("token"), at(row, "2026-10-19T09:15:00Z").getString("token"));
        deliver(row, first, "2026-10-19T09:15:00Z");
        assertEquals("2026-10-20", at(row, "2026-10-19T09:15:00Z").getString("date"));
    }

    @Test
    public void rebootRetainsDueFollowupButDropsStaleAndUninstalledPrimaries() throws Exception {
        JSONObject row = row(8, 0, 127);
        JSONObject first = at(row, "2026-10-19T07:00:00Z");
        row.put("pending", first);
        assertEquals("2026-10-20", at(row, "2026-10-19T14:01:00Z").getString("date"));
        row.remove("pending");
        row.remove("primaryPending");
        assertEquals("2026-10-20", at(row, "2026-10-19T08:01:00Z").getString("date"));
        deliver(row, first, "2026-10-19T08:00:00Z");
        assertEquals("FOLLOWUP", at(row, "2026-10-19T10:00:00Z").getString("kind"));
        assertEquals("2026-10-20", at(row, "2026-10-19T15:01:00Z").getString("date"));
    }

    @Test
    public void midnightDelayedPrimaryRetainsOriginalDayAndOriginalFollowup() throws Exception {
        JSONObject row = row(23, 30, 1);
        JSONObject primary = at(row, "2026-10-19T23:00:00Z");
        row.put("pending", primary);
        JSONObject due = at(row, "2026-10-20T00:05:00Z");
        assertEquals(primary.getString("token"), due.getString("token"));
        deliver(row, due, "2026-10-20T00:05:00Z");
        JSONObject followup = at(row, "2026-10-20T00:05:00Z");
        assertEquals("2026-10-19", followup.getString("date"));
        assertEquals("FOLLOWUP", followup.getString("kind"));
        assertEquals(
                Instant.parse("2026-10-20T00:30:00Z").toEpochMilli(), followup.getLong("epoch"));
    }

    @Test
    public void midnightPrimaryDeliveredPastFollowupDoesNotProduceSecondAlert() throws Exception {
        JSONObject row = row(23, 30, 1);
        JSONObject primary = at(row, "2026-10-19T23:00:00Z");
        row.put("pending", primary);
        JSONObject due = at(row, "2026-10-20T00:45:00Z");
        deliver(row, due, "2026-10-20T00:45:00Z");
        assertEquals("2026-10-26", at(row, "2026-10-20T00:45:00Z").getString("date"));
    }

    @Test
    public void previousDayPrimaryExpiresAfterCatchupWindow() throws Exception {
        JSONObject row = row(23, 30, 127);
        row.put("pending", at(row, "2026-10-19T23:00:00Z"));
        assertEquals("2026-10-20", at(row, "2026-10-20T05:31:00Z").getString("date"));
    }

    @Test
    public void nextPrimaryIsRetainedWhileEarlierFollowupOccupiesAlarmSlot() throws Exception {
        JSONObject row = row(8, 0, 127);
        JSONObject first = at(row, "2026-10-19T07:00:00Z");
        deliver(row, first, "2026-10-19T08:00:00Z");
        JSONObject followup = at(row, "2026-10-19T08:00:00Z");
        row.put("pending", followup);
        // Device sleeps through both this followup and tomorrow's first alarm.
        JSONObject due = at(row, "2026-10-20T08:05:00Z");
        assertEquals("PRIMARY", due.getString("kind"));
        assertEquals("2026-10-20", due.getString("date"));
        assertEquals(Instant.parse("2026-10-20T08:00:00Z").toEpochMilli(), due.getLong("epoch"));
        deliver(row, due, "2026-10-20T08:05:00Z");
        assertEquals(
                Instant.parse("2026-10-20T09:00:00Z").toEpochMilli(),
                at(row, "2026-10-20T08:05:00Z").getLong("epoch"));
    }

    @Test
    public void editCancelsOldFollowupAndDisableCancelsAll() throws Exception {
        JSONObject row = row(8, 0, 127);
        deliver(row, at(row, "2026-10-19T07:00:00Z"), "2026-10-19T08:00:00Z");
        row.put("revision", "edited");
        assertEquals("2026-10-20", at(row, "2026-10-19T08:10:00Z").getString("date"));
        row.put("enabled", false);
        assertNull(at(row, "2026-10-19T08:10:00Z"));
        assertFalse(row.has("displayed"));
    }

    @Test
    public void timezoneChangeMovesUndeliveredPrimaryButKeepsElapsedFollowup() throws Exception {
        JSONObject row = row(8, 0, 127);
        JSONObject first = at(row, "2026-10-19T07:00:00Z");
        row.put("pending", first);
        JSONObject changed =
                ReminderScheduler.nextOccurrence(
                        row, Instant.parse("2026-10-19T07:30:00Z"), ZoneId.of("Europe/Berlin"));
        assertEquals(
                Instant.parse("2026-10-20T06:00:00Z").toEpochMilli(), changed.getLong("epoch"));
        deliver(row, first, "2026-10-19T08:00:00Z");
        JSONObject followup =
                ReminderScheduler.nextOccurrence(
                        row, Instant.parse("2026-10-19T08:30:00Z"), ZoneId.of("Europe/Berlin"));
        assertEquals(
                Instant.parse("2026-10-19T09:00:00Z").toEpochMilli(), followup.getLong("epoch"));
    }

    @Test
    public void snoozeSurvivesAnEarlierFollowupAndIsRemovedByDone() throws Exception {
        JSONObject row = row(8, 0, 127);
        deliver(row, at(row, "2026-10-19T07:00:00Z"), "2026-10-19T08:00:00Z");
        JSONObject snooze =
                ReminderScheduler.occurrence(
                        row,
                        MONDAY,
                        Instant.parse("2026-10-19T09:05:00Z").toEpochMilli(),
                        "SNOOZE");
        snooze.put("baseEpoch", Instant.parse("2026-10-19T08:00:00Z").toEpochMilli());
        row.put("snoozed", snooze);
        JSONObject followup = at(row, "2026-10-19T08:55:00Z");
        assertEquals("FOLLOWUP", followup.getString("kind"));
        deliver(row, followup, "2026-10-19T09:00:00Z");
        assertEquals(snooze.getString("token"), at(row, "2026-10-19T09:01:00Z").getString("token"));
        ReminderStore.complete(row, MONDAY, true);
        assertFalse(row.has("snoozed"));
        assertEquals("2026-10-20", at(row, "2026-10-19T09:01:00Z").getString("date"));
    }

    @Test
    public void oldDisplayedStateMigratesWithoutRepeatingPrimary() throws Exception {
        JSONObject row = row(8, 0, 127);
        JSONObject old =
                ReminderScheduler.occurrence(
                        row,
                        MONDAY,
                        Instant.parse("2026-10-19T08:00:00Z").toEpochMilli(),
                        "PRIMARY");
        old.remove("kind");
        old.remove("baseEpoch");
        row.put("displayed", old);
        assertEquals("FOLLOWUP", at(row, "2026-10-19T08:30:00Z").getString("kind"));
    }

    @Test
    public void deliveryEvidenceIsBoundedAndExpiresOldNotifications() throws Exception {
        JSONObject row = row(8, 0, 127);
        for (int i = 0; i < 100; i++) {
            LocalDate date = MONDAY.plusDays(i);
            Instant time = date.atTime(8, 0).toInstant(ZoneOffset.UTC);
            ReminderScheduler.recordDelivery(
                    row,
                    ReminderScheduler.occurrence(row, date, time.toEpochMilli(), "PRIMARY"),
                    time);
        }
        assertEquals(90, row.getJSONObject("deliveries").length());
        row.put("displayed", ReminderScheduler.occurrence(row, MONDAY, 0, "PRIMARY"));
        at(row, "2026-10-21T08:01:00Z");
        assertFalse(row.has("displayed"));
    }
}
