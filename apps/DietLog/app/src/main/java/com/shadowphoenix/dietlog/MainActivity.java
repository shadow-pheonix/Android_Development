package com.shadowphoenix.dietlog;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.data.FoodEntry;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.diagnostics.DiagnosticsExporter;
import com.shadowphoenix.dietlog.food.CatalogFood;
import com.shadowphoenix.dietlog.food.FoodCatalogDb;
import com.shadowphoenix.dietlog.profile.ProfileController;
import com.shadowphoenix.dietlog.ui.RingProgressView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final double CALORIE_GOAL = 2000.0;
    private static final double PROTEIN_GOAL = 150.0;
    private static final double CARBS_GOAL = 250.0;
    private static final double FAT_GOAL = 70.0;

    private DiaryDbHelper diary;
    private FoodCatalogDb catalog;
    private ProfileController profileController;

    private View homeScreen, searchScreen, detailScreen, manualScreen, progressScreen, profileScreen, bottomNav;
    private View navHome, navSearch, navAdd, navProgress, navProfile;
    private TextView navHomeIcon, navHomeLabel, navSearchIcon, navSearchLabel;
    private TextView navProgressIcon, navProgressLabel, navProfileIcon, navProfileLabel;

    private RingProgressView caloriesRing, proteinRing, carbsRing, fatRing;
    private TextView calorieValue, calorieGoalText, calorieLeft, proteinValue, carbsValue, fatValue;
    private TextView dateText, foodLogTitle;
    private LinearLayout entriesContainer;

    private EditText searchInput;
    private TextView databaseStatusText, profileDatabaseStatus, searchSectionTitle;
    private LinearLayout searchResultsContainer;

    private TextView detailEmoji, detailName, detailSubtitle, detailSourceChip, detailCategoryChip, detailBrandChip;
    private TextView detailCalories100, detailProtein100, detailCarbs100, detailFat100, detailServingLabel;
    private TextView detailTotalCalories, detailTotalProtein, detailTotalCarbs, detailTotalFat;
    private TextView detailTotalFiber, detailTotalSugar, detailTotalSodium, detailExtra, servingCountText;
    private EditText detailGramsInput;
    private CatalogFood selectedFood;
    private int servingCount = 1;
    private double baseServingGrams = 100.0;
    private boolean internalGramsChange;

    private EditText nameInput, gramsInput, caloriesInput, proteinInput, carbsInput, fatInput;
    private TextView detailAddButton, manualAddButton;
    private String activeMealType = FoodEntry.mealForHour(LocalTime.now().getHour());
    private LocalDate selectedDate = LocalDate.now();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.init(this);
        CrashHandler.install(this);
        AppLogger.i("MainActivity created - dark UI baseline");
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        setContentView(R.layout.activity_main);

        diary = new DiaryDbHelper(this);
        if (savedInstanceState != null) {
            String savedDate = savedInstanceState.getString("selected_date");
            if (savedDate != null) {
                try { selectedDate = LocalDate.parse(savedDate); } catch (Exception ignored) {}
            }
            activeMealType = FoodEntry.normalizeMealType(savedInstanceState.getString("active_meal", activeMealType));
        }
        bindViews();
        profileController = new ProfileController(this, this::refreshHome);
        profileController.bind();
        configureRings();
        configureNavigation();
        configureSearch();
        configureDetail();
        configureManualEntry();
        configureDiagnostics();
        configureWeekStrip();

        try {
            catalog = new FoodCatalogDb(this);
            String status = String.format(Locale.getDefault(),
                    "%,d foods offline • %,d packaged • %,d cooked Indian dishes",
                    catalog.count(), catalog.countBySource("OFF"), catalog.countBySource("INDB"));
            databaseStatusText.setText(status);
            profileDatabaseStatus.setText("●  " + status);
            loadPopularFoods();
        } catch (Exception e) {
            AppLogger.e("Offline catalog failed to open", e);
            databaseStatusText.setText("Offline database unavailable in this build");
            profileDatabaseStatus.setText("Offline database unavailable");
        }

        refreshHome();
        showScreen(homeScreen, 0);
    }

    private void bindViews() {
        homeScreen = findViewById(R.id.homeScreen);
        searchScreen = findViewById(R.id.searchScreen);
        detailScreen = findViewById(R.id.detailScreen);
        manualScreen = findViewById(R.id.manualScreen);
        progressScreen = findViewById(R.id.progressScreen);
        profileScreen = findViewById(R.id.profileScreen);
        bottomNav = findViewById(R.id.bottomNav);

        navHome = findViewById(R.id.navHome);
        navSearch = findViewById(R.id.navSearch);
        navAdd = findViewById(R.id.navAdd);
        navProgress = findViewById(R.id.navProgress);
        navProfile = findViewById(R.id.navProfile);
        navHomeIcon = findViewById(R.id.navHomeIcon);
        navHomeLabel = findViewById(R.id.navHomeLabel);
        navSearchIcon = findViewById(R.id.navSearchIcon);
        navSearchLabel = findViewById(R.id.navSearchLabel);
        navProgressIcon = findViewById(R.id.navProgressIcon);
        navProgressLabel = findViewById(R.id.navProgressLabel);
        navProfileIcon = findViewById(R.id.navProfileIcon);
        navProfileLabel = findViewById(R.id.navProfileLabel);

        caloriesRing = findViewById(R.id.caloriesRing);
        proteinRing = findViewById(R.id.proteinRing);
        carbsRing = findViewById(R.id.carbsRing);
        fatRing = findViewById(R.id.fatRing);
        calorieValue = findViewById(R.id.calorieValue);
        calorieGoalText = findViewById(R.id.calorieGoalText);
        calorieLeft = findViewById(R.id.calorieLeft);
        proteinValue = findViewById(R.id.proteinValue);
        carbsValue = findViewById(R.id.carbsValue);
        fatValue = findViewById(R.id.fatValue);
        dateText = findViewById(R.id.dateText);
        entriesContainer = findViewById(R.id.entriesContainer);
        ViewGroup logCard = (ViewGroup) entriesContainer.getParent();
        ViewGroup logHeader = (ViewGroup) logCard.getChildAt(0);
        foodLogTitle = (TextView) logHeader.getChildAt(0);

        searchInput = findViewById(R.id.searchInput);
        databaseStatusText = findViewById(R.id.databaseStatusText);
        profileDatabaseStatus = findViewById(R.id.profileDatabaseStatus);
        searchSectionTitle = findViewById(R.id.searchSectionTitle);
        searchResultsContainer = findViewById(R.id.searchResultsContainer);

        detailEmoji = findViewById(R.id.detailEmoji);
        detailName = findViewById(R.id.detailName);
        detailSubtitle = findViewById(R.id.detailSubtitle);
        detailSourceChip = findViewById(R.id.detailSourceChip);
        detailCategoryChip = findViewById(R.id.detailCategoryChip);
        detailBrandChip = findViewById(R.id.detailBrandChip);
        detailCalories100 = findViewById(R.id.detailCalories100);
        detailProtein100 = findViewById(R.id.detailProtein100);
        detailCarbs100 = findViewById(R.id.detailCarbs100);
        detailFat100 = findViewById(R.id.detailFat100);
        detailServingLabel = findViewById(R.id.detailServingLabel);
        detailTotalCalories = findViewById(R.id.detailTotalCalories);
        detailTotalProtein = findViewById(R.id.detailTotalProtein);
        detailTotalCarbs = findViewById(R.id.detailTotalCarbs);
        detailTotalFat = findViewById(R.id.detailTotalFat);
        detailTotalFiber = findViewById(R.id.detailTotalFiber);
        detailTotalSugar = findViewById(R.id.detailTotalSugar);
        detailTotalSodium = findViewById(R.id.detailTotalSodium);
        detailExtra = findViewById(R.id.detailExtra);
        servingCountText = findViewById(R.id.servingCount);
        detailGramsInput = findViewById(R.id.detailGramsInput);
        detailAddButton = findViewById(R.id.detailAddButton);

        nameInput = findViewById(R.id.nameInput);
        gramsInput = findViewById(R.id.gramsInput);
        caloriesInput = findViewById(R.id.caloriesInput);
        proteinInput = findViewById(R.id.proteinInput);
        carbsInput = findViewById(R.id.carbsInput);
        fatInput = findViewById(R.id.fatInput);
        manualAddButton = findViewById(R.id.addButton);
        updateMealTargetLabels();
    }

    private void configureRings() {
        caloriesRing.setStrokeWidthDp(11f);
        caloriesRing.setColors(getColor(R.color.accent), getColor(R.color.cyan));
        proteinRing.setStrokeWidthDp(7f);
        proteinRing.setColors(getColor(R.color.protein), getColor(R.color.cyan));
        carbsRing.setStrokeWidthDp(7f);
        carbsRing.setColors(getColor(R.color.carbs), 0xFFFFA92E);
        fatRing.setStrokeWidthDp(7f);
        fatRing.setColors(getColor(R.color.fat), 0xFFFFD052);
    }

    private void configureNavigation() {
        navHome.setOnClickListener(v -> showScreen(homeScreen, 0));
        navSearch.setOnClickListener(v -> {
            setActiveMeal(defaultMealType());
            if (catalog != null) loadPopularFoods();
            showScreen(searchScreen, 1);
        });
        navAdd.setOnClickListener(v -> {
            setActiveMeal(defaultMealType());
            showManualEntry(null, null);
        });
        navProgress.setOnClickListener(v -> showScreen(progressScreen, 2));
        navProfile.setOnClickListener(v -> showScreen(profileScreen, 3));
        findViewById(R.id.homeSeeAll).setOnClickListener(v -> {
            setActiveMeal(defaultMealType());
            if (catalog != null) loadPopularFoods();
            showScreen(searchScreen, 1);
        });
    }

    private void configureSearch() {
        findViewById(R.id.searchButton).setOnClickListener(v -> search());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search();
                return true;
            }
            return false;
        });
        findViewById(R.id.manualEntryButton).setOnClickListener(v -> showManualEntry(null, null));
        findViewById(R.id.barcodeTile).setOnClickListener(v -> toast("Barcode scanner will be added next."));
        findViewById(R.id.voiceTile).setOnClickListener(v -> toast("Voice search will be added later."));
    }

    private void configureDetail() {
        findViewById(R.id.detailBack).setOnClickListener(v -> showScreen(searchScreen, 1));
        findViewById(R.id.servingMinus).setOnClickListener(v -> changeServingCount(-1));
        findViewById(R.id.servingPlus).setOnClickListener(v -> changeServingCount(1));
        detailAddButton.setOnClickListener(v -> addSelectedFood());
        detailGramsInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!internalGramsChange) updateDetailTotals();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void configureManualEntry() {
        findViewById(R.id.manualBack).setOnClickListener(v -> showScreen(searchScreen, 1));
        manualAddButton.setOnClickListener(v -> addManualFood());
    }

    private void configureDiagnostics() {
        findViewById(R.id.exportButton).setOnClickListener(v -> exportDiagnostics());
    }

    private void configureWeekStrip() {
        ViewGroup root = (ViewGroup) dateText.getParent();
        int dateIndex = root.indexOfChild(dateText);
        root.removeView(dateText);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        controlsParams.topMargin = dp(14);
        controls.setLayoutParams(controlsParams);

        TextView previous = dateControl("‹", 42);
        previous.setContentDescription("Previous week");
        previous.setOnClickListener(v -> selectDate(selectedDate.minusWeeks(1)));
        controls.addView(previous);

        dateText.setVisibility(View.VISIBLE);
        dateText.setGravity(android.view.Gravity.CENTER);
        dateText.setTextColor(getColor(R.color.text_primary));
        dateText.setTextSize(13f);
        dateText.setTypeface(dateText.getTypeface(), android.graphics.Typeface.BOLD);
        dateText.setOnClickListener(v -> selectDate(LocalDate.now()));
        controls.addView(dateText, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView today = dateControl("Today", 64);
        today.setContentDescription("Jump to today");
        today.setOnClickListener(v -> selectDate(LocalDate.now()));
        controls.addView(today);

        TextView next = dateControl("›", 42);
        next.setContentDescription("Next week");
        next.setOnClickListener(v -> selectDate(selectedDate.plusWeeks(1)));
        controls.addView(next);

        root.addView(controls, dateIndex);
        renderWeekStrip();
    }

    private TextView dateControl(String text, int widthDp) {
        TextView control = new TextView(this);
        control.setText(text);
        control.setGravity(android.view.Gravity.CENTER);
        control.setTextColor(getColor(R.color.accent));
        control.setTextSize(13f);
        control.setTypeface(control.getTypeface(), android.graphics.Typeface.BOLD);
        control.setBackgroundResource(R.drawable.bg_chip);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(36));
        params.setMarginStart(dp(4));
        params.setMarginEnd(dp(4));
        control.setLayoutParams(params);
        return control;
    }

    private void selectDate(LocalDate date) {
        selectedDate = date;
        renderWeekStrip();
        refreshHome();
        AppLogger.i("Diary date selected=" + selectedDate);
    }

    private void renderWeekStrip() {
        LocalDate today = LocalDate.now();
        LocalDate monday = selectedDate.minusDays(selectedDate.getDayOfWeek().getValue() - 1L);
        DateTimeFormatter headerFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault());
        dateText.setText(selectedDate.equals(today) ? "Today • " + selectedDate.format(headerFormat) : selectedDate.format(headerFormat));
        for (int i = 0; i < 7; i++) {
            int id = getResources().getIdentifier("day" + i, "id", getPackageName());
            TextView day = findViewById(id);
            LocalDate date = monday.plusDays(i);
            String dow = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
            day.setText(dow + System.lineSeparator() + date.getDayOfMonth());
            day.setOnClickListener(v -> selectDate(date));
            if (date.equals(selectedDate)) {
                day.setBackgroundResource(R.drawable.bg_calendar_selected);
                day.setTextColor(getColor(R.color.accent));
                day.setTypeface(day.getTypeface(), android.graphics.Typeface.BOLD);
            } else {
                day.setBackgroundResource(R.drawable.bg_calendar);
                day.setTextColor(date.equals(today) ? getColor(R.color.accent) : getColor(R.color.text_secondary));
                day.setTypeface(day.getTypeface(), date.equals(today) ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void showScreen(View target, int navIndex) {
        homeScreen.setVisibility(target == homeScreen ? View.VISIBLE : View.GONE);
        searchScreen.setVisibility(target == searchScreen ? View.VISIBLE : View.GONE);
        detailScreen.setVisibility(target == detailScreen ? View.VISIBLE : View.GONE);
        manualScreen.setVisibility(target == manualScreen ? View.VISIBLE : View.GONE);
        progressScreen.setVisibility(target == progressScreen ? View.VISIBLE : View.GONE);
        profileScreen.setVisibility(target == profileScreen ? View.VISIBLE : View.GONE);
        boolean immersive = target == detailScreen || target == manualScreen;
        bottomNav.setVisibility(immersive ? View.GONE : View.VISIBLE);
        if (!immersive) updateNavColors(navIndex);
    }

    private void updateNavColors(int selected) {
        TextView[] icons = {navHomeIcon, navSearchIcon, navProgressIcon, navProfileIcon};
        TextView[] labels = {navHomeLabel, navSearchLabel, navProgressLabel, navProfileLabel};
        for (int i = 0; i < icons.length; i++) {
            int color = i == selected ? getColor(R.color.accent) : getColor(R.color.text_secondary);
            icons[i].setTextColor(color);
            labels[i].setTextColor(color);
            labels[i].setTypeface(labels[i].getTypeface(), i == selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void refreshHome() {
        double[] totals = diary.getTotalsForDate(selectedDate);
        double calorieGoal = profileController == null ? CALORIE_GOAL : profileController.getCalorieGoal();
        double proteinGoal = profileController == null ? PROTEIN_GOAL : profileController.getProteinGoal();
        double carbsGoal = profileController == null ? CARBS_GOAL : profileController.getCarbsGoal();
        double fatGoal = profileController == null ? FAT_GOAL : profileController.getFatGoal();
        calorieValue.setText(String.format(Locale.getDefault(), "%.0f", totals[0]));
        calorieGoalText.setText(String.format(Locale.getDefault(), "of %,.0f kcal", calorieGoal));
        calorieLeft.setText(String.format(Locale.getDefault(), "%.0f", Math.max(0, calorieGoal - totals[0])));
        proteinValue.setText(String.format(Locale.getDefault(), "%.0f / %.0fg", totals[1], proteinGoal));
        carbsValue.setText(String.format(Locale.getDefault(), "%.0f / %.0fg", totals[2], carbsGoal));
        fatValue.setText(String.format(Locale.getDefault(), "%.0f / %.0fg", totals[3], fatGoal));
        caloriesRing.setProgress((float) (totals[0] / calorieGoal));
        proteinRing.setProgress((float) (totals[1] / proteinGoal));
        carbsRing.setProgress(carbsGoal <= 0.0 ? 0f : (float) (totals[2] / carbsGoal));
        fatRing.setProgress((float) (totals[3] / fatGoal));
        LocalDate today = LocalDate.now();
        foodLogTitle.setText(selectedDate.equals(today) ? "Today’s Food Log" :
                "Food Log • " + selectedDate.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())));
        renderDiaryEntries(diary.getEntriesForDate(selectedDate));
    }

    private void renderDiaryEntries(List<FoodEntry> entries) {
        entriesContainer.removeAllViews();
        renderMealSection(FoodEntry.MEAL_BREAKFAST, "☀", entries);
        renderMealSection(FoodEntry.MEAL_LUNCH, "🍱", entries);
        renderMealSection(FoodEntry.MEAL_SNACKS, "🍎", entries);
        renderMealSection(FoodEntry.MEAL_DINNER, "☾", entries);
    }

    private void renderMealSection(String mealType, String icon, List<FoodEntry> entries) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (entriesContainer.getChildCount() > 0) sectionParams.topMargin = dp(14);
        section.setLayoutParams(sectionParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(icon + "  " + FoodEntry.mealDisplayName(mealType));
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1f));

        double mealCalories = 0.0;
        for (FoodEntry entry : entries) {
            if (mealType.equals(entry.mealType)) mealCalories += entry.calories;
        }
        TextView calories = new TextView(this);
        calories.setText(String.format(Locale.getDefault(), "%.0f kcal", mealCalories));
        calories.setTextColor(getColor(R.color.text_secondary));
        calories.setTextSize(11f);
        calories.setGravity(android.view.Gravity.CENTER_VERTICAL);
        calories.setPadding(dp(8), 0, dp(10), 0);
        header.addView(calories, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));

        TextView add = new TextView(this);
        add.setText("+ Add");
        add.setTextColor(getColor(R.color.accent));
        add.setTextSize(12f);
        add.setTypeface(add.getTypeface(), android.graphics.Typeface.BOLD);
        add.setGravity(android.view.Gravity.CENTER);
        add.setPadding(dp(12), 0, dp(12), 0);
        add.setBackgroundResource(R.drawable.bg_chip);
        add.setOnClickListener(v -> openMealSearch(mealType));
        header.addView(add, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        section.addView(header);

        boolean hasEntries = false;
        for (FoodEntry entry : entries) {
            if (!mealType.equals(entry.mealType)) continue;
            hasEntries = true;
            View row = getLayoutInflater().inflate(R.layout.item_diary_entry, section, false);
            ((TextView) row.findViewById(R.id.entryIcon)).setText(foodEmoji(entry.name));
            ((TextView) row.findViewById(R.id.entryName)).setText(entry.name);
            ((TextView) row.findViewById(R.id.entryAmount)).setText(String.format(Locale.getDefault(), "%.0f g  •  %s", entry.grams, friendlySource(entry.source)));
            ((TextView) row.findViewById(R.id.entryMacros)).setText(String.format(Locale.getDefault(), "P %.0fg    C %.0fg    F %.0fg", entry.protein, entry.carbs, entry.fat));
            ((TextView) row.findViewById(R.id.entryCalories)).setText(String.format(Locale.getDefault(), "%.0f kcal", entry.calories));
            row.setOnClickListener(v -> showEntryActions(entry));
            row.setContentDescription("Edit " + entry.name);
            section.addView(row);
        }

        if (!hasEntries) {
            TextView empty = new TextView(this);
            empty.setText("No foods logged");
            empty.setTextColor(getColor(R.color.text_tertiary));
            empty.setTextSize(12f);
            empty.setPadding(dp(4), dp(4), dp(4), dp(8));
            section.addView(empty);
        }
        entriesContainer.addView(section);
    }

    private void showEntryActions(FoodEntry entry) {
        String[] actions = {"Edit amount", "Move to another meal", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(entry.name + " • " + formatAmount(entry.grams) + " g")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showEditAmountDialog(entry);
                    else if (which == 1) showMoveMealDialog(entry);
                    else showDeleteEntryDialog(entry);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditAmountDialog(FoodEntry entry) {
        EditText amount = new EditText(this);
        amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setSingleLine(true);
        amount.setText(formatAmount(entry.grams));
        amount.setSelectAllOnFocus(true);
        amount.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(22), 0, dp(22), 0);
        container.addView(amount, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        new AlertDialog.Builder(this)
                .setTitle("Edit amount • " + entry.name)
                .setMessage("Enter the amount in grams. Calories and macros will scale automatically.")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        double grams = Double.parseDouble(amount.getText().toString().trim());
                        FoodEntry updated = entry.withAmount(grams);
                        if (diary.update(updated) == 1) {
                            AppLogger.i("Diary entry amount updated id=" + entry.id + " grams=" + grams);
                            refreshHome();
                            toast("Amount updated");
                        } else {
                            toast("Unable to update entry");
                        }
                    } catch (Exception e) {
                        toast("Enter a valid amount greater than zero");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showMoveMealDialog(FoodEntry entry) {
        String[] labels = {"Breakfast", "Lunch", "Snacks", "Dinner"};
        String[] values = {FoodEntry.MEAL_BREAKFAST, FoodEntry.MEAL_LUNCH, FoodEntry.MEAL_SNACKS, FoodEntry.MEAL_DINNER};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(entry.mealType)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle("Move " + entry.name)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    FoodEntry updated = entry.withMealType(values[which]);
                    if (diary.update(updated) == 1) {
                        AppLogger.i("Diary entry moved id=" + entry.id + " meal=" + values[which]);
                        refreshHome();
                        toast("Moved to " + labels[which]);
                    } else {
                        toast("Unable to move entry");
                    }
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDeleteEntryDialog(FoodEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + entry.name + "?")
                .setMessage("This removes the entry from " + selectedDate.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) + ".")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (diary.delete(entry.id) == 1) {
                        AppLogger.i("Diary entry deleted id=" + entry.id);
                        refreshHome();
                        toast("Entry deleted");
                    } else {
                        toast("Unable to delete entry");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openMealSearch(String mealType) {
        setActiveMeal(mealType);
        searchInput.setText("");
        if (catalog != null) loadPopularFoods();
        showScreen(searchScreen, 1);
    }

    private void search() {
        if (catalog == null) return;
        String q = searchInput.getText().toString().trim();
        if (q.isEmpty()) {
            loadPopularFoods();
            return;
        }
        List<CatalogFood> results = catalog.search(q, 30);
        AppLogger.i("Offline search queryLength=" + q.length() + " results=" + results.size());
        searchSectionTitle.setText("Search Results • " + mealTargetLabel());
        renderFoodResults(results);
    }

    private void loadPopularFoods() {
        if (catalog == null) return;
        String[] queries = {"idli", "upma", "biryani", "paneer", "bread", "dosa", "milk"};
        List<CatalogFood> foods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String q : queries) {
            for (CatalogFood food : catalog.search(q, 20)) {
                if (food.hasCompleteCoreMacros() && food.code != null && seen.add(food.code)) {
                    foods.add(food);
                    break;
                }
            }
        }
        searchSectionTitle.setText("Popular Foods • " + mealTargetLabel());
        renderFoodResults(foods);
    }

    private void renderFoodResults(List<CatalogFood> results) {
        searchResultsContainer.removeAllViews();
        if (results.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.no_results));
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(13f);
            empty.setPadding(dp(4), dp(14), dp(4), dp(14));
            searchResultsContainer.addView(empty);
            return;
        }
        for (CatalogFood food : results) {
            View row = getLayoutInflater().inflate(R.layout.item_food_search, searchResultsContainer, false);
            ((TextView) row.findViewById(R.id.foodIcon)).setText(foodEmoji(food.name));
            ((TextView) row.findViewById(R.id.foodName)).setText(food.name);
            ((TextView) row.findViewById(R.id.foodSubtitle)).setText(searchSubtitle(food));
            ((TextView) row.findViewById(R.id.foodMacros)).setText(searchMacroLine(food));
            row.setOnClickListener(v -> openDetail(food));
            row.findViewById(R.id.foodAdd).setOnClickListener(v -> openDetail(food));
            searchResultsContainer.addView(row);
        }
    }

    private String searchSubtitle(CatalogFood food) {
        if (food.servingSize != null && !food.servingSize.trim().isEmpty()) return food.servingSize;
        if (food.brand != null && !food.brand.trim().isEmpty()) return food.brand + " • " + friendlySource(food.source);
        return friendlySource(food.source) + (food.category == null ? "" : " • " + compact(food.category, 32));
    }

    private String searchMacroLine(CatalogFood food) {
        String kcal = food.calories == null ? "—" : String.format(Locale.getDefault(), "%.0f", food.calories);
        String line = kcal + " kcal/100g   P " + numberOrDash(food.protein) +
                "   C " + numberOrDash(food.carbs) + "   F " + numberOrDash(food.fat);
        return food.hasCompleteCoreMacros() ? line : line + "   • Incomplete nutrition";
    }

    private void openDetail(CatalogFood food) {
        selectedFood = food;
        servingCount = 1;
        baseServingGrams = food.servingG != null && food.servingG > 0 ? food.servingG : 100.0;
        detailEmoji.setText(foodEmoji(food.name));
        detailName.setText(food.name);
        detailSubtitle.setText(detailSubtitle(food));
        detailSourceChip.setText(friendlySource(food.source));
        detailCategoryChip.setText(compact(food.category == null ? "Food" : food.category, 26));
        detailBrandChip.setText(compact(food.brand == null ? "Offline" : food.brand, 24));
        detailCalories100.setText(food.calories == null ? "—" : String.format(Locale.getDefault(), "%.0f", food.calories));
        detailProtein100.setText(numberOrDash(food.protein));
        detailCarbs100.setText(numberOrDash(food.carbs));
        detailFat100.setText(numberOrDash(food.fat));
        detailServingLabel.setText(servingLabel(food));
        servingCountText.setText("1");
        setDetailGrams(baseServingGrams);
        detailExtra.setText(detailExtra(food));
        updateDetailTotals();
        updateMealTargetLabels();
        AppLogger.i("Catalog food selected source=" + food.source + " code=" + food.code + " meal=" + activeMealType);
        showScreen(detailScreen, -1);
    }

    private String detailSubtitle(CatalogFood food) {
        if (food.brand != null && !food.brand.trim().isEmpty()) return food.brand;
        if (food.category != null && !food.category.trim().isEmpty()) return compact(food.category, 55);
        return friendlySource(food.source) + " food";
    }

    private String servingLabel(CatalogFood food) {
        if (food.servingSize != null && !food.servingSize.trim().isEmpty()) {
            if (food.servingG != null && food.servingG > 0) return food.servingSize + "  •  " + formatAmount(food.servingG) + " g";
            return food.servingSize;
        }
        return formatAmount(baseServingGrams) + " g";
    }

    private String detailExtra(CatalogFood food) {
        StringBuilder out = new StringBuilder();
        appendLine(out, "Brand", food.brand);
        appendLine(out, "Pack", food.quantity);
        appendLine(out, "Barcode", food.barcode);
        appendLine(out, "Ingredients", food.ingredients);
        appendLine(out, "Allergens", food.allergens);
        appendLine(out, "Labels", food.labels);
        appendLine(out, "Nutri-Score", food.nutriscore == null ? null : food.nutriscore.toUpperCase(Locale.ROOT));
        appendLine(out, "NOVA", food.nova == null ? null : String.valueOf(food.nova));
        appendLine(out, "Source", friendlySource(food.source));
        if (out.length() == 0) return "No additional details are available for this record.";
        return out.toString().trim();
    }

    private static void appendLine(StringBuilder out, String label, String value) {
        if (value != null && !value.trim().isEmpty()) out.append(label).append(": ").append(value.trim()).append(System.lineSeparator());
    }

    private void changeServingCount(int delta) {
        if (selectedFood == null) return;
        servingCount = Math.max(1, servingCount + delta);
        servingCountText.setText(String.valueOf(servingCount));
        setDetailGrams(baseServingGrams * servingCount);
        updateDetailTotals();
    }

    private void setDetailGrams(double grams) {
        internalGramsChange = true;
        detailGramsInput.setText(formatAmount(grams));
        detailGramsInput.setSelection(detailGramsInput.length());
        internalGramsChange = false;
    }

    private void updateDetailTotals() {
        if (selectedFood == null) return;
        double grams = parseOptional(detailGramsInput, baseServingGrams * servingCount);
        double factor = grams / 100.0;
        detailTotalCalories.setText(selectedFood.calories == null ? "— kcal" : String.format(Locale.getDefault(), "%.0f kcal", selectedFood.calories * factor));
        detailTotalProtein.setText("Protein" + System.lineSeparator() + scaled(selectedFood.protein, factor, "g"));
        detailTotalCarbs.setText("Carbs" + System.lineSeparator() + scaled(selectedFood.carbs, factor, "g"));
        detailTotalFat.setText("Fat" + System.lineSeparator() + scaled(selectedFood.fat, factor, "g"));
        detailTotalFiber.setText("Fibre" + System.lineSeparator() + scaled(selectedFood.fiber, factor, "g"));
        detailTotalSugar.setText("Sugar" + System.lineSeparator() + scaled(selectedFood.sugars, factor, "g"));
        detailTotalSodium.setText("Sodium" + System.lineSeparator() + (selectedFood.sodium == null ? "—" : String.format(Locale.getDefault(), "%.0fmg", selectedFood.sodium * factor * 1000.0)));
    }

    private String scaled(Double value, double factor, String suffix) {
        return value == null ? "—" : String.format(Locale.getDefault(), "%.1f%s", value * factor, suffix);
    }

    private void addSelectedFood() {
        if (selectedFood == null) return;
        if (!selectedFood.hasCompleteCoreMacros()) {
            toast("This record is missing a core macro. Complete it in Manual Food.");
            showManualEntry(selectedFood.name, selectedFood);
            return;
        }
        try {
            double grams = positive(detailGramsInput, "amount");
            FoodEntry entry = FoodEntry.fromPer100g(selectedFood.name, grams, selectedFood.calories,
                    selectedFood.protein, selectedFood.carbs, selectedFood.fat,
                    selectedDateTimestamp(), activeMealType, selectedFood.source, selectedFood.code);
            long id = diary.insert(entry);
            AppLogger.i("Food entry added id=" + id + " source=" + selectedFood.source + " meal=" + activeMealType);
            refreshHome();
            toast(addConfirmationText());
            showScreen(homeScreen, 0);
        } catch (Exception e) {
            AppLogger.e("Failed to add selected food", e);
            toast("Unable to add food");
        }
    }

    private void showManualEntry(String prefillName, CatalogFood sourceFood) {
        clearManualInputs();
        if (prefillName != null) nameInput.setText(prefillName);
        if (sourceFood != null) {
            if (sourceFood.servingG != null) gramsInput.setText(formatAmount(sourceFood.servingG));
            setNumber(caloriesInput, sourceFood.calories);
            setNumber(proteinInput, sourceFood.protein);
            setNumber(carbsInput, sourceFood.carbs);
            setNumber(fatInput, sourceFood.fat);
        }
        updateMealTargetLabels();
        showScreen(manualScreen, -1);
    }

    private void addManualFood() {
        try {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Enter a food name");
            double grams = positive(gramsInput, "amount");
            double calories = nonNegative(caloriesInput, "calories");
            double protein = nonNegative(proteinInput, "protein");
            double carbs = nonNegative(carbsInput, "carbs");
            double fat = nonNegative(fatInput, "fat");
            FoodEntry entry = FoodEntry.fromPer100g(name, grams, calories, protein, carbs, fat, selectedDateTimestamp(), activeMealType);
            long id = diary.insert(entry);
            AppLogger.i("Food entry added id=" + id + " source=manual meal=" + activeMealType);
            clearManualInputs();
            refreshHome();
            toast(addConfirmationText());
            showScreen(homeScreen, 0);
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
        } catch (Exception e) {
            AppLogger.e("Failed to add manual food", e);
            toast("Unable to add food");
        }
    }

    private void clearManualInputs() {
        nameInput.setText("");
        gramsInput.setText("");
        caloriesInput.setText("");
        proteinInput.setText("");
        carbsInput.setText("");
        fatInput.setText("");
    }

    private void exportDiagnostics() {
        try {
            AppLogger.i("Diagnostics export requested");
            DiagnosticsExporter.ExportResult result = DiagnosticsExporter.export(this, diary);
            Toast.makeText(this, "Saved: " + result.displayPath, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLogger.e("Diagnostics export failed", e);
            Toast.makeText(this, "Diagnostics export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String foodEmoji(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("idli")) return "◉";
        if (n.contains("dosa") || n.contains("chapati") || n.contains("roti")) return "🥙";
        if (n.contains("biryani") || n.contains("rice") || n.contains("pulao")) return "🍚";
        if (n.contains("paneer") || n.contains("curry") || n.contains("dal") || n.contains("sambar")) return "🍛";
        if (n.contains("bread") || n.contains("bun")) return "🍞";
        if (n.contains("milk") || n.contains("curd") || n.contains("yogurt")) return "🥛";
        if (n.contains("banana")) return "🍌";
        if (n.contains("apple")) return "🍎";
        if (n.contains("egg")) return "🥚";
        if (n.contains("chicken")) return "🍗";
        if (n.contains("tea") || n.contains("coffee")) return "☕";
        return "🍽";
    }

    private String friendlySource(String source) {
        if (source == null) return "Manual";
        switch (source.toUpperCase(Locale.ROOT)) {
            case "OFF": return "Packaged";
            case "INDB": return "Indian dish";
            case "IFCT": return "Indian ingredient";
            case "USDA": return "Food database";
            default: return source.substring(0, 1).toUpperCase(Locale.ROOT) + source.substring(1).toLowerCase(Locale.ROOT);
        }
    }

    private String numberOrDash(Double value) {
        return value == null ? "—" : String.format(Locale.getDefault(), "%.1fg", value);
    }

    private String compact(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace((char) 10, ' ').trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, Math.max(1, max - 1)) + "…";
    }

    private String formatAmount(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) return String.format(Locale.US, "%.0f", value);
        return String.format(Locale.US, "%.1f", value);
    }

    private void setNumber(EditText editText, Double value) {
        editText.setText(value == null ? "" : formatAmount(value));
    }

    private double positive(EditText input, String label) {
        double value = parse(input, label);
        if (value <= 0) throw new IllegalArgumentException(label + " must be greater than zero");
        return value;
    }

    private double nonNegative(EditText input, String label) {
        double value = parse(input, label);
        if (value < 0) throw new IllegalArgumentException(label + " cannot be negative");
        return value;
    }

    private double parse(EditText input, String label) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Enter " + label);
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private double parseOptional(EditText input, double fallback) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return fallback;
        try {
            double parsed = Double.parseDouble(raw);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }


    private long selectedDateTimestamp() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        if (selectedDate.equals(today)) return System.currentTimeMillis();
        return selectedDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();
    }

    private String addConfirmationText() {
        String text = "Added to " + FoodEntry.mealDisplayName(activeMealType);
        if (!selectedDate.equals(LocalDate.now())) {
            text += " • " + selectedDate.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
        }
        return text;
    }

    private String defaultMealType() {
        return FoodEntry.mealForHour(LocalTime.now().getHour());
    }

    private void setActiveMeal(String mealType) {
        activeMealType = FoodEntry.normalizeMealType(mealType);
        updateMealTargetLabels();
    }

    private String mealTargetLabel() {
        String label = FoodEntry.mealDisplayName(activeMealType);
        if (!selectedDate.equals(LocalDate.now())) {
            label += " • " + selectedDate.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()));
        }
        return label;
    }

    private void updateMealTargetLabels() {
        String target = mealTargetLabel();
        if (detailAddButton != null) detailAddButton.setText("+  Add to " + target);
        if (manualAddButton != null) manualAddButton.setText("+  Add to " + target);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString("selected_date", selectedDate.toString());
        outState.putString("active_meal", activeMealType);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (profileController != null && profileController.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (detailScreen.getVisibility() == View.VISIBLE || manualScreen.getVisibility() == View.VISIBLE) {
            showScreen(searchScreen, 1);
        } else if (homeScreen.getVisibility() != View.VISIBLE) {
            showScreen(homeScreen, 0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (catalog != null) catalog.close();
        diary.close();
        super.onDestroy();
    }
}
