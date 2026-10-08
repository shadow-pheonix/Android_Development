package com.shadowphoenix.dietlog.profile;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import com.shadowphoenix.dietlog.R;
import java.util.Locale;

/** Scoped bindings keep setup and edit-profile drafts separate despite shared layout IDs. */
public final class OptionalTargetDetails {
    private final EditText bodyFat, rmr, maintenance;
    private final Spinner training;
    private final TextView toggle;
    private final View panel;
    private boolean suppress;

    public OptionalTargetDetails(View root, Runnable onChanged) {
        bodyFat = root.findViewById(R.id.optionalBodyFatInput);
        rmr = root.findViewById(R.id.optionalRmrInput);
        maintenance = root.findViewById(R.id.optionalMaintenanceInput);
        training = root.findViewById(R.id.optionalTrainingSpinner);
        toggle = root.findViewById(R.id.optionalTargetsToggle);
        panel = root.findViewById(R.id.optionalTargetsPanel);
        ArrayAdapter<ProfileSettings.TrainingFocus> adapter = new ArrayAdapter<ProfileSettings.TrainingFocus>(
                root.getContext(), R.layout.item_dropdown_value, ProfileSettings.TrainingFocus.values()) {
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                CheckedTextView row = (CheckedTextView) super.getDropDownView(position, convertView, parent);
                row.setChecked(position == training.getSelectedItemPosition());
                row.setTypeface(null, row.isChecked() ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                return row;
            }
        };
        adapter.setDropDownViewResource(R.layout.item_dropdown_option);
        training.setAdapter(adapter);
        training.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!suppress) onChanged.run();
            }
        });
        toggle.setOnClickListener(v -> setExpanded(panel.getVisibility() != View.VISIBLE));
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!suppress) onChanged.run();
            }
        };
        for (EditText input : new EditText[]{bodyFat, rmr, maintenance}) input.addTextChangedListener(watcher);
    }

    public void readInto(ProfileSettings s) {
        s.bodyFatPercent = number(bodyFat);
        s.measuredRmrKcal = number(rmr);
        s.observedMaintenanceKcal = number(maintenance);
        s.trainingFocus = (ProfileSettings.TrainingFocus) training.getSelectedItem();
    }

    public void populate(ProfileSettings s) {
        suppress = true;
        bodyFat.setText(optional(s.bodyFatPercent));
        rmr.setText(optional(s.measuredRmrKcal));
        maintenance.setText(optional(s.observedMaintenanceKcal));
        training.setSelection(s.trainingFocus.ordinal());
        setExpanded(s.bodyFatPercent > 0 || s.measuredRmrKcal > 0 || s.observedMaintenanceKcal > 0
                || s.trainingFocus != ProfileSettings.TrainingFocus.UNSPECIFIED);
        suppress = false;
    }

    public Bundle saveState() {
        Bundle state = new Bundle();
        state.putString("bodyFat", bodyFat.getText().toString());
        state.putString("rmr", rmr.getText().toString());
        state.putString("maintenance", maintenance.getText().toString());
        ProfileSettings.TrainingFocus focus = (ProfileSettings.TrainingFocus) training.getSelectedItem();
        state.putString("training", focus == null ? ProfileSettings.TrainingFocus.UNSPECIFIED.name() : focus.name());
        state.putBoolean("expanded", panel.getVisibility() == View.VISIBLE);
        return state;
    }

    public void restoreState(Bundle state) {
        if (state == null) return;
        suppress = true;
        bodyFat.setText(state.getString("bodyFat", "")); rmr.setText(state.getString("rmr", ""));
        maintenance.setText(state.getString("maintenance", ""));
        try { training.setSelection(ProfileSettings.TrainingFocus.valueOf(state.getString("training", "UNSPECIFIED")).ordinal()); }
        catch (IllegalArgumentException ignored) { training.setSelection(0); }
        setExpanded(state.getBoolean("expanded"));
        suppress = false;
    }

    private void setExpanded(boolean expanded) {
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        toggle.setText(expanded ? "Hide optional details" : "Add optional details");
        toggle.setContentDescription(expanded ? "Optional details, expanded. Tap to collapse" : "Optional details, collapsed. Tap to expand");
    }

    private static double number(EditText input) {
        String raw = input.getText().toString().trim().replace(',', '.');
        if (raw.isEmpty()) return 0;
        try { return Double.parseDouble(raw); }
        catch (NumberFormatException ignored) { return Double.NaN; } // Validation reports the field; never reuse an old value.
    }

    private static String optional(double value) {
        if (value <= 0) return "";
        return value == Math.rint(value) ? String.format(Locale.US, "%.0f", value) : Double.toString(value);
    }
}
