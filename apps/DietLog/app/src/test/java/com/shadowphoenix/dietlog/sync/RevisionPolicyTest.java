package com.shadowphoenix.dietlog.sync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RevisionPolicyTest {
    @Test
    public void cleanInstallAdoptRemoteBaseline() {
        assertEquals(6L , RevisionPolicy.reconcile(0L, false, 6L));
    }

    @Test
    public void dirtyInstallAdvancesPastRemoteBaseline() {
        assertEquals(7L, RevisionPolicy.reconcile(1L, true, 6L));
    }

    @Test
    public void dirtyRevisionEqualToRemoteAdvancesOnce() {
        assertEquals(7L, RevisionPolicy.reconcile(6L, true, 6L));
    }

    @Test
    public void localRevisionAheadOfRemoteIsPreserved() {
        assertEquals(9L, RevisionPolicy.reconcile(9L, true, 6L));
    }

    @Test
    public void firstDirtySnapshotStartsAtOne() {
        assertEquals(1L, RevisionPolicy.reconcile(0L, true, 0L));
    }
}
