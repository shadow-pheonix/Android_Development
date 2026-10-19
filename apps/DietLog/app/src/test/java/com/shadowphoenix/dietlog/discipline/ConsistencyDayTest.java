package com.shadowphoenix.dietlog.discipline;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.time.*;
import java.util.*;

public class ConsistencyDayTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 19);

    private JSONObject row(String id) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("title", id)
                .put("category", "CUSTOM")
                .put("hour", 8)
                .put("minute", 0)
                .put("days", 127)
                .put("enabled", true)
                .put("createdDate", DAY.minusDays(5).toString())
                .put("routine", "Morning routine");
    }

    private JSONObject event(String id, String outcome) throws Exception {
        return new JSONObject()
                .put("reminderId", id)
                .put("date", DAY.toString())
                .put("title", id)
                .put("routine", "Morning routine")
                .put("outcome", outcome);
    }

    private ConsistencyDay build(List<JSONObject> rows, JSONArray history, String now)
            throws Exception {
        return ConsistencyDay.build(
                new JSONObject().put("history", history),
                rows,
                DAY,
                Instant.parse(now),
                ZoneOffset.UTC);
    }

    @Test
    public void routineOnlyCompletesWhenAllIndependentOccurrencesAreDone() throws Exception {
        List<JSONObject> rows = List.of(row("a"), row("b"));
        ConsistencyDay partial =
                build(rows, new JSONArray().put(event("a", "DONE")), "2026-10-19T12:00:00Z");
        assertEquals(2, partial.expected);
        assertEquals(1, partial.done);
        assertEquals(0, partial.completedRoutines());
        assertEquals(1, partial.open);
        ConsistencyDay full =
                build(
                        rows,
                        new JSONArray().put(event("a", "DONE")).put(event("b", "DONE")),
                        "2026-10-19T12:00:00Z");
        assertEquals(1, full.completedRoutines());
        assertEquals(2, full.done);
    }

    @Test
    public void pausedDaysAreExcludedAndSkipsAreSeparateFromMisses() throws Exception {
        JSONObject paused =
                row("paused")
                        .put(
                                "pauses",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("from", DAY.toString())
                                                        .put("until", DAY.plusDays(1).toString())));
        ConsistencyDay day =
                build(
                        List.of(row("skip"), row("miss"), paused),
                        new JSONArray().put(event("skip", "SKIPPED")),
                        "2026-10-21T12:00:00Z");
        assertEquals(2, day.expected);
        assertEquals(1, day.skipped);
        assertEquals(1, day.missed);
        assertEquals(1, day.paused);
        assertEquals(0, day.done);
        ConsistencyDay excluded = build(List.of(paused), new JSONArray(), "2026-10-21T12:00:00Z");
        assertEquals(0, excluded.expected);
        assertTrue(excluded.routines.isEmpty());
    }

    @Test
    public void historicalSnapshotsSurviveDeletionAndDoNotDoubleCount() throws Exception {
        ConsistencyDay deleted =
                build(List.of(), new JSONArray().put(event("a", "DONE")), "2026-10-21T12:00:00Z");
        assertEquals(1, deleted.completedRoutines());
        ConsistencyDay present =
                build(
                        List.of(row("a")),
                        new JSONArray().put(event("a", "DONE")),
                        "2026-10-21T12:00:00Z");
        assertEquals(1, present.expected);
    }

    @Test
    public void overnightFollowupIsOpenUntilItsDeliveryWindowExpires() throws Exception {
        JSONObject late = row("late").put("hour", 23).put("minute", 30);
        assertEquals(1, build(List.of(late), new JSONArray(), "2026-10-20T00:05:00Z").open);
        assertEquals(1, build(List.of(late), new JSONArray(), "2026-10-20T07:00:00Z").missed);
    }
}
