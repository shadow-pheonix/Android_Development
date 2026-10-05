package com.shadowphoenix.dietlog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
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
import com.shadowphoenix.dietlog.data.FoodHistory;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.diagnostics.DiagnosticsExporter;
import com.shadowphoenix.dietlog.food.CatalogFood;
import com.shadowphoenix.dietlog.food.FoodCatalogDb;
import com.shadowphoenix.dietlog.food.ServingOption;
import com.shadowphoenix.dietlog.profile.ProfileController;
import com.shadowphoenix.dietlog.progress.ProgressController;
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
    private static final String SERVING_PREFS = "serving_memory";
    private static final int BARCODE_SCAN_REQUEST = 2001;

    private DiaryDbHelper diary;
    private FoodCatalogDb catalog;
    private ProfileController profileController;
    private ProgressController progressController;

    private View homeScreen, searchScreen, detailScreen, manualScreen, progressScreen, profileScreen, bottomNav;
    private View navHome, navSearch, navAdd, navProgress, navProfile;
    private TextView navHomeIcon, navHomeLabel, navSearchIcon, navSearchLabel;
    private TextView navProgressIcon, navProgressLabel, navProfileIcon, navProfileLabel;

    private RingProgressView caloriesRing, proteinRing, carbsRing, fatRing;
    private TextView calorieValue, calorieGoalText, calorieLeft, proteinValue, carbsValue, fatValue;
    private TextView dateText, foodLogTitle, relogButton;
    private LinearLayout entriesContainer;

    private EditText searchInput;
    private TextView databaseStatusText, profileDatabaseStatus, searchSectionTitle;
    private TextView shortcutAll, shortcutRecent, shortcutFrequent, shortcutFavorites;
    private LinearLayout searchResultsContainer;

    private TextView detailEmoji, detailName, detailSubtitle, detailSourceChip, detailCategoryChip, detailBrandChip;
    private TextView detailCalories100, detailProtein100, detailCarbs100, detailFat100, detailServingLabel;
    private TextView detailTotalCalories, detailTotalProtein, detailTotalCarbs, detailTotalFat;
    private TextView detailTotalFiber, detailTotalSugar, detailTotalSodium, detailExtra, servingCountText;
    private TextView detailFavorite, detailNutritionStatus;
    private EditText detailGramsInput;
    private CatalogFood selectedFood;
    private double servingMultiplier = 1.0;
    private double baseServingGrams = 100.0;
    private String selectedServingLabel = "100 g";
    private List<ServingOption> servingOptions = new ArrayList<>();
    private boolean internalGramsChange;

    private EditText nameInput, gramsInput, caloriesInput, proteinInput, carbsInput, fatInput;
    private TextView detailAddButton, manualAddButton, manualContextText;
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
        profileController = new ProfileController(this, this::refreshTargetsAndProgress);
        profileController.bind();
        progressController = new ProgressController(this, diary, profileController);
        progressController.bind();
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
        foodLogTitle = findViewById(R.id.foodLogTitle);
        relogButton = findViewById(R.id.relogButton);
        entriesContainer = findViewById(R.id.entriesContainer);

        searchInput = findViewById(R.id.searchInput);
        databaseStatusText = findViewById(R.id.databaseStatusText);
        profileDatabaseStatus = findViewById(R.id.profileDatabaseStatus);
        searchSectionTitle = findViewById(R.id.searchSectionTitle);
        shortcutAll = findViewById(R.id.shortcutAll);
        shortcutRecent = findViewById(R.id.shortcutRecent);
        shortcutFrequent = findViewById(R.id.shortcutFrequent);
        shortcutFavorites = findViewById(R.id.shortcutFavorites);
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
        detailFavorite = findViewById(R.id.detailFavorite);
        detailNutritionStatus = findViewById(R.id.detailNutritionStatus);

        nameInput = findViewById(R.id.nameInput);
        gramsInput = findViewById(R.id.gramsInput);
        caloriesInput = findViewById(R.id.caloriesInput);
        proteinInput = findViewById(R.id.proteinInput);
        carbsInput = findViewById(R.id.carbsInput);
        fatInput = findViewById(R.id.fatInput);
        manualContextText = findViewById(R.id.manualContextText);
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
        navProgress.setOnClickListener(v -> {
            if (progressController != null) progressController.refresh();
            showScreen(progressScreen, 2);
        });
        navProfile.setOnClickListener(v -> showScreen(profileScreen, 3));
        findViewById(R.id.homeSeeAll).setOnClickListener(v -> {
            setActiveMeal(defaultMealType());
            if (catalog != null) loadPopularFoods();
            showScreen(searchScreen, 1);
        });
        relogButton.setOnClickListener(v -> showRelogDialog());
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
        shortcutAll.setOnClickListener(v -> {
            searchInput.setText("");
            loadPopularFoods();
        });
        shortcutRecent.setOnClickListener(v -> {
            searchInput.setText("");
            loadRecentFoods();
        });
        shortcutFrequent.setOnClickListener(v -> {
            searchInput.setText("");
            loadFrequentFoods();
        });
        shortcutFavorites.setOnClickListener(v -> {
            searchInput.setText("");
            loadFavoriteFoods();
        });
        findViewById(R.id.manualEntryButton).setOnClickListener(v -> showManualEntry(null, null));
        findViewById(R.id.barcodeTile).setOnClickListener(v -> startBarcodeScan());
        findViewById(R.id.voiceTile).setOnClickListener(v -> toast("Voice search will be added later."));
    }

    private void configureDetail() {
        findViewById(R.id.detailBack).setOnClickListener(v -> showScreen(searchScreen, 1));
        findViewById(R.id.servingMinus).setOnClickListener(v -> changeServingCount(-1));
        findViewById(R.id.servingPlus).setOnClickListener(v -> changeServingCount(1));
        servingCountText.setOnClickListener(v -> showServingMultiplierOptions());
        detailServingLabel.setOnClickListener(v -> showServingOptions());
        detailFavorite.setOnClickListener(v -> toggleSelectedFavorite());
        detailAddButton.setOnClickListener(v -> addSelectedFood());
        detailGramsInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!internalGramsChange) {
                    updateServingMultiplierFromGrams();
                    updateDetailTotals();
                }
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

    private void refreshTargetsAndProgress() {
        refreshHome();
        if (progressController != null) progressController.refresh();
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
        boolean viewingToday = selectedDate.equals(today);
        foodLogTitle.setText(viewingToday ? "Today’s Food Log" :
                "Food Log • " + selectedDate.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())));
        relogButton.setVisibility(viewingToday ? View.VISIBLE : View.GONE);
        renderDiaryEntries(diary.getEntriesForDate(selectedDate));
    }

    private void showRelogDialog() {
        LocalDate today = LocalDate.now();
        if (!selectedDate.equals(today)) {
            toast("Relog is available from Today.");
            return;
        }

        LocalDate yesterday = today.minusDays(1);
        LocalDate lastLogged = diary.getLastLoggedDateBefore(today);
        List<String> labels = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();

        labels.add("Yesterday  •  " + formatRelogDate(yesterday));
        dates.add(yesterday);

        if (lastLogged != null && !lastLogged.equals(yesterday)) {
            labels.add("Last logged day  •  " + formatRelogDate(lastLogged));
            dates.add(lastLogged);
        }

        labels.add("Choose another date…");
        dates.add(null);

        new AlertDialog.Builder(this)
                .setTitle("Relog from…")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    LocalDate sourceDate = dates.get(which);
                    if (sourceDate == null) showRelogDatePicker(lastLogged != null ? lastLogged : yesterday);
                    else confirmRelogDate(sourceDate);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRelogDatePicker(LocalDate initialDate) {
        LocalDate today = LocalDate.now();
        LocalDate safeInitial = initialDate == null || !initialDate.isBefore(today)
                ? today.minusDays(1) : initialDate;
        DatePickerDialog picker = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) ->
                        confirmRelogDate(LocalDate.of(year, month + 1, dayOfMonth)),
                safeInitial.getYear(),
                safeInitial.getMonthValue() - 1,
                safeInitial.getDayOfMonth());
        long maxMillis = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
        picker.getDatePicker().setMaxDate(maxMillis);
        picker.setTitle("Choose day to relog");
        picker.show();
    }

    private void confirmRelogDate(LocalDate sourceDate) {
        LocalDate today = LocalDate.now();
        if (sourceDate == null || !sourceDate.isBefore(today)) {
            toast("Choose a day before today.");
            return;
        }

        List<FoodEntry> sourceEntries = diary.getEntriesForDate(sourceDate);
        if (sourceEntries.isEmpty()) {
            toast("No foods logged on " + formatRelogDate(sourceDate));
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Relog " + formatRelogDate(sourceDate) + "?")
                .setMessage("Copy " + sourceEntries.size() +
                        (sourceEntries.size() == 1 ? " food" : " foods") +
                        " into today? Existing foods today will not be removed.")
                .setPositiveButton("Relog", (dialog, which) -> relogDateIntoToday(sourceDate))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void relogDateIntoToday(LocalDate sourceDate) {
        try {
            int copied = diary.copyDateToDate(sourceDate, LocalDate.now());
            if (copied <= 0) {
                toast("Nothing to relog from " + formatRelogDate(sourceDate));
                return;
            }
            selectedDate = LocalDate.now();
            renderWeekStrip();
            refreshHome();
            AppLogger.i("Relog copied entries=" + copied + " sourceDate=" + sourceDate);
            toast("Relogged " + copied + (copied == 1 ? " food" : " foods") +
                    " from " + formatRelogDate(sourceDate));
        } catch (Exception e) {
            AppLogger.e("Relog failed sourceDate=" + sourceDate, e);
            toast("Unable to relog that day");
        }
    }

    private String formatRelogDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()));
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
        String favoriteAction = diary.isFavorite(entry) ? "Remove from favorites" : "Add to favorites";
        String[] actions = {"Edit amount", "Move to another meal", favoriteAction, "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(entry.name + " • " + formatAmount(entry.grams) + " g")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showEditAmountDialog(entry);
                    else if (which == 1) showMoveMealDialog(entry);
                    else if (which == 2) toggleEntryFavorite(entry);
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
                            if (diary.isFavorite(entry)) diary.setFavorite(updated);
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
                        if (diary.isFavorite(entry)) diary.setFavorite(updated);
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

    private void toggleEntryFavorite(FoodEntry entry) {
        if (diary.isFavorite(entry)) {
            diary.removeFavorite(entry);
            toast("Removed from favorites");
        } else {
            diary.setFavorite(entry);
            toast("Added to favorites");
        }
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
        selectShortcutTab(null);
        List<CatalogFood> results = catalog.search(q, 30);
        AppLogger.i("Offline search queryLength=" + q.length() + " results=" + results.size());
        searchSectionTitle.setText("Search Results • " + mealTargetLabel());
        renderFoodResults(results);
    }

    private void startBarcodeScan() {
        AppLogger.i("Barcode scan requested meal=" + activeMealType + " selectedDate=" + selectedDate);
        android.content.Intent intent = new android.content.Intent(this, BarcodeScannerActivity.class);
        startActivityForResult(intent, BARCODE_SCAN_REQUEST);
    }

    private void handleBarcodeResult(String barcode, String format) {
        if (barcode == null || barcode.trim().isEmpty()) return;
        if (catalog == null) {
            toast("Offline database unavailable in this build");
            return;
        }
        CatalogFood food = catalog.findByBarcode(barcode, format);
        if (food != null) {
            AppLogger.i("Barcode matched source=" + food.source + " code=" + food.code);
            openDetail(food);
            return;
        }
        AppLogger.i("Barcode not found digits=" + barcode.length());
        showBarcodeNotFound(barcode);
    }

    private void showBarcodeNotFound(String barcode) {
        new AlertDialog.Builder(this)
                .setTitle("Barcode not found")
                .setMessage(barcode + " is not in DietLog's offline database.")
                .setPositiveButton("Manual food", (dialog, which) -> showManualEntryForBarcode(barcode))
                .setNegativeButton("Scan again", (dialog, which) -> startBarcodeScan())
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void showManualEntryForBarcode(String barcode) {
        showManualEntry(null, null);
        manualContextText.setText("Scanned barcode: " + barcode + " • Enter nutrition from the product label.");
        manualContextText.setVisibility(View.VISIBLE);
    }

    private void loadPopularFoods() {
        if (catalog == null) return;
        selectShortcutTab(shortcutAll);
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

    private void loadRecentFoods() {
        selectShortcutTab(shortcutRecent);
        searchSectionTitle.setText("Recent Foods • " + mealTargetLabel());
        renderHistoryResults(diary.getRecentFoods(20), "recent");
    }

    private void loadFrequentFoods() {
        selectShortcutTab(shortcutFrequent);
        searchSectionTitle.setText("Frequent Foods • " + mealTargetLabel());
        renderHistoryResults(diary.getFrequentFoods(20), "frequent");
    }

    private void loadFavoriteFoods() {
        selectShortcutTab(shortcutFavorites);
        searchSectionTitle.setText("Favorites • " + mealTargetLabel());
        renderHistoryResults(diary.getFavoriteFoods(30), "favorite");
    }

    private void selectShortcutTab(TextView selected) {
        TextView[] tabs = {shortcutAll, shortcutRecent, shortcutFrequent, shortcutFavorites};
        for (TextView tab : tabs) {
            boolean active = tab == selected;
            tab.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            tab.setTextColor(getColor(active ? R.color.black : R.color.text_secondary));
            tab.setTypeface(tab.getTypeface(), active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void renderHistoryResults(List<FoodHistory.Item> items, String mode) {
        searchResultsContainer.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            String message = "favorite".equals(mode) ? "No favorites yet. Tap ♡ on a food or favorite a diary entry."
                    : "frequent".equals(mode) ? "Log foods a few times and your most-used foods will appear here."
                    : "No recent foods yet.";
            empty.setText(message);
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(13f);
            empty.setPadding(dp(4), dp(14), dp(4), dp(14));
            searchResultsContainer.addView(empty);
            return;
        }

        for (FoodHistory.Item item : items) {
            FoodEntry entry = item.entry;
            View row = getLayoutInflater().inflate(R.layout.item_food_search, searchResultsContainer, false);
            ((TextView) row.findViewById(R.id.foodIcon)).setText(foodEmoji(entry.name));
            ((TextView) row.findViewById(R.id.foodName)).setText(entry.name);
            String subtitle;
            if ("favorite".equals(mode)) {
                subtitle = "Favorite • " + formatAmount(entry.grams) + " g remembered";
            } else if ("frequent".equals(mode)) {
                subtitle = item.count + (item.count == 1 ? " log" : " logs") +
                        " • last " + formatAmount(entry.grams) + " g";
            } else {
                subtitle = "Last " + formatAmount(entry.grams) + " g • " +
                        FoodEntry.mealDisplayName(entry.mealType);
            }
            ((TextView) row.findViewById(R.id.foodSubtitle)).setText(subtitle);
            ((TextView) row.findViewById(R.id.foodMacros)).setText(String.format(Locale.getDefault(),
                    "%.0f kcal   P %.0fg   C %.0fg   F %.0fg",
                    entry.calories, entry.protein, entry.carbs, entry.fat));
            TextView action = row.findViewById(R.id.foodAdd);
            action.setText("↻");
            action.setContentDescription("Quick log " + entry.name);
            action.setOnClickListener(v -> quickRelog(entry));
            row.setOnClickListener(v -> openHistoryFood(entry));
            searchResultsContainer.addView(row);
        }
    }

    private void quickRelog(FoodEntry remembered) {
        try {
            FoodEntry entry = remembered.copyForLog(selectedDateTimestamp(), activeMealType);
            long id = diary.insert(entry);
            syncFavoriteSnapshot(entry);
            AppLogger.i("Food quick re-logged id=" + id + " source=" + entry.source + " meal=" + activeMealType);
            refreshHome();
            toast(addConfirmationText());
            showScreen(homeScreen, 0);
        } catch (Exception e) {
            AppLogger.e("Quick re-log failed", e);
            toast("Unable to re-log food");
        }
    }

    private void openHistoryFood(FoodEntry remembered) {
        if (catalog != null && remembered.sourceFoodId != null) {
            CatalogFood food = catalog.findByCode(remembered.sourceFoodId);
            if (food != null) {
                openDetail(food);
                setRememberedHistoryAmount(remembered.grams);
                return;
            }
        }
        showManualEntryFromHistory(remembered);
    }

    private void showManualEntryFromHistory(FoodEntry entry) {
        clearManualInputs();
        nameInput.setText(entry.name);
        gramsInput.setText(formatAmount(entry.grams));
        double factor = entry.grams / 100.0;
        if (factor > 0) {
            caloriesInput.setText(formatAmount(entry.calories / factor));
            proteinInput.setText(formatAmount(entry.protein / factor));
            carbsInput.setText(formatAmount(entry.carbs / factor));
            fatInput.setText(formatAmount(entry.fat / factor));
        }
        updateMealTargetLabels();
        showScreen(manualScreen, -1);
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
        restoreServingState(food);
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
        detailNutritionStatus.setVisibility(food.hasCompleteCoreMacros() ? View.GONE : View.VISIBLE);
        updateServingControls();
        detailExtra.setText(detailExtra(food));
        updateDetailTotals();
        updateMealTargetLabels();
        updateFavoriteButton();
        AppLogger.i("Catalog food selected source=" + food.source + " code=" + food.code + " meal=" + activeMealType);
        showScreen(detailScreen, -1);
    }

    private String detailSubtitle(CatalogFood food) {
        if (food.brand != null && !food.brand.trim().isEmpty()) return food.brand;
        if (food.category != null && !food.category.trim().isEmpty()) return compact(food.category, 55);
        return friendlySource(food.source) + " food";
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
        double snapped = Math.round(servingMultiplier * 2.0) / 2.0;
        servingMultiplier = Math.max(0.5, snapped + delta * 0.5);
        updateServingControls();
        updateDetailTotals();
    }

    private void showServingMultiplierOptions() {
        if (selectedFood == null) return;
        String[] labels = {"½ serving", "1 serving", "1.5 servings", "2 servings", "2.5 servings", "3 servings"};
        double[] values = {0.5, 1.0, 1.5, 2.0, 2.5, 3.0};
        new AlertDialog.Builder(this)
                .setTitle("Number of servings")
                .setItems(labels, (dialog, which) -> {
                    servingMultiplier = values[which];
                    updateServingControls();
                    updateDetailTotals();
                })
                .show();
    }

    private void showServingOptions() {
        if (selectedFood == null || servingOptions.isEmpty()) return;
        String[] labels = new String[servingOptions.size()];
        for (int i = 0; i < servingOptions.size(); i++) {
            ServingOption option = servingOptions.get(i);
            labels[i] = option.label + "  •  " + formatAmount(option.grams) + " g";
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose serving size")
                .setItems(labels, (dialog, which) -> {
                    ServingOption option = servingOptions.get(which);
                    selectedServingLabel = option.label;
                    baseServingGrams = option.grams;
                    servingMultiplier = 1.0;
                    updateServingControls();
                    updateDetailTotals();
                })
                .show();
    }

    private void restoreServingState(CatalogFood food) {
        servingOptions = ServingOption.forFood(food);
        ServingOption initial = servingOptions.get(0);
        selectedServingLabel = initial.label;
        baseServingGrams = initial.grams;
        servingMultiplier = 1.0;

        SharedPreferences prefs = getSharedPreferences(SERVING_PREFS, MODE_PRIVATE);
        String key = servingMemoryKey(food);
        if (prefs.contains(key + ".base")) {
            double rememberedBase = prefs.getFloat(key + ".base", (float) baseServingGrams);
            double rememberedMultiplier = prefs.getFloat(key + ".multiplier", 1.0f);
            if (rememberedBase > 0 && rememberedMultiplier > 0) {
                baseServingGrams = rememberedBase;
                servingMultiplier = rememberedMultiplier;
                selectedServingLabel = prefs.getString(key + ".label", selectedServingLabel);
            }
        }
    }

    private void saveServingMemory() {
        if (selectedFood == null || baseServingGrams <= 0 || servingMultiplier <= 0) return;
        getSharedPreferences(SERVING_PREFS, MODE_PRIVATE).edit()
                .putFloat(servingMemoryKey(selectedFood) + ".base", (float) baseServingGrams)
                .putFloat(servingMemoryKey(selectedFood) + ".multiplier", (float) servingMultiplier)
                .putString(servingMemoryKey(selectedFood) + ".label", selectedServingLabel)
                .apply();
    }

    private String servingMemoryKey(CatalogFood food) {
        return FoodEntry.foodKey(food.source, food.code, food.name);
    }

    private void updateServingControls() {
        detailServingLabel.setText(selectedServingLabel + "  •  " +
                formatAmount(baseServingGrams) + " g  ›");
        servingCountText.setText(formatServingMultiplier(servingMultiplier));
        setDetailGrams(baseServingGrams * servingMultiplier);
    }

    private void updateServingMultiplierFromGrams() {
        if (baseServingGrams <= 0) return;
        String raw = detailGramsInput.getText().toString().trim();
        if (raw.isEmpty()) return;
        try {
            double grams = Double.parseDouble(raw);
            if (grams > 0) {
                servingMultiplier = grams / baseServingGrams;
                servingCountText.setText(formatServingMultiplier(servingMultiplier));
            }
        } catch (NumberFormatException ignored) {}
    }

    private String formatServingMultiplier(double value) {
        if (Math.abs(value - 0.5) < 0.001) return "½";
        if (Math.abs(value - Math.rint(value)) < 0.001) return String.format(Locale.US, "%.0f", value);
        if (Math.abs(value * 2.0 - Math.rint(value * 2.0)) < 0.001) return String.format(Locale.US, "%.1f", value);
        return String.format(Locale.US, "%.2f", value);
    }

    private void setRememberedHistoryAmount(double grams) {
        if (grams <= 0 || baseServingGrams <= 0) return;
        servingMultiplier = grams / baseServingGrams;
        servingCountText.setText(formatServingMultiplier(servingMultiplier));
        setDetailGrams(grams);
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
        double grams = parseOptional(detailGramsInput, baseServingGrams * servingMultiplier);
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
            FoodEntry entry = selectedFoodEntry(selectedDateTimestamp());
            saveServingMemory();
            long id = diary.insert(entry);
            syncFavoriteSnapshot(entry);
            AppLogger.i("Food entry added id=" + id + " source=" + selectedFood.source + " meal=" + activeMealType);
            refreshHome();
            toast(addConfirmationText());
            showScreen(homeScreen, 0);
        } catch (Exception e) {
            AppLogger.e("Failed to add selected food", e);
            toast("Unable to add food");
        }
    }

    private FoodEntry selectedFoodEntry(long loggedAt) {
        double grams = positive(detailGramsInput, "amount");
        return FoodEntry.fromPer100g(selectedFood.name, grams, selectedFood.calories,
                selectedFood.protein, selectedFood.carbs, selectedFood.fat,
                loggedAt, activeMealType, selectedFood.source, selectedFood.code);
    }

    private void toggleSelectedFavorite() {
        if (selectedFood == null) return;
        String key = FoodEntry.foodKey(selectedFood.source, selectedFood.code, selectedFood.name);
        if (diary.isFavoriteKey(key)) {
            diary.removeFavoriteKey(key);
            updateFavoriteButton();
            toast("Removed from favorites");
            return;
        }
        if (!selectedFood.hasCompleteCoreMacros()) {
            toast("Complete the missing nutrition before favoriting this food.");
            return;
        }
        try {
            FoodEntry snapshot = selectedFoodEntry(System.currentTimeMillis());
            diary.setFavorite(snapshot);
            saveServingMemory();
            updateFavoriteButton();
            toast("Added to favorites");
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
        }
    }

    private void updateFavoriteButton() {
        if (selectedFood == null) return;
        String key = FoodEntry.foodKey(selectedFood.source, selectedFood.code, selectedFood.name);
        boolean favorite = diary.isFavoriteKey(key);
        detailFavorite.setText(favorite ? "♥" : "♡");
        detailFavorite.setTextColor(getColor(favorite ? R.color.accent : R.color.text_primary));
    }

    private void syncFavoriteSnapshot(FoodEntry entry) {
        if (diary.isFavorite(entry)) diary.setFavorite(entry);
    }

    private void showManualEntry(String prefillName, CatalogFood sourceFood) {
        clearManualInputs();
        manualContextText.setVisibility(View.GONE);
        manualContextText.setText("");
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
            syncFavoriteSnapshot(entry);
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
        if (requestCode == BARCODE_SCAN_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                handleBarcodeResult(
                        data.getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE),
                        data.getStringExtra(BarcodeScannerActivity.EXTRA_FORMAT));
            }
            return;
        }
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
