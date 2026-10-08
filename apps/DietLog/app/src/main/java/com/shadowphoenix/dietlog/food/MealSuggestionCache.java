package com.shadowphoenix.dietlog.food;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Cached immutable worker results are keyed by normalized meal. Display state belongs to the UI thread. */
public final class MealSuggestionCache<T> {
    private final Map<String, List<T>> foods = new ConcurrentHashMap<>();
    private String displayedMeal;

    public List<T> get(String meal) { return foods.get(meal); }

    public List<T> store(String meal, List<T> result) {
        List<T> snapshot = Collections.unmodifiableList(new ArrayList<>(result));
        foods.put(meal, snapshot);
        return snapshot;
    }

    public boolean needsRender(String meal) { return !meal.equals(displayedMeal); }

    public void markDisplayed(String meal) { displayedMeal = meal; }

    public void invalidateDisplayed() { displayedMeal = null; }
}
