package com.shadowphoenix.dietlog.discipline;

import static org.junit.Assert.*;

import android.content.*;

import org.json.*;
import org.junit.Test;

import java.time.*;
import java.util.*;

public class ReminderStoreTest {
    private static final class Memory implements SharedPreferences {
        final Map<String, Object> data = new LinkedHashMap<>();
        boolean failCommit;
        int reads;

        public Map<String, ?> getAll() {
            return new HashMap<>(data);
        }

        public String getString(String k, String d) {
            reads++;
            return (String) data.getOrDefault(k, d);
        }

        public Set<String> getStringSet(String k, Set<String> d) {
            return d;
        }

        public int getInt(String k, int d) {
            return d;
        }

        public long getLong(String k, long d) {
            return d;
        }

        public float getFloat(String k, float d) {
            return d;
        }

        public boolean getBoolean(String k, boolean d) {
            return d;
        }

        public boolean contains(String k) {
            return data.containsKey(k);
        }

        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener l) {}

        public Editor edit() {
            return new Editor() {
                final Map<String, Object> changes = new LinkedHashMap<>();
                final Set<String> removed = new HashSet<>();
                boolean clear;

                public Editor putString(String k, String v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putStringSet(String k, Set<String> v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putInt(String k, int v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putLong(String k, long v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putFloat(String k, float v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor putBoolean(String k, boolean v) {
                    changes.put(k, v);
                    return this;
                }

                public Editor remove(String k) {
                    removed.add(k);
                    return this;
                }

                public Editor clear() {
                    clear = true;
                    return this;
                }

                public boolean commit() {
                    if (clear) data.clear();
                    for (String k : removed) data.remove(k);
                    data.putAll(changes);
                    return !failCommit;
                }

                public void apply() {
                    commit();
                }
            };
        }
    }

    private ReminderStore store(Memory memory) {
        return new ReminderStore(
                new ContextWrapper(null) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return memory;
                    }
                });
    }

    private Reminder r(String id) {
        return new Reminder(
                id,
                "Morning routine",
                "Private notes",
                Reminder.Category.SKIN,
                8,
                15,
                127,
                true,
                "");
    }

    @Test
    public void roundTripEditDeleteAndDailyCompletion() {
        Memory m = new Memory();
        ReminderStore s = store(m);
        s.save(r("a"));
        Reminder read = store(m).find("a");
        assertEquals("Private notes", read.notes);
        assertEquals(Reminder.Category.SKIN, read.category);
        LocalDate date = LocalDate.of(2026, 10, 19);
        s.setCompleted("a", date, true);
        assertTrue(store(m).isCompleted("a", date));
        assertFalse(s.isCompleted("a", date.plusDays(1)));
        s.save(new Reminder("a", "Edited", "", Reminder.Category.FOOD, 12, 30, 31, false, "lunch"));
        assertTrue(s.isCompleted("a", date));
        assertEquals("lunch", s.find("a").mealKey);
        assertFalse(s.find("a").enabled);
        s.setCompleted("a", date, false);
        assertFalse(s.isCompleted("a", date));
        s.delete("a");
        assertNull(s.find("a"));
    }

    @Test
    public void failedSaveRollsBackEvenWhenPreferencesUpdatedMemory() {
        Memory m = new Memory();
        ReminderStore s = store(m);
        s.save(r("a"));
        String before = m.getString("state", null);
        m.failCommit = true;
        try {
            s.save(r("b"));
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(before, m.getString("state", null));
        assertNull(store(m).find("b"));
        try {
            s.delete("a");
            fail();
        } catch (IllegalStateException expected) {
        }
        assertNotNull(s.find("a"));
        try {
            s.setCompleted("a", LocalDate.now(), true);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertFalse(s.isCompleted("a", LocalDate.now()));
    }

    @Test
    public void historyAndReminderCountsAreBounded() {
        Memory m = new Memory();
        ReminderStore s = store(m);
        s.save(r("a"));
        LocalDate day = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 110; i++) s.setCompleted("a", day.plusDays(i), true);
        assertFalse(s.isCompleted("a", day));
        assertTrue(s.isCompleted("a", day.plusDays(109)));
        assertEquals(
                90, ReminderStore.rows(s.read()).optJSONObject("a").optJSONArray("done").length());
        for (int i = 1; i < 200; i++) s.save(r("id" + i));
        try {
            s.save(r("overflow"));
            fail();
        } catch (IllegalArgumentException expected) {
        }
        assertEquals(200, s.list().size());
    }

    @Test
    public void editsInvalidateOccurrenceAndOldIdentityCannotReturn() {
        ReminderStore s = store(new Memory());
        s.save(r("a"));
        JSONObject state = s.read(), row = ReminderStore.rows(state).optJSONObject("a");
        String revision = row.optString("revision");
        ReminderStore.put(row, "pending", new JSONObject());
        ReminderStore.put(row, "displayed", new JSONObject());
        s.write(state);
        s.save(r("a"));
        row = ReminderStore.rows(s.read()).optJSONObject("a");
        assertNotEquals(revision, row.optString("revision"));
        assertFalse(row.has("pending"));
        assertFalse(row.has("displayed"));
        s.delete("a");
        s.save(r("a"));
        assertNotEquals(
                revision, ReminderStore.rows(s.read()).optJSONObject("a").optString("revision"));
    }

    @Test
    public void completionClearsOnlyMatchingDateOccurrence() {
        ReminderStore s = store(new Memory());
        s.save(r("a"));
        JSONObject state = s.read(), row = ReminderStore.rows(state).optJSONObject("a");
        JSONObject current = new JSONObject(), future = new JSONObject();
        ReminderStore.put(current, "date", "2026-10-19");
        ReminderStore.put(future, "date", "2026-10-20");
        ReminderStore.put(row, "displayed", current);
        ReminderStore.put(row, "pending", future);
        s.write(state);
        s.setCompleted("a", LocalDate.of(2026, 10, 19), true);
        row = ReminderStore.rows(s.read()).optJSONObject("a");
        assertFalse(row.has("displayed"));
        assertEquals("2026-10-20", row.optJSONObject("pending").optString("date"));
    }

    @Test
    public void supplementsAppendWithoutChangingExistingCategoryOrdinals() {
        assertEquals(0, Reminder.Category.FOOD.ordinal());
        assertEquals(3, Reminder.Category.CUSTOM.ordinal());
        assertEquals(4, Reminder.Category.SUPPLEMENTS.ordinal());
        Memory memory = new Memory();
        ReminderStore store = store(memory);
        store.save(
                new Reminder(
                        "vitamin",
                        "Vitamin",
                        "",
                        Reminder.Category.SUPPLEMENTS,
                        8,
                        0,
                        127,
                        true,
                        ""));
        assertEquals(Reminder.Category.SUPPLEMENTS, store(memory).find("vitamin").category);
        assertEquals(
                "SUPPLEMENTS",
                ReminderStore.rows(store.read()).optJSONObject("vitamin").optString("category"));
    }

    @Test
    public void activityHistorySnapshotsSurviveRenameDeleteAndReload() {
        Memory memory = new Memory();
        ReminderStore store = store(memory);
        LocalDate originalDay = LocalDate.now().minusDays(1);
        store.save(r("a"));
        long before = System.currentTimeMillis();
        store.setCompleted("a", originalDay, true);
        long after = System.currentTimeMillis();
        ReminderStore.Completion logged = store(memory).history(originalDay).get(0);
        assertEquals("a", logged.reminderId);
        assertEquals("Morning routine", logged.title);
        assertEquals(Reminder.Category.SKIN, logged.category);
        assertEquals(originalDay, logged.date);
        assertTrue(logged.completedAt >= before && logged.completedAt <= after);
        assertTrue(store.history(LocalDate.now()).isEmpty());
        store.save(
                new Reminder(
                        "a", "Renamed", "", Reminder.Category.SUPPLEMENTS, 9, 0, 127, true, ""));
        store.setCompleted("a", originalDay, true);
        assertEquals(1, store.history(originalDay).size());
        assertEquals(logged.completedAt, store.history(originalDay).get(0).completedAt);
        assertEquals("Morning routine", store.history(originalDay).get(0).title);
        store.delete("a");
        ReminderStore.Completion retained = store(memory).history(originalDay).get(0);
        assertEquals("Morning routine", retained.title);
        assertEquals(Reminder.Category.SKIN, retained.category);
        assertFalse(store.read().optJSONArray("history").toString().contains("Private notes"));
    }

    @Test
    public void undoAndRedoUpdateCompletionAndActivityHistoryTogether() {
        Memory memory = new Memory();
        ReminderStore store = store(memory);
        LocalDate day = LocalDate.now();
        store.save(r("a"));
        store.setCompleted("a", day, true);
        store.setCompleted("a", day, false);
        assertFalse(store(memory).isCompleted("a", day));
        assertTrue(store(memory).history(day).isEmpty());
        store.save(
                new Reminder(
                        "a", "Updated activity", "", Reminder.Category.HAIR, 9, 0, 127, true, ""));
        store.setCompleted("a", day, true);
        assertEquals(1, store.history(day).size());
        assertEquals("Updated activity", store.history(day).get(0).title);
        assertEquals(Reminder.Category.HAIR, store.history(day).get(0).category);
    }

    @Test
    public void failedCompletionCannotLeavePartialActivityLogOrUndo() {
        Memory memory = new Memory();
        ReminderStore store = store(memory);
        LocalDate day = LocalDate.now();
        store.save(r("a"));
        String checkpoint = memory.getString("state", null);
        memory.failCommit = true;
        try {
            store.setCompleted("a", day, true);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(checkpoint, memory.getString("state", null));
        assertFalse(store.isCompleted("a", day));
        assertTrue(store.history(day).isEmpty());
        memory.failCommit = false;
        store.setCompleted("a", day, true);
        checkpoint = memory.getString("state", null);
        memory.failCommit = true;
        try {
            store.setCompleted("a", day, false);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(checkpoint, memory.getString("state", null));
        assertTrue(store.isCompleted("a", day));
        assertEquals(1, store.history(day).size());
    }

    @Test
    public void legacyCompletionFlagsMigrateOnceWithoutInventingTimes() {
        Memory memory = new Memory();
        ReminderStore store = store(memory);
        LocalDate day = LocalDate.now().minusDays(2);
        store.save(r("a"));
        JSONObject state = store.read(), row = ReminderStore.rows(state).optJSONObject("a");
        state.remove("historyVersion");
        state.remove("history");
        ReminderStore.put(
                row,
                "done",
                new JSONArray(Arrays.asList(day.toString(), day.toString(), "invalid")));
        store.write(state);
        assertEquals(1, store(memory).history(day).size());
        assertEquals(0, store.history(day).get(0).completedAt);
        // Saving a renamed reminder persists migration using the original snapshot.
        store.save(new Reminder("a", "New title", "", Reminder.Category.HAIR, 8, 0, 127, true, ""));
        assertEquals("Morning routine", store(memory).history(day).get(0).title);
        assertEquals(Reminder.Category.SKIN, store.history(day).get(0).category);
        state = store.read();
        row = ReminderStore.rows(state).optJSONObject("a");
        ReminderStore.put(row, "done", new JSONArray(Arrays.asList(day.plusDays(1).toString())));
        store.write(state);
        assertTrue(store(memory).history(day.plusDays(1)).isEmpty());
    }

    @Test
    public void activityHistoryOutlivesNinetyDayFlagsAndExpiresAfterOneYear() {
        ReminderStore store = store(new Memory());
        LocalDate today = LocalDate.now();
        store.save(r("a"));
        for (int i = 109; i >= 0; i--) store.setCompleted("a", today.minusDays(i), true);
        assertFalse(store.isCompleted("a", today.minusDays(109)));
        assertEquals(1, store.history(today.minusDays(109)).size());
        store.setCompleted("a", today.minusDays(364), true);
        store.setCompleted("a", today.minusDays(365), true);
        assertEquals(1, store.history(today.minusDays(364)).size());
        assertTrue(store.history(today.minusDays(365)).isEmpty());
    }

    @Test
    public void migrationBoundsGlobalHistoryToNewestTwoThousandActivities() {
        ReminderStore store = store(new Memory());
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 30; i++) store.save(r("id" + i));
        JSONObject state = store.read();
        state.remove("historyVersion");
        state.remove("history");
        for (Iterator<String> it = ReminderStore.rows(state).keys(); it.hasNext(); ) {
            JSONObject row = ReminderStore.rows(state).optJSONObject(it.next());
            JSONArray dates = new JSONArray();
            for (int i = 79; i >= 0; i--) dates.put(today.minusDays(i).toString());
            ReminderStore.put(row, "done", dates);
        }
        store.write(state);
        assertEquals(2000, store.read().optJSONArray("history").length());
        assertEquals(30, store.history(today).size());
        assertTrue(store.history(today.minusDays(79)).isEmpty());
    }

    @Test
    public void overnightActivityKeepsOriginalDayAndClearsOnlyItsOccurrences() {
        ReminderStore store = store(new Memory());
        LocalDate today = LocalDate.now(), yesterday = today.minusDays(1);
        store.save(r("a"));
        JSONObject state = store.read(), row = ReminderStore.rows(state).optJSONObject("a");
        JSONObject overnight = new JSONObject(),
                next = new JSONObject(),
                deliveries = new JSONObject();
        ReminderStore.put(overnight, "date", yesterday.toString());
        ReminderStore.put(next, "date", today.toString());
        ReminderStore.put(row, "displayed", overnight);
        ReminderStore.put(row, "snoozed", overnight);
        ReminderStore.put(row, "primaryPending", overnight);
        ReminderStore.put(row, "pending", next);
        ReminderStore.put(deliveries, yesterday.toString(), new JSONObject());
        ReminderStore.put(row, "deliveries", deliveries);
        store.write(state);
        assertEquals(yesterday, store.outstandingDate("a", today));
        store.setCompleted("a", yesterday, true);
        assertNull(store.outstandingDate("a", today));
        row = ReminderStore.rows(store.read()).optJSONObject("a");
        assertFalse(row.has("displayed"));
        assertFalse(row.has("snoozed"));
        assertFalse(row.has("primaryPending"));
        assertEquals(today.toString(), row.optJSONObject("pending").optString("date"));
        assertTrue(row.optJSONObject("deliveries").has(yesterday.toString()));
        assertEquals(1, store.history(yesterday).size());
        assertTrue(store.history(today).isEmpty());
        store.save(r("a"));
        assertTrue(
                ReminderStore.rows(store.read())
                        .optJSONObject("a")
                        .optJSONObject("deliveries")
                        .has(yesterday.toString()));
    }

    @Test
    public void dashboardOvernightLookupRejectsOlderFutureAndDisabledOccurrences() {
        ReminderStore store = store(new Memory());
        LocalDate today = LocalDate.now();
        store.save(r("a"));
        JSONObject state = store.read(), row = ReminderStore.rows(state).optJSONObject("a");
        JSONObject older = new JSONObject(), future = new JSONObject();
        ReminderStore.put(older, "date", today.minusDays(2).toString());
        ReminderStore.put(future, "date", today.plusDays(1).toString());
        ReminderStore.put(row, "displayed", older);
        ReminderStore.put(row, "pending", future);
        store.write(state);
        assertNull(store.outstandingDate("a", today));
        ReminderStore.put(older, "date", today.minusDays(1).toString());
        ReminderStore.put(row, "enabled", false);
        store.write(state);
        assertNull(store.outstandingDate("a", today));
        assertNull(store.outstandingDate("missing", today));
    }

    @Test
    public void dashboardBatchQueriesReadStateOnceAndReturnIndependentCollections() {
        Memory memory = new Memory();
        ReminderStore store = store(memory);
        LocalDate today = LocalDate.now(), yesterday = today.minusDays(1);
        store.save(r("done"));
        store.save(r("overnight"));
        store.save(r("open"));
        store.setCompleted("done", today, true);
        JSONObject state = store.read(), row = ReminderStore.rows(state).optJSONObject("overnight");
        JSONObject shown = new JSONObject();
        ReminderStore.put(shown, "date", yesterday.toString());
        ReminderStore.put(row, "displayed", shown);
        store.write(state);
        memory.reads = 0;
        Set<String> completed = store.completedForDate(today);
        assertEquals(1, memory.reads);
        assertEquals(Collections.singleton("done"), completed);
        completed.clear();
        assertTrue(store.isCompleted("done", today));
        memory.reads = 0;
        Map<String, LocalDate> outstanding = store.outstandingDates(today);
        assertEquals(1, memory.reads);
        assertEquals(Collections.singletonMap("overnight", yesterday), outstanding);
        outstanding.clear();
        assertEquals(yesterday, store.outstandingDate("overnight", today));
    }

    @Test
    public void expiredHistoryIsHiddenOnReadAndPrunedAtNextDurableWrite() {
        Memory memory = new Memory();
        ReminderStore store = store(memory);
        LocalDate yesterday = LocalDate.now().minusDays(1),
                expired = LocalDate.now().minusDays(365);
        store.save(r("a"));
        store.setCompleted("a", yesterday, true);
        JSONObject state = store.read();
        ReminderStore.put(
                state.optJSONArray("history").optJSONObject(0), "date", expired.toString());
        memory.data.put("state", state.toString());
        assertTrue(store.history(expired).isEmpty());
        assertEquals(1, store.read().optJSONArray("history").length());
        store.save(r("a"));
        assertEquals(0, store.read().optJSONArray("history").length());
    }

    private Intent action(
            String id, String revision, String token, String date, long epoch, String action) {
        return new Intent() {
            @Override
            public String getAction() {
                return action;
            }

            @Override
            public String getStringExtra(String key) {
                switch (key) {
                    case "id":
                        return id;
                    case "revision":
                        return revision;
                    case "token":
                        return token;
                    case "date":
                        return date;
                    default:
                        return null;
                }
            }

            @Override
            public long getLongExtra(String key, long fallback) {
                return "epoch".equals(key) ? epoch : fallback;
            }
        };
    }

    @Test
    public void notificationActionsAreDurableAndRejectStaleDuplicates() {
        Memory m = new Memory();
        Context context =
                new ContextWrapper(null) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return m;
                    }
                };
        ReminderStore s = new ReminderStore(context);
        s.save(r("a"));
        JSONObject state = s.read(),
                row = ReminderStore.rows(state).optJSONObject("a"),
                shown = new JSONObject();
        String revision = row.optString("revision"), date = LocalDate.now().toString();
        long epoch = System.currentTimeMillis() - 1000;
        ReminderStore.put(shown, "revision", revision);
        ReminderStore.put(shown, "token", "shown");
        ReminderStore.put(shown, "date", date);
        ReminderStore.put(shown, "epoch", epoch);
        ReminderStore.put(row, "displayed", shown);
        s.write(state);
        Intent wrong = action("a", revision, "shown", date, epoch + 1, "DONE");
        ReminderScheduler.act(context, wrong);
        assertFalse(s.isCompleted("a", LocalDate.now()));
        Intent snooze = action("a", revision, "shown", date, epoch, "SNOOZE");
        ReminderScheduler.act(context, snooze);
        row = ReminderStore.rows(s.read()).optJSONObject("a");
        JSONObject pending = row.optJSONObject("pending");
        assertTrue(pending.optBoolean("snooze"));
        assertFalse(row.has("displayed"));
        assertTrue(pending.optLong("epoch") >= System.currentTimeMillis() + 14 * 60_000L);
        String token = pending.optString("token");
        ReminderScheduler.act(context, snooze);
        assertEquals(
                token,
                ReminderStore.rows(s.read())
                        .optJSONObject("a")
                        .optJSONObject("pending")
                        .optString("token"));
        Intent staleDone = action("a", revision, "shown", date, epoch, "DONE");
        ReminderScheduler.act(context, staleDone);
        assertFalse(s.isCompleted("a", LocalDate.now()));
        // A subsequent shown occurrence accepts Done once and cannot be snoozed afterwards.
        state = s.read();
        row = ReminderStore.rows(state).optJSONObject("a");
        row.remove("pending");
        ReminderStore.put(row, "displayed", shown);
        s.write(state);
        ReminderScheduler.act(context, staleDone);
        assertTrue(s.isCompleted("a", LocalDate.now()));
        assertEquals(1, s.history(LocalDate.now()).size());
        String checkpoint = m.getString("state", null);
        ReminderScheduler.act(context, staleDone);
        assertEquals(checkpoint, m.getString("state", null));
        ReminderScheduler.act(context, snooze);
        assertFalse(
                ReminderStore.rows(s.read())
                        .optJSONObject("a")
                        .optJSONObject("pending")
                        .optBoolean("snooze"));
    }

    @Test
    public void replacedDeletedAndPausedRemindersRejectOldAlarms() {
        Memory m = new Memory();
        Context context =
                new ContextWrapper(null) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return m;
                    }
                };
        ReminderStore s = new ReminderStore(context);
        s.save(r("a"));
        JSONObject state = s.read(),
                row = ReminderStore.rows(state).optJSONObject("a"),
                o = new JSONObject();
        String revision = row.optString("revision"), date = LocalDate.now().toString();
        long epoch = System.currentTimeMillis() - 1000;
        ReminderStore.put(o, "revision", revision);
        ReminderStore.put(o, "token", "alarm");
        ReminderStore.put(o, "date", date);
        ReminderStore.put(o, "epoch", epoch);
        ReminderStore.put(row, "pending", o);
        s.write(state);
        Intent alarm = action("a", revision, "alarm", date, epoch, "ALARM");
        s.save(r("a"));
        String checkpoint = m.getString("state", null);
        ReminderScheduler.fire(context, alarm);
        assertEquals(checkpoint, m.getString("state", null));
        s.delete("a");
        ReminderScheduler.fire(context, alarm);
        assertNull(s.find("a"));
        s.save(new Reminder("a", "Paused", "", Reminder.Category.CUSTOM, 8, 0, 127, false, ""));
        checkpoint = m.getString("state", null);
        ReminderScheduler.fire(context, alarm);
        assertEquals(checkpoint, m.getString("state", null));
    }

    @Test
    public void dashboardSnoozePersistsWithoutPriorNotificationAndCompletionBlocksIt() {
        Memory memory = new Memory();
        Context context =
                new ContextWrapper(null) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return memory;
                    }
                };
        ReminderStore store = new ReminderStore(context);
        store.save(r("a"));
        assertTrue(ReminderScheduler.snooze(context, "a", 10));
        JSONObject pending =
                ReminderStore.rows(new ReminderStore(context).read())
                        .optJSONObject("a")
                        .optJSONObject("pending");
        assertEquals(LocalDate.now().toString(), pending.optString("date"));
        assertTrue(pending.optBoolean("snooze"));
        long epoch = pending.optLong("epoch");
        assertTrue(epoch >= System.currentTimeMillis() + 9 * 60_000L);
        ReminderScheduler.rescheduleAll(context);
        assertEquals(
                epoch,
                ReminderStore.rows(store.read())
                        .optJSONObject("a")
                        .optJSONObject("pending")
                        .optLong("epoch"));
        store.setCompleted("a", LocalDate.now(), true);
        assertFalse(ReminderScheduler.snooze(context, "a", 15));
    }

    @Test
    public void deliveredAlarmAdvancesOnceAndDuplicateCannotReplaceNewOccurrence() {
        Memory memory = new Memory();
        Context context =
                new ContextWrapper(null) {
                    @Override
                    public SharedPreferences getSharedPreferences(String name, int mode) {
                        return memory;
                    }
                };
        ReminderStore store = new ReminderStore(context);
        LocalTime time = LocalTime.now();
        store.save(
                new Reminder(
                        "a",
                        "Due now",
                        "",
                        Reminder.Category.CUSTOM,
                        time.getHour(),
                        time.getMinute(),
                        127,
                        true,
                        ""));
        JSONObject state = store.read(),
                row = ReminderStore.rows(state).optJSONObject("a"),
                pending = new JSONObject();
        String revision = row.optString("revision"), date = LocalDate.now().toString();
        long epoch =
                LocalDate.now()
                        .atTime(time.getHour(), time.getMinute())
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
        ReminderStore.put(pending, "revision", revision);
        ReminderStore.put(pending, "token", "due");
        ReminderStore.put(pending, "date", date);
        ReminderStore.put(pending, "epoch", epoch);
        ReminderStore.put(row, "pending", pending);
        store.write(state);
        Intent alarm = action("a", revision, "due", date, epoch, "ALARM");
        ReminderScheduler.fire(context, alarm);
        row = ReminderStore.rows(store.read()).optJSONObject("a");
        assertEquals("due", row.optJSONObject("displayed").optString("token"));
        assertEquals(date, row.optJSONObject("pending").optString("date"));
        assertEquals("FOLLOWUP", row.optJSONObject("pending").optString("kind"));
        assertEquals(
                epoch + Duration.ofHours(1).toMillis(),
                row.optJSONObject("pending").optLong("epoch"));
        String checkpoint = memory.getString("state", null);
        ReminderScheduler.fire(context, alarm);
        assertEquals(checkpoint, memory.getString("state", null));
        assertFalse(store.isCompleted("a", LocalDate.now()));
    }

    @Test
    public void skipIsDistinctIdempotentUndoableAndBlocksCompletion() {
        ReminderStore s = store(new Memory());
        LocalDate day = LocalDate.now();
        s.save(r("a"));
        s.setSkipped("a", day, true);
        s.setSkipped("a", day, true);
        assertFalse(s.isCompleted("a", day));
        assertTrue(s.isSkipped("a", day));
        assertEquals(1, s.history(day).size());
        assertEquals(ReminderStore.Outcome.SKIPPED, s.history(day).get(0).outcome);
        s.setCompleted("a", day, true);
        assertFalse(s.isCompleted("a", day));
        s.setSkipped("a", day, false);
        assertTrue(s.history(day).isEmpty());
        s.setCompleted("a", day, true);
        assertTrue(s.isCompleted("a", day));
    }

    @Test
    public void datedPauseSuppressesDatesAndSchedulesAutomaticResume() {
        ReminderStore s = store(new Memory());
        s.save(r("a"));
        LocalDate today = LocalDate.now(), resume = today.plusDays(5);
        s.pauseUntil("a", resume);
        JSONObject row = ReminderStore.rows(s.read()).optJSONObject("a");
        assertTrue(ReminderStore.paused(row, today));
        assertTrue(ReminderStore.paused(row, resume.minusDays(1)));
        assertFalse(ReminderStore.paused(row, resume));
        assertEquals(resume.toString(), s.pausedUntil("a"));
        JSONObject next =
                ReminderScheduler.nextOccurrence(
                        row,
                        today.atStartOfDay(ZoneId.systemDefault()).toInstant(),
                        ZoneId.systemDefault());
        assertEquals(resume.toString(), next.optString("date"));
        s.setCompleted("a", today, true);
        assertFalse(s.isCompleted("a", today));
        s.pauseUntil("a", null);
        assertNull(s.pausedUntil("a"));
    }

    @Test
    public void missedActivityStartsAtCreationAndExcludesPausedDays() {
        ReminderStore s = store(new Memory());
        s.save(r("a"));
        LocalDate day = LocalDate.now().minusDays(2);
        assertTrue(s.history(day).isEmpty());
        JSONObject state = s.read(), row = ReminderStore.rows(state).optJSONObject("a");
        ReminderStore.put(row, "createdDate", day.toString());
        s.write(state);
        assertEquals(ReminderStore.Outcome.MISSED, s.history(day).get(0).outcome);
        JSONObject pause = new JSONObject();
        ReminderStore.put(pause, "from", day.toString());
        ReminderStore.put(pause, "until", LocalDate.now().toString());
        ReminderStore.put(row, "pauses", new JSONArray().put(pause));
        s.write(state);
        assertTrue(s.history(day).isEmpty());
    }

    @Test
    public void skipAndPauseFailuresRollBackAndLongPauseResumes() {
        Memory memory = new Memory();
        ReminderStore s = store(memory);
        s.save(r("a"));
        String before = memory.getString("state", null);
        memory.failCommit = true;
        try {
            s.setSkipped("a", LocalDate.now(), true);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(before, memory.getString("state", null));
        try {
            s.pauseUntil("a", LocalDate.now().plusYears(2));
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(before, memory.getString("state", null));
        memory.failCommit = false;
        LocalDate resume = LocalDate.now().plusYears(2);
        s.pauseUntil("a", resume);
        JSONObject row = ReminderStore.rows(s.read()).optJSONObject("a");
        JSONObject next =
                ReminderScheduler.nextOccurrence(row, Instant.now(), ZoneId.systemDefault());
        assertEquals(resume.toString(), next.optString("date"));
    }

    @Test
    public void multipleTimesHaveIndependentCompletionSkipDeliveryAndHistory() {
        Memory m = new Memory();
        ReminderStore s = store(m);
        ReminderPlan plan =
                new ReminderPlan(
                        ReminderPlan.Mode.WEEKDAYS,
                        null,
                        null,
                        1,
                        java.util.List.of(),
                        java.util.List.of(LocalTime.of(21, 30)),
                        25);
        Reminder main =
                new Reminder("a", "Routine", "", Reminder.Category.SKIN, 8, 0, 127, true, "", plan);
        s.save(main);
        String evening = main.occurrences().get(1).id;
        LocalDate date = LocalDate.now();
        assertNotEquals("a", evening);
        s.setCompleted("a", date, true);
        assertFalse(s.isCompleted(evening, date));
        s.setSkipped(evening, date, true);
        assertEquals(2, s.history(date).size());
        assertTrue(s.isCompleted("a", date));
        assertTrue(store(m).isSkipped(evening, date));
        s.setSkipped(evening, date, false);
        s.setCompleted(evening, date, true);
        assertEquals(2, s.history(date).size());
        assertEquals(2, s.completedForDate(date).size());
        assertEquals(25, store(m).find(evening).plan.followupMinutes);
        s.setCompleted("a", date, false);
        assertTrue(s.isCompleted(evening, date));
        s.save(main);
        assertTrue(store(m).isCompleted(evening, date));
    }

    @Test
    public void addedTimesDoNotInventEarlierMisses() {
        ReminderStore s = store(new Memory());
        s.save(r("a"));
        JSONObject state = s.read();
        ReminderStore.put(
                ReminderStore.rows(state).optJSONObject("a"),
                "createdDate",
                LocalDate.now().minusDays(3).toString());
        s.write(state);
        ReminderPlan plan =
                new ReminderPlan(
                        ReminderPlan.Mode.WEEKDAYS,
                        null,
                        null,
                        1,
                        java.util.List.of(),
                        java.util.List.of(LocalTime.of(21, 0)),
                        60);
        s.save(
                new Reminder(
                        "a", "Routine", "", Reminder.Category.CUSTOM, 8, 0, 127, true, "", plan));
        assertEquals(1, s.history(LocalDate.now().minusDays(2)).size());
    }

    @Test
    public void routineCreationIsAtomicAndStepsStayIndependent() {
        Memory m = new Memory();
        ReminderStore s = store(m);
        s.createRoutine("  Morning   routine ", java.util.List.of("Brush teeth", "Skincare"), 8, 0);
        assertEquals(2, s.list().size());
        assertEquals("Morning routine", s.list().get(0).routine);
        String a = s.list().get(0).id, b = s.list().get(1).id;
        s.setCompleted(a, LocalDate.now(), true);
        assertFalse(s.isCompleted(b, LocalDate.now()));
        assertEquals("Morning routine", s.history(LocalDate.now()).get(0).routine);
        try {
            s.createRoutine("morning routine", java.util.List.of("Third"), 9, 0);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        assertEquals(2, s.list().size());
        String before = m.getString("state", null);
        m.failCommit = true;
        try {
            s.createRoutine("Before bed", java.util.List.of("Read", "Sleep"), 22, 0);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(before, m.getString("state", null));
    }

    @Test
    public void routineMetadataAndExtraTimesSurviveEditingAndDeletion() {
        ReminderStore s = store(new Memory());
        ReminderPlan plan =
                new ReminderPlan(
                        ReminderPlan.Mode.WEEKDAYS,
                        null,
                        null,
                        1,
                        java.util.List.of(),
                        java.util.List.of(LocalTime.of(22, 0)),
                        60);
        s.createRoutine(
                "Before bed", java.util.List.of("Read"), 8, 0, plan, Reminder.Category.CUSTOM, 127);
        Reminder step = s.list().get(0);
        assertEquals("Before bed", step.occurrences().get(1).routine);
        String extra = step.occurrences().get(1).id;
        s.setCompleted(extra, LocalDate.now(), true);
        s.delete(step.id);
        assertEquals("Before bed", s.history(LocalDate.now()).get(0).routine);
        assertTrue(s.list().isEmpty());
    }

    @Test
    public void backupRoundTripStagesPreviewAndKeepsIndependentOccurrenceHistory() {
        ReminderStore source = store(new Memory());
        ReminderPlan plan =
                new ReminderPlan(
                        ReminderPlan.Mode.WEEKDAYS,
                        null,
                        null,
                        1,
                        List.of(),
                        List.of(LocalTime.of(22, 0)),
                        35);
        source.createRoutine(
                "Before bed", List.of("Read"), 8, 0, plan, Reminder.Category.CUSTOM, 127);
        Reminder reminder = source.list().get(0);
        String extra = reminder.occurrences().get(1).id;
        LocalDate day = LocalDate.now();
        source.setCompleted(reminder.id, day, true);
        source.setSkipped(extra, day, true);
        JSONObject state = source.read(),
                row = ReminderStore.rows(state).optJSONObject(reminder.id),
                pending = new JSONObject();
        ReminderStore.put(pending, "token", "not-a-backup-token");
        ReminderStore.put(row, "pending", pending);
        source.write(state);
        JSONObject backup = source.exportBackup();
        assertFalse(backup.toString().contains("not-a-backup-token"));
        ReminderStore target = store(new Memory());
        target.stageRestore(backup);
        assertTrue(target.list().isEmpty());
        ReminderStore.RestorePreview preview = target.previewRestore();
        assertNotNull(preview);
        assertTrue(preview.description.contains("1 reminders to add"));
        target.applyRestore(preview.token);
        assertEquals(1, target.list().size());
        assertEquals("Before bed", target.list().get(0).routine);
        assertEquals(35, target.find(extra).plan.followupMinutes);
        assertTrue(target.isCompleted(reminder.id, day));
        assertTrue(target.isSkipped(extra, day));
        assertEquals(2, target.history(day).size());
        assertFalse(target.hasPendingRestore());
        target.stageRestore(backup);
        assertFalse(target.hasPendingRestore());
    }

    @Test
    public void restoreDeduplicatesEquivalentRemindersAndMapsHistory() {
        ReminderStore local = store(new Memory()), remote = store(new Memory());
        LocalDate day = LocalDate.now();
        local.save(r("local"));
        remote.save(r("remote"));
        remote.setCompleted("remote", day, true);
        local.stageRestore(remote.exportBackup());
        assertTrue(local.previewRestore().description.contains("1 matches"));
        local.applyRestore(local.previewRestore().token);
        assertEquals(1, local.list().size());
        assertTrue(local.isCompleted("local", day));
        assertEquals("local", local.history(day).get(0).reminderId);
    }

    @Test
    public void staleBackupsCannotResurrectUndoOrDeletedReminders() {
        ReminderStore local = store(new Memory());
        LocalDate day = LocalDate.now();
        local.save(r("a"));
        local.setCompleted("a", day, true);
        JSONObject older = local.exportBackup();
        local.setCompleted("a", day, false);
        local.stageRestore(older);
        if (local.hasPendingRestore()) local.applyRestore(local.previewRestore().token);
        assertFalse(local.isCompleted("a", day));
        assertTrue(local.history(day).isEmpty());
        local.delete("a");
        local.stageRestore(older);
        if (local.hasPendingRestore()) local.applyRestore(local.previewRestore().token);
        assertNull(local.find("a"));
        ReminderStore fresh = store(new Memory());
        fresh.stageRestore(local.exportBackup());
        if (fresh.hasPendingRestore()) fresh.applyRestore(fresh.previewRestore().token);
        fresh.stageRestore(older);
        if (fresh.hasPendingRestore()) fresh.applyRestore(fresh.previewRestore().token);
        assertNull(fresh.find("a"));
    }

    @Test
    public void restorePreviewDetectsLocalChangesAndFailedApplyKeepsPendingData() {
        ReminderStore remote = store(new Memory());
        remote.save(r("a"));
        Memory memory = new Memory();
        ReminderStore local = store(memory);
        local.stageRestore(remote.exportBackup());
        String stale = local.previewRestore().token;
        local.save(
                new Reminder("b", "Different", "", Reminder.Category.CUSTOM, 10, 0, 127, true, ""));
        try {
            local.applyRestore(stale);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertNull(local.find("a"));
        String token = local.previewRestore().token;
        String before = memory.getString("state", null);
        memory.failCommit = true;
        try {
            local.applyRestore(token);
            fail();
        } catch (IllegalStateException expected) {
        }
        assertEquals(before, memory.getString("state", null));
        assertTrue(local.hasPendingRestore());
        memory.failCommit = false;
        local.applyRestore(token);
        assertEquals(2, local.list().size());
    }

    @Test
    public void restoreRetainsPauseDatesAndSkipsWithoutCompletingThem() {
        ReminderStore source = store(new Memory());
        source.save(r("a"));
        source.setSkipped("a", LocalDate.now(), true);
        source.pauseUntil("a", LocalDate.now().plusDays(5));
        ReminderStore target = store(new Memory());
        target.stageRestore(source.exportBackup());
        target.applyRestore(target.previewRestore().token);
        assertEquals(LocalDate.now().plusDays(5).toString(), target.pausedUntil("a"));
        assertTrue(target.isSkipped("a", LocalDate.now()));
        assertFalse(target.isCompleted("a", LocalDate.now()));
    }
}
