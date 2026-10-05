package com.shadowphoenix.dietlog.sync.drive;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Minimal Drive v3 REST transport restricted to the appDataFolder space. */
public final class DriveRestClient {
    private static final String API_BASE = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BACKUP_BYTES = 32 * 1024 * 1024;

    public List<DriveFileMetadata> listAppDataFiles(String accessToken) throws IOException {
        String fields = "files(id,name,modifiedTime,size,appProperties),nextPageToken";
        String url = API_BASE + "/files?spaces=appDataFolder&pageSize=1000&fields="
                + encode(fields);
        HttpURLConnection connection = open(url, "GET", accessToken);
        try {
            JSONObject root = parseObject(readSuccessfulResponse(connection, MAX_METADATA_BYTES));
            JSONArray files = root.optJSONArray("files");
            if (files == null || files.length() == 0) return Collections.emptyList();
            List<DriveFileMetadata> result = new ArrayList<>(files.length());
            for (int i = 0; i < files.length(); i++) {
                JSONObject object = files.optJSONObject(i);
                if (object != null) result.add(DriveFileMetadata.fromJson(object));
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    public DriveFileMetadata createAppDataFile(
            String accessToken, String name, byte[] content, Map<String, String> appProperties)
            throws IOException {
        JSONObject metadata = metadata(name, appProperties, true);
        return uploadMultipart(accessToken, null, metadata, content);
    }

    public DriveFileMetadata updateAppDataFile(
            String accessToken, String fileId, String name, byte[] content,
            Map<String, String> appProperties) throws IOException {
        requireFileId(fileId);
        JSONObject metadata = metadata(name, appProperties, false);
        return uploadMultipart(accessToken, fileId, metadata, content);
    }

    public byte[] downloadFile(String accessToken, String fileId) throws IOException {
        requireFileId(fileId);
        String url = API_BASE + "/files/" + encodePath(fileId) + "?alt=media";
        HttpURLConnection connection = open(url, "GET", accessToken);
        try {
            return readSuccessfulResponse(connection, MAX_BACKUP_BYTES);
        } finally {
            connection.disconnect();
        }
    }

    public void deleteFile(String accessToken, String fileId) throws IOException {
        requireFileId(fileId);
        String url = API_BASE + "/files/" + encodePath(fileId);
        HttpURLConnection connection = open(url, "DELETE", accessToken);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_NO_CONTENT && code != HttpURLConnection.HTTP_OK) {
                throw responseException(connection, code);
            }
        } finally {
            connection.disconnect();
        }
    }

    private DriveFileMetadata uploadMultipart(
            String accessToken, String fileId, JSONObject metadata, byte[] content)
            throws IOException {
        byte[] safeContent = content == null ? new byte[0] : content;
        if (safeContent.length > MAX_BACKUP_BYTES) {
            throw new IOException("Backup payload exceeds " + MAX_BACKUP_BYTES + " bytes");
        }

        String boundary = "dietlog_" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = UPLOAD_BASE + "/files";
        String method = "POST";
        if (fileId != null) {
            endpoint += "/" + encodePath(fileId);
            method = "PATCH";
        }
        endpoint += "?uploadType=multipart&fields="
                + encode("id,name,modifiedTime,size,appProperties");

        HttpURLConnection connection = open(endpoint, method, accessToken);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
        try (OutputStream output = connection.getOutputStream()) {
            writeAscii(output, "--" + boundary + "\r\n");
            writeAscii(output, "Content-Type: application/json; charset=UTF-8\r\n\r\n");
            output.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            writeAscii(output, "\r\n--" + boundary + "\r\n");
            writeAscii(output, "Content-Type: application/octet-stream\r\n\r\n");
            output.write(safeContent);
            writeAscii(output, "\r\n--" + boundary + "--\r\n");
        }

        try {
            JSONObject result = parseObject(readSuccessfulResponse(connection, MAX_METADATA_BYTES));
            return DriveFileMetadata.fromJson(result);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject metadata(
            String name, Map<String, String> appProperties, boolean includeParent) throws IOException {
        if (name == null || name.trim().isEmpty()) throw new IOException("Drive file name is required");
        try {
            JSONObject object = new JSONObject();
            object.put("name", name.trim());
            if (includeParent) {
                JSONArray parents = new JSONArray();
                parents.put("appDataFolder");
                object.put("parents", parents);
            }
            if (appProperties != null && !appProperties.isEmpty()) {
                JSONObject properties = new JSONObject();
                for (Map.Entry<String, String> entry : appProperties.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    properties.put(entry.getKey(), entry.getValue());
                }
                if (properties.length() > 0) object.put("appProperties", properties);
            }
            return object;
        } catch (JSONException e) {
            throw new IOException("Could not build Drive file metadata", e);
        }
    }

    private static HttpURLConnection open(String url, String method, String accessToken)
            throws IOException {
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IOException("Google Drive access token is missing");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if ("PATCH".equals(method)) {
            // Some HttpURLConnection implementations reject PATCH. Google APIs explicitly support
            // POST plus X-HTTP-Method-Override as the compatible representation of PATCH.
            connection.setRequestMethod("POST");
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            connection.setRequestMethod(method);
        }
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static byte[] readSuccessfulResponse(HttpURLConnection connection, int maxBytes)
            throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw responseException(connection, code);
        try (InputStream input = connection.getInputStream()) {
            return readBounded(input, maxBytes);
        }
    }

    private static DriveHttpException responseException(HttpURLConnection connection, int code)
            throws IOException {
        InputStream error = connection.getErrorStream();
        String body = "";
        if (error != null) {
            try (InputStream input = error) {
                body = new String(readBounded(input, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
            }
        }
        return new DriveHttpException(code, sanitizeError(body));
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IOException("Drive response exceeded size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static JSONObject parseObject(byte[] bytes) throws IOException {
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("Google Drive returned invalid JSON", e);
        }
    }

    private static void writeAscii(OutputStream output, String text) throws IOException {
        output.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireFileId(String fileId) throws IOException {
        if (fileId == null || fileId.trim().isEmpty()) throw new IOException("Drive file id is required");
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }

    private static String encodePath(String value) {
        return value.replace("%", "%25").replace("/", "%2F").replace("?", "%3F")
                .replace("#", "%23");
    }

    private static String sanitizeError(String body) {
        if (body == null || body.isEmpty()) return "";
        String flattened = body.replace('\n', ' ').replace('\r', ' ').trim();
        return flattened.length() <= 1000 ? flattened : flattened.substring(0, 1000);
    }

    public static final class DriveHttpException extends IOException {
        public final int statusCode;

        DriveHttpException(int statusCode, String responseBody) {
            super("Google Drive HTTP " + statusCode
                    + (responseBody.isEmpty() ? "" : ": " + responseBody));
            this.statusCode = statusCode;
        }
    }
}
