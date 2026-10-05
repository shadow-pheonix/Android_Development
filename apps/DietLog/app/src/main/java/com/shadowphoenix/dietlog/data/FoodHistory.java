package com.shadowphoenix.dietlog.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FoodHistory {
    public static final class Item {
        public final FoodEntry entry;
        public final int count;

        public Item(FoodEntry entry, int count) {
            this.entry = entry;
            this.count = count;
        }
    }

    private static final class Accumulator {
        final FoodEntry latest;
        int count;

        Accumulator(FoodEntry latest) {
            this.latest = latest;
            this.count = 1;
        }
    }

    private FoodHistory() {}

    public static List<Item> recent(List<FoodEntry> newestFirst, int limit) {
        Map<String, Accumulator> byKey = aggregate(newestFirst);
        List<Item> out = new ArrayList<>();
        int safeLimit = Math.max(0, limit);
        if (safeLimit == 0) return out;
        for (Accumulator item : byKey.values()) {
            out.add(new Item(item.latest, item.count));
            if (out.size() >= safeLimit) break;
        }
        return out;
    }

    public static List<Item> frequent(List<FoodEntry> newestFirst, int limit) {
        List<Accumulator> values = new ArrayList<>(aggregate(newestFirst).values());
        values.sort(Comparator.<Accumulator>comparingInt(a -> a.count).reversed()
                .thenComparing(Comparator.comparingLong((Accumulator a) -> a.latest.loggedAt).reversed()));
        List<Item> out = new ArrayList<>();
        int safeLimit = Math.max(0, limit);
        for (int i = 0; i < values.size() && i < safeLimit; i++) {
            Accumulator item = values.get(i);
            out.add(new Item(item.latest, item.count));
        }
        return out;
    }

    private static LinkedHashMap<String, Accumulator> aggregate(List<FoodEntry> newestFirst) {
        LinkedHashMap<String, Accumulator> byKey = new LinkedHashMap<>();
        for (FoodEntry entry : newestFirst) {
            Accumulator item = byKey.get(entry.foodKey());
            if (item == null) byKey.put(entry.foodKey(), new Accumulator(entry));
            else item.count++;
        }
        return byKey;
    }
}
