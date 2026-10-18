package com.shadowphoenix.dietlog.discipline;

import static org.junit.Assert.*;

import android.content.*;
import java.time.*;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class ReminderStoreTest {
  private static final class Memory implements SharedPreferences {
    final Map<String, Object> data = new LinkedHashMap<>();
    boolean failCommit;

    public Map<String, ?> getAll() {
      return new HashMap<>(data);
    }

    public String getString(String k, String d) {
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

    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

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
        id, "Morning routine", "Private notes", Reminder.Category.SKIN, 8, 15, 127, true, "");
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
    assertEquals(90, ReminderStore.rows(s.read()).optJSONObject("a").optJSONArray("done").length());
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
    store.save(r("a"));
    JSONObject state = store.read(),
        row = ReminderStore.rows(state).optJSONObject("a"),
        pending = new JSONObject();
    String revision = row.optString("revision"), date = LocalDate.now().toString();
    long epoch = System.currentTimeMillis() - 1000;
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
    assertNotEquals(date, row.optJSONObject("pending").optString("date"));
    String checkpoint = memory.getString("state", null);
    ReminderScheduler.fire(context, alarm);
    assertEquals(checkpoint, memory.getString("state", null));
    assertFalse(store.isCompleted("a", LocalDate.now()));
  }
}
