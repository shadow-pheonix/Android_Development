package com.shadowphoenix.dietlog.food;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BarcodeValue {
    private BarcodeValue() {}

    public static List<String> lookupCandidates(String raw) {
        return lookupCandidates(raw, null);
    }

    public static List<String> lookupCandidates(String raw, String format) {
        if (raw == null) return Collections.emptyList();
        String value = raw.trim();
        if (!value.matches("\\d{6,14}")) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        addUnique(out, value);

        if (value.length() == 12) {
            addUnique(out, "0" + value); // UPC-A represented as EAN-13.
        } else if (value.length() == 13 && value.charAt(0) == '0') {
            addUnique(out, value.substring(1)); // EAN-13 representation of UPC-A.
        } else if (value.length() == 8 && "UPC_E".equalsIgnoreCase(format)) {
            String expanded = expandUpce(value);
            if (expanded != null) {
                addUnique(out, expanded);
                addUnique(out, "0" + expanded);
            }
        }
        return out;
    }

    static String expandUpce(String value) {
        if (value == null || value.length() != 8 || !value.matches("\\d{8}")) return null;
        char[] middle = value.substring(1, 7).toCharArray();
        char last = middle[5];
        StringBuilder out = new StringBuilder(12);
        out.append(value.charAt(0));
        switch (last) {
            case '0':
            case '1':
            case '2':
                out.append(middle, 0, 2).append(last).append("0000").append(middle, 2, 3);
                break;
            case '3':
                out.append(middle, 0, 3).append("00000").append(middle, 3, 2);
                break;
            case '4':
                out.append(middle, 0, 4).append("00000").append(middle[4]);
                break;
            default:
                out.append(middle, 0, 5).append("0000").append(last);
                break;
        }
        out.append(value.charAt(7));
        return out.toString();
    }

    private static void addUnique(List<String> values, String candidate) {
        if (candidate != null && !values.contains(candidate)) values.add(candidate);
    }
}
