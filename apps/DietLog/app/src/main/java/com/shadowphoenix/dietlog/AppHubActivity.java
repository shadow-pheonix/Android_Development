package com.shadowphoenix.dietlog;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.discipline.DisciplineActivity;
import com.shadowphoenix.dietlog.discipline.DisciplineUi;
import com.shadowphoenix.dietlog.discipline.ReminderScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The two connected sections share this installation and the existing local data. */
public final class AppHubActivity extends Activity {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        AppLogger.init(this);
        CrashHandler.install(this);
        LinearLayout body = DisciplineUi.page(this, R.drawable.discipline_hub_background);
        DisciplineUi.add(
                body,
                DisciplineUi.text(this, "YOUR EVERYDAY SPACE", 12, DisciplineUi.MUTED, true),
                20);
        DisciplineUi.add(
                body,
                DisciplineUi.text(this, "Choose your focus", 30, DisciplineUi.TEXT, true),
                10);
        DisciplineUi.add(
                body,
                DisciplineUi.text(
                        this,
                        "Two connected sections. One place for your day.",
                        16,
                        DisciplineUi.MUTED,
                        false),
                10);
        addModule(
                body,
                "FuelNote",
                "NOURISH YOUR DAY",
                "Food diary, nutrition targets and meal planning.",
                0xFF63F5D1,
                MainActivity.class);
        addModule(
                body,
                "Discipline",
                "BUILD YOUR ROUTINE",
                "Food, supplements, skin, hair and everyday reminders. Small steps, on your"
                    + " schedule.",
                DisciplineUi.BLUE,
                DisciplineActivity.class);
        LinearLayout connected = DisciplineUi.card(this);
        DisciplineUi.add(
                connected,
                DisciplineUi.text(this, "Connected by design", 15, DisciplineUi.TEXT, true),
                0);
        DisciplineUi.add(
                connected,
                DisciplineUi.text(
                        this,
                        "Your profile, food diary and reminders stay together. Food reminders can"
                                + " show your FuelNote progress.",
                        14,
                        DisciplineUi.MUTED,
                        false),
                6);
        DisciplineUi.add(body, connected, 24);
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.shadowphoenix.dietlog.updates.UpdateManager.get(this).check(false);
        android.content.Context app = getApplicationContext();
        IO.execute(
                () -> {
                    try {
                        ReminderScheduler.rescheduleAll(app);
                    } catch (Exception e) {
                        AppLogger.e("App chooser reminder scheduling failed", e);
                    }
                });
    }

    private void addModule(
            LinearLayout body,
            String name,
            String eyebrow,
            String description,
            int accent,
            Class<?> target) {
        LinearLayout card = DisciplineUi.card(this);
        DisciplineUi.clickable(card, accent, true);
        card.setContentDescription(name + ". " + description + " Open " + name);
        // The card is one accessible, keyboard-focusable navigation target.
        card.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        card.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        TextView label = DisciplineUi.text(this, eyebrow, 11, accent, true);
        TextView title = DisciplineUi.text(this, name, 29, DisciplineUi.TEXT, true);
        TextView detail = DisciplineUi.text(this, description, 15, DisciplineUi.MUTED, false);
        TextView open = DisciplineUi.text(this, "Open " + name + "  →", 15, accent, true);
        for (TextView child : new TextView[] {label, title, detail, open}) {
            child.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        DisciplineUi.add(card, label, 0);
        DisciplineUi.add(card, title, 8);
        DisciplineUi.add(card, detail, 8);
        DisciplineUi.add(card, open, 20);
        card.setOnClickListener(
                v ->
                        startActivity(
                                new Intent(this, target)
                                        .addFlags(
                                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        DisciplineUi.add(body, card, 22);
    }
}
