package com.shadowphoenix.dietlog.sync.supabase;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal authenticated PostgREST client for DietLog's private snapshot table. */
public final class SupabaseDataClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_SNAPSHOT_BYTES = 12 * 1024 * 1024;
    private static final String TABLE = "/rest/v1/dietlog_snapshots";

    public List<SupabaseSnapshotMetadata> listSnapshots(String accessToken) throws IOException {
        String path = TABLE
                + "?select=snapshot_key,device_id,kind,revision,snapshot_created_at,schema_version"
                + "&order=snapshot_created_at.desc&limit=1000";
        JSONArray rows = requestArray("GET", path, null, accessToken, null);
        List<SupabaseSnapshotMetadata> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            out.add(new SupabaseSnapshotMetadata(
                    row.optString("snapshot_key", ""),
                    row.optString("device_id", ""),
                    row.optString("kind", ""),
                    Math.max(0L, row.optLong("revision", 0L)),
                    Math.max(0L, row.optLong("snapshot_created_at", 0L)),
                    Math.max(0, row.optInt("schema_version", 0))));
        }
        return out;
    }

    public void upsertCurrent(
            String accessToken, String userId, String snapshotKey, String deviceId,
            String kind, long revision, long createdAt, int schemaVersion, byte[] payload)
            throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Prefer", "resolution=merge-duplicates,return=minimal");
        String path = TABLE + "?on_conflict=user_id,snapshot_key";
        requestArray("POST", path,
                snapshotBody(userId, snapshotKey, deviceId, kind, revision,
                        createdAt, schemaVersion, payload),
                accessToken, headers);
    }

    public void insertHistory(
            String accessToken, String userId, String snapshotKey, String deviceId,
            String kind, long revision, long createdAt, int schemaVersion, byte[] payload)
            throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Prefer", "return=minimal");
        requestArray("POST", TABLE,
                snapshotBody(userId, snapshotKey, deviceId, kind, revision,
                        createdAt, schemaVersion, payload),
                accessToken, headers);
    }

    public byte[] downloadSnapshot(String accessToken, String snapshotKey) throws IOException {
        String path = TABLE + "?select=payload_base64&snapshot_key=eq."
                + encodeFilter(snapshotKey) + "&limit=1";
        JSONArray rows = requestArray("GET", path, null, accessToken, null);
        if (rows.length() == 0) throw new DataHttpException(404, "Supabase snapshot was not found");
        JSONObject row = rows.optJSONObject(0);
        String encoded = row == null ? "" : row.optString("payload_base64", "");
        if (encoded.isEmpty()) throw new IOException("Supabase snapshot payload is empty");
        try {
            byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
            if (decoded.length > MAX_SNAPSHOT_BYTES) {
                throw new IOException("Supabase snapshot exceeds the safety limit");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw new IOException("Supabase snapshot payload is invalid", e);
        }
    }

    public void deleteSnapshot(String accessToken, String snapshotKey) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Prefer", "return=minimal");
        requestArray("DELETE", TABLE + "?snapshot_key=eq." + encodeFilter(snapshotKey),
                null, accessToken, headers);
    }

    private static JSONObject snapshotBody(
            String userId, String snapshotKey, String deviceId, String kind,
            long revision, long createdAt, int schemaVersion, byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) throw new IOException("Snapshot is empty");
        if (payload.length > MAX_SNAPSHOT_BYTES) {
            throw new IOException("Snapshot exceeds the 12 MiB Supabase safety limit");
        }
        if (userId == null || userId.trim().isEmpty()) throw new IOException("Supabase user id is missing");
        try {
            return new JSONObject()
                    .put("user_id", userId.trim())
                    .put("snapshot_key", bounded(snapshotKey, 200, "snapshot key"))
                    .put("device_id", bounded(deviceId, 100, "device id"))
                    .put("kind", bounded(kind, 40, "snapshot kind"))
                    .put("revision", Math.max(0L, revision))
                    .put("snapshot_created_at", Math.max(0L, createdAt))
                    .put("schema_version", Math.max(1, schemaVersion))
                    .put("payload_base64", Base64.encodeToString(payload, Base64.NO_WRAP));
        } catch (JSONException e) {
            throw new IOException("Could not prepare Supabase snapshot", e);
        }
    }

    private static JSONArray requestArray(
            String method, String path, JSONObject body, String accessToken,
            Map<String, String> extraHeaders) throws IOException {
        SupabaseConfig.requireConfigured();
        if (accessToken == null || accessToken.isEmpty()) {
            throw new DataHttpException(401, "Supabase session is missing. Sign in again.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(
                SupabaseConfig.projectUrl() + path).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("apikey", SupabaseConfig.publishableKey());
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                byte[] rawBody = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(rawBody);
                }
            }

            int status = connection.getResponseCode();
            byte[] raw = readBounded(
                    status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            String text = new String(raw, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new DataHttpException(status, safeError(text, status));
            }
            if (text.trim().isEmpty()) return new JSONArray();
            try {
                return new JSONArray(text);
            } catch (JSONException e) {
                throw new IOException("Supabase Data API returned invalid JSON", e);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("Supabase Data API response is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String safeError(String raw, int status) {
        String fallback = "Supabase Data API failed (HTTP " + status + ")";
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            JSONObject json = new JSONObject(raw);
            String message = json.optString("message", "");
            if (message.isEmpty()) message = json.optString("details", "");
            if (message.isEmpty()) message = json.optString("hint", "");
            if (message.isEmpty()) return fallback;
            message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
            return message.length() <= 300 ? message : message.substring(0, 300);
        } catch (JSONException ignored) {
            return fallback;
        }
    }

    private static String encodeFilter(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name())
                .replace("+", "%20");
    }

    private static String bounded(String value, int max, String label) throws IOException {
        if (value == null || value.trim().isEmpty()) throw new IOException("Supabase " + label + " is missing");
        String trimmed = value.trim();
        if (trimmed.length() > max) throw new IOException("Supabase " + label + " is too long");
        return trimmed;
    }

    public static final class DataHttpException extends IOException {
        public final int statusCode;

        DataHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public boolean isRetryable() {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500;
        }
    }
}
