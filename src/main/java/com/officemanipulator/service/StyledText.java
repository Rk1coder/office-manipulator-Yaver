package com.officemanipulator.service;

import java.util.ArrayList;
import java.util.List;

/** Maps retained characters back to their original runs, including between separate edits. */
final class StyledText {
    private StyledText() {}

    static List<String> replace(List<String> runs, String replacement) {
        String old = String.join("", runs);
        if (old.equals(replacement) || old.trim().equals(replacement)) return runs;
        // The preview trims text; its hidden outer whitespace must survive value edits.
        if (replacement.equals(replacement.trim())) {
            int first = 0, last = old.length();
            while (first < last && old.charAt(first) <= ' ') first++;
            while (last > first && old.charAt(last - 1) <= ' ') last--;
            replacement = old.substring(0, first) + replacement + old.substring(last);
        }
        if (runs.size() == 1) return List.of(replacement);
        int prefix = 0, suffix = 0;
        while (prefix < old.length() && prefix < replacement.length()
                && old.charAt(prefix) == replacement.charAt(prefix)) prefix++;
        while (suffix < old.length() - prefix && suffix < replacement.length() - prefix
                && old.charAt(old.length() - suffix - 1) == replacement.charAt(replacement.length() - suffix - 1)) suffix++;
        String before = old.substring(prefix, old.length() - suffix);
        String after = replacement.substring(prefix, replacement.length() - suffix);
        // Bound work for adversarial or very large edits; callers surface this as an explicit failure.
        if (before.length() > 20_000 || after.length() > 20_000
                || (long) (before.length() + 1) * (after.length() + 1) > 4_000_000)
            throw new IllegalArgumentException("Bu kadar büyük karma biçimli metin değişikliği güvenle uygulanamıyor.");
        int[][] common = new int[before.length() + 1][after.length() + 1];
        for (int i = before.length() - 1; i >= 0; i--)
            for (int j = after.length() - 1; j >= 0; j--)
                common[i][j] = before.charAt(i) == after.charAt(j) ? 1 + common[i + 1][j + 1]
                        : Math.max(common[i + 1][j], common[i][j + 1]);
        int[] owner = new int[old.length() + 1];
        List<StringBuilder> result = new ArrayList<>();
        int offset = 0;
        for (int r = 0; r < runs.size(); r++) {
            result.add(new StringBuilder());
            for (int c = 0; c < runs.get(r).length(); c++) owner[offset++] = r;
        }
        owner[old.length()] = old.isEmpty() ? 0 : owner[old.length() - 1];
        for (int p = 0; p < prefix; p++) result.get(owner[p]).append(old.charAt(p));
        int i = 0, j = 0;
        while (i < before.length() || j < after.length()) {
            if (i < before.length() && j < after.length() && before.charAt(i) == after.charAt(j)) {
                result.get(owner[prefix + i++]).append(after.charAt(j++));
            } else if (j < after.length() && (i == before.length() || common[i][j + 1] >= common[i + 1][j])) {
                result.get(owner[prefix + i]).append(after.charAt(j++));
            } else i++;
        }
        for (int p = old.length() - suffix; p < old.length(); p++) result.get(owner[p]).append(old.charAt(p));
        return result.stream().map(StringBuilder::toString).toList();
    }
}
