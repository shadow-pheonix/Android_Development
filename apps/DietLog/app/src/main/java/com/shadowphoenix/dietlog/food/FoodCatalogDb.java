package com.shadowphoenix.dietlog.food;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.shadowphoenix.dietlog.BuildConfig;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.data.FoodEntry;
import com.shadowphoenix.dietlog.nutrition.Micronutrient;
import com.shadowphoenix.dietlog.nutrition.MicronutrientValues;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class FoodCatalogDb implements AutoCloseable {
    private static final String ASSET = "offline_foods.db";
    private static final String FOOD_SELECT = "SELECT f.*,r.display_name FROM foods f " +
            "LEFT JOIN regional_foods r ON r.code=f.code ";
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
        return searchResult(rawQuery, limit).foods;
    }

    public static final class SearchResult {
        public final List<CatalogFood> foods;
        public final boolean corrected;
        SearchResult(List<CatalogFood> foods, boolean corrected) { this.foods = foods; this.corrected = corrected; }
    }

    public SearchResult searchResult(String rawQuery, int limit) {
        FoodSearch.Query query = new FoodSearch.Query(rawQuery);
        int boundedLimit = Math.max(1, Math.min(50, limit));
        if (query.text.isEmpty()) return new SearchResult(Collections.emptyList(), false);
        if (query.barcode) {
            CatalogFood food = findByBarcode(query.text);
            return new SearchResult(food == null ? Collections.emptyList() : Collections.singletonList(food), false);
        }
        List<CatalogFood> results = match(query, query.match(), boundedLimit, false);
        if (!results.isEmpty()) return new SearchResult(results, query.spellingAdjusted);
        List<List<String>> alternatives = new ArrayList<>();
        boolean changed = false;
        for (String token : query.tokens) {
            List<String> replacement = Collections.emptyList();
            if (FoodSearch.canCorrect(token) && !hasPrefix(token)) replacement = correct(token);
            if (replacement.isEmpty()) alternatives.add(Collections.singletonList(token));
            else { alternatives.add(replacement); changed = true; }
        }
        if (changed) results = match(query, FoodSearch.expression(alternatives), boundedLimit, true);
        return new SearchResult(results, (changed || query.spellingAdjusted) && !results.isEmpty());
    }

    private List<CatalogFood> match(FoodSearch.Query query, String expression, int limit, boolean approximate) {
        String sql = "SELECT f.*,r.display_name,s.name AS matched_name,s.brand AS matched_brand," +
                "s.aliases AS matched_aliases,s.category AS matched_category FROM foods_search s " +
                "JOIN foods f ON f.rowid=s.docid LEFT JOIN regional_foods r ON r.code=f.code " +
                "WHERE foods_search MATCH ? ORDER BY CASE WHEN s.name=? OR " +
                "instr(char(10)||s.aliases||char(10),char(10)||?||char(10))>0 THEN 0 " +
                "WHEN s.name LIKE ? THEN 1 ELSE 2 END, CASE WHEN (" + COMPLETE_CORE_MACROS + ") " +
                "THEN 0 ELSE 1 END, CASE WHEN f.source='INDB' THEN 0 WHEN f.source='IFCT' " +
                "THEN 1 WHEN f.source='USDA' THEN 2 ELSE 3 END, length(s.name),f.code LIMIT 600";
        List<ScoredFood> matches = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, new String[]{expression, query.text, query.text, query.text + "%"})) {
            while (c.moveToNext()) {
                CatalogFood food = read(c);
                matches.add(new ScoredFood(food, FoodSearch.score(query, s(c,"matched_name"),
                        s(c,"matched_brand"),s(c,"matched_aliases"),s(c,"matched_category"),
                        food.source,food.hasCompleteCoreMacros(),approximate)));
            }
        }
        matches.sort(Comparator.comparingInt((ScoredFood f) -> f.score)
                .thenComparingInt(f -> f.food.name.length()).thenComparing(f -> f.food.code));
        List<CatalogFood> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, matches.size()); i++) out.add(matches.get(i).food);
        return out;
    }

    private static final class ScoredFood {
        final CatalogFood food; final int score;
        ScoredFood(CatalogFood food, int score) { this.food = food; this.score = score; }
    }

    private boolean hasPrefix(String token) {
        try (Cursor c = db.rawQuery("SELECT term FROM search_terms WHERE term>=? AND term<? LIMIT 1",
                new String[]{token, token + "\uffff"})) { return c.moveToFirst(); }
    }

    private List<String> correct(String token) {
        Set<String> keys = FoodSearch.deletions(token);
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        String sql = "SELECT DISTINCT t.term,t.frequency FROM search_deletions d " +
                "JOIN search_terms t ON t.term=d.term WHERE d.deletion IN (" + placeholders + ") " +
                "ORDER BY t.frequency DESC,t.term LIMIT 256";
        List<FoodSearch.Term> terms = new ArrayList<>();
        try (Cursor c = db.rawQuery(sql, keys.toArray(new String[0]))) {
            while (c.moveToNext()) terms.add(new FoodSearch.Term(c.getString(0),c.getInt(1)));
        }
        return FoodSearch.corrections(token, terms);
    }

    public List<CatalogFood> andhraFavourites() {
        List<CatalogFood> foods = new ArrayList<>();
        try (Cursor c = db.rawQuery(FOOD_SELECT + "WHERE r.popular=1 ORDER BY r.position", null)) {
            while (c.moveToNext()) foods.add(read(c));
        }
        return foods;
    }

    public List<CatalogFood> mealSuggestions(String mealType) {
        List<CatalogFood> foods = new ArrayList<>();
        String sql = "SELECT f.*,COALESCE(m.display_name,r.display_name) AS display_name " +
                "FROM meal_foods m JOIN foods f ON f.code=m.code " +
                "LEFT JOIN regional_foods r ON r.code=f.code WHERE m.meal_type=? ORDER BY m.position";
        try (Cursor c = db.rawQuery(sql, new String[]{FoodEntry.normalizeMealType(mealType)})) {
            while (c.moveToNext()) foods.add(read(c));
        }
        return foods;
    }

    public CatalogFood findByCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        try (Cursor c = db.rawQuery(FOOD_SELECT + "WHERE f.code=? LIMIT 1", new String[]{code})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public CatalogFood findByBarcode(String barcode) {
        return findByBarcode(barcode, null);
    }

    public CatalogFood findByBarcode(String barcode, String format) {
        for (String candidate : BarcodeValue.lookupCandidates(barcode, format)) {
            try (Cursor c = db.rawQuery(FOOD_SELECT + "WHERE f.barcode=? LIMIT 1", new String[]{candidate})) {
                if (c.moveToFirst()) return read(c);
            }
        }
        return null;
    }

    private static CatalogFood read(Cursor c) {
        String displayName = s(c,"display_name");
        return new CatalogFood(s(c,"code"),s(c,"barcode"),displayName == null ? s(c,"name") : displayName,s(c,"brand"),s(c,"quantity"),s(c,"category"),s(c,"source"),
                d(c,"calories_100g"),d(c,"protein_100g"),d(c,"carbs_100g"),d(c,"fat_100g"),d(c,"sugars_100g"),d(c,"fiber_100g"),
                d(c,"saturated_fat_100g"),d(c,"salt_100g"),d(c,"sodium_100g"),s(c,"serving_size"),d(c,"serving_g"),s(c,"ingredients"),
                s(c,"allergens"),s(c,"labels"),s(c,"nutriscore"),i(c,"nova"),s(c,"product_url"),s(c,"extra_json"),micros(c));
    }

    private static MicronutrientValues micros(Cursor c) {
        MicronutrientValues values = MicronutrientValues.empty();
        values = add(values, Micronutrient.CALCIUM, c, "calcium_mg_100g");
        values = add(values, Micronutrient.PHOSPHORUS, c, "phosphorus_mg_100g");
        values = add(values, Micronutrient.MAGNESIUM, c, "magnesium_mg_100g");
        values = add(values, Micronutrient.SODIUM, c, "sodium_mg_100g");
        values = add(values, Micronutrient.POTASSIUM, c, "potassium_mg_100g");
        values = add(values, Micronutrient.IRON, c, "iron_mg_100g");
        values = add(values, Micronutrient.COPPER, c, "copper_mg_100g");
        values = add(values, Micronutrient.SELENIUM, c, "selenium_ug_100g");
        values = add(values, Micronutrient.CHROMIUM, c, "chromium_ug_100g");
        values = add(values, Micronutrient.MANGANESE, c, "manganese_mg_100g");
        values = add(values, Micronutrient.ZINC, c, "zinc_mg_100g");
        values = add(values, Micronutrient.VITAMIN_A, c, "vitamin_a_ug_100g");
        values = add(values, Micronutrient.VITAMIN_E, c, "vitamin_e_mg_100g");
        values = add(values, Micronutrient.VITAMIN_D, c, "vitamin_d_ug_100g");
        values = add(values, Micronutrient.VITAMIN_K, c, "vitamin_k_ug_100g");
        values = add(values, Micronutrient.THIAMINE, c, "thiamine_mg_100g");
        values = add(values, Micronutrient.RIBOFLAVIN, c, "riboflavin_mg_100g");
        values = add(values, Micronutrient.NIACIN, c, "niacin_mg_100g");
        values = add(values, Micronutrient.PANTOTHENIC_ACID, c, "pantothenic_acid_mg_100g");
        values = add(values, Micronutrient.VITAMIN_B6, c, "vitamin_b6_mg_100g");
        values = add(values, Micronutrient.BIOTIN, c, "biotin_ug_100g");
        values = add(values, Micronutrient.FOLATE, c, "folate_ug_100g");
        return add(values, Micronutrient.VITAMIN_C, c, "vitamin_c_mg_100g");
    }

    private static MicronutrientValues add(MicronutrientValues values, Micronutrient nutrient, Cursor c, String column) {
        int index = c.getColumnIndexOrThrow(column);
        return c.isNull(index) ? values : values.with(nutrient, c.getDouble(index));
    }
    private static String s(Cursor c,String n){int i=c.getColumnIndexOrThrow(n);return c.isNull(i)?null:c.getString(i);}
    private static Double d(Cursor c,String n){int i=c.getColumnIndexOrThrow(n);return c.isNull(i)?null:c.getDouble(i);}
    private static Integer i(Cursor c,String n){int i=c.getColumnIndexOrThrow(n);return c.isNull(i)?null:c.getInt(i);}

    @Override public void close() { db.close(); }
}
