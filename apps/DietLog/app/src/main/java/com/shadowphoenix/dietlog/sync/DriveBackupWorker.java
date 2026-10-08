package com.shadowphoenix.dietlog.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.profile.ProfilePreferences;
import com.shadowphoenix.dietlog.profile.StartupPreferences;
import com.shadowphoenix.dietlog.sync.drive.DriveFileMetadata;
import com.shadowphoenix.dietlog.sync.drive.DriveRestClient;
import com.shadowphoenix.dietlog.sync.drive.GoogleDriveAuthorization;

import org.json.JSONException;

import java.io.IOException;

/** Performs one background snapshot upload. User mutations always remain local-first. */
public final class DriveBackupWorker extends Worker {
    public DriveBackupWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (new StartupPreferences(context).needsDriveRestore(true,
                new ProfilePreferences(context).hasCompletedPersonalProfile())) {
            AppLogger.i("Drive upload deferred until startup recovery finishes");
            return Result.retry();
        }
        DriveSyncStatusStore status = new DriveSyncStatusStore(context);
        status.markAttempt();
        try {
            GoogleDriveAuthorization.TokenResult token =
                    GoogleDriveAuthorization.requestSilentTokenBlocking(context);
            SyncCoordinator.State beforeMerge = SyncCoordinator.getState(context);
            DriveRestoreManager.MergeResult merge = new DriveRestoreManager().mergeDriveSnapshots(
                    context, token.accessToken, beforeMerge.deviceId, false);
            if (merge.snapshotsApplied > 0) {
                status.markRemoteMerge(merge.snapshotsApplied, merge.changed);
            }
            if (merge.changed) SupabaseBackupScheduler.scheduleImmediate(context);

            SyncCoordinator.State state = SyncCoordinator.getState(context);
            DriveSyncStatusStore.State prior = status.getState();
            if (!state.dirty && state.localRevision <= prior.lastSuccessRevision) {
                return Result.success();
            }

            byte[] snapshot = BackupSnapshotCodec.create(context, state);
            DriveBackupRepository repository = new DriveBackupRepository();
            DriveFileMetadata uploaded = repository.uploadDeviceSnapshot(
                    token.accessToken, state, snapshot, status.getRemoteFileId());
            try {
                repository.createHistoryIfDue(token.accessToken, state, snapshot);
            } catch (IOException historyError) {
                // Current sync remains authoritative; history is an additional recovery layer.
                AppLogger.e("Drive history snapshot failed", historyError);
            }
            boolean exactRevision = SyncCoordinator.markRevisionSynced(context, state.localRevision);
            status.markSuccess(state.localRevision, uploaded.id);
            AppLogger.i("Drive backup uploaded revision=" + state.localRevision
                    + " exact=" + exactRevision + " bytes=" + snapshot.length);
            return exactRevision ? Result.success() : Result.retry();
        } catch (GoogleDriveAuthorization.AuthorizationException e) {
            boolean reauth = e.failure == GoogleDriveAuthorization.Failure.REAUTH_REQUIRED
                    || e.failure == GoogleDriveAuthorization.Failure.AUTHORIZATION_FAILED;
            status.markError(e.getMessage(), reauth);
            AppLogger.e("Drive backup authorization failed: " + e.failure, e);
            return reauth ? Result.failure() : Result.retry();
        } catch (DriveRestClient.DriveHttpException e) {
            boolean reauth = e.statusCode == 401 || e.statusCode == 403;
            status.markError("Google Drive returned HTTP " + e.statusCode, reauth);
            AppLogger.e("Drive backup HTTP failure status=" + e.statusCode, e);
            if (reauth) return Result.failure();
            if (e.statusCode == 408 || e.statusCode == 429 || e.statusCode >= 500) {
                return Result.retry();
            }
            return Result.failure();
        } catch (IOException | JSONException e) {
            status.markError("Drive backup failed: " + e.getClass().getSimpleName(), false);
            AppLogger.e("Drive backup failed", e);
            return Result.retry();
        } catch (RuntimeException e) {
            status.markError("Drive backup failed: " + e.getClass().getSimpleName(), false);
            AppLogger.e("Drive backup runtime failure", e);
            return Result.failure();
        }
    }
}
