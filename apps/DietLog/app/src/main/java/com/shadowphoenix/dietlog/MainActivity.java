package com.shadowphoenix.dietlog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.data.FoodEntry;
import com.shadowphoenix.dietlog.data.FoodHistory;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.diagnostics.DiagnosticsExporter;
import com.shadowphoenix.dietlog.food.CatalogFood;
import com.shadowphoenix.dietlog.food.FoodPhotoLoader;
import com.shadowphoenix.dietlog.food.FoodCatalogDb;
import com.shadowphoenix.dietlog.food.ServingOption;
import com.shadowphoenix.dietlog.nutrition.DailyMicronutrientSummary;
import com.shadowphoenix.dietlog.nutrition.Micronutrient;
import com.shadowphoenix.dietlog.nutrition.MicronutrientTarget;
import com.shadowphoenix.dietlog.nutrition.MicronutrientTargets;
import com.shadowphoenix.dietlog.nutrition.MicronutrientValues;
import com.shadowphoenix.dietlog.profile.AccountIdentity;
import com.shadowphoenix.dietlog.profile.ProfileController;
import com.shadowphoenix.dietlog.profile.ProfileSettings;
import com.shadowphoenix.dietlog.progress.ProgressController;
import com.shadowphoenix.dietlog.sync.DriveBackupScheduler;
import com.shadowphoenix.dietlog.sync.DriveRestoreManager;
import com.shadowphoenix.dietlog.sync.DriveSyncStatusStore;
import com.shadowphoenix.dietlog.sync.SyncCoordinator;
import com.shadowphoenix.dietlog.sync.SupabaseBackupScheduler;
import com.shadowphoenix.dietlog.sync.SupabaseRestoreManager;
import com.shadowphoenix.dietlog.sync.SupabaseSyncStatusStore;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseAuthClient;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseConfig;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseConnectionStore;
import com.shadowphoenix.dietlog.sync.drive.DriveConnectionStore;
import com.shadowphoenix.dietlog.sync.drive.GoogleDriveAuthorization;
import com.shadowphoenix.dietlog.ui.RingProgressView;
import com.shadowphoenix.dietlog.ui.MealIllustrationView;
import com.shadowphoenix.dietlog.ui.FrostedUi;
import com.shadowphoenix.dietlog.ui.UiMotion;
import com.shadowphoenix.dietlog.ui.FrostedSurfaceDrawable.Material;
import com.shadowphoenix.dietlog.ui.FrostedNavigationView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final double CALORIE_GOAL = 2000.0;
    private static final double PROTEIN_GOAL = 150.0;
    private static final double CARBS_GOAL = 250.0;
    private static final double FAT_GOAL = 70.0;
    private static final String SERVING_PREFS = "serving_memory";
    private static final int BARCODE_SCAN_REQUEST = 2001;

    private DiaryDbHelper diary;
    private FoodCatalogDb catalog;
    private FoodPhotoLoader photoLoader;
    private ImageView detailImage;
    private TextView detailPhotoCredit;
    private ProfileController profileController;
    private ProgressController progressController;
    private final ExecutorService driveExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService supabaseExecutor = Executors.newSingleThreadExecutor();
    private DriveSyncStatusStore driveSyncStatusStore;
    private SharedPreferences.OnSharedPreferenceChangeListener driveSyncStatusListener;
    private long renderedDriveDataChangeGeneration;

    private View homeScreen, searchScreen, detailScreen, manualScreen, nutritionScreen, progressScreen, profileScreen, editProfileScreen, accountScreen, bottomNav;
    private View navHome, navSearch, navAdd, navProgress, navProfile;
    private UiMotion uiMotion;
    private int selectedNavIndex = -1;
    private ImageView navHomeIcon, navSearchIcon, navProgressIcon, navProfileIcon;
    private TextView navHomeLabel, navSearchLabel;
    private TextView navProgressLabel, navProfileLabel;
    private TextView driveConnectionStatus, driveSyncStatus, driveConnectButton;
    private TextView driveSyncNowButton, driveRestoreButton, driveDisconnectButton;
    private View driveConnectedActions;
    private TextView supabaseConnectionStatus, supabaseSyncStatus, supabaseConnectButton;
    private TextView supabaseSyncNowButton, supabaseRestoreButton, supabaseDisconnectButton;
    private View supabaseConnectedActions;
    private TextView profileAccountStatus, profileAccountAction;

    private RingProgressView caloriesRing, proteinRing, carbsRing, fatRing;
    private TextView calorieValue, calorieGoalText, calorieLeft, proteinValue, carbsValue, fatValue;
    private TextView dateText, homeTodayButton, foodLogTitle, relogButton;
    private LinearLayout entriesContainer;
    private TextView nutritionDateText, nutritionMacroSummary, nutritionTargetNote;
    private LinearLayout nutritionMineralsContainer, nutritionVitaminsContainer;

    private EditText searchInput;
    private TextView databaseStatusText, profileDatabaseStatus, searchSectionTitle, searchMealTargetText;
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
    private CatalogFood manualSourceFood;
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
        configureSystemBarInsets();

        photoLoader = new FoodPhotoLoader(this);
        diary = new DiaryDbHelper(this);
        if (savedInstanceState != null) {
            String savedDate = savedInstanceState.getString("selected_date");
            if (savedDate != null) {
                try { selectedDate = LocalDate.parse(savedDate); } catch (Exception ignored) {}
            }
            activeMealType = FoodEntry.normalizeMealType(savedInstanceState.getString("active_meal", activeMealType));
        }
        bindViews();
        ((FrostedNavigationView) bottomNav).setBackdropSource(findViewById(R.id.screenContainer));
        uiMotion = new UiMotion(findViewById(R.id.appRoot));
        profileController = new ProfileController(this, this::refreshTargetsAndProgress);
        profileController.bind();
        progressController = new ProgressController(this, diary, profileController);
        progressController.bind();
        configureRings();
        configureNavigation();
        configureSearch();
        configureDetail();
        configureManualEntry();
        configureDriveBackup();
        configureSupabaseBackup();
        configureDiagnostics();
        configureDateNavigation();

        try {
            catalog = new FoodCatalogDb(this);
            databaseStatusText.setText(String.format(Locale.getDefault(), "%,d foods available offline", catalog.count()));
            profileDatabaseStatus.setText(String.format(Locale.getDefault(), "%,d foods • ready offline", catalog.count()));
            loadPopularFoods();
        } catch (Exception e) {
            AppLogger.e("Offline catalog failed to open", e);
            databaseStatusText.setText("Offline database unavailable in this build");
            profileDatabaseStatus.setText("Offline database unavailable");
        }

        DriveBackupScheduler.schedulePeriodic(this);
        SupabaseBackupScheduler.schedulePeriodic(this);
        refreshDriveBackupUi();
        refreshSupabaseBackupUi();
        refreshAccountUi();
        refreshHome();
        FrostedUi.applyTree(findViewById(R.id.appRoot));
        showScreen(homeScreen, 0);
    }

    private void configureSystemBarInsets() {
        View root = findViewById(R.id.appRoot);
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void bindViews() {
        homeScreen = findViewById(R.id.homeScreen);
        searchScreen = findViewById(R.id.searchScreen);
        detailScreen = findViewById(R.id.detailScreen);
        manualScreen = findViewById(R.id.manualScreen);
        nutritionScreen = findViewById(R.id.nutritionScreen);
        progressScreen = findViewById(R.id.progressScreen);
        profileScreen = findViewById(R.id.profileScreen);
        editProfileScreen = findViewById(R.id.editProfileScreen);
        accountScreen = findViewById(R.id.accountScreen);
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
        nutritionDateText = findViewById(R.id.nutritionDate);
        nutritionMacroSummary = findViewById(R.id.nutritionMacroSummary);
        nutritionTargetNote = findViewById(R.id.nutritionTargetNote);
        nutritionMineralsContainer = findViewById(R.id.nutritionMineralsContainer);
        nutritionVitaminsContainer = findViewById(R.id.nutritionVitaminsContainer);

        searchInput = findViewById(R.id.searchInput);
        databaseStatusText = findViewById(R.id.databaseStatusText);
        profileDatabaseStatus = findViewById(R.id.profileDatabaseStatus);
        driveConnectionStatus = findViewById(R.id.driveConnectionStatus);
        driveSyncStatus = findViewById(R.id.driveSyncStatus);
        driveConnectButton = findViewById(R.id.driveConnectButton);
        driveConnectedActions = findViewById(R.id.driveConnectedActions);
        driveSyncNowButton = findViewById(R.id.driveSyncNowButton);
        driveRestoreButton = findViewById(R.id.driveRestoreButton);
        driveDisconnectButton = findViewById(R.id.driveDisconnectButton);
        supabaseConnectionStatus = findViewById(R.id.supabaseConnectionStatus);
        supabaseSyncStatus = findViewById(R.id.supabaseSyncStatus);
        supabaseConnectButton = findViewById(R.id.supabaseConnectButton);
        supabaseConnectedActions = findViewById(R.id.supabaseConnectedActions);
        supabaseSyncNowButton = findViewById(R.id.supabaseSyncNowButton);
        supabaseRestoreButton = findViewById(R.id.supabaseRestoreButton);
        supabaseDisconnectButton = findViewById(R.id.supabaseDisconnectButton);
        profileAccountStatus = findViewById(R.id.profileAccountStatus);
        profileAccountAction = findViewById(R.id.profileAccountAction);
        searchSectionTitle = findViewById(R.id.searchSectionTitle);
        searchMealTargetText = findViewById(R.id.searchMealTarget);
        shortcutAll = findViewById(R.id.shortcutAll);
        shortcutRecent = findViewById(R.id.shortcutRecent);
        shortcutFrequent = findViewById(R.id.shortcutFrequent);
        shortcutFavorites = findViewById(R.id.shortcutFavorites);
        searchResultsContainer = findViewById(R.id.searchResultsContainer);

        detailEmoji = findViewById(R.id.detailEmoji);
        detailImage = findViewById(R.id.detailImage);
        detailPhotoCredit = findViewById(R.id.detailPhotoCredit);
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
        navProfile.setOnClickListener(v -> showProfileScreen());
        findViewById(R.id.profileEditProfileButton).setOnClickListener(v -> openEditProfileScreen());
        findViewById(R.id.editProfileBack).setOnClickListener(v -> closeEditProfileScreen());
        findViewById(R.id.profileSavePersonalButton).setOnClickListener(v -> {
            if (profileController.savePersonalProfile()) closeEditProfileScreen();
        });
        profileAccountAction.setOnClickListener(v -> openAccountScreen());
        findViewById(R.id.accountBack).setOnClickListener(v -> showProfileScreen());
        findViewById(R.id.homeSeeAll).setOnClickListener(v -> {
            setActiveMeal(defaultMealType());
            if (catalog != null) loadPopularFoods();
            showScreen(searchScreen, 1);
        });
        relogButton.setOnClickListener(v -> showRelogDialog());
        findViewById(R.id.homeNutritionCard).setOnClickListener(v -> openNutritionDetails());
        findViewById(R.id.nutritionBack).setOnClickListener(v -> showScreen(homeScreen, 0));
    }

    private void showProfileScreen() {
        refreshDriveBackupUi();
        refreshSupabaseBackupUi();
        refreshAccountUi();
        showScreen(profileScreen, 3);
    }

    private void openAccountScreen() {
        refreshDriveBackupUi();
        refreshSupabaseBackupUi();
        refreshAccountUi();
        showScreen(accountScreen, -1);
    }

    private void openEditProfileScreen() {
        profileController.beginPersonalProfileEdit();
        showScreen(editProfileScreen, -1);
        ((android.widget.ScrollView) editProfileScreen).scrollTo(0, 0);
    }

    private void closeEditProfileScreen() {
        profileController.reloadFromPreferences();
        View focused = getCurrentFocus();
        if (focused != null) {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        showProfileScreen();
    }

    private void refreshAccountUi() {
        if (profileAccountStatus == null || profileAccountAction == null) return;

        DriveConnectionStore.State drive = new DriveConnectionStore(this).getState();
        SupabaseConnectionStore.State supabase = new SupabaseConnectionStore(this).getState();
        boolean connected = drive.connected || supabase.connected;

        String nickname = "";
        if (profileController != null && profileController.getSettings() != null
                && profileController.getSettings().displayName != null) {
            nickname = profileController.getSettings().displayName.trim();
        }
        String googleAccountName = drive.connected && drive.displayName != null
                && !drive.displayName.trim().isEmpty()
                ? drive.displayName.trim()
                : (drive.connected && drive.accountName != null ? drive.accountName.trim() : "");
        String supabaseEmail = supabase.connected && supabase.email != null
                ? supabase.email.trim() : "";
        String accountFallback = AccountIdentity.preferredName("", googleAccountName, supabaseEmail);

        if (profileController != null) {
            profileController.setFallbackDisplayName(accountFallback);
        }

        if (!connected) {
            profileAccountStatus.setText("Not signed in • local data stays on this device");
            profileAccountAction.setText("Log in / sign up");
            styleCloudConnectButton(profileAccountAction, true);
            return;
        }

        String identity = AccountIdentity.preferredName(nickname, googleAccountName, supabaseEmail);
        String providers = AccountIdentity.providerLabel(drive.connected, supabase.connected);

        if (identity.isEmpty()) {
            profileAccountStatus.setText(providers + " connected");
        } else {
            profileAccountStatus.setText("Signed in as " + identity + " • " + providers);
        }
        profileAccountAction.setText("Manage account");
        styleCloudConnectButton(profileAccountAction, false);
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

    private void configureDriveBackup() {
        driveSyncStatusStore = new DriveSyncStatusStore(this);
        renderedDriveDataChangeGeneration = driveSyncStatusStore.getState().dataChangeGeneration;
        driveSyncStatusListener = (sharedPreferences, key) -> {
            if (!DriveSyncStatusStore.isDataChangeKey(key)) return;
            runOnUiThread(this::refreshAfterDriveDataChangeIfNeeded);
        };
        driveConnectButton.setOnClickListener(v -> connectGoogleDrive());
        driveSyncNowButton.setOnClickListener(v -> syncDriveNow());
        driveRestoreButton.setOnClickListener(v -> confirmDriveRestore());
        driveDisconnectButton.setOnClickListener(v -> confirmDriveDisconnect());
    }

    private void refreshAfterDriveDataChangeIfNeeded() {
        if (driveSyncStatusStore == null) return;
        long generation = driveSyncStatusStore.getState().dataChangeGeneration;
        if (generation <= renderedDriveDataChangeGeneration) return;
        renderedDriveDataChangeGeneration = generation;
        if (profileController != null) profileController.reloadFromPreferences();
        if (progressController != null) progressController.refresh();
        refreshHome();
        if (nutritionScreen != null && nutritionScreen.getVisibility() == View.VISIBLE) {
            refreshNutritionDetails();
        }
        refreshDriveBackupUi();
        AppLogger.i("UI refreshed after Drive merge generation=" + generation);
    }

    private void connectGoogleDrive() {
        new DriveSyncStatusStore(this).clearError();
        setDriveControlsEnabled(false);
        driveSyncStatus.setText("Opening Google authorization…");
        GoogleDriveAuthorization.beginConnect(this, driveAuthorizationCallback());
    }

    private GoogleDriveAuthorization.Callback driveAuthorizationCallback() {
        return new GoogleDriveAuthorization.Callback() {
            @Override
            public void onAuthorized(String accessToken, String accountName) {
                new DriveSyncStatusStore(MainActivity.this).clearError();
                SyncCoordinator.forceDirty(MainActivity.this, "drive-connect");
                DriveBackupScheduler.schedulePeriodic(MainActivity.this);
                DriveBackupScheduler.scheduleImmediate(MainActivity.this);
                runOnUiThread(() -> {
                    setDriveControlsEnabled(true);
                    refreshDriveBackupUi();
                    toast("Google Drive backup connected");
                });
            }

            @Override
            public void onFailure(GoogleDriveAuthorization.Failure failure, String message, Throwable error) {
                String safeMessage = message == null ? "Google Drive authorization failed" : message;
                new DriveSyncStatusStore(MainActivity.this).markError(
                        safeMessage, failure == GoogleDriveAuthorization.Failure.REAUTH_REQUIRED);
                if (failure == GoogleDriveAuthorization.Failure.CANCELLED && error == null) {
                    AppLogger.i("Drive authorization UI cancelled: " + safeMessage);
                } else {
                    AppLogger.e("Drive authorization UI failed: " + failure
                            + " message=" + safeMessage, error);
                }
                runOnUiThread(() -> {
                    setDriveControlsEnabled(true);
                    refreshDriveBackupUi();
                    Toast.makeText(MainActivity.this, safeMessage, Toast.LENGTH_LONG).show();
                });
            }
        };
    }

    private void syncDriveNow() {
        if (!new DriveConnectionStore(this).getState().connected) {
            connectGoogleDrive();
            return;
        }
        SyncCoordinator.forceDirty(this, "manual-sync");
        DriveBackupScheduler.scheduleImmediate(this);
        driveSyncStatus.setText("Sync queued • will run when network is available");
        toast("Drive sync queued");
    }

    private void confirmDriveRestore() {
        new AlertDialog.Builder(this)
                .setTitle("Restore from Google Drive?")
                .setMessage("FuelNote will merge the newest valid backup from each device. Newer records win and local-only records are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore / merge", (dialog, which) -> restoreFromDrive())
                .show();
    }

    private void restoreFromDrive() {
        setDriveControlsEnabled(false);
        driveSyncStatus.setText("Authorizing restore…");
        GoogleDriveAuthorization.requestSilentToken(this, new GoogleDriveAuthorization.Callback() {
            @Override
            public void onAuthorized(String accessToken, String accountName) {
                driveExecutor.execute(() -> {
                    try {
                        String localDeviceId = SyncCoordinator.getState(MainActivity.this).deviceId;
                        DriveRestoreManager.MergeResult result = new DriveRestoreManager()
                                .mergeDriveSnapshots(MainActivity.this, accessToken, localDeviceId, true);
                        if (result.changed) DriveBackupScheduler.scheduleImmediate(MainActivity.this);
                        runOnUiThread(() -> {
                            if (profileController != null) profileController.reloadFromPreferences();
                            if (progressController != null) progressController.refresh();
                            refreshHome();
                            setDriveControlsEnabled(true);
                            refreshDriveBackupUi();
                            toast(result.changed
                                    ? "Drive backup merged into FuelNote"
                                    : "Drive backup is already up to date");
                        });
                    } catch (Exception e) {
                        AppLogger.e("Manual Drive restore failed", e);
                        runOnUiThread(() -> {
                            setDriveControlsEnabled(true);
                            refreshDriveBackupUi();
                            Toast.makeText(MainActivity.this,
                                    "Drive restore failed: " + safeMessage(e), Toast.LENGTH_LONG).show();
                        });
                    }
                });
            }

            @Override
            public void onFailure(GoogleDriveAuthorization.Failure failure, String message, Throwable error) {
                if (error != null) AppLogger.e("Drive restore authorization failed: " + failure, error);
                runOnUiThread(() -> {
                    setDriveControlsEnabled(true);
                    refreshDriveBackupUi();
                    toast(message == null ? "Google Drive authorization failed" : message);
                });
            }
        });
    }

    private void confirmDriveDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect Google Drive?")
                .setMessage("Automatic backup will stop. Existing backup files remain in Drive app data so they can be restored after reconnecting.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Disconnect", (dialog, which) -> disconnectDrive())
                .show();
    }

    private void disconnectDrive() {
        setDriveControlsEnabled(false);
        GoogleDriveAuthorization.revokeAccess(this, new GoogleDriveAuthorization.DisconnectCallback() {
            @Override
            public void onDisconnected() {
                finishLocalDriveDisconnect();
            }

            @Override
            public void onFailure(String message, Throwable error) {
                if (error != null) AppLogger.e("Drive revoke failed", error);
                // Always allow the user to stop local automatic sync even if Google cannot be reached.
                finishLocalDriveDisconnect();
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Drive disconnected locally. Google permission could not be fully revoked: "
                                + (message == null ? "unknown error" : message),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void finishLocalDriveDisconnect() {
        DriveBackupScheduler.cancel(this);
        new DriveConnectionStore(this).clear();
        if (driveSyncStatusStore != null) {
            driveSyncStatusStore.clear();
        } else {
            new DriveSyncStatusStore(this).clear();
        }
        runOnUiThread(() -> {
            renderedDriveDataChangeGeneration = 0L;
            setDriveControlsEnabled(true);
            refreshDriveBackupUi();
            toast("Google Drive backup disconnected");
        });
    }

    private void refreshDriveBackupUi() {
        if (driveConnectionStatus == null) return;
        DriveConnectionStore.State connection = new DriveConnectionStore(this).getState();
        DriveSyncStatusStore.State status = new DriveSyncStatusStore(this).getState();
        SyncCoordinator.State sync = SyncCoordinator.getState(this);

        driveConnectedActions.setVisibility(connection.connected ? View.VISIBLE : View.GONE);
        driveConnectButton.setText(connection.connected
                ? (status.reauthRequired ? "Reconnect Google Drive" : "Change account")
                : "Connect Google Drive");
        styleCloudConnectButton(driveConnectButton, !connection.connected || status.reauthRequired);

        if (!connection.connected) {
            driveConnectionStatus.setText("Not connected");
            if (!status.lastError.isEmpty()) {
                driveSyncStatus.setText("Last connection issue: " + status.lastError);
            } else {
                driveSyncStatus.setText("FuelNote stays local-first. Connect Drive to back up and merge your user data automatically.");
            }
            return;
        }

        String account = connection.accountName.isEmpty()
                ? "Google account connected"
                : "Connected • " + connection.accountName;
        driveConnectionStatus.setText(status.reauthRequired ? "Reconnect required" : account);

        if (status.reauthRequired) {
            driveSyncStatus.setText("Google authorization expired. Reconnect to resume automatic backup.");
        } else if (!status.lastError.isEmpty()) {
            driveSyncStatus.setText("Last sync issue: " + status.lastError);
        } else if (status.lastSuccessAt > 0L) {
            String text = "Last synced " + formatCloudTime(status.lastSuccessAt)
                    + " • revision " + status.lastSuccessRevision;
            if (sync.dirty) text += " • newer changes waiting";
            driveSyncStatus.setText(text);
        } else if (sync.dirty) {
            driveSyncStatus.setText("Connected • first backup is waiting to sync");
        } else {
            driveSyncStatus.setText("Connected • automatic backup is enabled");
        }
    }

    private void setDriveControlsEnabled(boolean enabled) {
        if (driveConnectButton != null) driveConnectButton.setEnabled(enabled);
        if (driveSyncNowButton != null) driveSyncNowButton.setEnabled(enabled);
        if (driveRestoreButton != null) driveRestoreButton.setEnabled(enabled);
        if (driveDisconnectButton != null) driveDisconnectButton.setEnabled(enabled);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) return "unknown error";
        String flattened = message.replace('\n', ' ').replace('\r', ' ').trim();
        return flattened.length() <= 160 ? flattened : flattened.substring(0, 160);
    }

    private void configureSupabaseBackup() {
        supabaseConnectButton.setOnClickListener(v -> showSupabaseAuthDialog());
        supabaseSyncNowButton.setOnClickListener(v -> syncSupabaseNow());
        supabaseRestoreButton.setOnClickListener(v -> confirmSupabaseRestore());
        supabaseDisconnectButton.setOnClickListener(v -> confirmSupabaseDisconnect());
    }

    private void showSupabaseAuthDialog() {
        if (!SupabaseConfig.isConfigured()) {
            Toast.makeText(this,
                    "Supabase is not configured in this APK yet. Local data and Google Drive are unaffected.",
                    Toast.LENGTH_LONG).show();
            refreshSupabaseBackupUi();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), 0);

        EditText email = new EditText(this);
        email.setHint("Email");
        email.setSingleLine(true);
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        SupabaseConnectionStore.State previous = new SupabaseConnectionStore(this).getState();
        if (!previous.email.isEmpty()) email.setText(previous.email);
        content.addView(email, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Supabase cloud sync")
                .setMessage("Enter your email and Supabase will send a one-time sign-in code. No password is created or stored by FuelNote.")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send code", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String value = email.getText().toString().trim();
                    if (value.isEmpty() || !value.contains("@")) {
                        Toast.makeText(this, "Enter a valid email address", Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    requestSupabaseEmailCode(value);
                }));
        dialog.show();
    }

    private void requestSupabaseEmailCode(String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.US);
        if (normalizedEmail.isEmpty() || !normalizedEmail.contains("@")) {
            Toast.makeText(this, "Enter a valid email address", Toast.LENGTH_LONG).show();
            return;
        }

        setSupabaseControlsEnabled(false);
        supabaseSyncStatus.setText("Sending Supabase sign-in code…");
        supabaseExecutor.execute(() -> {
            try {
                new SupabaseAuthClient().sendEmailOtp(normalizedEmail);
                new SupabaseSyncStatusStore(MainActivity.this).clearError();
                runOnUiThread(() -> {
                    setSupabaseControlsEnabled(true);
                    supabaseSyncStatus.setText("Code requested for " + normalizedEmail + " • enter it to finish sign-in");
                    showSupabaseOtpDialog(normalizedEmail);
                });
            } catch (Exception e) {
                String message = safeMessage(e);
                new SupabaseSyncStatusStore(MainActivity.this).markError(message, false);
                AppLogger.e("Supabase email code request failed", e);
                runOnUiThread(() -> {
                    setSupabaseControlsEnabled(true);
                    refreshSupabaseBackupUi();
                    Toast.makeText(MainActivity.this,
                            "Could not send Supabase sign-in code: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showSupabaseOtpDialog(String email) {
        EditText code = new EditText(this);
        code.setHint("6-digit code");
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(8), dp(22), 0);
        content.addView(code, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Enter Supabase code")
                .setMessage("Enter the 6-digit code sent to " + email
                        + ". If the email contains only a sign-in link, Supabase email delivery is not configured for numeric OTP codes yet.")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Resend code", null)
                .setPositiveButton("Verify", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String token = code.getText().toString().trim();
                if (!token.matches("\\d{6}")) {
                    Toast.makeText(this, "Enter the 6-digit Supabase code", Toast.LENGTH_LONG).show();
                    return;
                }
                dialog.dismiss();
                verifySupabaseEmailCode(email, token);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                dialog.dismiss();
                requestSupabaseEmailCode(email);
            });
        });
        dialog.show();
    }

    private void verifySupabaseEmailCode(String email, String token) {
        setSupabaseControlsEnabled(false);
        supabaseSyncStatus.setText("Verifying Supabase sign-in code…");
        supabaseExecutor.execute(() -> {
            try {
                completeSupabaseConnection(new SupabaseAuthClient().verifyEmailOtp(email, token));
            } catch (Exception e) {
                String message = safeMessage(e);
                new SupabaseSyncStatusStore(MainActivity.this).markError(message, false);
                AppLogger.e("Supabase email code verification failed", e);
                runOnUiThread(() -> {
                    setSupabaseControlsEnabled(true);
                    refreshSupabaseBackupUi();
                    Toast.makeText(MainActivity.this,
                            "Supabase code verification failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void completeSupabaseConnection(SupabaseAuthClient.Session session) throws Exception {
        if (session == null || session.userId.isEmpty()) {
            throw new IllegalStateException("Supabase returned no account identity");
        }
        new SupabaseConnectionStore(this).saveSession(
                session.email, session.userId, session.refreshToken);
        new SupabaseSyncStatusStore(this).clearError();
        SyncCoordinator.forceDirty(this, "supabase-connect");
        SupabaseBackupScheduler.schedulePeriodic(this);
        SupabaseBackupScheduler.scheduleImmediate(this);
        runOnUiThread(() -> {
            setSupabaseControlsEnabled(true);
            refreshSupabaseBackupUi();
            toast("Supabase cloud sync connected");
        });
    }

    private void syncSupabaseNow() {
        if (!SupabaseConfig.isConfigured()) {
            refreshSupabaseBackupUi();
            Toast.makeText(this, "Supabase is not configured in this APK", Toast.LENGTH_LONG).show();
            return;
        }
        if (!new SupabaseConnectionStore(this).getState().connected) {
            showSupabaseAuthDialog();
            return;
        }
        SyncCoordinator.forceDirty(this, "supabase-manual-sync");
        SupabaseBackupScheduler.scheduleImmediate(this);
        supabaseSyncStatus.setText("Sync queued • will run when network is available");
        toast("Supabase sync queued");
    }

    private void confirmSupabaseRestore() {
        if (!new SupabaseConnectionStore(this).getState().connected) {
            showSupabaseAuthDialog();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Restore from Supabase?")
                .setMessage("FuelNote will merge the newest valid snapshot from each device. Newer records win and local-only records are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore / merge", (dialog, which) -> restoreFromSupabase())
                .show();
    }

    private void restoreFromSupabase() {
        if (!SupabaseConfig.isConfigured()) {
            refreshSupabaseBackupUi();
            Toast.makeText(this, "Supabase is not configured in this APK", Toast.LENGTH_LONG).show();
            return;
        }
        setSupabaseControlsEnabled(false);
        supabaseSyncStatus.setText("Restoring from Supabase…");
        supabaseExecutor.execute(() -> {
            try {
                SupabaseAuthClient.Session session = refreshSupabaseSession();
                String localDeviceId = SyncCoordinator.getState(MainActivity.this).deviceId;
                SupabaseRestoreManager.MergeResult result = new SupabaseRestoreManager()
                        .mergeSupabaseSnapshots(MainActivity.this, session.accessToken, localDeviceId, true);
                SupabaseSyncStatusStore restoreStatus = new SupabaseSyncStatusStore(MainActivity.this);
                restoreStatus.clearError();
                if (result.snapshotsApplied > 0) {
                    restoreStatus.markRemoteMerge(result.snapshotsApplied);
                }
                if (result.changed) {
                    SupabaseBackupScheduler.scheduleImmediate(MainActivity.this);
                    DriveBackupScheduler.scheduleImmediate(MainActivity.this);
                }
                runOnUiThread(() -> {
                    if (profileController != null) profileController.reloadFromPreferences();
                    if (progressController != null) progressController.refresh();
                    refreshHome();
                    setSupabaseControlsEnabled(true);
                    refreshSupabaseBackupUi();
                    toast(result.changed
                            ? "Supabase snapshot merged into FuelNote"
                            : "Supabase backup is already up to date");
                });
            } catch (Exception e) {
                boolean reauth = isSupabaseReauthFailure(e);
                String message = safeMessage(e);
                new SupabaseSyncStatusStore(MainActivity.this).markError(message, reauth);
                AppLogger.e("Manual Supabase restore failed", e);
                runOnUiThread(() -> {
                    setSupabaseControlsEnabled(true);
                    refreshSupabaseBackupUi();
                    Toast.makeText(MainActivity.this,
                            "Supabase restore failed: " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private SupabaseAuthClient.Session refreshSupabaseSession() throws Exception {
        SupabaseConnectionStore store = new SupabaseConnectionStore(this);
        SupabaseConnectionStore.State previous = store.getState();
        SupabaseAuthClient.Session session = new SupabaseAuthClient().refresh(store.loadRefreshToken());
        String userId = session.userId.isEmpty() ? previous.userId : session.userId;
        String email = session.email.isEmpty() ? previous.email : session.email;
        if (userId.isEmpty()) throw new IllegalStateException("Supabase account identity is unavailable. Sign in again.");
        store.saveSession(email, userId, session.refreshToken);
        return session;
    }

    private void confirmSupabaseDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle("Sign out of Supabase?")
                .setMessage("Automatic Supabase sync will stop on this device. Existing cloud snapshots remain available after you sign in again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out", (dialog, which) -> disconnectSupabase())
                .show();
    }

    private void disconnectSupabase() {
        setSupabaseControlsEnabled(false);
        supabaseExecutor.execute(() -> {
            boolean remoteSignedOut = false;
            if (SupabaseConfig.isConfigured()) {
                try {
                    SupabaseAuthClient.Session session = refreshSupabaseSession();
                    new SupabaseAuthClient().signOut(session.accessToken);
                    remoteSignedOut = true;
                } catch (Exception e) {
                    AppLogger.e("Supabase remote sign-out failed; clearing local session", e);
                }
            }
            SupabaseBackupScheduler.cancel(MainActivity.this);
            new SupabaseConnectionStore(MainActivity.this).clear();
            new SupabaseSyncStatusStore(MainActivity.this).clear();
            boolean signedOutEverywhere = remoteSignedOut;
            runOnUiThread(() -> {
                setSupabaseControlsEnabled(true);
                refreshSupabaseBackupUi();
                if (signedOutEverywhere) {
                    toast("Signed out of Supabase");
                } else {
                    Toast.makeText(MainActivity.this,
                            "Signed out locally. The remote session could not be revoked.",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void refreshSupabaseBackupUi() {
        if (supabaseConnectionStatus == null) return;
        boolean configured = SupabaseConfig.isConfigured();
        SupabaseConnectionStore.State connection = new SupabaseConnectionStore(this).getState();
        SupabaseSyncStatusStore.State status = new SupabaseSyncStatusStore(this).getState();
        SyncCoordinator.State sync = SyncCoordinator.getState(this);

        supabaseConnectedActions.setVisibility(connection.connected ? View.VISIBLE : View.GONE);
        supabaseConnectButton.setText(connection.connected
                ? (status.reauthRequired ? "Send a new code" : "Change account")
                : "Sign in with email code");
        styleCloudConnectButton(supabaseConnectButton, !connection.connected || status.reauthRequired);

        if (!configured) {
            supabaseConnectionStatus.setText("Not configured in this APK");
            supabaseSyncStatus.setText("This build is missing the Supabase project URL/publishable key. Local data and Google Drive continue to work.");
            supabaseConnectButton.setEnabled(false);
            supabaseSyncNowButton.setEnabled(false);
            supabaseRestoreButton.setEnabled(false);
            supabaseDisconnectButton.setEnabled(connection.connected);
            return;
        }

        supabaseConnectButton.setEnabled(true);
        supabaseDisconnectButton.setEnabled(true);
        if (!connection.connected) {
            supabaseConnectedActions.setVisibility(View.GONE);
            supabaseConnectionStatus.setText("Not signed in");
            if (!status.lastError.isEmpty()) {
                supabaseSyncStatus.setText("Last sign-in issue: " + status.lastError);
            } else {
                supabaseSyncStatus.setText("FuelNote stays local-first. Sign in with a Supabase email code to add backup and multi-device merge.");
            }
            return;
        }

        supabaseConnectedActions.setVisibility(View.VISIBLE);
        supabaseSyncNowButton.setEnabled(!status.reauthRequired);
        supabaseRestoreButton.setEnabled(!status.reauthRequired);
        String account = connection.email.isEmpty()
                ? "Supabase account connected"
                : "Connected • " + connection.email;
        supabaseConnectionStatus.setText(status.reauthRequired ? "Sign in again required" : account);

        if (status.reauthRequired) {
            supabaseSyncStatus.setText("The Supabase session expired. Sign in again to resume cloud sync.");
        } else if (!status.lastError.isEmpty()) {
            supabaseSyncStatus.setText("Last sync issue: " + status.lastError);
        } else if (status.lastSuccessAt > 0L) {
            String text = "Last synced " + formatCloudTime(status.lastSuccessAt)
                    + " • revision " + status.lastSuccessRevision;
            if (sync.localRevision > status.lastSuccessRevision) text += " • newer changes waiting";
            supabaseSyncStatus.setText(text);
        } else {
            supabaseSyncStatus.setText("Connected • first Supabase backup is waiting to sync");
        }
    }

    private void setSupabaseControlsEnabled(boolean enabled) {
        boolean configured = SupabaseConfig.isConfigured();
        if (supabaseConnectButton != null) supabaseConnectButton.setEnabled(enabled && configured);
        if (supabaseSyncNowButton != null) supabaseSyncNowButton.setEnabled(enabled && configured);
        if (supabaseRestoreButton != null) supabaseRestoreButton.setEnabled(enabled && configured);
        if (supabaseDisconnectButton != null) supabaseDisconnectButton.setEnabled(enabled);
    }

    private void styleCloudConnectButton(TextView button, boolean primary) {
        if (button == null) return;
        FrostedUi.surface(button, primary ? Material.ACTION : Material.CONTROL);
        button.setTextColor(getColor(primary ? R.color.background : R.color.accent));
    }

    private static boolean isSupabaseReauthFailure(Throwable error) {
        if (!(error instanceof SupabaseAuthClient.AuthHttpException)) return false;
        int status = ((SupabaseAuthClient.AuthHttpException) error).statusCode;
        return status == 400 || status == 401 || status == 403;
    }

    private String formatCloudTime(long timestamp) {
        try {
            return Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.getDefault()));
        } catch (RuntimeException ignored) {
            return "recently";
        }
    }

    private void configureDiagnostics() {
        findViewById(R.id.exportButton).setOnClickListener(v -> exportDiagnostics());
    }

    private void configureDateNavigation() {
        ViewGroup root = (ViewGroup) dateText.getParent();
        int dateIndex = root.indexOfChild(dateText);
        root.removeView(dateText);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        controls.setLayoutParams(controlsParams);

        TextView previous = dateControl("‹", 48);
        previous.setContentDescription(getString(R.string.previous_day));
        previous.setOnClickListener(v -> selectDate(selectedDate.minusDays(1)));
        controls.addView(previous);

        dateText.setVisibility(View.VISIBLE);
        dateText.setGravity(android.view.Gravity.CENTER);
        dateText.setTextColor(getColor(R.color.text_primary));
        dateText.setTextSize(15f);
        FrostedUi.surface(dateText, Material.CONTROL);
        dateText.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_ui_calendar, 0, 0, 0);
        dateText.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
        dateText.setCompoundDrawablePadding(dp(6));
        dateText.setPadding(dp(10), 0, dp(10), 0);
        dateText.setAutoSizeTextTypeUniformWithConfiguration(11, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        dateText.setTypeface(dateText.getTypeface(), android.graphics.Typeface.BOLD);
        dateText.setFocusable(true);
        dateText.setOnClickListener(v -> showDiaryDatePicker());
        controls.addView(dateText, new LinearLayout.LayoutParams(0, dp(48), 1f));

        homeTodayButton = dateControl("Today", 60);
        homeTodayButton.setContentDescription("Jump to today");
        homeTodayButton.setOnClickListener(v -> selectDate(LocalDate.now()));
        controls.addView(homeTodayButton);

        TextView next = dateControl("›", 48);
        next.setContentDescription(getString(R.string.next_day));
        next.setOnClickListener(v -> selectDate(selectedDate.plusDays(1)));
        controls.addView(next);

        root.addView(controls, dateIndex);
        renderDateNavigation();
    }

    private TextView dateControl(String text, int widthDp) {
        TextView control = new TextView(this);
        control.setText(text);
        control.setGravity(android.view.Gravity.CENTER);
        control.setTextColor(getColor(R.color.accent));
        control.setTextSize("Today".equals(text) ? 12f : 24f);
        control.setTypeface(control.getTypeface(), android.graphics.Typeface.BOLD);
        FrostedUi.surface(control, Material.CONTROL);
        control.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(48));
        params.setMarginStart(dp(2));
        params.setMarginEnd(dp(2));
        control.setLayoutParams(params);
        return control;
    }

    private void selectDate(LocalDate date) {
        selectedDate = date;
        renderDateNavigation();
        refreshHome();
        AppLogger.i("Diary date selected=" + selectedDate);
    }

    private void showDiaryDatePicker() {
        DatePickerDialog picker = new DatePickerDialog(
                this, R.style.DiaryDatePickerDialog,
                (view, year, month, dayOfMonth) -> selectDate(LocalDate.of(year, month + 1, dayOfMonth)),
                selectedDate.getYear(), selectedDate.getMonthValue() - 1, selectedDate.getDayOfMonth());
        picker.setTitle(R.string.choose_diary_date);
        picker.show();
    }

    private void renderDateNavigation() {
        LocalDate today = LocalDate.now();
        String headerPattern = selectedDate.getYear() == today.getYear() ? "EEE, d MMM" : "EEE, d MMM yyyy";
        DateTimeFormatter headerFormat = DateTimeFormatter.ofPattern(headerPattern, Locale.getDefault());
        dateText.setText(selectedDate.format(headerFormat));
        dateText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                selectedDate.equals(today) ? R.drawable.ic_ui_calendar : 0, 0, 0, 0);
        dateText.setContentDescription(getString(R.string.choose_diary_date) + ", "
                + selectedDate.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL)));
        if (homeTodayButton != null) {
            homeTodayButton.setVisibility(selectedDate.equals(today) ? View.GONE : View.VISIBLE);
        }
    }

    private void showScreen(View target, int navIndex) {
        homeScreen.setVisibility(target == homeScreen ? View.VISIBLE : View.GONE);
        searchScreen.setVisibility(target == searchScreen ? View.VISIBLE : View.GONE);
        detailScreen.setVisibility(target == detailScreen ? View.VISIBLE : View.GONE);
        manualScreen.setVisibility(target == manualScreen ? View.VISIBLE : View.GONE);
        nutritionScreen.setVisibility(target == nutritionScreen ? View.VISIBLE : View.GONE);
        progressScreen.setVisibility(target == progressScreen ? View.VISIBLE : View.GONE);
        profileScreen.setVisibility(target == profileScreen ? View.VISIBLE : View.GONE);
        editProfileScreen.setVisibility(target == editProfileScreen ? View.VISIBLE : View.GONE);
        accountScreen.setVisibility(target == accountScreen ? View.VISIBLE : View.GONE);
        boolean immersive = target == detailScreen || target == manualScreen || target == nutritionScreen
                || target == accountScreen || target == editProfileScreen;
        bottomNav.setVisibility(immersive ? View.GONE : View.VISIBLE);
        target.setPadding(target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(),
                immersive ? 0 : dp(100));
        FrostedUi.applyTree(target);
        ((FrostedNavigationView) bottomNav).refreshBackdrop();
        bottomNav.removeCallbacks(refreshFrostedNavigation);
        if (!immersive) bottomNav.postDelayed(refreshFrostedNavigation, 750);
        if (!immersive) updateNavColors(navIndex);
        uiMotion.enterScreen(target);
    }

    private final Runnable refreshFrostedNavigation = () -> {
        if (bottomNav != null && bottomNav.isAttachedToWindow()) {
            ((FrostedNavigationView) bottomNav).refreshBackdrop();
        }
    };

    private void updateNavColors(int selected) {
        ImageView[] icons = {navHomeIcon, navSearchIcon, navProgressIcon, navProfileIcon};
        TextView[] labels = {navHomeLabel, navSearchLabel, navProgressLabel, navProfileLabel};
        for (int i = 0; i < icons.length; i++) {
            int color = i == selected ? getColor(R.color.accent) : getColor(R.color.text_secondary);
            icons[i].setImageTintList(android.content.res.ColorStateList.valueOf(color));
            ((View) icons[i].getParent()).setSelected(i == selected);
            if (i == selected && selectedNavIndex != selected) uiMotion.emphasize(icons[i]);
            labels[i].setTextColor(color);
            labels[i].setTypeface(labels[i].getTypeface(), i == selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        selectedNavIndex = selected;
    }

    private void refreshTargetsAndProgress() {
        refreshAccountUi();
        refreshHome();
        if (nutritionScreen != null && nutritionScreen.getVisibility() == View.VISIBLE) refreshNutritionDetails();
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
        uiMotion.bindPressTree(homeScreen);
        ((FrostedNavigationView) bottomNav).refreshBackdrop();
    }

    private void openNutritionDetails() {
        refreshNutritionDetails();
        showScreen(nutritionScreen, -1);
        AppLogger.i("Daily nutrition opened date=" + selectedDate);
    }

    private void refreshNutritionDetails() {
        double[] totals = diary.getTotalsForDate(selectedDate);
        double calorieGoal = profileController == null ? CALORIE_GOAL : profileController.getCalorieGoal();
        double proteinGoal = profileController == null ? PROTEIN_GOAL : profileController.getProteinGoal();
        double carbsGoal = profileController == null ? CARBS_GOAL : profileController.getCarbsGoal();
        double fatGoal = profileController == null ? FAT_GOAL : profileController.getFatGoal();
        nutritionDateText.setText(selectedDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())));
        nutritionMacroSummary.setText(String.format(Locale.getDefault(),
                "Calories  %,.0f / %,.0f kcal\nProtein  %.0f / %.0f g\nCarbs  %.0f / %.0f g\nFat  %.0f / %.0f g",
                totals[0], calorieGoal, totals[1], proteinGoal, totals[2], carbsGoal, totals[3], fatGoal));

        List<FoodEntry> entries = diary.getEntriesForDate(selectedDate);
        DailyMicronutrientSummary summary = new DailyMicronutrientSummary();
        for (FoodEntry entry : entries) summary.addFood(resolveMicronutrients(entry));

        Map<Micronutrient, MicronutrientTarget> targets;
        if (profileController != null && profileController.hasSavedProfile()) {
            ProfileSettings settings = profileController.getSettings();
            targets = MicronutrientTargets.forProfile(settings, selectedDate);
            nutritionTargetNote.setText("Personalized Indian reference targets use your age, sex and energy needs where supported by ICMR-NIN guidance. Sodium is a ceiling, not a goal to fill.");
        } else {
            targets = java.util.Collections.emptyMap();
            nutritionTargetNote.setText("Save your Profile to calculate individualized vitamin and mineral targets. Intake amounts still use available offline food data.");
        }
        renderNutrientSection(nutritionMineralsContainer, Micronutrient.Group.MINERAL, summary, targets);
        renderNutrientSection(nutritionVitaminsContainer, Micronutrient.Group.VITAMIN, summary, targets);
    }

    private MicronutrientValues resolveMicronutrients(FoodEntry entry) {
        if (entry != null && !entry.micronutrients.isEmpty()) return entry.micronutrients;
        if (entry == null || catalog == null || entry.sourceFoodId == null || entry.grams <= 0.0) {
            return MicronutrientValues.empty();
        }
        CatalogFood source = catalog.findByCode(entry.sourceFoodId);
        if (source == null || source.micronutrients.isEmpty()) return MicronutrientValues.empty();
        return source.micronutrients.scaled(entry.grams / 100.0);
    }

    private void renderNutrientSection(LinearLayout container, Micronutrient.Group group,
                                       DailyMicronutrientSummary summary,
                                       Map<Micronutrient, MicronutrientTarget> targets) {
        container.removeAllViews();
        for (Micronutrient nutrient : Micronutrient.values()) {
            if (nutrient.group != group) continue;
            container.addView(nutrientRow(nutrient, summary, targets.get(nutrient)));
        }
    }

    private View nutrientRow(Micronutrient nutrient, DailyMicronutrientSummary summary, MicronutrientTarget target) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(13), dp(12), dp(13), dp(12));
        FrostedUi.surface(row, Material.CARD);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(nutrient.label);
        name.setTextColor(getColor(R.color.text_primary));
        name.setTextSize(14f);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        int known = summary.knownFoods(nutrient);
        int foods = summary.totalFoods();
        Double amount = summary.total(nutrient);
        boolean fullCoverage = summary.hasFullCoverage(nutrient);
        TextView value = new TextView(this);
        value.setTextColor(getColor(R.color.text_primary));
        value.setTextSize(13f);
        value.setGravity(android.view.Gravity.END);
        if (known == 0 || amount == null) {
            value.setText(target == null ? "No data" : "No data  •  target " + formatMicronutrient(target.amount, nutrient.unit));
        } else if (target == null) {
            value.setText((fullCoverage ? "" : "≥ ") + formatMicronutrient(amount, nutrient.unit));
        } else {
            String targetMark = target.upperLimit ? "≤ " : "";
            value.setText((fullCoverage ? "" : "≥ ") + formatMicronutrient(amount, nutrient.unit)
                    + "  /  " + targetMark + formatMicronutrient(target.amount, nutrient.unit));
        }
        top.addView(value);
        row.addView(top);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(false);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(7));
        barParams.topMargin = dp(9);
        row.addView(bar, barParams);

        TextView meta = new TextView(this);
        meta.setTextSize(11f);
        meta.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = dp(6);
        row.addView(meta, metaParams);

        if (known == 0 || amount == null) {
            bar.setVisibility(View.GONE);
            meta.setText(foods == 0 ? "No foods logged" : "No micronutrient data in " + foods + " logged food" + (foods == 1 ? "" : "s"));
            return row;
        }

        String coverage = fullCoverage ? "Data: all " + foods + " food" + (foods == 1 ? "" : "s")
                : "Partial data: " + known + "/" + foods + " foods";
        if (target == null || target.amount <= 0.0) {
            bar.setVisibility(View.GONE);
            meta.setText(coverage + "  •  no age-specific target available");
            return row;
        }

        double ratio = amount / target.amount;
        bar.setProgress((int) Math.round(Math.min(1.0, Math.max(0.0, ratio)) * 1000.0));
        boolean overCeiling = target.upperLimit && amount > target.amount;
        bar.setProgressTintList(ColorStateList.valueOf(getColor(overCeiling ? R.color.calories : R.color.accent)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(getColor(R.color.outline)));
        double percent = ratio * 100.0;
        String status;
        if (target.upperLimit) {
            if (overCeiling) status = fullCoverage ? "Over ceiling" : "Over ceiling from known foods";
            else status = (fullCoverage ? "" : "At least ") + String.format(Locale.getDefault(), "%.0f%% of ceiling used", percent);
        } else {
            status = (fullCoverage ? "" : "At least ") + String.format(Locale.getDefault(), "%.0f%% of target", percent);
        }
        meta.setText(status + "  •  " + coverage);
        if (overCeiling) meta.setTextColor(getColor(R.color.calories));
        return row;
    }

    private String formatMicronutrient(double value, String unit) {
        String number;
        if (value >= 100.0 || Math.abs(value - Math.rint(value)) < 0.005) {
            number = String.format(Locale.getDefault(), "%.0f", value);
        } else if (value >= 10.0) {
            number = String.format(Locale.getDefault(), "%.1f", value);
        } else {
            number = String.format(Locale.getDefault(), "%.2f", value);
        }
        return number + " " + unit;
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
            renderDateNavigation();
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
        renderMealSection(FoodEntry.MEAL_BREAKFAST, entries);
        renderMealSection(FoodEntry.MEAL_LUNCH, entries);
        renderMealSection(FoodEntry.MEAL_SNACKS, entries);
        renderMealSection(FoodEntry.MEAL_DINNER, entries);
    }

    private void renderMealSection(String mealType, List<FoodEntry> entries) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (entriesContainer.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.outline_soft));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.topMargin = dp(8);
            dividerParams.bottomMargin = dp(8);
            entriesContainer.addView(divider, dividerParams);
        }
        section.setLayoutParams(sectionParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        MealIllustrationView mealIllustration = new MealIllustrationView(this, mealType);
        LinearLayout.LayoutParams illustrationParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        illustrationParams.setMarginEnd(dp(10));
        header.addView(mealIllustration, illustrationParams);

        TextView title = new TextView(this);
        title.setText(FoodEntry.mealDisplayName(mealType));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout mealLabels = new LinearLayout(this);
        mealLabels.setOrientation(LinearLayout.VERTICAL);
        mealLabels.setGravity(android.view.Gravity.CENTER_VERTICAL);
        mealLabels.addView(title);
        header.addView(mealLabels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        double mealCalories = 0.0;
        for (FoodEntry entry : entries) {
            if (mealType.equals(entry.mealType)) mealCalories += entry.calories;
        }
        TextView calories = new TextView(this);
        calories.setText(String.format(Locale.getDefault(), "%.0f kcal", mealCalories));
        calories.setTextColor(getColor(R.color.text_secondary));
        calories.setTextSize(12f);
        calories.setGravity(android.view.Gravity.CENTER_VERTICAL);
        calories.setPadding(0, dp(4), 0, 0);
        mealLabels.addView(calories);

        TextView add = new TextView(this);
        add.setText("+ Add");
        add.setTextColor(getColor(R.color.accent));
        add.setTextSize(13f);
        add.setTypeface(add.getTypeface(), android.graphics.Typeface.BOLD);
        add.setGravity(android.view.Gravity.CENTER);
        add.setPadding(dp(12), 0, dp(12), 0);
        FrostedUi.surface(add, Material.CONTROL);
        add.setOnClickListener(v -> openMealSearch(mealType));
        add.setContentDescription("Add food to " + FoodEntry.mealDisplayName(mealType));
        add.setFocusable(true);
        header.addView(add, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
        section.addView(header);

        boolean hasEntries = false;
        for (FoodEntry entry : entries) {
            if (!mealType.equals(entry.mealType)) continue;
            hasEntries = true;
            View row = getLayoutInflater().inflate(R.layout.item_diary_entry, section, false);
            photoLoader.bind(row.findViewById(R.id.entryImage), row.findViewById(R.id.entryIcon),
                    entry.sourceFoodId, foodGlyph(entry.name), null);
            ((TextView) row.findViewById(R.id.entryName)).setText(entry.name);
            ((TextView) row.findViewById(R.id.entryAmount)).setText(String.format(Locale.getDefault(), "%.0f g  •  %s", entry.grams, friendlySource(entry.source)));
            ((TextView) row.findViewById(R.id.entryMacros)).setText(String.format(Locale.getDefault(), "P %.0fg    C %.0fg    F %.0fg", entry.protein, entry.carbs, entry.fat));
            ((TextView) row.findViewById(R.id.entryCalories)).setText(String.format(Locale.getDefault(), "%.0f kcal", entry.calories));
            row.setOnClickListener(v -> showEntryActions(entry));
            row.setContentDescription("Edit " + entry.name);
            FrostedUi.applyTree(row);
            uiMotion.bindPressTree(row);
            section.addView(row);
        }

        if (!hasEntries) {
            calories.setText(String.format(Locale.getDefault(), "%.0f kcal · No foods logged", mealCalories));
            calories.setTextColor(getColor(R.color.text_tertiary));
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
        searchSectionTitle.setText("Search results");
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
                .setMessage(barcode + " is not in FuelNote's offline database.")
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
        searchSectionTitle.setText("Popular foods");
        renderFoodResults(foods);
    }

    private void loadRecentFoods() {
        selectShortcutTab(shortcutRecent);
        searchSectionTitle.setText("Recent foods");
        renderHistoryResults(diary.getRecentFoods(20), "recent");
    }

    private void loadFrequentFoods() {
        selectShortcutTab(shortcutFrequent);
        searchSectionTitle.setText("Frequent foods");
        renderHistoryResults(diary.getFrequentFoods(20), "frequent");
    }

    private void loadFavoriteFoods() {
        selectShortcutTab(shortcutFavorites);
        searchSectionTitle.setText("Favorites");
        renderHistoryResults(diary.getFavoriteFoods(30), "favorite");
    }

    private void selectShortcutTab(TextView selected) {
        TextView[] tabs = {shortcutAll, shortcutRecent, shortcutFrequent, shortcutFavorites};
        for (TextView tab : tabs) {
            boolean active = tab == selected;
            FrostedUi.surface(tab, active ? Material.ACTION : Material.CONTROL);
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
            photoLoader.bind(row.findViewById(R.id.foodImage), row.findViewById(R.id.foodIcon),
                    entry.sourceFoodId, foodGlyph(entry.name), null);
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
            ((TextView) row.findViewById(R.id.foodMacros)).setText(foodNutritionText(
                    String.format(Locale.getDefault(), "%.0f kcal / %s g", entry.calories, formatAmount(entry.grams)),
                    entry.protein, entry.carbs, entry.fat, true));
            TextView action = row.findViewById(R.id.foodAdd);
            action.setText("↻");
            action.setContentDescription("Quick log " + entry.name);
            action.setOnClickListener(v -> quickRelog(entry));
            row.setOnClickListener(v -> openHistoryFood(entry));
            FrostedUi.applyTree(row);
            uiMotion.bindPressTree(row);
            searchResultsContainer.addView(row);
            ((FrostedNavigationView) bottomNav).refreshBackdrop();
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
            photoLoader.bind(row.findViewById(R.id.foodImage), row.findViewById(R.id.foodIcon),
                    food.code, foodGlyph(food.name), null);
            ((TextView) row.findViewById(R.id.foodName)).setText(food.name);
            ((TextView) row.findViewById(R.id.foodSubtitle)).setText(searchSubtitle(food));
            ((TextView) row.findViewById(R.id.foodMacros)).setText(searchMacroLine(food));
            row.setOnClickListener(v -> openDetail(food));
            row.findViewById(R.id.foodAdd).setContentDescription("Add " + food.name);
            row.findViewById(R.id.foodAdd).setOnClickListener(v -> openDetail(food));
            FrostedUi.applyTree(row);
            uiMotion.bindPressTree(row);
            searchResultsContainer.addView(row);
            ((FrostedNavigationView) bottomNav).refreshBackdrop();
        }
    }

    private String searchSubtitle(CatalogFood food) {
        String source = friendlySource(food.source);
        if (food.brand != null && !food.brand.trim().isEmpty()) {
            return compact(food.brand, 34) + " • " + source;
        }
        if (food.category != null && !food.category.trim().isEmpty()) {
            return source + " • " + compact(food.category, 34);
        }
        if (food.servingSize != null && !food.servingSize.trim().isEmpty()) {
            return compact(food.servingSize, 34) + " • " + source;
        }
        return source;
    }

    private CharSequence searchMacroLine(CatalogFood food) {
        String kcal = food.calories == null ? "—" : formatAmount(food.calories);
        return foodNutritionText(kcal + " kcal / 100 g", food.protein, food.carbs,
                food.fat, food.hasCompleteCoreMacros());
    }

    private CharSequence foodNutritionText(String energy, Double protein, Double carbs,
                                          Double fat, boolean complete) {
        String[] values = {"P " + macroAmount(protein), "C " + macroAmount(carbs), "F " + macroAmount(fat)};
        String text = energy + "\n" + String.join("   ·   ", values) + (complete ? "" : "\nIncomplete nutrition data");
        android.text.SpannableString styled = new android.text.SpannableString(text);
        styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, energy.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.text_primary)),
                0, energy.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int[] colors = {R.color.protein, R.color.carbs, R.color.fat};
        int offset = energy.length() + 1;
        for (int i = 0; i < values.length; i++) {
            styled.setSpan(new android.text.style.ForegroundColorSpan(getColor(colors[i])),
                    offset, offset + values[i].length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            offset += values[i].length() + "   ·   ".length();
        }
        return styled;
    }

    private String macroAmount(Double value) {
        return value == null ? "—" : formatAmount(value) + " g";
    }

    private void openDetail(CatalogFood food) {
        selectedFood = food;
        restoreServingState(food);
        detailPhotoCredit.setVisibility(View.GONE);
        detailPhotoCredit.setOnClickListener(null);
        photoLoader.bind(detailImage, detailEmoji, food.code, foodGlyph(food.name), photo -> {
            detailPhotoCredit.setText(("product".equals(photo.kind) ? "Product photo" : "Representative photo") + " • Photo credits ›");
            detailPhotoCredit.setVisibility(View.VISIBLE);
            detailPhotoCredit.setOnClickListener(v -> showPhotoCredits(photo));
        });
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

    private void showPhotoCredits(FoodPhotoLoader.Photo photo) {
        new AlertDialog.Builder(this)
                .setTitle("Photo credits")
                .setMessage(photo.credit + "\n\n" + photo.license +
                        "\n\nResized and compressed for offline use." +
                        ("representative".equals(photo.kind) ? "\nThis is a representative food photo; preparation may vary." : ""))
                .setPositiveButton("Source", (dialog, which) -> openPhotoLink(photo.sourceUrl))
                .setNeutralButton("License", (dialog, which) -> openPhotoLink(photo.licenseUrl))
                .setNegativeButton("Close", null).show();
    }

    private void openPhotoLink(String url) {
        Uri uri = Uri.parse(url);
        if (!"https".equals(uri.getScheme())) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException error) {
            toast("No browser available");
        }
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
                .putLong(servingMemoryKey(selectedFood) + ".updatedAt", System.currentTimeMillis())
                .apply();
        SyncCoordinator.markUserDataChanged(this, "serving-memory");
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
                loggedAt, activeMealType, selectedFood.source, selectedFood.code, selectedFood.micronutrients);
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
        manualSourceFood = sourceFood;
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
            FoodEntry entry;
            if (manualSourceFood != null) {
                entry = FoodEntry.fromPer100g(name, grams, calories, protein, carbs, fat, selectedDateTimestamp(),
                        activeMealType, manualSourceFood.source, manualSourceFood.code, manualSourceFood.micronutrients);
            } else {
                entry = FoodEntry.fromPer100g(name, grams, calories, protein, carbs, fat, selectedDateTimestamp(), activeMealType);
            }
            long id = diary.insert(entry);
            syncFavoriteSnapshot(entry);
            AppLogger.i("Food entry added id=" + id + " source=manual meal=" + activeMealType);
            clearManualInputs();
            manualSourceFood = null;
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
        manualSourceFood = null;
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

    private String foodGlyph(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("idli")) return "ID";
        if (n.contains("dosa") || n.contains("chapati") || n.contains("roti")) return "FL";
        if (n.contains("biryani") || n.contains("rice") || n.contains("pulao")) return "RI";
        if (n.contains("paneer") || n.contains("curry") || n.contains("dal") || n.contains("sambar")) return "CU";
        if (n.contains("bread") || n.contains("bun")) return "BR";
        if (n.contains("milk") || n.contains("curd") || n.contains("yogurt")) return "DA";
        if (n.contains("banana") || n.contains("apple") || n.contains("fruit")) return "FR";
        if (n.contains("egg")) return "EG";
        if (n.contains("chicken")) return "CH";
        if (n.contains("tea") || n.contains("coffee")) return "DR";
        return "FO";
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
        if (searchMealTargetText != null) searchMealTargetText.setText("Adding to " + target);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (driveSyncStatusStore != null && driveSyncStatusListener != null) {
            driveSyncStatusStore.registerListener(driveSyncStatusListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uiMotion != null) uiMotion.setForeground(true);
        if (bottomNav != null) ((FrostedNavigationView) bottomNav).refreshBackdrop();
        refreshAfterDriveDataChangeIfNeeded();
        refreshDriveBackupUi();
        refreshSupabaseBackupUi();
        refreshAccountUi();
    }

    @Override
    protected void onStop() {
        if (driveSyncStatusStore != null && driveSyncStatusListener != null) {
            driveSyncStatusStore.unregisterListener(driveSyncStatusListener);
        }
        super.onStop();
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
        if (GoogleDriveAuthorization.handleActivityResult(
                this, requestCode, resultCode, data, driveAuthorizationCallback())) return;
        if (profileController != null && profileController.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (editProfileScreen.getVisibility() == View.VISIBLE) {
            closeEditProfileScreen();
        } else if (accountScreen.getVisibility() == View.VISIBLE) {
            showProfileScreen();
        } else if (nutritionScreen.getVisibility() == View.VISIBLE) {
            showScreen(homeScreen, 0);
        } else if (detailScreen.getVisibility() == View.VISIBLE || manualScreen.getVisibility() == View.VISIBLE) {
            showScreen(searchScreen, 1);
        } else if (homeScreen.getVisibility() != View.VISIBLE) {
            showScreen(homeScreen, 0);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        if (uiMotion != null) uiMotion.setForeground(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (uiMotion != null) uiMotion.close();
        if (bottomNav != null) bottomNav.removeCallbacks(refreshFrostedNavigation);
        if (photoLoader != null) photoLoader.close();
        driveExecutor.shutdownNow();
        supabaseExecutor.shutdownNow();
        if (catalog != null) catalog.close();
        diary.close();
        super.onDestroy();
    }
}
