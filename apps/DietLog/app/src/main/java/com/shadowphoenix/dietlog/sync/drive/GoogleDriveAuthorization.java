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
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;

import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        AppLogger.i("Drive authorization requested scope=drive.appdata");
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
        requestAuthorization(context, silentRequest(state), callback, false);
    }

    /** Background-only token acquisition. Never launches user interface. */
    public static TokenResult requestSilentTokenBlocking(Context context)
            throws AuthorizationException {
        DriveConnectionStore.State state = new DriveConnectionStore(context).getState();
        if (!state.connected) {
            throw new AuthorizationException(
                    Failure.NOT_CONNECTED, "Google Drive is not connected.", null);
        }
        try {
            AuthorizationResult result = Tasks.await(
                    Identity.getAuthorizationClient(context).authorize(silentRequest(state)),
                    20, TimeUnit.SECONDS);
            if (result.hasResolution()) {
                throw new AuthorizationException(
                        Failure.REAUTH_REQUIRED, "Google Drive needs user authorization.", null);
            }
            return tokenResult(context, result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthorizationException(
                    Failure.AUTHORIZATION_FAILED, "Google Drive authorization was interrupted.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AuthorizationException(
                    Failure.AUTHORIZATION_FAILED, "Could not authorize Google Drive.", e);
        }
    }

    private static AuthorizationRequest silentRequest(DriveConnectionStore.State state) {
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_APPDATA_SCOPE)));
        if (!state.accountName.isEmpty()) {
            builder.setAccount(new Account(state.accountName, "com.google"));
        }
        return builder.build();
    }


    public static void revokeAccess(Context context, DisconnectCallback callback) {
        DriveConnectionStore.State state = new DriveConnectionStore(context).getState();
        if (!state.connected) {
            callback.onDisconnected();
            return;
        }
        if (state.accountName == null || state.accountName.trim().isEmpty()) {
            callback.onFailure(
                    "Google account identity is unavailable for server-side revocation.", null);
            return;
        }

        RevokeAccessRequest request = RevokeAccessRequest.builder()
                .setAccount(new Account(state.accountName.trim(), "com.google"))
                .setScopes(Collections.singletonList(new Scope(DRIVE_APPDATA_SCOPE)))
                .build();
        Identity.getAuthorizationClient(context).revokeAccess(request)
                .addOnSuccessListener(unused -> callback.onDisconnected())
                .addOnFailureListener(error -> callback.onFailure(
                        "Google Drive permission revocation failed.", error));
    }

    public static boolean handleActivityResult(
            Activity activity, int requestCode, int resultCode, Intent data, Callback callback) {
        if (requestCode != REQUEST_CODE) return false;
        AppLogger.i("Drive authorization activity result code=" + resultCode
                + " hasData=" + (data != null));

        // Google returns authorization failures inside the result Intent as ApiException status
        // codes. Parse any returned Intent even if the Activity result code is not RESULT_OK, so
        // OAuth configuration errors are not silently misclassified as user cancellation.
        if (data != null) {
            AuthorizationClient client = Identity.getAuthorizationClient(activity);
            try {
                AuthorizationResult result = client.getAuthorizationResultFromIntent(data);
                deliverAuthorizedResult(activity, result, callback);
                return true;
            } catch (ApiException e) {
                int statusCode = e.getStatusCode();
                String statusName = CommonStatusCodes.getStatusCodeString(statusCode);
                String message = authorizationFailureMessage(statusCode);
                AppLogger.e("Drive authorization result failed status=" + statusCode
                        + " name=" + statusName + " resultCode=" + resultCode, e);
                callback.onFailure(statusCode == CommonStatusCodes.CANCELED
                                ? Failure.CANCELLED : Failure.AUTHORIZATION_FAILED,
                        message, e);
                return true;
            }
        }

        if (resultCode == Activity.RESULT_CANCELED) {
            AppLogger.i("Drive authorization canceled with no result data");
            callback.onFailure(Failure.CANCELLED,
                    "Google Drive authorization was cancelled before Google returned details.", null);
        } else {
            AppLogger.e("Drive authorization returned no data resultCode=" + resultCode, null);
            callback.onFailure(Failure.AUTHORIZATION_FAILED,
                    "Google Drive authorization returned no result (code " + resultCode + ").", null);
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
                    if (error instanceof ApiException) {
                        ApiException api = (ApiException) error;
                        int statusCode = api.getStatusCode();
                        AppLogger.e("Drive authorization request failed status=" + statusCode
                                + " name=" + CommonStatusCodes.getStatusCodeString(statusCode), error);
                        callback.onFailure(statusCode == CommonStatusCodes.CANCELED
                                        ? Failure.CANCELLED : Failure.AUTHORIZATION_FAILED,
                                authorizationFailureMessage(statusCode), error);
                    } else {
                        AppLogger.e("Drive authorization request failed: "
                                + error.getClass().getSimpleName(), error);
                        callback.onFailure(Failure.AUTHORIZATION_FAILED,
                                "Could not authorize Google Drive.", error);
                    }
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
            AppLogger.i("Drive authorization resolution launched");
            activity.startIntentSenderForResult(
                    pendingIntent.getIntentSender(), REQUEST_CODE, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            callback.onFailure(Failure.AUTHORIZATION_FAILED,
                    "Could not open Google Drive authorization.", e);
        }
    }

    private static void deliverAuthorizedResult(
            Context context, AuthorizationResult result, Callback callback) {
        try {
            TokenResult token = tokenResult(context, result);
            callback.onAuthorized(token.accessToken, token.accountName);
        } catch (AuthorizationException e) {
            callback.onFailure(e.failure, e.getMessage(), e.getCause());
        }
    }

    @SuppressWarnings("deprecation")
    private static TokenResult tokenResult(Context context, AuthorizationResult result)
            throws AuthorizationException {
        String accessToken = result == null ? null : result.getAccessToken();
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new AuthorizationException(Failure.AUTHORIZATION_FAILED,
                    "Google Drive did not return an access token.", null);
        }

        String accountName = "";
        String displayName = "";
        GoogleSignInAccount signInAccount = result.toGoogleSignInAccount();
        if (signInAccount != null) {
            Account account = signInAccount.getAccount();
            if (account != null && account.name != null) accountName = account.name;
            if (signInAccount.getDisplayName() != null) {
                displayName = signInAccount.getDisplayName().trim();
            }
        }

        DriveConnectionStore store = new DriveConnectionStore(context);
        if (!accountName.isEmpty() || !displayName.isEmpty() || !store.getState().connected) {
            store.markConnected(accountName, displayName);
        }
        else store.markAuthorizationRefreshed();
        return new TokenResult(accessToken, accountName);
    }

    private static String authorizationFailureMessage(int statusCode) {
        switch (statusCode) {
            case CommonStatusCodes.DEVELOPER_ERROR:
                return "Google OAuth is not configured for this FuelNote APK. Verify the Android OAuth client package name and signing SHA-1, then try again (status 10).";
            case CommonStatusCodes.NETWORK_ERROR:
                return "Google Drive authorization hit a network error. Check your connection and try again (status 7).";
            case CommonStatusCodes.INVALID_ACCOUNT:
                return "Google rejected the selected account. Choose another Google account and try again (status 5).";
            case CommonStatusCodes.CANCELED:
                return "Google Drive authorization was cancelled (status 16).";
            case CommonStatusCodes.TIMEOUT:
                return "Google Drive authorization timed out. Try again (status 15).";
            case CommonStatusCodes.INTERNAL_ERROR:
                return "Google Play services returned an internal authorization error. Try again (status 8).";
            default:
                return "Google Drive authorization failed ("
                        + CommonStatusCodes.getStatusCodeString(statusCode)
                        + ", status " + statusCode + ").";
        }
    }

    public enum Failure {
        NOT_CONNECTED,
        REAUTH_REQUIRED,
        CANCELLED,
        AUTHORIZATION_FAILED
    }

    public static final class TokenResult {
        public final String accessToken;
        public final String accountName;

        TokenResult(String accessToken, String accountName) {
            this.accessToken = accessToken;
            this.accountName = accountName == null ? "" : accountName;
        }
    }

    public static final class AuthorizationException extends Exception {
        public final Failure failure;

        AuthorizationException(Failure failure, String message, Throwable cause) {
            super(message, cause);
            this.failure = failure;
        }
    }

    public interface DisconnectCallback {
        void onDisconnected();
        void onFailure(String message, Throwable error);
    }

    public interface Callback {
        void onAuthorized(String accessToken, String accountName);
        void onFailure(Failure failure, String message, Throwable error);
    }
}
