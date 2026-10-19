package com.shadowphoenix.dietlog.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadowphoenix.dietlog.BuildConfig;
import com.shadowphoenix.dietlog.data.DiaryDbHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Produces and validates deterministic, versioned backups of user-owned local source data. */
public final class BackupSnapshotCodec {
    public static final int SCHEMA_VERSION = 3;
    static final String PROFILE_PREFS = "dietlog_profile";
    static final String SERVING_PREFS = "serving_memory";
    private static final String[] USER_PREF_FILES = {PROFILE_PREFS, SERVING_PREFS};
    private static final int MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024;

    private BackupSnapshotCodec() {}

    public static byte[] create(Context context, SyncCoordinator.State state)
            throws IOException, JSONException {
        Context appContext = context.getApplicationContext();
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("createdAt", System.currentTimeMillis());
        root.put("deviceId", state.deviceId);
        root.put("revision", state.localRevision);
        root.put("appVersionCode", BuildConfig.VERSION_CODE);
        root.put("appVersionName", BuildConfig.VERSION_NAME);

        try (DiaryDbHelper diary = new DiaryDbHelper(appContext)) {
            root.put("database", diary.exportBackupData());
        }

        JSONObject preferences = new JSONObject();
        for (String file : USER_PREF_FILES) {
            preferences.put(file, encodePreferences(
                    appContext.getSharedPreferences(file, Context.MODE_PRIVATE)));
        }
        root.put("preferences", preferences);
        root.put("discipline",new com.shadowphoenix.dietlog.discipline.ReminderStore(appContext).exportBackup());
        return gzip(root.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static DecodedSnapshot decode(byte[] compressed) throws IOException {
        if (compressed == null || compressed.length == 0) {
            throw new BackupFormatException("Backup snapshot is empty");
        }
        byte[] raw = gunzipBounded(compressed);
        try {
            JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            int schema = root.optInt("schemaVersion", 0);
            if (schema < 1 || schema > SCHEMA_VERSION) {
                throw new BackupFormatException("Unsupported backup schema " + schema);
            }
            String deviceId = root.optString("deviceId", "").trim();
            if (deviceId.isEmpty()) throw new BackupFormatException("Backup device id is missing");
            JSONObject database = root.optJSONObject("database");
            if (database == null) throw new BackupFormatException("Backup database is missing");
            JSONObject preferences = root.optJSONObject("preferences");
            if (preferences == null) preferences = new JSONObject();
            JSONObject discipline = root.optJSONObject("discipline");
            if (discipline != null) com.shadowphoenix.dietlog.discipline.ReminderBackup.validate(discipline);
            return new DecodedSnapshot(
                    schema,
                    Math.max(0L, root.optLong("createdAt", 0L)),
                    deviceId,
                    Math.max(0L, root.optLong("revision", 0L)),
                    database,
                    preferences, discipline);
        } catch (JSONException | IllegalArgumentException e) {
            throw new BackupFormatException("Backup JSON is invalid", e);
        }
    }

    static Object decodePreferenceValue(JSONObject encoded) throws JSONException {
        if (encoded == null) return null;
        String type = encoded.optString("type", "");
        switch (type) {
            case "string": return encoded.optString("value", "");
            case "boolean": return encoded.optBoolean("value", false);
            case "int": return encoded.optInt("value", 0);
            case "long": return encoded.optLong("value", 0L);
            case "float": return (float) encoded.optDouble("value", 0.0);
            case "stringSet":
                JSONArray array = encoded.optJSONArray("value");
                Set<String> values = new java.util.HashSet<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        String value = array.optString(i, null);
                        if (value != null) values.add(value);
                    }
                }
                return values;
            default:
                return null;
        }
    }

    static long encodedLong(JSONObject prefs, String key, long fallback) {
        if (prefs == null) return fallback;
        JSONObject encoded = prefs.optJSONObject(key);
        if (encoded == null || !"long".equals(encoded.optString("type", ""))) return fallback;
        return encoded.optLong("value", fallback);
    }

    private static JSONObject encodePreferences(SharedPreferences prefs) throws JSONException {
        JSONObject out = new JSONObject();
        List<String> keys = new ArrayList<>(prefs.getAll().keySet());
        Collections.sort(keys);
        Map<String, ?> values = prefs.getAll();
        for (String key : keys) {
            Object value = values.get(key);
            if (value == null) continue;
            JSONObject encoded = new JSONObject();
            if (value instanceof String) {
                encoded.put("type", "string").put("value", value);
            } else if (value instanceof Boolean) {
                encoded.put("type", "boolean").put("value", value);
            } else if (value instanceof Integer) {
                encoded.put("type", "int").put("value", value);
            } else if (value instanceof Long) {
                encoded.put("type", "long").put("value", value);
            } else if (value instanceof Float) {
                encoded.put("type", "float").put("value", ((Float) value).doubleValue());
            } else if (value instanceof Set) {
                List<String> strings = new ArrayList<>();
                for (Object item : (Set<?>) value) {
                    if (item instanceof String) strings.add((String) item);
                }
                Collections.sort(strings);
                JSONArray array = new JSONArray();
                for (String item : strings) array.put(item);
                encoded.put("type", "stringSet").put("value", array);
            } else {
                continue;
            }
            out.put(key, encoded);
        }
        return out;
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(raw);
        }
        return output.toByteArray();
    }

    private static byte[] gunzipBounded(byte[] compressed) throws IOException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw new BackupFormatException("Backup expands beyond the safety limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (BackupFormatException e) {
            throw e;
        } catch (IOException e) {
            throw new BackupFormatException("Backup compression is invalid", e);
        }
    }

    public static final class DecodedSnapshot {
        public final int schemaVersion;
        public final long createdAt;
        public final String deviceId;
        public final long revision;
        public final JSONObject database;
        public final JSONObject preferences;
        public final JSONObject discipline;

        DecodedSnapshot(int schemaVersion, long createdAt, String deviceId, long revision,
                        JSONObject database, JSONObject preferences, JSONObject discipline) {
            this.schemaVersion = schemaVersion;
            this.createdAt = createdAt;
            this.deviceId = deviceId;
            this.revision = revision;
            this.database = database;
            this.preferences = preferences;
            this.discipline = discipline;
        }
    }

    public static final class BackupFormatException extends IOException {
        BackupFormatException(String message) { super(message); }
        BackupFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
