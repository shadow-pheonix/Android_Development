package com.shadowphoenix.dietlog.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.shadowphoenix.dietlog.BuildConfig;
import com.shadowphoenix.dietlog.data.DiaryDbHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/** Produces deterministic, versioned backup snapshots from user-owned local source data. */
public final class BackupSnapshotCodec {
    public static final int SCHEMA_VERSION = 1;
    private static final String[] USER_PREF_FILES = {"dietlog_profile", "serving_memory"};

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
        return gzip(root.toString().getBytes(StandardCharsets.UTF_8));
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
}
