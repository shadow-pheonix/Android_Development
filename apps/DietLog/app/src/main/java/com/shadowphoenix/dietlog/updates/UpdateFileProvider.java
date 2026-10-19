package com.shadowphoenix.dietlog.updates;

import androidx.core.content.FileProvider;

/** Dedicated provider class for Android/OEM compatibility; paths stay limited to cached updates. */
public final class UpdateFileProvider extends FileProvider {
    public UpdateFileProvider() {
        super();
    }
}
