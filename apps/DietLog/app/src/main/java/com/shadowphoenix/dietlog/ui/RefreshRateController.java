package com.shadowphoenix.dietlog.ui;

import android.app.Activity;
import android.view.Display;
import android.view.WindowManager;

public final class RefreshRateController {
    static final float TARGET_REFRESH_RATE_HZ = 120.0f;
    private static final float EPSILON_HZ = 0.1f;

    private RefreshRateController() {}

    public static float applyPreferredRefreshRate(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode[] modes = display.getSupportedModes();
        float[] supportedRates = new float[modes.length];
        for (int i = 0; i < modes.length; i++) {
            supportedRates[i] = modes[i].getRefreshRate();
        }

        float preferredRate = chooseClosestRefreshRate(TARGET_REFRESH_RATE_HZ, supportedRates);
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        if (Math.abs(attributes.preferredRefreshRate - preferredRate) > EPSILON_HZ) {
            attributes.preferredRefreshRate = preferredRate;
            activity.getWindow().setAttributes(attributes);
        }
        return preferredRate;
    }

    static float chooseClosestRefreshRate(float targetRate, float[] supportedRates) {
        if (supportedRates == null || supportedRates.length == 0) return targetRate;

        float bestRate = supportedRates[0];
        float bestDistance = Math.abs(bestRate - targetRate);
        for (int i = 1; i < supportedRates.length; i++) {
            float candidate = supportedRates[i];
            float distance = Math.abs(candidate - targetRate);
            if (distance < bestDistance || (Math.abs(distance - bestDistance) <= EPSILON_HZ && candidate > bestRate)) {
                bestRate = candidate;
                bestDistance = distance;
            }
        }
        return bestRate;
    }
}
