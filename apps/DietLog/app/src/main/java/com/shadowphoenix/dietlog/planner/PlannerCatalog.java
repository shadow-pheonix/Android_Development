package com.shadowphoenix.dietlog.planner;

import android.content.Context;
import com.shadowphoenix.dietlog.food.CatalogFood;
import com.shadowphoenix.dietlog.food.FoodCatalogDb;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PlannerCatalog {
    public final Map<String,PlannerFood> foods=new LinkedHashMap<>();
    public final List<MealRecipe> recipes=new ArrayList<>();
    public final Map<String,CatalogFood> sourceFoods=new HashMap<>();
    public PlannerCatalog(Context context,FoodCatalogDb db,JSONArray personal) throws Exception {
        String raw;
        try(InputStream in=context.getAssets().open("planner_meals.json")) {ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);raw=new String(bytes.toByteArray(),StandardCharsets.UTF_8);}
        JSONObject root=new JSONObject(raw);JSONObject definitions=root.getJSONObject("foods");
        for(Iterator<String> keys=definitions.keys();keys.hasNext();) {
            String key=keys.next();JSONObject def=definitions.getJSONObject(key);String code=def.getString("code");
            CatalogFood f=db.findByCode(code);if(f==null||!f.hasCompleteCoreMacros())throw new IOException("Planner nutrition catalogue is incomplete");
            Set<String> tags=new LinkedHashSet<>();JSONArray a=def.getJSONArray("tags");for(int i=0;i<a.length();i++)tags.add(a.getString(i));
            PlannerFood food=new PlannerFood(code,def.getString("name"),def.getString("state"),
                    new double[]{number(f.calories),number(f.protein),number(f.carbs),number(f.fat),number(f.fiber),number(f.sodium),number(f.sugars),number(f.saturatedFat)},
                    tags,def.getBoolean("ingredients_known"),def.getDouble("reference_price_per_100g"));
            foods.put(key,food);sourceFoods.put(code,f);
        }
        JSONArray list=root.getJSONArray("recipes");for(int i=0;i<list.length();i++)recipes.add(readRecipe(list.getJSONObject(i)));
        for(int i=0;i<personal.length()&&i<100;i++)recipes.add(readRecipe(personal.getJSONObject(i)));
    }
    public MealRecipe readRecipe(JSONObject obj) throws JSONException {
        List<MealRecipe.Ingredient> ingredients=new ArrayList<>();JSONArray parts=obj.getJSONArray("ingredients");
        for(int i=0;i<parts.length();i++) {JSONObject p=parts.getJSONObject(i);PlannerFood f=foods.get(p.getString("food"));if(f==null)throw new JSONException("Unknown recipe ingredient");ingredients.add(new MealRecipe.Ingredient(f,p.getDouble("grams")));}
        return new MealRecipe(obj.getString("id"),obj.getString("name"),obj.getInt("meal_mask"),obj.getInt("prep_minutes"),obj.getBoolean("requires_cooking"),obj.optString("cuisine","Indian"),obj.getString("instructions"),obj.getDouble("yield_grams"),ingredients);
    }
    public MealRecipe find(String id) {for(MealRecipe r:recipes)if(r.id.equals(id))return r;return null;}
    private static double number(Double n) {return n==null?Double.NaN:n;}
}
