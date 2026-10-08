package com.shadowphoenix.dietlog.food;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure search planning/ranking. User text never becomes SQL or FTS operators. */
public final class FoodSearch {
    private FoodSearch() {}

    public static String normalize(String raw) {
        String value = Normalizer.normalize(raw == null ? "" : raw, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT).replaceAll("[\\u0300-\\u036f]", "");
        StringBuilder out = new StringBuilder();
        boolean space = true;
        for (int i = 0; i < value.length();) {
            int c = value.codePointAt(i); i += Character.charCount(c);
            int type = Character.getType(c);
            if (Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                out.appendCodePoint(c); space = false;
            } else if (!space) { out.append(' '); space = true; }
        }
        return out.toString().trim();
    }

    public static final class Query {
        public final String text;
        public final List<String> tokens;
        public final boolean barcode;
        public final boolean spellingAdjusted;
        public Query(String raw) {
            // Bound expensive matching even for pasted descriptions.
            String value = raw == null ? "" : raw.trim();
            if (value.length() > 128) value = value.substring(0, 128);
            barcode = value.matches("\\d{6,14}");
            String normalized = normalize(value);
            List<String> parts = normalized.isEmpty() ? Collections.emptyList()
                    : Arrays.asList(normalized.split(" "));
            List<String> canonical = new ArrayList<>();
            for (String part : parts.subList(0, Math.min(8, parts.size()))) canonical.add(canonicalSpelling(part));
            spellingAdjusted = !canonical.equals(parts.subList(0, Math.min(8, parts.size())));
            tokens = Collections.unmodifiableList(canonical);
            text = String.join(" ", tokens);
        }
        public String match() {
            List<List<String>> terms = new ArrayList<>();
            for (String token : tokens) terms.add(Collections.singletonList(token));
            return expression(terms);
        }
    }

    private static String canonicalSpelling(String token) {
        switch (token) {
            case "chiken": case "chciken": return "chicken";
            case "panner": return "paneer";
            case "briyani": case "biriyani": return "biryani";
            case "basmathi": return "basmati";
            default: return token;
        }
    }

    public static String expression(List<List<String>> alternatives) {
        List<String> clauses = new ArrayList<>();
        for (List<String> group : alternatives) {
            List<String> words = new ArrayList<>();
            for (String term : group) {
                String safe = normalize(term);
                if (!safe.isEmpty() && !safe.contains(" ")) words.add("\"" + safe + "*\"");
            }
            if (!words.isEmpty()) clauses.add(words.size() == 1 ? words.get(0)
                    : "(" + String.join(" OR ", words) + ")");
        }
        return String.join(" AND ", clauses);
    }

    public static int score(Query query, String name, String brand, String aliases, String category,
                            String source, boolean complete) {
        return score(query, name, brand, aliases, category, source, complete, false);
    }

    public static int score(Query query, String name, String brand, String aliases, String category,
                            String source, boolean complete, boolean approximate) {
        String n = normalize(name), b = normalize(brand), a = aliases == null ? "" : aliases;
        int tier;
        if (n.equals(query.text) || Arrays.asList(a.split("\n")).contains(query.text)) tier = 0;
        else if ((" " + n + " ").contains(" " + query.text + " ")) tier = 8;
        else {
            tier = 20;
            String[] names = n.split(" "), brands = b.split(" ");
            String[] aliasWords = normalize(a).split(" "), categories = normalize(category).split(" ");
            for (String token : query.tokens) {
                int cost = Math.min(wordCost(token, names, 0, approximate), wordCost(token, brands, 0, approximate));
                cost = Math.min(cost, wordCost(token, aliasWords, 2, approximate));
                cost = Math.min(cost, wordCost(token, categories, 35, false));
                tier += cost;
            }
        }
        int sourceOrder = "INDB".equals(source) ? 0 : "IFCT".equals(source) ? 4
                : "USDA".equals(source) ? 6 : 8;
        return tier * 100 + (complete ? 0 : 25) + sourceOrder;
    }

    private static int wordCost(String token, String[] words, int base, boolean approximate) {
        int cost = 100;
        for (String word : words) {
            if (word.equals(token)) return base;
            if (word.startsWith(token)) cost = Math.min(cost, base + 4);
            if (approximate && canCorrect(token) && word.length() <= 32
                    && Math.abs(word.length() - token.length()) <= 2) {
                int edits = distance(token, word);
                if (edits <= (token.length() < 6 ? 1 : 2)) cost = Math.min(cost, base + edits * 8);
            }
        }
        return cost;
    }

    public static boolean canCorrect(String token) { return token.matches("[a-z]{4,32}"); }

    public static Set<String> deletions(String token) {
        Set<String> out = new LinkedHashSet<>(); out.add(token);
        for (int i = 0; i < token.length(); i++) out.add(token.substring(0, i) + token.substring(i + 1));
        return out;
    }

    public static final class Term {
        public final String word;
        public final int frequency;
        public Term(String word, int frequency) { this.word = word; this.frequency = frequency; }
    }

    public static List<String> corrections(String token, List<Term> candidates) {
        if (!canCorrect(token)) return Collections.emptyList();
        int maxDistance = token.length() < 6 ? 1 : 2;
        List<Term> good = new ArrayList<>();
        for (Term term : candidates) {
            if (Math.abs(term.word.length() - token.length()) <= maxDistance
                    && distance(token, term.word) <= maxDistance) good.add(term);
        }
        good.sort(Comparator.comparingInt((Term t) -> distance(token, t.word))
                .thenComparing(Comparator.comparingInt((Term t) -> t.frequency).reversed())
                .thenComparing(t -> t.word));
        List<String> out = new ArrayList<>();
        for (Term term : good) if (!out.contains(term.word)) {
            out.add(term.word); if (out.size() == 3) break;
        }
        return out;
    }

    /** Optimal string alignment: handles adjacent swaps as well as edits. */
    public static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) for (int j = 1; j <= b.length(); j++) {
            int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
            d[i][j] = Math.min(d[i - 1][j] + 1, Math.min(d[i][j - 1] + 1, d[i - 1][j - 1] + cost));
            if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1))
                d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
        }
        return d[a.length()][b.length()];
    }
}
