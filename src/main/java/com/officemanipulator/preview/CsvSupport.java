package com.officemanipulator.preview;

import org.apache.commons.csv.CSVFormat;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Shared dialect detection; no header map, so duplicate/blank column names retain their positions. */
public final class CsvSupport {
    private CsvSupport() {}

    public static String text(Path path) throws IOException {
        return withoutBom(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String withoutBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * Compare complete logical records, not punctuation in the first physical line. A real
     * multicolumn header beats an incidental single-column interpretation; otherwise prefer
     * consistent record widths, then more columns. Equally supported/one-column inputs use comma.
     */
    public static char delimiter(String text) {
        String source = withoutBom(text);
        Dialect comma = inspect(source, ',');
        Dialect semicolon = inspect(source, ';');
        if (comma.valid() != semicolon.valid()) return semicolon.valid() ? ';' : ',';
        if (!comma.valid()) return ','; // The actual parse reports the malformed input.
        if ((comma.width() > 1) != (semicolon.width() > 1)) return semicolon.width() > 1 ? ';' : ',';
        long semiConsistency = semicolon.matching() * comma.records();
        long commaConsistency = comma.matching() * semicolon.records();
        if (semiConsistency != commaConsistency) return semiConsistency > commaConsistency ? ';' : ',';
        return semicolon.width() > comma.width() ? ';' : ',';
    }

    private record Dialect(boolean valid, int width, long matching, long records) {}

    private static Dialect inspect(String text, char delimiter) {
        int width = 0;
        long matching = 0, records = 0;
        try (var parser = format(delimiter).parse(new StringReader(text))) {
            for (var record : parser) {
                if (records++ == 0) width = record.size();
                if (record.size() == width) matching++;
            }
            return new Dialect(true, width, matching, records);
        } catch (IOException | UncheckedIOException e) {
            return new Dialect(false, 0, 0, 0);
        }
    }

    private static CSVFormat format(char delimiter) {
        return CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setIgnoreEmptyLines(false).build();
    }

    public static List<List<String>> rows(String text) throws IOException {
        String source = withoutBom(text);
        try (var parser = format(delimiter(source)).parse(new StringReader(source))) {
            return parser.getRecords().stream().map(r -> r.toList()).toList();
        }
    }
}
