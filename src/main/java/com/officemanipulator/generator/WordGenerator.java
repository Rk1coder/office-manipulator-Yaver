package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreateWordParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Component
public class WordGenerator {

    private final NodeScriptExecutor executor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.officemanipulator.config.BrandThemeService brand;

    public WordGenerator(NodeScriptExecutor executor) {
        this.executor = executor;
    }

    public void generate(CreateWordParams params, Path outputPath) throws IOException, InterruptedException {
        generate(params, outputPath, Map.of());
    }

    public void generate(CreateWordParams params, Path outputPath, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        executor.execute(params.getScript(), "gen_docx.js", outputPath, extraEnv);
        // Cosmetic clean-ups: a document they cannot parse still ships as the script wrote it.
        try {
            removeDoubledText(outputPath);
            fillClearShading(outputPath);
            shrinkHeaderPictures(outputPath);
            var theme = brand == null ? null : brand.current();
            if (theme != null && (theme.headingFont() != null || theme.bodyFont() != null))
                applyFonts(outputPath, theme.headingFont(), theme.bodyFont());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(WordGenerator.class).warn("DOCX clean-up skipped: {}", e.toString());
        }
    }

    private static final long MAX_HEADER_PICTURE_EMU = 548_640; // 0.6in

    /** A header logo sized in pixels-as-EMU or inches-as-pixels fills the page on every page (seen: 16.5in wide).
     * Pictures in headers and footers are scaled down, keeping their ratio, to at most 0.6in high. */
    static void shrinkHeaderPictures(Path docx) throws IOException {
        boolean changed = false;
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(java.nio.file.Files.newInputStream(docx))) {
            var parts = new java.util.ArrayList<org.apache.poi.xwpf.usermodel.XWPFHeaderFooter>(doc.getHeaderList());
            parts.addAll(doc.getFooterList());
            for (var part : parts)
                for (var drawing : part._getHdrFtr().selectPath(
                        "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' .//w:drawing")) {
                    // wp:extent sets the layout size, a:ext the picture's own; both are CTPositiveSize2D.
                    var sizes = new java.util.ArrayList<org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D>();
                    for (String path : new String[] {
                            "declare namespace wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing' .//wp:extent",
                            "declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' .//a:ext"})
                        for (var size : drawing.selectPath(path))
                            if (size instanceof org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D s) sizes.add(s);
                    long height = sizes.stream().mapToLong(org.openxmlformats.schemas.drawingml.x2006.main.CTPositiveSize2D::getCy).max().orElse(0);
                    if (height <= MAX_HEADER_PICTURE_EMU) continue;
                    double scale = (double) MAX_HEADER_PICTURE_EMU / height;
                    for (var s : sizes) {
                        s.setCx(Math.round(s.getCx() * scale));
                        s.setCy(Math.round(s.getCy() * scale));
                    }
                    changed = true;
                }
            if (changed) try (var out = java.nio.file.Files.newOutputStream(docx)) { doc.write(out); }
        }
    }

    /** shading: { type: CLEAR, color } shows nothing — CLEAR draws only "fill" — so white header text
     * disappears on white. The color moves to the fill only when the shaded cell/paragraph holds light text:
     * then a dark background was clearly meant (with dark text the fill would hide it instead). */
    static void fillClearShading(Path docx) throws IOException {
        boolean changed = false;
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(java.nio.file.Files.newInputStream(docx))) {
            for (var object : doc.getDocument().selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' .//w:shd")) {
                // w:shd where the schema has none (e.g. under w:trPr) parses as an untyped element: skip it.
                if (!(object instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd)) continue;
                Object color = shd.getColor();
                boolean clear = shd.getVal() == null || shd.getVal() == org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR;
                if (clear && !shd.isSetFill() && color != null && !"auto".equals(color.toString()) && onlyLightText(shd)) {
                    shd.setFill(color);
                    changed = true;
                }
            }
            if (changed) try (var out = java.nio.file.Files.newOutputStream(docx)) { doc.write(out); }
        }
    }

    private static boolean onlyLightText(org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd) {
        try (var cursor = shd.newCursor()) {
            cursor.toParent(); // tcPr / pPr
            cursor.toParent(); // tc / p
            // Simple paths only: xmlbeans needs Saxon for predicates and attribute steps.
            var runs = cursor.getObject().selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' .//w:r");
            boolean any = false;
            for (var object : runs) {
                if (!(object instanceof org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR run) || run.sizeOfTArray() == 0) continue;
                if (!run.isSetRPr() || run.getRPr().sizeOfColorArray() == 0) return false;
                String hex = run.getRPr().getColorArray(0).xgetVal().getStringValue(); // getVal() is byte[]
                if (!hex.matches("[0-9A-Fa-f]{6}")) return false;
                int rgb = Integer.parseInt(hex, 16);
                if (0.299 * (rgb >> 16) + 0.587 * (rgb >> 8 & 0xFF) + 0.114 * (rgb & 0xFF) <= 215) return false;
                any = true;
            }
            if (!any) return false;
            return true;
        }
    }

    /** Paragraph({ text, children }) writes the text twice: a plain run, then the styled one. The plain
     * copy is dropped when a paragraph is exactly that pair. Literal markdown "**" markers are removed too. */
    static void removeDoubledText(Path docx) throws IOException {
        boolean changed = false;
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(java.nio.file.Files.newInputStream(docx))) {
            var paragraphs = new java.util.ArrayList<>(doc.getParagraphs());
            for (var table : doc.getTables())
                for (var row : table.getRows())
                    for (var cell : row.getTableCells()) paragraphs.addAll(cell.getParagraphs());
            for (var paragraph : paragraphs) {
                // Markdown bold markers ("**VPN**") written as literal text are never wanted in Word.
                for (var run : paragraph.getRuns())
                    for (var text : run.getCTR().getTList())
                        if (text.getStringValue() != null && text.getStringValue().contains("**")) {
                            text.setStringValue(text.getStringValue().replace("**", ""));
                            changed = true;
                        }
                var runs = paragraph.getRuns();
                if (runs.size() != 2) continue;
                String first = runs.get(0).text(), second = runs.get(1).text();
                if (!first.isBlank() && first.equals(second) && !runs.get(0).getCTR().isSetRPr()) {
                    paragraph.removeRun(0);
                    changed = true;
                }
            }
            if (changed) try (var out = java.nio.file.Files.newOutputStream(docx)) { doc.write(out); }
        }
    }

    /** Text the script left without a font falls back to the corporate one instead of Times New Roman:
     * the body font becomes the document default, the heading font goes to the Title and Heading styles. */
    static void applyFonts(Path docx, String headingFont, String bodyFont) throws IOException {
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(java.nio.file.Files.newInputStream(docx))) {
            var styles = doc.getStyles() == null ? doc.createStyles() : doc.getStyles();
            if (bodyFont != null) styles.setDefaultFonts(fonts(bodyFont));
            if (headingFont != null) {
                for (String id : java.util.List.of("Title", "Heading1", "Heading2", "Heading3", "Heading4", "Heading5", "Heading6")) {
                    var style = styles.getStyle(id);
                    if (style == null) continue;
                    var ct = style.getCTStyle();
                    var rPr = ct.isSetRPr() ? ct.getRPr() : ct.addNewRPr();
                    var target = rPr.sizeOfRFontsArray() > 0 ? rPr.getRFontsArray(0) : rPr.addNewRFonts();
                    target.set(fonts(headingFont));
                }
            }
            try (var out = java.nio.file.Files.newOutputStream(docx)) { doc.write(out); }
        }
    }

    private static org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts fonts(String name) {
        var fonts = org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts.Factory.newInstance();
        fonts.setAscii(name); fonts.setHAnsi(name); fonts.setCs(name); fonts.setEastAsia(name);
        return fonts;
    }
}
