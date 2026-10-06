package com.shadowphoenix.dietlog.profile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AccountIdentityTest {
    @Test
    public void nicknameWinsOverConnectedAccounts() {
        assertEquals("Phoenix", AccountIdentity.preferredName(
                " Phoenix ", "google@example.com", "cloud@example.com"));
    }

    @Test
    public void googleAccountIsFallbackWhenNicknameIsBlank() {
        assertEquals("google@example.com", AccountIdentity.preferredName(
                "", " google@example.com ", "cloud@example.com"));
    }

    @Test
    public void supabaseEmailIsFallbackWhenGoogleIsUnavailable() {
        assertEquals("cloud@example.com", AccountIdentity.preferredName(
                null, "", " cloud@example.com "));
    }

    @Test
    public void providerLabelReflectsConnectedMethods() {
        assertEquals("Google Drive + Supabase", AccountIdentity.providerLabel(true, true));
        assertEquals("Google Drive", AccountIdentity.providerLabel(true, false));
        assertEquals("Supabase", AccountIdentity.providerLabel(false, true));
        assertEquals("", AccountIdentity.providerLabel(false, false));
    }
}
