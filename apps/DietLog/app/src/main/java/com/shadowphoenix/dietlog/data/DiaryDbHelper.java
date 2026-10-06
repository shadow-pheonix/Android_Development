package com.shadowphoenix.dietlog.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
import java.util.UUID;

public final class DiaryDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "dietlog.db";
    private static final int DB_VERSION = 6;
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
        createSyncTables(db);
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
        if (oldVersion < 6) {
            createSyncTables(db);
            bootstrapFoodEntrySync(db, SyncCoordinator.getState(appContext).deviceId);
            oldVersion = 6;
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
        SQLiteDatabase db = getWritableDatabase();
        long id = db.insertOrThrow("food_entries", null, values);
        long now = System.currentTimeMillis();
        upsertFoodEntrySync(db, UUID.randomUUID().toString(), id, now, 0L);
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
        SQLiteDatabase db = getWritableDatabase();
        int count = db.update("food_entries", values, "id = ?",
                new String[]{Long.toString(entry.id)});
        if (count > 0) {
            touchFoodEntrySync(db, entry.id, System.currentTimeMillis());
            SyncCoordinator.markUserDataChanged(appContext, "diary-update");
        }
        return count;
    }

    public int delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String syncId = ensureFoodEntrySync(db, id);
        int count = db.delete("food_entries", "id = ?", new String[]{Long.toString(id)});
        if (count > 0) {
            long now = System.currentTimeMillis();
            upsertFoodEntrySync(db, syncId, null, now, now);
            SyncCoordinator.markUserDataChanged(appContext, "diary-delete");
        }
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
        long now = System.currentTimeMillis();
        values.put("updated_at", now);
        SQLiteDatabase db = getWritableDatabase();
        long result = db.insertWithOnConflict(
                "food_favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        if (result != -1L) {
            db.delete("favorite_tombstones", "food_key=?", new String[]{entry.foodKey()});
            SyncCoordinator.markUserDataChanged(appContext, "favorite-upsert");
        }
    }

    public int removeFavorite(FoodEntry entry) {
        return removeFavoriteKey(entry.foodKey());
    }

    public int removeFavoriteKey(String foodKey) {
        SQLiteDatabase db = getWritableDatabase();
        int count = db.delete("food_favorites", "food_key=?", new String[]{foodKey});
        if (count > 0) {
            ContentValues tombstone = new ContentValues();
            tombstone.put("food_key", foodKey);
            tombstone.put("deleted_at", System.currentTimeMillis());
            db.insertWithOnConflict("favorite_tombstones", null, tombstone,
                    SQLiteDatabase.CONFLICT_REPLACE);
            SyncCoordinator.markUserDataChanged(appContext, "favorite-delete");
        }
        return count;
    }

    public long getEntryCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM food_entries", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    /** Returns a complete, source-data-only snapshot of the user-owned SQLite tables. */
    public JSONObject exportBackupData() throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        bootstrapFoodEntrySync(db, SyncCoordinator.getState(appContext).deviceId);
        JSONObject root = new JSONObject();
        root.put("foodEntries", exportFoodEntries(db));
        root.put("favorites", exportFavorites(db));
        root.put("weightLogs", exportTable("weight_logs", "log_date ASC"));
        return root;
    }

    /** Merges one decoded remote snapshot using entity timestamps and deletion tombstones. */
    public boolean mergeBackupData(JSONObject database, String sourceDeviceId, long snapshotCreatedAt)
            throws JSONException {
        if (database == null) return false;
        SQLiteDatabase db = getWritableDatabase();
        boolean changed = false;
        db.beginTransaction();
        try {
            changed |= mergeFoodEntries(db, database.optJSONArray("foodEntries"),
                    sourceDeviceId, snapshotCreatedAt);
            changed |= mergeFavorites(db, database.optJSONArray("favorites"), snapshotCreatedAt);
            changed |= mergeWeightLogs(db, database.optJSONArray("weightLogs"), snapshotCreatedAt);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return changed;
    }

    private JSONArray exportFoodEntries(SQLiteDatabase db) throws JSONException {
        JSONArray rows = new JSONArray();
        String sql = "SELECT f.*, s.sync_id AS _sync_id, s.updated_at AS _updated_at, "
                + "s.deleted_at AS _deleted_at FROM food_entries f "
                + "JOIN sync_food_entries s ON s.local_id=f.id ORDER BY f.id ASC";
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) rows.put(cursorToJson(cursor));
        }
        try (Cursor cursor = db.query("sync_food_entries",
                new String[]{"sync_id", "updated_at", "deleted_at"},
                "local_id IS NULL AND deleted_at IS NOT NULL", null, null, null,
                "updated_at ASC")) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("_sync_id", cursor.getString(0));
                row.put("_updated_at", cursor.getLong(1));
                row.put("_deleted_at", cursor.getLong(2));
                rows.put(row);
            }
        }
        return rows;
    }

    private JSONArray exportFavorites(SQLiteDatabase db) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.query("food_favorites", null, null, null, null, null,
                "food_key ASC")) {
            while (cursor.moveToNext()) {
                JSONObject row = cursorToJson(cursor);
                row.put("_updated_at", row.optLong("updated_at", 0L));
                row.put("_deleted_at", JSONObject.NULL);
                rows.put(row);
            }
        }
        try (Cursor cursor = db.query("favorite_tombstones",
                new String[]{"food_key", "deleted_at"}, null, null, null, null,
                "food_key ASC")) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("food_key", cursor.getString(0));
                row.put("_updated_at", cursor.getLong(1));
                row.put("_deleted_at", cursor.getLong(1));
                rows.put(row);
            }
        }
        return rows;
    }

    private JSONArray exportTable(String table, String orderBy) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query(
                table, null, null, null, null, null, orderBy)) {
            while (cursor.moveToNext()) rows.put(cursorToJson(cursor));
        }
        return rows;
    }

    private static JSONObject cursorToJson(Cursor cursor) throws JSONException {
        JSONObject row = new JSONObject();
        String[] columns = cursor.getColumnNames();
        for (int i = 0; i < columns.length; i++) {
            switch (cursor.getType(i)) {
                case Cursor.FIELD_TYPE_NULL:
                    row.put(columns[i], JSONObject.NULL);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    row.put(columns[i], cursor.getLong(i));
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    row.put(columns[i], cursor.getDouble(i));
                    break;
                case Cursor.FIELD_TYPE_STRING:
                    row.put(columns[i], cursor.getString(i));
                    break;
                default:
                    throw new JSONException("Unsupported backup column type: " + columns[i]);
            }
        }
        return row;
    }

    private boolean mergeFoodEntries(SQLiteDatabase db, JSONArray rows, String sourceDeviceId,
                                     long snapshotCreatedAt) throws JSONException {
        if (rows == null) return false;
        boolean changed = false;
        String device = sourceDeviceId == null || sourceDeviceId.isEmpty() ? "unknown" : sourceDeviceId;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String syncId = row.optString("_sync_id", "");
            if (syncId.isEmpty()) {
                syncId = "legacy:" + device + ":" + row.optLong("id", i + 1L);
            }
            long remoteUpdated = row.optLong("_updated_at",
                    row.optLong("logged_at", snapshotCreatedAt));
            if (remoteUpdated <= 0L) remoteUpdated = snapshotCreatedAt;
            long remoteDeleted = nullableLong(row, "_deleted_at");
            FoodSyncRecord local = getFoodSyncRecord(db, syncId);
            if (local != null && local.updatedAt >= remoteUpdated) continue;

            if (remoteDeleted > 0L) {
                if (local != null && local.localId != null) {
                    db.delete("food_entries", "id=?",
                            new String[]{Long.toString(local.localId)});
                }
                upsertFoodEntrySync(db, syncId, null, remoteUpdated, remoteDeleted);
                changed = true;
                continue;
            }
            if (!row.has("name")) continue;
            ContentValues values = foodEntryValues(row);
            Long localId = local == null ? null : local.localId;
            if (localId != null) {
                int updated = db.update("food_entries", values, "id=?",
                        new String[]{Long.toString(localId)});
                if (updated == 0) localId = db.insertOrThrow("food_entries", null, values);
            } else {
                localId = db.insertOrThrow("food_entries", null, values);
            }
            upsertFoodEntrySync(db, syncId, localId, remoteUpdated, 0L);
            changed = true;
        }
        return changed;
    }

    private boolean mergeFavorites(SQLiteDatabase db, JSONArray rows, long snapshotCreatedAt)
            throws JSONException {
        if (rows == null) return false;
        boolean changed = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String key = row.optString("food_key", "");
            if (key.isEmpty()) continue;
            long remoteUpdated = row.optLong("_updated_at",
                    row.optLong("updated_at", snapshotCreatedAt));
            if (remoteUpdated <= 0L) remoteUpdated = snapshotCreatedAt;
            long remoteDeleted = nullableLong(row, "_deleted_at");
            long localUpdated = favoriteVersion(db, key);
            if (localUpdated >= remoteUpdated) continue;

            if (remoteDeleted > 0L) {
                db.delete("food_favorites", "food_key=?", new String[]{key});
                ContentValues tombstone = new ContentValues();
                tombstone.put("food_key", key);
                tombstone.put("deleted_at", remoteDeleted);
                db.insertWithOnConflict("favorite_tombstones", null, tombstone,
                        SQLiteDatabase.CONFLICT_REPLACE);
            } else if (row.has("name")) {
                ContentValues values = favoriteValues(row, remoteUpdated);
                db.insertWithOnConflict("food_favorites", null, values,
                        SQLiteDatabase.CONFLICT_REPLACE);
                db.delete("favorite_tombstones", "food_key=?", new String[]{key});
            } else {
                continue;
            }
            changed = true;
        }
        return changed;
    }

    private boolean mergeWeightLogs(SQLiteDatabase db, JSONArray rows, long snapshotCreatedAt) {
        if (rows == null) return false;
        boolean changed = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String date = row.optString("log_date", "");
            if (date.isEmpty()) continue;
            long remoteUpdated = row.optLong("logged_at", snapshotCreatedAt);
            long localUpdated = 0L;
            try (Cursor c = db.query("weight_logs", new String[]{"logged_at"},
                    "log_date=?", new String[]{date}, null, null, null, "1")) {
                if (c.moveToFirst()) localUpdated = c.getLong(0);
            }
            if (localUpdated >= remoteUpdated) continue;
            ContentValues values = new ContentValues();
            values.put("log_date", date);
            values.put("weight_kg", row.optDouble("weight_kg", 0.0));
            values.put("logged_at", remoteUpdated);
            db.insertWithOnConflict("weight_logs", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            changed = true;
        }
        return changed;
    }

    private static ContentValues foodEntryValues(JSONObject row) {
        ContentValues values = new ContentValues();
        values.put("name", row.optString("name", ""));
        values.put("grams", row.optDouble("grams", 0.0));
        values.put("calories", row.optDouble("calories", 0.0));
        values.put("protein", row.optDouble("protein", 0.0));
        values.put("carbs", row.optDouble("carbs", 0.0));
        values.put("fat", row.optDouble("fat", 0.0));
        values.put("meal_type", FoodEntry.normalizeMealType(row.optString("meal_type", "snacks")));
        values.put("logged_at", row.optLong("logged_at", System.currentTimeMillis()));
        values.put("source", row.optString("source", "manual"));
        putNullable(values, "source_food_id", row, "source_food_id");
        putNullable(values, "source_serving_id", row, "source_serving_id");
        putNullable(values, "micronutrients", row, "micronutrients");
        return values;
    }

    private static ContentValues favoriteValues(JSONObject row, long updatedAt) {
        ContentValues values = new ContentValues();
        values.put("food_key", row.optString("food_key", ""));
        values.put("name", row.optString("name", ""));
        values.put("grams", row.optDouble("grams", 0.0));
        values.put("calories", row.optDouble("calories", 0.0));
        values.put("protein", row.optDouble("protein", 0.0));
        values.put("carbs", row.optDouble("carbs", 0.0));
        values.put("fat", row.optDouble("fat", 0.0));
        values.put("meal_type", FoodEntry.normalizeMealType(row.optString("meal_type", "snacks")));
        values.put("source", row.optString("source", "manual"));
        putNullable(values, "source_food_id", row, "source_food_id");
        putNullable(values, "source_serving_id", row, "source_serving_id");
        putNullable(values, "micronutrients", row, "micronutrients");
        values.put("updated_at", updatedAt);
        return values;
    }

    private static void putNullable(ContentValues values, String column, JSONObject row, String key) {
        if (!row.has(key) || row.isNull(key)) values.putNull(column);
        else values.put(column, row.optString(key, null));
    }

    private static long nullableLong(JSONObject row, String key) {
        return row.has(key) && !row.isNull(key) ? row.optLong(key, 0L) : 0L;
    }

    private static long favoriteVersion(SQLiteDatabase db, String key) {
        long version = 0L;
        try (Cursor c = db.query("food_favorites", new String[]{"updated_at"},
                "food_key=?", new String[]{key}, null, null, null, "1")) {
            if (c.moveToFirst()) version = c.getLong(0);
        }
        try (Cursor c = db.query("favorite_tombstones", new String[]{"deleted_at"},
                "food_key=?", new String[]{key}, null, null, null, "1")) {
            if (c.moveToFirst()) version = Math.max(version, c.getLong(0));
        }
        return version;
    }

    private static FoodSyncRecord getFoodSyncRecord(SQLiteDatabase db, String syncId) {
        try (Cursor c = db.query("sync_food_entries",
                new String[]{"local_id", "updated_at", "deleted_at"},
                "sync_id=?", new String[]{syncId}, null, null, null, "1")) {
            if (!c.moveToFirst()) return null;
            Long localId = c.isNull(0) ? null : c.getLong(0);
            return new FoodSyncRecord(localId, c.getLong(1), c.isNull(2) ? 0L : c.getLong(2));
        }
    }

    private static String ensureFoodEntrySync(SQLiteDatabase db, long localId) {
        try (Cursor c = db.query("sync_food_entries", new String[]{"sync_id"},
                "local_id=?", new String[]{Long.toString(localId)}, null, null, null, "1")) {
            if (c.moveToFirst()) return c.getString(0);
        }
        long updatedAt;
        try (Cursor c = db.query("food_entries", new String[]{"logged_at"}, "id=?",
                new String[]{Long.toString(localId)}, null, null, null, "1")) {
            if (!c.moveToFirst()) return null;
            updatedAt = Math.max(System.currentTimeMillis(), c.getLong(0));
        }
        String syncId = UUID.randomUUID().toString();
        upsertFoodEntrySync(db, syncId, localId, updatedAt, 0L);
        return syncId;
    }

    private static void touchFoodEntrySync(SQLiteDatabase db, long localId, long updatedAt) {
        String syncId = ensureFoodEntrySync(db, localId);
        if (syncId != null) upsertFoodEntrySync(db, syncId, localId, updatedAt, 0L);
    }

    private static void upsertFoodEntrySync(SQLiteDatabase db, String syncId, Long localId,
                                            long updatedAt, long deletedAt) {
        ContentValues values = new ContentValues();
        values.put("sync_id", syncId);
        if (localId == null) values.putNull("local_id"); else values.put("local_id", localId);
        values.put("updated_at", updatedAt);
        if (deletedAt > 0L) values.put("deleted_at", deletedAt); else values.putNull("deleted_at");
        db.insertWithOnConflict("sync_food_entries", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static void bootstrapFoodEntrySync(SQLiteDatabase db, String deviceId) {
        String namespace = deviceId == null || deviceId.isEmpty() ? "unknown" : deviceId;
        List<long[]> missing = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT f.id, f.logged_at FROM food_entries f LEFT JOIN sync_food_entries s "
                        + "ON s.local_id=f.id WHERE s.sync_id IS NULL", null)) {
            while (c.moveToNext()) missing.add(new long[]{c.getLong(0), c.getLong(1)});
        }
        for (long[] row : missing) {
            long id = row[0];
            upsertFoodEntrySync(db, "legacy:" + namespace + ":" + id, id, row[1], 0L);
        }
    }

    private static final class FoodSyncRecord {
        final Long localId;
        final long updatedAt;
        final long deletedAt;

        FoodSyncRecord(Long localId, long updatedAt, long deletedAt) {
            this.localId = localId;
            this.updatedAt = updatedAt;
            this.deletedAt = deletedAt;
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

    private static void createSyncTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_food_entries (" +
                "sync_id TEXT PRIMARY KEY," +
                "local_id INTEGER UNIQUE," +
                "updated_at INTEGER NOT NULL," +
                "deleted_at INTEGER" +
                ")");
        db.execSQL("CREATE TABLE IF NOT EXISTS favorite_tombstones (" +
                "food_key TEXT PRIMARY KEY," +
                "deleted_at INTEGER NOT NULL" +
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
