package com.officemanipulator.claude;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Figures (amounts, dates, counts) in the source that the generated document no longer contains.
 * Compared by digits only, so "40.000", "40,000" and "40000" match; single digits (list numbers) are ignored.
 * A comma may also be a CSV cell separator ("1,1250.5"), so a comma token also counts as its cells.
 * Dates with a month name ("10 Mart 2026") are checked as day + month, since "10" alone is usually somewhere else too. */
final class SourceCoverage {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final List<String> MONTHS = List.of("ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz",
            "ağustos", "eylül", "ekim", "kasım", "aralık", "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{1,2})\\s+(" + String.join("|", MONTHS) + ")(?!\\p{L})(?:\\s+\\d{4})?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[./](\\d{1,2})[./]\\d{4}(?!\\d)|(?<!\\d)\\d{4}-(\\d{2})-(\\d{2})(?!\\d)");
    private static final Pattern SOURCE_HEADER = Pattern.compile("(?m)^=== BELGE \\d+: (.+) ===$");

    private SourceCoverage() {}

    static List<String> missing(String source, String output) {
        if (source == null || source.isBlank()) return List.of();
        Set<String> present = new HashSet<>();
        for (String token : tokens(output)) {
            present.add(digits(token));
            for (String cell : token.split(",")) present.add(digits(cell));
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String token : tokens(source)) {
            if (digits(token).length() < 2 || present.contains(digits(token))) continue;
            if (!token.contains(",")) { missing.add(token); continue; }
            for (String cell : token.split(","))
                if (digits(cell).length() >= 2 && !present.contains(digits(cell))) missing.add(cell);
        }
        Set<String> outputDates = dateKeys(output);
        Matcher d = DATE.matcher(source);
        while (d.find()) if (!outputDates.contains(dateKey(d))) missing.add(d.group());
        return List.copyOf(missing);
    }

    /** For a multi-document source: each document's dates with the words before them, so the model sees when two
     * documents give different dates for the same event. Empty unless at least two documents contain dates. */
    static String datesBySource(String source) {
        if (source == null) return "";
        List<java.util.regex.MatchResult> headers = SOURCE_HEADER.matcher(source).results().toList();
        StringBuilder out = new StringBuilder();
        int withDates = 0;
        for (int i = 0; i < headers.size(); i++) {
            String body = source.substring(headers.get(i).end(), i + 1 < headers.size() ? headers.get(i + 1).start() : source.length());
            Matcher d = DATE.matcher(body);
            boolean any = false;
            while (d.find()) {
                int lineStart = body.lastIndexOf('\n', d.start()) + 1;
                String before = body.substring(Math.max(lineStart, d.start() - 60), d.start()).strip();
                out.append("- ").append(headers.get(i).group(1)).append(": ").append(d.group())
                        .append(before.isEmpty() ? "" : " (\"…" + before + "\")").append('\n');
                any = true;
            }
            if (any) withDates++;
        }
        return withDates < 2 ? "" : out.toString();
    }

    private static Set<String> dateKeys(String text) {
        Set<String> keys = new HashSet<>();
        if (text == null) return keys;
        Matcher d = DATE.matcher(text);
        while (d.find()) keys.add(dateKey(d));
        Matcher n = NUMERIC_DATE.matcher(text);
        while (n.find()) {
            int day = Integer.parseInt(n.group(1) != null ? n.group(1) : n.group(4));
            int month = Integer.parseInt(n.group(2) != null ? n.group(2) : n.group(3));
            if (month >= 1 && month <= 12) keys.add(day + "/" + month);
        }
        return keys;
    }

    private static String dateKey(Matcher d) {
        int month = MONTHS.indexOf(d.group(2).toLowerCase(java.util.Locale.ROOT)) % 12 + 1;
        return Integer.parseInt(d.group(1)) + "/" + month;
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = NUMBER.matcher(text == null ? "" : text);
        while (m.find()) tokens.add(m.group());
        return tokens;
    }

    private static String digits(String number) {
        return number.replaceAll("[.,]", "");
    }
}
