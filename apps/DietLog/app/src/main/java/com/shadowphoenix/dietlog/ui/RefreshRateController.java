package com.shadowphoenix.dietlog.ui;

import android.app.Activity;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;

import java.util.Locale;

public final class RefreshRateController {
    static final float TARGET_REFRESH_RATE_HZ = 120.0f;
    private static final float MODE_MATCH_TOLERANCE_HZ = 1.0f;

    private RefreshRateController() {}

    public static void apply(Activity activity, String reason) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode currentMode = display.getMode();
        Display.Mode[] modes = display.getSupportedModes();

        int preferredModeId = chooseExactTargetModeId(
                currentMode.getPhysicalWidth(), currentMode.getPhysicalHeight(),
                TARGET_REFRESH_RATE_HZ, modes);

        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        attributes.preferredRefreshRate = TARGET_REFRESH_RATE_HZ;
        attributes.preferredDisplayModeId = preferredModeId;
        activity.getWindow().setAttributes(attributes);

        View decor = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 35) {
            activity.getWindow().setFrameRateBoostOnTouchEnabled(true);
            activity.getWindow().setFrameRatePowerSavingsBalanced(false);
            requestFrameRateRecursively(decor, TARGET_REFRESH_RATE_HZ);
        }
        decor.invalidate();

        logState(activity, reason + "/apply", preferredModeId);
        decor.postDelayed(() -> logState(activity, reason + "/verify-250ms", preferredModeId), 250L);
        decor.postDelayed(() -> logState(activity, reason + "/verify-1000ms", preferredModeId), 1000L);
        if ("resume".equals(reason)) measureVsync(reason);
    }

    static int chooseExactTargetModeId(int currentWidth, int currentHeight, float targetRate, Display.Mode[] modes) {
        if (modes == null || modes.length == 0) return 0;
        int[] modeIds = new int[modes.length];
        int[] widths = new int[modes.length];
        int[] heights = new int[modes.length];
        float[] rates = new float[modes.length];
        for (int i = 0; i < modes.length; i++) {
            modeIds[i] = modes[i].getModeId();
            widths[i] = modes[i].getPhysicalWidth();
            heights[i] = modes[i].getPhysicalHeight();
            rates[i] = modes[i].getRefreshRate();
        }
        return chooseExactTargetModeId(currentWidth, currentHeight, targetRate, modeIds, widths, heights, rates);
    }

    static int chooseExactTargetModeId(int currentWidth, int currentHeight, float targetRate,
                                       int[] modeIds, int[] widths, int[] heights, float[] rates) {
        if (modeIds == null || widths == null || heights == null || rates == null) return 0;
        int count = Math.min(Math.min(modeIds.length, widths.length), Math.min(heights.length, rates.length));
        int bestModeId = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (widths[i] != currentWidth || heights[i] != currentHeight) continue;
            float distance = Math.abs(rates[i] - targetRate);
            if (distance <= MODE_MATCH_TOLERANCE_HZ && distance < bestDistance) {
                bestDistance = distance;
                bestModeId = modeIds[i];
            }
        }
        return bestModeId;
    }

    private static void requestFrameRateRecursively(View view, float frameRate) {
        view.setRequestedFrameRate(frameRate);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            requestFrameRateRecursively(group.getChildAt(i), frameRate);
        }
    }

    private static void logState(Activity activity, String reason, int requestedModeId) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Display.Mode activeMode = display.getMode();
        WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        float viewRequest = 0f;
        if (Build.VERSION.SDK_INT >= 35) {
            viewRequest = activity.getWindow().getDecorView().getRequestedFrameRate();
        }
        boolean touchBoost = false;
        boolean powerSavingsBalanced = false;
        if (Build.VERSION.SDK_INT >= 35) {
            touchBoost = activity.getWindow().getFrameRateBoostOnTouchEnabled();
            powerSavingsBalanced = activity.getWindow().isFrameRatePowerSavingsBalanced();
        }
        PowerManager powerManager = activity.getSystemService(PowerManager.class);
        boolean powerSaveMode = powerManager != null && powerManager.isPowerSaveMode();
        float systemPeak = Settings.System.getFloat(activity.getContentResolver(), "peak_refresh_rate", -1f);
        float systemMin = Settings.System.getFloat(activity.getContentResolver(), "min_refresh_rate", -1f);
        AppLogger.i(String.format(Locale.US,
                "RefreshRate reason=%s target=%.1fHz active=%.2fHz activeMode=id%d %dx%d@%.2fHz " +
                        "windowPreferred=%.1fHz preferredModeId=%d selected120ModeId=%d decorRequested=%.1fHz " +
                        "touchBoost=%s powerSavingsBalanced=%s powerSave=%s systemPeak=%.2f systemMin=%.2f supported=%s",
                reason,
                TARGET_REFRESH_RATE_HZ,
                display.getRefreshRate(),
                activeMode.getModeId(), activeMode.getPhysicalWidth(), activeMode.getPhysicalHeight(), activeMode.getRefreshRate(),
                attrs.preferredRefreshRate,
                attrs.preferredDisplayModeId,
                requestedModeId,
                viewRequest,
                touchBoost,
                powerSavingsBalanced,
                powerSaveMode,
                systemPeak,
                systemMin,
                describeModes(display.getSupportedModes())));
    }

    private static void measureVsync(String reason) {
        Choreographer choreographer = Choreographer.getInstance();
        final int targetIntervals = 60;
        choreographer.postFrameCallback(new Choreographer.FrameCallback() {
            long firstFrameNanos = 0L;
            long lastFrameNanos = 0L;
            int intervals = 0;

            @Override
            public void doFrame(long frameTimeNanos) {
                if (firstFrameNanos == 0L) {
                    firstFrameNanos = frameTimeNanos;
                } else if (frameTimeNanos > lastFrameNanos) {
                    intervals++;
                }
                lastFrameNanos = frameTimeNanos;
                if (intervals < targetIntervals) {
                    choreographer.postFrameCallback(this);
                    return;
                }
                long elapsed = lastFrameNanos - firstFrameNanos;
                double measuredHz = elapsed > 0L ? intervals * 1_000_000_000.0 / elapsed : 0.0;
                AppLogger.i(String.format(Locale.US,
                        "RefreshRate reason=%s/vsync-sample intervals=%d measuredVsync=%.2fHz",
                        reason, intervals, measuredHz));
            }
        });
    }

    static String describeModes(Display.Mode[] modes) {
        if (modes == null || modes.length == 0) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) out.append(", ");
            Display.Mode mode = modes[i];
            out.append(String.format(Locale.US, "id%d:%dx%d@%.2f", mode.getModeId(),
                    mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate()));
        }
        return out.append(']').toString();
    }
}
