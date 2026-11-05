package com.officemanipulator.office;

import com.officemanipulator.domain.OutputFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders an in-progress PPTX to one JPEG per slide (ONLYOFFICE → PDF → PDFBox), so the visual
 * review step can show a model what the deck really looks like instead of only its script.
 */
@Service
@ConditionalOnProperty(name = "app.office.enabled", havingValue = "true")
public class SlideRenderer {
    // 72 DPI on a 13.33" × 7.5" slide = 960 × 540: legible for review, cheap in image tokens.
    private static final float DPI = 72f;
    private final DocumentConversionService conversion;

    public SlideRenderer(DocumentConversionService conversion) {
        this.conversion = conversion;
    }

    public List<byte[]> renderPptx(Path pptx, int maxSlides) throws IOException {
        Path pdf = Files.createTempFile(pptx.toAbsolutePath().getParent(), "review-", ".pdf");
        try {
            conversion.convert(pptx, pdf, OutputFormat.PPTX, OutputFormat.PDF);
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                PDFRenderer renderer = new PDFRenderer(document);
                List<byte[]> slides = new ArrayList<>();
                for (int i = 0; i < Math.min(document.getNumberOfPages(), maxSlides); i++) {
                    var out = new ByteArrayOutputStream();
                    ImageIO.write(renderer.renderImageWithDPI(i, DPI, ImageType.RGB), "jpg", out);
                    slides.add(out.toByteArray());
                }
                return slides;
            }
        } finally {
            Files.deleteIfExists(pdf);
        }
    }
}
