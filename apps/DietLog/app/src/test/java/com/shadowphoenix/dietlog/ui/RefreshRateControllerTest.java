package com.shadowphoenix.dietlog.ui;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class RefreshRateControllerTest {
    @Test public void picksExact120ModeAtCurrentResolution() {
        assertEquals(2, RefreshRateController.chooseExactTargetModeId(1440, 3200, 120.0f,
                new int[]{1, 2, 3}, new int[]{1440, 1440, 1080}, new int[]{3200, 3200, 2400}, new float[]{60.0f, 120.0f, 120.0f}));
    }

    @Test public void acceptsFractional120Mode() {
        assertEquals(7, RefreshRateController.chooseExactTargetModeId(1440, 3200, 120.0f,
                new int[]{7}, new int[]{1440}, new int[]{3200}, new float[]{119.88f}));
    }

    @Test public void neverChangesResolutionJustToReach120() {
        assertEquals(0, RefreshRateController.chooseExactTargetModeId(1440, 3200, 120.0f,
                new int[]{1, 2}, new int[]{1440, 1080}, new int[]{3200, 2400}, new float[]{60.0f, 120.0f}));
    }

    @Test public void returnsZeroWhen120IsUnavailable() {
        assertEquals(0, RefreshRateController.chooseExactTargetModeId(1440, 3200, 120.0f,
                new int[]{1, 2}, new int[]{1440, 1440}, new int[]{3200, 3200}, new float[]{60.0f, 90.0f}));
    }

    @Test public void mismatchedArrayLengthsAreBoundedSafely() {
        assertEquals(0, RefreshRateController.chooseExactTargetModeId(1440, 3200, 120.0f,
                new int[]{1, 2}, new int[]{1440}, new int[]{3200}, new float[]{60.0f, 120.0f}));
    }
}
