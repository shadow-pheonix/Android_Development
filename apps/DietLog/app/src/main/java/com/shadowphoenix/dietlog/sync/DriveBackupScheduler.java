package com.shadowphoenix.dietlog.sync;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.shadowphoenix.dietlog.sync.drive.DriveConnectionStore;

import java.util.concurrent.TimeUnit;

/** Schedules reliable, network-constrained Drive backups without blocking user writes. */
public final class DriveBackupScheduler {
    private static final String UNIQUE_WORK = "dietlog-google-drive-backup";
    private static final String UNIQUE_PERIODIC_WORK = "dietlog-google-drive-periodic-sync";
    private static final long DEBOUNCE_SECONDS = 20L;
    private static final long PERIODIC_HOURS = 6L;

    private DriveBackupScheduler() {}

    public static void scheduleDebounced(Context context) {
        if (!new DriveConnectionStore(context).getState().connected) return;
        enqueue(context, DEBOUNCE_SECONDS, ExistingWorkPolicy.KEEP);
    }

    public static void scheduleImmediate(Context context) {
        if (!new DriveConnectionStore(context).getState().connected) return;
        enqueue(context, 0L, ExistingWorkPolicy.REPLACE);
    }

    public static void schedulePeriodic(Context context) {
        if (!new DriveConnectionStore(context).getState().connected) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DriveBackupWorker.class, PERIODIC_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK, androidx.work.ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(UNIQUE_WORK);
        manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK);
    }

    private static void enqueue(
            Context context, long delaySeconds, ExistingWorkPolicy policy) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(DriveBackupWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS);
        if (delaySeconds > 0L) builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS);
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, policy, builder.build());
    }
}
