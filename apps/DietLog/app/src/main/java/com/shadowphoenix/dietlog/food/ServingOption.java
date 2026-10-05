package com.shadowphoenix.dietlog.food;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServingOption {
    private static final Pattern PACK_GRAMS =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*g(?:rams?)?\\b", Pattern.CASE_INSENSITIVE);

    public final String label;
    public final double grams;

    public ServingOption(String label, double grams) {
        this.label = label;
        this.grams = grams;
    }

    public static List<ServingOption> forFood(CatalogFood food) {
        List<ServingOption> out = new ArrayList<>();
        if (food.servingG != null && food.servingG > 0) {
            add(out, cleanServingLabel(food.servingSize), food.servingG);
        }

        String name = food.name == null ? "" : food.name.toLowerCase(Locale.ROOT);
        if (name.contains("idli")) add(out, "1 idli", 50);
        else if (name.contains("dosa")) add(out, "1 dosa", 100);
        else if (name.contains("egg")) add(out, "1 egg", 50);
        else if (name.contains("chapati") || name.contains("roti")) add(out, "1 piece", 40);
        else if (name.contains("bread")) add(out, "1 slice", 30);
        else if (name.contains("milk")) add(out, "1 glass", 250);
        else if (name.contains("curd") || name.contains("yogurt")) add(out, "1 cup", 200);
        else if (name.contains("banana")) add(out, "1 medium", 118);
        else if (name.contains("apple")) add(out, "1 medium", 182);
        else if (name.contains("cooked rice")) add(out, "1 cup", 158);
        else if ("INDB".equalsIgnoreCase(food.source) &&
                (name.contains("rice") || name.contains("biryani") || name.contains("pulao"))) {
            add(out, "1 cup", 180);
        }

        Double packGrams = parsePackGrams(food.quantity);
        if (packGrams != null) add(out, "1 pack", packGrams);
        add(out, "100 g", 100);
        return out;
    }

    static Double parsePackGrams(String quantity) {
        if (quantity == null) return null;
        Matcher matcher = PACK_GRAMS.matcher(quantity);
        if (!matcher.find()) return null;
        try {
            double grams = Double.parseDouble(matcher.group(1));
            return grams > 0 && grams <= 5000 ? grams : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String cleanServingLabel(String label) {
        return label == null || label.trim().isEmpty() ? "1 serving" : label.trim();
    }

    private static void add(List<ServingOption> out, String label, double grams) {
        if (!Double.isFinite(grams) || grams <= 0) return;
        for (ServingOption option : out) {
            if (Math.abs(option.grams - grams) < 0.01) return;
        }
        out.add(new ServingOption(label, grams));
    }
}
