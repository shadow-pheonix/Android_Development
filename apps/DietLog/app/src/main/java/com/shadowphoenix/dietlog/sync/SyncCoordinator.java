package com.shadowphoenix.dietlog.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;

import java.util.UUID;

/**
 * Central mutation marker for user-owned DietLog state.
 *
 * Data writes remain local-first. Every successful user-state mutation increments a monotonically
 * increasing local revision and marks cloud backup dirty. Later sync stages can safely clear the
 * dirty bit only for the exact revision they uploaded, so a newer local write is never lost.
 */
public final class SyncCoordinator {
    static final String PREFS = "dietlog_sync_state";
    private static final String KEY_DEVICE_ID = "deviceId";
    private static final String KEY_LOCAL_REVISION = "localRevision";
    private static final String KEY_DIRTY = "dirty";
    private static final String KEY_LAST_CHANGE_AT = "lastChangeAt";
    private static final String KEY_LAST_CHANGE_REASON = "lastChangeReason";
    private static final String KEY_LAST_SYNCED_REVISION = "lastSyncedRevision";

    private SyncCoordinator() {}

    public static State getState(Context context) {
        SharedPreferences prefs = prefs(context);
        return new State(
                getOrCreateDeviceId(prefs),
                prefs.getLong(KEY_LOCAL_REVISION, 0L),
                prefs.getBoolean(KEY_DIRTY, false),
                prefs.getLong(KEY_LAST_CHANGE_AT, 0L),
                prefs.getString(KEY_LAST_CHANGE_REASON, ""),
                prefs.getLong(KEY_LAST_SYNCED_REVISION, 0L)
        );
    }

    public static long markUserDataChanged(Context context, String reason) {
        SharedPreferences prefs = prefs(context);
        long nextRevision = prefs.getLong(KEY_LOCAL_REVISION, 0L) + 1L;
        prefs.edit()
                .putLong(KEY_LOCAL_REVISION, nextRevision)
                .putBoolean(KEY_DIRTY, true)
                .putLong(KEY_LAST_CHANGE_AT, System.currentTimeMillis())
                .putString(KEY_LAST_CHANGE_REASON, sanitizeReason(reason))
                .apply();
        AppLogger.i("Backup state dirty revision=" + nextRevision + " reason=" + sanitizeReason(reason));
        DriveBackupScheduler.scheduleDebounced(context);
        return nextRevision;
    }

    /**
     * Clears dirty only when no newer local mutation happened while a snapshot was being uploaded.
     */
    public static boolean markRevisionSynced(Context context, long uploadedRevision) {
        SharedPreferences prefs = prefs(context);
        long currentRevision = prefs.getLong(KEY_LOCAL_REVISION, 0L);
        SharedPreferences.Editor editor = prefs.edit()
                .putLong(KEY_LAST_SYNCED_REVISION,
                        Math.max(prefs.getLong(KEY_LAST_SYNCED_REVISION, 0L), uploadedRevision));
        boolean exact = currentRevision == uploadedRevision;
        if (exact) editor.putBoolean(KEY_DIRTY, false);
        editor.apply();
        return exact;
    }

    public static void forceDirty(Context context, String reason) {
        SharedPreferences prefs = prefs(context);
        prefs.edit()
                .putBoolean(KEY_DIRTY, true)
                .putLong(KEY_LAST_CHANGE_AT, System.currentTimeMillis())
                .putString(KEY_LAST_CHANGE_REASON, sanitizeReason(reason))
                .apply();
        DriveBackupScheduler.scheduleDebounced(context);
    }

    /** Marks one completed remote merge without recursively scheduling the worker already running. */
    public static long markRemoteMergeApplied(Context context, int snapshotCount) {
        SharedPreferences prefs = prefs(context);
        long nextRevision = prefs.getLong(KEY_LOCAL_REVISION, 0L) + 1L;
        prefs.edit()
                .putLong(KEY_LOCAL_REVISION, nextRevision)
                .putBoolean(KEY_DIRTY, true)
                .putLong(KEY_LAST_CHANGE_AT, System.currentTimeMillis())
                .putString(KEY_LAST_CHANGE_REASON,
                        "remote-merge:" + Math.max(0, snapshotCount))
                .apply();
        return nextRevision;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String getOrCreateDeviceId(SharedPreferences prefs) {
        String existing = prefs.getString(KEY_DEVICE_ID, "");
        if (existing != null && !existing.isEmpty()) return existing;
        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, created).commit();
        return created;
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) return "user-data";
        String trimmed = reason.trim();
        return trimmed.length() <= 80 ? trimmed : trimmed.substring(0, 80);
    }

    public static final class State {
        public final String deviceId;
        public final long localRevision;
        public final boolean dirty;
        public final long lastChangeAt;
        public final String lastChangeReason;
        public final long lastSyncedRevision;

        State(String deviceId, long localRevision, boolean dirty, long lastChangeAt,
              String lastChangeReason, long lastSyncedRevision) {
            this.deviceId = deviceId;
            this.localRevision = localRevision;
            this.dirty = dirty;
            this.lastChangeAt = lastChangeAt;
            this.lastChangeReason = lastChangeReason;
            this.lastSyncedRevision = lastSyncedRevision;
        }
    }
}
