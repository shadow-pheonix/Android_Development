package com.shadowphoenix.dietlog;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
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
import com.shadowphoenix.dietlog.ui.RingProgressView;

import java.text.DateFormat;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
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

    private View homeScreen, searchScreen, detailScreen, manualScreen, progressScreen, profileScreen, bottomNav;
    private View navHome, navSearch, navAdd, navProgress, navProfile;
    private TextView navHomeIcon, navHomeLabel, navSearchIcon, navSearchLabel;
    private TextView navProgressIcon, navProgressLabel, navProfileIcon, navProfileLabel;

    private RingProgressView caloriesRing, proteinRing, carbsRing, fatRing;
    private TextView calorieValue, calorieGoalText, calorieLeft, proteinValue, carbsValue, fatValue;
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
        bindViews();
        configureRings();
        configureNavigation();
        configureSearch();
        configureDetail();
        configureManualEntry();
        configureDiagnostics();
        configureWeekStrip();

        ((TextView) findViewById(R.id.dateText)).setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));

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
        entriesContainer = findViewById(R.id.entriesContainer);

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

        nameInput = findViewById(R.id.nameInput);
        gramsInput = findViewById(R.id.gramsInput);
        caloriesInput = findViewById(R.id.caloriesInput);
        proteinInput = findViewById(R.id.proteinInput);
        carbsInput = findViewById(R.id.carbsInput);
        fatInput = findViewById(R.id.fatInput);
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
            if (catalog != null && searchResultsContainer.getChildCount() == 0) loadPopularFoods();
            showScreen(searchScreen, 1);
        });
        navAdd.setOnClickListener(v -> showManualEntry(null, null));
        navProgress.setOnClickListener(v -> showScreen(progressScreen, 2));
        navProfile.setOnClickListener(v -> showScreen(profileScreen, 3));
        findViewById(R.id.homeSeeAll).setOnClickListener(v -> showScreen(searchScreen, 1));
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
        findViewById(R.id.detailAddButton).setOnClickListener(v -> addSelectedFood());
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
        findViewById(R.id.addButton).setOnClickListener(v -> addManualFood());
    }

    private void configureDiagnostics() {
        findViewById(R.id.exportButton).setOnClickListener(v -> exportDiagnostics());
    }

    private void configureWeekStrip() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        for (int i = 0; i < 7; i++) {
            int id = getResources().getIdentifier("day" + i, "id", getPackageName());
            TextView day = findViewById(id);
            LocalDate date = monday.plusDays(i);
            String dow = date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
            day.setText(dow + System.lineSeparator() + date.getDayOfMonth());
            if (date.equals(today)) {
                day.setBackgroundResource(R.drawable.bg_calendar_selected);
                day.setTextColor(getColor(R.color.accent));
                day.setTypeface(day.getTypeface(), android.graphics.Typeface.BOLD);
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
        double[] totals = diary.getTodayTotals();
        calorieValue.setText(String.format(Locale.getDefault(), "%.0f", totals[0]));
        calorieGoalText.setText(String.format(Locale.getDefault(), "of %,.0f kcal", CALORIE_GOAL));
        calorieLeft.setText(String.format(Locale.getDefault(), "%.0f", Math.max(0, CALORIE_GOAL - totals[0])));
        proteinValue.setText(String.format(Locale.getDefault(), "%.0f / %.0fg", totals[1], PROTEIN_GOAL));
        carbsValue.setText(String.format(Locale.getDefault(), "%.0f / %.0fg", totals[2], CARBS_GOAL));
        fatValue.setText(String.format(Locale.getDefault(), "%.0f / %.0fg", totals[3], FAT_GOAL));
        caloriesRing.setProgress((float) (totals[0] / CALORIE_GOAL));
        proteinRing.setProgress((float) (totals[1] / PROTEIN_GOAL));
        carbsRing.setProgress((float) (totals[2] / CARBS_GOAL));
        fatRing.setProgress((float) (totals[3] / FAT_GOAL));
        renderDiaryEntries(diary.getTodayEntries());
    }

    private void renderDiaryEntries(List<FoodEntry> entries) {
        entriesContainer.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(getString(R.string.empty_entries));
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setTextSize(13f);
            empty.setPadding(dp(4), dp(14), dp(4), dp(10));
            entriesContainer.addView(empty);
            return;
        }
        int count = Math.min(entries.size(), 5);
        for (int i = 0; i < count; i++) {
            FoodEntry entry = entries.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_diary_entry, entriesContainer, false);
            ((TextView) row.findViewById(R.id.entryIcon)).setText(foodEmoji(entry.name));
            ((TextView) row.findViewById(R.id.entryName)).setText(entry.name);
            ((TextView) row.findViewById(R.id.entryAmount)).setText(String.format(Locale.getDefault(), "%.0f g  •  %s", entry.grams, friendlySource(entry.source)));
            ((TextView) row.findViewById(R.id.entryMacros)).setText(String.format(Locale.getDefault(), "P %.0fg    C %.0fg    F %.0fg", entry.protein, entry.carbs, entry.fat));
            ((TextView) row.findViewById(R.id.entryCalories)).setText(String.format(Locale.getDefault(), "%.0f kcal", entry.calories));
            entriesContainer.addView(row);
        }
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
        searchSectionTitle.setText("Search Results");
        renderFoodResults(results);
    }

    private void loadPopularFoods() {
        if (catalog == null) return;
        String[] queries = {"idli", "upma", "biryani", "paneer", "bread", "dosa", "milk"};
        List<CatalogFood> foods = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String q : queries) {
            for (CatalogFood food : catalog.search(q, 4)) {
                if (food.code != null && seen.add(food.code)) {
                    foods.add(food);
                    break;
                }
            }
        }
        searchSectionTitle.setText("Popular Foods");
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
        return kcal + " kcal/100g   P " + numberOrDash(food.protein) + "   C " + numberOrDash(food.carbs) + "   F " + numberOrDash(food.fat);
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
        AppLogger.i("Catalog food selected source=" + food.source + " code=" + food.code);
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
        if (selectedFood.calories == null || selectedFood.protein == null || selectedFood.carbs == null || selectedFood.fat == null) {
            toast("This record is missing a core macro. Complete it in Manual Food.");
            showManualEntry(selectedFood.name, selectedFood);
            return;
        }
        try {
            double grams = positive(detailGramsInput, "amount");
            FoodEntry entry = FoodEntry.fromPer100g(selectedFood.name, grams, selectedFood.calories,
                    selectedFood.protein, selectedFood.carbs, selectedFood.fat,
                    System.currentTimeMillis(), selectedFood.source, selectedFood.code);
            long id = diary.insert(entry);
            AppLogger.i("Food entry added id=" + id + " source=" + selectedFood.source);
            refreshHome();
            toast("Added to today’s diary");
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
            FoodEntry entry = FoodEntry.fromPer100g(name, grams, calories, protein, carbs, fat, System.currentTimeMillis());
            long id = diary.insert(entry);
            AppLogger.i("Food entry added id=" + id + " source=manual");
            clearManualInputs();
            refreshHome();
            toast("Added to today’s diary");
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
