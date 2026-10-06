package com.shadowphoenix.dietlog.sync;

import com.shadowphoenix.dietlog.sync.supabase.SupabaseDataClient;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseSnapshotMetadata;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Owns the RLS-protected current snapshot and bounded recovery history in Supabase. */
public final class SupabaseBackupRepository {
    public static final String KIND_CURRENT = "device_snapshot";
    public static final String KIND_HISTORY = "history_snapshot";
    private static final long HISTORY_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_HISTORY_PER_DEVICE = 7;

    private final SupabaseDataClient client = new SupabaseDataClient();

    public void uploadDeviceSnapshot(
            String accessToken, String userId, SyncCoordinator.State state, byte[] snapshot)
            throws IOException {
        long now = System.currentTimeMillis();
        client.upsertCurrent(
                accessToken,
                userId,
                currentKey(state.deviceId),
                state.deviceId,
                KIND_CURRENT,
                state.localRevision,
                now,
                BackupSnapshotCodec.SCHEMA_VERSION,
                snapshot);
    }

    /** Writes at most one history snapshot per 24h and keeps the newest seven per device. */
    public void createHistoryIfDue(
            String accessToken, String userId, SyncCoordinator.State state, byte[] snapshot)
            throws IOException {
        long now = System.currentTimeMillis();
        List<SupabaseSnapshotMetadata> history = new ArrayList<>();
        long newest = 0L;
        for (SupabaseSnapshotMetadata row : listSnapshotRows(accessToken)) {
            if (!KIND_HISTORY.equals(row.kind) || !state.deviceId.equals(row.deviceId)) continue;
            history.add(row);
            newest = Math.max(newest, row.createdAt);
        }
        if (newest > 0L && now - newest < HISTORY_INTERVAL_MS) return;

        SupabaseSnapshotMetadata created = new SupabaseSnapshotMetadata(
                historyKey(state.deviceId, state.localRevision, now),
                state.deviceId,
                KIND_HISTORY,
                state.localRevision,
                now,
                BackupSnapshotCodec.SCHEMA_VERSION);
        client.insertHistory(
                accessToken, userId, created.snapshotKey, created.deviceId, created.kind,
                created.revision, created.createdAt, created.schemaVersion, snapshot);
        history.add(created);
        history.sort(Comparator.comparingLong((SupabaseSnapshotMetadata row) -> row.createdAt).reversed());
        for (int i = MAX_HISTORY_PER_DEVICE; i < history.size(); i++) {
            client.deleteSnapshot(accessToken, history.get(i).snapshotKey);
        }
    }

    public List<SupabaseSnapshotMetadata> listSnapshotRows(String accessToken) throws IOException {
        return client.listSnapshots(accessToken);
    }

    public byte[] downloadSnapshot(String accessToken, String snapshotKey) throws IOException {
        return client.downloadSnapshot(accessToken, snapshotKey);
    }

    static String currentKey(String deviceId) {
        return "current:" + deviceId;
    }

    private static String historyKey(String deviceId, long revision, long createdAt) {
        return "history:" + deviceId + ":r" + revision + ":" + createdAt;
    }
}
