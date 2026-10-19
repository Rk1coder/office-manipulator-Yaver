package com.officemanipulator.preview;

import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class PreviewService {

    public PreviewData extract(Job job) throws IOException {
        if (job.getFilePath() == null) return null;
        Path path = Paths.get(job.getFilePath());
        if (!Files.exists(path)) return null;

        return switch (job.getFormat()) {
            case PPTX -> extractPptx(path);
            case DOCX -> extractDocx(path);
            case PDF  -> extractPdf(path);
            case CSV, XLSX -> extractCsv(path, job.getFormat());
            case ODT -> extractOdt(path);
            case ODS -> extractOds(path);
            case ODP -> extractOdp(path);
            case DOC -> extractDoc(path);
            case TXT, MD, JSON, XML -> extractPlainText(path, job.getFormat());
            case ODB -> new PreviewData("unsupported", null, null, List.of("Bu format için önizleme desteklenmiyor."), null);
        };
    }

    private PreviewData extractOdt(Path path) throws IOException {
        try (var doc = org.odftoolkit.simple.TextDocument.loadDocument(Files.newInputStream(path))) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            var it = doc.getParagraphIterator();
            while (it.hasNext()) {
                String text = it.next().getTextContent();
                if (text == null || text.isBlank()) continue;
                blocks.add(Map.of("type", "p", "text", text.trim()));
            }
            return new PreviewData("odt", null, blocks, null, null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid ODT", e);
        }
    }

    private PreviewData extractOds(Path path) throws IOException {
        try (var doc = org.odftoolkit.simple.SpreadsheetDocument.loadDocument(Files.newInputStream(path))) {
            List<Map<String, Object>> sheets = new ArrayList<>();
            for (int s = 0; s < doc.getSheetCount(); s++) {
                var sheet = doc.getSheetByIndex(s);
                int rowCount = Math.min(sheet.getRowCount(), 5000);
                int colCount = Math.min(sheet.getColumnCount(), 500);
                List<List<String>> rows = new ArrayList<>();
                for (int r = 0; r < rowCount; r++) {
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < colCount; c++) cells.add(sheet.getCellByPosition(c, r).getDisplayText());
                    rows.add(cells);
                }
                sheets.add(Map.of("name", sheet.getTableName(), "rows", rows));
            }
            return new PreviewData("ods", null, null, null, sheets.size(), null, sheets, null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid ODS", e);
        }
    }

    private PreviewData extractOdp(Path path) throws IOException {
        try (var doc = org.odftoolkit.simple.PresentationDocument.loadDocument(Files.newInputStream(path))) {
            List<Map<String, Object>> slides = new ArrayList<>();
            for (int i = 0; i < doc.getSlideCount(); i++) {
                var slide = doc.getSlideByIndex(i);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("title", slide.getSlideName() != null ? slide.getSlideName() : "Slide " + (i + 1));
                s.put("bullets", List.of());
                s.put("notes", "");
                slides.add(s);
            }
            return new PreviewData("odp", slides, null, null, null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid ODP", e);
        }
    }

    private PreviewData extractDoc(Path path) throws IOException {
        try (var doc = new org.apache.poi.hwpf.HWPFDocument(Files.newInputStream(path))) {
            String text = doc.getRange().text();
            List<String> lines = Arrays.stream(text == null ? new String[0] : text.split("[\\r\\n\\u0007]+"))
                    .map(String::trim).filter(l -> !l.isBlank()).toList();
            return new PreviewData("doc", null, null, lines, null);
        }
    }

    private PreviewData extractPlainText(Path path, OutputFormat format) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        List<String> lines = Arrays.stream(text.split("\n")).toList();
        return new PreviewData(format.fileExtension(), null, null, lines, null);
    }

    private PreviewData extractPptx(Path path) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(path))) {
            List<Map<String, Object>> slides = new ArrayList<>();
            for (XSLFSlide slide : pptx.getSlides()) {
                String title = null;
                List<String> bullets = new ArrayList<>();
                List<List<String>> tableRows = new ArrayList<>();

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTable table) {
                        for (XSLFTableRow row : table.getRows()) {
                            List<String> cells = new ArrayList<>();
                            for (XSLFTableCell cell : row.getCells()) {
                                cells.add(cell.getText().trim());
                            }
                            if (!cells.isEmpty()) tableRows.add(cells);
                        }
                    } else if (shape instanceof XSLFTextShape ts) {
                        String text = ts.getText().trim();
                        if (text.isEmpty()) continue;
                        if (ts.getShapeName() != null &&
                            (ts.getShapeName().toLowerCase().contains("title") || ts.getShapeName().equals("1"))) {
                            title = text;
                        } else {
                            for (String line : text.split("\n")) {
                                String l = line.trim();
                                if (!l.isEmpty()) bullets.add(l);
                            }
                        }
                    }
                }

                // If title not found by shape name, use first text as title
                if (title == null && !bullets.isEmpty()) {
                    title = bullets.remove(0);
                }

                Map<String, Object> s = new LinkedHashMap<>();
                s.put("title", title != null ? title : "");
                s.put("bullets", bullets);
                if (!tableRows.isEmpty()) s.put("table", tableRows);
                String notes = "";
                if (slide.getNotes() != null) {
                    StringBuilder nb = new StringBuilder();
                    for (XSLFTextShape ts : slide.getNotes().getPlaceholders()) {
                        String t = ts.getText().trim();
                        if (!t.isEmpty() && !t.matches("\\d+")) nb.append(t);
                    }
                    notes = nb.toString().trim();
                }
                s.put("notes", notes);
                slides.add(s);
            }
            return new PreviewData("pptx", slides, null, null, null);
        }
    }

    private PreviewData extractDocx(Path path) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(path))) {
            List<Map<String, Object>> blocks = new ArrayList<>();

            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph para) {
                    String text = para.getText().trim();
                    if (text.isEmpty()) continue;
                    String style = para.getStyle() != null ? para.getStyle().toLowerCase() : "";
                    String type = style.contains("heading1") || style.contains("1") ? "h1"
                                : style.contains("heading2") || style.contains("2") ? "h2"
                                : style.contains("heading3") || style.contains("3") ? "h3"
                                : "p";
                    blocks.add(Map.of("type", type, "text", text));

                } else if (el instanceof XWPFTable table) {
                    List<List<String>> rows = new ArrayList<>();
                    for (XWPFTableRow row : table.getRows()) {
                        List<String> cells = new ArrayList<>();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            cells.add(cell.getText().trim());
                        }
                        rows.add(cells);
                    }
                    if (!rows.isEmpty()) blocks.add(Map.of("type", "table", "rows", rows));
                }
            }
            return new PreviewData("docx", null, blocks, null, null);
        }
    }

    private PreviewData extractPdf(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            int total = doc.getNumberOfPages();
            // Editing uses this representation too: never truncate source content.
            stripper.setStartPage(1);
            stripper.setEndPage(total);
            String text = stripper.getText(doc).trim();
            List<String> lines = Arrays.stream(text.split("\n"))
                    .map(String::trim)
                    .filter(l -> !l.isBlank())
                    .toList();
            return new PreviewData("pdf", null, null, lines, total);
        }
    }

    private PreviewData extractCsv(Path path, OutputFormat fmt) throws IOException {
        if (fmt == OutputFormat.XLSX) {
            try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(path))) {
                List<Map<String, Object>> sheets = new ArrayList<>();
                var formatter = new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);
                for (var sheet : wb) {
                    List<List<String>> rows = new ArrayList<>();
                    int width = 0;
                    for (var row : sheet) width = Math.max(width, row.getLastCellNum());
                    for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                        List<String> cells = new ArrayList<>();
                        var row = sheet.getRow(r);
                        for (int c = 0; c < width; c++) {
                            var cell = row == null ? null : row.getCell(c);
                            cells.add(cell == null ? "" : cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                                    ? "=" + cell.getCellFormula() : formatter.formatCellValue(cell));
                        }
                        rows.add(cells);
                    }
                    sheets.add(Map.of("name", sheet.getSheetName(), "rows", rows));
                }
                return new PreviewData("xlsx", null, null, null, sheets.size(), null, sheets, null);
            }
        }
        String text = CsvSupport.text(path);
        var rows = CsvSupport.rows(text);
        return new PreviewData("csv", null, null, null, Math.max(0, rows.size() - 1), rows,
                null, String.valueOf(CsvSupport.delimiter(text)));
    }
}
