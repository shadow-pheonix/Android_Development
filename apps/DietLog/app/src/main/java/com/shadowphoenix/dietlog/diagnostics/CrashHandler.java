package com.shadowphoenix.dietlog.diagnostics;

import android.content.Context;

import com.shadowphoenix.dietlog.BuildConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context context) {
        this.appContext = context.getApplicationContext();
        this.previous = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        AppLogger.e("Uncaught exception", throwable);
        File dir = new File(appContext.getFilesDir(), "diagnostics");
        if (dir.exists() || dir.mkdirs()) {
            File file = new File(dir, "crash.txt");
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8), true)) {
                writer.println("timestamp=" + Instant.now());
                writer.println("thread=" + thread.getName());
                writer.println("version=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
                writer.println("sourceCommit=" + BuildConfig.SOURCE_COMMIT);
                throwable.printStackTrace(writer);
            } catch (Exception ignored) {
                // Best effort only.
            }
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable);
        } else {
            System.exit(10);
        }
    }
}
