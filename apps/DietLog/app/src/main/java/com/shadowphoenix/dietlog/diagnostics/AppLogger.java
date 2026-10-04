package com.shadowphoenix.dietlog.diagnostics;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class AppLogger {
    private static final long MAX_LOG_BYTES = 2L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static File logFile;

    private AppLogger() {}

    public static void init(Context context) {
        synchronized (LOCK) {
            File dir = new File(context.getFilesDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            logFile = new File(dir, "app.log");
        }
    }

    public static void i(String message) {
        write("INFO", message, null);
    }

    public static void e(String message, Throwable error) {
        write("ERROR", message, error);
    }

    public static File getLogFile() {
        return logFile;
    }

    private static void write(String level, String message, Throwable error) {
        synchronized (LOCK) {
            if (logFile == null) return;
            rotateIfNeeded();
            StringBuilder line = new StringBuilder()
                    .append(Instant.now()).append(' ')
                    .append(level).append(' ')
                    .append('[').append(Thread.currentThread().getName()).append("] ")
                    .append(message == null ? "" : message)
                    .append('\n');
            if (error != null) {
                line.append(error.getClass().getName()).append(": ")
                        .append(error.getMessage() == null ? "" : error.getMessage()).append('\n');
                for (StackTraceElement element : error.getStackTrace()) {
                    line.append("  at ").append(element).append('\n');
                }
            }
            try (FileOutputStream out = new FileOutputStream(logFile, true)) {
                out.write(line.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Diagnostics must never crash the app.
            }
        }
    }

    private static void rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_LOG_BYTES) return;
        File previous = new File(logFile.getParentFile(), "app.log.1");
        if (previous.exists()) previous.delete();
        logFile.renameTo(previous);
    }
}
