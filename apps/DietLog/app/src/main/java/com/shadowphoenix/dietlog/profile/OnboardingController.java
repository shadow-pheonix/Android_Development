package com.shadowphoenix.dietlog.profile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.ui.CalendarDialogs;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Four short steps; nothing is persisted until the user confirms the preview. */
public final class OnboardingController {
    private final Activity activity;
    private final ProfilePreferences preferences;
    private final Runnable onComplete;
    private final View[] panels;
    private final EditText name, age, height, weight, target;
    private final Spinner sex, goal, level;
    private final TextView dob, title, subtitle, stepText, next, error;
    private int step;
    private String birthIso = "";
    private boolean complete;

    public OnboardingController(Activity activity, Bundle restored, Runnable onComplete, Runnable exit) {
        this.activity = activity;
        this.onComplete = onComplete;
        preferences = new ProfilePreferences(activity);
        name = activity.findViewById(R.id.setupName);
        age = activity.findViewById(R.id.setupAge);
        height = activity.findViewById(R.id.setupHeight);
        weight = activity.findViewById(R.id.setupWeight);
        target = activity.findViewById(R.id.setupTarget);
        sex = activity.findViewById(R.id.setupSex);
        goal = activity.findViewById(R.id.setupGoal);
        level = activity.findViewById(R.id.setupActivity);
        dob = activity.findViewById(R.id.setupDob);
        title = activity.findViewById(R.id.setupTitle);
        subtitle = activity.findViewById(R.id.setupSubtitle);
        stepText = activity.findViewById(R.id.setupStep);
        next = activity.findViewById(R.id.setupNext);
        error = activity.findViewById(R.id.setupError);
        panels = new View[]{activity.findViewById(R.id.setupBasics), activity.findViewById(R.id.setupBody),
                activity.findViewById(R.id.setupPlan), activity.findViewById(R.id.setupReview)};
        bindOptions(sex, "Select sex", ProfileSettings.Sex.values());
        bindOptions(goal, "Select a goal", ProfileSettings.Goal.values());
        bindOptions(level, "Select activity level", ProfileSettings.ActivityLevel.values());
        if (restored != null) restore(restored);
        else if (preferences.hasSavedProfile()) populate(preferences.load());
        updateBirthDate();
        dob.setOnClickListener(v -> chooseBirthDate());
        age.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!birthIso.isEmpty() && !s.toString().equals(String.valueOf(
                        Period.between(LocalDate.parse(birthIso), LocalDate.now()).getYears()))) {
                    birthIso = "";
                    updateBirthDate();
                }
            }
        });
        AdapterView.OnItemSelectedListener selections = new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updatePlan(); }
        };
        goal.setOnItemSelectedListener(selections);
        level.setOnItemSelectedListener(selections);
        next.setOnClickListener(v -> advance());
        activity.findViewById(R.id.setupBack).setOnClickListener(v -> { if (!goBack()) exit.run(); });
        updatePlan();
        render();
    }

    private <T> void bindOptions(Spinner spinner, String prompt, T[] options) {
        String[] labels = new String[options.length + 1];
        labels[0] = prompt;
        for (int i = 0; i < options.length; i++) labels[i + 1] = options[i].toString();
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, R.layout.item_dropdown_value, labels) {
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                CheckedTextView row = (CheckedTextView) super.getDropDownView(position, convertView, parent);
                row.setChecked(position == spinner.getSelectedItemPosition());
                return row;
            }
        };
        adapter.setDropDownViewResource(R.layout.item_dropdown_option);
        spinner.setAdapter(adapter);
    }

    private void advance() {
        if (complete) return;
        try {
            ProfileSettings s = readDraft(step);
            if (step == 0) ProfileSetup.validateBasics(s, LocalDate.now());
            else if (step == 1) ProfileSetup.validateMeasurements(s);
            else ProfileSetup.validatePersonal(s, LocalDate.now());
            hideKeyboard();
            if (step < 3) {
                step++;
                render();
            } else {
                applyTargets(s);
                preferences.savePersonalProfile(s);
                complete = true;
                AppLogger.i("First-run profile setup completed");
                onComplete.run();
            }
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage());
            error.setVisibility(View.VISIBLE);
            ((ScrollView) activity.findViewById(R.id.setupScroll)).post(() ->
                    ((ScrollView) activity.findViewById(R.id.setupScroll)).smoothScrollTo(0, error.getBottom()));
        }
    }

    public boolean goBack() {
        if (step == 0) return false;
        hideKeyboard();
        step--;
        render();
        return true;
    }

    private ProfileSettings readDraft(int throughStep) {
        ProfileSettings s = preferences.load();
        s.displayName = name.getText().toString().trim();
        s.dateOfBirthIso = birthIso;
        if (birthIso.isEmpty()) {
            double years = number(age, "Age");
            if (years != Math.rint(years)) throw new IllegalArgumentException("Enter age in whole years");
            s.ageYears = (int) years;
        } else s.ageYears = Period.between(LocalDate.parse(birthIso), LocalDate.now()).getYears();
        s.sex = selected(sex, ProfileSettings.Sex.values(), "sex");
        if (throughStep >= 1) {
            s.heightCm = number(height, "Height");
            s.currentWeightKg = number(weight, "Current weight");
        }
        if (throughStep >= 2) {
            s.goal = selected(goal, ProfileSettings.Goal.values(), "a goal");
            s.activityLevel = selected(level, ProfileSettings.ActivityLevel.values(), "activity level");
            s.targetWeightKg = s.goal.isWeightStable() ? s.currentWeightKg : number(target, "Target weight");
            if (s.goal.direction < 0 && s.targetWeightKg >= s.currentWeightKg)
                throw new IllegalArgumentException("Choose a target below your current weight for this goal");
            if (s.goal.direction > 0 && s.targetWeightKg <= s.currentWeightKg)
                throw new IllegalArgumentException("Choose a target above your current weight for this goal");
            s.weeklyWeightChangeKg = CalorieCalculator.recommendedWeeklyChange(s);
            s.manualCalorieOverride = false;
        }
        return s;
    }

    private static double number(EditText input, String label) {
        try {
            double value = Double.parseDouble(input.getText().toString().trim().replace(',', '.'));
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Enter " + label.toLowerCase(Locale.ROOT));
    }

    private static <T> T selected(Spinner spinner, T[] values, String label) {
        int index = spinner.getSelectedItemPosition() - 1;
        if (index < 0 || index >= values.length) throw new IllegalArgumentException("Select " + label);
        return values[index];
    }

    private void applyTargets(ProfileSettings s) {
        double calories = CalorieCalculator.calculate(s, LocalDate.now()).calorieTarget;
        NutritionGoalCalculator.Goals macros = NutritionGoalCalculator.recommend(s, calories);
        s.macroMode = ProfileSettings.MacroMode.GRAMS;
        s.proteinGoalG = macros.proteinG;
        s.carbsGoalG = macros.carbsG;
        s.fatGoalG = macros.fatG;
        s.fibreGoalG = macros.fibreG;
        s.sugarLimitG = macros.sugarLimitG;
        s.sodiumLimitMg = macros.sodiumLimitMg;
        s.proteinPercent = s.proteinGoalG * 4 / calories * 100;
        s.carbsPercent = s.carbsGoalG * 4 / calories * 100;
        s.fatPercent = s.fatGoalG * 9 / calories * 100;
    }

    private void render() {
        String[] titles = {"Let’s make it yours", "Your starting point", "What’s your goal?", "Your plan is ready"};
        String[] subtitles = {"A few details help us build your daily nutrition targets.",
                "Tell us a little about your body.", "Build a plan around your everyday routine.",
                "Review your starting targets, then make your first food log."};
        title.setText(titles[step]);
        subtitle.setText(subtitles[step]);
        stepText.setText(String.format(Locale.getDefault(), "Step %d of 4", step + 1));
        ((ProgressBar) activity.findViewById(R.id.setupProgress)).setProgress(step + 1);
        next.setText(step == 3 ? "Start logging" : "Continue");
        activity.findViewById(R.id.setupBack).setContentDescription(step == 0 ? "Leave setup" : "Previous setup step");
        for (int i = 0; i < panels.length; i++) panels[i].setVisibility(i == step ? View.VISIBLE : View.GONE);
        error.setVisibility(View.GONE);
        if (step == 3) {
            try {
                ProfileSettings s = readDraft(3);
                ProfileSetup.validatePersonal(s, LocalDate.now());
                applyTargets(s);
                double calories = CalorieCalculator.calculate(s, LocalDate.now()).calorieTarget;
                ((TextView) activity.findViewById(R.id.setupCalories)).setText(String.format(Locale.getDefault(), "%,.0f kcal / day", calories));
                ((TextView) activity.findViewById(R.id.setupMacros)).setText(String.format(Locale.getDefault(),
                        "Protein %.0f g  ·  Carbs %.0f g  ·  Fat %.0f g", s.proteinGoalG, s.carbsGoalG, s.fatGoalG));
                ((TextView) activity.findViewById(R.id.setupSummary)).setText(String.format(Locale.getDefault(),
                        "%s · %s\n%.1f kg → %.1f kg", s.goal, s.activityLevel, s.currentWeightKg, s.targetWeightKg));
            } catch (IllegalArgumentException e) {
                step = 0; render(); return;
            }
        }
        ((ScrollView) activity.findViewById(R.id.setupScroll)).scrollTo(0, 0);
        title.announceForAccessibility(title.getText());
    }

    private void updatePlan() {
        int goalIndex = goal.getSelectedItemPosition() - 1;
        boolean stable = goalIndex >= 0 && ProfileSettings.Goal.values()[goalIndex].isWeightStable();
        activity.findViewById(R.id.setupTargetGroup).setVisibility(stable ? View.GONE : View.VISIBLE);
        ((TextView) activity.findViewById(R.id.setupGoalNote)).setText(goalIndex == ProfileSettings.Goal.RECOMPOSITION.ordinal()
                ? "Build muscle and lose fat while keeping weight roughly stable. Start near maintenance calories with higher protein; resistance training supports this goal." : "");
        int index = level.getSelectedItemPosition() - 1;
        ((TextView) activity.findViewById(R.id.setupActivityNote)).setText(index >= 0
                ? ProfileSettings.ActivityLevel.values()[index].description : "Choose the level that best fits your usual week.");
    }

    private void chooseBirthDate() {
        hideKeyboard();
        LocalDate initial = LocalDate.now().minusYears(25);
        try { if (!birthIso.isEmpty()) initial = LocalDate.parse(birthIso); } catch (RuntimeException ignored) {}
        DatePickerDialog picker = new DatePickerDialog(activity, R.style.DiaryDatePickerDialog,
                (v, year, month, day) -> {
                    birthIso = LocalDate.of(year, month + 1, day).toString();
                    age.setText(String.valueOf(Period.between(LocalDate.parse(birthIso), LocalDate.now()).getYears()));
                    updateBirthDate();
                }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        CalendarDialogs.show(picker);
    }

    private void updateBirthDate() {
        try {
            dob.setText(birthIso.isEmpty() ? "Choose date of birth" : LocalDate.parse(birthIso)
                    .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())));
        } catch (RuntimeException ignored) { birthIso = ""; dob.setText("Choose date of birth"); }
    }

    private void hideKeyboard() {
        View focused = activity.getCurrentFocus();
        if (focused == null) return;
        InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    private void populate(ProfileSettings s) {
        name.setText(s.displayName); age.setText(String.valueOf(s.ageYears));
        height.setText(String.valueOf(s.heightCm)); weight.setText(String.valueOf(s.currentWeightKg));
        target.setText(String.valueOf(s.targetWeightKg)); birthIso = s.dateOfBirthIso;
        sex.setSelection(s.sex.ordinal() + 1); goal.setSelection(s.goal.ordinal() + 1);
        level.setSelection(s.activityLevel.ordinal() + 1);
    }

    public Bundle saveState() {
        Bundle state = new Bundle();
        state.putInt("step", step); state.putString("birth", birthIso);
        EditText[] inputs = {name, age, height, weight, target};
        for (int i = 0; i < inputs.length; i++) state.putString("input" + i, inputs[i].getText().toString());
        state.putInt("sex", sex.getSelectedItemPosition());
        int goalIndex = goal.getSelectedItemPosition() - 1;
        state.putString("goal_name", goalIndex >= 0 ? ProfileSettings.Goal.values()[goalIndex].name() : "");
        state.putInt("level", level.getSelectedItemPosition());
        return state;
    }

    private void restore(Bundle state) {
        step = Math.max(0, Math.min(3, state.getInt("step")));
        birthIso = state.getString("birth", "");
        EditText[] inputs = {name, age, height, weight, target};
        for (int i = 0; i < inputs.length; i++) inputs[i].setText(state.getString("input" + i, ""));
        sex.setSelection(state.getInt("sex"));
        String savedGoal = state.getString("goal_name", null);
        if (savedGoal == null) {
            String[] legacy = {"", "LOSE_WEIGHT", "MAINTAIN", "GAIN_WEIGHT", "LEAN_BULK",
                    "DIRTY_BULK", "CUT", "CUSTOM", "RECOMPOSITION"};
            int oldIndex = state.getInt("goal");
            savedGoal = oldIndex >= 0 && oldIndex < legacy.length ? legacy[oldIndex] : "";
        }
        try { goal.setSelection(ProfileSettings.Goal.valueOf(savedGoal).ordinal() + 1); }
        catch (IllegalArgumentException ignored) { goal.setSelection(0); }
        level.setSelection(state.getInt("level"));
    }
}
