package com.shadowphoenix.dietlog.profile;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-local startup progress; recovery is resumed until its backup check succeeds. */
public final class StartupPreferences {
    private final SharedPreferences prefs;

    public StartupPreferences(Context context) {
        prefs = context.getSharedPreferences("dietlog_startup", Context.MODE_PRIVATE);
    }

    public boolean hasChosen() { return prefs.getBoolean("entryChoiceComplete", false); }
    public boolean hasPendingDriveRestore() { return prefs.getBoolean("driveRestorePending", false); }

    public boolean needsDriveRestore(boolean connected, boolean profileComplete) {
        return needsDriveRestore(connected, profileComplete, hasChosen(), hasPendingDriveRestore());
    }

    public static boolean needsDriveRestore(boolean connected, boolean profileComplete,
                                           boolean chosen, boolean pending) {
        return connected && (pending || (!chosen && !profileComplete));
    }

    public void beginDriveRestore() {
        prefs.edit().putBoolean("driveRestorePending", true).commit();
    }

    public void markChosen() {
        prefs.edit().putBoolean("entryChoiceComplete", true)
                .putBoolean("driveRestorePending", false).commit();
    }
}
