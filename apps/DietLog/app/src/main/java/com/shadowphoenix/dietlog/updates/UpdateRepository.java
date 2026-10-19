package com.shadowphoenix.dietlog.updates;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Public GitHub releases only. Never shares account credentials or application data. */
public final class UpdateRepository {
    public static final String APK_NAME = "DietLog-debug.apk";
    public static final String METADATA_NAME = "dietlog-update.json";
    public static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    static final int MAX_JSON_BYTES = 1024 * 1024;
    static final String TAG = "dietlog-latest";
    static final String RELEASE_API =
            "https://api.github.com/repos/shadow-pheonix/Android_Development/releases/tags/" + TAG;
    static final String DOWNLOAD_PREFIX =
            "https://github.com/shadow-pheonix/Android_Development/releases/download/" + TAG + "/";
    private static final Set<String> ALLOWED_HOSTS =
            new HashSet<>(
                    Arrays.asList(
                            "api.github.com",
                            "github.com",
                            "release-assets.githubusercontent.com",
                            "objects.githubusercontent.com"));
    private static final long REQUEST_MILLIS = 5L * 60 * 1000;
    private static final Cancellation NEVER_CANCELLED = () -> false;
    private final File directory;

    public interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    public interface Cancellation {
        boolean isCancelled();
    }

    public UpdateRepository(Context context) {
        directory = new File(context.getCacheDir(), "updates");
    }

    // Filesystem-only construction keeps integrity and cache behavior testable without Android.
    UpdateRepository(File directory) {
        this.directory = directory;
    }

    public UpdateRelease check(String currentPackage, long currentVersion) throws IOException {
        try {
            JSONObject release =
                    new JSONObject(
                            new String(
                                    readJson(RELEASE_API, MAX_JSON_BYTES), StandardCharsets.UTF_8));
            ReleaseAssets assets = assets(release);
            byte[] bytes = readJson(assetUrl(assets.metadata, METADATA_NAME), MAX_JSON_BYTES);
            if (bytes.length != UpdateRelease.positiveLong(assets.metadata, "size"))
                throw UpdateRelease.invalid();
            verifyAssetDigest(assets.metadata, ApkVerifier.hex(ApkVerifier.sha256().digest(bytes)));
            JSONObject metadata = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return parse(release, metadata, currentPackage, currentVersion);
        } catch (JSONException ignored) {
            throw UpdateRelease.invalid();
        }
    }

    /** Pure parser shared by network checks and tests. */
    static UpdateRelease parse(
            JSONObject release, JSONObject metadata, String currentPackage, long currentVersion)
            throws IOException {
        ReleaseAssets assets = assets(release);
        try {
            JSONObject combined = new JSONObject(metadata.toString());
            combined.put("assetId", UpdateRelease.positiveLong(assets.apk, "id"));
            combined.put("apkUrl", assetUrl(assets.apk, APK_NAME));
            UpdateRelease result = UpdateRelease.fromJson(combined);
            if (result.apkSize != UpdateRelease.positiveLong(assets.apk, "size"))
                throw UpdateRelease.invalid();
            verifyAssetDigest(assets.apk, result.apkSha256);
            if (!result.packageName.equals(currentPackage)) {
                throw new IOException(
                        "The published update belongs to a different DietLog distribution.");
            }
            return result.versionCode > currentVersion ? result : null;
        } catch (JSONException ignored) {
            throw UpdateRelease.invalid();
        }
    }

    private static ReleaseAssets assets(JSONObject release) throws IOException {
        if (!TAG.equals(release.optString("tag_name"))
                || !Boolean.FALSE.equals(release.opt("draft"))) throw UpdateRelease.invalid();
        JSONArray all = release.optJSONArray("assets");
        if (all == null || all.length() > 100) throw UpdateRelease.invalid();
        JSONObject apk = null;
        JSONObject metadata = null;
        for (int i = 0; i < all.length(); i++) {
            JSONObject asset = all.optJSONObject(i);
            if (asset == null) throw UpdateRelease.invalid();
            if (APK_NAME.equals(asset.optString("name"))) {
                if (apk != null) throw UpdateRelease.invalid();
                apk = asset;
            }
            if (METADATA_NAME.equals(asset.optString("name"))) {
                if (metadata != null) throw UpdateRelease.invalid();
                metadata = asset;
            }
        }
        if (apk == null || metadata == null)
            throw new IOException("This release is not ready for in-app updates. Try again later.");
        if (!"uploaded".equals(apk.optString("state"))
                || !"uploaded".equals(metadata.optString("state"))) throw UpdateRelease.invalid();
        if (UpdateRelease.positiveLong(apk, "size") > MAX_APK_BYTES
                || UpdateRelease.positiveLong(metadata, "size") > MAX_JSON_BYTES)
            throw UpdateRelease.invalid();
        assetUrl(apk, APK_NAME);
        assetUrl(metadata, METADATA_NAME);
        return new ReleaseAssets(apk, metadata);
    }

    private static void verifyAssetDigest(JSONObject asset, String sha256) throws IOException {
        Object digest = asset.opt("digest");
        if (digest == null || digest == JSONObject.NULL)
            return; // GitHub older assets may have no digest.
        if (!(digest instanceof String) || !("sha256:" + sha256).equals(digest))
            throw UpdateRelease.invalid();
    }

    private static String assetUrl(JSONObject asset, String name) throws IOException {
        long id = UpdateRelease.positiveLong(asset, "id");
        String original = UpdateRelease.text(asset, "browser_download_url", 2048);
        // An asset ID cache key prevents the rolling tag URL reusing a cached older download.
        if (!(DOWNLOAD_PREFIX + name).equals(original)) throw UpdateRelease.invalid();
        return original + "?asset=" + id;
    }

    static void validateAssetUrl(String value, String name, long id) throws IOException {
        if (!(DOWNLOAD_PREFIX + name + "?asset=" + id).equals(value)) throw UpdateRelease.invalid();
        trustedUrl(value);
    }

    static URL trustedUrl(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme())
                    || !ALLOWED_HOSTS.contains(uri.getHost())
                    || uri.getRawUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getRawFragment() != null) {
                throw new IOException("The update server returned an untrusted download address.");
            }
            return uri.toURL();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            throw new IOException("The update server returned an invalid download address.");
        }
    }

    static URL redirect(URL current, String location) throws IOException {
        if (location == null || location.isEmpty())
            throw new IOException("The update server returned an invalid redirect.");
        try {
            return trustedUrl(new URL(current, location).toExternalForm());
        } catch (IOException ignored) {
            throw new IOException("The update server returned an untrusted redirect.");
        }
    }

    public synchronized File download(
            UpdateRelease release, ProgressListener progress, Cancellation cancellation)
            throws IOException {
        // Revalidate even caller-supplied/persisted objects before touching paths or the network.
        try {
            release = UpdateRelease.fromJson(release.toJson());
        } catch (JSONException ignored) {
            throw UpdateRelease.invalid();
        }
        Cancellation cancelled = cancellation == null ? NEVER_CANCELLED : cancellation;
        checkCancelled(cancelled);
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot prepare storage for the update.");
        File destination = new File(directory, release.apkSha256 + ".apk");
        trimCache(destination);
        if (destination.exists()) {
            try {
                ApkVerifier.verifyFile(destination, release.apkSize, release.apkSha256);
                checkCancelled(cancelled);
                if (progress != null) progress.onProgress(release.apkSize, release.apkSize);
                return destination;
            } catch (InterruptedIOException error) {
                throw error;
            } catch (IOException ignored) {
                if (!destination.delete())
                    throw new IOException("Cannot replace the incomplete update.");
            }
        }
        File partial = new File(directory, "download.part");
        if (partial.exists() && !partial.delete())
            throw new IOException("Cannot clear an incomplete update.");
        HttpURLConnection connection = null;
        long deadline = System.nanoTime() + REQUEST_MILLIS * 1000000;
        try {
            connection = open(release.apkUrl, "application/octet-stream", deadline, cancelled);
            long declared = connection.getContentLengthLong();
            if (declared > 0 && declared != release.apkSize)
                throw new IOException("The update download size changed. Check for updates again.");
            long count = 0;
            if (progress != null) progress.onProgress(0, release.apkSize);
            try (InputStream input = connection.getInputStream();
                    FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    ensureActive(deadline, cancelled);
                    count += read;
                    if (count > release.apkSize || count > MAX_APK_BYTES)
                        throw new IOException("The update download exceeded its expected size.");
                    output.write(buffer, 0, read);
                    if (progress != null) progress.onProgress(count, release.apkSize);
                }
                output.getFD().sync();
            }
            ensureActive(deadline, cancelled);
            ApkVerifier.verifyFile(partial, release.apkSize, release.apkSha256);
            checkCancelled(cancelled);
            Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            trimCache(destination);
            return destination;
        } catch (InterruptedIOException error) {
            throw new InterruptedIOException("Update download cancelled or timed out. Try again.");
        } catch (IOException ignored) {
            throw new IOException(
                    "The update could not be downloaded and verified. Check your connection and try"
                        + " again.");
        } finally {
            if (connection != null) connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    private void trimCache(File keep) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!file.equals(keep)
                    && (file.getName().matches("[0-9a-f]{64}\\.apk")
                            || file.getName().endsWith(".part"))) file.delete();
        }
    }

    private static byte[] readJson(String address, int limit) throws IOException {
        HttpURLConnection connection = null;
        long deadline = System.nanoTime() + 60000L * 1000000;
        try {
            connection =
                    open(
                            address,
                            address.startsWith(RELEASE_API)
                                    ? "application/vnd.github+json"
                                    : "application/octet-stream",
                            deadline,
                            NEVER_CANCELLED);
            if (connection.getContentLengthLong() > limit) throw UpdateRelease.invalid();
            try (InputStream input = connection.getInputStream()) {
                return readBounded(input, limit, deadline);
            }
        } catch (IOException ignored) {
            throw new IOException(
                    "Could not check GitHub for updates. Check your connection and try again"
                        + " later.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static byte[] readBounded(InputStream input, int limit, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            ensureActive(deadline, NEVER_CANCELLED);
            if (output.size() + count > limit)
                throw new IOException("The update response is too large.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static HttpURLConnection open(
            String address, String accept, long deadline, Cancellation cancelled)
            throws IOException {
        URL url = trustedUrl(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            ensureActive(deadline, cancelled);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "DietLog-Updater");
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-cache");
            boolean keep = false;
            try {
                int status = connection.getResponseCode();
                ensureActive(deadline, cancelled);
                if (status == HttpURLConnection.HTTP_OK) {
                    keep = true;
                    return connection;
                }
                if (status == 301
                        || status == 302
                        || status == 303
                        || status == 307
                        || status == 308) {
                    url = redirect(url, connection.getHeaderField("Location"));
                } else {
                    throw new IOException(
                            status == 403 || status == 429
                                    ? "GitHub is temporarily limiting update checks. Try again"
                                          + " later."
                                    : "GitHub could not serve this update. Try again later.");
                }
            } finally {
                if (!keep) connection.disconnect();
            }
        }
        throw new IOException("The update server returned too many redirects.");
    }

    private static void ensureActive(long deadline, Cancellation cancelled)
            throws InterruptedIOException {
        checkCancelled(cancelled);
        if (System.nanoTime() > deadline)
            throw new InterruptedIOException("The update request timed out.");
    }

    private static void checkCancelled(Cancellation cancelled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || cancelled.isCancelled())
            throw new InterruptedIOException("Update download cancelled.");
    }

    private static final class ReleaseAssets {
        final JSONObject apk;
        final JSONObject metadata;

        ReleaseAssets(JSONObject apk, JSONObject metadata) {
            this.apk = apk;
            this.metadata = metadata;
        }
    }
}
