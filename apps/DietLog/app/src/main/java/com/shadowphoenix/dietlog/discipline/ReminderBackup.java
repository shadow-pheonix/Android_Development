package com.shadowphoenix.dietlog.discipline;

import org.json.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Portable source data only. No OS alarm identities, notification tokens, or editor drafts. */
public final class ReminderBackup {
    private ReminderBackup() {}

    private static final List<String> FIELDS =
            Arrays.asList(
                    "id",
                    "title",
                    "notes",
                    "category",
                    "hour",
                    "minute",
                    "days",
                    "enabled",
                    "meal",
                    "plan",
                    "routine",
                    "createdDate",
                    "pauses",
                    "done",
                    "skipped");

    static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid Discipline backup", e);
        }
    }

    public static JSONObject source(JSONObject state) {
        JSONObject out = new JSONObject(), reminders = new JSONObject();
        for (Iterator<String> it = ReminderStore.rows(state).keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject row = ReminderStore.rows(state).optJSONObject(id);
            ReminderStore.occurrenceRows(row);
            ReminderStore.put(reminders, id, cleanRow(row));
        }
        ReminderStore.put(out, "schema", 1);
        ReminderStore.put(out, "reminders", reminders);
        for (String key : Arrays.asList("history", "outcomeUpdates", "deleted"))
            ReminderStore.put(
                    out,
                    key,
                    state.has(key)
                            ? state.opt(key)
                            : key.equals("history") ? new JSONArray() : new JSONObject());
        return copy(out);
    }

    private static JSONObject cleanRow(JSONObject row) {
        JSONObject clean = new JSONObject();
        for (String key : FIELDS) if (row.has(key)) ReminderStore.put(clean, key, row.opt(key));
        ReminderStore.put(
                clean,
                "done",
                row.optJSONArray("done") == null ? new JSONArray() : row.optJSONArray("done"));
        ReminderStore.put(
                clean,
                "skipped",
                row.optJSONArray("skipped") == null
                        ? new JSONArray()
                        : row.optJSONArray("skipped"));
        JSONObject slots = new JSONObject(), previous = row.optJSONObject("slots");
        if (previous != null)
            for (Iterator<String> it = previous.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject slot = previous.optJSONObject(id);
                JSONObject encoded = new JSONObject();
                for (String key : Arrays.asList("done", "skipped", "createdDate"))
                    if (slot.has(key)) ReminderStore.put(encoded, key, slot.opt(key));
                ReminderStore.put(slots, id, encoded);
            }
        ReminderStore.put(clean, "slots", slots);
        return clean;
    }

    public static void validate(JSONObject data) {
        try {
            validateContents(data);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid Discipline backup", invalid);
        }
    }

    private static void validateContents(JSONObject data) {
        if (data == null || data.optInt("schema") != 1 || data.optJSONObject("reminders") == null)
            throw new IllegalArgumentException("Unsupported Discipline backup");
        JSONObject rows = ReminderStore.rows(data);
        if (rows.length() > 200
                || data.optJSONArray("history") == null
                || data.optJSONArray("history").length() > 2000)
            throw new IllegalArgumentException("Discipline backup exceeds its record limit");
        for (Iterator<String> it = rows.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject row = rows.optJSONObject(id);
            Reminder r = ReminderStore.reminder(row);
            if (!id.equals(r.id) || (id.contains("~") || id.contains("|")) || id.length() > 80)
                throw new IllegalArgumentException("Invalid reminder identity");
            LocalDate.parse(row.optString("createdDate"));
            JSONObject slots = row.optJSONObject("slots");
            if (slots != null && slots.length() > 7)
                throw new IllegalArgumentException("Too many reminder times");
            for (String key : Arrays.asList("done", "skipped"))
                validateDates(row.optJSONArray(key), 365);
            JSONArray pauses = row.optJSONArray("pauses");
            if (pauses != null) {
                if (pauses.length() > 1000)
                    throw new IllegalArgumentException("Too many pause intervals");
                for (int i = 0; i < pauses.length(); i++) {
                    JSONObject pause = pauses.optJSONObject(i);
                    if (LocalDate.parse(pause.optString("until"))
                            .isBefore(LocalDate.parse(pause.optString("from"))))
                        throw new IllegalArgumentException("Invalid pause interval");
                }
            }
            if (slots != null) {
                Set<String> valid = new HashSet<>();
                for (Reminder occurrence : r.occurrences()) valid.add(occurrence.id);
                for (Iterator<String> si = slots.keys(); si.hasNext(); ) {
                    String sid = si.next();
                    if (sid.equals(id) || !valid.contains(sid))
                        throw new IllegalArgumentException("Invalid scheduled time identity");
                    JSONObject slot = slots.optJSONObject(sid);
                    LocalDate.parse(slot.optString("createdDate"));
                    validateDates(slot.optJSONArray("done"), 365);
                    validateDates(slot.optJSONArray("skipped"), 365);
                }
            }
        }
        JSONArray history = data.optJSONArray("history");
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i);
            if (entry == null
                    || entry.optString("reminderId").isEmpty()
                    || entry.optString("title").length() > 120)
                throw new IllegalArgumentException("Invalid activity record");
            if (entry.optString("reminderId").length() > 100
                    || entry.optString("parentId").length() > 80
                    || entry.optLong("completedAt") < 0)
                throw new IllegalArgumentException("Invalid activity identity or time");
            Reminder.routineName(entry.optString("routine"));
            if (!entry.optString("scheduledTime").isEmpty())
                LocalTime.parse(entry.optString("scheduledTime"));
            LocalDate.parse(entry.optString("date"));
            Reminder.Category.valueOf(entry.optString("category"));
            ReminderStore.Outcome.valueOf(entry.optString("outcome", "DONE"));
        }
        JSONObject updates = data.optJSONObject("outcomeUpdates"),
                deleted = data.optJSONObject("deleted");
        if (updates != null) {
            if (updates.length() > 5000)
                throw new IllegalArgumentException("Too many activity updates");
            for (Iterator<String> it = updates.keys(); it.hasNext(); ) {
                String key = it.next();
                int separator = key.lastIndexOf('|');
                if (separator < 1 || key.length() > 112)
                    throw new IllegalArgumentException("Invalid activity identity");
                LocalDate.parse(key.substring(separator + 1));
                if (updates.optJSONObject(key) == null
                        || updates.optJSONObject(key).optLong("at") < 0)
                    throw new IllegalArgumentException("Invalid activity update");
                String value = updates.optJSONObject(key).optString("outcome");
                if (!Arrays.asList("DONE", "SKIPPED", "OPEN").contains(value))
                    throw new IllegalArgumentException("Invalid activity outcome");
            }
        }
        if (deleted != null && deleted.length() > 2000)
            throw new IllegalArgumentException("Too many deleted reminders");
    }

    private static void validateDates(JSONArray dates, int max) {
        if (dates == null) return;
        if (dates.length() > max) throw new IllegalArgumentException("Too many occurrence dates");
        for (int i = 0; i < dates.length(); i++) LocalDate.parse(dates.optString(i));
    }

    public static final class Preview {
        public final JSONObject merged;
        public final List<String> details;
        public final int added, matched, removed, activities;
        public final boolean changed;

        Preview(
                JSONObject merged,
                List<String> details,
                int added,
                int matched,
                int removed,
                int activities,
                boolean changed) {
            this.merged = merged;
            this.details = Collections.unmodifiableList(details);
            this.added = added;
            this.matched = matched;
            this.removed = removed;
            this.activities = activities;
            this.changed = changed;
        }

        public String description() {
            return added
                    + " reminders to add · "
                    + matched
                    + " matches to merge · "
                    + removed
                    + " deletion records honored.\n"
                    + activities
                    + " activity records after merge. Matched reminders keep local settings. Saved"
                    + " deletions are honored; newer Done/Skip/Undo records win.\n\n"
                    + String.join("\n", details);
        }
    }

    public static Preview merge(JSONObject local, JSONObject remote) {
        validate(local);
        validate(remote);
        JSONObject result = copy(local),
                rows = ReminderStore.rows(result),
                deleted = result.optJSONObject("deleted");
        if (deleted == null) {
            deleted = new JSONObject();
            ReminderStore.put(result, "deleted", deleted);
        }
        JSONObject remoteDeleted = remote.optJSONObject("deleted");
        if (remoteDeleted != null)
            for (String id : sortedKeys(remoteDeleted))
                ReminderStore.put(
                        deleted, id, Math.max(deleted.optLong(id), remoteDeleted.optLong(id)));
        Map<String, String> identities = new HashMap<>();
        List<String> details = new ArrayList<>();
        int added = 0, matched = 0, removed = 0;
        for (String id : sortedKeys(deleted)) {
            JSONObject existing = rows.optJSONObject(id);
            if (existing != null) {
                removed++;
                details.add("Delete · " + existing.optString("title") + " (saved deletion record)");
            }
            rows.remove(id);
        }
        JSONObject remoteRows = ReminderStore.rows(remote);
        for (String id : sortedKeys(remoteRows)) {
            JSONObject row = remoteRows.optJSONObject(id);
            String target = rows.has(id) ? id : null;
            if (deleted.has(id)) {
                removed++;
                details.add("Keep deleted · " + row.optString("title"));
                identities.put(id, id);
                continue;
            }
            if (target == null)
                for (String existing : sortedKeys(rows))
                    if (signature(rows.optJSONObject(existing)).equals(signature(row))) {
                        target = existing;
                        break;
                    }
            if (target == null) {
                if (rows.length() >= 200)
                    throw new IllegalArgumentException(
                            "Restoring would exceed 200 reminders. Remove reminders first");
                target = id;
                ReminderStore.put(rows, id, copy(row));
                added++;
                details.add("Add · " + row.optString("title"));
            } else {
                matched++;
                details.add("Merge history · " + row.optString("title") + " (keep local schedule)");
            }
            identities.put(id, target);
        }
        if (deleted.length() > 2000) {
            List<String> keys = sortedKeys(deleted);
            final JSONObject removals = deleted;
            keys.sort(
                    Comparator.comparingLong((String k) -> removals.optLong(k))
                            .thenComparing(k -> k));
            for (int i = 0; i < keys.size() - 2000; i++) deleted.remove(keys.get(i));
        }
        // Union remote activity with local-first ties and explicit Undo tombstones.
        Map<String, JSONObject> entries = new HashMap<>(), updates = new HashMap<>();
        collect(remote, identities, entries, updates, false);
        collect(local, Collections.emptyMap(), entries, updates, true);
        JSONArray history = new JSONArray();
        JSONObject encodedUpdates = new JSONObject();
        List<String> updateKeys = new ArrayList<>(updates.keySet());
        updateKeys.sort(Comparator.comparingLong(k -> updates.get(k).optLong("at")));
        for (String key :
                updateKeys.subList(Math.max(0, updateKeys.size() - 5000), updateKeys.size()))
            ReminderStore.put(encodedUpdates, key, updates.get(key));
        List<String> eventKeys = new ArrayList<>(entries.keySet());
        Collections.sort(eventKeys);
        for (String key : eventKeys) {
            JSONObject entry = entries.get(key), update = updates.get(key);
            String outcome =
                    update == null
                            ? entry.optString("outcome", "DONE")
                            : update.optString("outcome");
            if (outcome.equals("OPEN")) continue;
            ReminderStore.put(entry, "outcome", outcome);
            history.put(entry);
        }
        // Retain the same one-year, 2,000-entry limit as live storage.
        JSONArray retained = new JSONArray();
        LocalDate first = LocalDate.now().minusDays(364);
        List<JSONObject> historyRows = new ArrayList<>();
        for (int i = 0; i < history.length(); i++)
            if (!LocalDate.parse(history.optJSONObject(i).optString("date")).isBefore(first))
                historyRows.add(history.optJSONObject(i));
        historyRows.sort(
                Comparator.comparing((JSONObject e) -> e.optString("date"))
                        .thenComparingLong(e -> e.optLong("completedAt"))
                        .thenComparing(e -> e.optString("reminderId")));
        for (int i = Math.max(0, historyRows.size() - 2000); i < historyRows.size(); i++)
            retained.put(historyRows.get(i));
        ReminderStore.put(result, "history", retained);
        ReminderStore.put(result, "outcomeUpdates", encodedUpdates);
        // Rebuild each occurrence's flags from outcome updates without sharing a daily flag across
        // times.
        for (JSONObject row : ReminderStore.allRows(result)) {
            String id = row.optString("id");
            TreeSet<String> done = dates(row.optJSONArray("done")),
                    skipped = dates(row.optJSONArray("skipped"));
            for (Map.Entry<String, JSONObject> event : updates.entrySet())
                if (event.getKey().startsWith(id + "|")) {
                    String date = event.getKey().substring(id.length() + 1),
                            outcome = event.getValue().optString("outcome");
                    done.remove(date);
                    skipped.remove(date);
                    if (outcome.equals("DONE")) done.add(date);
                    if (outcome.equals("SKIPPED")) skipped.add(date);
                }
            while (done.size() > 90) done.pollFirst();
            while (skipped.size() > 365) skipped.pollFirst();
            ReminderStore.put(row, "done", new JSONArray(done));
            ReminderStore.put(row, "skipped", new JSONArray(skipped));
        }
        result = source(result);
        return new Preview(
                result,
                details,
                added,
                matched,
                removed,
                retained.length(),
                !digest(source(local)).equals(digest(result)));
    }

    private static void collect(
            JSONObject data,
            Map<String, String> ids,
            Map<String, JSONObject> entries,
            Map<String, JSONObject> updates,
            boolean local) {
        JSONObject sourceUpdates = data.optJSONObject("outcomeUpdates");
        if (sourceUpdates != null)
            for (String key : sortedKeys(sourceUpdates)) {
                int split = key.lastIndexOf('|');
                String mapped = mapId(key.substring(0, split), ids) + key.substring(split);
                choose(updates, mapped, copy(sourceUpdates.optJSONObject(key)), local);
            }
        JSONArray history = data.optJSONArray("history");
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = copy(history.optJSONObject(i));
            String id = mapId(entry.optString("reminderId"), ids);
            ReminderStore.put(entry, "reminderId", id);
            ReminderStore.put(entry, "parentId", mapId(entry.optString("parentId", id), ids));
            String key = id + "|" + entry.optString("date");
            JSONObject previous = entries.get(key);
            long at = entry.optLong("completedAt");
            if (previous == null
                    || at > previous.optLong("completedAt")
                    || (local && at == previous.optLong("completedAt"))) entries.put(key, entry);
            // Legacy entries have no explicit operation clock.
            if (sourceUpdates == null
                    || !sourceUpdates.has(
                            history.optJSONObject(i).optString("reminderId")
                                    + "|"
                                    + entry.optString("date"))) {
                JSONObject inferred = new JSONObject();
                ReminderStore.put(inferred, "at", at);
                ReminderStore.put(inferred, "outcome", entry.optString("outcome", "DONE"));
                if (!"MISSED".equals(inferred.optString("outcome")))
                    choose(updates, key, inferred, local);
            }
        }
    }

    private static void choose(
            Map<String, JSONObject> map, String key, JSONObject next, boolean local) {
        JSONObject old = map.get(key);
        if (old == null
                || next.optLong("at") > old.optLong("at")
                || (local && next.optLong("at") == old.optLong("at"))) map.put(key, next);
    }

    private static String mapId(String id, Map<String, String> ids) {
        int split = id.indexOf('~');
        String parent = split < 0 ? id : id.substring(0, split);
        return ids.getOrDefault(parent, parent) + (split < 0 ? "" : id.substring(split));
    }

    private static TreeSet<String> dates(JSONArray array) {
        TreeSet<String> out = new TreeSet<>();
        if (array != null) for (int i = 0; i < array.length(); i++) out.add(array.optString(i));
        return out;
    }

    private static String signature(JSONObject row) {
        JSONObject config = new JSONObject();
        for (String key :
                Arrays.asList(
                        "title",
                        "notes",
                        "category",
                        "hour",
                        "minute",
                        "days",
                        "meal",
                        "plan",
                        "routine")) ReminderStore.put(config, key, row.opt(key));
        ReminderStore.put(config, "title", row.optString("title").trim().toLowerCase(Locale.ROOT));
        ReminderStore.put(config, "routine", Reminder.routineKey(row.optString("routine")));
        return digest(config);
    }

    private static List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = object.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        return keys;
    }

    public static String digest(JSONObject data) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical(data).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String canonical(Object value) {
        if (value instanceof JSONObject) {
            JSONObject j = (JSONObject) value;
            StringBuilder b = new StringBuilder("{");
            for (String key : sortedKeys(j))
                b.append(JSONObject.quote(key))
                        .append(':')
                        .append(canonical(j.opt(key)))
                        .append(',');
            return b.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) b.append(canonical(a.opt(i))).append(',');
            return b.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
}
