package com.shadowphoenix.dietlog.discipline;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.time.*;
import java.util.*;

public class TimingSuggestionTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private static final Instant NOW = TODAY.atTime(23, 0).toInstant(ZoneOffset.UTC);

    private JSONObject state(int current) throws Exception {
        JSONObject row =
                new JSONObject()
                        .put("id", "a")
                        .put("title", "Before bed")
                        .put("category", "CUSTOM")
                        .put("hour", current / 60)
                        .put("minute", current % 60)
                        .put("days", 127)
                        .put("enabled", true)
                        .put("revision", "r1")
                        .put("createdDate", TODAY.minusDays(50).toString());
        return new JSONObject()
                .put("schema", 1)
                .put("reminders", new JSONObject().put("a", row))
                .put("history", new JSONArray());
    }

    private void samples(JSONObject state, String outcome, int... minutes) throws Exception {
        JSONArray history = state.getJSONArray("history");
        for (int i = 0; i < minutes.length; i++) {
            LocalDate day = TODAY.minusDays(i + 1);
            history.put(
                    new JSONObject()
                            .put("reminderId", "a")
                            .put("date", day.toString())
                            .put("outcome", outcome)
                            .put(
                                    "completedAt",
                                    day.atTime(minutes[i] / 60, minutes[i] % 60)
                                            .toInstant(ZoneOffset.UTC)
                                            .toEpochMilli()));
        }
    }

    @Test
    public void requiresSixDistinctDoneDaysAndStableTiming() throws Exception {
        JSONObject state = state(21 * 60);
        samples(state, "DONE", 1350, 1350, 1355, 1345, 1350);
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
        samples(state, "SKIPPED", 1350, 1350, 1350, 1350, 1350, 1350, 1350);
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
        state = state(21 * 60);
        samples(state, "DONE", 1350, 1350, 1355, 1345, 1350, 1350, 600);
        TimingSuggestion suggestion = TimingSuggestion.build(state, NOW, ZoneOffset.UTC).get(0);
        assertEquals(LocalTime.of(22, 30), suggestion.proposed);
        assertEquals(7, suggestion.sampleDays);
        assertEquals(
                21,
                ReminderStore.rows(state)
                        .getJSONObject("a")
                        .getInt("hour")); // Analysis never changes the schedule.
    }

    @Test
    public void skippedMissedUnknownAndOldTimestampsDoNotExpressPreferences() throws Exception {
        JSONObject state = state(21 * 60);
        samples(state, "MISSED", 1350, 1350, 1350, 1350, 1350, 1350, 1350);
        samples(state, "SKIPPED", 1350, 1350, 1350, 1350, 1350, 1350, 1350);
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
        state = state(21 * 60);
        samples(state, "DONE", 1350, 1350, 1350, 1350, 1350, 1350);
        state.getJSONArray("history").getJSONObject(0).put("completedAt", 0);
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
    }

    @Test
    public void circularMedianHandlesMidnightAndRejectsUnstableSamples() throws Exception {
        JSONObject state = state(22 * 60);
        samples(state, "DONE", 1430, 1435, 0, 5, 10, 1435, 0, 5);
        LocalTime next = TimingSuggestion.build(state, NOW, ZoneOffset.UTC).get(0).proposed;
        assertTrue(next.getHour() == 23 || next.getHour() == 0);
        state = state(8 * 60);
        samples(state, "DONE", 60, 300, 540, 780, 1020, 1260);
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
    }

    @Test
    public void smallDifferencesPausedAndDismissedRemindersAreNotSuggested() throws Exception {
        JSONObject state = state(22 * 60 + 20);
        samples(state, "DONE", 1350, 1350, 1350, 1350, 1350, 1350);
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
        state = state(21 * 60);
        samples(state, "DONE", 1350, 1350, 1350, 1350, 1350, 1350);
        state.put(
                "timingDismissed",
                new JSONObject().put("a", NOW.plusSeconds(86400).toEpochMilli()));
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
        state.remove("timingDismissed");
        state.getJSONObject("reminders")
                .getJSONObject("a")
                .put(
                        "pauses",
                        new JSONArray()
                                .put(
                                        new JSONObject()
                                                .put("from", TODAY.toString())
                                                .put("until", TODAY.plusDays(2).toString())));
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
    }

    @Test
    public void oldOrRepeatedSameDayHistoryCannotSupplySixDaysOfEvidence() throws Exception {
        JSONObject state = state(21 * 60);
        samples(state, "DONE", 1350, 1350, 1350, 1350, 1350, 1350);
        for (int i = 0; i < 6; i++)
            state.getJSONArray("history")
                    .getJSONObject(i)
                    .put("date", TODAY.minusDays(60).toString());
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
        for (int i = 0; i < 6; i++)
            state.getJSONArray("history")
                    .getJSONObject(i)
                    .put("date", TODAY.minusDays(1).toString());
        assertTrue(TimingSuggestion.build(state, NOW, ZoneOffset.UTC).isEmpty());
    }
}
