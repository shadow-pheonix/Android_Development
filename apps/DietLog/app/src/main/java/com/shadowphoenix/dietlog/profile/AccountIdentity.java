package com.shadowphoenix.dietlog.profile;

/** Pure account-label rules shared by Profile and the account/sync entry point. */
public final class AccountIdentity {
    private AccountIdentity() {}

    public static String preferredName(
            String nickname, String googleAccountName, String supabaseEmail) {
        String savedNickname = clean(nickname);
        if (!savedNickname.isEmpty()) return savedNickname;
        String google = clean(googleAccountName);
        if (!google.isEmpty()) return google;
        return clean(supabaseEmail);
    }

    public static String providerLabel(boolean googleDriveConnected, boolean supabaseConnected) {
        if (googleDriveConnected && supabaseConnected) return "Google Drive + Supabase";
        if (googleDriveConnected) return "Google Drive";
        if (supabaseConnected) return "Supabase";
        return "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
