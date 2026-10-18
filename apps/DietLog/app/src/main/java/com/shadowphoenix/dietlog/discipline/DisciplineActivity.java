package com.shadowphoenix.dietlog.discipline;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.shadowphoenix.dietlog.AppHubActivity;
import com.shadowphoenix.dietlog.MainActivity;
import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.diagnostics.DiagnosticsExporter;
import com.shadowphoenix.dietlog.shared.SharedAppData;

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline routine manager. All persistent reads/writes and scheduling run on a serial worker. */
public final class DisciplineActivity extends Activity {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final int NOTIFICATION_REQUEST = 704;
    private static final String PREFS = "discipline_editor";
    private static final String DRAFT = "draft";
    private static final String[] CATEGORIES = {"Food", "Skin", "Hair", "Other"};
    private static final String[] MEALS = {
        "No meal link", "Breakfast", "Lunch", "Snacks", "Dinner"
    };
    private static final String[] MEAL_KEYS = {"", "breakfast", "lunch", "snacks", "dinner"};
    private LinearLayout body;
    private final List<Reminder> reminders = new ArrayList<>();
    private final Map<String, Boolean> completed = new HashMap<>();
    private SharedAppData.FuelNoteSnapshot fuel;
    private String fuelError;
    private String loadError;
    private String scheduleError;
    private LocalDate today = LocalDate.now();
    private int filter = -1;
    private int generation;
    private boolean active;
    private boolean loading = true;
    private boolean busy;
    private boolean draftLoaded;
    private Draft draft;
    private AlertDialog editor;
    private EditText titleInput, notesInput;
    private Spinner categoryInput, mealInput;
    private CheckBox enabledInput;
    private final List<CheckBox> dayInputs = new ArrayList<>();
    private TextView timeInput, editorError;
    private boolean suppressEditorPersistence;
    private boolean savingEditor;
    private LinearLayout editorFields;
    private final Handler clock = new Handler(Looper.getMainLooper());
    private ReminderStore listenerStore;
    private final Runnable reminderChanged =
            new Runnable() {
                @Override
                public void run() {
                    if (!active || busy || savingEditor) return;
                    // Let the first load restore its editor draft before a read-only refresh.
                    if (loading) {
                        clock.postDelayed(this, 150);
                        return;
                    }
                    refresh(false);
                }
            };
    private final SharedPreferences.OnSharedPreferenceChangeListener reminderListener =
            (preferences, key) -> {
                if (!active || busy || savingEditor) return;
                clock.removeCallbacks(reminderChanged);
                clock.postDelayed(reminderChanged, 150);
            };
    private final Runnable tick =
            new Runnable() {
                @Override
                public void run() {
                    if (!active) return;
                    if (!today.equals(LocalDate.now())) refresh();
                    else render();
                    clock.postDelayed(this, 60_000);
                }
            };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        AppLogger.init(this);
        CrashHandler.install(this);
        if (state != null) {
            filter = state.getInt("filter", -1);
            String raw = state.getString(DRAFT);
            draft = Draft.parse(raw);
            draftLoaded = state.getBoolean("draftLoaded", false) || raw != null;
        }
        body = DisciplineUi.page(this, R.drawable.discipline_background);
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        listenerStore = new ReminderStore(getApplicationContext());
        listenerStore.registerListener(reminderListener);
    }

    @Override
    protected void onStop() {
        if (listenerStore != null) {
            listenerStore.unregisterListener(reminderListener);
            listenerStore = null;
        }
        clock.removeCallbacks(reminderChanged);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        refresh();
        clock.postDelayed(tick, 60_000);
    }

    @Override
    protected void onPause() {
        captureDraft();
        if (!savingEditor) persistDraft(draft);
        active = false;
        clock.removeCallbacks(tick);
        clock.removeCallbacks(reminderChanged);
        generation++;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (editor != null) {
            suppressEditorPersistence = true;
            editor.dismiss();
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        captureDraft();
        out.putInt("filter", filter);
        out.putBoolean("draftLoaded", !savingEditor && draftLoaded);
        if (draft != null) out.putString(DRAFT, draft.json());
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (active) refresh();
    }

    private void refresh() {
        refresh(true);
    }

    private void refresh(boolean reschedule) {
        final int token = ++generation;
        final LocalDate date = LocalDate.now();
        final boolean readDraft = reschedule && !draftLoaded;
        Context app = getApplicationContext();
        IO.execute(
                () -> {
                    List<Reminder> loaded = new ArrayList<>();
                    Map<String, Boolean> done = new HashMap<>();
                    SharedAppData.FuelNoteSnapshot shared = null;
                    String error = null, sharedError = null, scheduleFailure = null;
                    if (reschedule) {
                        try {
                            ReminderScheduler.rescheduleAll(app);
                        } catch (Exception e) {
                            AppLogger.e("Discipline reminder scheduling failed", e);
                            scheduleFailure =
                                    "Reminders are saved, but alert scheduling failed. Retry to set up alerts.";
                        }
                    }
                    Draft saved = null;
                    try {
                        ReminderStore store = new ReminderStore(app);
                        loaded = store.list();
                        for (Reminder item : loaded)
                            done.put(item.id, store.isCompleted(item.id, date));
                    } catch (Exception e) {
                        AppLogger.e("Discipline reminders load failed", e);
                        error = "Couldn’t load your reminders. Tap Retry.";
                    }
                    try {
                        shared = SharedAppData.loadFuelNote(app, date);
                    } catch (Exception e) {
                        AppLogger.e("Discipline shared nutrition load failed", e);
                        sharedError = "FuelNote progress is temporarily unavailable.";
                    }
                    if (readDraft)
                        saved =
                                Draft.parse(
                                        app.getSharedPreferences(PREFS, MODE_PRIVATE)
                                                .getString(DRAFT, null));
                    List<Reminder> result = loaded;
                    Map<String, Boolean> doneResult = done;
                    SharedAppData.FuelNoteSnapshot sharedResult = shared;
                    String failure = error,
                            sharedFailure = sharedError,
                            schedulingFailure = scheduleFailure;
                    Draft restored = saved;
                    runOnUiThread(
                            () -> {
                                if (!valid(token)) return;
                                today = date;
                                loading = false;
                                loadError = failure;
                                if (reschedule) scheduleError = schedulingFailure;
                                reminders.clear();
                                reminders.addAll(result);
                                completed.clear();
                                completed.putAll(doneResult);
                                fuel = sharedResult;
                                fuelError = sharedFailure;
                                if (readDraft && !draftLoaded) {
                                    draftLoaded = true;
                                    if (draft == null) draft = restored;
                                }
                                render();
                                if (reschedule && draft != null && editor == null && !busy)
                                    showEditor();
                            });
                });
    }

    private boolean valid(int token) {
        return active && token == generation && !isFinishing() && !isDestroyed();
    }

    private void render() {
        body.removeAllViews();
        LinearLayout navigation = DisciplineUi.row(this);
        DisciplineUi.weighted(
                navigation, DisciplineUi.text(this, "DISCIPLINE", 13, DisciplineUi.BLUE, true), 0);
        navigation.addView(DisciplineUi.button(this, "Switch apps", this::switchApps));
        DisciplineUi.add(body, navigation, 0);
        DisciplineUi.add(
                body,
                DisciplineUi.text(this, "Make time for you", 29, DisciplineUi.TEXT, true),
                20);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        today.format(
                                DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                        15,
                        DisciplineUi.MUTED,
                        false),
                6);
        renderCounts();
        renderNotificationStatus();
        renderFuelNote();
        TextView add = DisciplineUi.button(this, "+  Add reminder", this::newReminder);
        add.setEnabled(!busy && !loading && loadError == null);
        DisciplineUi.clickable(add, DisciplineUi.BLUE, true);
        DisciplineUi.add(body, add, 22);
        renderFilters();
        if (loading) {
            DisciplineUi.add(
                    body,
                    DisciplineUi.text(
                            this, "Loading your routines…", 15, DisciplineUi.MUTED, false),
                    24);
        } else if (loadError != null) {
            LinearLayout error = DisciplineUi.card(this);
            DisciplineUi.add(
                    error, DisciplineUi.text(this, loadError, 15, DisciplineUi.TEXT, false), 0);
            DisciplineUi.add(error, DisciplineUi.button(this, "Retry", this::refresh), 10);
            DisciplineUi.add(body, error, 18);
        } else {
            List<Reminder> visible = new ArrayList<>();
            for (Reminder item : reminders)
                if (filter < 0 || item.category.ordinal() == filter) visible.add(item);
            visible.sort(
                    Comparator.comparingInt(this::sortGroup)
                            .thenComparingInt(item -> item.hour * 60 + item.minute)
                            .thenComparing(item -> item.title));
            if (visible.isEmpty()) renderEmpty();
            for (Reminder item : visible) renderReminder(item);
        }
        TextView diagnostics =
                DisciplineUi.button(this, "Export diagnostics", this::exportDiagnostics);
        diagnostics.setEnabled(!busy);
        DisciplineUi.add(body, diagnostics, 24);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        "Reminders and completion are saved locally and shared with FuelNote. Marking a food reminder done does not add food to your diary.",
                        12,
                        DisciplineUi.MUTED,
                        false),
                12);
    }

    private void renderCounts() {
        int due = 0, upcoming = 0, done = 0;
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        for (Reminder item : reminders) {
            if (!item.enabled || !scheduledToday(item)) continue;
            if (isDone(item)) done++;
            else if (item.enabled && item.hour * 60 + item.minute <= now) due++;
            else if (item.enabled) upcoming++;
        }
        LinearLayout stats = DisciplineUi.row(this);
        addCount(stats, due, "Due today");
        addCount(stats, upcoming, "Upcoming");
        addCount(stats, done, "Done today");
        DisciplineUi.add(body, stats, 22);
    }

    private void addCount(LinearLayout row, int count, String label) {
        LinearLayout cell = DisciplineUi.card(this);
        cell.setPadding(
                DisciplineUi.dp(this, 10),
                DisciplineUi.dp(this, 14),
                DisciplineUi.dp(this, 8),
                DisciplineUi.dp(this, 14));
        DisciplineUi.add(
                cell,
                DisciplineUi.text(this, Integer.toString(count), 25, DisciplineUi.BLUE, true),
                0);
        DisciplineUi.add(cell, DisciplineUi.text(this, label, 12, DisciplineUi.MUTED, false), 4);
        DisciplineUi.weighted(row, cell, row.getChildCount() == 0 ? 0 : 8);
    }

    private void renderNotificationStatus() {
        boolean notifications = ReminderScheduler.notificationsAllowed(this);
        boolean exact = ReminderScheduler.exactAllowed(this);
        LinearLayout card = DisciplineUi.card(this);
        String heading =
                notifications
                        ? (exact ? "Alerts are ready" : "Alerts use approximate timing")
                        : "Notifications are off";
        String message =
                !notifications
                        ? "Reminders are saved. Alerts cannot appear until notifications are allowed."
                        : !exact
                                ? "Android may deliver alerts later. Precise timing is optional; reminders still work without it."
                                : "Enabled reminders can alert at the times you choose. Android’s battery settings may affect delivery.";
        if (scheduleError != null) {
            heading = "Alert scheduling needs attention";
            message = scheduleError;
            DisciplineUi.add(
                    card, DisciplineUi.button(this, "Retry scheduling", this::refresh), 10);
        }
        DisciplineUi.add(card, DisciplineUi.text(this, heading, 15, DisciplineUi.TEXT, true), 0);
        DisciplineUi.add(card, DisciplineUi.text(this, message, 13, DisciplineUi.MUTED, false), 6);
        if (!notifications)
            DisciplineUi.add(
                    card,
                    DisciplineUi.button(this, "Set up notifications", this::requestNotifications),
                    10);
        if (Build.VERSION.SDK_INT >= 31 && !exact) {
            DisciplineUi.add(
                    card,
                    DisciplineUi.button(
                            this,
                            "Allow precise timing (optional)",
                            () -> {
                                try {
                                    startActivity(
                                            new Intent(
                                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                                    Uri.parse("package:" + getPackageName())));
                                } catch (Exception e) {
                                    toast("This device does not offer precise-alarm settings.");
                                }
                            }),
                    8);
        }
        DisciplineUi.add(body, card, 16);
    }

    private void renderFuelNote() {
        LinearLayout card = DisciplineUi.card(this);
        DisciplineUi.add(
                card,
                DisciplineUi.text(this, "CONNECTED TO FUELNOTE", 11, DisciplineUi.BLUE, true),
                0);
        String detail;
        if (fuel != null && fuel.profileReady) {
            detail =
                    String.format(
                            Locale.getDefault(),
                            "%.0f / %.0f kcal today",
                            fuel.calories,
                            fuel.calorieTarget);
            DisciplineUi.add(card, DisciplineUi.text(this, detail, 21, DisciplineUi.TEXT, true), 8);
            detail =
                    String.format(
                            Locale.getDefault(),
                            "Protein %.0f/%.0f g  ·  Carbs %.0f/%.0f g\nFat %.0f/%.0f g",
                            fuel.protein,
                            fuel.proteinTarget,
                            fuel.carbs,
                            fuel.carbsTarget,
                            fuel.fat,
                            fuel.fatTarget);
        } else if (fuel != null) {
            detail =
                    fuel.entryCount
                            + " food entries today. Set up your FuelNote profile to see targets.";
        } else detail = fuelError == null ? "Loading today’s nutrition…" : fuelError;
        DisciplineUi.add(card, DisciplineUi.text(this, detail, 14, DisciplineUi.MUTED, false), 8);
        DisciplineUi.add(
                card, DisciplineUi.button(this, "Open FuelNote", () -> openFuelNote("")), 12);
        DisciplineUi.add(body, card, 16);
    }

    private void renderFilters() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = DisciplineUi.row(this);
        for (int i = -1; i < CATEGORIES.length; i++) {
            final int selected = i;
            TextView chip =
                    DisciplineUi.button(
                            this,
                            i < 0 ? "All" : CATEGORIES[i],
                            () -> {
                                filter = selected;
                                render();
                            });
            chip.setSelected(filter == i);
            DisciplineUi.clickable(chip, DisciplineUi.BLUE, filter == i);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.setMarginEnd(DisciplineUi.dp(this, 8));
            chips.addView(chip, p);
        }
        scroll.addView(chips);
        DisciplineUi.add(body, scroll, 18);
    }

    private void renderEmpty() {
        LinearLayout card = DisciplineUi.card(this);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this, "A little structure, your way", 20, DisciplineUi.TEXT, true),
                0);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        reminders.isEmpty()
                                ? "Your routines start here. Add only the reminders you want."
                                : "No reminders in this category yet.",
                        15,
                        DisciplineUi.MUTED,
                        false),
                10);
        for (int i = 0; i < 3; i++) {
            final int category = i;
            DisciplineUi.add(
                    card,
                    DisciplineUi.button(
                            this,
                            "+ "
                                    + new String[] {
                                                "Food reminder",
                                                "Skincare reminder",
                                                "Haircare reminder"
                                            }
                                            [i],
                            () -> openDraft(Draft.fresh(category))),
                    10);
        }
        DisciplineUi.add(body, card, 18);
    }

    private boolean scheduledToday(Reminder item) {
        return (item.daysMask & (1 << (today.getDayOfWeek().getValue() - 1))) != 0;
    }

    private boolean isDone(Reminder item) {
        return Boolean.TRUE.equals(completed.get(item.id));
    }

    private int sortGroup(Reminder item) {
        if (!item.enabled) return 4;
        if (!scheduledToday(item)) return 3;
        if (isDone(item)) return 2;
        return item.hour * 60 + item.minute
                        <= LocalTime.now().getHour() * 60 + LocalTime.now().getMinute()
                ? 0
                : 1;
    }

    private String status(Reminder item) {
        if (!item.enabled) return "Paused";
        if (!scheduledToday(item)) return "Not scheduled today";
        if (isDone(item)) return "Done today";
        return sortGroup(item) == 0 ? "Due today" : "Upcoming today";
    }

    private void renderReminder(Reminder item) {
        LinearLayout card = DisciplineUi.card(this);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        CATEGORIES[item.category.ordinal()].toUpperCase(Locale.getDefault())
                                + "  ·  "
                                + status(item),
                        12,
                        DisciplineUi.BLUE,
                        true),
                0);
        DisciplineUi.add(card, DisciplineUi.text(this, item.title, 20, DisciplineUi.TEXT, true), 8);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        clock(item.hour, item.minute) + "  ·  " + days(item.daysMask),
                        14,
                        DisciplineUi.MUTED,
                        false),
                6);
        if (!item.notes.isEmpty())
            DisciplineUi.add(
                    card, DisciplineUi.text(this, item.notes, 14, DisciplineUi.MUTED, false), 8);
        if (item.category == Reminder.Category.FOOD && fuel != null) {
            String linked =
                    item.mealKey.isEmpty()
                            ? fuel.entryCount + " food entries logged today"
                            : fuel.mealStatus(item.mealKey);
            DisciplineUi.add(
                    card,
                    DisciplineUi.text(this, "FuelNote · " + linked, 13, DisciplineUi.MUTED, false),
                    12);
            String planned = fuel.plannedMeals.get(item.mealKey);
            if (planned != null && !planned.isEmpty()) {
                DisciplineUi.add(
                        card,
                        DisciplineUi.text(
                                this, "Planned meal · " + planned, 13, DisciplineUi.MUTED, false),
                        6);
            }
            TextView log =
                    DisciplineUi.button(
                            this,
                            "Open " + (item.mealKey.isEmpty() ? "food diary" : item.mealKey),
                            () -> openFuelNote(item.mealKey));
            log.setEnabled(!busy);
            DisciplineUi.add(card, log, 10);
        }
        if (scheduledToday(item)) {
            LinearLayout actions = DisciplineUi.row(this);
            boolean done = isDone(item);
            TextView complete =
                    DisciplineUi.button(
                            this,
                            done ? "Undo today" : "Done today",
                            () ->
                                    mutate(
                                            () -> {
                                                new ReminderStore(getApplicationContext())
                                                        .setCompleted(item.id, today, !done);
                                                return rescheduleAfterChange(
                                                        done
                                                                ? "Marked not done for today."
                                                                : "Marked done for today.");
                                            }));
            complete.setEnabled(!busy);
            DisciplineUi.weighted(actions, complete, 0);
            if (item.enabled && !done) {
                TextView snooze =
                        DisciplineUi.button(
                                this,
                                "Snooze 15 min",
                                () ->
                                        mutate(
                                                () -> {
                                                    boolean scheduled =
                                                            ReminderScheduler.snooze(
                                                                    getApplicationContext(),
                                                                    item.id,
                                                                    15);
                                                    return !ReminderScheduler.notificationsAllowed(
                                                                    getApplicationContext())
                                                            ? "Notifications are off. No alert can appear."
                                                            : scheduled
                                                                    ? "Reminder snoozed for 15 minutes."
                                                                    : "Couldn’t snooze this reminder. Try again.";
                                                }));
                snooze.setEnabled(!busy);
                DisciplineUi.weighted(actions, snooze, 8);
            }
            DisciplineUi.add(card, actions, 14);
        }
        LinearLayout manage = DisciplineUi.row(this);
        TextView edit = DisciplineUi.button(this, "Edit", () -> openDraft(Draft.from(item)));
        TextView pause =
                DisciplineUi.button(
                        this,
                        item.enabled ? "Pause" : "Enable",
                        () ->
                                mutate(
                                        () -> {
                                            new ReminderStore(getApplicationContext())
                                                    .save(
                                                            new Reminder(
                                                                    item.id,
                                                                    item.title,
                                                                    item.notes,
                                                                    item.category,
                                                                    item.hour,
                                                                    item.minute,
                                                                    item.daysMask,
                                                                    !item.enabled,
                                                                    item.mealKey));
                                            return rescheduleAfterChange(
                                                    item.enabled
                                                            ? "Reminder paused."
                                                            : "Reminder enabled.");
                                        }));
        TextView delete =
                DisciplineUi.button(
                        this,
                        "Delete",
                        0xFFFFB9BB,
                        () ->
                                new AlertDialog.Builder(this, R.style.DisciplineDialogTheme)
                                        .setTitle("Delete reminder?")
                                        .setMessage(
                                                "This removes the reminder and its completion history.")
                                        .setNegativeButton("Cancel", null)
                                        .setPositiveButton(
                                                "Delete",
                                                (d, w) ->
                                                        mutate(
                                                                () -> {
                                                                    new ReminderStore(
                                                                                    getApplicationContext())
                                                                            .delete(item.id);
                                                                    return rescheduleAfterChange(
                                                                            "Reminder deleted.");
                                                                }))
                                        .show());
        for (TextView control : new TextView[] {edit, pause, delete}) {
            control.setEnabled(!busy);
            DisciplineUi.weighted(manage, control, manage.getChildCount() == 0 ? 0 : 6);
        }
        DisciplineUi.add(card, manage, 10);
        DisciplineUi.add(body, card, 16);
    }

    private String clock(int hour, int minute) {
        return LocalTime.of(hour, minute)
                .format(
                        DateTimeFormatter.ofPattern(
                                DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a",
                                Locale.getDefault()));
    }

    private static String days(int mask) {
        if (mask == 127) return "Every day";
        if (mask == 31) return "Weekdays";
        if (mask == 96) return "Weekends";
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 7; i++)
            if ((mask & (1 << i)) != 0) {
                if (value.length() > 0) value.append(", ");
                value.append(new String[] {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"}[i]);
            }
        return value.toString();
    }

    private String rescheduleAfterChange(String savedMessage) {
        try {
            ReminderScheduler.rescheduleAll(getApplicationContext());
            return savedMessage;
        } catch (Exception e) {
            AppLogger.e("Discipline alert scheduling failed after saved change", e);
            return savedMessage + " Alert scheduling failed. Retry from the alert status card.";
        }
    }

    private interface Work {
        String run() throws Exception;
    }

    private void mutate(Work work) {
        mutate(work, "Couldn’t update this reminder. Please try again.");
    }

    private void mutate(Work work, String errorMessage) {
        if (busy) return;
        busy = true;
        render();
        IO.execute(
                () -> {
                    String message;
                    try {
                        message = work.run();
                    } catch (Exception e) {
                        AppLogger.e("Discipline action failed", e);
                        message = errorMessage;
                    }
                    String result = message;
                    runOnUiThread(
                            () -> {
                                if (isDestroyed() || isFinishing()) return;
                                busy = false;
                                if (active) {
                                    toast(result);
                                    refresh();
                                }
                            });
                });
    }

    @Override
    public void onBackPressed() {
        switchApps();
    }

    private void switchApps() {
        captureDraft();
        persistDraft(draft);
        startActivity(
                new Intent(this, AppHubActivity.class)
                        .addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void openFuelNote(String meal) {
        Intent intent =
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!meal.isEmpty()) intent.putExtra("discipline_meal", meal);
        startActivity(intent);
        finish();
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this, R.style.DisciplineDialogTheme)
                    .setTitle("Allow reminder alerts?")
                    .setMessage(
                            "Discipline needs notification permission to show your saved reminders. You can keep using routines without alerts.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton(
                            "Continue",
                            (d, w) ->
                                    requestPermissions(
                                            new String[] {Manifest.permission.POST_NOTIFICATIONS},
                                            NOTIFICATION_REQUEST))
                    .show();
        } else openNotificationSettings();
    }

    private void openNotificationSettings() {
        try {
            startActivity(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception e) {
            toast("Notification settings are unavailable on this device.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == NOTIFICATION_REQUEST) {
            refresh();
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this, R.style.DisciplineDialogTheme)
                        .setTitle("Reminders are saved")
                        .setMessage(
                                "Notifications are still off. You can enable them later in Android settings.")
                        .setNegativeButton("Later", null)
                        .setPositiveButton("Open settings", (d, w) -> openNotificationSettings())
                        .show();
            }
        }
    }

    private void newReminder() {
        openDraft(Draft.fresh(filter < 0 ? 3 : filter));
    }

    private void openDraft(Draft next) {
        if (busy || editor != null) return;
        draft = next;
        showEditor();
    }

    private void showEditor() {
        if (draft == null || editor != null || isFinishing()) return;
        suppressEditorPersistence = false;
        Draft value = draft;
        ScrollView scroll = new ScrollView(this);
        LinearLayout fields = DisciplineUi.column(this);
        editorFields = fields;
        fields.setPadding(
                DisciplineUi.dp(this, 20),
                DisciplineUi.dp(this, 6),
                DisciplineUi.dp(this, 20),
                DisciplineUi.dp(this, 16));
        scroll.addView(fields);
        label(fields, "Reminder title");
        titleInput = input(value.title, "e.g. Evening skincare", 120, false);
        DisciplineUi.add(fields, titleInput, 4);
        label(fields, "Notes (optional)");
        notesInput = input(value.notes, "Your routine or a small nudge", 1000, true);
        DisciplineUi.add(fields, notesInput, 4);
        label(fields, "Category");
        categoryInput = spinner(CATEGORIES, value.category);
        DisciplineUi.add(fields, categoryInput, 4);
        label(fields, "Time");
        timeInput =
                DisciplineUi.button(
                        this,
                        clock(value.hour, value.minute),
                        () -> {
                            if (draft == null) return;
                            TimePickerDialog picker =
                                    new TimePickerDialog(
                                            this,
                                            R.style.DisciplineDialogTheme,
                                            (view, hour, minute) -> {
                                                if (draft == null) return;
                                                draft.hour = hour;
                                                draft.minute = minute;
                                                timeInput.setText(clock(hour, minute));
                                            },
                                            draft.hour,
                                            draft.minute,
                                            DateFormat.is24HourFormat(this));
                            picker.show();
                        });
        DisciplineUi.add(fields, timeInput, 4);
        label(fields, "Repeat on (choose at least one day)");
        dayInputs.clear();
        String[] labels = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int start = 0; start < 7; start += 4) {
            LinearLayout row = DisciplineUi.row(this);
            for (int i = start; i < Math.min(7, start + 4); i++) {
                CheckBox day = check(labels[i], (value.daysMask & (1 << i)) != 0);
                day.setContentDescription(
                        new String[] {
                                    "Monday",
                                    "Tuesday",
                                    "Wednesday",
                                    "Thursday",
                                    "Friday",
                                    "Saturday",
                                    "Sunday"
                                }
                                [i]);
                dayInputs.add(day);
                DisciplineUi.weighted(row, day, 0);
            }
            DisciplineUi.add(fields, row, 2);
        }
        label(fields, "FuelNote meal link (food reminders only)");
        mealInput = spinner(MEALS, value.meal);
        DisciplineUi.add(fields, mealInput, 4);
        categoryInput.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> p, View v, int position, long id) {
                        mealInput.setEnabled(position == 0);
                        mealInput.setAlpha(position == 0 ? 1f : .5f);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> p) {}
                });
        enabledInput = check("Enabled", value.enabled);
        DisciplineUi.add(fields, enabledInput, 12);
        editorError = DisciplineUi.text(this, "", 13, 0xFFFFB9BB, false);
        editorError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        DisciplineUi.add(fields, editorError, 6);
        editor =
                new AlertDialog.Builder(this, R.style.DisciplineDialogTheme)
                        .setTitle(value.existing ? "Edit reminder" : "New reminder")
                        .setView(scroll)
                        .setNegativeButton("Cancel", (d, w) -> discardDraft())
                        .setPositiveButton("Save reminder", null)
                        .create();
        editor.setOnCancelListener(d -> discardDraft());
        editor.setOnDismissListener(
                d -> {
                    editor = null;
                    titleInput = null;
                    notesInput = null;
                    categoryInput = null;
                    mealInput = null;
                    enabledInput = null;
                    dayInputs.clear();
                });
        editor.setOnShowListener(
                d ->
                        editor.getButton(AlertDialog.BUTTON_POSITIVE)
                                .setOnClickListener(v -> saveEditor()));
        editor.show();
        editor.getWindow()
                .setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                | android.view.WindowManager.LayoutParams
                                        .SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void label(LinearLayout parent, String text) {
        DisciplineUi.add(parent, DisciplineUi.text(this, text, 13, DisciplineUi.MUTED, true), 14);
    }

    private EditText input(String text, String hint, int max, boolean multiline) {
        EditText field = new EditText(this);
        field.setText(text);
        field.setHint(hint);
        field.setTextColor(DisciplineUi.TEXT);
        field.setHintTextColor(0xFFA6B5CC);
        field.setTextSize(15);
        field.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setSingleLine(!multiline);
        if (multiline) field.setMinLines(2);
        field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(max)});
        field.setBackground(DisciplineUi.surface(this, DisciplineUi.BLUE, false));
        field.setPadding(
                DisciplineUi.dp(this, 14),
                DisciplineUi.dp(this, 12),
                DisciplineUi.dp(this, 14),
                DisciplineUi.dp(this, 12));
        field.setMinHeight(DisciplineUi.dp(this, 48));
        return field;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox field = new CheckBox(this);
        field.setText(text);
        field.setTextColor(DisciplineUi.TEXT);
        field.setTextSize(13);
        field.setChecked(checked);
        field.setButtonTintList(
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {DisciplineUi.BLUE, DisciplineUi.MUTED}));
        field.setMinHeight(DisciplineUi.dp(this, 48));
        return field;
    }

    private Spinner spinner(String[] values, int selection) {
        Spinner field = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        field.setAdapter(adapter);
        field.setSelection(selection);
        field.setPopupBackgroundDrawable(DisciplineUi.surface(this, DisciplineUi.BLUE, false));
        field.setBackgroundTintList(ColorStateList.valueOf(DisciplineUi.BLUE));
        field.setMinimumHeight(DisciplineUi.dp(this, 48));
        return field;
    }

    private void captureDraft() {
        if (draft == null || titleInput == null) return;
        draft.title = titleInput.getText().toString();
        draft.notes = notesInput.getText().toString();
        draft.category = categoryInput.getSelectedItemPosition();
        draft.meal = mealInput.getSelectedItemPosition();
        draft.enabled = enabledInput.isChecked();
        draft.daysMask = 0;
        for (int i = 0; i < dayInputs.size(); i++)
            if (dayInputs.get(i).isChecked()) draft.daysMask |= 1 << i;
    }

    private void persistDraft(Draft value) {
        String raw = value == null ? null : value.json();
        Context app = getApplicationContext();
        IO.execute(
                () -> {
                    android.content.SharedPreferences.Editor prefs =
                            app.getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                    if (raw == null) prefs.remove(DRAFT);
                    else prefs.putString(DRAFT, raw);
                    prefs.apply();
                });
    }

    private void discardDraft() {
        if (suppressEditorPersistence) return;
        draft = null;
        persistDraft(null);
    }

    private void saveEditor() {
        if (busy) return;
        captureDraft();
        if (draft.title.trim().isEmpty()) {
            editorError.setText("Enter a reminder title.");
            titleInput.requestFocus();
            return;
        }
        if (draft.daysMask == 0) {
            editorError.setText("Choose at least one repeat day.");
            return;
        }
        Draft saving = Draft.parse(draft.json());
        busy = true;
        savingEditor = true;
        draft = null;
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        editor.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        editor.setCancelable(false);
        editorError.setText("Saving…");
        enableFields(editorFields, false);
        IO.execute(
                () -> {
                    Exception failure = null;
                    String confirmation = null;
                    try {
                        Reminder reminder =
                                new Reminder(
                                        saving.id,
                                        saving.title.trim(),
                                        saving.notes.trim(),
                                        Reminder.Category.values()[saving.category],
                                        saving.hour,
                                        saving.minute,
                                        saving.daysMask,
                                        saving.enabled,
                                        saving.category == 0 ? MEAL_KEYS[saving.meal] : "");
                        new ReminderStore(getApplicationContext()).save(reminder);
                        // Clear a saved draft on the same serial worker, including when the
                        // activity rotates.
                        getApplicationContext()
                                .getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit()
                                .remove(DRAFT)
                                .apply();
                        confirmation =
                                rescheduleAfterChange(
                                        ReminderScheduler.notificationsAllowed(
                                                        getApplicationContext())
                                                ? "Reminder saved."
                                                : "Reminder saved. Notifications are off.");
                    } catch (Exception e) {
                        AppLogger.e("Discipline reminder save failed", e);
                        failure = e;
                        getApplicationContext()
                                .getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit()
                                .putString(DRAFT, saving.json())
                                .apply();
                    }
                    Exception result = failure;
                    String savedConfirmation = confirmation;
                    runOnUiThread(
                            () -> {
                                if (isDestroyed() || isFinishing()) return;
                                busy = false;
                                savingEditor = false;
                                if (result == null) {
                                    draft = null;
                                    if (editor != null) editor.dismiss();
                                    if (active) {
                                        toast(savedConfirmation);
                                        refresh();
                                    }
                                } else if (editor != null) {
                                    draft = saving;
                                    enableFields(editorFields, true);
                                    mealInput.setEnabled(
                                            categoryInput.getSelectedItemPosition() == 0);
                                    editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    editor.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                                    editor.setCancelable(true);
                                    editorError.setText(
                                            "Couldn’t save. Your draft is kept. Please try again.");
                                }
                            });
                });
    }

    private static void enableFields(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                enableFields(group.getChildAt(i), enabled);
        }
    }

    private void exportDiagnostics() {
        mutate(
                () -> {
                    DiaryDbHelper db = new DiaryDbHelper(getApplicationContext());
                    try {
                        return "Saved to "
                                + DiagnosticsExporter.export(getApplicationContext(), db)
                                        .displayPath;
                    } finally {
                        db.close();
                    }
                },
                "Couldn’t export diagnostics. Please try again.");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static final class Draft {
        String id = UUID.randomUUID().toString(), title = "", notes = "";
        int category = 3, hour = 9, minute = 0, daysMask = 127, meal = 0;
        boolean enabled = true, existing;

        static Draft fresh(int category) {
            Draft value = new Draft();
            value.category = category;
            return value;
        }

        static Draft from(Reminder item) {
            Draft value = new Draft();
            value.id = item.id;
            value.title = item.title;
            value.notes = item.notes;
            value.category = item.category.ordinal();
            value.hour = item.hour;
            value.minute = item.minute;
            value.daysMask = item.daysMask;
            value.enabled = item.enabled;
            value.existing = true;
            for (int i = 0; i < MEAL_KEYS.length; i++)
                if (MEAL_KEYS[i].equals(item.mealKey)) value.meal = i;
            return value;
        }

        String json() {
            try {
                return new JSONObject()
                        .put("id", id)
                        .put("title", title)
                        .put("notes", notes)
                        .put("category", category)
                        .put("hour", hour)
                        .put("minute", minute)
                        .put("days", daysMask)
                        .put("meal", meal)
                        .put("enabled", enabled)
                        .put("existing", existing)
                        .toString();
            } catch (Exception impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        static Draft parse(String raw) {
            if (raw == null) return null;
            try {
                JSONObject json = new JSONObject(raw);
                Draft value = new Draft();
                value.id = json.getString("id");
                value.title = json.getString("title");
                value.notes = json.getString("notes");
                value.category = json.getInt("category");
                value.hour = json.getInt("hour");
                value.minute = json.getInt("minute");
                value.daysMask = json.getInt("days");
                value.meal = json.getInt("meal");
                value.enabled = json.getBoolean("enabled");
                value.existing = json.getBoolean("existing");
                if (value.category < 0
                        || value.category > 3
                        || value.hour < 0
                        || value.hour > 23
                        || value.minute < 0
                        || value.minute > 59
                        || value.daysMask < 0
                        || value.daysMask > 127
                        || value.meal < 0
                        || value.meal > 4) return null;
                return value;
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
