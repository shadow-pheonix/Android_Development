package com.shadowphoenix.dietlog.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Provider-neutral entity merge for validated DietLog backup snapshots. */
public final class BackupMergeEngine {
    private BackupMergeEngine() {}

    public static MergeResult merge(Context context, List<BackupSnapshotCodec.DecodedSnapshot> snapshots) {
        boolean changed = false;
        int applied = 0;
        for (BackupSnapshotCodec.DecodedSnapshot snapshot : snapshots) {
            if (snapshot == null) continue;
            try {
                if (applySnapshot(context, snapshot)) changed = true;
                applied++;
            } catch (JSONException e) {
                AppLogger.e("Cloud snapshot merge skipped due to malformed data", e);
            }
        }
        if (changed) SyncCoordinator.markRemoteMergeApplied(context, applied);
        return new MergeResult(changed, applied);
    }

    private static boolean applySnapshot(Context context, BackupSnapshotCodec.DecodedSnapshot snapshot)
            throws JSONException {
        Context appContext = context.getApplicationContext();
        boolean changed;
        try (DiaryDbHelper diary = new DiaryDbHelper(appContext)) {
            changed = diary.mergeBackupData(
                    snapshot.database, snapshot.deviceId, snapshot.createdAt);
        }
        changed |= mergeProfilePreferences(appContext, snapshot);
        changed |= mergeServingPreferences(appContext, snapshot);
        return changed;
    }

    private static boolean mergeProfilePreferences(
            Context context, BackupSnapshotCodec.DecodedSnapshot snapshot) throws JSONException {
        JSONObject remote = snapshot.preferences.optJSONObject(BackupSnapshotCodec.PROFILE_PREFS);
        if (remote == null) return false;
        SharedPreferences local = context.getSharedPreferences(
                BackupSnapshotCodec.PROFILE_PREFS, Context.MODE_PRIVATE);
        long remoteUpdated = BackupSnapshotCodec.encodedLong(
                remote, "_sync_updated_at", snapshot.createdAt);
        long localUpdated = local.getLong("_sync_updated_at", 0L);
        if (remoteUpdated <= localUpdated) return false;

        SharedPreferences.Editor editor = local.edit();
        java.util.Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            putDecoded(editor, key, remote.optJSONObject(key));
        }
        editor.putLong("_sync_updated_at", remoteUpdated).commit();
        return true;
    }

    private static boolean mergeServingPreferences(
            Context context, BackupSnapshotCodec.DecodedSnapshot snapshot) throws JSONException {
        JSONObject remote = snapshot.preferences.optJSONObject(BackupSnapshotCodec.SERVING_PREFS);
        if (remote == null) return false;
        SharedPreferences local = context.getSharedPreferences(
                BackupSnapshotCodec.SERVING_PREFS, Context.MODE_PRIVATE);
        Set<String> bases = new HashSet<>();
        java.util.Iterator<String> keys = remote.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String base = servingBase(key);
            if (base != null) bases.add(base);
        }

        boolean changed = false;
        SharedPreferences.Editor editor = local.edit();
        for (String base : bases) {
            long remoteUpdated = BackupSnapshotCodec.encodedLong(
                    remote, base + ".updatedAt", snapshot.createdAt);
            long localUpdated = local.getLong(base + ".updatedAt", 0L);
            if (remoteUpdated <= localUpdated) continue;
            copyIfPresent(editor, remote, base + ".base");
            copyIfPresent(editor, remote, base + ".multiplier");
            copyIfPresent(editor, remote, base + ".label");
            editor.putLong(base + ".updatedAt", remoteUpdated);
            changed = true;
        }
        if (changed) editor.commit();
        return changed;
    }

    private static String servingBase(String key) {
        String[] suffixes = {".base", ".multiplier", ".label", ".updatedAt"};
        for (String suffix : suffixes) {
            if (key.endsWith(suffix) && key.length() > suffix.length()) {
                return key.substring(0, key.length() - suffix.length());
            }
        }
        return null;
    }

    private static void copyIfPresent(
            SharedPreferences.Editor editor, JSONObject remote, String key) throws JSONException {
        JSONObject encoded = remote.optJSONObject(key);
        if (encoded != null) putDecoded(editor, key, encoded);
    }

    @SuppressWarnings("unchecked")
    private static void putDecoded(
            SharedPreferences.Editor editor, String key, JSONObject encoded) throws JSONException {
        Object value = BackupSnapshotCodec.decodePreferenceValue(encoded);
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
    }

    public static final class MergeResult {
        public final boolean changed;
        public final int snapshotsApplied;

        MergeResult(boolean changed, int snapshotsApplied) {
            this.changed = changed;
            this.snapshotsApplied = snapshotsApplied;
        }
    }
}
