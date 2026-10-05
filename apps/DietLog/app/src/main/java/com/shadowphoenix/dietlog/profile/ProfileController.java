package com.shadowphoenix.dietlog.profile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.shadowphoenix.dietlog.R;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class ProfileController {
    public static final int PICK_AVATAR_REQUEST = 6407;
    private static final double LEGACY_CALORIE_GOAL = 2000.0;
    private static final double LEGACY_PROTEIN_GOAL = 150.0;
    private static final double LEGACY_CARBS_GOAL = 250.0;
    private static final double LEGACY_FAT_GOAL = 70.0;

    private final Activity activity;
    private final Runnable onTargetsChanged;
    private final ProfilePreferences preferences;
    private ProfileSettings settings;
    private boolean hasSavedProfile;
    private boolean suppressCallbacks;

    private EditText nameInput, ageInput, heightInput, weightInput, targetWeightInput, weeklyInput, manualCaloriesInput;
    private EditText proteinInput, carbsInput, fatInput, proteinPctInput, carbsPctInput, fatPctInput;
    private EditText fibreInput, sugarInput, sodiumInput;
    private Spinner sexSpinner, activitySpinner, goalSpinner, macroModeSpinner;
    private CheckBox manualCalorieCheck;
    private TextView dobButton, activityDescription, goalHint, calorieTargetText, energyBreakdown;
    private TextView macroSummary, macroWarning, headerName, headerSummary, avatarFallback;
    private ImageView avatarImage;

    public ProfileController(Activity activity, Runnable onTargetsChanged) {
        this.activity = activity;
        this.onTargetsChanged = onTargetsChanged;
        this.preferences = new ProfilePreferences(activity);
        this.hasSavedProfile = preferences.hasSavedProfile();
        this.settings = preferences.load();
    }

    public void bind() {
        bindViews();
        setupSpinners();
        populateUi();
        setupInteractions();
        refreshPreview();
    }

    public double getCalorieGoal() {
        if (!hasSavedProfile) return LEGACY_CALORIE_GOAL;
        try { return CalorieCalculator.calculate(settings, LocalDate.now()).calorieTarget; }
        catch (RuntimeException ignored) { return LEGACY_CALORIE_GOAL; }
    }

    public double getProteinGoal() { return hasSavedProfile ? positiveOr(settings.proteinGoalG, LEGACY_PROTEIN_GOAL) : LEGACY_PROTEIN_GOAL; }
    public double getCarbsGoal() { return hasSavedProfile ? Math.max(0.0, settings.carbsGoalG) : LEGACY_CARBS_GOAL; }
    public double getFatGoal() { return hasSavedProfile ? positiveOr(settings.fatGoalG, LEGACY_FAT_GOAL) : LEGACY_FAT_GOAL; }
    public ProfileSettings getSettings() { return settings; }

    public void refreshPreview() {
        if (calorieTargetText == null) return;
        ProfileSettings draft = readDraft(false);
        updateActivityDescription(draft.activityLevel);
        updateGoalHint(draft);
        updateHeader(draft);
        updateMacroModeState(draft.macroMode);
        try {
            CalorieCalculator.Estimate e = CalorieCalculator.calculate(draft, LocalDate.now());
            calorieTargetText.setText(String.format(Locale.getDefault(), "%,.0f kcal/day", e.calorieTarget));
            String adjustment = e.dailyAdjustment >= 0
                    ? String.format(Locale.getDefault(), "+%.0f", e.dailyAdjustment)
                    : String.format(Locale.getDefault(), "%.0f", e.dailyAdjustment);
            energyBreakdown.setText(String.format(Locale.getDefault(),
                    "BMR %,.0f kcal  •  TDEE %,.0f kcal  •  %s kcal/day vs maintenance%s",
                    e.bmr, e.tdee, adjustment, e.manualOverride ? "  •  manual override" : ""));
            updateMacroSummary(draft, e.calorieTarget);
        } catch (IllegalArgumentException e) {
            calorieTargetText.setText("— kcal/day");
            energyBreakdown.setText(e.getMessage());
            macroSummary.setText("");
            macroWarning.setText("");
        }
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_AVATAR_REQUEST) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // Some document providers do not offer persistable grants; the current grant can still display the image.
        }
        settings.avatarUri = uri.toString();
        showAvatar(settings.avatarUri);
        if (hasSavedProfile) preferences.save(settings);
        return true;
    }

    private void bindViews() {
        nameInput = activity.findViewById(R.id.profileNameInput);
        ageInput = activity.findViewById(R.id.profileAgeInput);
        heightInput = activity.findViewById(R.id.profileHeightInput);
        weightInput = activity.findViewById(R.id.profileWeightInput);
        targetWeightInput = activity.findViewById(R.id.profileTargetWeightInput);
        weeklyInput = activity.findViewById(R.id.profileWeeklyChangeInput);
        manualCaloriesInput = activity.findViewById(R.id.profileManualCalorieInput);
        proteinInput = activity.findViewById(R.id.profileProteinInput);
        carbsInput = activity.findViewById(R.id.profileCarbsInput);
        fatInput = activity.findViewById(R.id.profileFatInput);
        proteinPctInput = activity.findViewById(R.id.profileProteinPercentInput);
        carbsPctInput = activity.findViewById(R.id.profileCarbsPercentInput);
        fatPctInput = activity.findViewById(R.id.profileFatPercentInput);
        fibreInput = activity.findViewById(R.id.profileFibreInput);
        sugarInput = activity.findViewById(R.id.profileSugarInput);
        sodiumInput = activity.findViewById(R.id.profileSodiumInput);
        sexSpinner = activity.findViewById(R.id.profileSexSpinner);
        activitySpinner = activity.findViewById(R.id.profileActivitySpinner);
        goalSpinner = activity.findViewById(R.id.profileGoalSpinner);
        macroModeSpinner = activity.findViewById(R.id.profileMacroModeSpinner);
        manualCalorieCheck = activity.findViewById(R.id.profileManualCalorieCheck);
        dobButton = activity.findViewById(R.id.profileDobButton);
        activityDescription = activity.findViewById(R.id.profileActivityDescription);
        goalHint = activity.findViewById(R.id.profileGoalHint);
        calorieTargetText = activity.findViewById(R.id.profileCalorieTargetText);
        energyBreakdown = activity.findViewById(R.id.profileEnergyBreakdown);
        macroSummary = activity.findViewById(R.id.profileMacroSummary);
        macroWarning = activity.findViewById(R.id.profileMacroWarning);
        headerName = activity.findViewById(R.id.profileHeaderName);
        headerSummary = activity.findViewById(R.id.profileHeaderSummary);
        avatarFallback = activity.findViewById(R.id.profileAvatarFallback);
        avatarImage = activity.findViewById(R.id.profileAvatarImage);
    }

    private void setupSpinners() {
        sexSpinner.setAdapter(enumAdapter(ProfileSettings.Sex.values()));
        activitySpinner.setAdapter(enumAdapter(ProfileSettings.ActivityLevel.values()));
        goalSpinner.setAdapter(enumAdapter(ProfileSettings.Goal.values()));
        macroModeSpinner.setAdapter(enumAdapter(ProfileSettings.MacroMode.values()));
    }

    private <T> ArrayAdapter<T> enumAdapter(T[] values) {
        return new ArrayAdapter<T>(activity, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(activity.getColor(R.color.text_primary));
                view.setTextSize(14f);
                view.setPadding(dp(4), 0, dp(4), 0);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(activity.getColor(R.color.text_primary));
                view.setBackgroundColor(activity.getColor(R.color.surface_elevated));
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }

            { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); }
        };
    }

    private void populateUi() {
        suppressCallbacks = true;
        nameInput.setText(settings.displayName);
        ageInput.setText(String.valueOf(settings.ageYears));
        heightInput.setText(format(settings.heightCm));
        weightInput.setText(format(settings.currentWeightKg));
        targetWeightInput.setText(format(settings.targetWeightKg));
        weeklyInput.setText(formatSigned(settings.weeklyWeightChangeKg));
        manualCaloriesInput.setText(format(settings.manualCalorieTarget));
        manualCalorieCheck.setChecked(settings.manualCalorieOverride);
        proteinInput.setText(format(settings.proteinGoalG));
        carbsInput.setText(format(settings.carbsGoalG));
        fatInput.setText(format(settings.fatGoalG));
        proteinPctInput.setText(format(settings.proteinPercent));
        carbsPctInput.setText(format(settings.carbsPercent));
        fatPctInput.setText(format(settings.fatPercent));
        fibreInput.setText(optionalFormat(settings.fibreGoalG));
        sugarInput.setText(optionalFormat(settings.sugarLimitG));
        sodiumInput.setText(optionalFormat(settings.sodiumLimitMg));
        sexSpinner.setSelection(settings.sex.ordinal());
        activitySpinner.setSelection(settings.activityLevel.ordinal());
        goalSpinner.setSelection(settings.goal.ordinal());
        macroModeSpinner.setSelection(settings.macroMode.ordinal());
        updateDobText();
        showAvatar(settings.avatarUri);
        manualCaloriesInput.setEnabled(settings.manualCalorieOverride);
        manualCaloriesInput.setAlpha(settings.manualCalorieOverride ? 1f : 0.45f);
        suppressCallbacks = false;
    }

    private void setupInteractions() {
        AdapterView.OnItemSelectedListener genericListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!suppressCallbacks) refreshPreview();
            }
        };
        sexSpinner.setOnItemSelectedListener(genericListener);
        activitySpinner.setOnItemSelectedListener(genericListener);
        macroModeSpinner.setOnItemSelectedListener(genericListener);
        goalSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressCallbacks) return;
                ProfileSettings draft = readDraft(false);
                if (draft.goal != ProfileSettings.Goal.CUSTOM) {
                    weeklyInput.setText(formatSigned(CalorieCalculator.recommendedWeeklyChange(draft)));
                }
                refreshPreview();
            }
        });

        manualCalorieCheck.setOnCheckedChangeListener((button, checked) -> {
            manualCaloriesInput.setEnabled(checked);
            manualCaloriesInput.setAlpha(checked ? 1f : 0.45f);
            if (!suppressCallbacks) refreshPreview();
        });

        activity.findViewById(R.id.profileUseRecommendedRate).setOnClickListener(v -> {
            ProfileSettings draft = readDraft(false);
            weeklyInput.setText(formatSigned(CalorieCalculator.recommendedWeeklyChange(draft)));
            refreshPreview();
        });
        activity.findViewById(R.id.profileUseRecommendedMacros).setOnClickListener(v -> applyRecommendedMacros());
        activity.findViewById(R.id.profileSaveButton).setOnClickListener(v -> save());
        activity.findViewById(R.id.profileAvatarPicker).setOnClickListener(v -> pickAvatar());
        dobButton.setOnClickListener(v -> pickDateOfBirth());

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!suppressCallbacks) refreshPreview();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        EditText[] watched = {nameInput, ageInput, heightInput, weightInput, targetWeightInput, weeklyInput,
                manualCaloriesInput, proteinInput, carbsInput, fatInput, proteinPctInput, carbsPctInput, fatPctInput};
        for (EditText input : watched) input.addTextChangedListener(watcher);
    }

    private void save() {
        try {
            ProfileSettings draft = readDraft(true);
            CalorieCalculator.Estimate estimate = CalorieCalculator.calculate(draft, LocalDate.now());
            if (draft.targetWeightKg < 25 || draft.targetWeightKg > 350) {
                throw new IllegalArgumentException("Target weight must be between 25 and 350 kg");
            }
            if (draft.macroMode == ProfileSettings.MacroMode.PERCENT) {
                double sum = draft.proteinPercent + draft.carbsPercent + draft.fatPercent;
                if (Math.abs(sum - 100.0) > 0.5) {
                    throw new IllegalArgumentException("Macro percentages must total 100%");
                }
                double[] grams = NutritionGoalCalculator.gramsFromPercent(
                        estimate.calorieTarget, draft.proteinPercent, draft.carbsPercent, draft.fatPercent);
                draft.proteinGoalG = grams[0];
                draft.carbsGoalG = grams[1];
                draft.fatGoalG = grams[2];
            } else {
                validateMacroGrams(draft);
                draft.proteinPercent = draft.proteinGoalG * 4.0 / estimate.calorieTarget * 100.0;
                draft.carbsPercent = draft.carbsGoalG * 4.0 / estimate.calorieTarget * 100.0;
                draft.fatPercent = draft.fatGoalG * 9.0 / estimate.calorieTarget * 100.0;
            }
            settings = draft;
            preferences.save(settings);
            hasSavedProfile = true;
            populateUi();
            refreshPreview();
            onTargetsChanged.run();
            Toast.makeText(activity, "Profile and nutrition targets saved", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyRecommendedMacros() {
        try {
            ProfileSettings draft = readDraft(false);
            CalorieCalculator.Estimate e = CalorieCalculator.calculate(draft, LocalDate.now());
            NutritionGoalCalculator.Goals goals = NutritionGoalCalculator.recommend(draft, e.calorieTarget);
            suppressCallbacks = true;
            proteinInput.setText(format(goals.proteinG));
            carbsInput.setText(format(goals.carbsG));
            fatInput.setText(format(goals.fatG));
            fibreInput.setText(format(goals.fibreG));
            sugarInput.setText(format(goals.sugarLimitG));
            sodiumInput.setText(format(goals.sodiumLimitMg));
            proteinPctInput.setText(format(goals.proteinG * 4.0 / e.calorieTarget * 100.0));
            carbsPctInput.setText(format(goals.carbsG * 4.0 / e.calorieTarget * 100.0));
            fatPctInput.setText(format(goals.fatG * 9.0 / e.calorieTarget * 100.0));
            suppressCallbacks = false;
            refreshPreview();
        } catch (IllegalArgumentException e) {
            suppressCallbacks = false;
            Toast.makeText(activity, "Complete body details before generating macro targets", Toast.LENGTH_SHORT).show();
        }
    }

    private ProfileSettings readDraft(boolean strict) {
        ProfileSettings s = new ProfileSettings();
        s.displayName = nameInput.getText().toString().trim();
        s.avatarUri = settings.avatarUri;
        s.dateOfBirthIso = settings.dateOfBirthIso;
        s.ageYears = strict ? intRequired(ageInput, "age") : intOr(ageInput, settings.ageYears);
        s.sex = (ProfileSettings.Sex) sexSpinner.getSelectedItem();
        s.heightCm = strict ? required(heightInput, "height") : numberOr(heightInput, settings.heightCm);
        s.currentWeightKg = strict ? required(weightInput, "current weight") : numberOr(weightInput, settings.currentWeightKg);
        s.targetWeightKg = strict ? required(targetWeightInput, "target weight") : numberOr(targetWeightInput, settings.targetWeightKg);
        s.activityLevel = (ProfileSettings.ActivityLevel) activitySpinner.getSelectedItem();
        s.goal = (ProfileSettings.Goal) goalSpinner.getSelectedItem();
        s.weeklyWeightChangeKg = numberOr(weeklyInput, 0.0);
        s.manualCalorieOverride = manualCalorieCheck.isChecked();
        s.manualCalorieTarget = strict && s.manualCalorieOverride
                ? required(manualCaloriesInput, "manual calorie target")
                : numberOr(manualCaloriesInput, settings.manualCalorieTarget);
        s.macroMode = (ProfileSettings.MacroMode) macroModeSpinner.getSelectedItem();
        s.proteinGoalG = strict && s.macroMode == ProfileSettings.MacroMode.GRAMS
                ? required(proteinInput, "protein goal") : numberOr(proteinInput, settings.proteinGoalG);
        s.carbsGoalG = strict && s.macroMode == ProfileSettings.MacroMode.GRAMS
                ? required(carbsInput, "carb goal") : numberOr(carbsInput, settings.carbsGoalG);
        s.fatGoalG = strict && s.macroMode == ProfileSettings.MacroMode.GRAMS
                ? required(fatInput, "fat goal") : numberOr(fatInput, settings.fatGoalG);
        s.proteinPercent = strict && s.macroMode == ProfileSettings.MacroMode.PERCENT
                ? required(proteinPctInput, "protein percentage") : numberOr(proteinPctInput, settings.proteinPercent);
        s.carbsPercent = strict && s.macroMode == ProfileSettings.MacroMode.PERCENT
                ? required(carbsPctInput, "carb percentage") : numberOr(carbsPctInput, settings.carbsPercent);
        s.fatPercent = strict && s.macroMode == ProfileSettings.MacroMode.PERCENT
                ? required(fatPctInput, "fat percentage") : numberOr(fatPctInput, settings.fatPercent);
        s.fibreGoalG = strict ? optionalNonNegative(fibreInput, "fibre goal") : nonNegativeOr(fibreInput, settings.fibreGoalG);
        s.sugarLimitG = strict ? optionalNonNegative(sugarInput, "sugar limit") : nonNegativeOr(sugarInput, settings.sugarLimitG);
        s.sodiumLimitMg = strict ? optionalNonNegative(sodiumInput, "sodium limit") : nonNegativeOr(sodiumInput, settings.sodiumLimitMg);
        return s;
    }

    private void updateHeader(ProfileSettings draft) {
        headerName.setText(draft.displayName.isEmpty() ? "Your profile" : draft.displayName);
        String summary = String.format(Locale.getDefault(), "%.0f kg  •  %.0f cm  •  %s  •  %s",
                draft.currentWeightKg, draft.heightCm, draft.goal.label, draft.activityLevel.label);
        headerSummary.setText(summary);
    }

    private void updateActivityDescription(ProfileSettings.ActivityLevel level) {
        activityDescription.setText(level.description + String.format(Locale.US, "  •  ×%.3f TDEE factor", level.multiplier));
    }

    private void updateGoalHint(ProfileSettings draft) {
        ProfileSettings.Goal goal = draft.goal;
        if (goal == ProfileSettings.Goal.MAINTAIN) {
            goalHint.setText("Maintenance keeps the calorie target at estimated TDEE.");
        } else if (goal == ProfileSettings.Goal.CUSTOM) {
            goalHint.setText("Custom accepts a signed weekly change: negative to lose, positive to gain. Adjustment is capped at ±30% of TDEE.");
        } else {
            double recommended = CalorieCalculator.recommendedWeeklyChange(draft);
            goalHint.setText(String.format(Locale.getDefault(),
                    "Recommended starting rate: %+.2f kg/week (%.2f%% body weight/week). Calorie adjustment is capped at %.0f%% of TDEE.",
                    recommended, goal.defaultBodyWeightFractionPerWeek * 100.0, goal.maxTdeeAdjustmentFraction * 100.0));
        }
        boolean enabled = goal != ProfileSettings.Goal.MAINTAIN;
        weeklyInput.setEnabled(enabled);
        weeklyInput.setAlpha(enabled ? 1f : 0.45f);
        activity.findViewById(R.id.profileUseRecommendedRate).setEnabled(enabled);
        activity.findViewById(R.id.profileUseRecommendedRate).setAlpha(enabled ? 1f : 0.45f);
    }

    private void updateMacroModeState(ProfileSettings.MacroMode mode) {
        boolean grams = mode == ProfileSettings.MacroMode.GRAMS;
        setEnabledGroup(grams, proteinInput, carbsInput, fatInput);
        setEnabledGroup(!grams, proteinPctInput, carbsPctInput, fatPctInput);
    }

    private void updateMacroSummary(ProfileSettings draft, double calorieTarget) {
        double p, c, f;
        if (draft.macroMode == ProfileSettings.MacroMode.PERCENT) {
            double[] grams = NutritionGoalCalculator.gramsFromPercent(
                    calorieTarget, draft.proteinPercent, draft.carbsPercent, draft.fatPercent);
            p = grams[0]; c = grams[1]; f = grams[2];
            double sum = draft.proteinPercent + draft.carbsPercent + draft.fatPercent;
            macroSummary.setText(String.format(Locale.getDefault(),
                    "At %,.0f kcal: %.0fg protein • %.0fg carbs • %.0fg fat", calorieTarget, p, c, f));
            macroWarning.setText(Math.abs(sum - 100.0) > 0.5
                    ? String.format(Locale.getDefault(), "Macro percentages total %.1f%% — set them to 100%%.", sum)
                    : "");
        } else {
            p = draft.proteinGoalG; c = draft.carbsGoalG; f = draft.fatGoalG;
            double macroCalories = NutritionGoalCalculator.macroCalories(p, c, f);
            double differencePct = calorieTarget <= 0 ? 0 : Math.abs(macroCalories - calorieTarget) / calorieTarget * 100.0;
            macroSummary.setText(String.format(Locale.getDefault(),
                    "Macros provide ~%,.0f kcal/day (P %.0fg • C %.0fg • F %.0fg)", macroCalories, p, c, f));
            macroWarning.setText(differencePct > 5.0
                    ? String.format(Locale.getDefault(), "Macro calories differ from your calorie target by %.0f%%.", differencePct)
                    : "");
        }
    }

    private void pickDateOfBirth() {
        LocalDate initial;
        try { initial = LocalDate.parse(settings.dateOfBirthIso); }
        catch (RuntimeException ignored) { initial = LocalDate.now().minusYears(Math.max(13, intOr(ageInput, 25))); }
        DatePickerDialog dialog = new DatePickerDialog(activity, (view, year, month, day) -> {
            LocalDate selected = LocalDate.of(year, month + 1, day);
            if (selected.isAfter(LocalDate.now())) {
                Toast.makeText(activity, "Date of birth cannot be in the future", Toast.LENGTH_SHORT).show();
                return;
            }
            settings.dateOfBirthIso = selected.toString();
            int age = Period.between(selected, LocalDate.now()).getYears();
            ageInput.setText(String.valueOf(age));
            updateDobText();
            refreshPreview();
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void updateDobText() {
        if (settings.dateOfBirthIso == null || settings.dateOfBirthIso.isEmpty()) {
            dobButton.setText("Select date");
            return;
        }
        try {
            LocalDate dob = LocalDate.parse(settings.dateOfBirthIso);
            dobButton.setText(dob.format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())));
        } catch (DateTimeParseException e) {
            dobButton.setText("Select date");
        }
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        activity.startActivityForResult(intent, PICK_AVATAR_REQUEST);
    }

    private void showAvatar(String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) {
            avatarImage.setVisibility(View.GONE);
            avatarFallback.setVisibility(View.VISIBLE);
            return;
        }
        try {
            avatarImage.setImageURI(Uri.parse(rawUri));
            avatarImage.setVisibility(View.VISIBLE);
            avatarFallback.setVisibility(View.GONE);
        } catch (RuntimeException e) {
            avatarImage.setVisibility(View.GONE);
            avatarFallback.setVisibility(View.VISIBLE);
        }
    }

    private static void validateMacroGrams(ProfileSettings s) {
        if (s.proteinGoalG <= 0 || s.proteinGoalG > 500) throw new IllegalArgumentException("Protein goal must be between 1 and 500 g");
        if (s.carbsGoalG < 0 || s.carbsGoalG > 1000) throw new IllegalArgumentException("Carb goal must be between 0 and 1000 g");
        if (s.fatGoalG <= 0 || s.fatGoalG > 300) throw new IllegalArgumentException("Fat goal must be between 1 and 300 g");
    }

    private double required(EditText input, String label) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("Enter " + label);
        try { return Double.parseDouble(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid " + label); }
    }

    private int intRequired(EditText input, String label) {
        double value = required(input, label);
        if (value != Math.rint(value)) throw new IllegalArgumentException("Enter " + label + " in whole years");
        return (int) value;
    }

    private double numberOr(EditText input, double fallback) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return fallback;
        try { return Double.parseDouble(raw); }
        catch (NumberFormatException e) { return fallback; }
    }

    private int intOr(EditText input, int fallback) {
        return (int) Math.round(numberOr(input, fallback));
    }

    private double nonNegativeOr(EditText input, double fallback) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return 0.0;
        try { return Math.max(0.0, Double.parseDouble(raw)); }
        catch (NumberFormatException e) { return fallback; }
    }

    private double optionalNonNegative(EditText input, String label) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty()) return 0.0;
        double value;
        try { value = Double.parseDouble(raw); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid " + label); }
        if (value < 0) throw new IllegalArgumentException(label + " cannot be negative");
        return value;
    }

    private static void setEnabledGroup(boolean enabled, EditText... inputs) {
        for (EditText input : inputs) {
            input.setEnabled(enabled);
            input.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private static double positiveOr(double value, double fallback) { return value > 0 ? value : fallback; }

    private static String optionalFormat(double value) { return value <= 0 ? "" : format(value); }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) return String.format(Locale.US, "%.0f", value);
        return String.format(Locale.US, "%.1f", value);
    }

    private static String formatSigned(double value) {
        if (Math.abs(value) < 0.0005) return "0";
        return String.format(Locale.US, "%+.2f", value);
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
