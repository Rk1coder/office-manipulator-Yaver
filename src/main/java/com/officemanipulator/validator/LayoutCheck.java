package com.officemanipulator.validator;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Layout defects that can be measured instead of judged: text boxes stacked on each other in a deck,
 * and PDF text running off the page. Each finding is one line the generator can act on.
 */
public final class LayoutCheck {
    private static final int MAX_FINDINGS = 12;

    private LayoutCheck() {}

    /** Text boxes overlapping each other by more than half of the smaller one, and pictures (the logo included) drawn on top of text. */
    public static List<String> overlappingText(Path pptx) throws IOException {
        var findings = new ArrayList<String>();
        try (var deck = new XMLSlideShow(Files.newInputStream(pptx))) {
            var slides = deck.getSlides();
            for (int s = 0; s < slides.size() && findings.size() < MAX_FINDINGS; s++) {
                var boxes = slides.get(s).getShapes().stream()
                        .filter(shape -> shape instanceof XSLFTextShape text && !text.getText().isBlank())
                        .map(shape -> (XSLFTextShape) shape).toList();
                // A picture later in drawing order than a text box is on top of it. pptxgenjs stores an image's
                // source path as its description, which is how the logo is told apart (any touch counts for it).
                var shapes = slides.get(s).getShapes();
                for (int t = 0; t < shapes.size(); t++) {
                    if (!(shapes.get(t) instanceof XSLFTextShape box) || box.getText().isBlank()) continue;
                    Rectangle2D text = box.getAnchor();
                    for (int p = t + 1; p < shapes.size(); p++) {
                        if (!(shapes.get(p) instanceof org.apache.poi.xslf.usermodel.XSLFPictureShape picture)) continue;
                        Rectangle2D covered = text.createIntersection(picture.getAnchor());
                        if (covered.isEmpty()) continue;
                        boolean logo = String.valueOf(((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) picture.getXmlObject())
                                .getNvPicPr().getCNvPr().getDescr()).contains("IMG_LOGO");
                        if (logo) findings.add("slide " + (s + 1) + ": text box \"" + snippet(box.getText())
                                + "\" runs under the logo; end it left of the logo (x + w below " + Math.round(picture.getAnchor().getX() / 72 * 100) / 100.0 + "in)");
                        else if (covered.getWidth() * covered.getHeight() > 0.15 * text.getWidth() * text.getHeight())
                            findings.add("slide " + (s + 1) + ": a picture is drawn on top of the text \"" + snippet(box.getText())
                                    + "\" and hides it; move the picture beside the text, or add it before the text so it sits behind");
                    }
                }
                for (int i = 0; i < boxes.size(); i++) {
                    for (int j = i + 1; j < boxes.size(); j++) {
                        Rectangle2D a = boxes.get(i).getAnchor(), b = boxes.get(j).getAnchor();
                        Rectangle2D overlap = a.createIntersection(b);
                        double smaller = Math.min(a.getWidth() * a.getHeight(), b.getWidth() * b.getHeight());
                        if (overlap.isEmpty() || smaller <= 0 || overlap.getWidth() * overlap.getHeight() < smaller / 2) continue;
                        findings.add("slide " + (s + 1) + ": text boxes \"" + snippet(boxes.get(i).getText()) + "\" and \""
                                + snippet(boxes.get(j).getText()) + "\" are drawn on top of each other");
                    }
                }
            }
        }
        return findings.size() > MAX_FINDINGS ? findings.subList(0, MAX_FINDINGS) : findings;
    }

    /** Light text on a light slide with no filled shape or picture behind it: unreadable, e.g. a cover whose
     * dark background the script forgot. */
    public static List<String> invisibleText(Path pptx) throws IOException {
        var findings = new ArrayList<String>();
        try (var deck = new XMLSlideShow(Files.newInputStream(pptx))) {
            var slides = deck.getSlides();
            for (int s = 0; s < slides.size() && findings.size() < MAX_FINDINGS; s++) {
                var background = slides.get(s).getBackground() == null ? null : slides.get(s).getBackground().getFillColor();
                if (background != null && !light(background)) continue;
                var shapes = slides.get(s).getShapes();
                for (int i = 0; i < shapes.size(); i++) {
                    if (!(shapes.get(i) instanceof XSLFTextShape box) || box.getText().isBlank() || !allTextLight(box)) continue;
                    if (box.getFillColor() != null && !light(box.getFillColor())) continue;
                    double x = box.getAnchor().getCenterX(), y = box.getAnchor().getCenterY();
                    boolean backed = false;
                    for (int j = 0; j < i && !backed; j++) {
                        var under = shapes.get(j);
                        if (!under.getAnchor().contains(x, y)) continue;
                        backed = under instanceof org.apache.poi.xslf.usermodel.XSLFPictureShape
                                || under instanceof org.apache.poi.xslf.usermodel.XSLFSimpleShape filled
                                && filled.getFillColor() != null && !light(filled.getFillColor());
                    }
                    if (!backed) findings.add("slide " + (s + 1) + ": light text \"" + snippet(box.getText())
                            + "\" sits on a light background and cannot be read — give the slide or a shape behind it a dark fill, or use dark text");
                }
            }
        }
        return findings;
    }

    private static boolean allTextLight(XSLFTextShape box) {
        boolean any = false;
        for (var paragraph : box.getTextParagraphs())
            for (var run : paragraph.getTextRuns()) {
                if (run.getRawText() == null || run.getRawText().isBlank()) continue;
                if (!(run.getFontColor() instanceof org.apache.poi.sl.usermodel.PaintStyle.SolidPaint paint)) return false;
                if (!light(paint.getSolidColor().getColor())) return false;
                any = true;
            }
        return any;
    }

    private static boolean light(java.awt.Color color) {
        return 0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue() > 215;
    }

    /** Lines of PDF text that start or end outside the page. */
    public static List<String> textOffPage(Path pdf) throws IOException {
        var findings = new ArrayList<String>();
        try (var document = Loader.loadPDF(pdf.toFile())) {
            // Glyph level: the stripper's own text output drops characters outside the page.
            var stripper = new PDFTextStripper() {
                private final java.util.Map<Integer, StringBuilder> lines = new java.util.TreeMap<>();
                private final java.util.Map<Integer, String> offEdge = new java.util.TreeMap<>();

                @Override
                protected void processTextPosition(TextPosition glyph) {
                    float width = getCurrentPage().getMediaBox().getWidth();
                    int line = Math.round(glyph.getYDirAdj());
                    lines.computeIfAbsent(line, y -> new StringBuilder()).append(glyph.getUnicode());
                    if (glyph.getXDirAdj() < -1) offEdge.putIfAbsent(line, "left");
                    else if (glyph.getXDirAdj() + glyph.getWidthDirAdj() > width + 1) offEdge.putIfAbsent(line, "right");
                    super.processTextPosition(glyph);
                }

                @Override
                protected void endPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
                    offEdge.forEach((line, side) -> {
                        if (findings.size() < MAX_FINDINGS) findings.add("page " + getCurrentPageNo() + ": \"" + snippet(lines.get(line).toString())
                                + "\" runs past the " + side + " edge of the page");
                    });
                    lines.clear();
                    offEdge.clear();
                    super.endPage(page);
                }
            };
            stripper.setSortByPosition(true);
            stripper.getText(document);
        }
        return findings;
    }

    /** Formulas that evaluate to an error (#DIV/0!, #REF!…) and a header row shifted against its data
     * (a column of data with no title while a title has no data below it). */
    public static List<String> spreadsheetDefects(Path xlsx) throws IOException {
        var findings = new ArrayList<String>();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(xlsx))) {
            for (var sheet : wb) {
                var errors = new ArrayList<String>();
                for (var row : sheet)
                    for (var cell : row)
                        if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                                && cell.getCachedFormulaResultType() == org.apache.poi.ss.usermodel.CellType.ERROR)
                            errors.add(cell.getAddress() + " =" + cell.getCellFormula() + " gives "
                                    + org.apache.poi.ss.usermodel.FormulaError.forInt(cell.getErrorCellValue()).getString());
                if (!errors.isEmpty()) findings.add("sheet \"" + sheet.getSheetName() + "\": formulas evaluate to errors: "
                        + String.join("; ", errors.subList(0, Math.min(5, errors.size())))
                        + (errors.size() > 5 ? " and " + (errors.size() - 5) + " more" : "")
                        + " — make every formula reference cells that hold values (check the row of the total)");
                int header = com.officemanipulator.generator.XlsxTheme.headerRow(sheet);
                if (header < 0) continue;
                var titles = new java.util.TreeMap<Integer, String>();
                for (var cell : sheet.getRow(header))
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && !cell.getStringCellValue().isBlank())
                        titles.put(cell.getColumnIndex(), cell.getStringCellValue().strip());
                var filled = new java.util.TreeSet<Integer>();
                for (int r = header + 1; r <= sheet.getLastRowNum() && !com.officemanipulator.generator.XlsxTheme.blank(sheet.getRow(r)); r++)
                    for (var cell : sheet.getRow(r))
                        if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.BLANK
                                && !(cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING && cell.getStringCellValue().isBlank()))
                            filled.add(cell.getColumnIndex());
                var untitled = filled.stream().filter(c -> !titles.containsKey(c)).map(org.apache.poi.ss.util.CellReference::convertNumToColString).toList();
                var empty = titles.entrySet().stream().filter(e -> !filled.contains(e.getKey())).map(e -> "\"" + e.getValue() + "\"").toList();
                // Both together mean a shift; either alone is a notes column or an unused title.
                if (!untitled.isEmpty() && !empty.isEmpty()) findings.add("sheet \"" + sheet.getSheetName() + "\" row " + (header + 1)
                        + ": the header row is shifted against the data — column(s) " + String.join(", ", untitled)
                        + " hold data with no title above, while " + String.join(", ", empty)
                        + " has no data below; write each title in the same column as its values");
            }
        }
        return findings.size() > MAX_FINDINGS ? findings.subList(0, MAX_FINDINGS) : findings;
    }

    private static String snippet(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }
}
