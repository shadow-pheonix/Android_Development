package com.shadowphoenix.dietlog.nutrition;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class MicronutrientValues {
    private final EnumMap<Micronutrient, Double> values;

    public MicronutrientValues() {
        values = new EnumMap<>(Micronutrient.class);
    }

    private MicronutrientValues(EnumMap<Micronutrient, Double> values) {
        this.values = values;
    }

    public static MicronutrientValues empty() {
        return new MicronutrientValues();
    }

    public MicronutrientValues with(Micronutrient nutrient, Double value) {
        EnumMap<Micronutrient, Double> copy = new EnumMap<>(values);
        if (nutrient != null && value != null && Double.isFinite(value) && value >= 0.0) {
            copy.put(nutrient, value);
        } else if (nutrient != null) {
            copy.remove(nutrient);
        }
        return new MicronutrientValues(copy);
    }

    public Double get(Micronutrient nutrient) {
        return values.get(nutrient);
    }

    public boolean has(Micronutrient nutrient) {
        return values.containsKey(nutrient);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public int knownCount() {
        return values.size();
    }

    public Map<Micronutrient, Double> asMap() {
        return Collections.unmodifiableMap(values);
    }

    public MicronutrientValues scaled(double factor) {
        if (!Double.isFinite(factor) || factor < 0.0) throw new IllegalArgumentException("Scale must be non-negative");
        EnumMap<Micronutrient, Double> out = new EnumMap<>(Micronutrient.class);
        for (Map.Entry<Micronutrient, Double> item : values.entrySet()) {
            out.put(item.getKey(), item.getValue() * factor);
        }
        return new MicronutrientValues(out);
    }

    public MicronutrientValues plus(MicronutrientValues other) {
        EnumMap<Micronutrient, Double> out = new EnumMap<>(values);
        if (other != null) {
            for (Map.Entry<Micronutrient, Double> item : other.values.entrySet()) {
                out.put(item.getKey(), out.getOrDefault(item.getKey(), 0.0) + item.getValue());
            }
        }
        return new MicronutrientValues(out);
    }

    public String encode() {
        if (values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Micronutrient nutrient : Micronutrient.values()) {
            Double value = values.get(nutrient);
            if (value == null) continue;
            if (out.length() > 0) out.append('|');
            out.append(nutrient.key).append('=').append(Double.toString(value));
        }
        return out.toString();
    }

    public static MicronutrientValues decode(String raw) {
        MicronutrientValues out = empty();
        if (raw == null || raw.trim().isEmpty()) return out;
        String[] pairs = raw.split("\\|");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals >= pair.length() - 1) continue;
            Micronutrient nutrient = Micronutrient.fromKey(pair.substring(0, equals));
            if (nutrient == null) continue;
            try {
                double value = Double.parseDouble(pair.substring(equals + 1));
                if (Double.isFinite(value) && value >= 0.0) out = out.with(nutrient, value);
            } catch (NumberFormatException ignored) {
                // Ignore malformed values so old diary rows remain readable.
            }
        }
        return out;
    }
}
