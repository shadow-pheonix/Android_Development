package com.shadowphoenix.dietlog.sync;

import android.content.Context;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseDataClient;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseSnapshotMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads the best Supabase snapshot per device and delegates entity merging to BackupMergeEngine. */
public final class SupabaseRestoreManager {
    private final SupabaseBackupRepository repository = new SupabaseBackupRepository();

    public MergeResult mergeSupabaseSnapshots(
            Context context, String accessToken, String localDeviceId, boolean includeLocalDevice)
            throws IOException {
        List<SupabaseSnapshotMetadata> rows = repository.listSnapshotRows(accessToken);
        Map<String, List<SupabaseSnapshotMetadata>> byDevice = new HashMap<>();
        for (SupabaseSnapshotMetadata row : rows) {
            if (row.deviceId.isEmpty()) continue;
            if (!includeLocalDevice && row.deviceId.equals(localDeviceId)) continue;
            byDevice.computeIfAbsent(row.deviceId, ignored -> new ArrayList<>()).add(row);
        }

        List<BackupSnapshotCodec.DecodedSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, List<SupabaseSnapshotMetadata>> entry : byDevice.entrySet()) {
            BackupSnapshotCodec.DecodedSnapshot snapshot =
                    loadBestSnapshot(accessToken, entry.getKey(), entry.getValue());
            if (snapshot != null) snapshots.add(snapshot);
        }
        BackupMergeEngine.MergeResult result = BackupMergeEngine.merge(context, snapshots);
        return new MergeResult(result.changed, result.snapshotsApplied);
    }

    private BackupSnapshotCodec.DecodedSnapshot loadBestSnapshot(
            String accessToken, String expectedDeviceId, List<SupabaseSnapshotMetadata> rows)
            throws IOException {
        // Keep current snapshots ahead of history while sorting each group newest-first.
        rows.sort((left, right) -> {
            int leftKind = SupabaseBackupRepository.KIND_CURRENT.equals(left.kind) ? 0 : 1;
            int rightKind = SupabaseBackupRepository.KIND_CURRENT.equals(right.kind) ? 0 : 1;
            if (leftKind != rightKind) return Integer.compare(leftKind, rightKind);
            return Long.compare(right.createdAt, left.createdAt);
        });

        for (SupabaseSnapshotMetadata row : rows) {
            try {
                BackupSnapshotCodec.DecodedSnapshot snapshot = BackupSnapshotCodec.decode(
                        repository.downloadSnapshot(accessToken, row.snapshotKey));
                if (!expectedDeviceId.equals(snapshot.deviceId)) {
                    AppLogger.e("Supabase snapshot device metadata mismatch", null);
                    continue;
                }
                return snapshot;
            } catch (BackupSnapshotCodec.BackupFormatException e) {
                AppLogger.e("Supabase snapshot is unreadable; trying older history", e);
            } catch (SupabaseDataClient.DataHttpException e) {
                if (e.statusCode == 404) continue;
                throw e;
            }
        }
        return null;
    }

    public static final class MergeResult {
        public final boolean changed;
        public final int snapshotsApplied;

        MergeResult(boolean changed, int snapshotsApplied) {
            this.changed = changed;
            this.snapshotsApplied = snapshotsApplied;
        }
    }
}
