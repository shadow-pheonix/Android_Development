package com.shadowphoenix.dietlog.ui;

import android.app.Activity;
import android.content.Context;
import android.widget.TextView;

import com.shadowphoenix.dietlog.R;

/** An optional, device-local entry choice; cancellation never counts as a choice. */
public final class StartupChoiceController {
    private final TextView google, guest, status;
    private boolean busy;

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
        guest.setOnClickListener(v -> { setBusy(false); continueWithoutLogin.run(); });
        activity.findViewById(R.id.startupChoiceDiagnostics).setOnClickListener(v -> diagnostics.run());
    }

    public static boolean hasChosen(Context context) {
        return context.getSharedPreferences("dietlog_startup", Context.MODE_PRIVATE)
                .getBoolean("entryChoiceComplete", false);
    }

    public static void markChosen(Context context) {
        context.getSharedPreferences("dietlog_startup", Context.MODE_PRIVATE)
                .edit().putBoolean("entryChoiceComplete", true).apply();
    }

    public void showFailure(boolean cancelled) {
        setBusy(false);
        status.setText(cancelled ? "Login cancelled. Try again, or continue without login."
                : "Couldn’t connect to Google Drive. Try again, or continue without login.");
    }

    public void resume() {
        // Google results arrive before onResume. If no result arrived, release
        // an interrupted preparation request so rotation/backgrounding cannot
        // leave the user stuck on disabled controls.
        if (busy) {
            setBusy(false);
            status.setText("Choose Google Drive, or continue without login.");
        }
    }

    private void setBusy(boolean value) {
        busy = value;
        google.setEnabled(!value);
        google.setAlpha(value ? 0.5f : 1);
        google.setText(value ? "Connecting…" : "Continue with Google Drive");
    }
}
