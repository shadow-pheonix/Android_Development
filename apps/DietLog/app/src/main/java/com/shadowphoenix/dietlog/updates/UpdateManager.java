package com.shadowphoenix.dietlog.updates;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import com.shadowphoenix.dietlog.BuildConfig;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One process-wide updater shared by both sections; all network/disk validation is off the UI. */
public final class UpdateManager {
    private static UpdateManager instance;
    private final Context app;
    private final SharedPreferences prefs;
    private final UpdateRepository repository;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicFile readyRecord;
    private UpdateRelease release;
    private File apk;
    private String status = "checking", message = "Checking saved update…";
    private int progress;
    private boolean busy = true;

    public static synchronized UpdateManager get(Context context) {
        if (instance == null) instance = new UpdateManager(context.getApplicationContext());
        return instance;
    }

    private UpdateManager(Context context) {
        app = context;
        prefs = app.getSharedPreferences("dietlog_updates", Context.MODE_PRIVATE);
        repository = new UpdateRepository(app);
        readyRecord = new AtomicFile(new File(app.getCacheDir(), "updates/ready.json"));
        io.execute(
                () -> {
                    try {
                        if (readyRecord.getBaseFile().isFile()) {
                            if (readyRecord.getBaseFile().length() > 16384)
                                throw new java.io.IOException("Invalid saved update");
                            byte[] bytes = readyRecord.readFully();
                            if (bytes.length > 16384)
                                throw new java.io.IOException("Invalid saved update");
                            UpdateRelease saved =
                                    UpdateRelease.fromJson(
                                            new JSONObject(
                                                    new String(bytes, StandardCharsets.UTF_8)));
                            File file =
                                    new File(
                                            app.getCacheDir(),
                                            "updates/" + saved.apkSha256 + ".apk");
                            ApkVerifier.validate(app, file, saved);
                            synchronized (this) {
                                release = saved;
                                apk = file;
                                status = "ready";
                                message = "Update downloaded and verified. Ready to install.";
                                progress = 100;
                            }
                        } else {
                            synchronized (this) {
                                status = "idle";
                                message = "Updates come directly from this app’s GitHub release.";
                            }
                        }
                    } catch (Exception e) {
                        readyRecord.delete();
                        synchronized (this) {
                            status = "idle";
                            message = "Check for the latest update.";
                        }
                        log("Saved update discarded", e);
                    } finally {
                        restoreAvailable();
                        synchronized (this) {
                            busy = false;
                        }
                        check(false);
                    }
                });
    }

    private void restoreAvailable() {
        synchronized (this) {
            if (release != null) return;
        }
        try {
            String json = prefs.getString("availableRelease", "");
            if (json.isEmpty()) return;
            if (json.length() > 16384) throw new java.io.IOException("Invalid saved release");
            UpdateRelease saved = UpdateRelease.fromJson(new JSONObject(json));
            if (!app.getPackageName().equals(saved.packageName)
                    || saved.versionCode <= BuildConfig.VERSION_CODE) {
                prefs.edit().remove("availableRelease").apply();
                return;
            }
            synchronized (this) {
                release = saved;
                status = "available";
                message = "A new version is available. Download it when you’re ready.";
            }
        } catch (Exception e) {
            prefs.edit().remove("availableRelease").apply();
            log("Saved release discarded", e);
        }
    }

    public static final class State {
        public final String status, message, versionName;
        public final int progress;
        public final boolean busy, available, ready, automaticChecks;

        private State(
                String status,
                String message,
                String versionName,
                int progress,
                boolean busy,
                boolean available,
                boolean ready,
                boolean automaticChecks) {
            this.status = status;
            this.message = message;
            this.versionName = versionName;
            this.progress = progress;
            this.busy = busy;
            this.available = available;
            this.ready = ready;
            this.automaticChecks = automaticChecks;
        }
    }

    public synchronized State snapshot() {
        return new State(
                status,
                message,
                release == null ? "" : release.versionName,
                progress,
                busy,
                release != null,
                apk != null,
                prefs.getBoolean("automatic", true));
    }

    public void setAutomaticChecks(boolean enabled) {
        prefs.edit().putBoolean("automatic", enabled).apply();
        if (enabled) check(false);
    }

    /** Called on foreground entry; no APK is downloaded until the user requests it. */
    public synchronized void check(boolean manual) {
        if (busy) return;
        long now = System.currentTimeMillis();
        long attempt = prefs.getLong("lastAttempt", 0), success = prefs.getLong("lastSuccess", 0);
        if (!manual
                && (!prefs.getBoolean("automatic", true)
                        || (now >= attempt && now - attempt < 15 * 60_000L)
                        || (now >= success && now - success < 6 * 60 * 60_000L))) return;
        busy = true;
        status = "checking";
        message = "Checking GitHub for updates…";
        prefs.edit().putLong("lastAttempt", now).apply();
        io.execute(
                () -> {
                    try {
                        UpdateRelease found =
                                repository.check(app.getPackageName(), BuildConfig.VERSION_CODE);
                        synchronized (this) {
                            if (found == null) {
                                clearReady();
                                release = null;
                                status = "current";
                                message = "You’re using the latest available version.";
                            } else {
                                if (release == null || !release.apkSha256.equals(found.apkSha256))
                                    clearReady();
                                release = found;
                                status = apk == null ? "available" : "ready";
                                message =
                                        apk == null
                                                ? "A new version is available. Download it when"
                                                        + " you’re ready."
                                                : "Update downloaded and verified. Ready to"
                                                        + " install.";
                            }
                            SharedPreferences.Editor saved =
                                    prefs.edit().putLong("lastSuccess", System.currentTimeMillis());
                            if (found == null) saved.remove("availableRelease");
                            else saved.putString("availableRelease", found.toJson().toString());
                            saved.apply();
                        }
                        AppLogger.i("Update check completed");
                    } catch (Exception e) {
                        synchronized (this) {
                            status = "error";
                            message =
                                    "Couldn’t check for updates. Check your connection and try"
                                            + " again.";
                        }
                        log("Update check failed", e);
                    } finally {
                        synchronized (this) {
                            busy = false;
                        }
                    }
                });
    }

    public synchronized void download() {
        if (busy || release == null) return;
        final UpdateRelease target = release;
        busy = true;
        cancelled.set(false);
        status = "downloading";
        progress = 0;
        message = "Downloading update. You can keep using the app.";
        io.execute(
                () -> {
                    File downloaded = null;
                    try {
                        downloaded =
                                repository.download(
                                        target,
                                        (done, total) -> {
                                            synchronized (this) {
                                                progress =
                                                        total > 0
                                                                ? (int)
                                                                        Math.min(
                                                                                99,
                                                                                done * 100 / total)
                                                                : 0;
                                            }
                                        },
                                        cancelled::get);
                        if (cancelled.get()) throw new java.io.InterruptedIOException("Cancelled");
                        synchronized (this) {
                            status = "verifying";
                            message = "Verifying the update…";
                        }
                        ApkVerifier.validate(app, downloaded, target);
                        if (cancelled.get()) throw new java.io.InterruptedIOException("Cancelled");
                        persistReady(target);
                        synchronized (this) {
                            apk = downloaded;
                            status = "ready";
                            progress = 100;
                            message = "Update downloaded and verified. Ready to install.";
                        }
                        AppLogger.i("Update download verified");
                    } catch (Exception e) {
                        if (downloaded != null) downloaded.delete();
                        synchronized (this) {
                            apk = null;
                            readyRecord.delete();
                            status = cancelled.get() ? "available" : "error";
                            message =
                                    cancelled.get()
                                            ? "Download cancelled. You can try again later."
                                            : "Couldn’t download or verify this update. Please"
                                                    + " check your connection and retry.";
                        }
                        log("Update download stopped", e);
                    } finally {
                        synchronized (this) {
                            busy = false;
                        }
                    }
                });
    }

    public void cancel() {
        cancelled.set(true);
    }

    public synchronized File readyFile() {
        return apk;
    }

    public synchronized UpdateRelease release() {
        return release;
    }

    public synchronized void invalidateDownload(String reason) {
        if (busy) return;
        clearReady();
        status = "error";
        message = reason;
    }

    public interface InstallCallback {
        void onReady(File file);

        void onError(String message);
    }

    public synchronized void prepareInstall(InstallCallback callback) {
        if (busy) {
            main.post(() -> callback.onError("Please wait for the current update task."));
            return;
        }
        final File file = apk;
        final UpdateRelease target = release;
        if (file == null || target == null) {
            main.post(() -> callback.onError("Download the update first."));
            return;
        }
        busy = true;
        status = "verifying";
        message = "Checking the APK before installation…";
        io.execute(
                () -> {
                    try {
                        ApkVerifier.validate(app, file, target);
                        synchronized (this) {
                            busy = false;
                            status = "ready";
                            message = "Confirm the update in Android’s installer.";
                        }
                        main.post(() -> callback.onReady(file));
                    } catch (Exception e) {
                        String text = "This update could not be verified. Download it again.";
                        synchronized (this) {
                            clearReady();
                            busy = false;
                            status = "error";
                            message = text;
                        }
                        log("Update install validation failed", e);
                        main.post(() -> callback.onError(text));
                    }
                });
    }

    private void persistReady(UpdateRelease target) throws Exception {
        File parent = readyRecord.getBaseFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new java.io.IOException("Cannot save update");
        FileOutputStream out = null;
        try {
            out = readyRecord.startWrite();
            out.write(target.toJson().toString().getBytes(StandardCharsets.UTF_8));
            readyRecord.finishWrite(out);
        } catch (Exception e) {
            if (out != null) readyRecord.failWrite(out);
            throw e;
        }
    }

    private void clearReady() {
        if (apk != null) apk.delete();
        apk = null;
        readyRecord.delete();
        progress = 0;
    }

    private static void log(String event, Exception error) {
        // Redirect URLs can carry signed query strings: never log exception messages or URLs.
        AppLogger.i(event + " type=" + error.getClass().getSimpleName());
    }
}
