package com.shadowphoenix.dietlog.discipline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.*;

import java.time.*;
import java.util.*;

/** Single durable checkpoint, separate from diary/profile/cloud schemas. */
public final class ReminderStore {
    public enum Outcome {
        DONE,
        SKIPPED,
        MISSED
    }

    static final Object LOCK = new Object();
    static final String KEY = "state";
    private final SharedPreferences prefs;

    /** Snapshot of a completed activity; date is its original reminder day. */
    public static final class Completion {
        public final String reminderId, title, scheduledTime, parentId;
        public final Reminder.Category category;
        public final LocalDate date;

        /** Epoch milliseconds, or zero when migrated from older completion flags. */
        public final long completedAt;

        public final Outcome outcome;

        private Completion(JSONObject entry) {
            reminderId = entry.optString("reminderId");
            parentId = entry.optString("parentId", reminderId);
            scheduledTime = entry.optString("scheduledTime");
            title = entry.optString("title");
            category = Reminder.Category.valueOf(entry.optString("category"));
            date = LocalDate.parse(entry.optString("date"));
            completedAt = entry.optLong("completedAt", 0);
            outcome = Outcome.valueOf(entry.optString("outcome", "DONE"));
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
            JSONObject row = resolve(read(), id);
            return row == null ? null : reminder(row);
        }
    }

    public void save(Reminder r) {
        Objects.requireNonNull(r);
        if (r.id.length() > 80 || r.id.contains("~"))
            throw new IllegalArgumentException("Invalid reminder ID");
        synchronized (LOCK) {
            JSONObject state = read(), rows = rows(state);
            if (!rows.has(r.id) && rows.length() >= 200)
                throw new IllegalArgumentException("Keep at most 200 reminders");
            archiveMissed(state, r.id);
            JSONObject old = rows.optJSONObject(r.id), row = encode(r);
            put(row, "revision", UUID.randomUUID().toString());
            put(
                    row,
                    "createdDate",
                    old == null
                            ? LocalDate.now().toString()
                            : old.optString("createdDate", LocalDate.now().toString()));
            for (String key : Arrays.asList("skipped", "pauses", "slots"))
                if (old != null && old.has(key)) put(row, key, old.opt(key));
            put(row, "done", old == null ? new JSONArray() : old.optJSONArray("done"));
            if (old != null && old.optJSONObject("deliveries") != null)
                put(row, "deliveries", old.optJSONObject("deliveries"));
            if (old == null || old.optBoolean("enabled") != r.enabled) {
                JSONArray pauses = row.optJSONArray("pauses");
                if (pauses == null) pauses = new JSONArray();
                for (int i = 0; i < pauses.length(); i++) {
                    JSONObject pause = pauses.optJSONObject(i);
                    if (LocalDate.parse(pause.optString("until")).isAfter(LocalDate.now()))
                        put(pause, "until", LocalDate.now().toString());
                }
                if (!r.enabled) {
                    JSONObject pause = new JSONObject();
                    put(pause, "from", LocalDate.now().toString());
                    put(pause, "until", "9999-12-31");
                    pauses.put(pause);
                }
                put(row, "pauses", pauses);
            }
            JSONObject activeSlots = new JSONObject(), previousSlots = row.optJSONObject("slots");
            if (previousSlots != null)
                for (Reminder occurrence : r.occurrences())
                    if (!occurrence.id.equals(r.id) && previousSlots.has(occurrence.id))
                        put(activeSlots, occurrence.id, previousSlots.optJSONObject(occurrence.id));
            put(row, "slots", activeSlots);
            put(rows, r.id, row);
            write(state);
        }
    }

    public void delete(String id) {
        synchronized (LOCK) {
            JSONObject state = read();
            archiveMissed(state, id);
            rows(state).remove(id);
            write(state);
        }
    }

    public boolean isCompleted(String id, LocalDate date) {
        synchronized (LOCK) {
            JSONObject row = resolve(read(), id);
            return row != null && done(row, date);
        }
    }

    public Set<String> completedForDate(LocalDate date) {
        Objects.requireNonNull(date);
        synchronized (LOCK) {
            JSONObject reminders = rows(read());
            Set<String> result = new HashSet<>();
            for (JSONObject row : allRowsFromParents(reminders))
                if (done(row, date)) result.add(row.optString("id"));
            return result;
        }
    }

    public List<Completion> history(LocalDate date) {
        Objects.requireNonNull(date);
        if (date.isBefore(LocalDate.now().minusDays(364))) return Collections.emptyList();
        synchronized (LOCK) {
            JSONObject snapshot = read();
            JSONArray entries = snapshot.optJSONArray("history");
            List<Completion> result = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (date.toString().equals(entry.optString("date")))
                    result.add(new Completion(entry));
            }
            for (JSONObject row : allRows(snapshot)) {
                String created = row.optString("createdDate", LocalDate.now().toString());
                boolean recorded = false;
                for (Completion c : result)
                    if (c.reminderId.equals(row.optString("id"))) recorded = true;
                if (!recorded
                        && !date.isBefore(LocalDate.parse(created))
                        && date.isBefore(LocalDate.now())
                        && scheduled(row, date)
                        && missedByNow(row, date)
                        && !paused(row, date)) {
                    JSONObject missed = historyEntry(row, date, 0);
                    put(missed, "outcome", "MISSED");
                    result.add(new Completion(missed));
                }
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
            return outstandingDate(resolve(read(), id), today);
        }
    }

    public Map<String, LocalDate> outstandingDates(LocalDate today) {
        Objects.requireNonNull(today);
        synchronized (LOCK) {
            JSONObject reminders = rows(read());
            Map<String, LocalDate> result = new HashMap<>();
            for (JSONObject row : allRowsFromParents(reminders)) {
                String id = row.optString("id");
                LocalDate date = outstandingDate(row, today);
                if (date != null) result.put(id, date);
            }
            return result;
        }
    }

    private static LocalDate outstandingDate(JSONObject row, LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        if (row == null
                || !row.optBoolean("enabled")
                || settled(row, yesterday)
                || paused(row, today)) return null;
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
            JSONObject state = read(), row = resolve(state, id);
            if (row == null) return;
            if (complete && (skipped(row, date) || paused(row, LocalDate.now()))) return;
            complete(state, row, date, complete);
            write(state);
        }
    }

    private static void archiveMissed(JSONObject state, String id) {
        JSONObject parent = rows(state).optJSONObject(id);
        if (parent == null) return;
        for (JSONObject row : occurrenceRows(parent)) archiveRow(state, row);
    }

    private static void archiveRow(JSONObject state, JSONObject row) {
        String id = row.optString("id");
        LocalDate first = LocalDate.parse(row.optString("createdDate", LocalDate.now().toString()));
        if (first.isBefore(LocalDate.now().minusDays(364))) first = LocalDate.now().minusDays(364);
        Set<String> recorded = new HashSet<>();
        JSONArray history = state.optJSONArray("history");
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (id.equals(entry.optString("reminderId"))) recorded.add(entry.optString("date"));
        }
        for (LocalDate day = first; day.isBefore(LocalDate.now()); day = day.plusDays(1)) {
            if (!recorded.contains(day.toString())
                    && scheduled(row, day)
                    && missedByNow(row, day)
                    && !paused(row, day)) {
                JSONObject missed = historyEntry(row, day, 0);
                put(missed, "outcome", "MISSED");
                history.put(missed);
            }
        }
    }

    public boolean isSkipped(String id, LocalDate date) {
        synchronized (LOCK) {
            JSONObject row = resolve(read(), id);
            return row != null && skipped(row, date);
        }
    }

    public String pausedUntil(String id) {
        synchronized (LOCK) {
            return pausedUntilRow(resolve(read(), id), LocalDate.now());
        }
    }

    private static String pausedUntilRow(JSONObject row, LocalDate date) {
        JSONArray pauses = row == null ? null : row.optJSONArray("pauses");
        if (pauses != null)
            for (int i = 0; i < pauses.length(); i++) {
                JSONObject pause = pauses.optJSONObject(i);
                if (!date.isBefore(LocalDate.parse(pause.optString("from")))
                        && date.isBefore(LocalDate.parse(pause.optString("until"))))
                    return pause.optString("until");
            }
        return null;
    }

    public static final class OccurrenceState {
        public final boolean done, skipped;
        public final String pausedUntil;

        OccurrenceState(JSONObject row, LocalDate date) {
            done = done(row, date);
            skipped = skipped(row, date);
            pausedUntil = pausedUntilRow(row, date);
        }
    }

    public Map<String, OccurrenceState> statesForDate(LocalDate date) {
        synchronized (LOCK) {
            Map<String, OccurrenceState> states = new HashMap<>();
            for (JSONObject row : allRows(read()))
                states.put(row.optString("id"), new OccurrenceState(row, date));
            return states;
        }
    }

    public void pauseUntil(String id, LocalDate until) {
        if (until != null && !until.isAfter(LocalDate.now()))
            throw new IllegalArgumentException("Choose a future resume date");
        synchronized (LOCK) {
            JSONObject state = read(), row = resolve(state, id);
            if (row == null) return;
            JSONArray pauses = row.optJSONArray("pauses");
            if (pauses == null) pauses = new JSONArray();
            for (int i = 0; i < pauses.length(); i++) {
                JSONObject pause = pauses.optJSONObject(i);
                if (LocalDate.parse(pause.optString("until")).isAfter(LocalDate.now()))
                    put(pause, "until", LocalDate.now().toString());
            }
            if (until != null) {
                JSONObject pause = new JSONObject();
                put(pause, "from", LocalDate.now().toString());
                put(pause, "until", until.toString());
                pauses.put(pause);
            }
            JSONArray retained = new JSONArray();
            for (int i = 0; i < pauses.length(); i++)
                if (!LocalDate.parse(pauses.optJSONObject(i).optString("until"))
                        .isBefore(LocalDate.now().minusDays(364)))
                    retained.put(pauses.optJSONObject(i));
            put(row, "pauses", retained);
            put(row, "enabled", true);
            put(row, "revision", UUID.randomUUID().toString());
            for (String key : Arrays.asList("pending", "primaryPending", "displayed", "snoozed"))
                row.remove(key);
            write(state);
        }
    }

    public void setSkipped(String id, LocalDate date, boolean skip) {
        synchronized (LOCK) {
            JSONObject state = read(), row = resolve(state, id);
            if (row == null || done(row, date)) return;
            JSONArray previous = row.optJSONArray("skipped");
            TreeSet<String> dates = new TreeSet<>();
            if (previous != null)
                for (int i = 0; i < previous.length(); i++) dates.add(previous.optString(i));
            if (skip) dates.add(date.toString());
            else dates.remove(date.toString());
            while (dates.size() > 365) dates.pollFirst();
            put(row, "skipped", new JSONArray(dates));
            complete(state, row, date, false);
            if (skip) {
                JSONObject entry = historyEntry(row, date, System.currentTimeMillis());
                put(entry, "outcome", "SKIPPED");
                state.optJSONArray("history").put(entry);
            }
            write(state);
        }
    }

    private static boolean missedByNow(JSONObject row, LocalDate date) {
        return ReminderSchedule.followup(reminder(row), date, ZoneId.systemDefault())
                .plus(Duration.ofHours(6))
                .isBefore(Instant.now());
    }

    static boolean scheduled(JSONObject row, LocalDate date) {
        Reminder r = reminder(row);
        return r.plan.occurs(date, r.daysMask);
    }

    static boolean paused(JSONObject row, LocalDate date) {
        JSONArray pauses = row.optJSONArray("pauses");
        if (pauses != null)
            for (int i = 0; i < pauses.length(); i++) {
                JSONObject pause = pauses.optJSONObject(i);
                if (!date.isBefore(LocalDate.parse(pause.optString("from")))
                        && date.isBefore(LocalDate.parse(pause.optString("until")))) return true;
            }
        return false;
    }

    static boolean skipped(JSONObject row, LocalDate date) {
        JSONArray dates = row.optJSONArray("skipped");
        if (dates != null)
            for (int i = 0; i < dates.length(); i++)
                if (date.toString().equals(dates.optString(i))) return true;
        return false;
    }

    static boolean settled(JSONObject row, LocalDate date) {
        return done(row, date) || skipped(row, date);
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
            boolean existingDone = "DONE".equals(entry.optString("outcome", "DONE"));
            if (matches && existingDone) found = true;
            if (!matches || (value && existingDone)) entries.put(entry);
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
        put(
                entry,
                "scheduledTime",
                String.format(
                        java.util.Locale.ROOT,
                        "%02d:%02d",
                        row.optInt("hour"),
                        row.optInt("minute")));
        put(entry, "parentId", row.optString("parentId", row.optString("id")));
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
            for (Iterator<String> it = rows(state).keys(); it.hasNext(); ) {
                JSONObject row = rows(state).optJSONObject(it.next());
                if (!row.has("createdDate")) put(row, "createdDate", LocalDate.now().toString());
            }
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

    static List<JSONObject> occurrenceRows(JSONObject parent) {
        List<JSONObject> result = new ArrayList<>();
        result.add(parent);
        JSONObject slots = parent.optJSONObject("slots");
        if (slots == null) {
            slots = new JSONObject();
            put(parent, "slots", slots);
        }
        Reminder source = reminder(parent);
        for (Reminder occurrence : source.occurrences()) {
            if (occurrence.id.equals(source.id)) continue;
            JSONObject row = slots.optJSONObject(occurrence.id);
            if (row == null) {
                row = new JSONObject();
                put(slots, occurrence.id, row);
                put(row, "createdDate", LocalDate.now().toString());
            }
            JSONObject config = encode(occurrence);
            for (Iterator<String> it = config.keys(); it.hasNext(); ) {
                String key = it.next();
                put(row, key, config.opt(key));
            }
            for (String key : Arrays.asList("revision", "pauses"))
                if (parent.has(key)) put(row, key, parent.opt(key));
            put(row, "parentId", source.id);
            if (!row.has("done")) put(row, "done", new JSONArray());
            result.add(row);
        }
        return result;
    }

    private static List<JSONObject> allRowsFromParents(JSONObject parents) {
        JSONObject state = new JSONObject();
        put(state, "reminders", parents);
        return allRows(state);
    }

    static List<JSONObject> allRows(JSONObject state) {
        List<JSONObject> result = new ArrayList<>();
        JSONObject parents = rows(state);
        for (Iterator<String> it = parents.keys(); it.hasNext(); )
            result.addAll(occurrenceRows(parents.optJSONObject(it.next())));
        return result;
    }

    static JSONObject resolve(JSONObject state, String id) {
        if (id == null) return null;
        JSONObject direct = rows(state).optJSONObject(id);
        if (direct != null) return direct;
        for (JSONObject row : allRows(state)) if (id.equals(row.optString("id"))) return row;
        return null;
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
                j.optString("meal"),
                ReminderPlan.parse(j.optJSONObject("plan")));
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
        put(j, "plan", r.plan.json());
        return j;
    }
}
