package com.shadowphoenix.dietlog.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.shadowphoenix.dietlog.progress.ProgressData.DailyNutrition;
import com.shadowphoenix.dietlog.progress.ProgressData.WeightPoint;
import com.shadowphoenix.dietlog.nutrition.MicronutrientValues;
import com.shadowphoenix.dietlog.sync.SyncCoordinator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DiaryDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "dietlog.db";
    private static final int DB_VERSION = 5;
    private static final int HISTORY_SCAN_LIMIT = 2000;
    private final Context appContext;

    public DiaryDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        appContext = context.getApplicationContext();
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
                "meal_type TEXT NOT NULL," +
                "logged_at INTEGER NOT NULL," +
                "source TEXT NOT NULL," +
                "source_food_id TEXT," +
                "source_serving_id TEXT," +
                "micronutrients TEXT" +
                ")");
        db.execSQL("CREATE INDEX idx_food_entries_logged_at ON food_entries(logged_at)");
        createFavoritesTable(db);
        createWeightLogsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE food_entries ADD COLUMN meal_type TEXT NOT NULL DEFAULT 'snacks'");
            db.execSQL("UPDATE food_entries SET meal_type = CASE " +
                    "WHEN CAST(strftime('%H', logged_at / 1000, 'unixepoch', 'localtime') AS INTEGER) < 11 THEN 'breakfast' " +
                    "WHEN CAST(strftime('%H', logged_at / 1000, 'unixepoch', 'localtime') AS INTEGER) < 15 THEN 'lunch' " +
                    "WHEN CAST(strftime('%H', logged_at / 1000, 'unixepoch', 'localtime') AS INTEGER) < 18 THEN 'snacks' " +
                    "ELSE 'dinner' END");
            oldVersion = 2;
        }
        if (oldVersion < 3) {
            createFavoritesTable(db);
            oldVersion = 3;
        }
        if (oldVersion < 4) {
            createWeightLogsTable(db);
            oldVersion = 4;
        }
        if (oldVersion < 5) {
            addColumnIfMissing(db, "food_entries", "micronutrients", "TEXT");
            addColumnIfMissing(db, "food_favorites", "micronutrients", "TEXT");
            oldVersion = 5;
        }
        if (oldVersion != newVersion) {
            throw new IllegalStateException("No database migration exists from " + oldVersion + " to " + newVersion);
        }
    }

    public long insert(FoodEntry entry) {
        ContentValues values = new ContentValues();
        values.put("name", entry.name);
        values.put("grams", entry.grams);
        values.put("calories", entry.calories);
        values.put("protein", entry.protein);
        values.put("carbs", entry.carbs);
        values.put("fat", entry.fat);
        values.put("meal_type", entry.mealType);
        values.put("logged_at", entry.loggedAt);
        values.put("source", entry.source);
        values.put("source_food_id", entry.sourceFoodId);
        values.put("source_serving_id", entry.sourceServingId);
        values.put("micronutrients", entry.micronutrients.encode());
        long id = getWritableDatabase().insertOrThrow("food_entries", null, values);
        SyncCoordinator.markUserDataChanged(appContext, "diary-insert");
        return id;
    }

    public List<FoodEntry> getTodayEntries() {
        return getEntriesForDate(LocalDate.now(ZoneId.systemDefault()));
    }

    public List<FoodEntry> getEntriesForDate(LocalDate date) {
        long[] bounds = dateBounds(date);
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

    public LocalDate getLastLoggedDateBefore(LocalDate beforeDate) {
        long beforeMillis = dateBounds(beforeDate)[0];
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(logged_at) FROM food_entries WHERE logged_at < ?",
                new String[]{Long.toString(beforeMillis)})) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return Instant.ofEpochMilli(c.getLong(0))
                        .atZone(ZoneId.systemDefault()).toLocalDate();
            }
        }
        return null;
    }

    public int copyDateToDate(LocalDate sourceDate, LocalDate targetDate) {
        if (sourceDate == null || targetDate == null || sourceDate.equals(targetDate)) return 0;
        List<FoodEntry> sourceEntries = getEntriesForDate(sourceDate);
        if (sourceEntries.isEmpty()) return 0;

        ZoneId zone = ZoneId.systemDefault();
        long baseTimestamp = targetDate.equals(LocalDate.now(zone))
                ? System.currentTimeMillis()
                : targetDate.atTime(12, 0).atZone(zone).toInstant().toEpochMilli();

        SQLiteDatabase writable = getWritableDatabase();
        int copied = 0;
        writable.beginTransaction();
        try {
            for (int i = 0; i < sourceEntries.size(); i++) {
                FoodEntry source = sourceEntries.get(i);
                FoodEntry copy = source.copyForLog(baseTimestamp - i, source.mealType);
                insert(copy);
                copied++;
            }
            writable.setTransactionSuccessful();
        } finally {
            writable.endTransaction();
        }
        return copied;
    }

    public List<DailyNutrition> getNutritionRange(LocalDate startDate, LocalDate endDate) {
        List<DailyNutrition> result = new ArrayList<>();
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) return result;
        long start = dateBounds(startDate)[0];
        long end = dateBounds(endDate.plusDays(1))[0];
        String sql = "SELECT strftime('%Y-%m-%d', logged_at / 1000, 'unixepoch', 'localtime') AS day, " +
                "SUM(calories), SUM(protein), SUM(carbs), SUM(fat), COUNT(*) " +
                "FROM food_entries WHERE logged_at >= ? AND logged_at < ? " +
                "GROUP BY day ORDER BY day ASC";
        try (Cursor c = getReadableDatabase().rawQuery(
                sql, new String[]{Long.toString(start), Long.toString(end)})) {
            while (c.moveToNext()) {
                result.add(new DailyNutrition(
                        LocalDate.parse(c.getString(0)),
                        c.getDouble(1),
                        c.getDouble(2),
                        c.getDouble(3),
                        c.getDouble(4),
                        c.getInt(5)
                ));
            }
        }
        return result;
    }

    public void upsertWeight(LocalDate date, double weightKg) {
        ContentValues values = new ContentValues();
        values.put("log_date", date.toString());
        values.put("weight_kg", weightKg);
        values.put("logged_at", System.currentTimeMillis());
        long result = getWritableDatabase().insertWithOnConflict(
                "weight_logs", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (result != -1L) SyncCoordinator.markUserDataChanged(appContext, "weight-upsert");
    }

    public List<WeightPoint> getWeightRange(LocalDate startDate, LocalDate endDate) {
        List<WeightPoint> result = new ArrayList<>();
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) return result;
        try (Cursor c = getReadableDatabase().query(
                "weight_logs",
                new String[]{"log_date", "weight_kg"},
                "log_date >= ? AND log_date <= ?",
                new String[]{startDate.toString(), endDate.toString()},
                null, null, "log_date ASC")) {
            while (c.moveToNext()) {
                result.add(new WeightPoint(
                        LocalDate.parse(c.getString(0)),
                        c.getDouble(1)
                ));
            }
        }
        return result;
    }

    public WeightPoint getLatestWeight() {
        try (Cursor c = getReadableDatabase().query(
                "weight_logs",
                new String[]{"log_date", "weight_kg"},
                null, null, null, null,
                "log_date DESC", "1")) {
            if (c.moveToFirst()) {
                return new WeightPoint(LocalDate.parse(c.getString(0)), c.getDouble(1));
            }
        }
        return null;
    }

    public long getWeightLogCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM weight_logs", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    public double[] getTodayTotals() {
        return getTotalsForDate(LocalDate.now(ZoneId.systemDefault()));
    }

    public double[] getTotalsForDate(LocalDate date) {
        long[] bounds = dateBounds(date);
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

    public int update(FoodEntry entry) {
        ContentValues values = new ContentValues();
        values.put("name", entry.name);
        values.put("grams", entry.grams);
        values.put("calories", entry.calories);
        values.put("protein", entry.protein);
        values.put("carbs", entry.carbs);
        values.put("fat", entry.fat);
        values.put("meal_type", entry.mealType);
        values.put("logged_at", entry.loggedAt);
        values.put("source", entry.source);
        values.put("source_food_id", entry.sourceFoodId);
        values.put("source_serving_id", entry.sourceServingId);
        values.put("micronutrients", entry.micronutrients.encode());
        int count = getWritableDatabase().update("food_entries", values, "id = ?",
                new String[]{Long.toString(entry.id)});
        if (count > 0) SyncCoordinator.markUserDataChanged(appContext, "diary-update");
        return count;
    }

    public int delete(long id) {
        int count = getWritableDatabase().delete("food_entries", "id = ?", new String[]{Long.toString(id)});
        if (count > 0) SyncCoordinator.markUserDataChanged(appContext, "diary-delete");
        return count;
    }

    public List<FoodHistory.Item> getRecentFoods(int limit) {
        return FoodHistory.recent(getHistoryEntries(), limit);
    }

    public List<FoodHistory.Item> getFrequentFoods(int limit) {
        return FoodHistory.frequent(getHistoryEntries(), limit);
    }

    public List<FoodHistory.Item> getFavoriteFoods(int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (FoodEntry entry : getHistoryEntries()) {
            counts.put(entry.foodKey(), counts.getOrDefault(entry.foodKey(), 0) + 1);
        }

        List<FoodHistory.Item> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "food_favorites", null, null, null, null, null,
                "updated_at DESC", Integer.toString(Math.max(0, limit)))) {
            while (c.moveToNext()) {
                FoodEntry entry = favoriteFromCursor(c);
                out.add(new FoodHistory.Item(entry, counts.getOrDefault(entry.foodKey(), 0)));
            }
        }
        return out;
    }

    public boolean isFavorite(FoodEntry entry) {
        return isFavoriteKey(entry.foodKey());
    }

    public boolean isFavoriteKey(String foodKey) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM food_favorites WHERE food_key=? LIMIT 1",
                new String[]{foodKey})) {
            return c.moveToFirst();
        }
    }

    public void setFavorite(FoodEntry entry) {
        ContentValues values = new ContentValues();
        values.put("food_key", entry.foodKey());
        values.put("name", entry.name);
        values.put("grams", entry.grams);
        values.put("calories", entry.calories);
        values.put("protein", entry.protein);
        values.put("carbs", entry.carbs);
        values.put("fat", entry.fat);
        values.put("meal_type", entry.mealType);
        values.put("source", entry.source == null ? "manual" : entry.source);
        values.put("source_food_id", entry.sourceFoodId);
        values.put("source_serving_id", entry.sourceServingId);
        values.put("micronutrients", entry.micronutrients.encode());
        values.put("updated_at", System.currentTimeMillis());
        long result = getWritableDatabase().insertWithOnConflict(
                "food_favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (result != -1L) SyncCoordinator.markUserDataChanged(appContext, "favorite-upsert");
    }

    public int removeFavorite(FoodEntry entry) {
        return removeFavoriteKey(entry.foodKey());
    }

    public int removeFavoriteKey(String foodKey) {
        int count = getWritableDatabase().delete(
                "food_favorites", "food_key=?", new String[]{foodKey});
        if (count > 0) SyncCoordinator.markUserDataChanged(appContext, "favorite-delete");
        return count;
    }

    public long getEntryCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM food_entries", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private List<FoodEntry> getHistoryEntries() {
        List<FoodEntry> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "food_entries", null, null, null, null, null,
                "logged_at DESC, id DESC", Integer.toString(HISTORY_SCAN_LIMIT))) {
            while (c.moveToNext()) result.add(fromCursor(c));
        }
        return result;
    }

    private static FoodEntry favoriteFromCursor(Cursor c) {
        return new FoodEntry(
                0,
                c.getString(c.getColumnIndexOrThrow("name")),
                c.getDouble(c.getColumnIndexOrThrow("grams")),
                c.getDouble(c.getColumnIndexOrThrow("calories")),
                c.getDouble(c.getColumnIndexOrThrow("protein")),
                c.getDouble(c.getColumnIndexOrThrow("carbs")),
                c.getDouble(c.getColumnIndexOrThrow("fat")),
                c.getLong(c.getColumnIndexOrThrow("updated_at")),
                c.getString(c.getColumnIndexOrThrow("meal_type")),
                c.getString(c.getColumnIndexOrThrow("source")),
                c.getString(c.getColumnIndexOrThrow("source_food_id")),
                c.getString(c.getColumnIndexOrThrow("source_serving_id")),
                MicronutrientValues.decode(c.getString(c.getColumnIndexOrThrow("micronutrients")))
        );
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
                c.getString(c.getColumnIndexOrThrow("meal_type")),
                c.getString(c.getColumnIndexOrThrow("source")),
                c.getString(c.getColumnIndexOrThrow("source_food_id")),
                c.getString(c.getColumnIndexOrThrow("source_serving_id")),
                MicronutrientValues.decode(c.getString(c.getColumnIndexOrThrow("micronutrients")))
        );
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        boolean found = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = c.getColumnIndexOrThrow("name");
            while (c.moveToNext()) {
                if (column.equals(c.getString(nameIndex))) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static void createFavoritesTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS food_favorites (" +
                "food_key TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "grams REAL NOT NULL," +
                "calories REAL NOT NULL," +
                "protein REAL NOT NULL," +
                "carbs REAL NOT NULL," +
                "fat REAL NOT NULL," +
                "meal_type TEXT NOT NULL," +
                "source TEXT NOT NULL," +
                "source_food_id TEXT," +
                "source_serving_id TEXT," +
                "micronutrients TEXT," +
                "updated_at INTEGER NOT NULL" +
                ")");
    }

    private static void createWeightLogsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS weight_logs (" +
                "log_date TEXT PRIMARY KEY," +
                "weight_kg REAL NOT NULL," +
                "logged_at INTEGER NOT NULL" +
                ")");
    }

    private static long[] dateBounds(LocalDate date) {
        ZoneId zone = ZoneId.systemDefault();
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        return new long[]{start.toEpochMilli(), end.toEpochMilli()};
    }
}
