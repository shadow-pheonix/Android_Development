package com.shadowphoenix.dietlog;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.data.FoodEntry;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.diagnostics.DiagnosticsExporter;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private DiaryDbHelper db;
    private EditText nameInput;
    private EditText gramsInput;
    private EditText caloriesInput;
    private EditText proteinInput;
    private EditText carbsInput;
    private EditText fatInput;
    private TextView summaryText;
    private LinearLayout entriesContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.init(this);
        CrashHandler.install(this);
        AppLogger.i("MainActivity created");

        setContentView(R.layout.activity_main);
        db = new DiaryDbHelper(this);

        nameInput = findViewById(R.id.nameInput);
        gramsInput = findViewById(R.id.gramsInput);
        caloriesInput = findViewById(R.id.caloriesInput);
        proteinInput = findViewById(R.id.proteinInput);
        carbsInput = findViewById(R.id.carbsInput);
        fatInput = findViewById(R.id.fatInput);
        summaryText = findViewById(R.id.summaryText);
        entriesContainer = findViewById(R.id.entriesContainer);

        TextView dateText = findViewById(R.id.dateText);
        dateText.setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));

        Button addButton = findViewById(R.id.addButton);
        addButton.setOnClickListener(v -> addFood());

        Button exportButton = findViewById(R.id.exportButton);
        exportButton.setOnClickListener(v -> exportDiagnostics());

        refresh();
    }

    private void addFood() {
        try {
            String name = nameInput.getText().toString().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Enter a food name");

            double grams = positive(gramsInput, "amount");
            double calories = nonNegative(caloriesInput, "calories");
            double protein = nonNegative(proteinInput, "protein");
            double carbs = nonNegative(carbsInput, "carbs");
            double fat = nonNegative(fatInput, "fat");

            FoodEntry entry = FoodEntry.fromPer100g(name, grams, calories, protein, carbs, fat,
                    System.currentTimeMillis());
            long id = db.insert(entry);
            AppLogger.i("Food entry added id=" + id + " source=manual");
            clearInputs();
            refresh();
            Toast.makeText(this, "Food added", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLogger.e("Failed to add food", e);
            Toast.makeText(this, "Unable to add food", Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        double[] totals = db.getTodayTotals();
        summaryText.setText(String.format(Locale.getDefault(),
                "%.0f kcal\nProtein %.1f g   •   Carbs %.1f g   •   Fat %.1f g",
                totals[0], totals[1], totals[2], totals[3]));

        List<FoodEntry> entries = db.getTodayEntries();
        entriesContainer.removeAllViews();
        if (entries.isEmpty()) {
            TextView empty = makeEntryView(getString(R.string.empty_entries), false);
            entriesContainer.addView(empty);
            return;
        }
        for (FoodEntry entry : entries) {
            String line = String.format(Locale.getDefault(),
                    "%s — %.0f g\n%.0f kcal  •  P %.1f  C %.1f  F %.1f g",
                    entry.name, entry.grams, entry.calories, entry.protein, entry.carbs, entry.fat);
            entriesContainer.addView(makeEntryView(line, true));
        }
    }

    private TextView makeEntryView(String text, boolean prominent) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(prominent ? 16f : 14f);
        view.setPadding(0, dp(10), 0, dp(10));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void exportDiagnostics() {
        try {
            AppLogger.i("Diagnostics export requested");
            DiagnosticsExporter.ExportResult result = DiagnosticsExporter.export(this, db);
            Toast.makeText(this, "Saved: " + result.displayPath, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLogger.e("Diagnostics export failed", e);
            Toast.makeText(this, "Diagnostics export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private void clearInputs() {
        nameInput.setText("");
        gramsInput.setText("");
        caloriesInput.setText("");
        proteinInput.setText("");
        carbsInput.setText("");
        fatInput.setText("");
        nameInput.requestFocus();
    }
}
