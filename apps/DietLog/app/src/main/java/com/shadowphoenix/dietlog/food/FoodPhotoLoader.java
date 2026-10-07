package com.shadowphoenix.dietlog.food;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Offline assets only. Serial background decoding keeps list rendering responsive. */
public final class FoodPhotoLoader implements AutoCloseable {
    public static final class Photo {
        public final String asset, credit, license, licenseUrl, sourceUrl, kind;
        Photo(JSONObject json) throws Exception {
            asset = json.getString("asset");
            if (!asset.matches("food_photos/[a-f0-9]{64}\\.webp")) throw new IllegalArgumentException("Invalid photo asset");
            credit = json.getString("credit");
            license = json.getString("license");
            licenseUrl = json.getString("license_url");
            sourceUrl = json.getString("source_url");
            kind = json.getString("kind");
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
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 32 * 1024 * 1024) throw new IllegalArgumentException("Photo manifest too large");
                    output.write(buffer, 0, count);
                }
                JSONObject manifest = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
                if (manifest.getInt("schema_version") != 1) throw new IllegalArgumentException("Unknown photo schema");
                JSONObject records = manifest.getJSONObject("photos");
                Iterator<String> keys = records.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    photos.put(key, new Photo(records.getJSONObject(key)));
                }
                JSONObject links = manifest.getJSONObject("foods");
                keys = links.keys();
                while (keys.hasNext()) {
                    String code = keys.next();
                    foods.put(code, links.getString(code));
                }
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
