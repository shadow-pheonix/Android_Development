package com.shadowphoenix.dietlog.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public final class DiaryDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "dietlog.db";
    private static final int DB_VERSION = 1;

    public DiaryDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE food_entries (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "grams REAL NOT NULL," +
                "calories REAL NOT NULL," +
                "protein REAL NOT NULL," +
                "carbs REAL NOT NULL," +
                "fat REAL NOT NULL," +
                "logged_at INTEGER NOT NULL," +
                "source TEXT NOT NULL," +
                "source_food_id TEXT," +
                "source_serving_id TEXT" +
                ")");
        db.execSQL("CREATE INDEX idx_food_entries_logged_at ON food_entries(logged_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No database migration exists from " + oldVersion + " to " + newVersion);
    }

    public long insert(FoodEntry entry) {
        ContentValues values = new ContentValues();
        values.put("name", entry.name);
        values.put("grams", entry.grams);
        values.put("calories", entry.calories);
        values.put("protein", entry.protein);
        values.put("carbs", entry.carbs);
        values.put("fat", entry.fat);
        values.put("logged_at", entry.loggedAt);
        values.put("source", entry.source);
        values.put("source_food_id", entry.sourceFoodId);
        values.put("source_serving_id", entry.sourceServingId);
        return getWritableDatabase().insertOrThrow("food_entries", null, values);
    }

    public List<FoodEntry> getTodayEntries() {
        long[] bounds = todayBounds();
        List<FoodEntry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "food_entries",
                null,
                "logged_at >= ? AND logged_at < ?",
                new String[]{Long.toString(bounds[0]), Long.toString(bounds[1])},
                null,
                null,
                "logged_at DESC",
                "100")) {
            while (c.moveToNext()) {
                result.add(fromCursor(c));
            }
        }
        return result;
    }

    public double[] getTodayTotals() {
        long[] bounds = todayBounds();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(calories),0), COALESCE(SUM(protein),0), " +
                        "COALESCE(SUM(carbs),0), COALESCE(SUM(fat),0) " +
                        "FROM food_entries WHERE logged_at >= ? AND logged_at < ?",
                new String[]{Long.toString(bounds[0]), Long.toString(bounds[1])})) {
            if (c.moveToFirst()) {
                return new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)};
            }
        }
        return new double[]{0, 0, 0, 0};
    }

    public long getEntryCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM food_entries", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private static FoodEntry fromCursor(Cursor c) {
        return new FoodEntry(
                c.getLong(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getDouble(c.getColumnIndexOrThrow("grams")),
                c.getDouble(c.getColumnIndexOrThrow("calories")),
                c.getDouble(c.getColumnIndexOrThrow("protein")),
                c.getDouble(c.getColumnIndexOrThrow("carbs")),
                c.getDouble(c.getColumnIndexOrThrow("fat")),
                c.getLong(c.getColumnIndexOrThrow("logged_at")),
                c.getString(c.getColumnIndexOrThrow("source")),
                c.getString(c.getColumnIndexOrThrow("source_food_id")),
                c.getString(c.getColumnIndexOrThrow("source_serving_id"))
        );
    }

    private static long[] todayBounds() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        return new long[]{start.toEpochMilli(), end.toEpochMilli()};
    }
}
