package com.officemanipulator.office;

import com.officemanipulator.domain.OutputFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a generated document to one JPEG per page/slide, so the visual review step can show a
 * model what it really looks like instead of only its script. PDFs render directly with PDFBox;
 * PPTX and DOCX go through ONLYOFFICE (PDF) first and are reviewable only when it is enabled.
 */
@Service
public class SlideRenderer {
    // 72 DPI on a 13.33" × 7.5" slide = 960 × 540: legible for review, cheap in image tokens.
    private static final float SLIDE_DPI = 72f;
    // A4 at 90 DPI = 744 × 1052: body text stays readable.
    private static final float PAGE_DPI = 90f;
    private final DocumentConversionService conversion;

    public SlideRenderer(ObjectProvider<DocumentConversionService> conversion) {
        this.conversion = conversion.getIfAvailable();
    }

    public boolean canRender(OutputFormat format) {
        return format == OutputFormat.PDF || conversion != null && (format == OutputFormat.PPTX || format == OutputFormat.DOCX);
    }

    public List<byte[]> renderPptx(Path pptx, int maxSlides) throws IOException {
        return render(pptx, OutputFormat.PPTX, maxSlides);
    }

    public List<byte[]> render(Path file, OutputFormat format, int maxPages) throws IOException {
        if (!canRender(format)) throw new IOException(format + " cannot be rendered here");
        if (format == OutputFormat.PDF) return pages(file, maxPages, PAGE_DPI);
        Path pdf = Files.createTempFile(file.toAbsolutePath().getParent(), "review-", ".pdf");
        try {
            conversion.convert(file, pdf, format, OutputFormat.PDF);
            return pages(pdf, maxPages, format == OutputFormat.PPTX ? SLIDE_DPI : PAGE_DPI);
        } finally {
            Files.deleteIfExists(pdf);
        }
    }

    private static List<byte[]> pages(Path pdf, int maxPages, float dpi) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<byte[]> pages = new ArrayList<>();
            for (int i = 0; i < Math.min(document.getNumberOfPages(), maxPages); i++) {
                var out = new ByteArrayOutputStream();
                ImageIO.write(renderer.renderImageWithDPI(i, dpi, ImageType.RGB), "jpg", out);
                pages.add(out.toByteArray());
            }
            return pages;
        }
    }
}
