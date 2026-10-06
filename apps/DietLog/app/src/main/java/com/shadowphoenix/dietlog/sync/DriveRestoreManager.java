package com.shadowphoenix.dietlog.sync;

import android.content.Context;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.sync.drive.DriveFileMetadata;
import com.shadowphoenix.dietlog.sync.drive.DriveRestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads the best Drive snapshot per device and delegates entity merging to BackupMergeEngine. */
public final class DriveRestoreManager {
    private final DriveBackupRepository repository = new DriveBackupRepository();

    /**
     * Background mode excludes this device's own snapshot. Manual restore includes it so a fresh
     * installation can recover the previous installation even if it later reuses a device id.
     */
    public MergeResult mergeDriveSnapshots(
            Context context, String accessToken, String localDeviceId, boolean includeLocalDevice)
            throws IOException {
        List<DriveFileMetadata> files = repository.listSnapshotFiles(accessToken);
        Map<String, List<DriveFileMetadata>> byDevice = new HashMap<>();
        for (DriveFileMetadata file : files) {
            String deviceId = DriveBackupRepository.deviceId(file);
            if (deviceId.isEmpty()) continue;
            if (!includeLocalDevice && deviceId.equals(localDeviceId)) continue;
            byDevice.computeIfAbsent(deviceId, ignored -> new ArrayList<>()).add(file);
        }

        List<BackupSnapshotCodec.DecodedSnapshot> snapshots = new ArrayList<>();
        for (Map.Entry<String, List<DriveFileMetadata>> entry : byDevice.entrySet()) {
            BackupSnapshotCodec.DecodedSnapshot snapshot =
                    loadBestSnapshot(accessToken, entry.getKey(), entry.getValue());
            if (snapshot != null) snapshots.add(snapshot);
        }
        BackupMergeEngine.MergeResult result = BackupMergeEngine.merge(context, snapshots);
        long maxRemoteRevision = 0L;
        for (BackupSnapshotCodec.DecodedSnapshot snapshot : snapshots) {
            maxRemoteRevision = Math.max(maxRemoteRevision, snapshot.revision);
        }
        long localRevision = SyncCoordinator.reconcileRemoteRevision(context, maxRemoteRevision);
        return new MergeResult(result.changed, result.snapshotsApplied,
                maxRemoteRevision, localRevision);
    }

    private BackupSnapshotCodec.DecodedSnapshot loadBestSnapshot(
            String accessToken, String expectedDeviceId, List<DriveFileMetadata> files)
            throws IOException {
        files.sort(Comparator
                .comparingInt((DriveFileMetadata f) ->
                        DriveBackupRepository.KIND_CURRENT.equals(DriveBackupRepository.kind(f)) ? 0 : 1)
                .thenComparing((DriveFileMetadata f) -> f.modifiedTime,
                        Comparator.reverseOrder()));
        for (DriveFileMetadata file : files) {
            try {
                BackupSnapshotCodec.DecodedSnapshot snapshot = BackupSnapshotCodec.decode(
                        repository.downloadSnapshot(accessToken, file.id));
                if (!expectedDeviceId.equals(snapshot.deviceId)) {
                    AppLogger.e("Drive snapshot device metadata mismatch", null);
                    continue;
                }
                return snapshot;
            } catch (BackupSnapshotCodec.BackupFormatException e) {
                AppLogger.e("Drive snapshot is unreadable; trying older history", e);
            } catch (DriveRestClient.DriveHttpException e) {
                if (e.statusCode == 404) continue;
                throw e;
            }
        }
        return null;
    }

    public static final class MergeResult {
        public final boolean changed;
        public final int snapshotsApplied;
        public final long maxRemoteRevision;
        public final long localRevision;

        MergeResult(boolean changed, int snapshotsApplied, long maxRemoteRevision,
                    long localRevision) {
            this.changed = changed;
            this.snapshotsApplied = snapshotsApplied;
            this.maxRemoteRevision = maxRemoteRevision;
            this.localRevision = localRevision;
        }
    }
}
