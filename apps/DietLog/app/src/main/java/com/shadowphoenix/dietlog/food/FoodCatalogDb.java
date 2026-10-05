package com.shadowphoenix.dietlog.food;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.shadowphoenix.dietlog.BuildConfig;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class FoodCatalogDb implements AutoCloseable {
    private static final String ASSET = "offline_foods.db";
    private static final String COMPLETE_CORE_MACROS =
            "calories_100g IS NOT NULL AND protein_100g IS NOT NULL AND " +
                    "carbs_100g IS NOT NULL AND fat_100g IS NOT NULL";
    private final SQLiteDatabase db;

    public FoodCatalogDb(Context context) throws Exception {
        File target = context.getDatabasePath("offline_foods.db");
        int stored = context.getSharedPreferences("catalog", Context.MODE_PRIVATE).getInt("assetVersion", -1);
        if (!target.exists() || stored != BuildConfig.VERSION_CODE) {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Unable to create database directory");
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            try (InputStream in = context.getAssets().open(ASSET); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) throw new IllegalStateException("Unable to replace old catalog");
            if (!tmp.renameTo(target)) throw new IllegalStateException("Unable to install offline catalog");
            context.getSharedPreferences("catalog", Context.MODE_PRIVATE).edit().putInt("assetVersion", BuildConfig.VERSION_CODE).apply();
            AppLogger.i("Offline food catalog installed bytes=" + target.length());
        }
        db = SQLiteDatabase.openDatabase(target.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    public int count() {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM foods", null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    public int countBySource(String source) {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM foods WHERE source=?", new String[]{source})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<CatalogFood> search(String rawQuery, int limit) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase();
        List<CatalogFood> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        boolean barcode = q.matches("\\d{6,14}");
        String sql;
        String[] args;
        if (barcode) {
            sql = "SELECT * FROM foods WHERE barcode=? OR search_name LIKE ? ORDER BY CASE WHEN barcode=? THEN 0 ELSE 1 END, name LIMIT ?";
            args = new String[]{q, "%" + q + "%", q, Integer.toString(limit)};
        } else {
            sql = "SELECT * FROM foods WHERE search_name LIKE ? ORDER BY " +
                    "CASE WHEN lower(name)=? AND (" + COMPLETE_CORE_MACROS + ") THEN 0 " +
                    "WHEN lower(name) LIKE ? AND (" + COMPLETE_CORE_MACROS + ") THEN 1 " +
                    "WHEN (" + COMPLETE_CORE_MACROS + ") THEN 2 " +
                    "WHEN lower(name)=? THEN 3 WHEN lower(name) LIKE ? THEN 4 ELSE 5 END, " +
                    "CASE WHEN source='INDB' THEN 0 WHEN source='IFCT' THEN 1 " +
                    "WHEN source='USDA' THEN 2 WHEN source='OFF' THEN 3 ELSE 4 END, name LIMIT ?";
            args = new String[]{"%" + q + "%", q, q + "%", q, q + "%", Integer.toString(limit)};
        }
        try (Cursor c = db.rawQuery(sql, args)) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    private static CatalogFood read(Cursor c) {
        return new CatalogFood(s(c,"code"),s(c,"barcode"),s(c,"name"),s(c,"brand"),s(c,"quantity"),s(c,"category"),s(c,"source"),
                d(c,"calories_100g"),d(c,"protein_100g"),d(c,"carbs_100g"),d(c,"fat_100g"),d(c,"sugars_100g"),d(c,"fiber_100g"),
                d(c,"saturated_fat_100g"),d(c,"salt_100g"),d(c,"sodium_100g"),s(c,"serving_size"),d(c,"serving_g"),s(c,"ingredients"),
                s(c,"allergens"),s(c,"labels"),s(c,"nutriscore"),i(c,"nova"),s(c,"product_url"),s(c,"extra_json"));
    }
    private static String s(Cursor c,String n){int i=c.getColumnIndexOrThrow(n);return c.isNull(i)?null:c.getString(i);}
    private static Double d(Cursor c,String n){int i=c.getColumnIndexOrThrow(n);return c.isNull(i)?null:c.getDouble(i);}
    private static Integer i(Cursor c,String n){int i=c.getColumnIndexOrThrow(n);return c.isNull(i)?null:c.getInt(i);}

    @Override public void close() { db.close(); }
}
