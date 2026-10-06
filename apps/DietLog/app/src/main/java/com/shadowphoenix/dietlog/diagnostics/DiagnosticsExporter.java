package com.shadowphoenix.dietlog.diagnostics;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.shadowphoenix.dietlog.BuildConfig;
import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.sync.DriveSyncStatusStore;
import com.shadowphoenix.dietlog.sync.SyncCoordinator;
import com.shadowphoenix.dietlog.sync.SupabaseSyncStatusStore;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseConfig;
import com.shadowphoenix.dietlog.sync.supabase.SupabaseConnectionStore;
import com.shadowphoenix.dietlog.sync.drive.DriveConnectionStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class DiagnosticsExporter {
    public static final class ExportResult {
        public final Uri uri;
        public final String displayPath;

        ExportResult(Uri uri, String displayPath) {
            this.uri = uri;
            this.displayPath = displayPath;
        }
    }

    private DiagnosticsExporter() {}

    public static ExportResult export(Context context, DiaryDbHelper db) throws Exception {
        String stamp = utcStamp();
        String fileName = "FuelNote-diagnostics-" + stamp + ".zip";
        byte[] zip = buildZip(context, db);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Exception rootFailure = null;
            try {
                return writeViaMediaStore(context, fileName, zip, "AppDebug/DietLog/", false);
            } catch (Exception e) {
                rootFailure = e;
                AppLogger.e("Root AppDebug export unavailable; using Downloads fallback", e);
            }
            try {
                return writeViaMediaStore(context, fileName, zip,
                        Environment.DIRECTORY_DOWNLOADS + "/AppDebug/DietLog/", true);
            } catch (Exception fallback) {
                if (rootFailure != null) fallback.addSuppressed(rootFailure);
                throw fallback;
            }
        }

        File root = Environment.getExternalStorageDirectory();
        File dir = new File(root, "AppDebug/DietLog");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Unable to create diagnostics directory: " + dir);
        }
        File outFile = new File(dir, fileName);
        try (OutputStream out = new java.io.FileOutputStream(outFile)) {
            out.write(zip);
        }
        return new ExportResult(Uri.fromFile(outFile), outFile.getAbsolutePath());
    }

    private static ExportResult writeViaMediaStore(Context context, String fileName, byte[] bytes,
                                                   String relativePath, boolean downloadsCollection) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = downloadsCollection
                ? MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("MediaStore insert returned null for " + relativePath);
        boolean success = false;
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Unable to open diagnostics output stream");
                out.write(bytes);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, ready, null, null);
            success = true;
            return new ExportResult(uri, "/storage/emulated/0/" + relativePath + fileName);
        } finally {
            if (!success) resolver.delete(uri, null, null);
        }
    }

    private static byte[] buildZip(Context context, DiaryDbHelper db) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            File log = AppLogger.getLogFile();
            if (log != null && log.exists()) addFile(zip, "app.log", log, 4L * 1024L * 1024L);

            File previousLog = log == null ? null : new File(log.getParentFile(), "app.log.1");
            if (previousLog != null && previousLog.exists()) {
                addFile(zip, "app.log.1", previousLog, 4L * 1024L * 1024L);
            }

            File crash = new File(new File(context.getFilesDir(), "diagnostics"), "crash.txt");
            if (crash.exists()) addFile(zip, "crash.txt", crash, 2L * 1024L * 1024L);
            else addText(zip, "crash.txt", "No retained crash record.\n");

            addText(zip, "device-info.txt", deviceInfo());
            addText(zip, "app-info.txt", appInfo(context));
            addText(zip, "build-info.txt", buildInfo());
            addText(zip, "diagnostics.json", diagnosticState(context, db));
        }
        return bytes.toByteArray();
    }

    private static void addText(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void addFile(ZipOutputStream zip, String name, File file, long maxBytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            long written = 0;
            int read;
            while ((read = in.read(buffer)) != -1 && written < maxBytes) {
                int allowed = (int) Math.min(read, maxBytes - written);
                zip.write(buffer, 0, allowed);
                written += allowed;
            }
        }
        zip.closeEntry();
    }

    private static String deviceInfo() {
        return "manufacturer=" + Build.MANUFACTURER + '\n' +
                "model=" + Build.MODEL + '\n' +
                "device=" + Build.DEVICE + '\n' +
                "android=" + Build.VERSION.RELEASE + '\n' +
                "api=" + Build.VERSION.SDK_INT + '\n' +
                "abis=" + String.join(",", Build.SUPPORTED_ABIS) + '\n';
    }

    private static String appInfo(Context context) {
        return "appName=FuelNote\n" +
                "applicationId=" + context.getPackageName() + '\n' +
                "versionName=" + BuildConfig.VERSION_NAME + '\n' +
                "versionCode=" + BuildConfig.VERSION_CODE + '\n' +
                "buildType=" + BuildConfig.BUILD_TYPE + '\n' +
                "signingSha1=" + signingSha1(context) + '\n';
    }

    private static String signingSha1(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return "unavailable";
            Signature[] signatures = info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) return "unavailable";
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
            StringBuilder out = new StringBuilder();
            for (byte value : digest) {
                if (out.length() > 0) out.append(':');
                out.append(String.format(Locale.US, "%02X", value & 0xff));
            }
            return out.toString();
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    private static String buildInfo() {
        return "sourceCommit=" + BuildConfig.SOURCE_COMMIT + '\n' +
                "ciRunId=" + BuildConfig.CI_RUN_ID + '\n';
    }

    private static String diagnosticState(Context context, DiaryDbHelper db) {
        double[] totals = db.getTodayTotals();
        DriveConnectionStore.State connection = new DriveConnectionStore(context).getState();
        DriveSyncStatusStore.State drive = new DriveSyncStatusStore(context).getState();
        SupabaseConnectionStore.State supabaseConnection = new SupabaseConnectionStore(context).getState();
        SupabaseSyncStatusStore.State supabase = new SupabaseSyncStatusStore(context).getState();
        SyncCoordinator.State sync = SyncCoordinator.getState(context);
        return String.format(Locale.US,
                "{\n  \"entryCount\": %d,\n  \"todayCalories\": %.1f,\n  \"todayProtein\": %.1f,\n  \"todayCarbs\": %.1f,\n  \"todayFat\": %.1f,\n  \"driveConnected\": %s,\n  \"driveDirty\": %s,\n  \"driveLocalRevision\": %d,\n  \"driveLastSuccessAt\": %d,\n  \"driveLastSuccessRevision\": %d,\n  \"driveReauthRequired\": %s,\n  \"supabaseConfigured\": %s,\n  \"supabaseConnected\": %s,\n  \"supabaseLastSuccessAt\": %d,\n  \"supabaseLastSuccessRevision\": %d,\n  \"supabaseReauthRequired\": %s\n}\n",
                db.getEntryCount(), totals[0], totals[1], totals[2], totals[3],
                Boolean.toString(connection.connected), Boolean.toString(sync.dirty),
                sync.localRevision, drive.lastSuccessAt, drive.lastSuccessRevision,
                Boolean.toString(drive.reauthRequired),
                Boolean.toString(SupabaseConfig.isConfigured()),
                Boolean.toString(supabaseConnection.connected),
                supabase.lastSuccessAt, supabase.lastSuccessRevision,
                Boolean.toString(supabase.reauthRequired));
    }

    private static String utcStamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
