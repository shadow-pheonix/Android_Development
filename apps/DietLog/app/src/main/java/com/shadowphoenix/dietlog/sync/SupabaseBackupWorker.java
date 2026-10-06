package com.shadowphoenix.dietlog.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseAuthClient;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseConnectionStore;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseDataClient;

import org.json.JSONException;

import java.io.IOException;

/** Performs one authenticated Supabase merge/upload pass. Local data remains authoritative. */
public final class SupabaseBackupWorker extends Worker {
    public SupabaseBackupWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        SupabaseSyncStatusStore status = new SupabaseSyncStatusStore(context);
        SupabaseConnectionStore connection = new SupabaseConnectionStore(context);
        status.markAttempt();
        try {
            SupabaseConnectionStore.State existing = connection.getState();
            String refreshToken = connection.loadRefreshToken();
            SupabaseAuthClient.Session session = new SupabaseAuthClient().refresh(refreshToken);
            String userId = session.userId.isEmpty() ? existing.userId : session.userId;
            String email = session.email.isEmpty() ? existing.email : session.email;
            connection.saveSession(email, userId, session.refreshToken);

            SyncCoordinator.State beforeMerge = SyncCoordinator.getState(context);
            SupabaseRestoreManager.MergeResult merge = new SupabaseRestoreManager()
                    .mergeSupabaseSnapshots(context, session.accessToken, beforeMerge.deviceId, false);
            if (merge.snapshotsApplied > 0) status.markRemoteMerge(merge.snapshotsApplied);
            if (merge.changed) DriveBackupScheduler.scheduleImmediate(context);

            SyncCoordinator.State state = SyncCoordinator.getState(context);
            SupabaseSyncStatusStore.State prior = status.getState();
            if (!state.dirty && state.localRevision <= prior.lastSuccessRevision) {
                return Result.success();
            }

            byte[] snapshot = BackupSnapshotCodec.create(context, state);
            SupabaseBackupRepository repository = new SupabaseBackupRepository();
            repository.uploadDeviceSnapshot(
                    session.accessToken, userId, state, snapshot);
            try {
                repository.createHistoryIfDue(
                        session.accessToken, userId, state, snapshot);
            } catch (IOException historyError) {
                AppLogger.e("Supabase history snapshot failed", historyError);
            }

            boolean exactRevision = SyncCoordinator.markRevisionSynced(
                    context, state.localRevision);
            status.markSuccess(state.localRevision);
            AppLogger.i("Supabase backup uploaded revision=" + state.localRevision
                    + " exact=" + exactRevision + " bytes=" + snapshot.length);
            return exactRevision ? Result.success() : Result.retry();
        } catch (SupabaseAuthClient.AuthHttpException e) {
            boolean reauth = e.statusCode == 400 || e.statusCode == 401 || e.statusCode == 403;
            status.markError(safeMessage(e, "Supabase sign-in expired"), reauth);
            AppLogger.e("Supabase session refresh failed status=" + e.statusCode, e);
            return reauth ? Result.failure() : Result.retry();
        } catch (SupabaseDataClient.DataHttpException e) {
            boolean reauth = e.statusCode == 401;
            status.markError(safeMessage(e, "Supabase sync failed"), reauth);
            AppLogger.e("Supabase Data API failure status=" + e.statusCode, e);
            if (reauth) return Result.failure();
            return e.isRetryable() ? Result.retry() : Result.failure();
        } catch (IllegalStateException e) {
            status.markError(safeMessage(e, "Supabase is not configured"), false);
            AppLogger.e("Supabase configuration failure", e);
            return Result.failure();
        } catch (IOException | JSONException e) {
            status.markError("Supabase backup failed: " + e.getClass().getSimpleName(), false);
            AppLogger.e("Supabase backup failed", e);
            return Result.retry();
        } catch (Exception e) {
            // Keystore/session corruption needs a fresh sign-in rather than an endless retry loop.
            status.markError("Supabase session could not be unlocked. Sign in again.", true);
            AppLogger.e("Supabase secure session failure", e);
            return Result.failure();
        }
    }

    private static String safeMessage(Throwable error, String fallback) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) return fallback;
        String safe = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return safe.length() <= 300 ? safe : safe.substring(0, 300);
    }
}
