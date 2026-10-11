package com.shadowphoenix.dietlog.planner;

import android.app.*;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.data.*;
import com.shadowphoenix.dietlog.food.*;
import com.shadowphoenix.dietlog.diagnostics.*;
import com.shadowphoenix.dietlog.profile.*;
import com.shadowphoenix.dietlog.nutrition.*;
import com.shadowphoenix.dietlog.ui.*;
import org.json.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Private offline planning workspace; saved plans never imply consumption. */
public final class PlannerActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private long generation;
    private LinearLayout page;
    private PlannerStore store;
    private FoodCatalogDb db;
    private DiaryDbHelper diary;
    private PlannerCatalog catalog;
    private PlannerProfile profile;
    private LocalDate date=LocalDate.now();
    private DayPlanner.Result result;
    private DayPlanner.Request request;
    private final Map<Integer,DayPlanner.Choice> locks=new LinkedHashMap<>();
    private final Map<Integer,Set<String>> rejected=new LinkedHashMap<>();
    private List<FoodEntry> history=Collections.emptyList(),entries=Collections.emptyList();
    private final Map<String,String> suggestions=new LinkedHashMap<>();
    private boolean ready,busy,foreground;
    private String signature;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);AppLogger.init(this);CrashHandler.install(this);store=new PlannerStore(this);
        if(state!=null)date=LocalDate.parse(state.getString("day",date.toString()));
        else if(getIntent().hasExtra("day"))date=LocalDate.parse(getIntent().getStringExtra("day"));
        getWindow().setStatusBarColor(getColor(R.color.background));getWindow().setNavigationBarColor(getColor(R.color.background));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundResource(R.drawable.bg_frosted_scene);
        page=column();page.setPadding(dp(20),dp(20),dp(20),dp(32));scroll.addView(page);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets;});scroll.requestApplyInsets();
        load();
    }
    private interface Work<T> {T run() throws Exception;}
    private <T> void async(Work<T> work,java.util.function.Consumer<T> done) {
        long token=++generation;busy=true;
        worker.execute(()->{try {T value=work.run();runOnUiThread(()->{if(!closed&&foreground&&token==generation){busy=false;done.accept(value);}});}
        catch(Exception e){AppLogger.i("Planner operation failed type="+e.getClass().getSimpleName());runOnUiThread(()->{if(!closed&&foreground&&token==generation){busy=false;message(e instanceof IllegalArgumentException?e.getMessage():"Unable to complete this planner action. Export diagnostics or retry.");if(ready)render();else{page.removeAllViews();text(page,"Unable to load the planner",20,true);button(page,"Retry",this::load);button(page,"Back",this::finish);}}});}});
    }
    private void load() {
        page.removeAllViews();text(page,"My diet plan",26,true);text(page,"Loading your foods and preferences…",15,false);
        async(()->{if(db==null)db=new FoodCatalogDb(this);if(diary==null)diary=new DiaryDbHelper(this);profile=store.profile();catalog=new PlannerCatalog(this,db,store.personalRecipes());history=diary.getPlannerHistory();return true;},v->{
            ready=true;suggestions.clear();for(PlannerFood f:catalog.foods.values())suggestions.put(f.id,f.name+" ("+f.state+")");
            for(MealRecipe r:catalog.recipes)suggestions.put(r.id,r.name);
            for(FoodEntry e:history)if(e.sourceFoodId!=null&&suggestions.size()<160)suggestions.putIfAbsent(e.sourceFoodId,e.name);
            render();
        });
    }
    private void render() {
        if(closed||!ready)return;page.removeAllViews();button(page,"‹ Back",this::finish);text(page,"My diet plan",26,true);
        text(page,"Food you enjoy. Portions for your targets. A budget you control.",14,false);
        button(page,date.toString()+" · Choose day",()->{DatePickerDialog picker=new DatePickerDialog(this,(v,y,m,d)->{date=LocalDate.of(y,m+1,d);result=null;locks.clear();rejected.clear();render();},date.getYear(),date.getMonthValue()-1,date.getDayOfMonth());picker.show();});
        LinearLayout controls=card(page);button(controls,"Eating preferences",this::preferences);button(controls,"Foods I like or avoid",this::ratings);button(controls,"My local food prices",this::prices);
        text(controls,profile.diet.name().replace('_',' ')+" · "+profile.ratings.size()+" food choices · "+(profile.effectiveDailyBudget()>0?money(profile.effectiveDailyBudget())+" / day":"Budget not set"),14,false);
        button(page,busy?"Working…":"Plan this day",()->generate(false,1,1));button(page,"Plan remaining meals",()->generate(true,1,1));
        button(page,"Use cheaper meals",()->generate(true,3,1));button(page,"Use quicker meals",()->generate(true,1,3));
        if(result==null){text(page,"Set your food choices and local prices, then generate a plan. Plans stay separate from your diary until you confirm a meal.",15,false);return;}
        LinearLayout total=card(page);text(total,"Day totals · including logged food",18,true);
        text(total,String.format(Locale.getDefault(),"%.0f / %.0f kcal\nProtein %.0f / %.0f g · Carbs %.0f / %.0f g · Fat %.0f / %.0f g",result.totals[0],request.targets[0],result.totals[1],request.targets[1],result.totals[2],request.targets[2],result.totals[3],request.targets[3]),15,false);
        text(total,"Consumed-food cost: "+money(result.cost)+(result.pricesEntered?" · your prices":" · estimate / incomplete"),15,true);
        for(String note:result.notes)text(total,note,13,false);
        for(DayPlanner.Choice c:result.choices)renderMeal(c);
        text(page,"Amounts use the listed raw/cooked state. Check product labels and preparation for allergies and cross-contact. These are general meal suggestions, not a medical diet.",12,false);
    }
    private void renderMeal(DayPlanner.Choice c) {
        LinearLayout box=card(page);text(box,DayPlanner.mealName(c.meal)+(locks.containsKey(c.meal)?" · kept":""),15,true);text(box,c.recipe.name,20,true);
        double[] n=c.recipe.nutrition(c.scale);text(box,String.format(Locale.getDefault(),"%.1f × base serving · %.0f kcal · P %.0f g / C %.0f g / F %.0f g · %s",c.scale,n[0],n[1],n[2],n[3],money(PriceModel.consumed(c.recipe,c.scale,profile).amount)),14,false);
        StringBuilder amounts=new StringBuilder();for(MealRecipe.Ingredient i:c.recipe.ingredients)amounts.append(String.format(Locale.getDefault(),"• %s · %.0f g (%s)\n",i.food.name,i.grams*c.scale,i.food.state));text(box,amounts.toString().trim(),14,false);text(box,c.recipe.instructions,13,false);
        text(box,explanation(c),13,false);
        button(box,locks.containsKey(c.meal)?"Unlock meal":"Keep this meal",()->{if(locks.containsKey(c.meal))locks.remove(c.meal);else locks.put(c.meal,c);persist();render();});
        button(box,"Swap meal",()->{locks.remove(c.meal);rejected.computeIfAbsent(c.meal,k->new HashSet<>()).add(c.recipe.id);for(DayPlanner.Choice other:result.choices)if(other.meal!=c.meal)locks.put(other.meal,other);generate(true,1,1);});
        button(box,"Rate this meal",()->rate(c.recipe.id,c.recipe.name));button(box,"Give feedback",()->feedback(c));
        if(!date.isAfter(LocalDate.now()))button(box,"I ate this · confirm portions",()->confirmConsumption(c));
    }
    private String explanation(DayPlanner.Choice c) {
        List<String> reasons=new ArrayList<>();if(profile.rating(c.recipe.id)==FoodPreference.LOVE)reasons.add("You marked this meal Love");
        for(MealRecipe.Ingredient i:c.recipe.ingredients)if(profile.rating(i.food.id)==FoodPreference.LOVE)reasons.add("Includes "+i.food.name+", which you love");
        int days=request.intent.distinctDays(c.recipe.id,c.meal);if(days>0)reasons.add("You logged this at "+DayPlanner.mealName(c.meal).toLowerCase(Locale.ROOT)+" on "+days+" distinct days");
        if(reasons.isEmpty())reasons.add("Chosen with the other meals to balance your saved targets");reasons.add(c.recipe.prepMinutes+" minutes active preparation");return String.join(". ",reasons)+".";
    }
    private void generate(boolean rest,double costWeight,double effortWeight) {
        if(busy||!ready)return;
        if(rest&&result!=null){boolean[] chosen=new boolean[4];for(DayPlanner.Choice c:result.choices)chosen[c.meal]=true;runPlan(chosen,costWeight,effortWeight);return;}
        final LocalDate day=date;
        async(()->diary.getPlannerDayEntries(day),logged->{
            LinearLayout form=column();text(form,"Logged food will count toward today's totals. Select each meal you still want suggestions for, including partially eaten meals.",14,false);
            CheckBox[] options=new CheckBox[4];Set<Integer> eaten=new HashSet<>();for(FoodEntry e:logged)eaten.add(mealIndex(e.mealType));
            for(int m=0;m<4;m++)options[m]=check(form,DayPlanner.mealName(m)+(eaten.contains(m)?" · has diary entries":""),profile.meals[m]&&(!rest||!eaten.contains(m)));
            dialog("Which meals should I plan?",form,()->{boolean[] selected=new boolean[4];boolean any=false;for(int m=0;m<4;m++){selected[m]=options[m].isChecked();any|=selected[m];}if(!any){message("Choose at least one remaining meal");return false;}runPlan(selected,costWeight,effortWeight);return true;});render();
        });render();
    }
    private void runPlan(boolean[] selected,double costWeight,double effortWeight) {
        if(busy||!ready)return;
        final LocalDate day=date;Map<Integer,DayPlanner.Choice> kept=new HashMap<>(locks);Map<Integer,Set<String>> omitted=new HashMap<>();for(Map.Entry<Integer,Set<String>> e:rejected.entrySet())omitted.put(e.getKey(),new HashSet<>(e.getValue()));
        async(()->{
            profile=store.profile();ProfilePreferences pp=new ProfilePreferences(this);if(!pp.hasCompletedPersonalProfile())throw new IllegalArgumentException("Complete your personal profile before generating a diet plan");
            ProfileSettings s=pp.load();if(s.effectiveAge(LocalDate.now())<18)throw new IllegalArgumentException("Personalized meal planning currently supports adults. Keep using the food diary and professional guidance for younger users.");
            DayPlanner.Request r=new DayPlanner.Request();r.profile=profile;r.targets=new double[]{CalorieCalculator.calculate(s,day).calorieTarget,s.proteinGoalG,s.carbsGoalG,s.fatGoalG,s.fibreGoalG,s.sodiumLimitMg,s.sugarLimitG,0};
            entries=diary.getPlannerDayEntries(day);Arrays.fill(r.consumed,0);Set<Integer> eaten=new HashSet<>();double[] diaryTotals=diary.getTotalsForDate(day);System.arraycopy(diaryTotals,0,r.consumed,0,4);
            if(!entries.isEmpty()){for(int i=4;i<8;i++)r.consumed[i]=Double.NaN;}
            for(FoodEntry e:entries){
                MealRecipe eatenRecipe=catalog.find(e.sourceFoodId);Double price=profile.prices.get(e.sourceFoodId);
                if("planner".equals(e.source)&&eatenRecipe!=null){PriceModel.Cost cost=PriceModel.consumed(eatenRecipe,e.grams/eatenRecipe.yieldGrams,profile);r.consumedCost+=cost.amount;r.consumedCostKnown&=cost.allPricesEntered;}
                else if(price!=null)r.consumedCost+=price*e.grams/100;
                else r.consumedCostKnown=false;
            }
            for(int m=0;m<4;m++)r.remaining[m]=selected[m]&&profile.meals[m];
            List<IntentModel.Observation> observations=new ArrayList<>();for(FoodEntry e:history)if(e.sourceFoodId!=null)observations.add(new IntentModel.Observation(e.sourceFoodId,mealIndex(e.mealType),Instant.ofEpochMilli(e.loggedAt).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()));
            r.intent=new IntentModel(observations,day.toEpochDay());r.feedback.putAll(store.feedback());r.locked.putAll(kept);r.rejected.putAll(omitted);r.costWeight=costWeight;r.effortWeight=effortWeight;
            request=r;signature=day+"|"+Arrays.toString(r.targets)+"|"+Arrays.toString(r.consumed)+"|"+store.profileIdentity()+"|"+store.learningIdentity()+"|"+Arrays.toString(r.remaining)+"|"+store.personalRecipes();
            JSONObject saved=store.savedPlan(day);if(result==null&&saved.optString("signature").equals(signature)&&costWeight==1&&effortWeight==1) {
                JSONArray list=saved.optJSONArray("choices");if(list!=null)for(int i=0;i<list.length();i++){JSONObject item=list.getJSONObject(i);MealRecipe recipe=catalog.find(item.getString("recipe"));int m=item.getInt("meal");if(recipe!=null&&r.remaining[m]){DayPlanner.Choice c=new DayPlanner.Choice(m,recipe,item.getDouble("scale"));r.locked.put(m,c);if(item.optBoolean("locked")){kept.put(m,c);}}}
            }
            long start=System.nanoTime();DayPlanner.Result plan=new DayPlanner().plan(catalog.recipes,r);AppLogger.i("Planner generated meals="+plan.choices.size()+" ms="+(System.nanoTime()-start)/1000000);store.savePlan(day,plan,signature,kept.keySet());return plan;
        },plan->{locks.clear();locks.putAll(kept);result=plan;render();});render();
    }
    private void persist() {try{if(result!=null)store.savePlan(date,result,signature,locks.keySet());}catch(Exception e){message("Unable to save the meal lock");}}
    private void preferences() {
        LinearLayout form=column();text(form,"Choose what fits your life",19,true);
        PlannerProfile draft;try{draft=store.profile();}catch(Exception e){message("Unable to read preferences");return;}
        button(form,"Diet: "+draft.diet.name().replace('_',' '),()->new AlertDialog.Builder(this).setTitle("Eating pattern").setSingleChoiceItems(new String[]{"All foods","Vegetarian · no eggs","Vegetarian + eggs","Vegan"},draft.diet.ordinal(),(d,n)->{draft.diet=PlannerProfile.Diet.values()[n];d.dismiss();}).show());
        EditText daily=input(form,"Daily budget ₹ (0 = not set)",draft.dailyBudget,true);EditText weekly=input(form,"Weekly budget ₹ (0 = not set)",draft.weeklyBudget,true);EditText prep=input(form,"Maximum active preparation minutes",draft.maxPrepMinutes,true);
        CheckBox cook=check(form,"I can cook meals",draft.canCook);CheckBox learn=check(form,"Use my diary habits to improve suggestions",draft.learnFromHistory);
        button(form,"Allergies",()->chooseTags(draft.allergies));button(form,"Ingredients that do not suit me",()->chooseTags(draft.excludedIngredients));
        text(form,"Choose meals to include",15,true);CheckBox[] meals=new CheckBox[4];for(int m=0;m<4;m++)meals[m]=check(form,DayPlanner.mealName(m),draft.meals[m]);
        button(form,"Largest meal: "+DayPlanner.mealName(draft.largestMeal),()->new AlertDialog.Builder(this).setTitle("Preferred largest meal").setSingleChoiceItems(new String[]{"Breakfast","Lunch","Snacks","Dinner"},draft.largestMeal,(d,n)->{draft.largestMeal=n;d.dismiss();}).show());
        button(form,"Cuisine: "+draft.cuisine,()->new AlertDialog.Builder(this).setTitle("Preferred cuisine").setItems(new String[]{"Any","Indian","Andhra"},(d,n)->draft.cuisine=new String[]{"Any","Indian","Andhra"}[n]).show());
        dialog("Eating preferences",form,()->{try{draft.dailyBudget=number(daily);draft.weeklyBudget=number(weekly);double minutes=number(prep);if(minutes!=Math.rint(minutes))throw new IllegalArgumentException("Use whole preparation minutes");draft.maxPrepMinutes=(int)minutes;draft.canCook=cook.isChecked();draft.learnFromHistory=learn.isChecked();for(int m=0;m<4;m++)draft.meals[m]=meals[m].isChecked();store.saveProfile(draft);profile=draft;result=null;locks.clear();rejected.clear();render();return true;}catch(Exception e){message(e.getMessage());return false;}});
    }
    private void chooseTags(Set<String> selection) {
        String[] tags={"dairy","egg","peanut","tree_nut","gluten","soy","fish","shellfish","sesame","meat","honey"};
        String[] labels={"Milk / dairy","Eggs","Peanuts","Tree nuts","Wheat / gluten (including uncertified oats)","Soy","Fish","Shellfish","Sesame","Meat / fish","Honey"};
        boolean[] checked=new boolean[tags.length];for(int i=0;i<tags.length;i++)checked[i]=selection.contains(tags[i]);
        new AlertDialog.Builder(this).setTitle("Always exclude these ingredients").setMultiChoiceItems(labels,checked,(d,n,on)->{if(on)selection.add(tags[n]);else selection.remove(tags[n]);}).setPositiveButton("Done",null).show();
    }
    private void ratings() {
        LinearLayout body=column();text(body,"Your choices outrank learned habits. Avoid and Never are left out of automatic plans.",14,false);
        button(body,"Find a food to rate",this::searchFood);
        button(body,"Choose from my foods and meals",()->{List<String> ids=new ArrayList<>(suggestions.keySet());String[] names=ids.stream().map(suggestions::get).toArray(String[]::new);new AlertDialog.Builder(this).setTitle("Foods and familiar meals").setItems(names,(d,n)->rate(ids.get(n),names[n])).show();});
        for(Map.Entry<String,FoodPreference> e:profile.ratings.entrySet())button(body,profile.ratingNames.getOrDefault(e.getKey(),e.getKey())+" · "+e.getValue().label,()->rate(e.getKey(),profile.ratingNames.getOrDefault(e.getKey(),e.getKey())));
        button(body,"Reset feedback learning",()->{store.resetLearning();message("Feedback reset. Explicit preferences are kept. Turn off diary learning in Eating preferences if desired.");});
        dialog("Foods I like or avoid",body,null);
    }
    private void rate(String id,String name) {
        FoodPreference[] values=FoodPreference.values();String[] labels=Arrays.stream(values).map(v->v.label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(name).setSingleChoiceItems(labels,profile.rating(id).ordinal(),(d,n)->{try{PlannerProfile p=store.profile();if(values[n]==FoodPreference.UNSET){p.ratings.remove(id);p.ratingNames.remove(id);}else{p.ratings.put(id,values[n]);p.ratingNames.put(id,name);}store.saveProfile(p);profile=p;result=null;locks.clear();rejected.clear();d.dismiss();render();}catch(Exception e){message("Unable to save this food choice");}}).show();
    }
    private void searchFood() {
        LinearLayout body=column();EditText query=input(body,"Search foods",null,false);LinearLayout rows=column();body.addView(rows);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Add a food preference").setView(scroll(body)).setNegativeButton("Close",null).create();dialog.show();
        final long[] token={0};query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int before,int count){long t=++token[0];String q=s.toString().trim();if(q.length()<2)return;worker.execute(()->{try{List<CatalogFood> foods=db.search(q,20);runOnUiThread(()->{if(closed||!dialog.isShowing()||t!=token[0])return;rows.removeAllViews();for(CatalogFood f:foods)button(rows,f.name,()->{dialog.dismiss();rate(f.code,f.name);});});}catch(Exception ignored){}});}public void afterTextChanged(Editable e){}});
    }
    private void prices() {
        LinearLayout body=column();text(body,"Enter ₹ per 100 g in the displayed state. Starter prices are illustrative estimates; blank keeps the estimate.",14,false);Map<String,EditText> fields=new LinkedHashMap<>();
        for(PlannerFood f:catalog.foods.values()){EditText field=input(body,f.name+" · "+f.state+" · estimate "+money(f.referencePrice),profile.prices.get(f.id),true);fields.put(f.id,field);}
        dialog("My local prices",body,()->{try{PlannerProfile p=store.profile();for(Map.Entry<String,EditText> e:fields.entrySet()){String v=e.getValue().getText().toString().trim();if(v.isEmpty())p.prices.remove(e.getKey());else p.prices.put(e.getKey(),number(e.getValue()));}store.saveProfile(p);profile=p;result=null;locks.clear();rejected.clear();render();return true;}catch(Exception e){message("Use valid non-negative prices");return false;}});
    }
    private void feedback(DayPlanner.Choice c) {
        String[] labels={"Liked it","Disliked the taste","Too expensive","Too much food","Too much cooking","Didn't suit me · stop recommending"};IntentModel.Feedback[] reasons=IntentModel.Feedback.values();
        new AlertDialog.Builder(this).setTitle("What should change?").setItems(labels,(d,n)->{try{store.recordFeedback(c.recipe.id,reasons[n]);if(reasons[n]==IntentModel.Feedback.UNSUITABLE){PlannerProfile p=store.profile();p.ratings.put(c.recipe.id,FoodPreference.NEVER);p.ratingNames.put(c.recipe.id,c.recipe.name);store.saveProfile(p);profile=p;}message("Feedback saved");result=null;locks.clear();rejected.clear();render();}catch(Exception e){message("Unable to save feedback");}}).show();
    }
    private void confirmConsumption(DayPlanner.Choice c) {
        LinearLayout body=column();EditText amount=input(body,"Servings actually eaten (0.5–2)",c.scale,true);
        dialog("Confirm "+c.recipe.name,body,()->{try{double scale=number(amount);if(scale<0.5||scale>2)throw new IllegalArgumentException("Use 0.5–2 servings; log other amounts through the diary");final LocalDate day=date;
            async(()->{double[] n=c.recipe.nutrition(scale);MicronutrientValues micros=MicronutrientValues.empty();for(Micronutrient nutrient:Micronutrient.values()){double value=0;boolean known=true;for(MealRecipe.Ingredient i:c.recipe.ingredients){CatalogFood f=catalog.sourceFoods.get(i.food.id);Double part=f==null?null:f.micronutrients.get(nutrient);if(part==null){known=false;break;}value+=part*i.grams/100*scale;}if(known)micros=micros.with(nutrient,value);}
                long at=day.equals(LocalDate.now())?System.currentTimeMillis():day.atTime(new int[]{8,13,17,20}[c.meal],0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();FoodEntry entry=new FoodEntry(0,c.recipe.name,c.recipe.yieldGrams*scale,n[0],n[1],n[2],n[3],at,mealKey(c.meal),"planner",c.recipe.id,"plan:"+day+":"+c.meal+":"+c.recipe.id,micros);
                return diary.insertPlannerMealOnce(entry);},id->{message("Meal recorded in your diary");result=null;locks.remove(c.meal);history=Collections.emptyList();load();});return true;
        }catch(Exception e){message(e.getMessage());return false;}});
    }
    private void message(String text) {Toast.makeText(this,text==null?"Review these values":text,Toast.LENGTH_LONG).show();}
    private interface Save {boolean save();}
    private void dialog(String title,LinearLayout content,Save save) {
        AlertDialog d=new AlertDialog.Builder(this).setTitle(title).setView(scroll(content)).setNegativeButton("Close",null).setPositiveButton(save==null?"Done":"Save",null).create();d.show();d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(save==null||save.save())d.dismiss();});
    }
    private ScrollView scroll(LinearLayout content) {ScrollView s=new ScrollView(this);content.setPadding(dp(18),dp(12),dp(18),dp(24));s.addView(content);return s;}
    private LinearLayout column() {LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return box;}
    private LinearLayout card(LinearLayout parent) {LinearLayout box=column();LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(12),0,dp(8));box.setLayoutParams(lp);box.setPadding(dp(16),dp(12),dp(16),dp(12));FrostedUi.surface(box,FrostedSurfaceDrawable.Material.CARD);parent.addView(box);return box;}
    private TextView text(LinearLayout parent,String value,int size,boolean bold) {TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(getColor(bold?R.color.text_primary:R.color.text_secondary));v.setPadding(0,dp(5),0,dp(5));if(bold)v.setTypeface(null,android.graphics.Typeface.BOLD);parent.addView(v);return v;}
    private Button button(LinearLayout parent,String value,Runnable click) {Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setTextColor(getColor(R.color.accent));b.setMinHeight(dp(48));b.setOnClickListener(v->{if(!busy)click.run();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));parent.addView(b,lp);FrostedUi.surface(b,FrostedSurfaceDrawable.Material.CONTROL);return b;}
    private EditText input(LinearLayout parent,String label,Object value,boolean numeric) {text(parent,label,13,false);EditText e=new EditText(this);e.setTextColor(getColor(R.color.text_primary));e.setHintTextColor(getColor(R.color.text_secondary));e.setSingleLine(true);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});e.setInputType(numeric?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL:InputType.TYPE_CLASS_TEXT);if(value!=null)e.setText(value.toString());e.setPadding(dp(12),dp(8),dp(12),dp(8));parent.addView(e);FrostedUi.surface(e,FrostedSurfaceDrawable.Material.INPUT);return e;}
    private CheckBox check(LinearLayout parent,String label,boolean value) {CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(getColor(R.color.text_primary));c.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));c.setChecked(value);c.setMinHeight(dp(48));parent.addView(c);return c;}
    private static double number(EditText e) {String s=e.getText().toString().trim();return s.isEmpty()?0:Double.parseDouble(s);}
    private static String money(double amount) {return String.format(Locale.getDefault(),"₹%.0f",amount);}
    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}
    private static int mealIndex(String key) {switch(key){case "breakfast":return 0;case "lunch":return 1;case "snacks":return 2;default:return 3;}}
    private static String mealKey(int index) {return new String[]{"breakfast","lunch","snacks","dinner"}[index];}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("day",date.toString());super.onSaveInstanceState(out);}
    @Override protected void onResume(){super.onResume();foreground=true;if(!ready&&!busy)load();else if(ready)render();}
    @Override protected void onPause(){foreground=false;generation++;busy=false;ready=false;result=null;locks.clear();rejected.clear();super.onPause();}
    @Override protected void onDestroy(){closed=true;generation++;worker.execute(()->{if(db!=null)db.close();if(diary!=null)diary.close();});worker.shutdown();super.onDestroy();}
}
