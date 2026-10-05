package com.shadowphoenix.dietlog.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RefreshRateControllerTest {
    @Test public void selects120WhenSupported() {
        assertEquals(120.0f, RefreshRateController.chooseClosestRefreshRate(120.0f, new float[]{60.0f, 120.0f}), 0.01f);
    }

    @Test public void fallsBackToNearestSupportedRate() {
        assertEquals(90.0f, RefreshRateController.chooseClosestRefreshRate(120.0f, new float[]{60.0f, 90.0f}), 0.01f);
        assertEquals(144.0f, RefreshRateController.chooseClosestRefreshRate(120.0f, new float[]{60.0f, 144.0f}), 0.01f);
    }

    @Test public void prefersHigherRateWhenEquallyClose() {
        assertEquals(130.0f, RefreshRateController.chooseClosestRefreshRate(120.0f, new float[]{110.0f, 130.0f}), 0.01f);
    }

    @Test public void emptyModesKeepsRequestedRate() {
        assertEquals(120.0f, RefreshRateController.chooseClosestRefreshRate(120.0f, new float[0]), 0.01f);
    }
}
