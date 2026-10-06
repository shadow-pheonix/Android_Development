package com.shadowphoenix.dietlog.sync.supabase;

import com.shadowphoenix.dietlog.BuildConfig;

/** Runtime-safe access to the public Supabase project configuration baked in at build time. */
public final class SupabaseConfig {
    private SupabaseConfig() {}

    public static boolean isConfigured() {
        return !projectUrl().isEmpty() && !publishableKey().isEmpty();
    }

    public static String projectUrl() {
        return normalizeUrl(BuildConfig.SUPABASE_URL);
    }

    public static String publishableKey() {
        String value = BuildConfig.SUPABASE_PUBLISHABLE_KEY;
        return value == null ? "" : value.trim();
    }

    static void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Supabase is not configured in this DietLog build.");
        }
    }

    private static String normalizeUrl(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }
}
