package com.officemanipulator.generator;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineSpacingFixTest {
    @TempDir Path dir;

    @Test void aMultiplePassedAsPointsBecomesAPercentageAndRealPointsStay() throws Exception {
        Path file = dir.resolve("deck.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var slide = deck.createSlide();
            var squashed = slide.createTextBox(); squashed.setText("a\nb"); squashed.getTextParagraphs().getFirst().setLineSpacing(-1.5);
            var points = slide.createTextBox(); points.setText("**c**"); points.getTextParagraphs().getFirst().setLineSpacing(-24.0);
            deck.write(out);
        }

        PresentationGenerator.fixPointLineSpacing(file);

        try (var deck = new XMLSlideShow(Files.newInputStream(file))) {
            var shapes = deck.getSlides().getFirst().getShapes();
            assertEquals(150.0, ((XSLFTextShape) shapes.get(0)).getTextParagraphs().getFirst().getLineSpacing(), 0.01);
            assertEquals(-24.0, ((XSLFTextShape) shapes.get(1)).getTextParagraphs().getFirst().getLineSpacing(), 0.01);
            assertEquals("c", ((XSLFTextShape) shapes.get(1)).getText(), "markdown markers removed");
        }
    }

    @Test void aTypedBulletInABulletedParagraphIsRemovedButADashInPlainTextStays() throws Exception {
        Path file = dir.resolve("bullets.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var slide = deck.createSlide();
            var list = slide.createTextBox(); list.setText("• Yüksek kağıt kullanımı"); list.getTextParagraphs().getFirst().setBullet(true);
            var plain = slide.createTextBox(); plain.setText("- 2025 hedefi");
            deck.write(out);
        }

        PresentationGenerator.fixPointLineSpacing(file);

        try (var deck = new XMLSlideShow(Files.newInputStream(file))) {
            var shapes = deck.getSlides().getFirst().getShapes();
            assertEquals("Yüksek kağıt kullanımı", ((XSLFTextShape) shapes.get(0)).getText());
            assertEquals("- 2025 hedefi", ((XSLFTextShape) shapes.get(1)).getText());
        }
    }
}
