package com.shadowphoenix.dietlog.sync.supabase;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** Browser-based Supabase social OAuth with PKCE and a DietLog deep-link callback. */
public final class SupabaseOAuthFlow {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SupabaseOAuthFlow() {}

    public static Uri createAuthorizationUri(Context context) throws Exception {
        SupabaseConfig.requireConfigured();
        byte[] random = new byte[48];
        RANDOM.nextBytes(random);
        String verifier = base64Url(random);
        String challenge = base64Url(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        new SupabaseConnectionStore(context).savePendingOAuthVerifier(verifier);

        return Uri.parse(SupabaseConfig.projectUrl() + "/auth/v1/authorize")
                .buildUpon()
                .appendQueryParameter("provider", SupabaseConfig.OAUTH_PROVIDER)
                .appendQueryParameter("redirect_to", SupabaseConfig.OAUTH_REDIRECT_URI)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "s256")
                .build();
    }

    public static Intent browserIntent(Context context) throws Exception {
        return new Intent(Intent.ACTION_VIEW, createAuthorizationUri(context))
                .addCategory(Intent.CATEGORY_BROWSABLE);
    }

    public static boolean isCallback(Intent intent) {
        if (intent == null) return false;
        Uri uri = intent.getData();
        return uri != null
                && "com.shadowphoenix.dietlog".equals(uri.getScheme())
                && "supabase-auth".equals(uri.getHost());
    }

    public static Callback parseCallback(Intent intent) {
        if (!isCallback(intent)) return new Callback("", "", "Not a Supabase callback");
        Uri uri = intent.getData();
        String code = safe(uri.getQueryParameter("code"));
        String error = safe(uri.getQueryParameter("error"));
        String description = safe(uri.getQueryParameter("error_description"));
        if (!error.isEmpty()) {
            String message = description.isEmpty() ? error : description;
            return new Callback("", error, bounded(message));
        }
        if (code.isEmpty()) {
            return new Callback("", "missing_code", "Supabase returned no authorization code");
        }
        return new Callback(code, "", "");
    }

    private static String base64Url(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(String value) {
        String flattened = safe(value).replace('\n', ' ').replace('\r', ' ');
        return flattened.length() <= 240 ? flattened : flattened.substring(0, 240);
    }

    public static final class Callback {
        public final String code;
        public final String errorCode;
        public final String errorMessage;

        Callback(String code, String errorCode, String errorMessage) {
            this.code = code == null ? "" : code;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }

        public boolean isSuccess() {
            return !code.isEmpty() && errorCode.isEmpty();
        }
    }
}
