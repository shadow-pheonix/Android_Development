package com.shadowphoenix.dietlog.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.profile.StartupPreferences;

/** An optional, device-local entry choice; cancellation never counts as a choice. */
public final class StartupChoiceController {
    private final TextView google, guest, status;
    private boolean busy, restoring;

    public StartupChoiceController(Activity activity, Runnable connect, Runnable continueWithoutLogin,
                                   Runnable diagnostics) {
        google = activity.findViewById(R.id.startupGoogle);
        guest = activity.findViewById(R.id.startupGuest);
        status = activity.findViewById(R.id.startupChoiceStatus);
        google.setOnClickListener(v -> {
            if (busy) return;
            setBusy(true);
            status.setText("Opening Google Drive…");
            connect.run();
        });
        guest.setOnClickListener(v -> {
            if (restoring) return;
            setBusy(false);
            continueWithoutLogin.run();
        });
        activity.findViewById(R.id.startupChoiceDiagnostics).setOnClickListener(v -> diagnostics.run());
    }

    public static boolean hasChosen(Context context) {
        return new StartupPreferences(context).hasChosen();
    }

    public static void markChosen(Context context) {
        new StartupPreferences(context).markChosen();
    }

    public void showRestoring() {
        restoring = true;
        setBusy(true);
        google.setText("Restoring…");
        status.setText("Checking your Drive backup and restoring your profile…");
    }

    public void showRestoreFailure() {
        restoring = false;
        setBusy(false);
        google.setText("Retry Drive restore");
        status.setText("Couldn’t check your backup. Retry, or continue without login.");
    }

    public void showFailure(boolean cancelled) {
        restoring = false;
        setBusy(false);
        status.setText(cancelled ? "Login cancelled. Try again, or continue without login."
                : "Couldn’t connect to Google Drive. Try again, or continue without login.");
    }

    public void resume() {
        // Google results arrive before onResume. If no result arrived, release
        // an interrupted preparation request so rotation/backgrounding cannot
        // leave the user stuck on disabled controls.
        if (busy && !restoring) {
            setBusy(false);
            status.setText("Choose Google Drive, or continue without login.");
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        google.setEnabled(!value);
        google.setAlpha(value ? 0.5f : 1);
        google.setText(value ? "Connecting…" : "Continue with Google Drive");
        guest.setEnabled(!restoring);
        guest.setAlpha(restoring ? 0.5f : 1);
    }
}
