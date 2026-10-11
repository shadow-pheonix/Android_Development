package com.shadowphoenix.dietlog.planner;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.shadowphoenix.dietlog.R;
import com.shadowphoenix.dietlog.data.*;
import com.shadowphoenix.dietlog.food.FoodCatalogDb;
import com.shadowphoenix.dietlog.diagnostics.*;
import com.shadowphoenix.dietlog.profile.*;
import com.shadowphoenix.dietlog.ui.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** One category at a time, with persistent drafts and explicit ratings on every card. */
public final class PreferenceSetupActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, View> fields = new LinkedHashMap<>();
    private final Map<String, RadioGroup> ratings = new LinkedHashMap<>();
    private PlannerStore store;
    private PlannerCatalog catalog;
    private FoodCatalogDb db;
    private PlannerProfile profile;
    private PreferenceSetup setup;
    private IntentModel intent;
    private double proteinRatio;
    private LinearLayout root, body, footer;
    private ScrollView scroll;
    private TextView step, status, error;
    private ProgressBar progress;
    private Button back, next;
    private boolean busy, foreground, closed, loaded;
    private long generation;
    private Bundle restored;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        AppLogger.init(this);
        CrashHandler.install(this);
        store = new PlannerStore(this);
        restored = saved;
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = column();
        root.setBackgroundResource(R.drawable.bg_frosted_scene);
        LinearLayout header = column();
        header.setPadding(dp(18), dp(8), dp(18), 0);
        button(header, "Save & leave setup", this::leave);
        step = text(header, "Your eating preferences", 14, true);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        header.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
        root.addView(header);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column();
        body.setPadding(dp(18), dp(16), dp(18), dp(16));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = column();
        footer.setPadding(dp(18), dp(4), dp(18), dp(12));
        status = text(footer, "Your choices are saved on this device", 12, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        back = button(row, "Back", () -> navigate(-1));
        next = button(row, "Next", () -> navigate(1));
        back.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        next.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(row);
        root.addView(footer);
        setContentView(root);
        root.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets bars =
                                insets.getInsets(
                                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    } else
                        v.setPadding(
                                insets.getSystemWindowInsetLeft(),
                                insets.getSystemWindowInsetTop(),
                                insets.getSystemWindowInsetRight(),
                                insets.getSystemWindowInsetBottom());
                    return insets;
                });
        root.requestApplyInsets();
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> void work(Work<T> work, java.util.function.Consumer<T> done) {
        long token = ++generation;
        busy = true;
        enable(root, false);
        status.setText("Saving / loading…");
        worker.execute(
                () -> {
                    try {
                        T value = work.run();
                        runOnUiThread(
                                () -> {
                                    if (!closed && foreground && token == generation) {
                                        busy = false;
                                        enable(root, true);
                                        status.setText("Saved on this device");
                                        done.accept(value);
                                    }
                                });
                    } catch (Exception e) {
                        AppLogger.i("Preference setup failed type=" + e.getClass().getSimpleName());
                        runOnUiThread(
                                () -> {
                                    if (!closed && foreground && token == generation) {
                                        // Restore the visible choice when an atomic save fails.
                                        for (Map.Entry<String, RadioGroup> row :
                                                ratings.entrySet()) {
                                            RadioGroup group = row.getValue();
                                            group.clearCheck();
                                            for (int i = 0; i < group.getChildCount(); i++) {
                                                View option = group.getChildAt(i);
                                                if (option.getTag() == profile.rating(row.getKey()))
                                                    group.check(option.getId());
                                            }
                                        }
                                        busy = false;
                                        enable(root, true);
                                        status.setText("Not saved · please retry");
                                        showError(
                                                e instanceof IllegalArgumentException
                                                        ? e.getMessage()
                                                        : "Unable to save or load preferences. Please retry.");
                                        if (!loaded) button(body, "Retry", this::load);
                                    }
                                });
                    }
                });
    }

    private void load() {
        body.removeAllViews();
        text(body, "Loading your preferences…", 22, true);
        work(
                () -> {
                    PlannerProfile p = store.profile();
                    PreferenceSetup s = store.setup();
                    if (db == null) db = new FoodCatalogDb(this);
                    PlannerCatalog c =
                            new PlannerCatalog(
                                    this, db, store.personalRecipes(), store.extraFoods());
                    List<IntentModel.Observation> observations = new ArrayList<>();
                    try (DiaryDbHelper diary = new DiaryDbHelper(this)) {
                        for (FoodEntry e : diary.getPlannerHistory()) {
                            String id =
                                    e.sourceFoodId == null || e.sourceFoodId.trim().isEmpty()
                                            ? "manual:" + e.foodKey()
                                            : e.sourceFoodId;
                            int meal =
                                    Arrays.asList("breakfast", "lunch", "snacks", "dinner")
                                            .indexOf(e.mealType);
                            observations.add(
                                    new IntentModel.Observation(
                                            p.canonicalId(id),
                                            meal,
                                            Instant.ofEpochMilli(e.loggedAt)
                                                    .atZone(ZoneId.systemDefault())
                                                    .toLocalDate()
                                                    .toEpochDay(),
                                            e.calories));
                        }
                    }
                    IntentModel evidence =
                            new IntentModel(observations, LocalDate.now().toEpochDay());
                    ProfilePreferences pp = new ProfilePreferences(this);
                    double ratio = 0;
                    if (pp.hasCompletedPersonalProfile()) {
                        ProfileSettings targets = pp.load();
                        double kcal =
                                CalorieCalculator.calculate(targets, LocalDate.now()).calorieTarget;
                        if (kcal > 0) ratio = targets.proteinGoalG / kcal;
                    }
                    if (s.completed) {
                        s = new PreferenceSetup();
                        if ("rate".equals(getIntent().getStringExtra("mode"))) {
                            s.buildBatches(c.recipes, c.foods.values(), p, evidence, ratio);
                            s.page = PreferenceSetup.BASICS;
                        }
                        store.saveSetup(p, s);
                    }
                    return new Object[] {p, s, c, evidence, ratio};
                },
                value -> {
                    profile = (PlannerProfile) value[0];
                    setup = (PreferenceSetup) value[1];
                    catalog = (PlannerCatalog) value[2];
                    intent = (IntentModel) value[3];
                    proteinRatio = (Double) value[4];
                    if (restored != null && restored.containsKey("draft")) {
                        try {
                            setup =
                                    PreferenceSetup.fromJson(
                                            new JSONObject(restored.getString("draft")));
                        } catch (JSONException ignored) {
                        }
                        restored = null;
                    }
                    loaded = true;
                    render();
                });
    }

    private void render() {
        status.setText(
                setup.page < PreferenceSetup.BASICS
                        ? "Next saves this step · leave anytime to resume"
                        : "Ratings save as you tap · Next leaves undecided cards unrated");
        fields.clear();
        ratings.clear();
        body.removeAllViews();
        int page = setup.page;
        boolean batch = page >= PreferenceSetup.BASICS && page < setup.reviewPage();
        String[] titles = {
            "How do you eat?",
            "Any food allergies?",
            "What doesn't suit you?",
            "Your meal routine",
            "What feels affordable?",
            "Cooking that fits your day"
        };
        String[] notes = {
            "Choose your eating pattern. You can change it anytime.",
            "Choose known allergies. These ingredients stay out of recommendations.",
            "Choose ingredients you need to leave out for comfort or other reasons.",
            "Choose meals you want and your preferred portion balance.",
            "Use your own daily or weekly food budget. Leave blank if you haven't decided.",
            "Tell us what you can prepare. Your ratings come next, recipes first."
        };
        String title =
                page < PreferenceSetup.BASICS
                        ? titles[page]
                        : page == setup.reviewPage()
                                ? "Ready to make it yours"
                                : batchTitle(setup.batches.get(page - PreferenceSetup.BASICS));
        TextView heading = text(body, title, 26, true);
        heading.setAccessibilityHeading(true);
        String note =
                page < PreferenceSetup.BASICS
                        ? notes[page]
                        : batch
                                ? "Rate what you know. Love is preferred; Okay is manageable. Avoid and Never recommend stay out of plans. Unrated means undecided."
                                : "These choices shape your standard plan. You can keep rating and update preferences anytime.";
        text(body, note, 14, false);
        if (page < PreferenceSetup.BASICS) {
            step.setText("Preferences · " + (page + 1) + " of " + PreferenceSetup.BASICS);
            progress.setMax(PreferenceSetup.BASICS);
            progress.setProgress(page + 1);
        } else {
            step.setText(
                    page == setup.reviewPage()
                            ? "Review your preferences"
                            : "Taste discovery · list "
                                    + (page - PreferenceSetup.BASICS + 1)
                                    + " of "
                                    + setup.batches.size());
            progress.setMax(setup.batches.size() + 1);
            progress.setProgress(page - PreferenceSetup.BASICS + 1);
        }
        back.setText(page == 0 ? "Leave" : "Back");
        next.setText(page == setup.reviewPage() ? "Save & open my plan" : "Next");
        switch (page) {
            case 0:
                choices(
                        "diet",
                        "Eating pattern",
                        new String[] {
                            "All foods", "Vegetarian · no eggs", "Vegetarian + eggs", "Vegan"
                        },
                        profile.diet.ordinal());
                choices(
                        "cuisine",
                        "Cuisine you enjoy",
                        new String[] {"Any", "Indian", "Andhra"},
                        Math.max(
                                0,
                                Arrays.asList("Any", "Indian", "Andhra").indexOf(profile.cuisine)));
                break;
            case 1:
                tags("allergy", profile.allergies);
                break;
            case 2:
                tags("exclude", profile.excludedIngredients);
                break;
            case 3:
                for (int m = 0; m < 4; m++)
                    check("meal" + m, DayPlanner.mealName(m), profile.meals[m]);
                choices(
                        "largest",
                        "Preferred largest meal",
                        new String[] {
                            "Balanced · gentle diary learning",
                            "Breakfast",
                            "Lunch",
                            "Snacks",
                            "Dinner"
                        },
                        profile.largestMeal + 1);
                break;
            case 4:
                input(
                        "daily",
                        "Daily food budget ₹",
                        profile.dailyBudget == 0 ? "" : String.valueOf(profile.dailyBudget));
                input(
                        "weekly",
                        "Weekly food budget ₹",
                        profile.weeklyBudget == 0 ? "" : String.valueOf(profile.weeklyBudget));
                text(
                        body,
                        "Blank or 0 means no budget set. If both are entered, the smaller daily allowance applies. Local ingredient prices can be edited from your plan.",
                        13,
                        false);
                break;
            case 5:
                check("cook", "I can cook meals", profile.canCook);
                input(
                        "prep",
                        "Maximum active preparation time · minutes",
                        String.valueOf(profile.maxPrepMinutes));
                check(
                        "learn",
                        "Allow continuous, secondary diary learning",
                        profile.learnFromHistory);
                text(
                        body,
                        "Each new plan refreshes recent diary evidence. Logging shows familiarity, not a like or proof a food suits you. Your explicit choices always take priority.",
                        13,
                        false);
                break;
            default:
                if (batch) renderBatch(setup.batches.get(page - PreferenceSetup.BASICS));
                else review();
        }
        error = text(body, "", 14, false);
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        restoreFields();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private String batchTitle(PreferenceSetup.Batch b) {
        return b.recipes
                ? DayPlanner.mealName(b.meal) + " recipes"
                : "Ingredients · a little more detail";
    }

    private void renderBatch(PreferenceSetup.Batch batch) {
        int shown = 0;
        for (String id : batch.ids) {
            if (batch.recipes) {
                MealRecipe recipe = catalog.find(id);
                if (recipe == null) continue;
                LinearLayout card = card();
                text(card, recipe.name, 20, true);
                StringJoiner parts = new StringJoiner(", ");
                for (MealRecipe.Ingredient i : recipe.ingredients) parts.add(i.food.name);
                text(card, parts.toString(), 13, false);
                double[] n = recipe.nutrition(1);
                text(
                        card,
                        String.format(
                                Locale.getDefault(),
                                "Base serving · %.0f kcal · %.0f g protein\n%d min active prep · ₹%.0f estimated",
                                n[0],
                                n[1],
                                recipe.prepMinutes,
                                PriceModel.consumed(recipe, 1, profile).amount),
                        13,
                        false);
                ratingCard(card, id, recipe.name);
                shown++;
            } else {
                PlannerFood food = null;
                for (PlannerFood candidate : catalog.foods.values())
                    if (candidate.id.equals(id)) {
                        food = candidate;
                        break;
                    }
                if (food == null) continue;
                LinearLayout card = card();
                text(card, food.name, 20, true);
                text(card, food.state, 13, false);
                if (!Collections.disjoint(food.tags, profile.allergies)
                        || !Collections.disjoint(food.tags, profile.excludedIngredients))
                    text(
                            card,
                            "Excluded by your suitability settings, regardless of a taste rating.",
                            13,
                            false);
                ratingCard(card, id, food.name);
                shown++;
            }
        }
        if (shown == 0)
            text(
                    body,
                    "These recipes are no longer available. Continue to the next list.",
                    15,
                    false);
        text(
                body,
                "Tap a choice to save it immediately. Next leaves undecided cards unrated.",
                12,
                false);
    }

    private void ratingCard(LinearLayout card, String id, String name) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        FoodPreference[] choices = {
            FoodPreference.LOVE, FoodPreference.OKAY, FoodPreference.AVOID, FoodPreference.NEVER
        };
        for (int i = 0; i < choices.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setTag(choices[i]);
            b.setText(new String[] {"Love", "Okay · manageable", "Avoid", "Never recommend"}[i]);
            b.setTextColor(getColor(R.color.text_primary));
            b.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            b.setMinHeight(dp(48));
            b.setContentDescription(name + " · " + b.getText());
            group.addView(b);
            if (profile.rating(id) == choices[i]) group.check(b.getId());
        }
        card.addView(group);
        ratings.put(id, group);
        group.setOnCheckedChangeListener(
                (g, checked) -> {
                    if (busy || checked == -1) return;
                    FoodPreference choice = (FoodPreference) g.findViewById(checked).getTag();
                    PlannerProfile changed = profile.copy();
                    changed.ratings.put(id, choice);
                    changed.ratingNames.put(id, name);
                    try {
                        PreferenceSetup checkpoint = setup.copy();
                        work(
                                () -> {
                                    store.saveSetup(changed, checkpoint);
                                    return true;
                                },
                                ok -> {
                                    profile = changed;
                                    setup = checkpoint;
                                    status.setText("Saved · " + name + " · " + choice.label);
                                });
                    } catch (JSONException e) {
                        showError("Unable to save this choice");
                    }
                });
    }

    private void review() {
        text(body, profile.diet.name().replace('_', ' '), 18, true);
        for (FoodPreference choice :
                new FoodPreference[] {
                    FoodPreference.LOVE,
                    FoodPreference.OKAY,
                    FoodPreference.AVOID,
                    FoodPreference.NEVER
                }) {
            long count = profile.ratings.values().stream().filter(v -> v == choice).count();
            text(body, choice.label + " · " + count, 15, false);
        }
        text(
                body,
                profile.dailyBudget > 0
                        ? "Daily budget ₹" + profile.dailyBudget
                        : "Daily budget not set",
                14,
                false);
        text(
                body,
                "Allergies: "
                        + (profile.allergies.isEmpty()
                                ? "None selected"
                                : String.join(", ", profile.allergies)),
                14,
                false);
        text(
                body,
                "Diary learning: "
                        + (profile.learnFromHistory ? "Continuous and secondary" : "Off"),
                14,
                false);
        if (setup.batches.stream().noneMatch(b -> b.recipes))
            text(
                    body,
                    "No starter recipe matches your current eating/cooking settings. You can add your own suitable recipe from the plan.",
                    14,
                    false);
        button(
                body,
                "Rate more / review recipe lists",
                () -> {
                    try {
                        PreferenceSetup s = setup.copy();
                        s.page = PreferenceSetup.BASICS;
                        save(profile.copy(), s, this::render);
                    } catch (JSONException e) {
                        showError("Unable to open recipe lists");
                    }
                });
    }

    private void navigate(int direction) {
        if (busy || !loaded) return;
        if (direction < 0 && setup.page == 0) {
            leave();
            return;
        }
        hideKeyboard();
        try {
            PreferenceSetup state = setup.copy();
            captureFields(state);
            PlannerProfile p = profile.copy();
            try {
                readFields(p);
                p.validate();
                state.draft.remove(String.valueOf(setup.page));
            } catch (IllegalArgumentException e) {
                if (direction > 0) throw e;
                p = profile.copy();
            }
            if (setup.page == setup.reviewPage() && direction > 0) {
                state.completed = true;
                save(
                        p,
                        state,
                        () -> {
                            setResult(RESULT_OK);
                            finish();
                        });
                return;
            }
            if (setup.page == PreferenceSetup.BASICS - 1
                    && direction > 0
                    && (state.batches.isEmpty()
                            || !state.selectionIdentity.equals(
                                    PreferenceSetup.selectionIdentity(p))))
                state.buildBatches(
                        catalog.recipes, catalog.foods.values(), p, intent, proteinRatio);
            state.page = Math.max(0, Math.min(state.reviewPage(), setup.page + direction));
            save(p, state, this::render);
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
        } catch (JSONException e) {
            showError("Unable to save this step");
        }
    }

    private void save(PlannerProfile p, PreferenceSetup state, Runnable done) {
        work(
                () -> {
                    store.saveSetup(p, state);
                    return true;
                },
                ok -> {
                    profile = p;
                    setup = state;
                    done.run();
                });
    }

    private void readFields(PlannerProfile p) {
        switch (setup.page) {
            case 0:
                p.diet = PlannerProfile.Diet.values()[selected("diet")];
                p.cuisine = new String[] {"Any", "Indian", "Andhra"}[selected("cuisine")];
                break;
            case 1:
                readTags("allergy", p.allergies);
                break;
            case 2:
                readTags("exclude", p.excludedIngredients);
                break;
            case 3:
                for (int m = 0; m < 4; m++) p.meals[m] = checked("meal" + m);
                p.largestMeal = selected("largest") - 1;
                break;
            case 4:
                p.dailyBudget = number("daily");
                p.weeklyBudget = number("weekly");
                break;
            case 5:
                double minutes = number("prep");
                if (minutes != Math.rint(minutes) || minutes < 0 || minutes > 240)
                    throw new IllegalArgumentException(
                            "Enter preparation time in whole minutes from 0–240");
                p.maxPrepMinutes = (int) minutes;
                p.canCook = checked("cook");
                p.learnFromHistory = checked("learn");
                break;
        }
    }

    private static final String[] TAGS = {
        "dairy",
        "egg",
        "peanut",
        "tree_nut",
        "gluten",
        "soy",
        "fish",
        "shellfish",
        "sesame",
        "meat",
        "honey"
    };

    private void tags(String key, Set<String> saved) {
        String[] labels = {
            "Milk / dairy",
            "Eggs",
            "Peanuts",
            "Tree nuts",
            "Wheat / gluten (including uncertified oats)",
            "Soy",
            "Fish",
            "Shellfish",
            "Sesame",
            "Meat / fish",
            "Honey"
        };
        for (int i = 0; i < TAGS.length; i++)
            check(key + TAGS[i], labels[i], saved.contains(TAGS[i]));
        text(
                body,
                "Nothing selected? Next keeps this list empty. Specific ingredient dislikes can be rated after recipes.",
                13,
                false);
    }

    private void readTags(String key, Set<String> tags) {
        for (String tag : TAGS) {
            if (checked(key + tag)) tags.add(tag);
            else tags.remove(tag);
        }
    }

    private boolean checked(String key) {
        return ((CheckBox) fields.get(key)).isChecked();
    }

    private int selected(String key) {
        RadioGroup g = (RadioGroup) fields.get(key);
        return (Integer) g.findViewById(g.getCheckedRadioButtonId()).getTag();
    }

    private double number(String key) {
        String raw = ((EditText) fields.get(key)).getText().toString().trim().replace(',', '.');
        if (raw.isEmpty()) return 0;
        try {
            double value = Double.parseDouble(raw);
            if (Double.isFinite(value)) return value;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException(
                "Enter a valid number for " + (key.equals("prep") ? "preparation time" : "budget"));
    }

    private void captureFields(PreferenceSetup state) throws JSONException {
        JSONObject values = new JSONObject();
        for (Map.Entry<String, View> e : fields.entrySet()) {
            View view = e.getValue();
            Object value =
                    view instanceof EditText
                            ? ((EditText) view).getText().toString()
                            : view instanceof CheckBox
                                    ? ((CheckBox) view).isChecked()
                                    : selected(e.getKey());
            values.put(e.getKey(), value);
        }
        if (values.length() > 0) state.draft.put(String.valueOf(setup.page), values);
    }

    private void restoreFields() {
        JSONObject values = setup.draft.optJSONObject(String.valueOf(setup.page));
        if (values == null) return;
        for (Map.Entry<String, View> e : fields.entrySet()) {
            if (!values.has(e.getKey())) continue;
            View view = e.getValue();
            String key = e.getKey();
            if (view instanceof EditText) ((EditText) view).setText(values.optString(key));
            else if (view instanceof CheckBox) ((CheckBox) view).setChecked(values.optBoolean(key));
            else {
                RadioGroup group = (RadioGroup) view;
                int index = values.optInt(key, -1);
                if (index >= 0 && index < group.getChildCount())
                    group.check(group.getChildAt(index).getId());
            }
        }
    }

    private void leave() {
        if (busy || !loaded) return;
        hideKeyboard();
        try {
            PreferenceSetup s = setup.copy();
            captureFields(s);
            PlannerProfile p = profile.copy();
            try {
                readFields(p);
                p.validate();
                s.draft.remove(String.valueOf(setup.page));
            } catch (IllegalArgumentException ignored) {
                p = profile.copy();
            }
            save(p, s, this::finish);
        } catch (JSONException e) {
            showError("Unable to save your progress");
        }
    }

    @Override
    public void onBackPressed() {
        navigate(-1);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        if (loaded && !busy)
            try {
                PreferenceSetup s = setup.copy();
                captureFields(s);
                out.putString("draft", s.toJson().toString());
            } catch (JSONException ignored) {
            }
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        busy = false;
        load();
    }

    @Override
    protected void onPause() {
        if (loaded && !busy)
            try {
                PreferenceSetup s = setup.copy();
                captureFields(s);
                worker.execute(
                        () -> {
                            try {
                                store.saveSetupDraft(s);
                            } catch (Exception e) {
                                AppLogger.i("Preference draft save failed");
                            }
                        });
            } catch (JSONException ignored) {
            }
        foreground = false;
        generation++;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        worker.execute(
                () -> {
                    if (db != null) db.close();
                });
        worker.shutdown();
        super.onDestroy();
    }

    private void showError(String value) {
        if (error == null) error = text(body, "", 14, false);
        error.setText(value);
        error.setVisibility(View.VISIBLE);
        scroll.post(() -> scroll.smoothScrollTo(0, error.getBottom()));
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null)
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private void choices(String key, String label, String[] values, int selected) {
        text(body, label, 15, true);
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < values.length; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setTag(i);
            b.setText(values[i]);
            b.setTextColor(getColor(R.color.text_primary));
            b.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            b.setMinHeight(dp(48));
            group.addView(b);
            if (i == selected) group.check(b.getId());
        }
        body.addView(group);
        fields.put(key, group);
    }

    private void check(String key, String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        box.setTextColor(getColor(R.color.text_primary));
        box.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        body.addView(box);
        fields.put(key, box);
    }

    private void input(String key, String label, String value) {
        TextView caption = text(body, label, 14, true);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        caption.setLabelFor(input.getId());
        input.setText(value);
        input.setSingleLine(true);
        input.setMinHeight(dp(48));
        input.setTextColor(getColor(R.color.text_primary));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(12)});
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        FrostedUi.surface(input, FrostedSurfaceDrawable.Material.INPUT);
        body.addView(input);
        fields.put(key, input);
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout card() {
        LinearLayout box = column();
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, dp(6));
        body.addView(box, lp);
        FrostedUi.surface(box, FrostedSurfaceDrawable.Material.CARD);
        return box;
    }

    private TextView text(LinearLayout parent, String value, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(getColor(bold ? R.color.text_primary : R.color.text_secondary));
        v.setPadding(0, dp(5), 0, dp(5));
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        parent.addView(v);
        return v;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(getColor(R.color.accent));
        b.setMinHeight(dp(48));
        b.setOnClickListener(
                v -> {
                    if (!busy) action.run();
                });
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
        FrostedUi.surface(b, FrostedSurfaceDrawable.Material.CONTROL);
        return b;
    }

    private void enable(View view, boolean value) {
        view.setEnabled(value);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) enable(group.getChildAt(i), value);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
