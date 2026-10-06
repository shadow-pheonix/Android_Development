package com.shadowphoenix.dietlog.sync.supabase;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores only non-secret account metadata plus a Keystore-encrypted refresh token. */
public final class SupabaseConnectionStore {
    private static final String PREFS = "dietlog_supabase_connection";
    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_EMAIL = "email";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_CONNECTED_AT = "connectedAt";
    private static final String KEY_REFRESH_CIPHERTEXT = "refreshCiphertext";
    private static final String KEY_REFRESH_IV = "refreshIv";
    private static final String KEY_ALIAS = "dietlog_supabase_refresh_token_v1";

    private final SharedPreferences prefs;

    public SupabaseConnectionStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void saveSession(String email, String userId, String refreshToken) throws Exception {
        if (refreshToken == null || refreshToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Supabase refresh token is missing");
        }
        EncryptedValue encrypted = encrypt(refreshToken);
        prefs.edit()
                .putBoolean(KEY_CONNECTED, true)
                .putString(KEY_EMAIL, safe(email, 200))
                .putString(KEY_USER_ID, safe(userId, 80))
                .putLong(KEY_CONNECTED_AT, System.currentTimeMillis())
                .putString(KEY_REFRESH_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_REFRESH_IV, encrypted.iv)
                .commit();
    }

    public State getState() {
        return new State(
                prefs.getBoolean(KEY_CONNECTED, false),
                prefs.getString(KEY_EMAIL, ""),
                prefs.getString(KEY_USER_ID, ""),
                prefs.getLong(KEY_CONNECTED_AT, 0L));
    }

    public String loadRefreshToken() throws Exception {
        if (!prefs.getBoolean(KEY_CONNECTED, false)) return "";
        String ciphertext = prefs.getString(KEY_REFRESH_CIPHERTEXT, "");
        String iv = prefs.getString(KEY_REFRESH_IV, "");
        if (ciphertext == null || ciphertext.isEmpty() || iv == null || iv.isEmpty()) return "";
        return decrypt(ciphertext, iv);
    }

    public void clear() {
        prefs.edit().clear().commit();
    }

    private static EncryptedValue encrypt(String raw) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8));
        return new EncryptedValue(
                Base64.encodeToString(encrypted, Base64.NO_WRAP),
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
    }

    private static String decrypt(String ciphertext, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
        byte[] raw = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP));
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String safe(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static final class EncryptedValue {
        final String ciphertext;
        final String iv;

        EncryptedValue(String ciphertext, String iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }

    public static final class State {
        public final boolean connected;
        public final String email;
        public final String userId;
        public final long connectedAt;

        State(boolean connected, String email, String userId, long connectedAt) {
            this.connected = connected;
            this.email = email == null ? "" : email;
            this.userId = userId == null ? "" : userId;
            this.connectedAt = connectedAt;
        }
    }
}
