package com.shadowphoenix.dietlog.sync;

import com.shadowphoenix.dietlog.sync.drive.DriveFileMetadata;
import com.shadowphoenix.dietlog.sync.drive.DriveRestClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns current per-device snapshots plus a bounded rolling safety history in appDataFolder. */
public final class DriveBackupRepository {
    public static final String KIND_CURRENT = "device_snapshot";
    public static final String KIND_HISTORY = "history_snapshot";
    private static final long HISTORY_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_HISTORY_PER_DEVICE = 7;

    private final DriveRestClient client = new DriveRestClient();

    public DriveFileMetadata uploadDeviceSnapshot(
            String accessToken, SyncCoordinator.State state, byte[] snapshot, String knownFileId)
            throws IOException {
        String name = fileName(state.deviceId);
        Map<String, String> properties = snapshotProperties(
                KIND_CURRENT, state.deviceId, state.localRevision, System.currentTimeMillis());

        String fileId = knownFileId == null ? "" : knownFileId.trim();
        if (!fileId.isEmpty()) {
            try {
                return client.updateAppDataFile(accessToken, fileId, name, snapshot, properties);
            } catch (DriveRestClient.DriveHttpException e) {
                if (e.statusCode != 404) throw e;
                // Cached id can become stale if Drive removes the file. Rediscover/create below.
            }
        }

        List<DriveFileMetadata> files = listSnapshotFiles(accessToken);
        for (DriveFileMetadata file : files) {
            if (KIND_CURRENT.equals(kind(file))
                    && state.deviceId.equals(deviceId(file))) {
                return client.updateAppDataFile(
                        accessToken, file.id, name, snapshot, properties);
            }
        }
        return client.createAppDataFile(accessToken, name, snapshot, properties);
    }

    /** Writes at most one safety history point per 24 hours and retains seven per device. */
    public void createHistoryIfDue(
            String accessToken, SyncCoordinator.State state, byte[] snapshot) throws IOException {
        long now = System.currentTimeMillis();
        List<DriveFileMetadata> history = new ArrayList<>();
        long newestCreatedAt = 0L;
        for (DriveFileMetadata file : listSnapshotFiles(accessToken)) {
            if (!KIND_HISTORY.equals(kind(file)) || !state.deviceId.equals(deviceId(file))) continue;
            history.add(file);
            newestCreatedAt = Math.max(newestCreatedAt, propertyLong(file, "createdAt"));
        }
        if (newestCreatedAt > 0L && now - newestCreatedAt < HISTORY_INTERVAL_MS) return;

        String name = historyFileName(state.deviceId, state.localRevision, now);
        DriveFileMetadata created = client.createAppDataFile(
                accessToken, name, snapshot,
                snapshotProperties(KIND_HISTORY, state.deviceId, state.localRevision, now));
        history.add(created);
        history.sort(Comparator.comparingLong(
                (DriveFileMetadata file) -> propertyLong(file, "createdAt")).reversed());
        for (int i = MAX_HISTORY_PER_DEVICE; i < history.size(); i++) {
            client.deleteFile(accessToken, history.get(i).id);
        }
    }

    public List<DriveFileMetadata> listSnapshotFiles(String accessToken) throws IOException {
        List<DriveFileMetadata> out = new ArrayList<>();
        for (DriveFileMetadata file : client.listAppDataFiles(accessToken)) {
            String kind = kind(file);
            if (KIND_CURRENT.equals(kind) || KIND_HISTORY.equals(kind)) out.add(file);
        }
        return out;
    }

    public byte[] downloadSnapshot(String accessToken, String fileId) throws IOException {
        return client.downloadFile(accessToken, fileId);
    }

    static String kind(DriveFileMetadata file) {
        return file.appProperties.optString("kind", "");
    }

    static String deviceId(DriveFileMetadata file) {
        return file.appProperties.optString("deviceId", "");
    }

    static long propertyLong(DriveFileMetadata file, String key) {
        try {
            return Long.parseLong(file.appProperties.optString(key, "0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static Map<String, String> snapshotProperties(
            String kind, String deviceId, long revision, long createdAt) {
        Map<String, String> properties = new HashMap<>();
        properties.put("kind", kind);
        properties.put("schema", Integer.toString(BackupSnapshotCodec.SCHEMA_VERSION));
        properties.put("deviceId", deviceId);
        properties.put("revision", Long.toString(revision));
        properties.put("createdAt", Long.toString(createdAt));
        return properties;
    }

    public static String fileName(String deviceId) {
        return "dietlog-device-" + deviceId + ".json.gz";
    }

    private static String historyFileName(String deviceId, long revision, long createdAt) {
        return "dietlog-history-" + deviceId + "-r" + revision + "-" + createdAt + ".json.gz";
    }
}
