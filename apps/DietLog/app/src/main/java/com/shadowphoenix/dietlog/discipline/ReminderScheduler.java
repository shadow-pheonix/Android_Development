package com.shadowphoenix.dietlog.discipline;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.*;

/** Alarm identities contain immutable revision/occurrence tokens, never just a reusable row ID. */
public final class ReminderScheduler {
  public static final String CHANNEL_ID = "discipline_reminders";
  private static final String TAG_PREFIX = "discipline:";
  static final ExecutorService IO = Executors.newSingleThreadExecutor();

  private ReminderScheduler() {}

  public static boolean exactAllowed(Context context) {
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms());
  }

  public static boolean notificationsAllowed(Context context) {
    if (Build.VERSION.SDK_INT >= 33
        && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return false;
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null || !manager.areNotificationsEnabled()) return false;
    NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
    return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
  }

  public static void rescheduleAll(Context context) {
    synchronized (ReminderStore.LOCK) {
      reconcile(context);
    }
  }

  /**
   * Dashboard snooze applies to today; notification actions retain their original occurrence date.
   */
  public static boolean snooze(Context context, String id, int minutes) {
    if (minutes != 10 && minutes != 15)
      throw new IllegalArgumentException("Choose a 10 or 15 minute snooze");
    synchronized (ReminderStore.LOCK) {
      ReminderStore store = new ReminderStore(context);
      JSONObject state = store.read(), row = ReminderStore.rows(state).optJSONObject(id);
      if (row == null || !row.optBoolean("enabled")) return false;
      LocalDate today = LocalDate.now();
      Reminder reminder = ReminderStore.reminder(row);
      if ((reminder.daysMask & (1 << (today.getDayOfWeek().getValue() - 1))) == 0
          || ReminderStore.done(row, today)) return false;
      JSONObject shown = row.optJSONObject("displayed");
      if (shown == null || !today.toString().equals(shown.optString("date")))
        shown =
            occurrence(
                row,
                today,
                today
                    .atTime(reminder.hour, reminder.minute)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli(),
                false);
      snoozeRow(row, shown, minutes);
      store.write(state);
      cancelNotification(context, id);
      reconcile(context);
      return true;
    }
  }

  private static void snoozeRow(JSONObject row, JSONObject shown, int minutes) {
    LocalDate day = LocalDate.parse(shown.optString("date"));
    ReminderStore.put(
        row, "pending", occurrence(row, day, System.currentTimeMillis() + minutes * 60_000L, true));
    row.remove("displayed");
  }

  private static JSONObject occurrence(JSONObject row, LocalDate date, long epoch, boolean snooze) {
    JSONObject o = new JSONObject();
    ReminderStore.put(o, "token", UUID.randomUUID().toString());
    ReminderStore.put(o, "revision", row.optString("revision"));
    ReminderStore.put(o, "date", date.toString());
    ReminderStore.put(o, "epoch", epoch);
    ReminderStore.put(o, "snooze", snooze);
    return o;
  }

  private static void reconcile(Context context) {
    createChannel(context);
    ReminderStore store = new ReminderStore(context);
    JSONObject state = store.read(), rows = ReminderStore.rows(state);
    Instant now = Instant.now();
    ZoneId zone = ZoneId.systemDefault();
    List<AlarmChange> changes = new ArrayList<>();
    Set<String> validNotifications = new HashSet<>();
    JSONArray installed = new JSONArray();
    for (Iterator<String> it = rows.keys(); it.hasNext(); ) {
      String id = it.next();
      JSONObject row = ReminderStore.object(rows, id);
      Reminder r = ReminderStore.reminder(row);
      JSONObject old = row.optJSONObject("pending"),
          shown = row.optJSONObject("displayed"),
          next = null;
      if (r.enabled) {
        if (shown != null && !ReminderStore.done(row, LocalDate.parse(shown.optString("date"))))
          validNotifications.add(TAG_PREFIX + id);
        if (old != null
            && old.optBoolean("snooze")
            && old.optString("revision").equals(row.optString("revision"))
            && old.optLong("epoch") > now.toEpochMilli() - 86_400_000L
            && !ReminderStore.done(row, LocalDate.parse(old.optString("date")))) {
          next = old;
        } else if (old != null
            && !old.optBoolean("snooze")
            && old.optString("revision").equals(row.optString("revision"))
            && old.optLong("epoch") <= now.toEpochMilli()
            && old.optLong("epoch") >= now.toEpochMilli() - 21_600_000L
            && LocalDate.parse(old.optString("date")).equals(now.atZone(zone).toLocalDate())
            && LocalDate.parse(old.optString("date"))
                    .atTime(r.hour, r.minute)
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
                == old.optLong("epoch")
            && !ReminderStore.done(row, LocalDate.parse(old.optString("date")))) {
          next = old;
        } else {
          ZonedDateTime scheduled =
              ReminderSchedule.next(
                  r,
                  now,
                  zone,
                  date ->
                      ReminderStore.done(row, date)
                          || (shown != null && date.toString().equals(shown.optString("date"))));
          if (scheduled != null) {
            long epoch = scheduled.toInstant().toEpochMilli();
            next =
                old != null
                        && !old.optBoolean("snooze")
                        && old.optLong("epoch") == epoch
                        && old.optString("revision").equals(row.optString("revision"))
                    ? old
                    : occurrence(row, scheduled.toLocalDate(), epoch, false);
          }
        }
      } else row.remove("displayed");
      if (next == null) row.remove("pending");
      else ReminderStore.put(row, "pending", next);
      changes.add(new AlarmChange(id, next));
      if (next != null) {
        JSONObject entry = new JSONObject();
        ReminderStore.put(entry, "id", id);
        ReminderStore.put(entry, "occurrence", next);
        installed.put(entry);
      }
    }
    AlarmManager alarms = context.getSystemService(AlarmManager.class);
    // Keep cancellation work durable across a crash between the checkpoint and OS calls.
    JSONArray retired = state.optJSONArray("retired");
    if (retired == null) retired = new JSONArray();
    JSONArray previous = state.optJSONArray("installed");
    if (previous != null)
      for (int i = 0; i < previous.length(); i++) retired.put(previous.optJSONObject(i));
    ReminderStore.put(state, "installed", installed);
    ReminderStore.put(state, "retired", retired);
    // A failed commit leaves previous OS alarms untouched and the visible state rolled back.
    store.write(state);
    if (alarms != null) {
      for (int i = 0; i < retired.length(); i++) {
        JSONObject entry = retired.optJSONObject(i);
        if (entry != null && entry.optJSONObject("occurrence") != null)
          alarms.cancel(
              alarmIntent(context, entry.optString("id"), entry.optJSONObject("occurrence")));
      }
      for (AlarmChange change : changes)
        if (change.next != null) install(context, alarms, change.id, change.next);
    }
    state.remove("retired");
    store.write(state);
    NotificationManager notifications = context.getSystemService(NotificationManager.class);
    if (notifications != null)
      for (android.service.notification.StatusBarNotification n :
          notifications.getActiveNotifications())
        if (n.getTag() != null
            && n.getTag().startsWith(TAG_PREFIX)
            && !validNotifications.contains(n.getTag())) notifications.cancel(n.getTag(), 1);
    AppLogger.i(
        "Discipline reconciled count="
            + rows.length()
            + " exact="
            + exactAllowed(context)
            + " notifications="
            + notificationsAllowed(context));
  }

  private static final class AlarmChange {
    final String id;
    final JSONObject next;

    AlarmChange(String id, JSONObject next) {
      this.id = id;
      this.next = next;
    }
  }

  private static void install(
      Context context, AlarmManager alarms, String id, JSONObject occurrence) {
    long epoch = Math.max(System.currentTimeMillis() + 1000, occurrence.optLong("epoch"));
    PendingIntent intent = alarmIntent(context, id, occurrence);
    if (exactAllowed(context)) {
      try {
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, intent);
        return;
      } catch (SecurityException denied) {
        AppLogger.i("Discipline exact permission changed; using approximate alarm");
      }
    }
    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epoch, intent);
  }

  private static Intent intent(
      Context context, Class<?> receiver, String action, String id, JSONObject o) {
    return new Intent(context, receiver)
        .setAction(action)
        .setData(
            new Uri.Builder()
                .scheme("discipline")
                .authority(context.getPackageName())
                .appendPath(id)
                .appendPath(o.optString("revision"))
                .appendPath(o.optString("date"))
                .appendPath(Long.toString(o.optLong("epoch")))
                .appendPath(o.optString("token"))
                .appendPath(action)
                .build())
        .putExtra("id", id)
        .putExtra("revision", o.optString("revision"))
        .putExtra("token", o.optString("token"))
        .putExtra("date", o.optString("date"))
        .putExtra("epoch", o.optLong("epoch"));
  }

  private static PendingIntent alarmIntent(Context context, String id, JSONObject o) {
    return PendingIntent.getBroadcast(
        context,
        0,
        intent(context, ReminderReceiver.class, "ALARM", id, o),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static boolean matches(JSONObject row, JSONObject o, Intent intent) {
    return row != null
        && o != null
        && row.optBoolean("enabled")
        && row.optString("revision").equals(intent.getStringExtra("revision"))
        && o.optString("revision").equals(row.optString("revision"))
        && o.optString("token").equals(intent.getStringExtra("token"))
        && o.optString("date").equals(intent.getStringExtra("date"))
        && o.optLong("epoch") == intent.getLongExtra("epoch", -1)
        && !ReminderStore.done(row, LocalDate.parse(o.optString("date")));
  }

  static void fire(Context context, Intent intent) {
    synchronized (ReminderStore.LOCK) {
      ReminderStore store = new ReminderStore(context);
      JSONObject state = store.read(),
          row = ReminderStore.rows(state).optJSONObject(intent.getStringExtra("id"));
      JSONObject pending = row == null ? null : row.optJSONObject("pending");
      if (!matches(row, pending, intent)) return;
      if (pending.optLong("epoch") > System.currentTimeMillis() + 2000) {
        reconcile(context);
        return;
      }
      ReminderStore.put(row, "displayed", pending);
      row.remove("pending");
      store.write(state);
      show(context, ReminderStore.reminder(row), pending);
      reconcile(context);
    }
  }

  static void act(Context context, Intent intent) {
    synchronized (ReminderStore.LOCK) {
      ReminderStore store = new ReminderStore(context);
      JSONObject state = store.read(),
          row = ReminderStore.rows(state).optJSONObject(intent.getStringExtra("id"));
      JSONObject shown = row == null ? null : row.optJSONObject("displayed");
      if (!matches(row, shown, intent)) return;
      if ("DONE".equals(intent.getAction()))
        ReminderStore.complete(row, LocalDate.parse(shown.optString("date")), true);
      else if ("SNOOZE".equals(intent.getAction())) snoozeRow(row, shown, 15);
      else return;
      store.write(state);
      cancelNotification(context, intent.getStringExtra("id"));
      reconcile(context);
    }
  }

  private static void cancelNotification(Context context, String id) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager != null) manager.cancel(TAG_PREFIX + id, 1);
  }

  private static void createChannel(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL_ID, "Discipline reminders", NotificationManager.IMPORTANCE_DEFAULT);
    channel.setDescription("Food, skin, hair and personal routine reminders");
    channel.enableLights(true);
    channel.setLightColor(Color.rgb(65, 145, 255));
    manager.createNotificationChannel(channel);
  }

  private static void show(Context context, Reminder reminder, JSONObject o) {
    createChannel(context);
    if (!notificationsAllowed(context)) {
      AppLogger.i("Discipline notification blocked");
      return;
    }
    Intent open =
        new Intent(context, DisciplineActivity.class)
            .setData(Uri.parse("discipline://open/" + Uri.encode(reminder.id)))
            .putExtra("reminder_id", reminder.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent content =
        PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent done =
        PendingIntent.getBroadcast(
            context,
            0,
            intent(context, ReminderActionReceiver.class, "DONE", reminder.id, o),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent snooze =
        PendingIntent.getBroadcast(
            context,
            0,
            intent(context, ReminderActionReceiver.class, "SNOOZE", reminder.id, o),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification notification =
        new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_discipline_notification)
            .setColor(Color.rgb(65, 145, 255))
            .setContentTitle(reminder.title)
            .setContentText(
                reminder.notes.isEmpty() ? reminder.category.name() + " routine" : reminder.notes)
            .setContentIntent(content)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Done", done)
                    .build())
            .addAction(
                new Notification.Action.Builder(
                        (android.graphics.drawable.Icon) null, "Snooze 15 min", snooze)
                    .build())
            .build();
    try {
      context
          .getSystemService(NotificationManager.class)
          .notify(TAG_PREFIX + reminder.id, 1, notification);
    } catch (SecurityException denied) {
      AppLogger.i("Discipline notification permission changed");
    }
  }

  static void receive(BroadcastReceiver receiver, Context context, Runnable operation) {
    BroadcastReceiver.PendingResult pending = receiver.goAsync();
    IO.execute(
        () -> {
          try {
            AppLogger.init(context);
            operation.run();
          } catch (RuntimeException error) {
            AppLogger.i("Discipline receiver failure type=" + error.getClass().getSimpleName());
          } finally {
            pending.finish();
          }
        });
  }
}
