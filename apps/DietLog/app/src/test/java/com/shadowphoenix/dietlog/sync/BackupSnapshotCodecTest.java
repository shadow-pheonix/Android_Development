package com.shadowphoenix.dietlog.sync;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class BackupSnapshotCodecTest {
    private byte[] encoded(JSONObject json) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    @Test
    public void oldBackupsRemainReadableAndNewSnapshotsCarryDiscipline() throws Exception {
        for (int version = 1; version <= 3; version++) {
            JSONObject root =
                    new JSONObject()
                            .put("schemaVersion", version)
                            .put("deviceId", "test-device")
                            .put("database", new JSONObject());
            if (version == 3)
                root.put(
                        "discipline",
                        new JSONObject()
                                .put("schema", 1)
                                .put("reminders", new JSONObject())
                                .put("history", new JSONArray()));
            BackupSnapshotCodec.DecodedSnapshot decoded = BackupSnapshotCodec.decode(encoded(root));
            assertEquals(version, decoded.schemaVersion);
            assertEquals(version == 3, decoded.discipline != null);
        }
    }

    @Test
    public void malformedDisciplineIsRejectedBeforeRestore() throws Exception {
        JSONObject root =
                new JSONObject()
                        .put("schemaVersion", 3)
                        .put("deviceId", "test-device")
                        .put("database", new JSONObject())
                        .put(
                                "discipline",
                                new JSONObject()
                                        .put("schema", 1)
                                        .put(
                                                "reminders",
                                                new JSONObject().put("bad", new JSONObject()))
                                        .put("history", new JSONArray()));
        try {
            BackupSnapshotCodec.decode(encoded(root));
            fail();
        } catch (BackupSnapshotCodec.BackupFormatException expected) {
        }
    }
}
