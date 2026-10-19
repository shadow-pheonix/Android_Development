package com.shadowphoenix.dietlog.discipline;

import android.app.*;
import android.widget.Toast;

import java.util.concurrent.*;

/** Shared restore review for both backup providers and the Discipline dashboard. */
public final class ReminderRestoreUi {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private ReminderRestoreUi() {}

    public static void show(Activity activity) {
        show(activity, true);
    }

    public static void showIfPending(Activity activity) {
        show(activity, false);
    }

    private static void show(Activity activity, boolean explainEmpty) {
        android.content.Context app = activity.getApplicationContext();
        IO.execute(
                () -> {
                    try {
                        ReminderStore.RestorePreview preview =
                                new ReminderStore(app).previewRestore();
                        activity.runOnUiThread(
                                () -> {
                                    if (activity.isFinishing() || activity.isDestroyed()) return;
                                    if (preview == null) {
                                        if (!explainEmpty) return;
                                        Toast.makeText(
                                                        activity,
                                                        "No Discipline restore is waiting for"
                                                                + " review.",
                                                        Toast.LENGTH_LONG)
                                                .show();
                                        return;
                                    }
                                    AlertDialog dialog =
                                            new AlertDialog.Builder(activity)
                                                    .setTitle("Preview Discipline restore")
                                                    .setMessage(preview.description)
                                                    .setNegativeButton("Later", null)
                                                    .setNeutralButton(
                                                            "Discard",
                                                            (d, w) ->
                                                                    IO.execute(
                                                                            () -> {
                                                                                try {
                                                                                    new ReminderStore(
                                                                                                    app)
                                                                                            .dismissRestore();
                                                                                } catch (
                                                                                        RuntimeException
                                                                                                error) {
                                                                                    message(
                                                                                            activity,
                                                                                            "Couldn’t"
                                                                                                + " discard"
                                                                                                + " the preview."
                                                                                                + " Please"
                                                                                                + " retry.");
                                                                                }
                                                                            }))
                                                    .setPositiveButton("Merge reminders", null)
                                                    .create();
                                    dialog.setOnShowListener(
                                            d ->
                                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                                            .setOnClickListener(
                                                                    v -> {
                                                                        dialog.getButton(
                                                                                        AlertDialog
                                                                                                .BUTTON_POSITIVE)
                                                                                .setEnabled(false);
                                                                        dialog.getButton(
                                                                                        AlertDialog
                                                                                                .BUTTON_NEGATIVE)
                                                                                .setEnabled(false);
                                                                        dialog.getButton(
                                                                                        AlertDialog
                                                                                                .BUTTON_NEUTRAL)
                                                                                .setEnabled(false);
                                                                        dialog.setCancelable(false);
                                                                        IO.execute(
                                                                                () -> {
                                                                                    String result;
                                                                                    try {
                                                                                        new ReminderStore(
                                                                                                        app)
                                                                                                .applyRestore(
                                                                                                        preview.token);
                                                                                        result =
                                                                                                "Discipline"
                                                                                                    + " reminders"
                                                                                                    + " and history"
                                                                                                    + " merged.";
                                                                                        try {
                                                                                            ReminderScheduler
                                                                                                    .rescheduleAll(
                                                                                                            app);
                                                                                        } catch (
                                                                                                RuntimeException
                                                                                                        error) {
                                                                                            result =
                                                                                                    "Data restored."
                                                                                                        + " Open"
                                                                                                        + " Discipline"
                                                                                                        + " and retry"
                                                                                                        + " alert"
                                                                                                        + " scheduling.";
                                                                                        }
                                                                                    } catch (
                                                                                            RuntimeException
                                                                                                    error) {
                                                                                        result =
                                                                                                "Restore"
                                                                                                    + " was not"
                                                                                                    + " applied."
                                                                                                    + " Your"
                                                                                                    + " data"
                                                                                                    + " may have"
                                                                                                    + " changed;"
                                                                                                    + " review"
                                                                                                    + " the preview"
                                                                                                    + " again.";
                                                                                    }
                                                                                    String
                                                                                            confirmation =
                                                                                                    result;
                                                                                    activity
                                                                                            .runOnUiThread(
                                                                                                    () -> {
                                                                                                        if (!activity
                                                                                                                        .isDestroyed()
                                                                                                                && !activity
                                                                                                                        .isFinishing()) {
                                                                                                            dialog
                                                                                                                    .dismiss();
                                                                                                            Toast
                                                                                                                    .makeText(
                                                                                                                            activity,
                                                                                                                            confirmation,
                                                                                                                            Toast
                                                                                                                                    .LENGTH_LONG)
                                                                                                                    .show();
                                                                                                        }
                                                                                                    });
                                                                                });
                                                                    }));
                                    dialog.show();
                                });
                    } catch (RuntimeException error) {
                        message(activity, "Couldn’t prepare the restore preview. Please retry.");
                    }
                });
    }

    private static void message(Activity activity, String message) {
        activity.runOnUiThread(
                () -> {
                    if (!activity.isDestroyed() && !activity.isFinishing())
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                });
    }
}
