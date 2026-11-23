package com.officemanipulator.generator;

import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class XlsxThemeTest {
    @Test void tableUnderATitleGetsHeaderFreezeFilterZebraFormatsAndWidths() {
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet("Satışlar");
            sheet.createRow(0).createCell(0).setCellValue("2025 Satış Raporu — çok uzun bir başlık satırı burada");
            var header = sheet.createRow(1);
            String[] titles = {"Bölge", "Yıl", "Tutar", "Oran", "Tarih"};
            for (int i = 0; i < titles.length; i++) header.createCell(i).setCellValue(titles[i]);
            var dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat((short) 14);
            for (int r = 2; r <= 5; r++) {
                var row = sheet.createRow(r);
                row.createCell(0).setCellValue("Bölge " + r);
                row.createCell(1).setCellValue(2024);
                row.createCell(2).setCellValue(125000);
                row.createCell(3).setCellValue(0.25);
                var date = row.createCell(4);
                date.setCellValue(LocalDate.of(2025, 3, r));
                date.setCellStyle(dateStyle);
            }
            sheet.createRow(7).createCell(0).setCellValue("Not: kaynak muhasebe");

            XlsxTheme.apply(wb, "C00000");

            var headerStyle = (XSSFCellStyle) sheet.getRow(1).getCell(0).getCellStyle();
            assertEquals(FillPatternType.SOLID_FOREGROUND, headerStyle.getFillPattern());
            assertEquals("C00000", headerStyle.getFillForegroundXSSFColor().getARGBHex().substring(2));
            assertTrue(headerStyle.getFont().getBold());
            assertEquals(2, sheet.getPaneInformation().getHorizontalSplitPosition(), "rows above the data are frozen");
            assertEquals("A2:E6", sheet.getCTWorksheet().getAutoFilter().getRef(), "filter stops at the blank row");
            assertEquals(FillPatternType.NO_FILL, sheet.getRow(2).getCell(0).getCellStyle().getFillPattern());
            assertEquals(FillPatternType.SOLID_FOREGROUND, sheet.getRow(3).getCell(0).getCellStyle().getFillPattern(), "zebra");
            assertEquals(FillPatternType.NO_FILL, sheet.getRow(7).getCell(0).getCellStyle().getFillPattern(), "notes below the table untouched");
            assertEquals("General", sheet.getRow(2).getCell(1).getCellStyle().getDataFormatString(), "years stay 2024");
            assertEquals("#,##0", sheet.getRow(2).getCell(2).getCellStyle().getDataFormatString());
            assertEquals("#,##0.00", sheet.getRow(2).getCell(3).getCellStyle().getDataFormatString());
            assertEquals("dd.mm.yyyy", sheet.getRow(3).getCell(4).getCellStyle().getDataFormatString());
            assertTrue(sheet.getColumnWidth(0) >= 11 * 256, "column fits 'Bölge 5' plus the filter arrow");
            assertTrue(sheet.getColumnWidth(0) < 40 * 256, "the title row does not widen the column");
            assertEquals(FillPatternType.NO_FILL, sheet.getRow(0).getCell(0).getCellStyle().getFillPattern(), "title row untouched");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test void sheetWithoutAHeaderOnlyGetsFormatsAndWidths() throws Exception {
        try (var wb = new XSSFWorkbook()) {
            var sheet = wb.createSheet();
            sheet.createRow(0).createCell(0).setCellValue(1234567.891);
            XlsxTheme.apply(wb, null);
            assertNull(sheet.getPaneInformation());
            assertEquals("#,##0.00", sheet.getRow(0).getCell(0).getCellStyle().getDataFormatString());
        }
    }
}
