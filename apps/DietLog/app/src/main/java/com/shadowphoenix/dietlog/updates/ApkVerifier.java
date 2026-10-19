package com.shadowphoenix.dietlog.updates;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

/** Rechecks the downloaded bytes and Android package identity before every install attempt. */
public final class ApkVerifier {
    private ApkVerifier() {}

    public static void validate(Context context, File file, UpdateRelease release)
            throws IOException {
        verifyFile(file, release.apkSize, release.apkSha256);
        PackageManager manager = context.getPackageManager();
        try {
            PackageInfo installed =
                    manager.getPackageInfo(
                            context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            PackageInfo candidate =
                    manager.getPackageArchiveInfo(
                            file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (candidate == null || candidate.applicationInfo == null) {
                throw new IOException("The downloaded file is not a valid Android app.");
            }
            validateIdentity(
                    installed.packageName,
                    installed.getLongVersionCode(),
                    candidate.packageName,
                    candidate.getLongVersionCode(),
                    candidate.applicationInfo.minSdkVersion,
                    Build.VERSION.SDK_INT,
                    release);
            if (!release.versionName.equals(candidate.versionName))
                throw new IOException(
                        "The downloaded app version does not match the published update.");
            if (installed.signingInfo == null || candidate.signingInfo == null) throw signerError();
            if (!sameSigners(
                    certificates(installed.signingInfo.getApkContentsSigners()),
                    certificates(candidate.signingInfo.getApkContentsSigners())))
                throw signerError();
        } catch (PackageManager.NameNotFoundException ignored) {
            throw new IOException("The installed DietLog version could not be verified.");
        } catch (SecurityException ignored) {
            throw new IOException("Android could not verify the downloaded app.");
        }
    }

    static void validateIdentity(
            String installedPackage,
            long installedVersion,
            String candidatePackage,
            long candidateVersion,
            int minSdk,
            int deviceSdk,
            UpdateRelease release)
            throws IOException {
        if (!installedPackage.equals(release.packageName)
                || !installedPackage.equals(candidatePackage)) {
            throw new IOException("This update belongs to a different app installation.");
        }
        if (candidateVersion != release.versionCode || candidateVersion <= installedVersion) {
            throw new IOException(
                    "This update is no longer newer than the installed app. Check again.");
        }
        if (minSdk > deviceSdk) throw new IOException("This update needs a newer Android version.");
    }

    static boolean sameSigners(Set<String> installed, Set<String> candidate) {
        return installed != null
                && candidate != null
                && !installed.isEmpty()
                && installed.equals(candidate);
    }

    private static Set<String> certificates(Signature[] signatures) throws IOException {
        Set<String> result = new HashSet<>();
        if (signatures == null) throw signerError();
        for (Signature signature : signatures)
            result.add(hex(sha256().digest(signature.toByteArray())));
        return result;
    }

    private static IOException signerError() {
        return new IOException(
                "The update signing certificate differs from this installation. Use the same"
                    + " distribution as your installed app.");
    }

    public static void verifyFile(File file, long expectedSize, String expectedHash)
            throws IOException {
        if (!file.isFile()
                || file.length() != expectedSize
                || expectedSize <= 0
                || expectedSize > UpdateRepository.MAX_APK_BYTES) throw integrityError();
        MessageDigest digest = sha256();
        long total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > expectedSize) throw integrityError();
                digest.update(buffer, 0, count);
            }
        }
        if (total != expectedSize || !hex(digest.digest()).equals(expectedHash))
            throw integrityError();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes)
            result.append(Character.forDigit((value >>> 4) & 15, 16))
                    .append(Character.forDigit(value & 15, 16));
        return result.toString();
    }

    private static IOException integrityError() {
        return new IOException(
                "The update download failed its integrity check. Download it again.");
    }
}
