package com.officemanipulator.generator;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Deterministic finish applied to every generated workbook, whatever the model wrote: themed header
 * row, frozen header, auto filter, zebra rows, thousands/decimal and Turkish date formats, and column
 * widths that fit the content. The table is the first row of at least two text cells (title rows
 * above it are left alone) down to the first blank row.
 */
public final class XlsxTheme {
    static final String DEFAULT_PRIMARY = "1F4E79";
    private static final int MAX_WIDTH = 60;
    // Integers below this stay as typed: years, codes and small counts must not become "2.024".
    private static final double THOUSANDS_FROM = 10_000;

    private final XSSFWorkbook wb;
    private final Map<String, XSSFCellStyle> styles = new HashMap<>();
    private final Map<Integer, XSSFFont> headerFonts = new HashMap<>();
    private final byte[] primary, zebra;
    private final boolean darkPrimary;

    private XlsxTheme(XSSFWorkbook wb, String primaryHex) {
        this.wb = wb;
        primary = rgb(primaryHex == null || !primaryHex.matches("[0-9A-Fa-f]{6}") ? DEFAULT_PRIMARY : primaryHex);
        zebra = new byte[3];
        for (int i = 0; i < 3; i++) zebra[i] = (byte) ((primary[i] & 0xFF) * 0.1 + 255 * 0.9);
        darkPrimary = 0.299 * (primary[0] & 0xFF) + 0.587 * (primary[1] & 0xFF) + 0.114 * (primary[2] & 0xFF) < 160;
    }

    static void apply(XSSFWorkbook wb, String primaryHex) {
        var theme = new XlsxTheme(wb, primaryHex);
        for (Sheet sheet : wb) theme.style((XSSFSheet) sheet);
    }

    private void style(XSSFSheet sheet) {
        int header = headerRow(sheet);
        int firstCol = 0, lastCol = -1, end = sheet.getLastRowNum();
        if (header >= 0) {
            Row row = sheet.getRow(header);
            firstCol = row.getFirstCellNum();
            lastCol = row.getLastCellNum() - 1;
            for (int r = header + 1; r <= sheet.getLastRowNum(); r++)
                if (blank(sheet.getRow(r))) { end = r - 1; break; }
        }
        formatNumbers(sheet, header);
        if (header >= 0) {
            for (Cell cell : sheet.getRow(header)) {
                if (cell.getColumnIndex() > lastCol) continue;
                derive(cell, "header", s -> {
                    s.setFillForegroundColor(new XSSFColor(primary, null));
                    s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    s.setFont(headerFont(s.getFont()));
                    s.setVerticalAlignment(VerticalAlignment.CENTER);
                    s.setWrapText(true);
                });
            }
            for (int r = header + 2; r <= end; r += 2) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                for (int c = firstCol; c <= lastCol; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    var current = (XSSFCellStyle) cell.getCellStyle();
                    // Fills the model chose (highlights) and bold totals rows keep their look.
                    if (current.getFillPattern() != FillPatternType.NO_FILL || current.getFont().getBold()) continue;
                    derive(cell, "zebra", s -> {
                        s.setFillForegroundColor(new XSSFColor(zebra, null));
                        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    });
                }
            }
            if (sheet.getPaneInformation() == null) sheet.createFreezePane(0, header + 1);
            if (!sheet.getCTWorksheet().isSetAutoFilter() && end > header)
                sheet.setAutoFilter(new CellRangeAddress(header, end, firstCol, lastCol));
        }
        fitColumns(sheet, Math.max(header, 0));
    }

    /** First of the top ten rows with two or more text cells and nothing else, followed by a row. */
    public static int headerRow(Sheet sheet) {
        int first = Math.max(sheet.getFirstRowNum(), 0);
        for (int r = first; r <= Math.min(sheet.getLastRowNum() - 1, first + 9); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int text = 0, other = 0;
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.STRING && !cell.getStringCellValue().isBlank()) text++;
                else if (cell.getCellType() != CellType.BLANK) other++;
            }
            if (text >= 2 && other == 0 && !blank(sheet.getRow(r + 1))) return r;
        }
        return -1;
    }

    private void formatNumbers(Sheet sheet, int header) {
        for (Row row : sheet) {
            for (Cell cell : row) {
                boolean numeric = cell.getCellType() == CellType.NUMERIC
                        || cell.getCellType() == CellType.FORMULA && cell.getCachedFormulaResultType() == CellType.NUMERIC;
                if (!numeric) continue;
                CellStyle style = cell.getCellStyle();
                if (DateUtil.isCellDateFormatted(cell)) {
                    String format = style.getDataFormatString();
                    // exceljs/US defaults ("mm-dd-yy", "m/d/yy") read wrong in Türkiye.
                    if (style.getDataFormat() == 14 || "mm-dd-yy".equals(format) || "m/d/yy".equals(format))
                        setFormat(cell, "dd.mm.yyyy");
                    continue;
                }
                if (style.getDataFormat() != 0) continue; // the model chose a format
                if (header >= 0 && looksLikeCode(sheet, header, cell.getColumnIndex())) continue;
                double value = cell.getNumericCellValue();
                if (value != Math.rint(value)) setFormat(cell, "#,##0.00");
                else if (Math.abs(value) >= THOUSANDS_FROM) setFormat(cell, "#,##0");
            }
        }
    }

    private static boolean looksLikeCode(Sheet sheet, int header, int column) {
        Cell title = sheet.getRow(header).getCell(column);
        return title != null && title.getCellType() == CellType.STRING
                && title.getStringCellValue().toLowerCase(java.util.Locale.ROOT)
                        .matches(".*\\b(yıl|year|kod|code|no|id|tc|telefon|phone|posta|zip)\\b.*");
    }

    private void setFormat(Cell cell, String format) {
        short id = wb.createDataFormat().getFormat(format);
        derive(cell, "fmt" + id, s -> s.setDataFormat(id));
    }

    private void fitColumns(Sheet sheet, int fromRow) {
        var formatter = new DataFormatter();
        var widths = new HashMap<Integer, Integer>();
        for (Row row : sheet) {
            if (row.getRowNum() < fromRow) continue;
            for (Cell cell : row) {
                if (inMerge(sheet, cell)) continue;
                int longest = 0;
                for (String line : formatter.formatCellValue(cell).split("\n")) longest = Math.max(longest, line.length());
                widths.merge(cell.getColumnIndex(), longest, Math::max);
            }
        }
        // +3 leaves room for the filter arrow on the header.
        widths.forEach((column, length) -> {
            int chars = Math.min(Math.max(length + 3, 8), MAX_WIDTH);
            if (chars * 256 > sheet.getColumnWidth(column)) sheet.setColumnWidth(column, chars * 256);
        });
    }

    private static boolean inMerge(Sheet sheet, Cell cell) {
        for (var region : sheet.getMergedRegions())
            if (region.isInRange(cell)) return true;
        return false;
    }

    /** Shared styles are copied, never edited in place: one cell's change must not leak to others. */
    private void derive(Cell cell, String kind, Consumer<XSSFCellStyle> change) {
        String key = cell.getCellStyle().getIndex() + "|" + kind;
        XSSFCellStyle style = styles.computeIfAbsent(key, k -> {
            XSSFCellStyle copy = wb.createCellStyle();
            copy.cloneStyleFrom(cell.getCellStyle());
            change.accept(copy);
            return copy;
        });
        cell.setCellStyle(style);
    }

    private XSSFFont headerFont(XSSFFont base) {
        return headerFonts.computeIfAbsent(base.getIndex(), i -> {
            XSSFFont font = wb.createFont();
            font.setFontName(base.getFontName());
            font.setFontHeightInPoints(base.getFontHeightInPoints());
            font.setBold(true);
            font.setColor(new XSSFColor(darkPrimary ? new byte[]{-1, -1, -1} : new byte[]{0, 0, 0}, null));
            return font;
        });
    }

    public static boolean blank(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell.getCellType() == CellType.STRING ? !cell.getStringCellValue().isBlank() : cell.getCellType() != CellType.BLANK)
                return false;
        }
        return true;
    }

    private static byte[] rgb(String hex) {
        int value = Integer.parseInt(hex, 16);
        return new byte[]{(byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }
}
