package com.shadowphoenix.dietlog.sync.drive;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores non-secret Google Drive connection metadata.
 *
 * OAuth access tokens are deliberately never persisted. They are requested from Google Identity
 * Services when a sync operation needs one.
 */
public final class DriveConnectionStore {
    private static final String PREFS = "dietlog_drive_connection";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_ACCOUNT_NAME = "accountName";
    private static final String KEY_CONNECTED_AT = "connectedAt";
    private static final String KEY_LAST_AUTH_AT = "lastAuthAt";

    private final SharedPreferences prefs;

    public DriveConnectionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public State getState() {
        return new State(
                prefs.getBoolean(KEY_CONNECTED, false),
                prefs.getString(KEY_ACCOUNT_NAME, ""),
                prefs.getLong(KEY_CONNECTED_AT, 0L),
                prefs.getLong(KEY_LAST_AUTH_AT, 0L));
    }

    public void markConnected(String accountName) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_CONNECTED, true)
                .putLong(KEY_LAST_AUTH_AT, now);
        if (!prefs.getBoolean(KEY_CONNECTED, false)) {
            editor.putLong(KEY_CONNECTED_AT, now);
        }
        if (accountName != null && !accountName.trim().isEmpty()) {
            editor.putString(KEY_ACCOUNT_NAME, accountName.trim());
        }
        editor.apply();
    }

    public void markAuthorizationRefreshed() {
        prefs.edit().putLong(KEY_LAST_AUTH_AT, System.currentTimeMillis()).apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    public static final class State {
        public final boolean connected;
        public final String accountName;
        public final long connectedAt;
        public final long lastAuthAt;

        State(boolean connected, String accountName, long connectedAt, long lastAuthAt) {
            this.connected = connected;
            this.accountName = accountName == null ? "" : accountName;
            this.connectedAt = connectedAt;
            this.lastAuthAt = lastAuthAt;
        }
    }
}
