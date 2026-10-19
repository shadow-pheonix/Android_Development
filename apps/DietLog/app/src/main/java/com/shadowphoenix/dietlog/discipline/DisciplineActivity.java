package com.shadowphoenix.dietlog.discipline;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
    private static final String[] CATEGORIES = {"Food", "Skin", "Hair", "Other", "Supplements"};
    private static final int[] CATEGORY_ORDER = {0, 1, 2, 4, 3};
    private static final String[] MEALS = {
        "No meal link", "Breakfast", "Lunch", "Snacks", "Dinner"
    };
    private static final String[] MEAL_KEYS = {"", "breakfast", "lunch", "snacks", "dinner"};
    private boolean calendarView;
    private int calendarDays = 7;
    private final List<ConsistencyDay> calendarEntries = new ArrayList<>();
    private String calendarError;
    private String routineView = "";
    private LinearLayout body;
    private final List<Reminder> reminders = new ArrayList<>();
    private final Map<String, String> pauses = new HashMap<>();
    private final Map<String, Boolean> skips = new HashMap<>();
    private final Map<String, Boolean> completed = new HashMap<>();
    private final Map<String, LocalDate> carriedDates = new HashMap<>();
    private final List<ReminderStore.Completion> historyEntries = new ArrayList<>();
    private LocalDate historyDate = LocalDate.now();
    private boolean historyFollowsToday = true, historyView, historyLoading = true;
    private String historyError;
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
    private EditText routineInput,
            titleInput,
            notesInput,
            intervalInput,
            startInput,
            endInput,
            datesInput,
            extraTimesInput,
            followupInput;
    private Spinner scheduleInput;
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
            routineView = state.getString("routineView", "");
            historyView = state.getBoolean("historyView", false);
            calendarView = state.getBoolean("calendarView", false);
            calendarDays = state.getInt("calendarDays", 7) == 30 ? 30 : 7;
            historyFollowsToday = state.getBoolean("historyFollowsToday", true);
            try {
                historyDate =
                        LocalDate.parse(state.getString("historyDate", LocalDate.now().toString()));
            } catch (RuntimeException ignored) {
                historyDate = LocalDate.now();
            }
            if (historyDate.isAfter(LocalDate.now())) historyDate = LocalDate.now();
            if (historyDate.isBefore(LocalDate.now().minusDays(364)))
                historyDate = LocalDate.now().minusDays(364);
            String raw = state.getString(DRAFT);
            draft = Draft.parse(raw);
            draftLoaded = state.getBoolean("draftLoaded", false) || raw != null;
        }
        if (getIntent() != null && getIntent().hasExtra("reminder_id")) {
            filter = -1;
            historyView = false;
            calendarView = false;
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
        com.shadowphoenix.dietlog.updates.UpdateManager.get(this).check(false);
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
        out.putString("routineView", routineView);
        out.putBoolean("historyView", historyView);
        out.putBoolean("calendarView", calendarView);
        out.putInt("calendarDays", calendarDays);
        out.putBoolean("historyFollowsToday", historyFollowsToday);
        out.putString("historyDate", historyDate.toString());
        out.putBoolean("draftLoaded", !savingEditor && draftLoaded);
        if (draft != null) out.putString(DRAFT, draft.json());
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra("reminder_id")) {
            filter = -1;
            historyView = false;
            calendarView = false;
        }
        if (active) refresh();
    }

    private void refresh() {
        refresh(true);
    }

    private void refresh(boolean reschedule) {
        final int token = ++generation;
        final LocalDate date = LocalDate.now();
        final int days = calendarDays;
        final LocalDate logDate = historyFollowsToday ? date : historyDate;
        historyLoading = true;
        final boolean readDraft = reschedule && !draftLoaded;
        Context app = getApplicationContext();
        IO.execute(
                () -> {
                    List<Reminder> loaded = new ArrayList<>();
                    Map<String, Boolean> done = new HashMap<>();
                    Map<String, String> paused = new HashMap<>();
                    Map<String, Boolean> skipped = new HashMap<>();
                    Map<String, LocalDate> carry = new HashMap<>();
                    List<ReminderStore.Completion> entries = new ArrayList<>();
                    String logError = null;
                    List<ConsistencyDay> calendar = new ArrayList<>();
                    String calendarFailure = null;
                    SharedAppData.FuelNoteSnapshot shared = null;
                    String error = null, sharedError = null, scheduleFailure = null;
                    if (reschedule) {
                        try {
                            ReminderScheduler.rescheduleAll(app);
                        } catch (Exception e) {
                            AppLogger.e("Discipline reminder scheduling failed", e);
                            scheduleFailure =
                                    "Reminders are saved, but alert scheduling failed. Retry to set"
                                            + " up alerts.";
                        }
                    }
                    Draft saved = null;
                    try {
                        ReminderStore store = new ReminderStore(app);
                        loaded = store.list();
                        Map<String, ReminderStore.OccurrenceState> states =
                                store.statesForDate(date);
                        carry = store.outstandingDates(date);
                        for (Reminder parent : loaded)
                            for (Reminder item : parent.occurrences()) {
                                ReminderStore.OccurrenceState state = states.get(item.id);
                                if (state != null) {
                                    done.put(item.id, state.done);
                                    paused.put(item.id, state.pausedUntil);
                                    skipped.put(item.id, state.skipped);
                                }
                            }
                    } catch (Exception e) {
                        AppLogger.e("Discipline reminders load failed", e);
                        error = "Couldn’t load your reminders. Tap Retry.";
                    }
                    try {
                        entries = new ReminderStore(app).history(logDate);
                    } catch (Exception e) {
                        AppLogger.i(
                                "Discipline history load failed type="
                                        + e.getClass().getSimpleName());
                        logError = "Couldn’t load your activity log. Tap Retry.";
                    }
                    try {
                        calendar = new ReminderStore(app).calendar(days);
                    } catch (Exception e) {
                        calendarFailure = "Couldn’t load consistency. Please retry.";
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
                    Map<String, LocalDate> carryResult = carry;
                    List<ReminderStore.Completion> logResult = entries;
                    String logFailure = logError;
                    List<ConsistencyDay> calendarResult = calendar;
                    String calendarProblem = calendarFailure;
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
                                String requested = getIntent().getStringExtra("reminder_id");
                                if (requested != null) {
                                    for (Reminder parent : result)
                                        for (Reminder occurrence : parent.occurrences())
                                            if (occurrence.id.equals(requested))
                                                routineView = parent.routine;
                                    getIntent().removeExtra("reminder_id");
                                }
                                completed.clear();
                                completed.putAll(doneResult);
                                pauses.clear();
                                pauses.putAll(paused);
                                skips.clear();
                                skips.putAll(skipped);
                                carriedDates.clear();
                                carriedDates.putAll(carryResult);
                                calendarEntries.clear();
                                calendarEntries.addAll(calendarResult);
                                calendarError = calendarProblem;
                                historyDate = logDate;
                                historyEntries.clear();
                                historyEntries.addAll(logResult);
                                historyLoading = false;
                                historyError = logFailure;
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
        renderViewTabs();
        if (calendarView) {
            renderCalendar();
            return;
        }
        if (historyView) {
            renderHistory();
            return;
        }
        if (!routineView.isEmpty()) {
            DisciplineUi.add(
                    body,
                    DisciplineUi.button(
                            this,
                            "‹ All routines",
                            () -> {
                                routineView = "";
                                render();
                            }),
                    18);
            DisciplineUi.add(
                    body, DisciplineUi.text(this, routineView, 24, DisciplineUi.TEXT, true), 12);
            TextView addStep =
                    DisciplineUi.button(
                            this,
                            "+ Add checklist step",
                            () -> {
                                Draft next = Draft.fresh(3);
                                next.routine = routineView;
                                openDraft(next);
                            });
            addStep.setEnabled(!busy);
            DisciplineUi.add(body, addStep, 12);
            for (Reminder item : reminders)
                if (Reminder.routineKey(item.routine).equals(Reminder.routineKey(routineView))) {
                    for (Reminder occurrence : item.occurrences()) {
                        LocalDate carried = carriedDates.get(occurrence.id);
                        if (carried != null) renderCarriedReminder(occurrence, carried);
                    }
                    renderReminder(item);
                }
            return;
        }
        renderCounts();
        renderNotificationStatus();
        renderFuelNote();
        TextView add = DisciplineUi.button(this, "+  Add reminder", this::newReminder);
        add.setEnabled(!busy && !loading && loadError == null);
        DisciplineUi.clickable(add, DisciplineUi.BLUE, true);
        DisciplineUi.add(body, add, 22);
        TextView createRoutine =
                DisciplineUi.button(this, "+ Create routine", this::showRoutineCreator);
        createRoutine.setEnabled(!busy && !loading && loadError == null);
        DisciplineUi.add(body, createRoutine, 8);
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
            java.util.Set<String> renderedRoutines = new java.util.HashSet<>();
            for (Reminder item : visible) {
                if (!item.routine.isEmpty()) {
                    String key = Reminder.routineKey(item.routine);
                    if (renderedRoutines.add(key)) renderRoutine(item.routine);
                    continue;
                }
                for (Reminder occurrence : item.occurrences()) {
                    LocalDate carried = carriedDates.get(occurrence.id);
                    if (carried != null) renderCarriedReminder(occurrence, carried);
                }
                renderReminder(item);
            }
        }
        DisciplineUi.add(
                body,
                DisciplineUi.button(
                        this,
                        "App updates",
                        () ->
                                startActivity(
                                        new Intent(
                                                this,
                                                com.shadowphoenix.dietlog.updates.UpdateActivity
                                                        .class))),
                24);
        TextView diagnostics =
                DisciplineUi.button(this, "Export diagnostics", this::exportDiagnostics);
        diagnostics.setEnabled(!busy);
        DisciplineUi.add(body, diagnostics, 24);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        "Reminders and completion are saved locally and shared with FuelNote."
                                + " Marking a food reminder done does not add food to your diary.",
                        12,
                        DisciplineUi.MUTED,
                        false),
                12);
    }

    private void renderViewTabs() {
        LinearLayout tabs = DisciplineUi.row(this);
        TextView routines =
                DisciplineUi.button(
                        this,
                        "Routines",
                        () -> {
                            historyView = false;
                            calendarView = false;
                            render();
                        });
        TextView log =
                DisciplineUi.button(
                        this,
                        "Activity",
                        () -> {
                            historyView = true;
                            calendarView = false;
                            render();
                        });
        DisciplineUi.clickable(routines, DisciplineUi.BLUE, !historyView && !calendarView);
        DisciplineUi.clickable(log, DisciplineUi.BLUE, historyView);
        routines.setSelected(!historyView && !calendarView);
        log.setSelected(historyView);
        DisciplineUi.weighted(tabs, routines, 0);
        TextView calendar =
                DisciplineUi.button(
                        this,
                        "Consistency",
                        () -> {
                            calendarView = true;
                            historyView = false;
                            refresh(false);
                            render();
                        });
        DisciplineUi.clickable(calendar, DisciplineUi.BLUE, calendarView);
        calendar.setSelected(calendarView);
        DisciplineUi.weighted(tabs, calendar, 8);
        DisciplineUi.weighted(tabs, log, 8);
        DisciplineUi.add(body, tabs, 20);
    }

    private void renderCalendar() {
        DisciplineUi.add(
                body, DisciplineUi.text(this, "Your consistency", 24, DisciplineUi.TEXT, true), 22);
        LinearLayout range = DisciplineUi.row(this);
        for (int days : new int[] {7, 30}) {
            TextView choice =
                    DisciplineUi.button(
                            this,
                            days + " days",
                            () -> {
                                calendarDays = days;
                                refresh(false);
                                render();
                            });
            choice.setEnabled(!busy);
            DisciplineUi.clickable(choice, DisciplineUi.BLUE, calendarDays == days);
            DisciplineUi.weighted(range, choice, range.getChildCount() == 0 ? 0 : 8);
        }
        DisciplineUi.add(body, range, 12);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        "Done · blue    Skip · amber    Missed · rose\n"
                            + "Paused days are excluded. Today’s unfinished steps remain open. Tap"
                            + " a date for activity.",
                        13,
                        DisciplineUi.MUTED,
                        false),
                12);
        if (historyLoading) {
            DisciplineUi.add(
                    body,
                    DisciplineUi.text(this, "Loading consistency…", 15, DisciplineUi.MUTED, false),
                    18);
            return;
        }
        if (calendarError != null) {
            DisciplineUi.add(
                    body, DisciplineUi.text(this, calendarError, 15, DisciplineUi.TEXT, false), 18);
            DisciplineUi.add(body, DisciplineUi.button(this, "Retry", () -> refresh(false)), 8);
            return;
        }
        int complete = 0, skips = 0;
        for (ConsistencyDay day : calendarEntries) {
            complete += day.completedRoutines();
            skips += day.skipped;
        }
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        complete + " routines completed · " + skips + " deliberate skips",
                        15,
                        DisciplineUi.BLUE,
                        true),
                16);
        renderCalendarGrid();
        for (int i = calendarEntries.size() - 1; i >= 0; i--) {
            ConsistencyDay day = calendarEntries.get(i);
            LinearLayout card = DisciplineUi.card(this);
            TextView date =
                    DisciplineUi.button(
                            this,
                            day.date.format(DateTimeFormatter.ofPattern("EEE, d MMM"))
                                    + " · "
                                    + calendarMark(day),
                            calendarColor(day),
                            () -> selectHistoryDate(day.date));
            date.setEnabled(!busy);
            DisciplineUi.add(card, date, 0);
            if (day.expected == 0)
                DisciplineUi.add(
                        card,
                        DisciplineUi.text(
                                this,
                                day.paused > 0 ? "Paused · excluded" : "No scheduled activity",
                                13,
                                DisciplineUi.MUTED,
                                false),
                        6);
            for (ConsistencyDay.RoutineProgress routine : day.routines) {
                String detail =
                        routine.name + " · " + routine.done + "/" + routine.expected + " done";
                if (routine.skipped > 0) detail += " · " + routine.skipped + " skipped";
                if (routine.missed > 0) detail += " · " + routine.missed + " missed";
                if (routine.open > 0) detail += " · " + routine.open + " open";
                DisciplineUi.add(
                        card,
                        DisciplineUi.text(
                                this,
                                detail,
                                13,
                                routine.complete() ? DisciplineUi.BLUE : DisciplineUi.MUTED,
                                false),
                        8);
            }
            DisciplineUi.add(body, card, 12);
        }
    }

    private void renderCalendarGrid() {
        if (calendarEntries.isEmpty()) return;
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout grid = DisciplineUi.column(this);
        grid.setMinimumWidth(DisciplineUi.dp(this, 7 * 48 + 12));
        LinearLayout headings = DisciplineUi.row(this);
        for (String label : new String[] {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"})
            DisciplineUi.weighted(
                    headings, DisciplineUi.text(this, label, 11, DisciplineUi.MUTED, true), 0);
        DisciplineUi.add(grid, headings, 8);
        int offset = calendarEntries.get(0).date.getDayOfWeek().getValue() - 1;
        int cells = ((offset + calendarEntries.size() + 6) / 7) * 7;
        LinearLayout row = null;
        for (int i = 0; i < cells; i++) {
            if (i % 7 == 0) {
                row = DisciplineUi.row(this);
                DisciplineUi.add(grid, row, 6);
            }
            int index = i - offset;
            if (index < 0 || index >= calendarEntries.size()) {
                DisciplineUi.weighted(row, new View(this), i % 7 == 0 ? 0 : 2);
                continue;
            }
            ConsistencyDay day = calendarEntries.get(index);
            TextView cell =
                    DisciplineUi.button(
                            this,
                            day.date.format(DateTimeFormatter.ofPattern("d MMM"))
                                    + "\n"
                                    + calendarMark(day),
                            calendarColor(day),
                            () -> selectHistoryDate(day.date));
            cell.setTextSize(10);
            cell.setMinWidth(DisciplineUi.dp(this, 48));
            cell.setEnabled(!busy);
            cell.setPadding(0, DisciplineUi.dp(this, 8), 0, DisciplineUi.dp(this, 8));
            cell.setContentDescription(
                    day.date
                            + ": "
                            + day.done
                            + " done, "
                            + day.skipped
                            + " skipped, "
                            + day.missed
                            + " missed, "
                            + day.paused
                            + " paused. Open activity");
            DisciplineUi.weighted(row, cell, i % 7 == 0 ? 0 : 2);
        }
        scroll.addView(grid);
        DisciplineUi.add(body, scroll, 12);
    }

    private String calendarMark(ConsistencyDay day) {
        if (day.expected == 0) return day.paused > 0 ? "Paused" : "—";
        return day.done + "/" + day.expected + (day.skipped > 0 ? " · S" + day.skipped : "");
    }

    private int calendarColor(ConsistencyDay day) {
        if (day.expected > 0 && day.done == day.expected) return DisciplineUi.BLUE;
        if (day.skipped > 0) return 0xFFFFCF83;
        if (day.missed > 0) return 0xFFFFB9BB;
        return DisciplineUi.MUTED;
    }

    private void renderHistory() {
        DisciplineUi.add(
                body,
                DisciplineUi.text(this, "Your daily activity", 23, DisciplineUi.TEXT, true),
                24);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        "Done, Skipped and Missed are shown for the original reminder day. Paused"
                                + " days are excluded.",
                        14,
                        DisciplineUi.MUTED,
                        false),
                8);
        LinearLayout dateRow = DisciplineUi.row(this);
        TextView previous =
                DisciplineUi.button(this, "‹", () -> selectHistoryDate(historyDate.minusDays(1)));
        previous.setContentDescription("Previous activity day");
        previous.setEnabled(!busy && historyDate.isAfter(LocalDate.now().minusDays(364)));
        dateRow.addView(previous);
        TextView date =
                DisciplineUi.button(
                        this,
                        historyDate.format(
                                DateTimeFormatter.ofPattern(
                                        "EEE, d MMM yyyy", Locale.getDefault())),
                        this::pickHistoryDate);
        date.setContentDescription("Choose activity log date");
        date.setEnabled(!busy);
        DisciplineUi.weighted(dateRow, date, 6);
        TextView next =
                DisciplineUi.button(this, "›", () -> selectHistoryDate(historyDate.plusDays(1)));
        next.setContentDescription("Next activity day");
        next.setEnabled(!busy && historyDate.isBefore(LocalDate.now()));
        dateRow.addView(next);
        DisciplineUi.add(body, dateRow, 18);
        if (!historyDate.equals(LocalDate.now())) {
            DisciplineUi.add(
                    body,
                    DisciplineUi.button(this, "Today", () -> selectHistoryDate(LocalDate.now())),
                    8);
        }
        if (historyLoading) {
            DisciplineUi.add(
                    body,
                    DisciplineUi.text(this, "Loading activity…", 15, DisciplineUi.MUTED, false),
                    20);
        } else if (historyError != null) {
            DisciplineUi.add(
                    body, DisciplineUi.text(this, historyError, 15, DisciplineUi.MUTED, false), 20);
            DisciplineUi.add(body, DisciplineUi.button(this, "Retry", () -> refresh(false)), 10);
        } else if (historyEntries.isEmpty()) {
            LinearLayout empty = DisciplineUi.card(this);
            DisciplineUi.add(
                    empty,
                    DisciplineUi.text(
                            this, "No activity for this day", 18, DisciplineUi.TEXT, true),
                    0);
            DisciplineUi.add(
                    empty,
                    DisciplineUi.text(
                            this,
                            "Mark a routine Done in the app or notification to save it here"
                                    + " automatically.",
                            14,
                            DisciplineUi.MUTED,
                            false),
                    10);
            DisciplineUi.add(body, empty, 20);
        } else {
            DisciplineUi.add(
                    body,
                    DisciplineUi.text(
                            this,
                            historyEntries.size()
                                    + (historyEntries.size() == 1 ? " activity" : " activities"),
                            15,
                            DisciplineUi.BLUE,
                            true),
                    18);
            for (ReminderStore.Completion entry : historyEntries) {
                LinearLayout card = DisciplineUi.card(this);
                DisciplineUi.add(
                        card,
                        DisciplineUi.text(
                                this,
                                CATEGORIES[entry.category.ordinal()],
                                12,
                                DisciplineUi.BLUE,
                                true),
                        0);
                DisciplineUi.add(
                        card,
                        DisciplineUi.text(
                                this,
                                entry.title
                                        + (entry.scheduledTime.isEmpty()
                                                ? ""
                                                : " · " + entry.scheduledTime),
                                19,
                                DisciplineUi.TEXT,
                                true),
                        8);
                String when =
                        entry.outcome != ReminderStore.Outcome.DONE
                                ? (entry.outcome == ReminderStore.Outcome.SKIPPED
                                        ? "Skipped deliberately"
                                        : "Missed")
                                : entry.completedAt == 0
                                        ? "Done · time not recorded in the earlier version"
                                        : "Done "
                                                + Instant.ofEpochMilli(entry.completedAt)
                                                        .atZone(ZoneId.systemDefault())
                                                        .format(
                                                                DateTimeFormatter.ofPattern(
                                                                        DateFormat.is24HourFormat(
                                                                                        this)
                                                                                ? "d MMM, HH:mm"
                                                                                : "d MMM, h:mm a",
                                                                        Locale.getDefault()));
                DisciplineUi.add(
                        card, DisciplineUi.text(this, when, 13, DisciplineUi.MUTED, false), 8);
                boolean present = false;
                for (Reminder reminder : reminders)
                    if (reminder.id.equals(entry.parentId)) {
                        present = true;
                        break;
                    }
                if (present && entry.outcome != ReminderStore.Outcome.MISSED) {
                    TextView undo =
                            DisciplineUi.button(
                                    this,
                                    entry.outcome == ReminderStore.Outcome.SKIPPED
                                            ? "Undo skip"
                                            : "Undo completion",
                                    () ->
                                            mutate(
                                                    () -> {
                                                        ReminderStore store =
                                                                new ReminderStore(
                                                                        getApplicationContext());
                                                        if (store.find(entry.reminderId) == null)
                                                            return "The reminder was removed. Its"
                                                                    + " saved activity is"
                                                                    + " retained.";
                                                        if (entry.outcome
                                                                == ReminderStore.Outcome.SKIPPED)
                                                            store.setSkipped(
                                                                    entry.reminderId,
                                                                    entry.date,
                                                                    false);
                                                        else
                                                            store.setCompleted(
                                                                    entry.reminderId,
                                                                    entry.date,
                                                                    false);
                                                        return rescheduleAfterChange(
                                                                "Completion removed from this day’s"
                                                                        + " log.");
                                                    }));
                    undo.setEnabled(!busy);
                    DisciplineUi.add(card, undo, 12);
                }
                DisciplineUi.add(body, card, 14);
            }
        }
        TextView export = DisciplineUi.button(this, "Export diagnostics", this::exportDiagnostics);
        export.setEnabled(!busy);
        DisciplineUi.add(body, export, 24);
    }

    private void selectHistoryDate(LocalDate date) {
        if (busy || date.isAfter(LocalDate.now()) || date.isBefore(LocalDate.now().minusDays(364)))
            return;
        calendarView = false;
        historyView = true;
        historyDate = date;
        historyFollowsToday = date.equals(LocalDate.now());
        refresh(false);
        render();
    }

    private void pickHistoryDate() {
        LocalDate date = historyDate;
        DatePickerDialog picker =
                new DatePickerDialog(
                        this,
                        R.style.DisciplineDialogTheme,
                        (view, year, month, day) ->
                                selectHistoryDate(LocalDate.of(year, month + 1, day)),
                        date.getYear(),
                        date.getMonthValue() - 1,
                        date.getDayOfMonth());
        ZoneId zone = ZoneId.systemDefault();
        picker.getDatePicker()
                .setMaxDate(LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli());
        picker.getDatePicker()
                .setMinDate(
                        LocalDate.now()
                                .minusDays(364)
                                .atStartOfDay(zone)
                                .toInstant()
                                .toEpochMilli());
        picker.show();
    }

    private String followupTime(Reminder item) {
        LocalTime first = LocalTime.of(item.hour, item.minute);
        java.time.ZonedDateTime next =
                today.atTime(first)
                        .atZone(ZoneId.systemDefault())
                        .plusMinutes(item.plan.followupMinutes);
        return clock(next.getHour(), next.getMinute())
                + (next.toLocalDate().equals(today) ? "" : " tomorrow");
    }

    private void renderCarriedReminder(Reminder item, LocalDate date) {
        LinearLayout card = DisciplineUi.card(this);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        "FROM YESTERDAY · " + CATEGORIES[item.category.ordinal()],
                        12,
                        DisciplineUi.BLUE,
                        true),
                0);
        DisciplineUi.add(card, DisciplineUi.text(this, item.title, 19, DisciplineUi.TEXT, true), 8);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        "This late reminder belongs to "
                                + date.format(
                                        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
                                + ". Completing it logs that day.",
                        14,
                        DisciplineUi.MUTED,
                        false),
                8);
        TextView done =
                DisciplineUi.button(
                        this,
                        "Complete yesterday’s routine",
                        () ->
                                mutate(
                                        () -> {
                                            new ReminderStore(getApplicationContext())
                                                    .setCompleted(item.id, date, true);
                                            return rescheduleAfterChange(
                                                    "Completed and saved to yesterday’s activity"
                                                            + " log.");
                                        }));
        done.setEnabled(!busy);
        DisciplineUi.add(card, done, 12);
        DisciplineUi.add(body, card, 16);
    }

    private void renderCounts() {
        int due = 0, upcoming = 0, done = 0;
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        for (Reminder parent : reminders)
            for (Reminder item : parent.occurrences()) {
                if (!item.enabled
                        || pauses.get(item.id) != null
                        || Boolean.TRUE.equals(skips.get(item.id))
                        || !scheduledToday(item)) continue;
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
                        ? "Reminders are saved. Alerts cannot appear until notifications are"
                                + " allowed."
                        : !exact
                                ? "Android may deliver alerts later. Precise timing is optional;"
                                        + " reminders still work without it."
                                : "Enabled reminders can alert at the times you choose. Android’s"
                                        + " battery settings may affect delivery.";
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
        for (int position = -1; position < CATEGORY_ORDER.length; position++) {
            final int i = position < 0 ? -1 : CATEGORY_ORDER[position];
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
        String[] quickNames = {
            "Food reminder", "Skincare reminder", "Haircare reminder", "Supplement reminder"
        };
        int[] quickCategories = {0, 1, 2, 4};
        for (int i = 0; i < quickNames.length; i++) {
            final int category = quickCategories[i];
            DisciplineUi.add(
                    card,
                    DisciplineUi.button(
                            this, "+ " + quickNames[i], () -> openDraft(Draft.fresh(category))),
                    10);
        }
        DisciplineUi.add(body, card, 18);
    }

    private boolean scheduledToday(Reminder item) {
        return item.plan.occurs(today, item.daysMask);
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
        if (pauses.get(item.id) != null) return "Paused until " + pauses.get(item.id);
        if (Boolean.TRUE.equals(skips.get(item.id))) return "Skipped today";
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
                        clock(item.hour, item.minute) + "  ·  " + scheduleLabel(item),
                        14,
                        DisciplineUi.MUTED,
                        false),
                6);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        "Follow-up · " + followupTime(item) + " if unfinished",
                        13,
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
        for (Reminder occurrence : item.occurrences()) {
            if (item.occurrences().size() > 1)
                DisciplineUi.add(
                        card,
                        DisciplineUi.text(
                                this,
                                clock(occurrence.hour, occurrence.minute)
                                        + " · "
                                        + status(occurrence),
                                15,
                                DisciplineUi.BLUE,
                                true),
                        14);
            renderOccurrenceActions(card, occurrence);
        }
        TextView datedPause =
                DisciplineUi.button(
                        this,
                        pauses.get(item.id) == null ? "Pause until…" : "Resume now",
                        () -> {
                            if (pauses.get(item.id) != null)
                                mutate(
                                        () -> {
                                            new ReminderStore(getApplicationContext())
                                                    .pauseUntil(item.id, null);
                                            return rescheduleAfterChange("Reminder resumed.");
                                        });
                            else choosePause(item);
                        });
        datedPause.setEnabled(!busy);
        DisciplineUi.add(card, datedPause, 8);
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
                                                                    item.mealKey,
                                                                    item.plan,
                                                                    item.routine));
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
                                                "This removes future reminders. Your completed"
                                                        + " daily activity stays in the log.")
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

    private void renderRoutine(String name) {
        LinearLayout card = DisciplineUi.card(this);
        int total = 0, done = 0, skipped = 0, steps = 0;
        List<String> preview = new ArrayList<>();
        for (Reminder item : reminders)
            if (Reminder.routineKey(item.routine).equals(Reminder.routineKey(name))) {
                steps++;
                if (preview.size() < 3) preview.add(item.title);
                for (Reminder occurrence : item.occurrences()) {
                    if (!scheduledToday(occurrence)
                            || !occurrence.enabled
                            || pauses.get(occurrence.id) != null) continue;
                    total++;
                    if (isDone(occurrence)) done++;
                    else if (Boolean.TRUE.equals(skips.get(occurrence.id))) skipped++;
                }
            }
        DisciplineUi.add(card, DisciplineUi.text(this, name, 21, DisciplineUi.TEXT, true), 0);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        total == 0
                                ? "No active steps today"
                                : done
                                        + "/"
                                        + total
                                        + " done today"
                                        + (skipped == 0 ? "" : " · " + skipped + " skipped"),
                        14,
                        DisciplineUi.BLUE,
                        false),
                6);
        DisciplineUi.add(
                card,
                DisciplineUi.text(
                        this,
                        String.join(" · ", preview)
                                + (steps > 3 ? " · +" + (steps - 3) + " more" : ""),
                        13,
                        DisciplineUi.MUTED,
                        false),
                8);
        TextView open =
                DisciplineUi.button(
                        this,
                        "Open checklist · " + steps + " steps",
                        () -> {
                            routineView = name;
                            render();
                        });
        open.setEnabled(!busy);
        DisciplineUi.add(card, open, 12);
        DisciplineUi.add(body, card, 16);
    }

    private void showRoutineCreator() {
        Draft next = Draft.fresh(3);
        next.checklist = true;
        openDraft(next);
    }

    private void renderOccurrenceActions(LinearLayout card, Reminder item) {
        if (scheduledToday(item) && pauses.get(item.id) == null) {
            LinearLayout actions = DisciplineUi.row(this);
            boolean done = isDone(item);
            final LocalDate actionDate = today;
            TextView complete =
                    DisciplineUi.button(
                            this,
                            done ? "Undo today" : "Done today",
                            () ->
                                    mutate(
                                            () -> {
                                                new ReminderStore(getApplicationContext())
                                                        .setCompleted(item.id, actionDate, !done);
                                                return rescheduleAfterChange(
                                                        done
                                                                ? "Marked not done for today."
                                                                : "Completed and saved in your"
                                                                        + " daily log.");
                                            }));
            complete.setEnabled(!busy && !Boolean.TRUE.equals(skips.get(item.id)));
            DisciplineUi.weighted(actions, complete, 0);
            if (item.enabled && !done && !Boolean.TRUE.equals(skips.get(item.id))) {
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
                                                            ? "Notifications are off. No alert can"
                                                                    + " appear."
                                                            : scheduled
                                                                    ? "Reminder snoozed for 15"
                                                                            + " minutes."
                                                                    : "Couldn’t snooze this"
                                                                          + " reminder. Try again.";
                                                }));
                snooze.setEnabled(!busy);
                DisciplineUi.weighted(actions, snooze, 8);
            }
            DisciplineUi.add(card, actions, 14);
        }
        if (scheduledToday(item) && !isDone(item) && pauses.get(item.id) == null) {
            boolean skipped = Boolean.TRUE.equals(skips.get(item.id));
            TextView skip =
                    DisciplineUi.button(
                            this,
                            skipped ? "Undo skip" : "Skip today",
                            () ->
                                    mutate(
                                            () -> {
                                                new ReminderStore(getApplicationContext())
                                                        .setSkipped(item.id, today, !skipped);
                                                return rescheduleAfterChange(
                                                        skipped
                                                                ? "Skip undone."
                                                                : "Skipped this occurrence.");
                                            }));
            skip.setEnabled(!busy);
            DisciplineUi.add(card, skip, 8);
        }
    }

    private String scheduleLabel(Reminder item) {
        String label =
                item.plan.mode == ReminderPlan.Mode.DATES
                        ? item.plan.dates.size() + " selected dates"
                        : item.plan.mode == ReminderPlan.Mode.INTERVAL
                                ? "Every " + item.plan.intervalDays + " days"
                                : days(item.daysMask);
        if (item.plan.start != null) label += " · from " + item.plan.start;
        if (item.plan.end != null) label += " · through " + item.plan.end;
        if (!item.plan.extraTimes.isEmpty())
            label += " · " + item.occurrences().size() + " times/day";
        return label;
    }

    private void choosePause(Reminder item) {
        LocalDate monday =
                LocalDate.now()
                        .with(
                                java.time.temporal.TemporalAdjusters.next(
                                        java.time.DayOfWeek.MONDAY));
        new AlertDialog.Builder(this, R.style.DisciplineDialogTheme)
                .setTitle("Pause reminder")
                .setItems(
                        new String[] {"Pause until Monday", "Choose resume date…"},
                        (dialog, which) -> {
                            if (which == 0) savePause(item, monday);
                            else {
                                LocalDate next = LocalDate.now().plusDays(1);
                                DatePickerDialog picker =
                                        new DatePickerDialog(
                                                this,
                                                R.style.DisciplineDialogTheme,
                                                (v, y, m, d) ->
                                                        savePause(item, LocalDate.of(y, m + 1, d)),
                                                next.getYear(),
                                                next.getMonthValue() - 1,
                                                next.getDayOfMonth());
                                picker.getDatePicker()
                                        .setMinDate(
                                                next.atStartOfDay(ZoneId.systemDefault())
                                                        .toInstant()
                                                        .toEpochMilli());
                                picker.show();
                            }
                        })
                .show();
    }

    private void savePause(Reminder item, LocalDate until) {
        mutate(
                () -> {
                    new ReminderStore(getApplicationContext()).pauseUntil(item.id, until);
                    return rescheduleAfterChange(
                            "Paused. Alerts resume automatically on " + until + ".");
                });
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
        if (calendarView) {
            calendarView = false;
            render();
            return;
        }
        if (historyView) {
            historyView = false;
            render();
            return;
        }
        if (!routineView.isEmpty()) {
            routineView = "";
            render();
            return;
        }
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
                            "Discipline needs notification permission to show your saved reminders."
                                    + " You can keep using routines without alerts.")
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
                                "Notifications are still off. You can enable them later in Android"
                                        + " settings.")
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
        label(fields, value.checklist ? "Routine name" : "Reminder title");
        titleInput =
                input(
                        value.title,
                        value.checklist ? "Morning routine or Before bed" : "e.g. Evening skincare",
                        value.checklist ? 60 : 120,
                        false);
        DisciplineUi.add(fields, titleInput, 4);
        if (!value.checklist) label(fields, "Routine (optional)");
        routineInput = input(value.routine, "e.g. Morning routine or Before bed", 60, false);
        DisciplineUi.add(fields, routineInput, 4);
        routineInput.setVisibility(value.checklist ? View.GONE : View.VISIBLE);
        label(
                fields,
                value.checklist
                        ? "Checklist (one step per line, up to 12 steps)"
                        : "Notes (optional)");
        notesInput =
                input(
                        value.notes,
                        value.checklist
                                ? "Brush teeth\nSkincare\nPrepare tomorrow’s clothes"
                                : "Your routine or a small nudge",
                        value.checklist ? 1500 : 1000,
                        true);
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
        DisciplineUi.add(
                fields,
                DisciplineUi.text(
                        this,
                        "Each time has its own Done or Skip record. A follow-up arrives 60 minutes"
                            + " later by default, adjustable below. Completing it saves the day’s"
                            + " log and stops the follow-up. Late follow-ups can arrive after"
                            + " midnight for the original day.",
                        13,
                        DisciplineUi.MUTED,
                        false),
                10);
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
        label(fields, "Schedule type");
        scheduleInput =
                spinner(
                        new String[] {"Selected weekdays", "Every few days", "Selected dates"},
                        value.mode);
        DisciplineUi.add(fields, scheduleInput, 4);
        label(fields, "Every how many days? (interval schedules)");
        intervalInput = input(value.interval, "e.g. 3", 3, false);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        DisciplineUi.add(fields, intervalInput, 4);
        label(fields, "Start date (optional; required for intervals)");
        startInput = input(value.start, "YYYY-MM-DD", 10, false);
        DisciplineUi.add(fields, startInput, 4);
        addDatePicker(fields, startInput, "Choose start date");
        label(fields, "End date (optional, inclusive)");
        endInput = input(value.end, "YYYY-MM-DD", 10, false);
        DisciplineUi.add(fields, endInput, 4);
        addDatePicker(fields, endInput, "Choose end date");
        label(fields, "Selected dates (comma separated)");
        datesInput = input(value.dates, "2026-11-02, 2026-11-05", 4392, true);
        DisciplineUi.add(fields, datesInput, 4);
        label(fields, "Additional times (24-hour, comma separated)");
        extraTimesInput = input(value.times, "e.g. 14:00, 21:30", 60, false);
        DisciplineUi.add(fields, extraTimesInput, 4);
        DisciplineUi.add(
                fields,
                DisciplineUi.button(
                        this,
                        "Add another time",
                        () -> {
                            new TimePickerDialog(
                                            this,
                                            R.style.DisciplineDialogTheme,
                                            (view, h, m) -> {
                                                String previous =
                                                        extraTimesInput.getText().toString().trim();
                                                extraTimesInput.setText(
                                                        previous
                                                                + (previous.isEmpty() ? "" : ", ")
                                                                + String.format(
                                                                        Locale.ROOT,
                                                                        "%02d:%02d",
                                                                        h,
                                                                        m));
                                            },
                                            18,
                                            0,
                                            DateFormat.is24HourFormat(this))
                                    .show();
                        }),
                4);
        label(fields, "Follow-up delay in minutes (default 60)");
        followupInput = input(value.followup, "60", 4, false);
        followupInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        DisciplineUi.add(fields, followupInput, 4);
        scheduleInput.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    public void onItemSelected(
                            android.widget.AdapterView<?> parent,
                            View view,
                            int position,
                            long id) {
                        intervalInput.setEnabled(position == 1);
                        datesInput.setEnabled(position == 2);
                        for (CheckBox day : dayInputs) day.setEnabled(position == 0);
                    }

                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });
        if (!value.checklist) label(fields, "FuelNote meal link (food reminders only)");
        mealInput = spinner(MEALS, value.meal);
        DisciplineUi.add(fields, mealInput, 4);
        mealInput.setVisibility(value.checklist ? View.GONE : View.VISIBLE);
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
        enabledInput = check("Enabled", value.checklist || value.enabled);
        enabledInput.setVisibility(value.checklist ? View.GONE : View.VISIBLE);
        DisciplineUi.add(fields, enabledInput, 12);
        editorError = DisciplineUi.text(this, "", 13, 0xFFFFB9BB, false);
        editorError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        DisciplineUi.add(fields, editorError, 6);
        editor =
                new AlertDialog.Builder(this, R.style.DisciplineDialogTheme)
                        .setTitle(
                                value.checklist
                                        ? "Create routine"
                                        : value.existing ? "Edit reminder" : "New reminder")
                        .setView(scroll)
                        .setNegativeButton("Cancel", (d, w) -> discardDraft())
                        .setPositiveButton(
                                value.checklist ? "Create routine" : "Save reminder", null)
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

    private void addDatePicker(LinearLayout fields, EditText target, String label) {
        DisciplineUi.add(
                fields,
                DisciplineUi.button(
                        this,
                        label,
                        () -> {
                            LocalDate current;
                            try {
                                current = LocalDate.parse(target.getText().toString());
                            } catch (Exception ignored) {
                                current = LocalDate.now();
                            }
                            new DatePickerDialog(
                                            this,
                                            R.style.DisciplineDialogTheme,
                                            (view, y, m, d) ->
                                                    target.setText(
                                                            LocalDate.of(y, m + 1, d).toString()),
                                            current.getYear(),
                                            current.getMonthValue() - 1,
                                            current.getDayOfMonth())
                                    .show();
                        }),
                4);
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
        draft.routine = routineInput.getText().toString();
        draft.notes = notesInput.getText().toString();
        draft.mode = scheduleInput.getSelectedItemPosition();
        draft.interval = intervalInput.getText().toString();
        draft.start = startInput.getText().toString();
        draft.end = endInput.getText().toString();
        draft.dates = datesInput.getText().toString();
        draft.times = extraTimesInput.getText().toString();
        draft.followup = followupInput.getText().toString();
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
        if (draft.mode == 0 && draft.daysMask == 0) {
            editorError.setText("Choose at least one repeat day.");
            return;
        }
        try {
            draft.plan();
            if (draft.checklist) {
                Reminder.routineName(draft.title);
                draft.checklistSteps();
            }
        } catch (Exception invalid) {
            editorError.setText(
                    invalid instanceof IllegalArgumentException
                                    && invalid.getMessage() != null
                                    && !invalid.getMessage().startsWith("Text")
                                    && !invalid.getMessage().startsWith("For input")
                            ? invalid.getMessage()
                            : "Check dates (YYYY-MM-DD), times (HH:mm), interval and follow-up.");
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
                                        saving.checklist ? "" : saving.notes.trim(),
                                        Reminder.Category.values()[saving.category],
                                        saving.hour,
                                        saving.minute,
                                        saving.daysMask == 0 ? 127 : saving.daysMask,
                                        saving.enabled,
                                        saving.category == 0 ? MEAL_KEYS[saving.meal] : "",
                                        saving.plan(),
                                        saving.routine);
                        ReminderStore store = new ReminderStore(getApplicationContext());
                        if (saving.checklist)
                            store.createRoutine(
                                    saving.title,
                                    saving.checklistSteps(),
                                    saving.hour,
                                    saving.minute,
                                    saving.plan(),
                                    Reminder.Category.values()[saving.category],
                                    saving.daysMask == 0 ? 127 : saving.daysMask);
                        else store.save(reminder);
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
                                                ? (saving.checklist
                                                        ? "Routine created."
                                                        : "Reminder saved.")
                                                : (saving.checklist
                                                        ? "Routine created. Notifications are off."
                                                        : "Reminder saved. Notifications are"
                                                                + " off."));
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
        String id = UUID.randomUUID().toString(), title = "", notes = "", routine = "";
        int category = 3, hour = 9, minute = 0, daysMask = 127, meal = 0;
        boolean enabled = true, existing, checklist;
        int mode = 0;
        String interval = "1", start = "", end = "", dates = "", times = "", followup = "60";

        List<String> checklistSteps() {
            List<String> steps = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String line : notes.split("\n"))
                if (!line.trim().isEmpty()) {
                    if (line.trim().length() > 120)
                        throw new IllegalArgumentException(
                                "Keep each checklist step under 120 characters");
                    if (!seen.add(line.trim().toLowerCase(Locale.ROOT)))
                        throw new IllegalArgumentException("Use distinct checklist steps");
                    steps.add(line.trim());
                }
            if (steps.isEmpty() || steps.size() > 12)
                throw new IllegalArgumentException("Use 1–12 checklist steps");
            return steps;
        }

        ReminderPlan plan() {
            List<LocalDate> selected = new ArrayList<>();
            List<LocalTime> extra = new ArrayList<>();
            if (mode == 2 && !dates.trim().isEmpty())
                for (String value : dates.split(",")) selected.add(LocalDate.parse(value.trim()));
            if (!times.trim().isEmpty())
                for (String value : times.split(",")) extra.add(LocalTime.parse(value.trim()));
            if (extra.contains(LocalTime.of(hour, minute)))
                throw new IllegalArgumentException(
                        "The main time is already scheduled; remove it from additional times");
            if (new java.util.HashSet<>(extra).size() != extra.size())
                throw new IllegalArgumentException("Use each scheduled time only once");
            return new ReminderPlan(
                    ReminderPlan.Mode.values()[mode],
                    start.trim().isEmpty() ? null : LocalDate.parse(start.trim()),
                    end.trim().isEmpty() ? null : LocalDate.parse(end.trim()),
                    mode == 1 ? Integer.parseInt(interval.trim()) : 1,
                    selected,
                    extra,
                    Integer.parseInt(followup.trim()));
        }

        private static String join(java.util.Collection<?> values) {
            StringBuilder joined = new StringBuilder();
            for (Object value : values) {
                if (joined.length() > 0) joined.append(", ");
                joined.append(value);
            }
            return joined.toString();
        }

        static Draft fresh(int category) {
            Draft value = new Draft();
            value.category = category;
            return value;
        }

        static Draft from(Reminder item) {
            Draft value = new Draft();
            value.id = item.id;
            value.title = item.title;
            value.routine = item.routine;
            value.notes = item.notes;
            value.category = item.category.ordinal();
            value.hour = item.hour;
            value.minute = item.minute;
            value.daysMask = item.daysMask;
            value.enabled = item.enabled;
            value.existing = true;
            value.mode = item.plan.mode.ordinal();
            value.interval = String.valueOf(item.plan.intervalDays);
            value.start = item.plan.start == null ? "" : item.plan.start.toString();
            value.end = item.plan.end == null ? "" : item.plan.end.toString();
            value.dates = join(item.plan.dates);
            value.times = join(item.plan.extraTimes);
            value.followup = String.valueOf(item.plan.followupMinutes);
            for (int i = 0; i < MEAL_KEYS.length; i++)
                if (MEAL_KEYS[i].equals(item.mealKey)) value.meal = i;
            return value;
        }

        String json() {
            try {
                return new JSONObject()
                        .put("id", id)
                        .put("title", title)
                        .put("routine", routine)
                        .put("notes", notes)
                        .put("category", category)
                        .put("hour", hour)
                        .put("minute", minute)
                        .put("days", daysMask)
                        .put("meal", meal)
                        .put("enabled", enabled)
                        .put("existing", existing)
                        .put("checklist", checklist)
                        .put("mode", mode)
                        .put("interval", interval)
                        .put("start", start)
                        .put("end", end)
                        .put("dates", dates)
                        .put("times", times)
                        .put("followup", followup)
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
                value.routine = json.optString("routine");
                value.notes = json.getString("notes");
                value.category = json.getInt("category");
                value.hour = json.getInt("hour");
                value.minute = json.getInt("minute");
                value.daysMask = json.getInt("days");
                value.meal = json.getInt("meal");
                value.enabled = json.getBoolean("enabled");
                value.existing = json.getBoolean("existing");
                value.checklist = json.optBoolean("checklist");
                value.mode = json.optInt("mode", 0);
                value.interval = json.optString("interval", "1");
                value.start = json.optString("start");
                value.end = json.optString("end");
                value.dates = json.optString("dates");
                value.times = json.optString("times");
                value.followup = json.optString("followup", "60");
                if (value.mode < 0 || value.mode > 2) return null;
                if (value.category < 0
                        || value.category >= CATEGORIES.length
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
