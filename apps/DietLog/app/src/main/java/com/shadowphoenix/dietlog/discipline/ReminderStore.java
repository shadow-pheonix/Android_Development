package com.shadowphoenix.dietlog.discipline;

import android.content.Context;
import android.content.SharedPreferences;
import java.time.*;
import java.util.*;
import org.json.*;

/** Single durable checkpoint, separate from diary/profile/cloud schemas. */
public final class ReminderStore {
    static final Object LOCK = new Object();
    static final String KEY = "state";
    private final SharedPreferences prefs;

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

    public void setCompleted(String id, LocalDate date, boolean complete) {
        Objects.requireNonNull(date);
        synchronized (LOCK) {
            JSONObject state = read(), row = rows(state).optJSONObject(id);
            if (row == null) return;
            complete(row, date, complete);
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
        for (String key : Arrays.asList("pending", "displayed")) {
            JSONObject occurrence = row.optJSONObject(key);
            if (occurrence != null && date.toString().equals(occurrence.optString("date")))
                row.remove(key);
        }
    }

    JSONObject read() {
        try {
            JSONObject state =
                    new JSONObject(prefs.getString(KEY, "{\"schema\":1,\"reminders\":{}}"));
            if (state.optInt("schema", -1) != 1 || state.optJSONObject("reminders") == null)
                throw new IllegalStateException("Unsupported reminder storage");
            return state;
        } catch (JSONException e) {
            throw new IllegalStateException("Unable to read reminders", e);
        }
    }

    void write(JSONObject state) {
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
