package com.shadowphoenix.dietlog.planner;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.time.LocalDate;
import java.util.*;

/** Planner state is private to this device; existing diary/cloud formats remain compatible. */
public final class PlannerStore {
    private final SharedPreferences prefs;
    public PlannerStore(Context context) {prefs=context.getSharedPreferences("dietlog_planner",Context.MODE_PRIVATE);}
    public PlannerProfile profile() throws JSONException {
        PlannerProfile p=new PlannerProfile();JSONObject j=new JSONObject(prefs.getString("profile","{}"));
        p.diet=PlannerProfile.Diet.valueOf(j.optString("diet","ANY"));p.dailyBudget=j.optDouble("dailyBudget",0);p.weeklyBudget=j.optDouble("weeklyBudget",0);
        p.maxPrepMinutes=j.optInt("prep",45);p.canCook=j.optBoolean("cook",true);p.learnFromHistory=j.optBoolean("learn",true);p.cuisine=j.optString("cuisine","Any");p.largestMeal=j.optInt("largestMeal",1);
        JSONArray meals=j.optJSONArray("meals");if(meals!=null&&meals.length()==4)for(int i=0;i<4;i++)p.meals[i]=meals.getBoolean(i);
        readSet(j.optJSONArray("allergies"),p.allergies);readSet(j.optJSONArray("excluded"),p.excludedIngredients);
        JSONObject ratings=j.optJSONObject("ratings");if(ratings!=null)for(Iterator<String> it=ratings.keys();it.hasNext();) {String id=it.next();p.ratings.put(id,FoodPreference.valueOf(ratings.getString(id)));}
        JSONObject names=j.optJSONObject("names");if(names!=null)for(Iterator<String> it=names.keys();it.hasNext();) {String id=it.next();p.ratingNames.put(id,names.getString(id));}
        JSONObject prices=j.optJSONObject("prices");if(prices!=null)for(Iterator<String> it=prices.keys();it.hasNext();) {String id=it.next();p.prices.put(id,prices.getDouble(id));}
        p.validate();return p;
    }
    public void saveProfile(PlannerProfile p) throws JSONException {
        p.validate();JSONObject j=new JSONObject();j.put("schema",1).put("diet",p.diet.name()).put("dailyBudget",p.dailyBudget).put("weeklyBudget",p.weeklyBudget)
                .put("prep",p.maxPrepMinutes).put("cook",p.canCook).put("learn",p.learnFromHistory).put("cuisine",p.cuisine).put("largestMeal",p.largestMeal)
                .put("allergies",new JSONArray(p.allergies)).put("excluded",new JSONArray(p.excludedIngredients))
                .put("ratings",new JSONObject(p.ratings)).put("names",new JSONObject(p.ratingNames)).put("prices",new JSONObject(p.prices));
        JSONArray meals=new JSONArray();for(boolean m:p.meals)meals.put(m);j.put("meals",meals);
        put("profile",j.toString());
    }
    public String learningIdentity() {return prefs.getString("feedback","{}");}
    public String profileIdentity() {return prefs.getString("profile","{}");}
    public JSONArray personalRecipes() throws JSONException {return new JSONArray(prefs.getString("recipes","[]"));}
    public void saveRecipes(JSONArray recipes) {if(recipes.length()>100)throw new IllegalArgumentException("Keep at most 100 personal recipes");put("recipes",recipes.toString());}
    public JSONObject savedPlan(LocalDate day) throws JSONException {return new JSONObject(prefs.getString("day:"+day,"{}"));}
    public void savePlan(LocalDate day,DayPlanner.Result result,String signature,Set<Integer> locks) throws JSONException {
        JSONArray choices=new JSONArray();for(DayPlanner.Choice c:result.choices)choices.put(new JSONObject().put("meal",c.meal).put("recipe",c.recipe.id).put("scale",c.scale).put("locked",locks.contains(c.meal)));
        JSONObject j=new JSONObject().put("signature",signature).put("choices",choices);
        SharedPreferences.Editor edit=prefs.edit().putString("day:"+day,j.toString());
        for(String key:prefs.getAll().keySet())if(key.startsWith("day:")) {
            LocalDate old=LocalDate.parse(key.substring(4));if(old.isBefore(LocalDate.now().minusDays(35))||old.isAfter(LocalDate.now().plusDays(14)))edit.remove(key);
        }
        if(!edit.commit())throw new IllegalStateException("Unable to save your plan");
    }
    public Map<String,Map<IntentModel.Feedback,Integer>> feedback() throws JSONException {
        Map<String,Map<IntentModel.Feedback,Integer>> result=new LinkedHashMap<>();JSONObject j=new JSONObject(prefs.getString("feedback","{}"));
        for(Iterator<String> keys=j.keys();keys.hasNext();) {String id=keys.next();Map<IntentModel.Feedback,Integer> counts=new EnumMap<>(IntentModel.Feedback.class);JSONObject v=j.getJSONObject(id);for(IntentModel.Feedback f:IntentModel.Feedback.values())counts.put(f,Math.min(20,v.optInt(f.name(),0)));result.put(id,counts);}return result;
    }
    public void recordFeedback(String id,IntentModel.Feedback f) throws JSONException {
        JSONObject j=new JSONObject(prefs.getString("feedback","{}"));if(!j.has(id)&&j.length()>=200)j.remove(j.keys().next());
        JSONObject v=j.optJSONObject(id);if(v==null)v=new JSONObject();v.put(f.name(),Math.min(20,v.optInt(f.name(),0)+1));j.put(id,v);put("feedback",j.toString());
    }
    public void resetLearning() {put("feedback","{}");}
    public JSONObject pantry() throws JSONException {return new JSONObject(prefs.getString("pantry","{}"));}
    public void savePantry(JSONObject j) {put("pantry",j.toString());}
    private void put(String key,String value) {if(!prefs.edit().putString(key,value).commit())throw new IllegalStateException("Unable to save planner settings");}
    private static void readSet(JSONArray a,Set<String> out) throws JSONException {if(a!=null)for(int i=0;i<a.length();i++)out.add(a.getString(i));}
}
