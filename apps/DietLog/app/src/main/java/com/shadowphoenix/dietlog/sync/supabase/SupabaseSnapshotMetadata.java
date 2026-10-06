package com.shadowphoenix.dietlog.sync.supabase;

/** Metadata for one RLS-protected DietLog snapshot row in Supabase. */
public final class SupabaseSnapshotMetadata {
    public final String snapshotKey;
    public final String deviceId;
    public final String kind;
    public final long revision;
    public final long createdAt;
    public final int schemaVersion;

    public SupabaseSnapshotMetadata(
            String snapshotKey, String deviceId, String kind,
            long revision, long createdAt, int schemaVersion) {
        this.snapshotKey = snapshotKey == null ? "" : snapshotKey;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.kind = kind == null ? "" : kind;
        this.revision = Math.max(0L, revision);
        this.createdAt = Math.max(0L, createdAt);
        this.schemaVersion = Math.max(0, schemaVersion);
    }
}
