package com.officemanipulator.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.preview.CsvSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileValidator {
    @Value("${app.limits.max-output-bytes:52428800}")
    private long maxOutputBytes = 52_428_800;
    @Value("${app.limits.max-word-sections:30}")
    private int maxWordSections = 30;
    @Value("${app.limits.max-ppt-slides:40}")
    private int maxPptSlides = 40;
    @Value("${app.limits.max-pdf-blocks:200}")
    private int maxPdfBlocks = 200;
    @Value("${app.limits.max-csv-rows:5000}")
    private int maxCsvRows = 5000;
    private final ObjectMapper json;

    @Autowired
    public FileValidator(ObjectMapper json) {
        this.json = json;
    }

    public FileValidator() {
        this(new ObjectMapper());
    }

    public void validate(Path path, OutputFormat format) throws ValidationException {
        if (!Files.isRegularFile(path)) throw new ValidationException("File was not created at: " + path);
        try {
            long size = Files.size(path);
            if (size == 0) throw new ValidationException("File is empty");
            limit(size, maxOutputBytes, "Output bytes");
            switch (format) {
                case DOCX -> validateDocx(path);
                case PPTX -> validatePptx(path);
                case PDF -> validatePdf(path);
                case CSV -> validateCsv(path);
                case XLSX -> validateXlsx(path);
                case ODT -> DocumentSignatures.requireNonBlankOdt(Files.readAllBytes(path));
                case ODS -> DocumentSignatures.requireNonBlankOds(Files.readAllBytes(path));
                case ODP -> DocumentSignatures.requireNonBlankOdp(Files.readAllBytes(path));
                case DOC -> DocumentSignatures.requireNonBlankDoc(Files.readAllBytes(path));
                case TXT, MD -> DocumentSignatures.requireNonBlankText(Files.readAllBytes(path));
                case JSON -> DocumentSignatures.requireValidJson(Files.readAllBytes(path), json);
                case XML -> DocumentSignatures.requireWellFormedXml(Files.readAllBytes(path));
                case ODB -> DocumentSignatures.requireOdfZipSignature(Files.readAllBytes(path), "application/vnd.oasis.opendocument.database");
            }
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ValidationException("Validation failed: " + e.getMessage(), e);
        }
    }

    private void limit(long actual, long maximum, String unit) {
        if (actual > maximum) throw new ValidationException(unit + " limit exceeded: " + actual + " > " + maximum);
    }

    private void validateDocx(Path path) throws IOException {
        try (var doc = new XWPFDocument(Files.newInputStream(path))) {
            boolean text = doc.getParagraphs().stream().anyMatch(p -> !p.getText().isBlank())
                    || doc.getTables().stream().anyMatch(t -> !t.getText().isBlank());
            if (!text && doc.getAllPictures().isEmpty()) throw new ValidationException("DOCX has no meaningful content");
            // Script output has no application section metadata. Count Word section breaks and
            // top-level heading boundaries, whichever describes more sections.
            long headings = doc.getParagraphs().stream().filter(p -> "heading1".equalsIgnoreCase(p.getStyle())).count();
            long sections = 1 + doc.getParagraphs().stream().filter(p -> p.getCTP().isSetPPr() && p.getCTP().getPPr().isSetSectPr()).count();
            limit(Math.max(headings, sections), maxWordSections, "DOCX sections");
        }
    }

    private boolean meaningful(XSLFShape shape) {
        if (shape instanceof XSLFTextShape text) return !text.getText().isBlank();
        if (shape instanceof XSLFPictureShape) return true;
        if (shape instanceof XSLFTable table) return table.getRows().stream()
                .flatMap(r -> r.getCells().stream()).anyMatch(c -> !c.getText().isBlank());
        if (shape instanceof XSLFGroupShape group) return group.getShapes().stream().anyMatch(this::meaningful);
        return shape instanceof XSLFGraphicFrame;
    }

    private void validatePptx(Path path) throws IOException {
        try (var pptx = new XMLSlideShow(Files.newInputStream(path))) {
            limit(pptx.getSlides().size(), maxPptSlides, "PPTX slides");
            if (pptx.getSlides().stream().flatMap(s -> s.getShapes().stream()).noneMatch(this::meaningful))
                throw new ValidationException("PPTX has no meaningful content");
        }
    }

    private void validatePdf(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            if (doc.getNumberOfPages() == 0) throw new ValidationException("PDF has no pages");
            // PDF has no semantic block tree; enforce the advertised bound on extracted nonblank
            // lines and page image resources, and on pages to bound blank-page output as well.
            limit(doc.getNumberOfPages(), maxPdfBlocks, "PDF pages");
            long blocks = new PDFTextStripper().getText(doc).lines().filter(s -> !s.isBlank()).count();
            for (var page : doc.getPages()) if (page.getResources() != null) {
                for (var name : page.getResources().getXObjectNames())
                    if (page.getResources().isImageXObject(name)) blocks++;
            }
            if (blocks == 0) throw new ValidationException("PDF has no meaningful text or images");
            limit(blocks, maxPdfBlocks, "PDF text lines and images");
        }
    }

    private void validateCsv(Path path) throws IOException {
        var rows = CsvSupport.rows(CsvSupport.text(path));
        if (rows.isEmpty() || rows.getFirst().isEmpty()) throw new ValidationException("CSV has no columns");
        int columns = rows.getFirst().size();
        limit(rows.size() - 1L, maxCsvRows, "CSV data rows");
        boolean meaningful = false;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).size() != columns)
                throw new ValidationException("CSV has inconsistent column count on row " + (i + 1));
            meaningful |= rows.get(i).stream().anyMatch(s -> !s.isBlank());
        }
        if (!meaningful) throw new ValidationException("CSV has no data rows with content");
    }

    private void validateXlsx(Path path) throws IOException {
        try (var wb = new XSSFWorkbook(Files.newInputStream(path))) {
            if (wb.getNumberOfSheets() == 0) throw new ValidationException("XLSX has no sheets");
            boolean meaningful = false;
            var formatter = new org.apache.poi.ss.usermodel.DataFormatter(java.util.Locale.ROOT);
            for (var sheet : wb) {
                limit(Math.max(0, sheet.getLastRowNum()), maxCsvRows, "XLSX data rows in " + sheet.getSheetName());
                for (var row : sheet) for (var cell : row) meaningful |= !formatter.formatCellValue(cell).isBlank();
            }
            if (!meaningful) throw new ValidationException("XLSX has no meaningful cells");
        }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
        public ValidationException(String message, Throwable cause) { super(message, cause); }
    }
}
