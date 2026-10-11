package com.shadowphoenix.dietlog.planner;

import java.util.*;

/** Idempotent stock deduction. A manually replaced stock batch never inherits old deductions. */
public final class InventoryLedger {
    public static final class Item {
        public final double amount;
        public final String revision;

        public Item(double amount, String revision) {
            if (!Double.isFinite(amount) || amount < 0 || amount > 100000 || revision == null)
                throw new IllegalArgumentException("Invalid inventory amount");
            this.amount = amount;
            this.revision = revision;
        }
    }

    public static boolean apply(
            String token, Map<String, Item> stock, Map<String, Item> usage, Set<String> completed) {
        if (completed.contains(token)) return false;
        for (Map.Entry<String, Item> entry : usage.entrySet()) {
            Item current = stock.get(entry.getKey()), needed = entry.getValue();
            if (current != null && current.revision.equals(needed.revision))
                stock.put(
                        entry.getKey(),
                        new Item(Math.max(0, current.amount - needed.amount), current.revision));
        }
        completed.add(token);
        return true;
    }
}
