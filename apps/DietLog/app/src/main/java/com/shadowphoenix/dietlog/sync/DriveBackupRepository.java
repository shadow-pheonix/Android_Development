package com.shadowphoenix.dietlog.sync;

import com.shadowphoenix.dietlog.sync.drive.DriveFileMetadata;
import com.shadowphoenix.dietlog.sync.drive.DriveRestClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Uploads the current snapshot into this device's isolated appDataFolder file. */
public final class DriveBackupRepository {
    private final DriveRestClient client = new DriveRestClient();

    public DriveFileMetadata uploadDeviceSnapshot(
            String accessToken, SyncCoordinator.State state, byte[] snapshot, String knownFileId)
            throws IOException {
        String name = fileName(state.deviceId);
        Map<String, String> properties = new HashMap<>();
        properties.put("kind", "device_snapshot");
        properties.put("schema", Integer.toString(BackupSnapshotCodec.SCHEMA_VERSION));
        properties.put("deviceId", state.deviceId);
        properties.put("revision", Long.toString(state.localRevision));

        String fileId = knownFileId == null ? "" : knownFileId.trim();
        if (!fileId.isEmpty()) {
            try {
                return client.updateAppDataFile(accessToken, fileId, name, snapshot, properties);
            } catch (DriveRestClient.DriveHttpException e) {
                if (e.statusCode != 404) throw e;
                // The cached id may refer to a file the user or Drive removed. Rediscover/create.
            }
        }

        List<DriveFileMetadata> files = client.listAppDataFiles(accessToken);
        for (DriveFileMetadata file : files) {
            if (name.equals(file.name)
                    && state.deviceId.equals(file.appProperties.optString("deviceId", ""))) {
                return client.updateAppDataFile(
                        accessToken, file.id, name, snapshot, properties);
            }
        }
        return client.createAppDataFile(accessToken, name, snapshot, properties);
    }

    public static String fileName(String deviceId) {
        return "dietlog-device-" + deviceId + ".json.gz";
    }
}
