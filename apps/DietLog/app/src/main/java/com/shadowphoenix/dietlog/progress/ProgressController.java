package com.shadowphoenix.dietlog.progress;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.ui.FrostedUi;
import com.shadowphoenix.dietlog.ui.FrostedSurfaceDrawable.Material;
import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.profile.ProfileController;
import com.shadowphoenix.dietlog.profile.ProfileSettings;
import com.shadowphoenix.dietlog.progress.ProgressData.DailyNutrition;
import com.shadowphoenix.dietlog.progress.ProgressData.WeightPoint;
import com.shadowphoenix.dietlog.ui.ProgressTrendView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProgressController {
    private final Activity activity;
    private final DiaryDbHelper diary;
    private final ProfileController profile;

    private TextView currentWeight, weightGoal, weightSource, weightEmpty, weightPace;
    private TextView avg7Calories, avg30Calories, adherence, loggedDays;
    private TextView caloriesSummary, proteinAvg, carbsAvg, fatAvg, nutritionLowData;
    private TextView calorieAdherence, proteinAdherence, range7, range30;
    private TextView caloriesChartLabel, macroChartLabel, macroLegend;
    private ProgressTrendView weightTrend, caloriesTrend, macroTrend;
    private int rangeDays = 7;

    public ProgressController(Activity activity, DiaryDbHelper diary, ProfileController profile) {
        this.activity = activity;
        this.diary = diary;
        this.profile = profile;
    }

    public void bind() {
        currentWeight = activity.findViewById(R.id.progressCurrentWeight);
        weightGoal = activity.findViewById(R.id.progressWeightGoal);
        weightSource = activity.findViewById(R.id.progressWeightSource);
        weightEmpty = activity.findViewById(R.id.progressWeightEmpty);
        weightPace = activity.findViewById(R.id.progressWeightPace);
        avg7Calories = activity.findViewById(R.id.progressAvg7Calories);
        avg30Calories = activity.findViewById(R.id.progressAvg30Calories);
        adherence = activity.findViewById(R.id.progressAdherence);
        loggedDays = activity.findViewById(R.id.progressLoggedDays);
        caloriesSummary = activity.findViewById(R.id.progressCaloriesSummary);
        nutritionLowData = activity.findViewById(R.id.progressNutritionLowData);
        caloriesChartLabel = activity.findViewById(R.id.progressCaloriesChartLabel);
        macroChartLabel = activity.findViewById(R.id.progressMacroChartLabel);
        macroLegend = activity.findViewById(R.id.progressMacroLegend);
        proteinAvg = activity.findViewById(R.id.progressProteinAvg);
        carbsAvg = activity.findViewById(R.id.progressCarbsAvg);
        fatAvg = activity.findViewById(R.id.progressFatAvg);
        calorieAdherence = activity.findViewById(R.id.progressCalorieAdherence);
        proteinAdherence = activity.findViewById(R.id.progressProteinAdherence);
        range7 = activity.findViewById(R.id.progressRange7);
        range30 = activity.findViewById(R.id.progressRange30);
        weightTrend = activity.findViewById(R.id.progressWeightTrend);
        caloriesTrend = activity.findViewById(R.id.progressCaloriesTrend);
        macroTrend = activity.findViewById(R.id.progressMacroTrend);

        activity.findViewById(R.id.progressLogWeight).setOnClickListener(v -> showWeightDialog());
        range7.setOnClickListener(v -> setRange(7));
        range30.setOnClickListener(v -> setRange(30));
        refresh();
    }

    public void refresh() {
        ProfileSettings settings = profile.getSettings();
        double calorieGoal = profile.getCalorieGoal();
        double proteinGoal = profile.getProteinGoal();
        double carbsGoal = profile.getCarbsGoal();
        double fatGoal = profile.getFatGoal();
        LocalDate today = LocalDate.now();

        List<DailyNutrition> days7 = diary.getNutritionRange(today.minusDays(6), today);
        List<DailyNutrition> days30 = diary.getNutritionRange(today.minusDays(29), today);
        ProgressStats.NutritionSummary summary7 =
                ProgressStats.summarize(days7, calorieGoal, proteinGoal);
        ProgressStats.NutritionSummary summary30 =
                ProgressStats.summarize(days30, calorieGoal, proteinGoal);
        ProgressStats.NutritionSummary adherence30 =
                ProgressStats.summarizeCompletedDays(days30, today, calorieGoal, proteinGoal);
        boolean todayInProgress = hasLoggedNutritionOn(days30, today);

        avg7Calories.setText(summary7.loggedDays == 0 ? "—" :
                String.format(Locale.getDefault(), "%.0f", summary7.averageCalories));
        avg30Calories.setText(summary30.loggedDays == 0 ? "—" :
                String.format(Locale.getDefault(), "%.0f", summary30.averageCalories));
        adherence.setText(adherence30.loggedDays == 0 ? "—" :
                String.format(Locale.getDefault(), "%.0f%%", adherence30.combinedAdherencePercent));
        if (adherence30.loggedDays == 0) {
            loggedDays.setText(todayInProgress ? "Today in progress" : "No completed days");
            calorieAdherence.setText("Calories —");
            proteinAdherence.setText("Protein —");
        } else {
            loggedDays.setText(adherence30.loggedDays
                    + (adherence30.loggedDays == 1 ? " completed day" : " completed days"));
            calorieAdherence.setText(String.format(Locale.getDefault(),
                    "Calories on target: %.0f%%", adherence30.calorieAdherencePercent));
            proteinAdherence.setText(String.format(Locale.getDefault(),
                    "Protein on target: %.0f%%", adherence30.proteinAdherencePercent));
        }

        renderNutritionCharts(calorieGoal, proteinGoal, carbsGoal, fatGoal);
        renderWeight(settings);
    }

    private boolean hasLoggedNutritionOn(List<DailyNutrition> days, LocalDate date) {
        for (DailyNutrition day : days) {
            if (day.entryCount > 0 && date.equals(day.date)) return true;
        }
        return false;
    }

    private void setRange(int days) {
        rangeDays = days;
        boolean seven = days == 7;
        FrostedUi.surface(range7, seven ? Material.ACTION : Material.CONTROL);
        range7.setTextColor(activity.getColor(seven ? R.color.black : R.color.text_secondary));
        FrostedUi.surface(range30, seven ? Material.CONTROL : Material.ACTION);
        range30.setTextColor(activity.getColor(seven ? R.color.text_secondary : R.color.black));
        refresh();
    }

    private void renderNutritionCharts(double calorieGoal, double proteinGoal,
                                       double carbsGoal, double fatGoal) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(rangeDays - 1L);
        List<DailyNutrition> raw = diary.getNutritionRange(start, today);
        ProgressStats.NutritionSummary selected =
                ProgressStats.summarize(raw, calorieGoal, proteinGoal);

        caloriesSummary.setText(selected.loggedDays == 0
                ? "No logged nutrition days in this range."
                : String.format(Locale.getDefault(),
                "%d logged %s • average %.0f kcal/day • target %.0f kcal",
                selected.loggedDays, selected.loggedDays == 1 ? "day" : "days",
                selected.averageCalories, calorieGoal));
        proteinAvg.setText(selected.loggedDays == 0 ? "P —" :
                String.format(Locale.getDefault(), "P %.0fg", selected.averageProtein));
        carbsAvg.setText(selected.loggedDays == 0 ? "C —" :
                String.format(Locale.getDefault(), "C %.0fg", selected.averageCarbs));
        fatAvg.setText(selected.loggedDays == 0 ? "F —" :
                String.format(Locale.getDefault(), "F %.0fg", selected.averageFat));

        boolean enoughTrendData = selected.loggedDays >= 3;
        int chartVisibility = enoughTrendData ? View.VISIBLE : View.GONE;
        caloriesChartLabel.setVisibility(chartVisibility);
        caloriesTrend.setVisibility(chartVisibility);
        macroChartLabel.setVisibility(chartVisibility);
        macroLegend.setVisibility(chartVisibility);
        macroTrend.setVisibility(chartVisibility);
        nutritionLowData.setVisibility(enoughTrendData ? View.GONE : View.VISIBLE);
        if (!enoughTrendData) {
            if (selected.loggedDays == 0) {
                nutritionLowData.setText("Log food on at least 3 days to start seeing nutrition trends.");
            } else {
                nutritionLowData.setText(String.format(Locale.getDefault(),
                        "%d of 3 logged days • keep logging to unlock meaningful trend charts.",
                        selected.loggedDays));
            }
            return;
        }

        Map<LocalDate, DailyNutrition> byDate = new HashMap<>();
        for (DailyNutrition day : raw) byDate.put(day.date, day);

        String[] labels = new String[rangeDays];
        double[] calories = new double[rangeDays];
        double[] proteinPct = new double[rangeDays];
        double[] carbsPct = new double[rangeDays];
        double[] fatPct = new double[rangeDays];

        DateTimeFormatter formatter = rangeDays == 7
                ? DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
                : DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());

        for (int i = 0; i < rangeDays; i++) {
            LocalDate date = start.plusDays(i);
            labels[i] = date.format(formatter);
            DailyNutrition day = byDate.get(date);
            if (day == null || day.entryCount <= 0) {
                calories[i] = Double.NaN;
                proteinPct[i] = Double.NaN;
                carbsPct[i] = Double.NaN;
                fatPct[i] = Double.NaN;
            } else {
                calories[i] = day.calories;
                proteinPct[i] = proteinGoal > 0 ? day.protein / proteinGoal * 100.0 : Double.NaN;
                carbsPct[i] = carbsGoal > 0 ? day.carbs / carbsGoal * 100.0 : Double.NaN;
                fatPct[i] = fatGoal > 0 ? day.fat / fatGoal * 100.0 : Double.NaN;
            }
        }

        caloriesTrend.setData(labels, new double[][]{calories},
                new int[]{activity.getColor(R.color.calories)},
                calorieGoal, true, "kcal");
        macroTrend.setData(labels, new double[][]{proteinPct, carbsPct, fatPct},
                new int[]{
                        activity.getColor(R.color.protein),
                        activity.getColor(R.color.carbs),
                        activity.getColor(R.color.fat)
                },
                100.0, true, "%");
    }

    private void renderWeight(ProfileSettings settings) {
        LocalDate today = LocalDate.now();
        List<WeightPoint> points = diary.getWeightRange(today.minusDays(89), today);
        WeightPoint latest = diary.getLatestWeight();
        double displayWeight = latest == null ? settings.currentWeightKg : latest.weightKg;

        currentWeight.setText(String.format(Locale.getDefault(), "%.1f", displayWeight));
        weightGoal.setText(String.format(Locale.getDefault(), "Target %.1f kg • %s",
                settings.targetWeightKg, settings.goal.label));
        if (latest == null) {
            weightSource.setText("Profile weight • no progress weight logged yet");
        } else {
            weightSource.setText("Latest log • " + latest.date.format(
                    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())));
        }

        boolean enoughTrendData = points.size() >= 2;
        weightTrend.setVisibility(enoughTrendData ? View.VISIBLE : View.GONE);
        weightEmpty.setVisibility(enoughTrendData ? View.GONE : View.VISIBLE);
        if (!enoughTrendData) {
            if (points.isEmpty() && latest == null) {
                weightEmpty.setText("Log your weight to start tracking change. Two logs on different days unlock the trend.");
            } else if (points.isEmpty()) {
                weightEmpty.setText("Your last weight log is older than 90 days • log again to restart the recent trend.");
            } else {
                weightEmpty.setText("1 recent weight log saved • add another on a different day to unlock your trend and pace.");
            }
        } else {
            String[] labels = new String[points.size()];
            double[] weights = new double[points.size()];
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
            for (int i = 0; i < points.size(); i++) {
                labels[i] = points.get(i).date.format(formatter);
                weights[i] = points.get(i).weightKg;
            }
            weightTrend.setData(labels, new double[][]{weights},
                    new int[]{activity.getColor(R.color.accent)},
                    settings.targetWeightKg, false, "kg");
        }

        List<WeightPoint> recent = diary.getWeightRange(today.minusDays(28), today);
        Double actualRate = ProgressStats.weeklyWeightRate(recent);
        double targetRate = settings.weeklyWeightChangeKg;
        if (Math.abs(targetRate) < 0.01 && settings.goal.direction != 0) {
            targetRate = settings.goal.direction *
                    settings.currentWeightKg *
                    settings.goal.defaultBodyWeightFractionPerWeek;
        }
        if (actualRate == null) {
            weightPace.setVisibility(View.GONE);
        } else {
            weightPace.setVisibility(View.VISIBLE);
            String actual = String.format(Locale.getDefault(), "Actual %+.2f kg/week", actualRate);
            String target = String.format(Locale.getDefault(), "Goal %+.2f kg/week", targetRate);
            weightPace.setText(actual + "  •  " + target + System.lineSeparator() +
                    ProgressStats.paceLabel(actualRate, targetRate));
        }
    }

    private void showWeightDialog() {
        LocalDate today = LocalDate.now();
        LocalDate[] selected = {today};
        ProfileSettings settings = profile.getSettings();
        ProgressData.WeightPoint latest = diary.getLatestWeight();
        double initialWeight = latest == null ? settings.currentWeightKg : latest.weightKg;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(4), dp(22), 0);

        TextView date = new TextView(activity);
        date.setTextColor(activity.getColor(R.color.accent));
        date.setTextSize(13f);
        date.setGravity(android.view.Gravity.CENTER_VERTICAL);
        date.setPadding(dp(14), 0, dp(14), 0);
        date.setBackgroundResource(R.drawable.bg_chip);
        updateWeightDateText(date, selected[0]);
        content.addView(date, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        EditText weight = new EditText(activity);
        weight.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        weight.setSingleLine(true);
        weight.setText(String.format(Locale.US, "%.1f", initialWeight));
        weight.setSelectAllOnFocus(true);
        weight.setHint("Weight in kg");
        weight.setTextColor(activity.getColor(R.color.text_primary));
        weight.setHintTextColor(activity.getColor(R.color.text_tertiary));
        weight.setBackgroundResource(R.drawable.bg_input);
        weight.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        inputParams.topMargin = dp(10);
        content.addView(weight, inputParams);

        date.setOnClickListener(v -> {
            DatePickerDialog picker = new DatePickerDialog(activity,
                    (view, year, month, dayOfMonth) -> {
                        selected[0] = LocalDate.of(year, month + 1, dayOfMonth);
                        updateWeightDateText(date, selected[0]);
                    },
                    selected[0].getYear(), selected[0].getMonthValue() - 1,
                    selected[0].getDayOfMonth());
            picker.getDatePicker().setMaxDate(System.currentTimeMillis());
            picker.show();
        });

        new AlertDialog.Builder(activity)
                .setTitle("Log weight")
                .setMessage("Logging another weight for the same date updates that day.")
                .setView(content)
                .setPositiveButton("Save", (dialog, which) -> {
                    try {
                        double kg = Double.parseDouble(weight.getText().toString().trim());
                        if (!Double.isFinite(kg) || kg < 25 || kg > 350) {
                            throw new IllegalArgumentException();
                        }
                        diary.upsertWeight(selected[0], kg);
                        if (selected[0].equals(LocalDate.now())) {
                            profile.updateCurrentWeightFromProgress(kg);
                        }
                        refresh();
                        Toast.makeText(activity, "Weight saved", Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException e) {
                        Toast.makeText(activity, "Enter a valid weight between 25 and 350 kg",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateWeightDateText(TextView view, LocalDate date) {
        view.setText("Date  •  " +
                date.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
