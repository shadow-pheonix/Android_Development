package com.shadowphoenix.dietlog.sync.drive;

import org.json.JSONObject;

public final class DriveFileMetadata {
    public final String id;
    public final String name;
    public final String modifiedTime;
    public final long size;
    public final JSONObject appProperties;

    DriveFileMetadata(String id, String name, String modifiedTime, long size, JSONObject appProperties) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.modifiedTime = modifiedTime == null ? "" : modifiedTime;
        this.size = size;
        this.appProperties = appProperties == null ? new JSONObject() : appProperties;
    }

    static DriveFileMetadata fromJson(JSONObject object) {
        long parsedSize = 0L;
        String rawSize = object.optString("size", "0");
        try {
            parsedSize = Long.parseLong(rawSize);
        } catch (NumberFormatException ignored) {
            // Drive may omit size for unusual file resources; zero is safe metadata fallback.
        }
        return new DriveFileMetadata(
                object.optString("id", ""),
                object.optString("name", ""),
                object.optString("modifiedTime", ""),
                parsedSize,
                object.optJSONObject("appProperties"));
    }
}
