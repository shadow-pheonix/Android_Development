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

import org.json.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Alarm identities contain immutable revision/occurrence tokens, never just a reusable row ID. */
public final class ReminderScheduler {
    public static final String CHANNEL_ID = "discipline_reminders";
    private static final String TAG_PREFIX = "discipline:";
    static final String PRIMARY = "PRIMARY", FOLLOWUP = "FOLLOWUP", SNOOZE = "SNOOZE";
    private static final long HOUR = Duration.ofHours(1).toMillis();
    private static final long CATCHUP = Duration.ofHours(6).toMillis();
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
     * Dashboard snooze applies to today; notification actions retain their original occurrence
     * date.
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
                                today.atTime(reminder.hour, reminder.minute)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant()
                                        .toEpochMilli(),
                                PRIMARY);
            snoozeRow(row, shown, minutes);
            store.write(state);
            cancelNotification(context, id);
            reconcile(context);
            return true;
        }
    }

    private static void snoozeRow(JSONObject row, JSONObject shown, int minutes) {
        LocalDate day = LocalDate.parse(shown.optString("date"));
        JSONObject snoozed =
                occurrence(row, day, System.currentTimeMillis() + minutes * 60_000L, SNOOZE);
        ReminderStore.put(snoozed, "baseEpoch", baseEpoch(shown));
        ReminderStore.put(snoozed, "stage", stage(shown));
        // Explicit snooze consumes the chosen stage even when requested from the dashboard
        // before its first delivery; its delayed alarm is retained independently below.
        recordDelivery(row, shown, Instant.now());
        ReminderStore.put(row, "snoozed", snoozed);
        row.remove("displayed");
    }

    static JSONObject occurrence(JSONObject row, LocalDate date, long epoch, String kind) {
        JSONObject o = new JSONObject();
        ReminderStore.put(o, "token", UUID.randomUUID().toString());
        ReminderStore.put(o, "revision", row.optString("revision"));
        ReminderStore.put(o, "date", date.toString());
        ReminderStore.put(o, "epoch", epoch);
        ReminderStore.put(o, "kind", kind);
        ReminderStore.put(o, "snooze", SNOOZE.equals(kind));
        ReminderStore.put(o, "baseEpoch", FOLLOWUP.equals(kind) ? epoch - HOUR : epoch);
        return o;
    }

    private static String kind(JSONObject o) {
        return o.optString("kind", o.optBoolean("snooze") ? SNOOZE : PRIMARY);
    }

    private static String stage(JSONObject o) {
        return SNOOZE.equals(kind(o)) ? o.optString("stage", PRIMARY) : kind(o);
    }

    private static long baseEpoch(JSONObject o) {
        return o.optLong("baseEpoch", o.optLong("epoch"));
    }

    private static JSONObject deliveries(JSONObject row) {
        JSONObject result = row.optJSONObject("deliveries");
        if (result == null) {
            result = new JSONObject();
            ReminderStore.put(row, "deliveries", result);
        }
        return result;
    }

    static int deliveredStages(JSONObject row, LocalDate date) {
        JSONObject delivery = deliveries(row).optJSONObject(date.toString());
        return delivery == null ? 0 : delivery.optInt("stages");
    }

    /** Durable stage evidence survives notification dismissal, app reopening and Done undo. */
    static void recordDelivery(JSONObject row, JSONObject occurrence, Instant now) {
        LocalDate date = LocalDate.parse(occurrence.optString("date"));
        JSONObject all = deliveries(row), delivery = all.optJSONObject(date.toString());
        if (delivery == null) delivery = new JSONObject();
        int bits = delivery.optInt("stages");
        long base = baseEpoch(occurrence);
        if (FOLLOWUP.equals(stage(occurrence))) bits |= 2;
        else {
            bits |= 1;
            // An approximate/late first alarm must not generate two alerts seconds apart.
            if (now.toEpochMilli() >= base + HOUR) bits |= 2;
        }
        ReminderStore.put(delivery, "stages", bits);
        ReminderStore.put(delivery, "primaryEpoch", base);
        ReminderStore.put(delivery, "revision", row.optString("revision"));
        ReminderStore.put(all, date.toString(), delivery);
        TreeSet<String> dates = new TreeSet<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) dates.add(it.next());
        while (dates.size() > 90) all.remove(dates.pollFirst());
    }

    private static boolean current(JSONObject row, JSONObject o) {
        return o != null
                && o.optString("revision").equals(row.optString("revision"))
                && !ReminderStore.done(row, LocalDate.parse(o.optString("date")));
    }

    /** Computes one next OS alarm while retaining delivery evidence and any explicit snooze. */
    static JSONObject nextOccurrence(JSONObject row, Instant now, ZoneId zone) {
        Reminder r = ReminderStore.reminder(row);
        if (!r.enabled) {
            row.remove("displayed");
            row.remove("snoozed");
            row.remove("primaryPending");
            return null;
        }
        JSONObject old = row.optJSONObject("pending"), shown = row.optJSONObject("displayed");
        if (shown != null
                && LocalDate.parse(shown.optString("date"))
                        .isBefore(now.atZone(zone).toLocalDate().minusDays(1))) {
            row.remove("displayed");
            shown = null;
        }
        // Upgrade pre-followup displayed state without repeating its primary notification.
        if (current(row, shown)
                && deliveredStages(row, LocalDate.parse(shown.optString("date"))) == 0) {
            JSONObject original =
                    occurrence(
                            row,
                            LocalDate.parse(shown.optString("date")),
                            ReminderSchedule.scheduledOn(
                                            r, LocalDate.parse(shown.optString("date")), zone)
                                    .toInstant()
                                    .toEpochMilli(),
                            PRIMARY);
            recordDelivery(row, original, Instant.ofEpochMilli(original.optLong("epoch")));
        }
        // Upgrade a pre-followup pending snooze into its independent durable slot.
        if (current(row, old) && SNOOZE.equals(kind(old)) && !row.has("snoozed")) {
            if (!old.has("baseEpoch"))
                ReminderStore.put(
                        old,
                        "baseEpoch",
                        ReminderSchedule.scheduledOn(
                                        r, LocalDate.parse(old.optString("date")), zone)
                                .toInstant()
                                .toEpochMilli());
            ReminderStore.put(row, "snoozed", old);
        }
        JSONObject next = null;
        ZonedDateTime primary =
                ReminderSchedule.next(
                        r,
                        now,
                        zone,
                        date ->
                                ReminderStore.done(row, date)
                                        || (deliveredStages(row, date) & 1) != 0);
        if (primary != null)
            next =
                    occurrence(
                            row,
                            primary.toLocalDate(),
                            primary.toInstant().toEpochMilli(),
                            PRIMARY);
        // Keep the upcoming primary even when the one OS alarm slot is occupied by
        // a follow-up/snooze. Reboot/delayed delivery can otherwise skip that primary.
        JSONObject queued = row.optJSONObject("primaryPending");
        for (JSONObject candidate : Arrays.asList(old, queued)) {
            if (!current(row, candidate) || !PRIMARY.equals(kind(candidate))) continue;
            LocalDate date = LocalDate.parse(candidate.optString("date"));
            long epoch = candidate.optLong("epoch");
            if ((deliveredStages(row, date) & 1) == 0
                    && epoch <= now.toEpochMilli()
                    && epoch >= now.toEpochMilli() - CATCHUP
                    && epoch
                            == ReminderSchedule.scheduledOn(r, date, zone)
                                    .toInstant()
                                    .toEpochMilli()) next = earlier(next, candidate);
        }
        if (sameOccurrence(row, old, next)) next = old;
        else if (sameOccurrence(row, queued, next)) next = queued;
        if (next == null) row.remove("primaryPending");
        else ReminderStore.put(row, "primaryPending", next);
        JSONObject all = deliveries(row);
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String day = it.next();
            JSONObject delivery = all.optJSONObject(day);
            LocalDate date = LocalDate.parse(day);
            if (delivery == null
                    || (delivery.optInt("stages") & 3) != 1
                    || !delivery.optString("revision").equals(row.optString("revision"))
                    || ReminderStore.done(row, date)) continue;
            long epoch = delivery.optLong("primaryEpoch") + HOUR;
            if (epoch >= now.toEpochMilli() - CATCHUP)
                next = earlier(next, occurrence(row, date, epoch, FOLLOWUP));
        }
        JSONObject snoozed = row.optJSONObject("snoozed");
        if (current(row, snoozed) && snoozed.optLong("epoch") > now.toEpochMilli() - 86_400_000L)
            next = earlier(next, snoozed);
        else row.remove("snoozed");
        // Preserve immutable identities while replanning equivalent stages.
        if (sameOccurrence(row, old, next)) return old;
        return next;
    }

    private static boolean sameOccurrence(JSONObject row, JSONObject a, JSONObject b) {
        return current(row, a)
                && b != null
                && a.optLong("epoch") == b.optLong("epoch")
                && a.optString("date").equals(b.optString("date"))
                && kind(a).equals(kind(b));
    }

    private static JSONObject earlier(JSONObject a, JSONObject b) {
        return a == null || b.optLong("epoch") < a.optLong("epoch") ? b : a;
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
            JSONObject next = nextOccurrence(row, now, zone);
            JSONObject shown = row.optJSONObject("displayed");
            if (r.enabled && current(row, shown)) validNotifications.add(TAG_PREFIX + id);
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
                            alarmIntent(
                                    context,
                                    entry.optString("id"),
                                    entry.optJSONObject("occurrence")));
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
                        && !validNotifications.contains(n.getTag()))
                    notifications.cancel(n.getTag(), 1);
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
                .putExtra("epoch", o.optLong("epoch"))
                .putExtra("kind", kind(o));
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
            Instant now = Instant.now();
            JSONObject expected = nextOccurrence(row, now, ZoneId.systemDefault());
            if (expected == null
                    || !expected.optString("token").equals(pending.optString("token"))) {
                reconcile(context);
                return;
            }
            recordDelivery(row, pending, now);
            ReminderStore.put(row, "displayed", pending);
            if (SNOOZE.equals(kind(pending))) row.remove("snoozed");
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
                ReminderStore.complete(state, row, LocalDate.parse(shown.optString("date")), true);
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
        channel.setDescription("Food, supplements, skin, hair and personal routine reminders");
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
                        .putExtra("reminder_date", o.optString("date"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content =
                PendingIntent.getActivity(
                        context,
                        0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
                                (FOLLOWUP.equals(stage(o)) ? "Follow-up · " : "")
                                        + (reminder.notes.isEmpty()
                                                ? reminder.category.name() + " routine"
                                                : reminder.notes))
                        .setContentIntent(content)
                        .setAutoCancel(true)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .addAction(
                                new Notification.Action.Builder(
                                                (android.graphics.drawable.Icon) null, "Done", done)
                                        .build())
                        .addAction(
                                new Notification.Action.Builder(
                                                (android.graphics.drawable.Icon) null,
                                                "Snooze 15 min",
                                                snooze)
                                        .build())
                        .build();
        try {
            context.getSystemService(NotificationManager.class)
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
                        AppLogger.i(
                                "Discipline receiver failure type="
                                        + error.getClass().getSimpleName());
                    } finally {
                        pending.finish();
                    }
                });
    }
}
