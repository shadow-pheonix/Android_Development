package com.shadowphoenix.dietlog.discipline;

import org.json.*;

import java.time.*;
import java.util.*;

/**
 * Optional local suggestions based exclusively on actual Done timestamps, never misses or skips.
 */
public final class TimingSuggestion {
    public final String reminderId, parentId, title, token;
    public final LocalTime current, proposed;
    public final int sampleDays;

    private TimingSuggestion(
            Reminder r, String parentId, LocalTime proposed, int days, String token) {
        this.reminderId = r.id;
        this.parentId = parentId;
        this.title = r.title;
        this.current = LocalTime.of(r.hour, r.minute);
        this.proposed = proposed;
        this.sampleDays = days;
        this.token = token;
    }

    static List<TimingSuggestion> build(JSONObject state, Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        Map<String, Map<LocalDate, JSONObject>> samples = new HashMap<>();
        JSONArray history = state.optJSONArray("history");
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (!entry.optString("outcome", "DONE").equals("DONE")
                    || entry.optLong("completedAt") <= 0
                    || entry.optLong("completedAt") > now.toEpochMilli()) continue;
            LocalDate date = LocalDate.parse(entry.optString("date"));
            if (date.isBefore(today.minusDays(29)) || date.isAfter(today)) continue;
            Map<LocalDate, JSONObject> dates =
                    samples.computeIfAbsent(entry.optString("reminderId"), k -> new TreeMap<>());
            JSONObject previous = dates.get(date);
            if (previous == null || previous.optLong("completedAt") < entry.optLong("completedAt"))
                dates.put(date, entry);
        }
        List<TimingSuggestion> result = new ArrayList<>();
        JSONObject dismissed = state.optJSONObject("timingDismissed");
        for (JSONObject row : ReminderStore.allRows(state)) {
            Reminder r = ReminderStore.reminder(row);
            if (!r.enabled || ReminderStore.paused(row, today)) continue;
            Map<LocalDate, JSONObject> dates = samples.get(r.id);
            if (dates == null
                    || dates.size() < 6
                    || ReminderSchedule.next(r, now, zone, d -> false) == null) continue;
            if (dismissed != null && dismissed.optLong(r.id) > now.toEpochMilli()) continue;
            List<Integer> minutes = new ArrayList<>();
            JSONArray evidence = new JSONArray();
            for (JSONObject entry : dates.values()) {
                LocalTime time =
                        Instant.ofEpochMilli(entry.optLong("completedAt"))
                                .atZone(zone)
                                .toLocalTime();
                minutes.add(time.getHour() * 60 + time.getMinute());
                evidence.put(entry.optLong("completedAt"));
            }
            int current = r.hour * 60 + r.minute, median = minutes.get(0), best = Integer.MAX_VALUE;
            for (int candidate : minutes) {
                int cost = 0;
                for (int value : minutes) cost += distance(candidate, value);
                if (cost < best
                        || (cost == best
                                && distance(candidate, current) < distance(median, current))) {
                    best = cost;
                    median = candidate;
                }
            }
            int proposed = ((median + 2) / 5 * 5) % 1440, concentrated = 0;
            for (int value : minutes) if (distance(proposed, value) <= 30) concentrated++;
            if (concentrated * 4 < minutes.size() * 3 || distance(proposed, current) < 20) continue;
            LocalTime next = LocalTime.of(proposed / 60, proposed % 60);
            String parentId = row.optString("parentId", r.id);
            JSONObject parent = ReminderStore.rows(state).optJSONObject(parentId);
            Reminder main = ReminderStore.reminder(parent);
            boolean conflict = false;
            for (Reminder occurrence : main.occurrences())
                if (!occurrence.id.equals(r.id)
                        && occurrence.hour == next.getHour()
                        && occurrence.minute == next.getMinute()) conflict = true;
            if (conflict) continue;
            JSONObject approval = new JSONObject();
            ReminderStore.put(approval, "revision", row.optString("revision"));
            ReminderStore.put(approval, "id", r.id);
            ReminderStore.put(approval, "current", current);
            ReminderStore.put(approval, "next", proposed);
            ReminderStore.put(approval, "evidence", evidence);
            ReminderStore.put(approval, "zone", zone.getId());
            result.add(
                    new TimingSuggestion(
                            r, parentId, next, dates.size(), ReminderBackup.digest(approval)));
        }
        result.sort(Comparator.comparing(s -> s.title));
        return result;
    }

    static int distance(int a, int b) {
        int d = Math.abs(a - b);
        return Math.min(d, 1440 - d);
    }
}
