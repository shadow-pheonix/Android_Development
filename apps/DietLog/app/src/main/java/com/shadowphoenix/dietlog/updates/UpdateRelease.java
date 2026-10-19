package com.shadowphoenix.dietlog.updates;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/** A release manifest bound to one immutable GitHub asset and APK checksum. */
public final class UpdateRelease {
    public final long versionCode;
    public final String versionName;
    public final String packageName;
    public final String apkName;
    public final String apkSha256;
    public final long apkSize;
    public final long assetId;
    public final String apkUrl;

    UpdateRelease(
            long versionCode,
            String versionName,
            String packageName,
            String apkName,
            String apkSha256,
            long apkSize,
            long assetId,
            String apkUrl) {
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.packageName = packageName;
        this.apkName = apkName;
        this.apkSha256 = apkSha256;
        this.apkSize = apkSize;
        this.assetId = assetId;
        this.apkUrl = apkUrl;
    }

    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("schema", 1)
                .put("versionCode", versionCode)
                .put("versionName", versionName)
                .put("packageName", packageName)
                .put("apkName", apkName)
                .put("apkSha256", apkSha256)
                .put("apkSize", apkSize)
                .put("assetId", assetId)
                .put("apkUrl", apkUrl);
    }

    /** Also validates persisted manifests; no persisted URL or filename is implicitly trusted. */
    public static UpdateRelease fromJson(JSONObject json) throws IOException {
        if (positiveLong(json, "schema") != 1) throw invalid();
        long version = positiveLong(json, "versionCode");
        String name = text(json, "versionName", 128);
        String pkg = text(json, "packageName", 255);
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) throw invalid();
        String apkName = text(json, "apkName", 128);
        if (!UpdateRepository.APK_NAME.equals(apkName)) throw invalid();
        String hash = text(json, "apkSha256", 64).toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) throw invalid();
        long size = positiveLong(json, "apkSize");
        if (size > UpdateRepository.MAX_APK_BYTES) throw invalid();
        long asset = positiveLong(json, "assetId");
        String url = text(json, "apkUrl", 2048);
        UpdateRepository.validateAssetUrl(url, apkName, asset);
        return new UpdateRelease(version, name, pkg, apkName, hash, size, asset, url);
    }

    static long positiveLong(JSONObject json, String key) throws IOException {
        Object value = json.opt(key);
        if (!(value instanceof Number) || !value.toString().matches("[0-9]+")) throw invalid();
        try {
            long number = Long.parseLong(value.toString());
            if (number <= 0) throw invalid();
            return number;
        } catch (NumberFormatException ignored) {
            throw invalid();
        }
    }

    static String text(JSONObject json, String key, int max) throws IOException {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw invalid();
        String text = (String) value;
        if (text.trim().isEmpty()
                || text.length() > max
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0
                || text.indexOf('\0') >= 0) throw invalid();
        return text;
    }

    static IOException invalid() {
        return new IOException("The update information is invalid. Try again later.");
    }
}
