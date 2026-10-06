package com.shadowphoenix.dietlog.sync.supabase;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Minimal Supabase Auth REST client for passwordless email OTP and refresh-token sessions. */
public final class SupabaseAuthClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    public void sendEmailOtp(String email) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("email", requireEmail(email));
            body.put("create_user", true);
        } catch (JSONException e) {
            throw new IOException("Could not prepare Supabase email code request", e);
        }
        request("POST", "/auth/v1/otp", body, null);
    }

    public Session verifyEmailOtp(String email, String token) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("email", requireEmail(email));
            body.put("token", requireEmailOtp(token));
            body.put("type", "email");
        } catch (JSONException e) {
            throw new IOException("Could not prepare Supabase email code verification", e);
        }
        JSONObject response = request("POST", "/auth/v1/verify", body, null);
        Session session = parseSession(response);
        if (session == null) throw new IOException("Supabase returned no login session");
        return session;
    }

    public Session refresh(String refreshToken) throws IOException {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new AuthHttpException(401, "Supabase session is missing. Sign in again.");
        }
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", refreshToken.trim());
        } catch (JSONException e) {
            throw new IOException("Could not prepare Supabase session refresh", e);
        }
        JSONObject response = request(
                "POST", "/auth/v1/token?grant_type=refresh_token", body, null);
        Session session = parseSession(response);
        if (session == null) throw new IOException("Supabase returned no refreshed session");
        return session;
    }

    public void signOut(String accessToken) throws IOException {
        if (accessToken == null || accessToken.isEmpty()) return;
        request("POST", "/auth/v1/logout", new JSONObject(), accessToken);
    }

    private static Session parseSession(JSONObject response) {
        String accessToken = response.optString("access_token", "");
        String refreshToken = response.optString("refresh_token", "");
        if (accessToken.isEmpty() || refreshToken.isEmpty()) return null;
        JSONObject user = response.optJSONObject("user");
        String userId = user == null ? "" : user.optString("id", "");
        String email = user == null ? "" : user.optString("email", "");
        long expiresIn = Math.max(0L, response.optLong("expires_in", 0L));
        return new Session(accessToken, refreshToken, userId, email,
                System.currentTimeMillis() + expiresIn * 1000L);
    }

    private static JSONObject request(
            String method, String path, JSONObject body, String accessToken) throws IOException {
        SupabaseConfig.requireConfigured();
        HttpURLConnection connection = (HttpURLConnection) new URL(
                SupabaseConfig.projectUrl() + path).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("apikey", SupabaseConfig.publishableKey());
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            if (accessToken != null && !accessToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            }
            connection.setDoOutput(true);
            byte[] rawBody = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(rawBody);
            }

            int status = connection.getResponseCode();
            byte[] raw = readBounded(
                    status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            String text = new String(raw, StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                throw new AuthHttpException(status, safeError(text, status));
            }
            if (text.trim().isEmpty()) return new JSONObject();
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException("Supabase returned invalid JSON", e);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) return new byte[0];
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Supabase response is too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String safeError(String raw, int status) {
        String fallback = "Supabase authentication failed (HTTP " + status + ")";
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            JSONObject json = new JSONObject(raw);
            String message = json.optString("msg", "");
            if (message.isEmpty()) message = json.optString("message", "");
            if (message.isEmpty()) message = json.optString("error_description", "");
            if (message.isEmpty()) message = json.optString("error", "");
            if (message.isEmpty()) return fallback;
            message = message.replaceAll("[\\r\\n\\t]+", " ").trim();
            return message.length() <= 240 ? message : message.substring(0, 240);
        } catch (JSONException ignored) {
            return fallback;
        }
    }

    private static String requireEmail(String value) {
        String email = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (email.length() < 3 || email.length() > 254 || !email.contains("@")) {
            throw new IllegalArgumentException("Enter a valid email address");
        }
        return email;
    }

    private static String requireEmailOtp(String value) {
        String token = value == null ? "" : value.trim();
        if (!token.matches("\\d{6}")) {
            throw new IllegalArgumentException("Enter the 6-digit Supabase code");
        }
        return token;
    }

    public static final class Session {
        public final String accessToken;
        public final String refreshToken;
        public final String userId;
        public final String email;
        public final long expiresAt;

        Session(String accessToken, String refreshToken, String userId, String email, long expiresAt) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.userId = userId == null ? "" : userId;
            this.email = email == null ? "" : email;
            this.expiresAt = expiresAt;
        }
    }

    public static final class AuthHttpException extends IOException {
        public final int statusCode;

        AuthHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
