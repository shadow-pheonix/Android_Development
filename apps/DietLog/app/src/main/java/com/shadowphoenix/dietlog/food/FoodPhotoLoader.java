package com.shadowphoenix.dietlog.food;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.JsonReader;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Offline assets only. Serial background decoding keeps list rendering responsive. */
public final class FoodPhotoLoader implements AutoCloseable {
    private static final Pattern ASSET_PATH = Pattern.compile("food_photos/[a-f0-9]{64}\\.webp");
    public static final class Photo {
        public final String asset, credit, license, licenseUrl, sourceUrl, kind;
        Photo(JsonReader reader) throws IOException {
            String path = null, author = null, terms = null, termsUrl = null, source = null, type = null;
            reader.beginObject();
            while (reader.hasNext()) {
                switch (reader.nextName()) {
                    case "asset": path = reader.nextString(); break;
                    case "credit": author = reader.nextString(); break;
                    case "license": terms = reader.nextString(); break;
                    case "license_url": termsUrl = reader.nextString(); break;
                    case "source_url": source = reader.nextString(); break;
                    case "kind": type = reader.nextString(); break;
                    default: reader.skipValue();
                }
            }
            reader.endObject();
            if (path == null || !ASSET_PATH.matcher(path).matches() ||
                    author == null || terms == null || termsUrl == null || source == null || type == null) {
                throw new IOException("Invalid photo metadata");
            }
            asset = path;
            credit = author;
            license = terms;
            licenseUrl = termsUrl;
            sourceUrl = source;
            kind = type;
        }
    }

    private final AssetManager assets;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> foods = new HashMap<>();
    private final Map<String, Photo> photos = new HashMap<>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private volatile boolean closed;

    public FoodPhotoLoader(Context context) {
        assets = context.getApplicationContext().getAssets();
        worker.execute(() -> {
            try (InputStream input = assets.open("food_photos.json");
                 JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                // AssetInputStream reports the exact remaining asset length here.
                if (input.available() > 32 * 1024 * 1024) throw new IOException("Photo manifest too large");
                boolean supportedSchema = false;
                reader.beginObject();
                while (reader.hasNext() && !closed) {
                    switch (reader.nextName()) {
                        case "schema_version": supportedSchema = reader.nextInt() == 1; break;
                        case "foods":
                            reader.beginObject();
                            while (reader.hasNext()) foods.put(reader.nextName(), reader.nextString());
                            reader.endObject();
                            break;
                        case "photos":
                            reader.beginObject();
                            while (reader.hasNext()) photos.put(reader.nextName(), new Photo(reader));
                            reader.endObject();
                            break;
                        default: reader.skipValue();
                    }
                }
                if (closed) return;
                reader.endObject();
                if (!supportedSchema) throw new IOException("Unknown photo schema");
                AppLogger.i("Offline food photos loaded count=" + foods.size());
            } catch (Exception error) {
                foods.clear();
                photos.clear();
                AppLogger.e("Offline food photos unavailable; using food symbols", error);
            }
        });
    }

    public void bind(ImageView image, TextView fallback, String code, String glyph, Consumer<Photo> ready) {
        Object token = new Object();
        image.setTag(token);
        image.setImageDrawable(null);
        image.setVisibility(View.GONE);
        fallback.setText(glyph);
        fallback.setVisibility(View.VISIBLE);
        if (closed || code == null || code.isEmpty()) return;
        worker.execute(() -> {
            if (closed) return;
            Photo photo = photos.get(foods.get(code));
            if (photo == null) return;
            Bitmap bitmap = cache.get(photo.asset);
            if (bitmap == null) {
                try (InputStream input = assets.open(photo.asset)) {
                    bitmap = BitmapFactory.decodeStream(input);
                    if (bitmap == null) return;
                    cache.put(photo.asset, bitmap);
                } catch (Exception error) {
                    AppLogger.e("Offline thumbnail unavailable", error);
                    return;
                }
            }
            Bitmap decoded = bitmap;
            main.post(() -> {
                if (closed || image.getTag() != token) return;
                boolean product = "product".equals(photo.kind);
                image.setScaleType(product ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                image.setBackgroundColor(product ? Color.WHITE : Color.TRANSPARENT);
                image.setImageBitmap(decoded);
                fallback.setVisibility(View.GONE);
                image.setVisibility(View.VISIBLE);
                if (ready != null) ready.accept(photo);
            });
        });
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
