package com.shadowphoenix.dietlog.discipline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.*;

import java.time.*;
import java.util.*;

/** Single durable checkpoint, separate from diary/profile/cloud schemas. */
public final class ReminderStore {
    static final Object LOCK = new Object();
    static final String KEY = "state";
    private final SharedPreferences prefs;

    /** Snapshot of a completed activity; date is its original reminder day. */
    public static final class Completion {
        public final String reminderId, title;
        public final Reminder.Category category;
        public final LocalDate date;

        /** Epoch milliseconds, or zero when migrated from older completion flags. */
        public final long completedAt;

        private Completion(JSONObject entry) {
            reminderId = entry.optString("reminderId");
            title = entry.optString("title");
            category = Reminder.Category.valueOf(entry.optString("category"));
            date = LocalDate.parse(entry.optString("date"));
            completedAt = entry.optLong("completedAt", 0);
        }
    }

    public ReminderStore(Context context) {
        prefs = context.getSharedPreferences("dietlog_discipline", Context.MODE_PRIVATE);
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public List<Reminder> list() {
        synchronized (LOCK) {
            JSONObject rows = rows(read());
            List<Reminder> result = new ArrayList<>();
            for (Iterator<String> it = rows.keys(); it.hasNext(); )
                result.add(reminder(object(rows, it.next())));
            result.sort(
                    Comparator.comparingInt((Reminder r) -> r.hour * 60 + r.minute)
                            .thenComparing(r -> r.title));
            return result;
        }
    }

    public Reminder find(String id) {
        synchronized (LOCK) {
            JSONObject row = rows(read()).optJSONObject(id);
            return row == null ? null : reminder(row);
        }
    }

    public void save(Reminder r) {
        Objects.requireNonNull(r);
        synchronized (LOCK) {
            JSONObject state = read(), rows = rows(state);
            if (!rows.has(r.id) && rows.length() >= 200)
                throw new IllegalArgumentException("Keep at most 200 reminders");
            JSONObject old = rows.optJSONObject(r.id), row = encode(r);
            put(row, "revision", UUID.randomUUID().toString());
            put(row, "done", old == null ? new JSONArray() : old.optJSONArray("done"));
            if (old != null && old.optJSONObject("deliveries") != null)
                put(row, "deliveries", old.optJSONObject("deliveries"));
            put(rows, r.id, row);
            write(state);
        }
    }

    public void delete(String id) {
        synchronized (LOCK) {
            JSONObject state = read();
            rows(state).remove(id);
            write(state);
        }
    }

    public boolean isCompleted(String id, LocalDate date) {
        synchronized (LOCK) {
            JSONObject row = rows(read()).optJSONObject(id);
            return row != null && done(row, date);
        }
    }

    public Set<String> completedForDate(LocalDate date) {
        Objects.requireNonNull(date);
        synchronized (LOCK) {
            JSONObject reminders = rows(read());
            Set<String> result = new HashSet<>();
            for (Iterator<String> it = reminders.keys(); it.hasNext(); ) {
                String id = it.next();
                if (done(object(reminders, id), date)) result.add(id);
            }
            return result;
        }
    }

    public List<Completion> history(LocalDate date) {
        Objects.requireNonNull(date);
        if (date.isBefore(LocalDate.now().minusDays(364))) return Collections.emptyList();
        synchronized (LOCK) {
            JSONArray entries = read().optJSONArray("history");
            List<Completion> result = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (date.toString().equals(entry.optString("date")))
                    result.add(new Completion(entry));
            }
            result.sort(
                    Comparator.comparingLong((Completion c) -> c.completedAt)
                            .reversed()
                            .thenComparing(c -> c.title)
                            .thenComparing(c -> c.reminderId));
            return result;
        }
    }

    /** Yesterday's still-open notification, including a follow-up after midnight. */
    public LocalDate outstandingDate(String id, LocalDate today) {
        Objects.requireNonNull(today);
        synchronized (LOCK) {
            return outstandingDate(rows(read()).optJSONObject(id), today);
        }
    }

    public Map<String, LocalDate> outstandingDates(LocalDate today) {
        Objects.requireNonNull(today);
        synchronized (LOCK) {
            JSONObject reminders = rows(read());
            Map<String, LocalDate> result = new HashMap<>();
            for (Iterator<String> it = reminders.keys(); it.hasNext(); ) {
                String id = it.next();
                LocalDate date = outstandingDate(object(reminders, id), today);
                if (date != null) result.put(id, date);
            }
            return result;
        }
    }

    private static LocalDate outstandingDate(JSONObject row, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        if (row == null || !row.optBoolean("enabled") || done(row, yesterday)) return null;
        for (String key : Arrays.asList("displayed", "pending", "snoozed")) {
            JSONObject occurrence = row.optJSONObject(key);
            if (occurrence != null && yesterday.toString().equals(occurrence.optString("date")))
                return yesterday;
        }
        return null;
    }

    public void setCompleted(String id, LocalDate date, boolean complete) {
        Objects.requireNonNull(date);
        synchronized (LOCK) {
            JSONObject state = read(), row = rows(state).optJSONObject(id);
            if (row == null) return;
            complete(state, row, date, complete);
            write(state);
        }
    }

    static boolean done(JSONObject row, LocalDate date) {
        JSONArray days = row.optJSONArray("done");
        if (days != null)
            for (int i = 0; i < days.length(); i++)
                if (date.toString().equals(days.optString(i))) return true;
        return false;
    }

    static void complete(JSONObject row, LocalDate date, boolean value) {
        TreeSet<String> dates = new TreeSet<>();
        JSONArray previous = row.optJSONArray("done");
        if (previous != null)
            for (int i = 0; i < previous.length(); i++) dates.add(previous.optString(i));
        if (value) dates.add(date.toString());
        else dates.remove(date.toString());
        while (dates.size() > 90) dates.pollFirst();
        put(row, "done", new JSONArray(dates));
        for (String key : Arrays.asList("pending", "primaryPending", "displayed", "snoozed")) {
            JSONObject occurrence = row.optJSONObject(key);
            if (occurrence != null && date.toString().equals(occurrence.optString("date")))
                row.remove(key);
        }
    }

    /** Updates completion and its activity log in the same durable JSON checkpoint. */
    static void complete(JSONObject state, JSONObject row, LocalDate date, boolean value) {
        ensureHistory(state);
        JSONArray previous = state.optJSONArray("history"), entries = new JSONArray();
        boolean found = false;
        for (int i = 0; i < previous.length(); i++) {
            JSONObject entry = previous.optJSONObject(i);
            boolean matches =
                    row.optString("id").equals(entry.optString("reminderId"))
                            && date.toString().equals(entry.optString("date"));
            if (matches) found = true;
            if (!matches || value) entries.put(entry);
        }
        if (value && !found) entries.put(historyEntry(row, date, System.currentTimeMillis()));
        put(state, "history", entries);
        complete(row, date, value);
    }

    private static JSONObject historyEntry(JSONObject row, LocalDate date, long completedAt) {
        JSONObject entry = new JSONObject();
        put(entry, "reminderId", row.optString("id"));
        put(entry, "title", row.optString("title"));
        put(entry, "category", row.optString("category"));
        put(entry, "date", date.toString());
        put(entry, "completedAt", completedAt);
        return entry;
    }

    private static void ensureHistory(JSONObject state) {
        if (state.optInt("historyVersion", 0) == 0) {
            JSONArray entries = new JSONArray();
            JSONObject reminders = rows(state);
            for (Iterator<String> it = reminders.keys(); it.hasNext(); ) {
                JSONObject row = object(reminders, it.next());
                JSONArray days = row.optJSONArray("done");
                Set<String> migrated = new HashSet<>();
                if (days == null) continue;
                for (int i = 0; i < days.length(); i++) {
                    String date = days.optString(i);
                    if (!migrated.add(date)) continue;
                    try {
                        entries.put(historyEntry(row, LocalDate.parse(date), 0));
                    } catch (DateTimeException invalidLegacyDate) {
                        // Invalid old flags cannot identify a daily activity.
                    }
                }
            }
            put(state, "history", entries);
            put(state, "historyVersion", 1);
            trimHistory(state);
        }
        if (state.optInt("historyVersion", -1) != 1 || state.optJSONArray("history") == null)
            throw new IllegalStateException("Unsupported reminder history");
    }

    /** Retain at most one rolling year of activities and 2,000 entries. */
    private static void trimHistory(JSONObject state) {
        LocalDate firstDay = LocalDate.now().minusDays(364);
        JSONArray entries = state.optJSONArray("history");
        List<JSONObject> retained = new ArrayList<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) throw new IllegalStateException("Invalid reminder history");
            LocalDate date;
            try {
                date = LocalDate.parse(entry.optString("date"));
            } catch (DateTimeException invalidDate) {
                throw new IllegalStateException("Invalid reminder history", invalidDate);
            }
            if (!date.isBefore(firstDay)) retained.add(entry);
        }
        retained.sort(
                Comparator.comparing((JSONObject e) -> e.optString("date"))
                        .thenComparingLong(e -> e.optLong("completedAt", 0))
                        .thenComparing(e -> e.optString("reminderId")));
        int from = Math.max(0, retained.size() - 2000);
        put(state, "history", new JSONArray(retained.subList(from, retained.size())));
    }

    JSONObject read() {
        try {
            JSONObject state =
                    new JSONObject(prefs.getString(KEY, "{\"schema\":1,\"reminders\":{}}"));
            if (state.optInt("schema", -1) != 1 || state.optJSONObject("reminders") == null)
                throw new IllegalStateException("Unsupported reminder storage");
            ensureHistory(state);
            return state;
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to read reminders", e);
        }
    }

    void write(JSONObject state) {
        ensureHistory(state);
        trimHistory(state);
        String before = prefs.getString(KEY, null);
        if (prefs.edit().putString(KEY, state.toString()).commit()) return;
        SharedPreferences.Editor rollback = prefs.edit();
        if (before == null) rollback.remove(KEY);
        else rollback.putString(KEY, before);
        rollback.commit();
        throw new IllegalStateException("Unable to save reminders. Please retry.");
    }

    static JSONObject rows(JSONObject state) {
        return object(state, "reminders");
    }

    static JSONObject object(JSONObject j, String key) {
        try {
            return j.getJSONObject(key);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid reminder storage", e);
        }
    }

    static void put(JSONObject j, String key, Object value) {
        try {
            j.put(key, value);
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid reminder storage", e);
        }
    }

    static Reminder reminder(JSONObject j) {
        return new Reminder(
                j.optString("id"),
                j.optString("title"),
                j.optString("notes"),
                Reminder.Category.valueOf(j.optString("category")),
                j.optInt("hour", -1),
                j.optInt("minute", -1),
                j.optInt("days", 0),
                j.optBoolean("enabled"),
                j.optString("meal"));
    }

    private static JSONObject encode(Reminder r) {
        JSONObject j = new JSONObject();
        put(j, "id", r.id);
        put(j, "title", r.title);
        put(j, "notes", r.notes);
        put(j, "category", r.category.name());
        put(j, "hour", r.hour);
        put(j, "minute", r.minute);
        put(j, "days", r.daysMask);
        put(j, "enabled", r.enabled);
        put(j, "meal", r.mealKey);
        return j;
    }
}
