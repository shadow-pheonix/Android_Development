package com.shadowphoenix.dietlog.sync;

/** Pure revision reconciliation rules shared by Drive restore/sync state handling. */
final class RevisionPolicy {
    private RevisionPolicy() {}

    static long reconcile(long localRevision, boolean dirty, long remoteRevision) {
        long local = Math.max(0L, localRevision);
        long remote = Math.max(0L, remoteRevision);
        if (remote < local) return local;
        if (!dirty) return remote;
        return remote == Long.MAX_VALUE ? Long.MAX_VALUE : remote + 1L;
    }
}
