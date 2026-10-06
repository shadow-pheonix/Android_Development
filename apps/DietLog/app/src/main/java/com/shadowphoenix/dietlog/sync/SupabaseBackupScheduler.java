package com.shadowphoenix.dietlog.sync;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.shadowphoenix.dietlog.sync.supabase.SupabaseConfig;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseConnectionStore;

import java.util.concurrent.TimeUnit;

/** Schedules reliable, network-constrained Supabase sync without blocking local writes. */
public final class SupabaseBackupScheduler {
    private static final String UNIQUE_WORK = "dietlog-supabase-backup";
    private static final String UNIQUE_PERIODIC_WORK = "dietlog-supabase-periodic-sync";
    private static final long DEBOUNCE_SECONDS = 20L;
    private static final long PERIODIC_HOURS = 6L;

    private SupabaseBackupScheduler() {}

    public static void scheduleDebounced(Context context) {
        if (!canSync(context)) return;
        enqueue(context, DEBOUNCE_SECONDS, ExistingWorkPolicy.KEEP);
    }

    public static void scheduleImmediate(Context context) {
        if (!canSync(context)) return;
        enqueue(context, 0L, ExistingWorkPolicy.REPLACE);
    }

    public static void schedulePeriodic(Context context) {
        if (!canSync(context)) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                SupabaseBackupWorker.class, PERIODIC_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(UNIQUE_WORK);
        manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK);
    }

    private static boolean canSync(Context context) {
        return SupabaseConfig.isConfigured()
                && new SupabaseConnectionStore(context).getState().connected;
    }

    private static void enqueue(Context context, long delaySeconds, ExistingWorkPolicy policy) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(SupabaseBackupWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS);
        if (delaySeconds > 0L) builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS);
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(UNIQUE_WORK, policy, builder.build());
    }
}
