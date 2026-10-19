package com.officemanipulator.service;

import com.officemanipulator.preview.PreviewData;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Edits an already-generated DOCX/PPTX/CSV file in place with POI/Commons-CSV instead of
 * asking Claude to rewrite the whole document. Only safe when the WYSIWYG patch keeps the
 * same structure (same block/slide count) as the original — pure text-value changes.
 * CSV rows and columns can change because every record is represented without formatting.
 * Unsupported edits fail explicitly so callers cannot silently lose document formatting.
 */
@Service
public class DirectPatchService {

    @SuppressWarnings("unchecked")
    public boolean canPatch(PreviewData original, Map<String, Object> patched) {
        Object typeObj = patched.get("type");
        if (!(typeObj instanceof String type) || !type.equals(original.type())) return false;
        try {
            return switch (type) {
                case "docx" -> canPatchDocx(original, (List<Map<String, Object>>) patched.get("blocks"));
                case "pptx" -> canPatchPptx(original, (List<Map<String, Object>>) patched.get("slides"));
                case "csv" -> canPatchCsv(original, (List<List<String>>) patched.get("csvRows"));
                case "xlsx" -> canPatchXlsx(original, (List<Map<String, Object>>) patched.get("sheets"));
                case "pdf" -> canPatchPdf(original, (List<String>) patched.get("lines"));
                default -> false;
            };
        } catch (ClassCastException | NullPointerException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean canPatchDocx(PreviewData original, List<Map<String, Object>> newBlocks) {
        List<Map<String, Object>> oldBlocks = original.blocks();
        if (newBlocks == null || oldBlocks == null || newBlocks.size() != oldBlocks.size()) return false;
        for (int i = 0; i < oldBlocks.size(); i++) {
            String oldType = String.valueOf(oldBlocks.get(i).get("type"));
            String newType = String.valueOf(newBlocks.get(i).get("type"));
            if (!oldType.equals(newType)) return false;
            if (!"table".equals(oldType) && !(newBlocks.get(i).get("text") instanceof String)) return false;
            if ("table".equals(oldType)) {
                List<List<String>> oldRows = (List<List<String>>) oldBlocks.get(i).get("rows");
                List<List<String>> newRows = (List<List<String>>) newBlocks.get(i).get("rows");
                if (!sameShape(oldRows, newRows)) return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean canPatchPptx(PreviewData original, List<Map<String, Object>> newSlides) {
        List<Map<String, Object>> oldSlides = original.slides();
        if (newSlides == null || oldSlides == null || newSlides.size() != oldSlides.size()) return false;
        for (int i = 0; i < oldSlides.size(); i++) {
            if (!(newSlides.get(i).get("title") instanceof String) || !(newSlides.get(i).get("notes") instanceof String)) return false;
            List<String> oldBullets = (List<String>) oldSlides.get(i).get("bullets");
            List<String> newBullets = (List<String>) newSlides.get(i).get("bullets");
            if (newBullets == null || newBullets.stream().anyMatch(v -> !(v instanceof String))) return false;
            int oldCount = oldBullets == null ? 0 : oldBullets.size();
            int newCount = newBullets == null ? 0 : newBullets.size();
            if (oldCount != newCount) return false;
            if ((oldSlides.get(i).get("table") != null) != (newSlides.get(i).get("table") != null)) return false;
            if (oldSlides.get(i).get("table") != null && !sameShape((List<List<String>>) oldSlides.get(i).get("table"),
                    (List<List<String>>) newSlides.get(i).get("table"))) return false;
        }
        return true;
    }

    private boolean canPatchCsv(PreviewData original, List<List<String>> newRows) {
        if (newRows == null || newRows.isEmpty() || newRows.getFirst().isEmpty()) return false;
        int columns = newRows.getFirst().size();
        return newRows.stream().allMatch(row -> row != null && row.size() == columns
                && row.stream().allMatch(v -> v instanceof String));
    }

    @SuppressWarnings("unchecked")
    private boolean canPatchXlsx(PreviewData original, List<Map<String, Object>> sheets) {
        if (sheets == null || original.sheets() == null || sheets.size() != original.sheets().size()) return false;
        for (int i = 0; i < sheets.size(); i++) {
            if (!java.util.Objects.equals(sheets.get(i).get("name"), original.sheets().get(i).get("name"))
                    || !sameShape((List<List<String>>) original.sheets().get(i).get("rows"),
                                  (List<List<String>>) sheets.get(i).get("rows"))) return false;
        }
        return true;
    }

    /** PDF no-op saves are exact copies. Changed PDF text needs explicit regeneration. */
    private boolean canPatchPdf(PreviewData original, List<String> newLines) {
        return java.util.Objects.equals(original.lines(), newLines);
    }

    private boolean sameShape(List<List<String>> a, List<List<String>> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).size() != b.get(i).size() || b.get(i).stream().anyMatch(v -> !(v instanceof String))) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public void applyPatch(Path originalFilePath, Path outputPath, String type, Map<String, Object> patched) throws IOException {
        var sourceJob = new com.officemanipulator.domain.Job();
        sourceJob.setFilePath(originalFilePath.toString());
        sourceJob.setFormat(com.officemanipulator.domain.OutputFormat.valueOf(type.toUpperCase(java.util.Locale.ROOT)));
        PreviewData source = new com.officemanipulator.preview.PreviewService().extract(sourceJob);
        if (!canPatch(source, patched)) throw new IllegalArgumentException("Belge yapısı değiştirilmiş; biçim korunarak kaydedilemiyor.");
        Object oldContent = switch (type) {
            case "docx" -> source.blocks(); case "pptx" -> source.slides();
            case "csv" -> source.csvRows(); case "xlsx" -> source.sheets();
            default -> source.lines();
        };
        String key = switch (type) { case "docx" -> "blocks"; case "pptx" -> "slides";
            case "csv" -> "csvRows"; case "xlsx" -> "sheets"; default -> "lines"; };
        if (java.util.Objects.equals(oldContent, patched.get(key))) {
            Files.createDirectories(outputPath.toAbsolutePath().getParent());
            Files.copy(originalFilePath, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        switch (type) {
            case "docx" -> patchDocx(originalFilePath, outputPath, (List<Map<String, Object>>) patched.get("blocks"));
            case "pptx" -> patchPptx(originalFilePath, outputPath, (List<Map<String, Object>>) patched.get("slides"));
            case "csv" -> patchCsv(originalFilePath, outputPath, (List<List<String>>) patched.get("csvRows"));
            case "xlsx" -> patchXlsx(originalFilePath, outputPath, (List<Map<String, Object>>) patched.get("sheets"));
            default -> throw new IllegalArgumentException("Unsupported type for direct patch: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private void patchDocx(Path originalFilePath, Path outputPath, List<Map<String, Object>> newBlocks) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(originalFilePath))) {
            int blockIdx = 0;
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph para) {
                    if (para.getText().trim().isEmpty()) continue;
                    if (blockIdx >= newBlocks.size()) break;
                    setParagraphText(para, String.valueOf(newBlocks.get(blockIdx).get("text")));
                    blockIdx++;
                } else if (el instanceof XWPFTable table) {
                    if (blockIdx >= newBlocks.size()) break;
                    List<List<String>> newRows = (List<List<String>>) newBlocks.get(blockIdx).get("rows");
                    if (newRows != null) {
                        List<XWPFTableRow> rows = table.getRows();
                        for (int r = 0; r < rows.size() && r < newRows.size(); r++) {
                            List<XWPFTableCell> cells = rows.get(r).getTableCells();
                            List<String> newCells = newRows.get(r);
                            for (int c = 0; c < cells.size() && c < newCells.size(); c++) {
                                setCellText(cells.get(c), newCells.get(c));
                            }
                        }
                    }
                    blockIdx++;
                }
            }
            Files.createDirectories(outputPath.getParent());
            try (OutputStream os = Files.newOutputStream(outputPath)) {
                doc.write(os);
            }
        }
    }

    private void setParagraphText(XWPFParagraph para, String newText) {
        if (para.getText().trim().equals(newText)) return;
        List<XWPFRun> runs = para.getRuns();
        if (newText.contains("\n") || newText.contains("\r") || newText.contains("\t"))
            throw new IllegalArgumentException("Satır sonu veya sekme içeren paragraf değişikliği desteklenmiyor.");
        if (!para.getText().equals(runs.stream().map(XWPFRun::text).collect(java.util.stream.Collectors.joining())))
            throw new IllegalArgumentException("Karmaşık paragraf içeriği biçim korunarak değiştirilemiyor.");
        for (var run : runs) {
            var children = run.getCTR().getDomNode().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                String name = children.item(i).getLocalName();
                if (!"t".equals(name) && !"rPr".equals(name))
                    throw new IllegalArgumentException("Alan, sekme, satır sonu veya nesne içeren paragraf değişikliği desteklenmiyor.");
            }
        }
        if (runs.isEmpty()) {
            para.createRun().setText(newText);
            return;
        }
        var replacements = StyledText.replace(runs.stream().map(XWPFRun::text).toList(), newText);
        for (int i = 0; i < runs.size(); i++) {
            var run = runs.get(i);
            if (run.text().equals(replacements.get(i))) continue;
            // Keep rPr and non-text objects (drawings), replace text nodes only.
            while (run.getCTR().sizeOfTArray() > 0) run.getCTR().removeT(0);
            run.setText(replacements.get(i));
        }
    }

    private void setCellText(XWPFTableCell cell, String newText) {
        if (cell.getText().trim().equals(newText)) return;
        List<XWPFParagraph> paras = cell.getParagraphs();
        if (paras.isEmpty()) {
            cell.setText(newText);
            return;
        }
        if (paras.size() != 1 || !cell.getTables().isEmpty())
            throw new IllegalArgumentException("Çok paragraflı veya iç içe tablo hücresi biçim korunarak değiştirilemiyor.");
        setParagraphText(paras.get(0), newText);
    }

    @SuppressWarnings("unchecked")
    private void patchPptx(Path originalFilePath, Path outputPath, List<Map<String, Object>> newSlides) throws IOException {
        try (XMLSlideShow pptx = new XMLSlideShow(Files.newInputStream(originalFilePath))) {
            List<XSLFSlide> slides = pptx.getSlides();
            for (int i = 0; i < slides.size() && i < newSlides.size(); i++) {
                Map<String, Object> newSlide = newSlides.get(i);
                String newTitle = String.valueOf(newSlide.getOrDefault("title", ""));
                List<String> newBullets = (List<String>) newSlide.get("bullets");
                String newNotes = String.valueOf(newSlide.getOrDefault("notes", ""));

                XSLFSlide slide = slides.get(i);
                List<List<String>> tableRows = (List<List<String>>) newSlide.get("table");
                int tableRowIndex = 0;
                if (tableRows != null) {
                    for (var shape : slide.getShapes()) if (shape instanceof XSLFTable table) {
                        for (var row : table.getRows()) {
                            var values = tableRows.get(tableRowIndex++);
                            for (int c = 0; c < row.getCells().size(); c++) setTextShapeText(row.getCells().get(c), values.get(c));
                        }
                    }
                }

                // Mirror PreviewService.extractPptx: find the (last) explicitly-named title shape,
                // all other non-empty text shapes contribute lines to the flat "bullets" sequence.
                XSLFTextShape titleShape = null;
                List<XSLFTextShape> contentShapes = new java.util.ArrayList<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (!(shape instanceof XSLFTextShape ts)) continue;
                    if (ts.getText().trim().isEmpty()) continue;
                    boolean isTitleShape = ts.getShapeName() != null
                            && (ts.getShapeName().toLowerCase().contains("title") || ts.getShapeName().equals("1"));
                    if (isTitleShape) titleShape = ts;
                    else contentShapes.add(ts);
                }

                // If there's no explicitly-named title shape, extraction falls back to treating the
                // first bullet line as the title — so the title we write back must be re-inserted as
                // the first element of the flat sequence before redistributing it across shapes.
                List<String> toDistribute;
                if (titleShape != null) {
                    setTextShapeText(titleShape, newTitle);
                    toDistribute = newBullets != null ? newBullets : List.of();
                } else {
                    toDistribute = new java.util.ArrayList<>();
                    toDistribute.add(newTitle);
                    if (newBullets != null) toDistribute.addAll(newBullets);
                }

                int consumed = 0;
                for (XSLFTextShape ts : contentShapes) {
                    var oldLines = java.util.Arrays.stream(ts.getText().trim().split("\n"))
                            .map(String::trim).filter(line -> !line.isEmpty()).toList();
                    int end = consumed + oldLines.size();
                    if (end > toDistribute.size()) throw new IllegalArgumentException("Slayt metin eşlemesi değişmiş.");
                    var values = toDistribute.subList(consumed, end);
                    if (!oldLines.equals(values)) setTextShapeText(ts, String.join("\n", values));
                    consumed = end;
                }
                var noteShapes = slide.getNotes() == null ? List.<XSLFTextShape>of() :
                        java.util.Arrays.stream(slide.getNotes().getPlaceholders())
                                .filter(ts -> !ts.getText().trim().isEmpty() && !ts.getText().trim().matches("\\d+"))
                                .toList();
                String oldNotes = noteShapes.stream().map(ts -> ts.getText().trim()).collect(java.util.stream.Collectors.joining()).trim();
                if (!oldNotes.equals(newNotes)) {
                    if (noteShapes.size() != 1)
                        throw new IllegalArgumentException("Not yapısı biçim korunarak değiştirilemiyor.");
                    setTextShapeText(noteShapes.getFirst(), newNotes);
                }
            }
            Files.createDirectories(outputPath.getParent());
            try (OutputStream os = Files.newOutputStream(outputPath)) {
                pptx.write(os);
            }
        }
    }

    private void setTextShapeText(XSLFTextShape shape, String newText) {
        if (shape.getText().trim().equals(newText)) return;
        String[] lines = newText.split("\n", -1);
        var paragraphs = shape.getTextParagraphs();
        if (paragraphs.size() != lines.length) {
            throw new IllegalArgumentException("Bu değişiklik metin paragraf yapısını değiştiriyor; mevcut biçim korunarak uygulanamadı.");
        }
        for (int p = 0; p < lines.length; p++) {
            var runs = paragraphs.get(p).getTextRuns();
            if (runs.isEmpty()) { paragraphs.get(p).addNewTextRun().setText(lines[p]); continue; }
            var replacements = StyledText.replace(runs.stream().map(XSLFTextRun::getRawText).toList(), lines[p]);
            for (int r = 0; r < runs.size(); r++) if (!runs.get(r).getRawText().equals(replacements.get(r))) runs.get(r).setText(replacements.get(r));
        }
    }

    private void patchCsv(Path original, Path outputPath, List<List<String>> rows) throws IOException {
        Files.createDirectories(outputPath.getParent());
        String source = Files.readString(original, StandardCharsets.UTF_8);
        try (Writer w = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            if (source.startsWith("\uFEFF")) w.write("\uFEFF");
            try (CSVPrinter printer = new CSVPrinter(w, CSVFormat.EXCEL.builder()
                    .setRecordSeparator(source.contains("\r\n") ? "\r\n" : "\n")
                    .setDelimiter(com.officemanipulator.preview.CsvSupport.delimiter(source)).build())) {
                for (List<String> row : rows) printer.printRecord(row);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void patchXlsx(Path original, Path output, List<Map<String, Object>> sheets) throws IOException {
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(original))) {
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            for (int i = 0; i < sheets.size(); i++) {
                var sheet = wb.getSheetAt(i); var rows = (List<List<String>>) sheets.get(i).get("rows");
                for (int r = 0; r < rows.size(); r++) for (int c = 0; c < rows.get(r).size(); c++) {
                    var row = sheet.getRow(r); var cell = row == null ? null : row.getCell(c);
                    String old = cell == null ? "" : cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                            ? "=" + cell.getCellFormula() : formatter.formatCellValue(cell);
                    String value = rows.get(r).get(c);
                    if (old.equals(value)) continue;
                    if (cell != null && cell.isPartOfArrayFormulaGroup())
                        throw new IllegalArgumentException("Dizi formülünün tek hücresi değiştirilemez.");
                    if (row == null) row = sheet.createRow(r);
                    if (cell == null) cell = row.createCell(c);
                    if (value.startsWith("=")) cell.setCellFormula(value.substring(1));
                    else if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC && value.matches("-?\\d+(\\.\\d+)?")) cell.setCellValue(Double.parseDouble(value));
                    else { cell.setBlank(); cell.setCellValue(value); }
                }
            }
            wb.setForceFormulaRecalculation(true);
            Files.createDirectories(output.getParent());
            try (var os = Files.newOutputStream(output)) { wb.write(os); }
        }
    }

}
