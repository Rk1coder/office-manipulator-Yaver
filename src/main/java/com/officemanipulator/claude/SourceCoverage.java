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
 * A comma may also be a CSV cell separator ("1,1250.5"), so a comma token also counts as its cells. */
final class SourceCoverage {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

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
        return List.copyOf(missing);
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
