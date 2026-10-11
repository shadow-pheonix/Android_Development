package com.shadowphoenix.dietlog.planner;

import android.app.*;
import android.content.Intent;
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
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private long generation;
    private LinearLayout page;
    private ScrollView mainScroll;
    private StandardPlan standard;
    private Integer editingMeal;
    private boolean generateAfterSetup;
    private static final int SETUP_REQUEST = 411;
    private PlannerStore store;
    private FoodCatalogDb db;
    private DiaryDbHelper diary;
    private PlannerCatalog catalog;
    private PlannerProfile profile;
    private LocalDate date = LocalDate.now();
    private DayPlanner.Result result;
    private DayPlanner.Request request;
    private List<FoodEntry> history = Collections.emptyList(), entries = Collections.emptyList();
    private final Map<String, String> suggestions = new LinkedHashMap<>();
    private boolean ready, busy, foreground, setupOpened;
    private List<FoodHistory.Item> favorites = Collections.emptyList();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLogger.init(this);
        CrashHandler.install(this);
        store = new PlannerStore(this);
        setupOpened = state != null && state.getBoolean("setupOpened", false);
        generateAfterSetup = state != null && state.getBoolean("generateAfterSetup", false);
        if (state != null && state.containsKey("editingMeal"))
            editingMeal = state.getInt("editingMeal");
        if (state != null) date = LocalDate.parse(state.getString("day", date.toString()));
        else if (getIntent().hasExtra("day"))
            date = LocalDate.parse(getIntent().getStringExtra("day"));
        getWindow().setStatusBarColor(getColor(R.color.background));
        getWindow().setNavigationBarColor(getColor(R.color.background));
        ScrollView scroll = new ScrollView(this);
        mainScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundResource(R.drawable.bg_frosted_scene);
        page = column();
        page.setPadding(dp(20), dp(20), dp(20), dp(32));
        scroll.addView(page);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets bars =
                                insets.getInsets(WindowInsets.Type.systemBars());
                        v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                    } else {
                        v.setPadding(
                                insets.getSystemWindowInsetLeft(),
                                insets.getSystemWindowInsetTop(),
                                insets.getSystemWindowInsetRight(),
                                insets.getSystemWindowInsetBottom());
                    }
                    return insets;
                });
        scroll.requestApplyInsets();
        load();
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> void async(Work<T> work, java.util.function.Consumer<T> done) {
        long token = ++generation;
        busy = true;
        setPlannerEnabled(page, false);
        worker.execute(
                () -> {
                    try {
                        T value = work.run();
                        runOnUiThread(
                                () -> {
                                    if (!closed && foreground && token == generation) {
                                        busy = false;
                                        setPlannerEnabled(page, true);
                                        done.accept(value);
                                    }
                                });
                    } catch (Exception e) {
                        AppLogger.i(
                                "Planner operation failed type=" + e.getClass().getSimpleName());
                        runOnUiThread(
                                () -> {
                                    if (!closed && foreground && token == generation) {
                                        busy = false;
                                        setPlannerEnabled(page, true);
                                        message(
                                                e instanceof IllegalArgumentException
                                                        ? e.getMessage()
                                                        : "Unable to complete this planner action."
                                                                + " Export diagnostics or retry.");
                                        if (ready) render();
                                        else {
                                            page.removeAllViews();
                                            text(page, "Unable to load the planner", 20, true);
                                            button(page, "Retry", this::load);
                                            button(page, "Back", this::finish);
                                        }
                                    }
                                });
                    }
                });
    }

    private void load() {
        page.removeAllViews();
        text(page, "My diet plan", 26, true);
        text(page, "Loading your foods and preferences…", 15, false);
        async(
                () -> {
                    if (db == null) db = new FoodCatalogDb(this);
                    if (diary == null) diary = new DiaryDbHelper(this);
                    for (String token : store.pendingTokens())
                        store.finishUsage(token, diary.hasPlannerConsumption(token));
                    profile = store.profile();
                    catalog =
                            new PlannerCatalog(
                                    this, db, store.personalRecipes(), store.extraFoods());
                    history = diary.getPlannerHistory();
                    favorites = diary.getFavoriteFoods(100);
                    ProfilePreferences pp = new ProfilePreferences(this);
                    if (pp.hasCompletedPersonalProfile()
                            && pp.load().effectiveAge(LocalDate.now()) >= 18) {
                        request = buildStandardRequest();
                        standard =
                                StandardPlan.fromJson(
                                        store.standardPlan(), catalog.recipes, request);
                        if (standard != null) store.saveStandardPlan(standard);
                        result = standard == null ? null : standard.result;
                    } else {
                        standard = null;
                        result = null;
                    }
                    return true;
                },
                v -> {
                    ready = true;
                    suggestions.clear();
                    for (PlannerFood f : catalog.foods.values())
                        suggestions.put(f.id, f.name + " (" + f.state + ")");
                    for (MealRecipe r : catalog.recipes) suggestions.put(r.id, r.name);
                    for (FoodEntry e : history)
                        if (suggestions.size() < 160)
                            suggestions.putIfAbsent(foodIdentity(e), e.name);
                    for (FoodHistory.Item item : favorites)
                        if (suggestions.size() < 200)
                            suggestions.putIfAbsent(
                                    foodIdentity(item.entry), item.entry.name + " · favorite");
                    render();
                    try {
                        if (!setupOpened && !store.setup().completed) {
                            setupOpened = true;
                            openSetup(false);
                        } else if (generateAfterSetup) {
                            generateAfterSetup = false;
                            if (standard == null) generateStandard(1, 1, false);
                        }
                    } catch (JSONException e) {
                        message("Unable to restore preference setup");
                    }
                });
    }

    private void render() {
        if (closed || !ready) return;
        int position = mainScroll.getScrollY();
        page.removeAllViews();
        if (editingMeal != null) {
            renderMealEditor(editingMeal);
            return;
        }
        button(page, "‹ Back", this::finish);
        text(page, "My standard diet plan", 26, true).setAccessibilityHeading(true);
        text(
                page,
                "Your usual meals, with choices for every category. Swipe to explore; choose what you enjoy.",
                14,
                false);
        LinearLayout controls = card(page);
        button(controls, "My eating preferences · guided setup", () -> openSetup(false));
        button(controls, "Rate more recipes & foods", () -> openSetup(true));
        text(
                controls,
                profile.ratings.size()
                        + " saved food choices · "
                        + (profile.effectiveDailyBudget() > 0
                                ? money(profile.effectiveDailyBudget()) + " / day"
                                : "Budget not set"),
                14,
                false);
        button(
                page,
                busy
                        ? "Working…"
                        : standard == null
                                ? "Generate my standard plan"
                                : "Regenerate my standard plan",
                () -> generateStandard(1, 1, true));
        button(page, "Plan tools & local prices", this::planTools);
        if (standard == null || result == null) {
            text(
                    page,
                    "Your saved targets and explicit preferences guide the plan. Choosing a recipe does not log it in your diary.",
                    15,
                    false);
            return;
        }
        LinearLayout total = card(page);
        text(total, "Your selected combination", 18, true);
        text(
                total,
                String.format(
                        Locale.getDefault(),
                        "%.0f / %.0f kcal\nProtein %.0f / %.0f g · Carbs %.0f / %.0f g · Fat %.0f / %.0f g",
                        result.totals[0],
                        request.targets[0],
                        result.totals[1],
                        request.targets[1],
                        result.totals[2],
                        request.targets[2],
                        result.totals[3],
                        request.targets[3]),
                15,
                false);
        text(
                total,
                "Estimated food cost: "
                        + money(result.cost)
                        + (result.pricesEntered ? " · your prices" : " · some illustrative prices"),
                15,
                true);
        text(
                total,
                "Reusable across days. Totals describe the selected meals; your diary tracks what you actually eat.",
                13,
                false);
        int enabled = 0;
        for (boolean mealEnabled : profile.meals) if (mealEnabled) enabled++;
        text(total, "Recommended meals · " + standard.selected.size() + " of " + enabled, 15, true);
        if (standard.selected.size() < enabled)
            text(
                    total,
                    "Plan incomplete: review the missing meals below. Your restrictions are still applied.",
                    15,
                    true);
        for (String note : result.notes) text(total, note, 13, false);
        for (int meal : new int[] {0, 1, 3, 2}) if (profile.meals[meal]) renderAlternatives(meal);
        text(
                page,
                "Changing a meal updates the selected totals. Alternatives are checked for your restrictions; different combinations may need target or budget adjustments.",
                12,
                false);
        mainScroll.post(() -> mainScroll.scrollTo(0, position));
    }

    private void renderAlternatives(int meal) {
        LinearLayout section = card(page);
        text(section, DayPlanner.mealName(meal), 20, true).setAccessibilityHeading(true);
        DayPlanner.Choice selected = standard.selected.get(meal);
        text(
                section,
                selected == null ? "Choose a meal below" : "Selected: " + selected.recipe.name,
                14,
                false);
        List<DayPlanner.Choice> options =
                standard.alternatives.getOrDefault(meal, Collections.emptyList());
        if (options.isEmpty()) {
            text(
                    section,
                    "No "
                            + DayPlanner.mealName(meal)
                            + " recipes fit your saved choices. "
                            + (profile.canCook
                                    ? "Active preparation limit: "
                                            + profile.maxPrepMinutes
                                            + " minutes. "
                                    : "Only already-prepared or ready-to-eat meals are allowed. ")
                            + "Your eating pattern, allergies and Avoid/Never ratings still apply.",
                    14,
                    false);
            button(section, "Review eating preferences", () -> openSetup(false));
        } else {
            MealAlternativesView carousel = new MealAlternativesView(this);
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            carousel.addView(strip);
            section.addView(carousel, new LinearLayout.LayoutParams(-1, -2));
            int selectedIndex = 0;
            for (int i = 0; i < options.size(); i++) {
                DayPlanner.Choice choice = options.get(i);
                LinearLayout frame = column();
                frame.setPadding(dp(4), dp(8), dp(4), dp(8));
                strip.addView(frame, new LinearLayout.LayoutParams(dp(280), -2));
                boolean chosen = selected != null && selected.recipe.id.equals(choice.recipe.id);
                if (chosen) selectedIndex = i;
                text(frame, chosen ? "✓ Selected" : "Alternative " + (i + 1), 13, true);
                text(frame, choice.recipe.name, 21, true);
                text(frame, choice.recipe.cuisine + " · " + DayPlanner.mealName(meal), 13, false);
                double[] n = choice.recipe.nutrition(choice.scale);
                text(
                        frame,
                        String.format(
                                Locale.getDefault(),
                                "%.1f × base serving · %.0f kcal\nProtein %.0f g · Carbs %.0f g · Fat %.0f g\n%d min prep · %s estimated",
                                choice.scale,
                                n[0],
                                n[1],
                                n[2],
                                n[3],
                                choice.recipe.prepMinutes,
                                money(
                                        PriceModel.consumed(choice.recipe, choice.scale, profile)
                                                .amount)),
                        14,
                        false);
                StringJoiner ingredients = new StringJoiner(", ");
                for (MealRecipe.Ingredient part : choice.recipe.ingredients)
                    ingredients.add(part.food.name);
                text(frame, ingredients.toString(), 13, false);
                button(
                        frame,
                        chosen
                                ? "Selected for " + DayPlanner.mealName(meal)
                                : "Choose this for " + DayPlanner.mealName(meal),
                        () -> selectStandard(choice));
                appendRatings(frame, choice.recipe);
                button(frame, "Recipe & amounts", () -> recipeDetails(choice));
            }
            TextView indicator = text(section, "Swipe for alternatives", 13, false);
            carousel.setOnPageChanged(
                    index ->
                            indicator.setText(
                                    "Alternative "
                                            + (index + 1)
                                            + " of "
                                            + options.size()
                                            + " · swipe left or right"));
            LinearLayout nav = new LinearLayout(this);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            Button previous = button(nav, "‹ Previous", () -> carousel.move(-1));
            Button next = button(nav, "Next ›", () -> carousel.move(1));
            previous.setContentDescription(
                    "Previous " + DayPlanner.mealName(meal) + " alternative");
            next.setContentDescription("Next " + DayPlanner.mealName(meal) + " alternative");
            previous.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            next.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            section.addView(nav);
            carousel.showPage(selectedIndex);
        }
        button(
                section,
                "Edit " + DayPlanner.mealName(meal) + " only",
                () -> {
                    editingMeal = meal;
                    render();
                    mainScroll.scrollTo(0, 0);
                });
        if (selected != null)
            button(section, "I ate the selected meal · confirm", () -> chooseLogDate(selected));
    }

    private void appendRatings(LinearLayout parent, MealRecipe recipe) {
        text(parent, "How does this recipe suit your taste?", 13, false);
        RadioGroup group = new RadioGroup(this);
        for (FoodPreference preference :
                new FoodPreference[] {
                    FoodPreference.LOVE,
                    FoodPreference.OKAY,
                    FoodPreference.AVOID,
                    FoodPreference.NEVER
                }) {
            RadioButton choice = new RadioButton(this);
            choice.setEnabled(!busy);
            choice.setId(View.generateViewId());
            choice.setTag(preference);
            choice.setText(
                    preference == FoodPreference.NEVER
                            ? "Never recommend"
                            : preference == FoodPreference.OKAY
                                    ? "Okay · manageable"
                                    : preference.label);
            choice.setContentDescription(recipe.name + " · " + choice.getText());
            choice.setTextColor(getColor(R.color.text_primary));
            choice.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            choice.setMinHeight(dp(48));
            group.addView(choice);
            if (profile.rating(recipe.id) == preference) group.check(choice.getId());
        }
        parent.addView(group);
        group.setOnCheckedChangeListener(
                (g, id) -> {
                    if (busy || id == -1) return;
                    FoodPreference preference = (FoodPreference) g.findViewById(id).getTag();
                    async(
                            () -> {
                                PlannerProfile p = store.profile();
                                p.ratings.put(recipe.id, preference);
                                p.ratingNames.put(recipe.id, recipe.name);
                                store.saveProfile(p);
                                DayPlanner.Request r = buildStandardRequest();
                                StandardPlan saved =
                                        StandardPlan.fromJson(
                                                store.standardPlan(), catalog.recipes, r);
                                if (saved != null) store.saveStandardPlan(saved);
                                return new Object[] {saved, r};
                            },
                            value -> {
                                acceptStandard(value);
                                message("Taste choice saved");
                            });
                });
    }

    private void recipeDetails(DayPlanner.Choice choice) {
        LinearLayout body = column();
        text(body, choice.recipe.name, 22, true);
        for (MealRecipe.Ingredient i : choice.recipe.ingredients)
            text(
                    body,
                    String.format(
                            Locale.getDefault(),
                            "%s · %.0f g (%s)",
                            i.food.name,
                            i.grams * choice.scale,
                            i.food.state),
                    14,
                    false);
        text(body, choice.recipe.instructions, 15, false);
        text(body, explanation(choice), 13, false);
        button(body, "Give feedback", () -> feedback(choice));
        dialog("Recipe & amounts", body, null);
    }

    private void chooseLogDate(DayPlanner.Choice choice) {
        DatePickerDialog picker =
                new DatePickerDialog(
                        this,
                        (v, y, m, d) -> {
                            date = LocalDate.of(y, m + 1, d);
                            if (date.isAfter(LocalDate.now())) {
                                message("Confirm only food actually eaten");
                                return;
                            }
                            confirmConsumption(choice);
                        },
                        date.getYear(),
                        date.getMonthValue() - 1,
                        date.getDayOfMonth());
        picker.setTitle("Date you ate this meal");
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }

    private void generateStandard(double costWeight, double effortWeight, boolean confirm) {
        if (busy || !ready) return;
        if (confirm && standard != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Regenerate the whole standard plan?")
                    .setMessage(
                            "This replaces the selected meals and alternatives. To change one category, use Edit this meal.")
                    .setNegativeButton("Keep my selections", null)
                    .setPositiveButton(
                            "Regenerate",
                            (d, n) -> generateStandard(costWeight, effortWeight, false))
                    .show();
            return;
        }
        async(
                () -> {
                    DayPlanner.Request r = buildStandardRequest();
                    r.costWeight = costWeight;
                    r.effortWeight = effortWeight;
                    StandardPlan generated = StandardPlan.generate(catalog.recipes, r);
                    store.saveStandardPlan(generated);
                    AppLogger.i("Standard plan generated meal_count=" + generated.selected.size());
                    return new Object[] {generated, r};
                },
                this::acceptStandard);
        render();
    }

    private void selectStandard(DayPlanner.Choice choice) {
        if (busy) return;
        try {
            JSONObject snapshot = standard == null ? new JSONObject() : standard.toJson();
            async(
                    () -> {
                        DayPlanner.Request r = buildStandardRequest();
                        StandardPlan changed = StandardPlan.fromJson(snapshot, catalog.recipes, r);
                        if (changed == null) changed = new StandardPlan();
                        changed.choose(choice, catalog.recipes, r);
                        store.saveStandardPlan(changed);
                        return new Object[] {changed, r};
                    },
                    value -> {
                        editingMeal = null;
                        acceptStandard(value);
                        selection(choice, IntentModel.Feedback.SELECTED);
                    });
        } catch (JSONException e) {
            message("Unable to save this meal choice");
        }
    }

    private void acceptStandard(Object[] value) {
        standard = (StandardPlan) value[0];
        request = (DayPlanner.Request) value[1];
        result = standard == null ? null : standard.result;
        render();
    }

    private void renderMealEditor(int meal) {
        button(
                page,
                "‹ Back to my plan",
                () -> {
                    editingMeal = null;
                    render();
                });
        text(page, "Edit " + DayPlanner.mealName(meal), 26, true).setAccessibilityHeading(true);
        text(page, "Only this meal changes. Other selected meals stay as they are.", 14, false);
        DayPlanner.Choice current = standard == null ? null : standard.selected.get(meal);
        if (current != null) {
            LinearLayout portion = card(page);
            text(portion, current.recipe.name, 18, true);
            EditText scale = input(portion, "Servings for this meal · 0.5–2", current.scale, true);
            button(
                    portion,
                    "Save portion only",
                    () -> {
                        try {
                            selectStandard(
                                    new DayPlanner.Choice(meal, current.recipe, number(scale)));
                        } catch (IllegalArgumentException e) {
                            message("Enter a serving amount from 0.5–2");
                        }
                    });
            button(
                    portion,
                    "Customize this recipe for this meal",
                    () -> {
                        try {
                            editRecipe(recipeCopy(current.recipe, meal), meal);
                        } catch (JSONException e) {
                            message("Unable to open this recipe");
                        }
                    });
        }
        button(
                page,
                "Build my own " + DayPlanner.mealName(meal) + " recipe",
                () -> editRecipe(null, meal));
        button(
                page,
                "Suggest fresh alternatives for this meal only",
                () -> refreshStandardMeal(meal));
        text(page, "Choose another suitable recipe", 18, true);
        EditText search = input(page, "Find a recipe by name or ingredient", null, false);
        LinearLayout rows = column();
        page.addView(rows);
        Runnable show =
                () -> {
                    rows.removeAllViews();
                    String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
                    int count = 0;
                    for (MealRecipe recipe : catalog.recipes) {
                        if (!recipe.allowed(profile, meal)) continue;
                        StringJoiner names = new StringJoiner(" ");
                        names.add(recipe.name);
                        for (MealRecipe.Ingredient ingredient : recipe.ingredients)
                            names.add(ingredient.food.name);
                        if (!query.isEmpty()
                                && !names.toString().toLowerCase(Locale.ROOT).contains(query))
                            continue;
                        double serving = bestServing(recipe, meal);
                        button(
                                rows,
                                recipe.name + " · " + serving + " servings",
                                () -> selectStandard(new DayPlanner.Choice(meal, recipe, serving)));
                        if (++count == 30) break;
                    }
                    if (count == 0)
                        text(
                                rows,
                                "No suitable recipe matches. Build your own recipe or update your explicit preferences.",
                                14,
                                false);
                };
        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int before, int count) {
                        show.run();
                    }

                    public void afterTextChanged(Editable e) {}
                });
        show.run();
    }

    private double bestServing(MealRecipe recipe, int meal) {
        double target =
                request == null
                        ? 500
                        : request.targets[0] * new double[] {0.25, 0.30, 0.15, 0.30}[meal];
        double best = 1, distance = Double.POSITIVE_INFINITY;
        for (double serving : new double[] {0.5, 1, 1.5, 2}) {
            double difference = Math.abs(recipe.nutrition(serving)[0] - target);
            if (difference < distance) {
                distance = difference;
                best = serving;
            }
        }
        return best;
    }

    private void refreshStandardMeal(int meal) {
        try {
            JSONObject snapshot = standard == null ? new JSONObject() : standard.toJson();
            async(
                    () -> {
                        DayPlanner.Request r = buildStandardRequest();
                        StandardPlan changed = StandardPlan.fromJson(snapshot, catalog.recipes, r);
                        if (changed == null) changed = new StandardPlan();
                        changed.refreshMeal(meal, catalog.recipes, r);
                        changed.evaluate(catalog.recipes, r);
                        store.saveStandardPlan(changed);
                        return new Object[] {changed, r};
                    },
                    value -> {
                        editingMeal = null;
                        acceptStandard(value);
                    });
        } catch (JSONException e) {
            message("Unable to refresh this meal");
        }
    }

    private JSONObject recipeCopy(MealRecipe recipe, int meal) throws JSONException {
        JSONArray parts = new JSONArray();
        for (MealRecipe.Ingredient part : recipe.ingredients) {
            String slug = null;
            for (Map.Entry<String, PlannerFood> e : catalog.foods.entrySet())
                if (e.getValue().id.equals(part.food.id)) {
                    slug = e.getKey();
                    break;
                }
            if (slug == null) throw new JSONException("Ingredient no longer available");
            parts.put(new JSONObject().put("food", slug).put("grams", part.grams));
        }
        return new JSONObject()
                .put("id", "personal:" + UUID.randomUUID())
                .put("name", recipe.name + " · my version")
                .put("instructions", recipe.instructions)
                .put("meal_mask", 1 << meal)
                .put("prep_minutes", recipe.prepMinutes)
                .put("requires_cooking", recipe.requiresCooking)
                .put("cuisine", recipe.cuisine)
                .put("family", recipe.family)
                .put("primary_meal", meal)
                .put("yield_grams", recipe.yieldGrams)
                .put("ingredients", parts);
    }

    private void planTools() {
        LinearLayout tools = column();
        button(tools, "My recipes & ingredients", this::personalRecipes);
        button(tools, "Local ingredient prices", this::prices);
        button(tools, "Pantry & pack prices", this::pantry);
        button(tools, "Already prepared food · for logging", this::leftovers);
        button(tools, "Shopping list · selected standard meals", this::shopping);
        button(tools, "Regenerate using cheaper meals", () -> generateStandard(3, 1, true));
        button(tools, "Regenerate using quicker meals", () -> generateStandard(1, 3, true));
        button(tools, "Link familiar diary foods", this::linkFoods);
        button(tools, "Find another food to rate", () -> searchFood(false));
        button(tools, "Choose diary favorites to mark Love", this::importFavorites);
        button(
                tools,
                "Reset recipe feedback",
                () -> {
                    try {
                        store.resetLearning();
                        load();
                        message("Recipe feedback reset. Your food ratings and diary are kept.");
                    } catch (Exception e) {
                        message("Unable to reset feedback");
                    }
                });
        dialog("Plan tools", tools, null);
    }

    private void selection(DayPlanner.Choice choice, IntentModel.Feedback signal) {
        if (!profile.learnFromHistory) return;
        try {
            store.recordFeedback(choice.recipe.id + "|" + choice.meal, signal);
        } catch (Exception e) {
            message("Meal change applied; unable to save its learning signal");
        }
    }

    private String explanation(DayPlanner.Choice c) {
        List<String> reasons = new ArrayList<>();
        if (profile.rating(c.recipe.id) == FoodPreference.LOVE)
            reasons.add("You marked this meal Love");
        for (MealRecipe.Ingredient i : c.recipe.ingredients)
            if (profile.rating(i.food.id) == FoodPreference.LOVE)
                reasons.add("Includes " + i.food.name + ", which you love");
        int days = request.intent.distinctDays(c.recipe.id, c.meal);
        if (days > 0 && profile.learnFromHistory)
            reasons.add(
                    "You logged this at "
                            + DayPlanner.mealName(c.meal).toLowerCase(Locale.ROOT)
                            + " on "
                            + days
                            + " distinct days");
        if (request.feedback
                                .getOrDefault(c.recipe.id + "|" + c.meal, Collections.emptyMap())
                                .getOrDefault(IntentModel.Feedback.SELECTED, 0)
                        > 0
                && profile.learnFromHistory)
            reasons.add("You previously kept this meal in this meal slot");
        if (reasons.isEmpty()) reasons.add("A meal option checked against your saved preferences");
        reasons.add(
                (c.preparedScale >= c.scale ? 0 : c.recipe.prepMinutes)
                        + " minutes active preparation");
        if (c.preparedScale > 0)
            reasons.add(
                    String.format(
                            Locale.getDefault(),
                            "Uses %.1f previously prepared servings",
                            c.preparedScale));
        return String.join(". ", reasons) + ".";
    }

    private DayPlanner.Request buildStandardRequest() throws Exception {
        return buildRequest(LocalDate.now(), store.profile().meals.clone(), true);
    }

    private DayPlanner.Request buildRequest(LocalDate day, boolean[] selected) throws Exception {
        return buildRequest(day, selected, false);
    }

    private DayPlanner.Request buildRequest(LocalDate day, boolean[] selected, boolean standardMode)
            throws Exception {
        profile = store.profile();
        ProfilePreferences pp = new ProfilePreferences(this);
        if (!pp.hasCompletedPersonalProfile())
            throw new IllegalArgumentException(
                    "Complete your personal profile before generating a diet plan");
        ProfileSettings s = pp.load();
        if (s.effectiveAge(LocalDate.now()) < 18)
            throw new IllegalArgumentException(
                    "Personalized meal planning currently supports adults. Keep using the food"
                            + " diary and professional guidance for younger users.");
        DayPlanner.Request r = new DayPlanner.Request();
        r.profile = profile;
        r.targets =
                new double[] {
                    CalorieCalculator.calculate(s, day).calorieTarget,
                    s.proteinGoalG,
                    s.carbsGoalG,
                    s.fatGoalG,
                    s.fibreGoalG,
                    s.sodiumLimitMg,
                    s.sugarLimitG,
                    0
                };
        entries = standardMode ? Collections.emptyList() : diary.getPlannerDayEntries(day);
        Arrays.fill(r.consumed, 0);
        for (FoodEntry e : entries) {
            r.consumed[0] += e.calories;
            r.consumed[1] += e.protein;
            r.consumed[2] += e.carbs;
            r.consumed[3] += e.fat;
            r.consumedByMeal[mealIndex(e.mealType)] += e.calories;
        }
        if (!entries.isEmpty()) {
            for (int i = 4; i < 8; i++) r.consumed[i] = Double.NaN;
        }
        for (FoodEntry e : entries) {
            MealRecipe eatenRecipe = catalog.find(e.sourceFoodId);
            Double price = profile.prices.get(e.sourceFoodId);
            if ("planner".equals(e.source) && eatenRecipe != null) {
                PriceModel.Cost cost =
                        PriceModel.consumed(eatenRecipe, e.grams / eatenRecipe.yieldGrams, profile);
                r.consumedCost += cost.amount;
                r.consumedCostKnown &= cost.allPricesEntered;
            } else if (price != null) r.consumedCost += price * e.grams / 100;
            else r.consumedCostKnown = false;
        }
        for (int m = 0; m < 4; m++) r.remaining[m] = selected[m] && profile.meals[m];
        // Re-read for every plan: new logs and diary corrections continuously update evidence.
        history = diary.getPlannerHistory();
        List<IntentModel.Observation> observations = new ArrayList<>();
        for (FoodEntry e : history)
            observations.add(
                    new IntentModel.Observation(
                            profile.canonicalId(foodIdentity(e)),
                            mealIndex(e.mealType),
                            Instant.ofEpochMilli(e.loggedAt)
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDate()
                                    .toEpochDay(),
                            e.calories));
        r.intent = new IntentModel(observations, day.toEpochDay());
        r.feedback.putAll(store.feedback());
        r.cancelled = () -> closed;
        if (!standardMode) applyInventory(r, day);
        return r;
    }

    private void applyInventory(DayPlanner.Request r, LocalDate day) throws JSONException {
        if (day.isBefore(LocalDate.now())) return;
        JSONObject stock = store.pantry();
        for (Iterator<String> it = stock.keys(); it.hasNext(); ) {
            String id = it.next();
            r.pantryGrams.put(id, stock.getJSONObject(id).optDouble("grams", 0));
        }
        JSONObject ready = store.leftovers();
        for (Iterator<String> it = ready.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject item = ready.getJSONObject(id);
            if (!LocalDate.parse(item.getString("through")).isBefore(day)
                    && !LocalDate.parse(item.optString("from", LocalDate.now().toString()))
                            .isAfter(day)) r.readyServings.put(id, item.getDouble("servings"));
        }
    }

    private void openSetup(boolean rate) {
        Intent intent = new Intent(this, PreferenceSetupActivity.class);
        intent.putExtra("mode", rate ? "rate" : "preferences");
        setupOpened = true;
        startActivityForResult(intent, SETUP_REQUEST);
    }

    private void rate(String id, String name) {
        FoodPreference[] values = FoodPreference.values();
        String[] labels = Arrays.stream(values).map(v -> v.label).toArray(String[]::new);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setSingleChoiceItems(
                        labels,
                        profile.rating(id).ordinal(),
                        (d, n) -> {
                            try {
                                PlannerProfile p = store.profile();
                                if (values[n] == FoodPreference.UNSET) {
                                    p.ratings.remove(id);
                                    p.ratingNames.remove(id);
                                } else {
                                    p.ratings.put(id, values[n]);
                                    p.ratingNames.put(id, name);
                                }
                                store.saveProfile(p);
                                profile = p;
                                d.dismiss();
                                load();
                            } catch (Exception e) {
                                message("Unable to save this food choice");
                            }
                        })
                .show();
    }

    private void searchFood(boolean ingredient) {
        LinearLayout body = column();
        EditText query = input(body, "Search foods", null, false);
        LinearLayout rows = column();
        body.addView(rows);
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("Add a food preference")
                        .setView(scroll(body))
                        .setNegativeButton("Close", null)
                        .create();
        dialog.show();
        final long[] token = {0};
        query.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int before, int count) {
                        long t = ++token[0];
                        String q = s.toString().trim();
                        rows.removeAllViews();
                        if (q.length() < 2) return;
                        query.postDelayed(
                                () -> {
                                    if (closed || !dialog.isShowing() || t != token[0]) return;
                                    worker.execute(
                                            () -> {
                                                try {
                                                    if (t != token[0]) return;
                                                    List<CatalogFood> foods = db.search(q, 20);
                                                    runOnUiThread(
                                                            () -> {
                                                                if (closed
                                                                        || !dialog.isShowing()
                                                                        || t != token[0]) return;
                                                                rows.removeAllViews();
                                                                for (CatalogFood f : foods)
                                                                    button(
                                                                            rows,
                                                                            f.name,
                                                                            () -> {
                                                                                dialog.dismiss();
                                                                                if (ingredient)
                                                                                    addCatalogIngredient(
                                                                                            f);
                                                                                else
                                                                                    rate(
                                                                                            f.code,
                                                                                            f.name);
                                                                            });
                                                            });
                                                } catch (Exception ignored) {
                                                }
                                            });
                                },
                                250);
                    }

                    public void afterTextChanged(Editable e) {}
                });
    }

    private void prices() {
        LinearLayout body = column();
        text(
                body,
                "Enter ₹ per 100 g in the displayed state. Starter prices are illustrative"
                        + " estimates; blank keeps the estimate.",
                14,
                false);
        Map<String, EditText> fields = new LinkedHashMap<>();
        for (PlannerFood f : catalog.foods.values()) {
            EditText field =
                    input(
                            body,
                            f.name + " · " + f.state + " · estimate " + money(f.referencePrice),
                            profile.prices.get(f.id),
                            true);
            fields.put(f.id, field);
        }
        dialog(
                "My local prices",
                body,
                () -> {
                    try {
                        PlannerProfile p = store.profile();
                        for (Map.Entry<String, EditText> e : fields.entrySet()) {
                            String v = e.getValue().getText().toString().trim();
                            if (v.isEmpty()) p.prices.remove(e.getKey());
                            else p.prices.put(e.getKey(), number(e.getValue()));
                        }
                        store.saveProfile(p);
                        profile = p;
                        load();
                        return true;
                    } catch (Exception e) {
                        message("Use valid non-negative prices");
                        return false;
                    }
                });
    }

    private void feedback(DayPlanner.Choice c) {
        String[] labels = {
            "Liked it",
            "Disliked the taste",
            "Too expensive",
            "Too much food",
            "Too much cooking",
            "Didn't suit me · stop recommending"
        };
        IntentModel.Feedback[] reasons = IntentModel.Feedback.values();
        new AlertDialog.Builder(this)
                .setTitle("What should change?")
                .setItems(
                        labels,
                        (d, n) -> {
                            try {
                                store.recordFeedback(c.recipe.id, reasons[n]);
                                if (reasons[n] == IntentModel.Feedback.UNSUITABLE) {
                                    PlannerProfile p = store.profile();
                                    p.ratings.put(c.recipe.id, FoodPreference.NEVER);
                                    p.ratingNames.put(c.recipe.id, c.recipe.name);
                                    store.saveProfile(p);
                                    profile = p;
                                }
                                message("Feedback saved");
                                load();
                            } catch (Exception e) {
                                message("Unable to save feedback");
                            }
                        })
                .show();
    }

    private void confirmConsumption(DayPlanner.Choice c) {
        LinearLayout body = column();
        EditText amount = input(body, "Servings actually eaten (0.5–2)", c.scale, true);
        EditText prepared =
                input(body, "Of these, servings from already prepared food", c.preparedScale, true);
        CheckBox update =
                check(
                        body,
                        "Update pantry ingredients and prepared portions",
                        date.equals(LocalDate.now()));
        update.setEnabled(date.equals(LocalDate.now()));
        text(
                body,
                "Inventory updates follow today's confirmed consumption. Editing or deleting a"
                        + " diary entry does not adjust inventory; edit inventory if you need a"
                        + " correction.",
                13,
                false);
        dialog(
                "Confirm " + c.recipe.name + " · " + date,
                body,
                () -> {
                    try {
                        double scale = number(amount), readyAmount = number(prepared);
                        if (!Double.isFinite(scale) || scale < 0.5 || scale > 2)
                            throw new IllegalArgumentException(
                                    "Use 0.5–2 servings; log other amounts through the diary");
                        if (!Double.isFinite(readyAmount) || readyAmount < 0 || readyAmount > scale)
                            throw new IllegalArgumentException(
                                    "Prepared servings must be between zero and the amount eaten");
                        final LocalDate day = date;
                        if (day.isAfter(LocalDate.now()))
                            throw new IllegalArgumentException("Confirm only food actually eaten");
                        final boolean inventory = update.isChecked();
                        final String token = "plan:" + day + ":" + c.meal + ":" + c.recipe.id;
                        async(
                                () -> {
                                    for (String pending : store.pendingTokens())
                                        store.finishUsage(
                                                pending, diary.hasPlannerConsumption(pending));
                                    if (diary.hasPlannerConsumption(token)) return false;
                                    if (inventory) {
                                        if (readyAmount > 0) {
                                            JSONObject ready =
                                                    store.leftovers().optJSONObject(c.recipe.id);
                                            if (ready == null
                                                    || ready.optDouble("servings", 0) + 0.001
                                                            < readyAmount
                                                    || LocalDate.parse(ready.getString("through"))
                                                            .isBefore(day))
                                                throw new IllegalArgumentException(
                                                        "Prepared portions are no longer available."
                                                                + " Update them or confirm the amount"
                                                                + " freshly prepared.");
                                        }
                                        Map<String, Double> used = new HashMap<>();
                                        for (MealRecipe.Ingredient i : c.recipe.ingredients)
                                            used.merge(
                                                    i.food.id,
                                                    i.grams * (scale - readyAmount),
                                                    Double::sum);
                                        store.stageUsage(token, used, c.recipe.id, readyAmount);
                                    }
                                    double[] n = c.recipe.nutrition(scale);
                                    MicronutrientValues micros = MicronutrientValues.empty();
                                    for (Micronutrient nutrient : Micronutrient.values()) {
                                        double value = 0;
                                        boolean known = true;
                                        for (MealRecipe.Ingredient i : c.recipe.ingredients) {
                                            CatalogFood f = catalog.sourceFoods.get(i.food.id);
                                            Double part =
                                                    f == null
                                                            ? null
                                                            : f.micronutrients.get(nutrient);
                                            if (part == null) {
                                                known = false;
                                                break;
                                            }
                                            value += part * i.grams / 100 * scale;
                                        }
                                        if (known) micros = micros.with(nutrient, value);
                                    }
                                    long at =
                                            day.equals(LocalDate.now())
                                                    ? System.currentTimeMillis()
                                                    : day.atTime(
                                                                    new int[] {8, 13, 17, 20}
                                                                            [c.meal],
                                                                    0)
                                                            .atZone(ZoneId.systemDefault())
                                                            .toInstant()
                                                            .toEpochMilli();
                                    FoodEntry entry =
                                            new FoodEntry(
                                                    0,
                                                    c.recipe.name,
                                                    c.recipe.yieldGrams * scale,
                                                    n[0],
                                                    n[1],
                                                    n[2],
                                                    n[3],
                                                    at,
                                                    mealKey(c.meal),
                                                    "planner",
                                                    c.recipe.id,
                                                    token,
                                                    micros);
                                    diary.insertPlannerMealOnce(entry);
                                    if (inventory) store.finishUsage(token, true);
                                    return true;
                                },
                                recorded -> {
                                    message(
                                            recorded
                                                    ? "Meal recorded in your diary"
                                                    : "This meal is already recorded; no second"
                                                            + " stock deduction");
                                    result = null;
                                    load();
                                });
                        return true;
                    } catch (Exception e) {
                        message(e.getMessage());
                        return false;
                    }
                });
    }

    private Map<String, Double> pantryAmounts(JSONObject stock) throws JSONException {
        Map<String, Double> amounts = new HashMap<>();
        for (Iterator<String> it = stock.keys(); it.hasNext(); ) {
            String id = it.next();
            amounts.put(id, stock.getJSONObject(id).optDouble("grams", 0));
        }
        return amounts;
    }

    private void shopping() {
        List<DayPlanner.Choice> chosen =
                standard == null
                        ? Collections.emptyList()
                        : new ArrayList<>(standard.selected.values());
        if (chosen.isEmpty()) {
            message("Choose meals in your standard plan first");
            return;
        }
        final String range = "One full set of your selected standard meals";
        async(
                () -> {
                    JSONObject stock = store.pantry();
                    Map<String, ShoppingPlanner.Pack> packs = new HashMap<>();
                    for (Iterator<String> it = stock.keys(); it.hasNext(); ) {
                        String id = it.next();
                        JSONObject row = stock.getJSONObject(id);
                        if (row.has("pack_grams"))
                            packs.put(
                                    id,
                                    new ShoppingPlanner.Pack(
                                            row.getDouble("pack_grams"),
                                            row.getDouble("pack_price")));
                    }
                    return new ShoppingPlanner()
                            .list(chosen, store.profile(), pantryAmounts(stock), packs);
                },
                list -> {
                    LinearLayout body = column();
                    StringBuilder share = new StringBuilder("DietLog shopping · " + range + "\n");
                    text(body, range, 18, true);
                    text(
                            body,
                            "Cash to buy: "
                                    + money(list.cash)
                                    + (list.pricesEntered
                                            ? " · entered prices"
                                            : " · some illustrative prices")
                                    + (list.allPacksKnown
                                            ? ""
                                            : " · unpacked amounts; final cash may differ"),
                            15,
                            true);
                    text(
                            body,
                            "Pantry is deducted once from the combined ingredients. Prepared"
                                    + " portions are excluded. Match each raw/cooked state when"
                                    + " weighing or buying.",
                            13,
                            false);
                    for (ShoppingPlanner.Row row : list.rows) {
                        String line =
                                String.format(
                                        Locale.getDefault(),
                                        "%s (%s)\n"
                                                + "Needed %.0f g · on hand %.0f g · buy %.0f g%s · %s",
                                        row.food.name,
                                        row.food.state,
                                        row.needed,
                                        row.onHand,
                                        row.buyGrams,
                                        row.packConfigured ? " · " + row.packs + " packs" : "",
                                        money(row.cost));
                        text(body, line, 14, false);
                        share.append(line).append('\n');
                    }
                    button(
                            body,
                            "Share shopping list",
                            () -> {
                                android.content.Intent intent =
                                        new android.content.Intent(
                                                android.content.Intent.ACTION_SEND);
                                intent.setType("text/plain");
                                intent.putExtra(
                                        android.content.Intent.EXTRA_TEXT, share.toString());
                                startActivity(
                                        android.content.Intent.createChooser(
                                                intent, "Share shopping list"));
                            });
                    dialog("Shopping list", body, null);
                    render();
                });
        render();
    }

    private void pantry() {
        try {
            JSONObject stock = store.pantry();
            LinearLayout form = column();
            text(
                    form,
                    "Stock in the exact weighed state shown. Plans do not change stock. Confirmed"
                            + " consumption deducts ingredients if you choose to update inventory. Pack"
                            + " prices affect shopping cash; per-100 g prices affect meal cost.",
                    14,
                    false);
            Map<String, EditText[]> fields = new LinkedHashMap<>();
            for (PlannerFood food : catalog.foods.values()) {
                JSONObject row = stock.optJSONObject(food.id);
                LinearLayout item = card(form);
                text(item, food.name + " · " + food.state, 16, true);
                fields.put(
                        food.id,
                        new EditText[] {
                            input(
                                    item,
                                    "On hand (g)",
                                    row == null ? 0 : row.optDouble("grams", 0),
                                    true),
                            input(
                                    item,
                                    "Pack size (g) · optional",
                                    row == null ? null : row.opt("pack_grams"),
                                    true),
                            input(
                                    item,
                                    "Pack price ₹ · optional",
                                    row == null ? null : row.opt("pack_price"),
                                    true)
                        });
            }
            dialog(
                    "My pantry",
                    form,
                    () -> {
                        try {
                            JSONObject updated = new JSONObject();
                            for (Map.Entry<String, EditText[]> e : fields.entrySet()) {
                                double grams = number(e.getValue()[0]);
                                new InventoryLedger.Item(grams, "check");
                                JSONObject row =
                                        new JSONObject()
                                                .put("grams", grams)
                                                .put("revision", UUID.randomUUID().toString());
                                String pack = e.getValue()[1].getText().toString().trim(),
                                        price = e.getValue()[2].getText().toString().trim();
                                if (!pack.isEmpty() || !price.isEmpty()) {
                                    if (pack.isEmpty() || price.isEmpty())
                                        throw new IllegalArgumentException(
                                                "Enter both pack size and pack price, or leave both"
                                                        + " blank");
                                    ShoppingPlanner.Pack checked =
                                            new ShoppingPlanner.Pack(
                                                    number(e.getValue()[1]),
                                                    number(e.getValue()[2]));
                                    row.put("pack_grams", checked.grams)
                                            .put("pack_price", checked.price);
                                }
                                updated.put(e.getKey(), row);
                            }
                            store.savePantry(updated);
                            load();
                            return true;
                        } catch (Exception e) {
                            message(e.getMessage());
                            return false;
                        }
                    });
        } catch (Exception e) {
            message("Unable to read pantry stock");
        }
    }

    private void leftovers() {
        LinearLayout body = column();
        text(
                body,
                "Only add food that is already prepared and suitable to eat. You choose its"
                        + " usable-through date. Planning reserves it within a menu; confirmed"
                        + " consumption updates the available portions.",
                14,
                false);
        button(
                body,
                "Add or update prepared portions",
                () -> {
                    LinearLayout form = column();
                    List<MealRecipe> recipes = new ArrayList<>(catalog.recipes);
                    Spinner recipe =
                            spinner(
                                    form,
                                    "Prepared recipe",
                                    recipes.stream().map(r -> r.name).toArray(String[]::new),
                                    0);
                    EditText servings = input(form, "Available base servings (0 removes)", 1, true);
                    final LocalDate[] through = {LocalDate.now()};
                    Button dateButton =
                            button(
                                    form,
                                    "Usable through: " + through[0],
                                    () -> {
                                        DatePickerDialog picker =
                                                new DatePickerDialog(
                                                        this,
                                                        (v, y, m, d) ->
                                                                through[0] =
                                                                        LocalDate.of(y, m + 1, d),
                                                        through[0].getYear(),
                                                        through[0].getMonthValue() - 1,
                                                        through[0].getDayOfMonth());
                                        picker.getDatePicker()
                                                .setMinDate(
                                                        LocalDate.now()
                                                                .atStartOfDay(
                                                                        ZoneId.systemDefault())
                                                                .toInstant()
                                                                .toEpochMilli());
                                        picker.getDatePicker()
                                                .setMaxDate(
                                                        LocalDate.now()
                                                                .plusDays(14)
                                                                .atStartOfDay(
                                                                        ZoneId.systemDefault())
                                                                .toInstant()
                                                                .toEpochMilli());
                                        picker.setOnDismissListener(
                                                v ->
                                                        form.post(
                                                                () -> {
                                                                    for (int i = 0;
                                                                            i
                                                                                    < form
                                                                                            .getChildCount();
                                                                            i++)
                                                                        if (form.getChildAt(i)
                                                                                instanceof Button)
                                                                            ((Button)
                                                                                            form
                                                                                                    .getChildAt(
                                                                                                            i))
                                                                                    .setText(
                                                                                            "Usable"
                                                                                                    + " through:"
                                                                                                    + " "
                                                                                                    + through[
                                                                                                            0]);
                                                                }));
                                        picker.show();
                                    });
                    CheckBox safe =
                            check(
                                    form,
                                    "I checked storage/product guidance and this food is suitable"
                                            + " through that date",
                                    false);
                    dialog(
                            "Prepared portions",
                            form,
                            () -> {
                                try {
                                    double amount = number(servings);
                                    if (!Double.isFinite(amount) || amount < 0 || amount > 20)
                                        throw new IllegalArgumentException(
                                                "Use 0–20 base servings");
                                    if (amount > 0 && !safe.isChecked())
                                        throw new IllegalArgumentException(
                                                "Check suitability before adding prepared food");
                                    MealRecipe r = recipes.get(recipe.getSelectedItemPosition());
                                    JSONObject ready = store.leftovers();
                                    if (amount == 0) ready.remove(r.id);
                                    else
                                        ready.put(
                                                r.id,
                                                new JSONObject()
                                                        .put("servings", amount)
                                                        .put("from", LocalDate.now().toString())
                                                        .put("through", through[0].toString())
                                                        .put(
                                                                "revision",
                                                                UUID.randomUUID().toString()));
                                    store.saveLeftovers(ready);
                                    load();
                                    return true;
                                } catch (Exception e) {
                                    message(e.getMessage());
                                    return false;
                                }
                            });
                });
        try {
            JSONObject ready = store.leftovers();
            for (Iterator<String> it = ready.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONObject row = ready.getJSONObject(id);
                MealRecipe recipe = catalog.find(id);
                if (recipe != null) {
                    text(
                            body,
                            recipe.name
                                    + " · "
                                    + row.optDouble("servings", 0)
                                    + " servings · through "
                                    + row.optString("through"),
                            14,
                            false);
                    button(
                            body,
                            "Remove " + recipe.name,
                            () -> {
                                try {
                                    JSONObject now = store.leftovers();
                                    now.remove(id);
                                    store.saveLeftovers(now);
                                    load();
                                    message("Prepared portions removed");
                                } catch (Exception e) {
                                    message("Unable to update prepared portions");
                                }
                            });
                }
            }
        } catch (Exception e) {
            message("Unable to read prepared portions");
        }
        dialog("Already prepared food", body, null);
    }

    private Spinner spinner(LinearLayout parent, String label, String[] values, int selected) {
        text(parent, label, 13, false);
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        s.setSelection(selected);
        s.setMinimumHeight(dp(48));
        parent.addView(s);
        return s;
    }

    private void linkFoods() {
        Map<String, String> familiar = new LinkedHashMap<>();
        for (FoodEntry e : history) familiar.putIfAbsent(foodIdentity(e), e.name);
        for (FoodHistory.Item item : favorites)
            familiar.putIfAbsent(foodIdentity(item.entry), item.entry.name);
        if (familiar.isEmpty()) {
            message("Log a food or save a diary favorite first");
            return;
        }
        List<String> ids = new ArrayList<>(familiar.keySet());
        new AlertDialog.Builder(this)
                .setTitle("Choose a familiar diary food")
                .setItems(
                        ids.stream().map(familiar::get).toArray(String[]::new),
                        (d, n) -> {
                            String source = ids.get(n);
                            List<String> targets = new ArrayList<>();
                            List<String> names = new ArrayList<>();
                            targets.add("");
                            names.add("Remove this link");
                            for (PlannerFood f : catalog.foods.values()) {
                                targets.add(f.id);
                                names.add(f.name + " (" + f.state + ")");
                            }
                            for (MealRecipe recipe : catalog.recipes) {
                                targets.add(recipe.id);
                                names.add(recipe.name);
                            }
                            new AlertDialog.Builder(this)
                                    .setTitle("Same basic food or same recipe only")
                                    .setItems(
                                            names.toArray(new String[0]),
                                            (dialog, index) -> {
                                                try {
                                                    PlannerProfile p = store.profile();
                                                    String target = targets.get(index);
                                                    if (target.isEmpty() || source.equals(target))
                                                        p.aliases.remove(source);
                                                    else p.aliases.put(source, target);
                                                    store.saveProfile(p);
                                                    profile = p;
                                                    load();
                                                    message(
                                                            "Food link saved. Linked taste choices"
                                                                    + " and exclusions apply together;"
                                                                    + " recipe weights keep their own"
                                                                    + " raw/cooked state.");
                                                } catch (Exception e) {
                                                    message(e.getMessage());
                                                }
                                            })
                                    .show();
                        })
                .show();
    }

    private void importFavorites() {
        if (favorites.isEmpty()) {
            message("No diary favorites saved yet");
            return;
        }
        String[] names = favorites.stream().map(item -> item.entry.name).toArray(String[]::new);
        boolean[] chosen = new boolean[names.length];
        new AlertDialog.Builder(this)
                .setTitle("Mark selected favorites Love")
                .setMultiChoiceItems(names, chosen, (d, n, on) -> chosen[n] = on)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Save",
                        (d, n) -> {
                            try {
                                PlannerProfile p = store.profile();
                                for (int i = 0; i < chosen.length; i++)
                                    if (chosen[i]) {
                                        FoodEntry e = favorites.get(i).entry;
                                        String id = foodIdentity(e);
                                        if (p.rating(id) == FoodPreference.UNSET) {
                                            p.ratings.put(id, FoodPreference.LOVE);
                                            p.ratingNames.put(id, e.name);
                                        }
                                    }
                                store.saveProfile(p);
                                profile = p;
                                load();
                                message(
                                        "Selected unrated favorites marked Love; existing choices"
                                                + " kept. Link unfamiliar entries to planning foods if"
                                                + " needed.");
                            } catch (Exception e) {
                                message("Unable to import favorites");
                            }
                        })
                .show();
    }

    private void personalRecipes() {
        LinearLayout body = column();
        text(
                body,
                "Build one base serving from weighed ingredients. Its nutrition comes from your"
                        + " offline catalogue; the planner adjusts servings. Plain ingredients and"
                        + " complete composition work best.",
                14,
                false);
        button(body, "Add ingredient from food catalogue", () -> searchFood(true));
        button(body, "Create my recipe", () -> editRecipe(null));
        try {
            JSONArray recipes = store.personalRecipes();
            for (int i = 0; i < recipes.length(); i++) {
                JSONObject recipe = recipes.getJSONObject(i);
                button(body, recipe.getString("name"), () -> editRecipe(recipe));
                button(
                        body,
                        "Delete " + recipe.getString("name"),
                        () ->
                                new AlertDialog.Builder(this)
                                        .setTitle("Delete this recipe?")
                                        .setMessage("Existing diary entries are kept.")
                                        .setNegativeButton("Cancel", null)
                                        .setPositiveButton(
                                                "Delete",
                                                (d, n) -> {
                                                    try {
                                                        JSONArray current = store.personalRecipes(),
                                                                updated = new JSONArray();
                                                        for (int j = 0; j < current.length(); j++)
                                                            if (!current.getJSONObject(j)
                                                                    .getString("id")
                                                                    .equals(recipe.getString("id")))
                                                                updated.put(
                                                                        current.getJSONObject(j));
                                                        store.saveRecipes(updated);
                                                        result = null;
                                                        load();
                                                    } catch (Exception e) {
                                                        message("Unable to delete this recipe");
                                                    }
                                                })
                                        .show());
            }
        } catch (Exception e) {
            message("Unable to read personal recipes");
        }
        try {
            JSONObject extra = store.extraFoods();
            for (Iterator<String> it = extra.keys(); it.hasNext(); ) {
                String slug = it.next();
                PlannerFood f = catalog.foods.get(slug);
                if (f != null) {
                    button(
                            body,
                            "Edit ingredient · " + f.name,
                            () -> addCatalogIngredient(catalog.sourceFoods.get(f.id)));
                    button(
                            body,
                            "Remove ingredient · " + f.name,
                            () -> {
                                try {
                                    JSONArray recipes = store.personalRecipes();
                                    for (int i = 0; i < recipes.length(); i++) {
                                        JSONArray parts =
                                                recipes.getJSONObject(i)
                                                        .getJSONArray("ingredients");
                                        for (int j = 0; j < parts.length(); j++)
                                            if (parts.getJSONObject(j)
                                                    .getString("food")
                                                    .equals(slug))
                                                throw new IllegalArgumentException(
                                                        "Remove this ingredient from your recipes"
                                                                + " first");
                                    }
                                    JSONObject updated = store.extraFoods();
                                    updated.remove(slug);
                                    store.saveExtraFoods(updated);
                                    result = null;
                                    load();
                                } catch (Exception e) {
                                    message(e.getMessage());
                                }
                            });
                }
            }
        } catch (Exception e) {
            message("Unable to read added ingredients");
        }
        dialog("My recipes", body, null);
    }

    private void addCatalogIngredient(CatalogFood food) {
        if (!food.hasCompleteCoreMacros()) {
            message(
                    "This food has incomplete calorie/macro data. Choose a complete catalogue"
                            + " record for a planning ingredient.");
            return;
        }
        JSONObject previous;
        try {
            previous = store.extraFoods().optJSONObject("custom:" + food.code);
        } catch (Exception e) {
            message("Unable to read this ingredient");
            return;
        }
        if (previous == null)
            for (PlannerFood f : catalog.foods.values())
                if (f.id.equals(food.code)) {
                    message("This ingredient is already available in My recipes");
                    return;
                }
        LinearLayout form = column();
        text(form, food.name, 18, true);
        text(
                form,
                "Use the weighed state matching this catalogue record. Check the actual ingredient"
                        + " list and allergens; unverified composition is excluded when restrictions"
                        + " are present.",
                14,
                false);
        Spinner state =
                spinner(
                        form,
                        "Weighed state",
                        new String[] {"raw", "cooked", "ready to eat"},
                        previous == null
                                ? 0
                                : Math.max(
                                        0,
                                        Arrays.asList("raw", "cooked", "ready to eat")
                                                .indexOf(previous.optString("state"))));
        EditText price =
                input(
                        form,
                        "Your price ₹ per 100 g (0 only if free)",
                        previous == null
                                ? null
                                : profile.prices.getOrDefault(
                                        food.code,
                                        previous.optDouble("reference_price_per_100g", 0)),
                        true);
        Set<String> tags = new LinkedHashSet<>();
        if (previous != null) {
            JSONArray oldTags = previous.optJSONArray("tags");
            if (oldTags != null)
                for (int i = 0; i < oldTags.length(); i++) tags.add(oldTags.optString(i));
        }
        button(form, "Set food groups and allergens", () -> chooseIngredientTags(tags));
        CheckBox known =
                check(
                        form,
                        "I verified the full composition and all applicable tags",
                        previous != null && previous.optBoolean("ingredients_known", false));
        dialog(
                "Add planning ingredient",
                form,
                () -> {
                    try {
                        if (price.getText().toString().trim().isEmpty())
                            throw new IllegalArgumentException("Enter your ingredient price");
                        double cost = number(price);
                        if (!Double.isFinite(cost) || cost < 0 || cost > 100000)
                            throw new IllegalArgumentException("Enter a valid price");
                        if (tags.isEmpty())
                            throw new IllegalArgumentException(
                                    "Select applicable food groups/allergens");
                        JSONObject foods = store.extraFoods();
                        foods.put(
                                "custom:" + food.code,
                                new JSONObject()
                                        .put("code", food.code)
                                        .put("name", food.name)
                                        .put("state", state.getSelectedItem().toString())
                                        .put("tags", new JSONArray(tags))
                                        .put("ingredients_known", known.isChecked())
                                        .put("reference_price_per_100g", cost));
                        store.saveExtraFoods(foods);
                        PlannerProfile p = store.profile();
                        p.prices.put(food.code, cost);
                        store.saveProfile(p);
                        profile = p;
                        result = null;
                        load();
                        return true;
                    } catch (Exception e) {
                        message(e.getMessage());
                        return false;
                    }
                });
    }

    private void chooseIngredientTags(Set<String> selected) {
        String[] tags = {
            "grain",
            "pulse",
            "vegetable",
            "fruit",
            "oil",
            "other",
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
            "honey",
            "mustard",
            "coconut"
        };
        boolean[] checked = new boolean[tags.length];
        for (int i = 0; i < tags.length; i++) checked[i] = selected.contains(tags[i]);
        new AlertDialog.Builder(this)
                .setTitle("Select every applicable tag")
                .setMultiChoiceItems(
                        tags,
                        checked,
                        (d, n, on) -> {
                            if (on) selected.add(tags[n]);
                            else selected.remove(tags[n]);
                            if (on && (tags[n].equals("fish") || tags[n].equals("shellfish")))
                                selected.add("meat");
                        })
                .setPositiveButton("Done", null)
                .show();
    }

    private final class IngredientRow {
        final Spinner food;
        final EditText grams;
        final LinearLayout view;

        IngredientRow(LinearLayout parent, List<String> slugs, String slug, double amount) {
            view = card(parent);
            String[] names =
                    slugs.stream()
                            .map(
                                    k -> {
                                        PlannerFood f = catalog.foods.get(k);
                                        return f.name + " · " + f.state;
                                    })
                            .toArray(String[]::new);
            food = spinner(view, "Ingredient", names, Math.max(0, slugs.indexOf(slug)));
            grams = input(view, "Grams for ONE base serving", amount > 0 ? amount : null, true);
        }
    }

    private void editRecipe(JSONObject existing) {
        editRecipe(existing, -1);
    }

    private void editRecipe(JSONObject existing, int forMeal) {
        try {
            LinearLayout form = column();
            EditText name =
                    input(
                            form,
                            "Recipe name",
                            existing == null ? null : existing.getString("name"),
                            false);
            EditText instructions =
                    input(
                            form,
                            "Preparation instructions",
                            existing == null ? null : existing.getString("instructions"),
                            false);
            instructions.setSingleLine(false);
            instructions.setMinLines(3);
            instructions.setFilters(new InputFilter[] {new InputFilter.LengthFilter(1000)});
            EditText yield =
                    input(
                            form,
                            "Prepared weight of ONE base serving (g)",
                            existing == null ? null : existing.getDouble("yield_grams"),
                            true);
            EditText prep =
                    input(
                            form,
                            "Active preparation minutes",
                            existing == null ? 15 : existing.getInt("prep_minutes"),
                            true);
            CheckBox cook =
                    check(
                            form,
                            "Requires cooking",
                            existing == null || existing.getBoolean("requires_cooking"));
            Spinner cuisine =
                    spinner(
                            form,
                            "Cuisine",
                            new String[] {"Any", "Indian", "Andhra"},
                            existing == null
                                    ? 1
                                    : Arrays.asList("Any", "Indian", "Andhra")
                                            .indexOf(existing.optString("cuisine", "Indian")));
            CheckBox[] slots = new CheckBox[4];
            for (int m = 0; m < 4; m++)
                slots[m] =
                        check(
                                form,
                                DayPlanner.mealName(m),
                                existing == null
                                        ? (forMeal < 0 || m == forMeal)
                                        : (existing.getInt("meal_mask") & (1 << m)) != 0);
            List<String> slugs = new ArrayList<>(catalog.foods.keySet());
            List<IngredientRow> rows = new ArrayList<>();
            LinearLayout ingredients = column();
            form.addView(ingredients);
            java.util.function.BiConsumer<String, Double> add =
                    (slug, amount) -> {
                        if (rows.size() >= 20) {
                            message("Use at most 20 ingredients");
                            return;
                        }
                        IngredientRow row = new IngredientRow(ingredients, slugs, slug, amount);
                        rows.add(row);
                        button(
                                row.view,
                                "Remove ingredient",
                                () -> {
                                    ingredients.removeView(row.view);
                                    rows.remove(row);
                                });
                    };
            if (existing != null) {
                JSONArray parts = existing.getJSONArray("ingredients");
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    add.accept(part.getString("food"), part.getDouble("grams"));
                }
            } else add.accept(slugs.get(0), 0.0);
            button(form, "Add ingredient", () -> add.accept(slugs.get(0), 0.0));
            dialog(
                    existing == null ? "Create recipe" : "Edit recipe",
                    form,
                    () -> {
                        try {
                            String title = name.getText().toString().trim(),
                                    steps = instructions.getText().toString().trim();
                            if (title.isEmpty() || steps.isEmpty())
                                throw new IllegalArgumentException(
                                        "Add a name and preparation instructions");
                            int mask = 0;
                            for (int m = 0; m < 4; m++) if (slots[m].isChecked()) mask |= 1 << m;
                            double minutes = number(prep);
                            if (minutes != Math.rint(minutes))
                                throw new IllegalArgumentException("Use whole preparation minutes");
                            JSONArray parts = new JSONArray();
                            Set<String> used = new HashSet<>();
                            for (IngredientRow row : rows) {
                                double grams = number(row.grams);
                                if (grams == 0) continue;
                                String slug = slugs.get(row.food.getSelectedItemPosition());
                                if (!used.add(slug))
                                    throw new IllegalArgumentException(
                                            "Combine duplicate ingredient amounts into one row");
                                parts.put(new JSONObject().put("food", slug).put("grams", grams));
                            }
                            String id =
                                    existing == null
                                            ? "personal:" + UUID.randomUUID()
                                            : existing.getString("id");
                            JSONObject recipe =
                                    new JSONObject()
                                            .put("id", id)
                                            .put("name", title)
                                            .put("instructions", steps)
                                            .put("meal_mask", mask)
                                            .put("prep_minutes", (int) minutes)
                                            .put("requires_cooking", cook.isChecked())
                                            .put("cuisine", cuisine.getSelectedItem().toString())
                                            .put("yield_grams", number(yield))
                                            .put("ingredients", parts);
                            if (existing != null && existing.has("family"))
                                recipe.put("family", existing.getString("family"));
                            if (forMeal >= 0) recipe.put("primary_meal", forMeal);
                            MealRecipe created = catalog.readRecipe(recipe);
                            if (forMeal >= 0 && !created.allowed(store.profile(), forMeal))
                                throw new IllegalArgumentException(
                                        "This recipe conflicts with your eating pattern, restrictions or cooking access. Edit its ingredients or preferences.");
                            JSONArray current = store.personalRecipes(), updated = new JSONArray();
                            for (int i = 0; i < current.length(); i++)
                                if (!current.getJSONObject(i).getString("id").equals(id))
                                    updated.put(current.getJSONObject(i));
                            updated.put(recipe);
                            if (forMeal >= 0) {
                                List<MealRecipe> all = new ArrayList<>(catalog.recipes);
                                all.removeIf(r -> r.id.equals(id));
                                all.add(created);
                                async(
                                        () -> {
                                            DayPlanner.Request r = buildStandardRequest();
                                            StandardPlan changed =
                                                    StandardPlan.fromJson(
                                                            store.standardPlan(), all, r);
                                            if (changed == null) changed = new StandardPlan();
                                            changed.choose(
                                                    new DayPlanner.Choice(forMeal, created, 1),
                                                    all,
                                                    r);
                                            store.saveRecipesAndStandardPlan(updated, changed);
                                            return new Object[] {changed, r};
                                        },
                                        value -> {
                                            catalog.recipes.clear();
                                            catalog.recipes.addAll(all);
                                            editingMeal = null;
                                            acceptStandard(value);
                                            message("This meal saved; other meal selections kept");
                                        });
                            } else {
                                store.saveRecipes(updated);
                                load();
                            }
                            return true;
                        } catch (Exception e) {
                            message(e.getMessage());
                            return false;
                        }
                    });
        } catch (Exception e) {
            message("Unable to open this recipe");
        }
    }

    private void message(String text) {
        Toast.makeText(this, text == null ? "Review these values" : text, Toast.LENGTH_LONG).show();
    }

    private interface Save {
        boolean save();
    }

    private void dialog(String title, LinearLayout content, Save save) {
        AlertDialog d =
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(scroll(content))
                        .setNegativeButton("Close", null)
                        .setPositiveButton(save == null ? "Done" : "Save", null)
                        .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                        v -> {
                            if (save == null || save.save()) d.dismiss();
                        });
    }

    private ScrollView scroll(LinearLayout content) {
        ScrollView s = new ScrollView(this);
        content.setPadding(dp(18), dp(12), dp(18), dp(24));
        s.addView(content);
        return s;
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout box = column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, dp(8));
        box.setLayoutParams(lp);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrostedUi.surface(box, FrostedSurfaceDrawable.Material.CARD);
        parent.addView(box);
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

    private Button button(LinearLayout parent, String value, Runnable click) {
        Button b = new Button(this);
        b.setEnabled(!busy);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextColor(getColor(R.color.accent));
        b.setMinHeight(dp(48));
        b.setOnClickListener(
                v -> {
                    if (!busy) click.run();
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(5), 0, dp(5));
        parent.addView(b, lp);
        FrostedUi.surface(b, FrostedSurfaceDrawable.Material.CONTROL);
        return b;
    }

    private EditText input(LinearLayout parent, String label, Object value, boolean numeric) {
        text(parent, label, 13, false);
        EditText e = new EditText(this);
        e.setEnabled(!busy);
        e.setTextColor(getColor(R.color.text_primary));
        e.setHintTextColor(getColor(R.color.text_secondary));
        e.setSingleLine(true);
        e.setFilters(new InputFilter[] {new InputFilter.LengthFilter(128)});
        e.setInputType(
                numeric
                        ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        : InputType.TYPE_CLASS_TEXT);
        if (value != null) e.setText(value.toString());
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        parent.addView(e);
        FrostedUi.surface(e, FrostedSurfaceDrawable.Material.INPUT);
        return e;
    }

    private CheckBox check(LinearLayout parent, String label, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setEnabled(!busy);
        c.setText(label);
        c.setTextColor(getColor(R.color.text_primary));
        c.setButtonTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        c.setChecked(value);
        c.setMinHeight(dp(48));
        parent.addView(c);
        return c;
    }

    private static double number(EditText e) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? 0 : Double.parseDouble(s);
    }

    private static String money(double amount) {
        return String.format(Locale.getDefault(), "₹%.0f", amount);
    }

    private void setPlannerEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                setPlannerEnabled(group.getChildAt(i), enabled);
        }
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private static String foodIdentity(FoodEntry e) {
        return e.sourceFoodId == null || e.sourceFoodId.trim().isEmpty()
                ? "manual:" + e.foodKey()
                : e.sourceFoodId;
    }

    private static int mealIndex(String key) {
        switch (key) {
            case "breakfast":
                return 0;
            case "lunch":
                return 1;
            case "snacks":
                return 2;
            default:
                return 3;
        }
    }

    private static String mealKey(int index) {
        return new String[] {"breakfast", "lunch", "snacks", "dinner"}[index];
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        out.putString("day", date.toString());
        out.putBoolean("setupOpened", setupOpened);
        out.putBoolean("generateAfterSetup", generateAfterSetup);
        if (editingMeal != null) out.putInt("editingMeal", editingMeal);
        super.onSaveInstanceState(out);
    }

    @Override
    protected void onActivityResult(int code, int resultCode, Intent data) {
        super.onActivityResult(code, resultCode, data);
        if (code == SETUP_REQUEST && resultCode == RESULT_OK) generateAfterSetup = true;
    }

    @Override
    public void onBackPressed() {
        if (editingMeal != null && !busy) {
            editingMeal = null;
            render();
        } else if (!busy) super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (!ready && !busy) load();
        else if (ready) render();
    }

    @Override
    protected void onPause() {
        foreground = false;
        generation++;
        busy = false;
        ready = false;
        result = null;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        generation++;
        worker.execute(
                () -> {
                    if (db != null) db.close();
                    if (diary != null) diary.close();
                });
        super.onDestroy();
    }
}
