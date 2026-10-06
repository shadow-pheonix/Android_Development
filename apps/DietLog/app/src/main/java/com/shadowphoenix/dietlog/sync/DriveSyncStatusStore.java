package com.shadowphoenix.dietlog.sync;

import android.content.Context;
import android.content.SharedPreferences;

/** Local-only operational status for presenting sync health without exposing credentials. */
public final class DriveSyncStatusStore {
    private static final String PREFS = "dietlog_drive_sync_status";
    private static final String KEY_LAST_ATTEMPT_AT = "lastAttemptAt";
    private static final String KEY_LAST_SUCCESS_AT = "lastSuccessAt";
    private static final String KEY_LAST_SUCCESS_REVISION = "lastSuccessRevision";
    private static final String KEY_LAST_ERROR = "lastError";
    private static final String KEY_REAUTH_REQUIRED = "reauthRequired";
    private static final String KEY_REMOTE_FILE_ID = "remoteFileId";
    private static final String KEY_LAST_REMOTE_MERGE_AT = "lastRemoteMergeAt";
    private static final String KEY_REMOTE_SNAPSHOTS_APPLIED = "remoteSnapshotsApplied";

    private final SharedPreferences prefs;

    public DriveSyncStatusStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void markAttempt() {
        prefs.edit().putLong(KEY_LAST_ATTEMPT_AT, System.currentTimeMillis()).apply();
    }

    public void markSuccess(long revision, String remoteFileId) {
        SharedPreferences.Editor editor = prefs.edit()
                .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
                .putLong(KEY_LAST_SUCCESS_REVISION, revision)
                .putString(KEY_LAST_ERROR, "")
                .putBoolean(KEY_REAUTH_REQUIRED, false);
        if (remoteFileId != null && !remoteFileId.isEmpty()) {
            editor.putString(KEY_REMOTE_FILE_ID, remoteFileId);
        }
        editor.apply();
    }

    public void markError(String message, boolean reauthRequired) {
        String safe = message == null ? "Sync failed" : message.trim();
        if (safe.length() > 300) safe = safe.substring(0, 300);
        prefs.edit()
                .putString(KEY_LAST_ERROR, safe)
                .putBoolean(KEY_REAUTH_REQUIRED, reauthRequired)
                .apply();
    }

    public void markRemoteMerge(int snapshotsApplied) {
        prefs.edit()
                .putLong(KEY_LAST_REMOTE_MERGE_AT, System.currentTimeMillis())
                .putInt(KEY_REMOTE_SNAPSHOTS_APPLIED, Math.max(0, snapshotsApplied))
                .apply();
    }

    public String getRemoteFileId() {
        return prefs.getString(KEY_REMOTE_FILE_ID, "");
    }

    public State getState() {
        return new State(
                prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L),
                prefs.getLong(KEY_LAST_SUCCESS_AT, 0L),
                prefs.getLong(KEY_LAST_SUCCESS_REVISION, 0L),
                prefs.getString(KEY_LAST_ERROR, ""),
                prefs.getBoolean(KEY_REAUTH_REQUIRED, false),
                prefs.getLong(KEY_LAST_REMOTE_MERGE_AT, 0L),
                prefs.getInt(KEY_REMOTE_SNAPSHOTS_APPLIED, 0));
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    public static final class State {
        public final long lastAttemptAt;
        public final long lastSuccessAt;
        public final long lastSuccessRevision;
        public final String lastError;
        public final boolean reauthRequired;
        public final long lastRemoteMergeAt;
        public final int remoteSnapshotsApplied;

        State(long lastAttemptAt, long lastSuccessAt, long lastSuccessRevision,
              String lastError, boolean reauthRequired, long lastRemoteMergeAt,
              int remoteSnapshotsApplied) {
            this.lastAttemptAt = lastAttemptAt;
            this.lastSuccessAt = lastSuccessAt;
            this.lastSuccessRevision = lastSuccessRevision;
            this.lastError = lastError == null ? "" : lastError;
            this.reauthRequired = reauthRequired;
            this.lastRemoteMergeAt = lastRemoteMergeAt;
            this.remoteSnapshotsApplied = remoteSnapshotsApplied;
        }
    }
}
