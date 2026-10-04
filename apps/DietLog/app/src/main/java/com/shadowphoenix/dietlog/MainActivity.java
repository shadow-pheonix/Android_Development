package com.shadowphoenix.dietlog;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import com.shadowphoenix.dietlog.data.DiaryDbHelper;
import com.shadowphoenix.dietlog.data.FoodEntry;
import com.shadowphoenix.dietlog.diagnostics.AppLogger;
import com.shadowphoenix.dietlog.diagnostics.CrashHandler;
import com.shadowphoenix.dietlog.diagnostics.DiagnosticsExporter;
import com.shadowphoenix.dietlog.food.CatalogFood;
import com.shadowphoenix.dietlog.food.FoodCatalogDb;

import java.text.DateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    private DiaryDbHelper diary;
    private FoodCatalogDb catalog;
    private EditText searchInput, nameInput, gramsInput, caloriesInput, proteinInput, carbsInput, fatInput;
    private TextView summaryText, databaseStatusText, selectedFoodDetails;
    private LinearLayout searchResultsContainer, entriesContainer;
    private CatalogFood selectedFood;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.init(this); CrashHandler.install(this); AppLogger.i("MainActivity created");
        setContentView(R.layout.activity_main);
        diary = new DiaryDbHelper(this);
        bindViews();
        try {
            catalog = new FoodCatalogDb(this);
            databaseStatusText.setText(String.format(Locale.getDefault(),
                    "Offline: %,d foods • %,d Indian packaged • %,d cooked Indian dishes",
                    catalog.count(), catalog.countBySource("OFF"), catalog.countBySource("INDB")));
        } catch (Exception e) {
            AppLogger.e("Offline catalog failed to open", e);
            databaseStatusText.setText("Offline database unavailable in this build");
        }
        ((TextView)findViewById(R.id.dateText)).setText(DateFormat.getDateInstance(DateFormat.FULL).format(new Date()));
        findViewById(R.id.searchButton).setOnClickListener(v -> search());
        findViewById(R.id.addButton).setOnClickListener(v -> addFood());
        findViewById(R.id.exportButton).setOnClickListener(v -> exportDiagnostics());
        refresh();
    }

    private void bindViews() {
        searchInput=findViewById(R.id.searchInput); nameInput=findViewById(R.id.nameInput); gramsInput=findViewById(R.id.gramsInput);
        caloriesInput=findViewById(R.id.caloriesInput); proteinInput=findViewById(R.id.proteinInput); carbsInput=findViewById(R.id.carbsInput);
        fatInput=findViewById(R.id.fatInput); summaryText=findViewById(R.id.summaryText); databaseStatusText=findViewById(R.id.databaseStatusText);
        selectedFoodDetails=findViewById(R.id.selectedFoodDetails); searchResultsContainer=findViewById(R.id.searchResultsContainer);
        entriesContainer=findViewById(R.id.entriesContainer);
    }

    private void search() {
        searchResultsContainer.removeAllViews();
        if (catalog == null) return;
        String q=searchInput.getText().toString().trim(); if(q.isEmpty()) return;
        List<CatalogFood> results=catalog.search(q,20);
        AppLogger.i("Offline search queryLength="+q.length()+" results="+results.size());
        if(results.isEmpty()){searchResultsContainer.addView(makeText(getString(R.string.no_results),false));return;}
        for(CatalogFood f:results){
            TextView row=makeText(resultLine(f),true); row.setPadding(0,dp(11),0,dp(11));
            row.setOnClickListener(v -> selectFood(f)); searchResultsContainer.addView(row);
        }
    }

    private String resultLine(CatalogFood f) {
        String brand=f.brand==null?"":f.brand+" • ";
        String kcal=f.calories==null?"?":String.format(Locale.getDefault(),"%.0f",f.calories);
        return f.name+"\n"+brand+f.source+" • "+kcal+" kcal/100g"+(f.barcode==null?"":" • "+f.barcode);
    }

    private void selectFood(CatalogFood f) {
        selectedFood=f; nameInput.setText(f.name); gramsInput.setText(f.servingG==null?"100":fmt(f.servingG));
        setNumber(caloriesInput,f.calories); setNumber(proteinInput,f.protein); setNumber(carbsInput,f.carbs); setNumber(fatInput,f.fat);
        selectedFoodDetails.setVisibility(View.VISIBLE); selectedFoodDetails.setText(details(f));
        AppLogger.i("Catalog food selected source="+f.source+" code="+f.code);
    }

    private String details(CatalogFood f) {
        StringBuilder s=new StringBuilder();
        s.append(f.name).append('\n');
        append(s,"Brand",f.brand); append(s,"Pack",f.quantity); append(s,"Category",f.category); append(s,"Barcode",f.barcode);
        append(s,"Serving",f.servingSize); if(f.servingG!=null) append(s,"Serving grams",fmt(f.servingG)+" g");
        s.append("Per 100 g: ").append(n(f.calories," kcal")).append(" • P ").append(n(f.protein," g"))
                .append(" • C ").append(n(f.carbs," g")).append(" • F ").append(n(f.fat," g")).append('\n');
        append(s,"Sugars",nOrNull(f.sugars," g/100g")); append(s,"Fiber",nOrNull(f.fiber," g/100g"));
        append(s,"Saturated fat",nOrNull(f.saturatedFat," g/100g")); append(s,"Salt",nOrNull(f.salt," g/100g"));
        append(s,"Sodium",nOrNull(f.sodium," g/100g")); append(s,"Nutri-Score",f.nutriscore);
        append(s,"NOVA",f.nova==null?null:String.valueOf(f.nova)); append(s,"Allergens",f.allergens); append(s,"Labels",f.labels);
        append(s,"Ingredients",f.ingredients); append(s,"Source",f.source);
        return s.toString().trim();
    }

    private static void append(StringBuilder s,String label,String value){if(value!=null&&!value.trim().isEmpty())s.append(label).append(": ").append(value).append('\n');}
    private String n(Double v,String suffix){return v==null?"?":fmt(v)+suffix;} private String nOrNull(Double v,String suffix){return v==null?null:fmt(v)+suffix;}
    private String fmt(Double v){return String.format(Locale.US,"%.1f",v);} private void setNumber(EditText e,Double v){e.setText(v==null?"":fmt(v));}

    private void addFood() {
        try {
            String name=nameInput.getText().toString().trim(); if(name.isEmpty())throw new IllegalArgumentException("Enter a food name");
            double grams=positive(gramsInput,"amount"), calories=nonNegative(caloriesInput,"calories"), protein=nonNegative(proteinInput,"protein"), carbs=nonNegative(carbsInput,"carbs"), fat=nonNegative(fatInput,"fat");
            String source=selectedFood==null?"manual":selectedFood.source; String sourceId=selectedFood==null?null:selectedFood.code;
            FoodEntry entry=FoodEntry.fromPer100g(name,grams,calories,protein,carbs,fat,System.currentTimeMillis(),source,sourceId);
            long id=diary.insert(entry); AppLogger.i("Food entry added id="+id+" source="+source);
            clearInputs(); refresh(); Toast.makeText(this,"Food added",Toast.LENGTH_SHORT).show();
        } catch(IllegalArgumentException e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
        catch(Exception e){AppLogger.e("Failed to add food",e);Toast.makeText(this,"Unable to add food",Toast.LENGTH_LONG).show();}
    }

    private void refresh(){double[] t=diary.getTodayTotals();summaryText.setText(String.format(Locale.getDefault(),"%.0f kcal\nProtein %.1f g   •   Carbs %.1f g   •   Fat %.1f g",t[0],t[1],t[2],t[3]));
        List<FoodEntry> entries=diary.getTodayEntries();entriesContainer.removeAllViews();if(entries.isEmpty()){entriesContainer.addView(makeText(getString(R.string.empty_entries),false));return;}for(FoodEntry e:entries)entriesContainer.addView(makeText(String.format(Locale.getDefault(),"%s — %.0f g\n%.0f kcal  •  P %.1f  C %.1f  F %.1f g",e.name,e.grams,e.calories,e.protein,e.carbs,e.fat),true));}
    private TextView makeText(String t,boolean prominent){TextView v=new TextView(this);v.setText(t);v.setTextSize(prominent?16f:14f);v.setTextColor(getColor(R.color.text_primary));return v;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void exportDiagnostics(){try{AppLogger.i("Diagnostics export requested");DiagnosticsExporter.ExportResult r=DiagnosticsExporter.export(this,diary);Toast.makeText(this,"Saved: "+r.displayPath,Toast.LENGTH_LONG).show();}catch(Exception e){AppLogger.e("Diagnostics export failed",e);Toast.makeText(this,"Diagnostics export failed: "+e.getMessage(),Toast.LENGTH_LONG).show();}}
    private double positive(EditText i,String l){double v=parse(i,l);if(v<=0)throw new IllegalArgumentException(l+" must be greater than zero");return v;}
    private double nonNegative(EditText i,String l){double v=parse(i,l);if(v<0)throw new IllegalArgumentException(l+" cannot be negative");return v;}
    private double parse(EditText i,String l){String r=i.getText().toString().trim();if(r.isEmpty())throw new IllegalArgumentException("Enter "+l);try{return Double.parseDouble(r);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid "+l);}}
    private void clearInputs(){nameInput.setText("");gramsInput.setText("");caloriesInput.setText("");proteinInput.setText("");carbsInput.setText("");fatInput.setText("");selectedFood=null;selectedFoodDetails.setVisibility(View.GONE);nameInput.requestFocus();}
    @Override protected void onDestroy(){if(catalog!=null)catalog.close();diary.close();super.onDestroy();}
}
