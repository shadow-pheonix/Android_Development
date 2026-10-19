package com.shadowphoenix.dietlog.discipline;

import org.json.*;

import java.time.*;
import java.util.*;

/** Read-only occurrence rollup. Skips are deliberate; paused occurrences are never missed. */
public final class ConsistencyDay {
    public static final class RoutineProgress {
        public final String name;
        public int expected, done, skipped, missed, open;

        RoutineProgress(String name) {
            this.name = name;
        }

        public boolean complete() {
            return expected > 0 && done == expected;
        }
    }

    public final LocalDate date;
    public final List<RoutineProgress> routines;
    public final int expected, done, skipped, missed, open, paused;

    private ConsistencyDay(LocalDate date, Collection<RoutineProgress> groups, int paused) {
        this.date = date;
        this.paused = paused;
        List<RoutineProgress> sorted = new ArrayList<>(groups);
        sorted.sort(Comparator.comparing(r -> r.name));
        routines = Collections.unmodifiableList(sorted);
        int e = 0, d = 0, s = 0, m = 0, o = 0;
        for (RoutineProgress group : groups) {
            e += group.expected;
            d += group.done;
            s += group.skipped;
            m += group.missed;
            o += group.open;
        }
        expected = e;
        done = d;
        skipped = s;
        missed = m;
        open = o;
    }

    public int completedRoutines() {
        int count = 0;
        for (RoutineProgress r : routines) if (r.complete()) count++;
        return count;
    }

    static ConsistencyDay build(
            JSONObject snapshot, List<JSONObject> rows, LocalDate day, Instant now, ZoneId zone) {
        Map<String, JSONObject> events = new HashMap<>();
        JSONArray history = snapshot.optJSONArray("history");
        if (history != null)
            for (int i = 0; i < history.length(); i++) {
                JSONObject entry = history.optJSONObject(i);
                if (day.toString().equals(entry.optString("date")))
                    events.put(entry.optString("reminderId"), entry);
            }
        int paused = 0;
        for (JSONObject row : rows) {
            Reminder r = ReminderStore.reminder(row);
            if (day.isBefore(
                            LocalDate.parse(
                                    row.optString(
                                            "createdDate",
                                            now.atZone(zone).toLocalDate().toString())))
                    || !r.plan.occurs(day, r.daysMask)) continue;
            if (ReminderStore.paused(row, day)
                    || (!r.enabled && row.optJSONArray("pauses") == null)) {
                paused++;
                continue;
            }
            if (events.containsKey(r.id)) continue;
            JSONObject entry = new JSONObject();
            ReminderStore.put(entry, "reminderId", r.id);
            ReminderStore.put(entry, "parentId", row.optString("parentId", r.id));
            ReminderStore.put(entry, "title", r.title);
            ReminderStore.put(entry, "routine", r.routine);
            String outcome =
                    ReminderStore.done(row, day)
                            ? "DONE"
                            : ReminderStore.skipped(row, day)
                                    ? "SKIPPED"
                                    : day.isBefore(now.atZone(zone).toLocalDate())
                                                    && ReminderSchedule.followup(r, day, zone)
                                                            .plus(Duration.ofHours(6))
                                                            .isBefore(now)
                                            ? "MISSED"
                                            : "OPEN";
            ReminderStore.put(entry, "outcome", outcome);
            events.put(r.id, entry);
        }
        Map<String, RoutineProgress> groups = new HashMap<>();
        for (JSONObject entry : events.values()) {
            String name = entry.optString("routine"),
                    id = entry.optString("parentId", entry.optString("reminderId"));
            String key = name.isEmpty() ? "id:" + id : "routine:" + Reminder.routineKey(name);
            RoutineProgress group = groups.get(key);
            if (group == null) {
                group = new RoutineProgress(name.isEmpty() ? entry.optString("title") : name);
                groups.put(key, group);
            }
            group.expected++;
            switch (entry.optString("outcome", "DONE")) {
                case "DONE":
                    group.done++;
                    break;
                case "SKIPPED":
                    group.skipped++;
                    break;
                case "MISSED":
                    group.missed++;
                    break;
                default:
                    group.open++;
            }
        }
        return new ConsistencyDay(day, groups.values(), paused);
    }
}
