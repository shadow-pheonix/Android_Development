package com.shadowphoenix.dietlog.sync.drive;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;

import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;

import java.util.Collections;

/**
 * Requests the narrow Drive AppData OAuth scope without persisting access tokens.
 *
 * Initial authorization may require an account/consent UI. Later background refreshes are silent;
 * if Google requires user interaction again, the caller receives REAUTH_REQUIRED and must wait for
 * the user to reopen DietLog instead of attempting to display UI from background work.
 */
public final class GoogleDriveAuthorization {
    public static final String DRIVE_APPDATA_SCOPE =
            "https://www.googleapis.com/auth/drive.appdata";
    public static final int REQUEST_CODE = 49031;

    private GoogleDriveAuthorization() {}

    public static void beginConnect(Activity activity, Callback callback) {
        AuthorizationRequest request = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_APPDATA_SCOPE)))
                .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
                .build();
        requestAuthorization(activity, request, callback, true);
    }

    public static void requestSilentToken(Context context, Callback callback) {
        DriveConnectionStore.State state = new DriveConnectionStore(context).getState();
        if (!state.connected) {
            callback.onFailure(Failure.NOT_CONNECTED, "Google Drive is not connected.", null);
            return;
        }

        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_APPDATA_SCOPE)));
        if (!state.accountName.isEmpty()) {
            builder.setAccount(new Account(state.accountName, "com.google"));
        }
        requestAuthorization(context, builder.build(), callback, false);
    }

    public static boolean handleActivityResult(
            Activity activity, int requestCode, int resultCode, Intent data, Callback callback) {
        if (requestCode != REQUEST_CODE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) {
            callback.onFailure(Failure.CANCELLED, "Google Drive authorization was cancelled.", null);
            return true;
        }

        AuthorizationClient client = Identity.getAuthorizationClient(activity);
        try {
            AuthorizationResult result = client.getAuthorizationResultFromIntent(data);
            deliverAuthorizedResult(activity, result, callback);
        } catch (ApiException e) {
            AppLogger.e("Drive authorization result failed status=" + e.getStatusCode(), e);
            callback.onFailure(Failure.AUTHORIZATION_FAILED,
                    "Google Drive authorization failed.", e);
        }
        return true;
    }

    private static void requestAuthorization(
            Context context, AuthorizationRequest request, Callback callback, boolean allowResolution) {
        AuthorizationClient client = Identity.getAuthorizationClient(context);
        client.authorize(request)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        if (!allowResolution || !(context instanceof Activity)) {
                            callback.onFailure(Failure.REAUTH_REQUIRED,
                                    "Google Drive needs user authorization.", null);
                            return;
                        }
                        launchResolution((Activity) context, result.getPendingIntent(), callback);
                        return;
                    }
                    deliverAuthorizedResult(context, result, callback);
                })
                .addOnFailureListener(error -> {
                    AppLogger.e("Drive authorization request failed: "
                            + error.getClass().getSimpleName(), error);
                    callback.onFailure(Failure.AUTHORIZATION_FAILED,
                            "Could not authorize Google Drive.", error);
                });
    }

    private static void launchResolution(
            Activity activity, PendingIntent pendingIntent, Callback callback) {
        if (pendingIntent == null) {
            callback.onFailure(Failure.AUTHORIZATION_FAILED,
                    "Google Drive authorization did not provide a consent flow.", null);
            return;
        }
        try {
            activity.startIntentSenderForResult(
                    pendingIntent.getIntentSender(), REQUEST_CODE, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            callback.onFailure(Failure.AUTHORIZATION_FAILED,
                    "Could not open Google Drive authorization.", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void deliverAuthorizedResult(
            Context context, AuthorizationResult result, Callback callback) {
        String accessToken = result == null ? null : result.getAccessToken();
        if (accessToken == null || accessToken.trim().isEmpty()) {
            callback.onFailure(Failure.AUTHORIZATION_FAILED,
                    "Google Drive did not return an access token.", null);
            return;
        }

        String accountName = "";
        GoogleSignInAccount signInAccount = result.toGoogleSignInAccount();
        if (signInAccount != null) {
            Account account = signInAccount.getAccount();
            if (account != null && account.name != null) accountName = account.name;
        }

        DriveConnectionStore store = new DriveConnectionStore(context);
        if (!store.getState().connected) store.markConnected(accountName);
        else {
            if (!accountName.isEmpty()) store.markConnected(accountName);
            else store.markAuthorizationRefreshed();
        }
        callback.onAuthorized(accessToken, accountName);
    }

    public enum Failure {
        NOT_CONNECTED,
        REAUTH_REQUIRED,
        CANCELLED,
        AUTHORIZATION_FAILED
    }

    public interface Callback {
        void onAuthorized(String accessToken, String accountName);
        void onFailure(Failure failure, String message, Throwable error);
    }
}
