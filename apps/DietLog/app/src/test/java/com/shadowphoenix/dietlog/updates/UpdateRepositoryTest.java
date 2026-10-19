package com.shadowphoenix.dietlog.updates;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public class UpdateRepositoryTest {
    private static final String HASH = "a".repeat(64);
    private static final String PACKAGE = "com.shadowphoenix.dietlog.debug";

    private JSONObject asset(String name, long id, long size) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("id", id)
                .put("state", "uploaded")
                .put("size", size)
                .put("browser_download_url", UpdateRepository.DOWNLOAD_PREFIX + name);
    }

    private JSONObject release() throws Exception {
        return new JSONObject()
                .put("tag_name", "dietlog-latest")
                .put("draft", false)
                .put(
                        "assets",
                        new JSONArray()
                                .put(asset(UpdateRepository.APK_NAME, 1001, 123))
                                .put(asset(UpdateRepository.METADATA_NAME, 1002, 300)));
    }

    private JSONObject metadata() throws Exception {
        return new JSONObject()
                .put("schema", 1)
                .put("versionCode", 60L)
                .put("versionName", "0.9.2-debug")
                .put("packageName", PACKAGE)
                .put("apkName", UpdateRepository.APK_NAME)
                .put("apkSha256", HASH)
                .put("apkSize", 123);
    }

    private UpdateRelease valid() throws Exception {
        return UpdateRepository.parse(release(), metadata(), PACKAGE, 58);
    }

    private static void rejects(Throwing action) throws Exception {
        try {
            action.run();
            fail("Expected validation failure");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private interface Throwing {
        void run() throws Exception;
    }

    @Test
    public void acceptsOnlyNewerVersionAndUsesImmutableAssetCacheKey() throws Exception {
        UpdateRelease update = valid();
        assertEquals(60, update.versionCode);
        assertEquals(
                UpdateRepository.DOWNLOAD_PREFIX + UpdateRepository.APK_NAME + "?asset=1001",
                update.apkUrl);
        assertNull(UpdateRepository.parse(release(), metadata(), PACKAGE, 60));
        assertNull(UpdateRepository.parse(release(), metadata(), PACKAGE, 61));
    }

    @Test
    public void usesLongVersionCodesWithoutTruncation() throws Exception {
        JSONObject metadata = metadata().put("versionCode", 4294967300L);
        assertEquals(
                4294967300L,
                UpdateRepository.parse(release(), metadata, PACKAGE, 4294967299L).versionCode);
        rejects(
                () ->
                        UpdateRepository.parse(
                                release(), metadata().put("versionCode", 60.5), PACKAGE, 58));
        rejects(
                () ->
                        UpdateRepository.parse(
                                release(), metadata().put("versionCode", "60"), PACKAGE, 58));
        rejects(
                () ->
                        UpdateRepository.parse(
                                release(), metadata().put("versionCode", -1), PACKAGE, 58));
    }

    @Test
    public void manifestRoundTripsAndRevalidatesPersistedUrl() throws Exception {
        UpdateRelease update = valid();
        UpdateRelease restored = UpdateRelease.fromJson(update.toJson());
        assertEquals(update.apkSha256, restored.apkSha256);
        assertEquals(update.apkUrl, restored.apkUrl);
        rejects(
                () ->
                        UpdateRelease.fromJson(
                                update.toJson().put("apkUrl", "https://github.com/evil.apk")));
        rejects(() -> UpdateRelease.fromJson(update.toJson().put("assetId", 1009)));
        rejects(() -> UpdateRelease.fromJson(update.toJson().put("apkName", "../bad.apk")));
    }

    @Test
    public void rejectsDraftWrongTagMissingAndDuplicateAssets() throws Exception {
        rejects(
                () ->
                        UpdateRepository.parse(
                                release().put("draft", true), metadata(), PACKAGE, 58));
        rejects(
                () ->
                        UpdateRepository.parse(
                                release().put("tag_name", "other"), metadata(), PACKAGE, 58));
        rejects(
                () ->
                        UpdateRepository.parse(
                                release().put("assets", new JSONArray()), metadata(), PACKAGE, 58));
        JSONObject duplicate = release();
        duplicate.getJSONArray("assets").put(asset(UpdateRepository.APK_NAME, 1003, 123));
        rejects(() -> UpdateRepository.parse(duplicate, metadata(), PACKAGE, 58));
    }

    @Test
    public void rejectsMetadataPackageDigestSizeAndSchemaMismatch() throws Exception {
        rejects(
                () ->
                        UpdateRepository.parse(
                                release(), metadata(), "com.shadowphoenix.dietlog", 58));
        rejects(
                () ->
                        UpdateRepository.parse(
                                release(), metadata().put("apkSize", 124), PACKAGE, 58));
        rejects(
                () ->
                        UpdateRepository.parse(
                                release(), metadata().put("apkSha256", "invalid"), PACKAGE, 58));
        rejects(() -> UpdateRepository.parse(release(), metadata().put("schema", 2), PACKAGE, 58));
        rejects(
                () ->
                        UpdateRepository.parse(
                                release(),
                                metadata().put("apkSize", UpdateRepository.MAX_APK_BYTES + 1),
                                PACKAGE,
                                58));
        JSONObject release = release();
        release.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "b".repeat(64));
        rejects(() -> UpdateRepository.parse(release, metadata(), PACKAGE, 58));
        release.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + HASH);
        assertNotNull(UpdateRepository.parse(release, metadata(), PACKAGE, 58));
    }

    @Test
    public void rejectsForeignAndMutableDownloadAddresses() throws Exception {
        for (String address :
                Arrays.asList(
                        "http://github.com/file",
                        "https://github.com.evil.test/file",
                        "https://github.com@evil.test/file",
                        "https://evil.test/file",
                        "https://github.com:443/file",
                        "https://github.com/file#fragment",
                        "file:///tmp/file")) {
            rejects(() -> UpdateRepository.trustedUrl(address));
        }
        JSONObject release = release();
        release.getJSONArray("assets")
                .getJSONObject(0)
                .put(
                        "browser_download_url",
                        "https://github.com/other/repo/releases/download/dietlog-latest/DietLog-debug.apk");
        rejects(() -> UpdateRepository.parse(release, metadata(), PACKAGE, 58));
    }

    @Test
    public void redirectsRequireTrustedHttpsAtEveryHop() throws Exception {
        java.net.URL current = UpdateRepository.trustedUrl("https://github.com/file");
        assertEquals(
                "release-assets.githubusercontent.com",
                UpdateRepository.redirect(
                                current,
                                "https://release-assets.githubusercontent.com/path?temporary=value")
                        .getHost());
        assertEquals(
                "https://github.com/next",
                UpdateRepository.redirect(current, "/next").toExternalForm());
        rejects(() -> UpdateRepository.redirect(current, "http://github.com/insecure"));
        rejects(() -> UpdateRepository.redirect(current, "//evil.test/file"));
        rejects(() -> UpdateRepository.redirect(current, null));
    }

    @Test
    public void jsonResponsesAreBoundedAndRespectDeadline() throws Exception {
        byte[] bytes = "metadata".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(
                bytes,
                UpdateRepository.readBounded(
                        new ByteArrayInputStream(bytes),
                        bytes.length,
                        System.nanoTime() + 1000000000L));
        rejects(
                () ->
                        UpdateRepository.readBounded(
                                new ByteArrayInputStream(bytes),
                                3,
                                System.nanoTime() + 1000000000L));
        rejects(
                () ->
                        UpdateRepository.readBounded(
                                new ByteArrayInputStream(bytes), 100, System.nanoTime() - 1));
    }

    @Test
    public void integrityRejectsTruncationTamperingAndOversize() throws Exception {
        File directory = Files.createTempDirectory("dietlog-update-test").toFile();
        File apk = new File(directory, "test.apk");
        try {
            byte[] bytes = "valid apk bytes".getBytes(StandardCharsets.UTF_8);
            Files.write(apk.toPath(), bytes);
            String hash = ApkVerifier.hex(ApkVerifier.sha256().digest(bytes));
            ApkVerifier.verifyFile(apk, bytes.length, hash);
            rejects(() -> ApkVerifier.verifyFile(apk, bytes.length + 1, hash));
            rejects(() -> ApkVerifier.verifyFile(apk, bytes.length, HASH));
            rejects(() -> ApkVerifier.verifyFile(apk, UpdateRepository.MAX_APK_BYTES + 1, hash));
            Files.write(apk.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
            rejects(() -> ApkVerifier.verifyFile(apk, bytes.length, hash));
        } finally {
            apk.delete();
            directory.delete();
        }
    }

    @Test
    public void cachedDownloadsAreRecheckedAndObsoleteFilesRemoved() throws Exception {
        File directory = Files.createTempDirectory("dietlog-update-test").toFile();
        byte[] bytes = "apk".getBytes(StandardCharsets.UTF_8);
        String hash = ApkVerifier.hex(ApkVerifier.sha256().digest(bytes));
        JSONObject metadata = metadata().put("apkSha256", hash).put("apkSize", bytes.length);
        JSONObject release = release();
        release.getJSONArray("assets").getJSONObject(0).put("size", bytes.length);
        UpdateRelease update = UpdateRepository.parse(release, metadata, PACKAGE, 58);
        File apk = new File(directory, hash + ".apk");
        File old = new File(directory, HASH + ".apk");
        File part = new File(directory, "stale.part");
        File ready = new File(directory, "ready.json");
        try {
            Files.write(apk.toPath(), bytes);
            Files.write(old.toPath(), bytes);
            Files.write(part.toPath(), bytes);
            Files.write(ready.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
            long[] received = {0};
            File result =
                    new UpdateRepository(directory)
                            .download(
                                    update,
                                    (count, total) -> {
                                        received[0] = count;
                                        assertEquals(bytes.length, total);
                                    },
                                    () -> false);
            assertEquals(apk, result);
            assertEquals(bytes.length, received[0]);
            assertFalse(old.exists());
            assertFalse(part.exists());
            assertTrue(ready.exists());
            try {
                new UpdateRepository(directory).download(update, null, () -> true);
                fail("Expected cancellation");
            } catch (InterruptedIOException expected) {
                /* No network request or installer launch. */
            }
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }

    @Test
    public void packageVersionSdkAndExactSignerSetsAreMandatory() throws Exception {
        UpdateRelease update = valid();
        ApkVerifier.validateIdentity(PACKAGE, 58, PACKAGE, 60, 29, 35, update);
        rejects(() -> ApkVerifier.validateIdentity(PACKAGE, 60, PACKAGE, 60, 29, 35, update));
        rejects(() -> ApkVerifier.validateIdentity(PACKAGE, 58, PACKAGE, 59, 29, 35, update));
        rejects(() -> ApkVerifier.validateIdentity(PACKAGE, 58, "other.app", 60, 29, 35, update));
        rejects(() -> ApkVerifier.validateIdentity(PACKAGE, 58, PACKAGE, 60, 36, 35, update));
        assertTrue(
                ApkVerifier.sameSigners(
                        new HashSet<>(Arrays.asList("a", "b")),
                        new HashSet<>(Arrays.asList("b", "a"))));
        assertFalse(
                ApkVerifier.sameSigners(Collections.singleton("a"), Collections.singleton("b")));
        assertFalse(
                ApkVerifier.sameSigners(
                        Collections.singleton("a"), new HashSet<>(Arrays.asList("a", "b"))));
        assertFalse(ApkVerifier.sameSigners(Collections.emptySet(), Collections.emptySet()));
        assertFalse(ApkVerifier.sameSigners(null, Collections.singleton("a")));
    }
}
