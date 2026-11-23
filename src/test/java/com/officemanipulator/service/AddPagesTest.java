package com.officemanipulator.service;

import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddPagesTest {
    @TempDir Path dir;
    private final DirectPatchService service = new DirectPatchService();

    @Test void pptxGetsSlidesCopiedFromAContentSlide() throws Exception {
        Path file = dir.resolve("deck.pptx");
        try (var pptx = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file)) {
            var master = pptx.getSlideMasters().getFirst();
            var cover = pptx.createSlide(master.getLayout(SlideLayout.TITLE));
            cover.getPlaceholder(0).setText("Böcekler");
            var content = pptx.createSlide(master.getLayout(SlideLayout.TITLE_AND_CONTENT));
            content.getPlaceholder(0).setText("Türler");
            content.getPlaceholder(1).setText("Arılar\nKarıncalar");
            pptx.createSlide(master.getLayout(SlideLayout.TITLE_ONLY)).getPlaceholder(0).setText("Sonra");
            pptx.write(out);
        }

        service.addPages(file, "pptx", List.of(
                new DirectPatchService.NewPage(0, "Özet", List.of("Bir", "İki", "Üç")),
                new DirectPatchService.NewPage(1, "Ara1", List.of("x")),
                new DirectPatchService.NewPage(1, "Ara2", List.of("y")),
                new DirectPatchService.NewPage(-1, "Sonuç", List.of("Son"))));

        try (var pptx = new XMLSlideShow(Files.newInputStream(file))) {
            var slides = pptx.getSlides();
            assertEquals(List.of("Böcekler", "Özet", "Türler", "Ara1", "Ara2", "Sonra", "Sonuç"),
                    slides.stream().map(slide -> slide.getPlaceholder(0).getText()).toList());
            assertEquals("Bir\nİki\nÜç", slides.get(1).getPlaceholder(1).getText());
            assertEquals("Arılar\nKarıncalar", slides.get(2).getPlaceholder(1).getText(), "original slide keeps its text");
            assertEquals(slides.get(2).getSlideLayout(), slides.get(1).getSlideLayout(), "new slide uses the content slide's layout");
        }
    }

    @Test void generatedDecksWithPlainTextBoxesUseTheFirstBoxAsTitle() throws Exception {
        Path file = dir.resolve("generated.pptx");
        try (var pptx = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file)) {
            for (String title : List.of("Kapak", "Türler")) {
                var slide = pptx.createSlide();
                var head = slide.createTextBox(); head.setAnchor(new java.awt.Rectangle(40, 20, 600, 60)); head.setText(title);
                var body = slide.createTextBox(); body.setAnchor(new java.awt.Rectangle(340, 100, 300, 300)); body.setText("Arılar\nKarıncalar");
                slide.createPicture(pptx.addPicture(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, org.apache.poi.sl.usermodel.PictureData.PictureType.PNG));
            }
            pptx.write(out);
        }

        service.addPages(file, "pptx", List.of(new DirectPatchService.NewPage(-1, "Özet", List.of("Bir", "İki"))));

        try (var pptx = new XMLSlideShow(Files.newInputStream(file))) {
            var added = pptx.getSlides().get(2);
            var texts = added.getShapes().stream().filter(s -> s instanceof XSLFTextShape).map(s -> ((XSLFTextShape) s).getText()).toList();
            assertEquals(List.of("Özet", "Bir\nİki"), texts);
            assertTrue(added.getShapes().stream().noneMatch(s -> s instanceof XSLFPictureShape), "content pictures are not copied");
            var shapes = added.getShapes().stream().filter(s -> s instanceof XSLFTextShape).toList();
            assertEquals(shapes.get(0).getAnchor().getWidth(), shapes.get(1).getAnchor().getWidth(), 0.5, "text takes the freed picture space");
        }
    }

    @Test void docxGetsSectionsStyledLikeItsOwnHeadingAndList() throws Exception {
        Path file = dir.resolve("doc.docx");
        try (var doc = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            doc.createParagraph().createRun().setText("Rapor");
            var heading = doc.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("Giriş");
            var item = doc.createParagraph(); item.setNumID(BigInteger.ONE); item.createRun().setText("Madde");
            doc.getDocument().getBody().addNewSectPr();
            doc.write(out);
        }

        service.addPages(file, "docx", List.of(
                new DirectPatchService.NewPage(0, "Özet", List.of("Kısa")),
                new DirectPatchService.NewPage(1, "Ara", List.of("x")),
                new DirectPatchService.NewPage(-1, "Sonuç", List.of("Bitti"))));

        try (var doc = new XWPFDocument(Files.newInputStream(file))) {
            var texts = doc.getParagraphs().stream().map(p -> p.getText().strip()).toList();
            // "" = page break: none before a page right after the title, none after the last page.
            assertEquals(List.of("Rapor", "Özet", "Kısa", "", "Giriş", "", "Ara", "x", "", "Madde", "", "Sonuç", "Bitti"), texts);
            var paragraphs = doc.getParagraphs();
            assertEquals("Heading1", paragraphs.get(1).getStyleID());
            assertEquals(BigInteger.ONE, paragraphs.get(2).getNumID(), "bullets copy the list item's numbering");
            assertEquals("Heading1", paragraphs.get(11).getStyleID());
            assertTrue(doc.getDocument().getBody().isSetSectPr(), "section properties stay last");
        }
    }

    @Test void unsupportedTypesAreRefusedSoTheCallerCanFallBack() {
        assertThrows(IllegalArgumentException.class,
                () -> service.addPages(dir.resolve("x.xlsx"), "xlsx", List.of(new DirectPatchService.NewPage(-1, "S", List.of()))));
    }

    @Test void afterIdsNameTheSlideOrBlockAndBlankMeansTheEnd() {
        assertEquals(2, DocumentEditService.pageIndex("slides/2/title"));
        assertEquals(5, DocumentEditService.pageIndex("[blocks/5/text]"));
        assertEquals(-1, DocumentEditService.pageIndex(""));
        assertEquals(2, DocumentEditService.pageIndex("slide 3"));
        assertEquals(0, DocumentEditService.pageIndex("Slide1"));
        assertEquals(0, DocumentEditService.pageIndex("slide 0"), "slide 0 means the cover, not the end");
        assertThrows(IllegalArgumentException.class, () -> DocumentEditService.pageIndex("üçüncü sayfa"));
    }

    @Test void replacedPicturesKeepTheirFrameAndAreCroppedToIt() throws Exception {
        Path file = dir.resolve("pictures.pptx");
        try (var pptx = new XMLSlideShow(); OutputStream out = Files.newOutputStream(file)) {
            var picture = pptx.createSlide().createPicture(pptx.addPicture(png(10, 10), org.apache.poi.sl.usermodel.PictureData.PictureType.PNG));
            picture.setAnchor(new java.awt.Rectangle(50, 60, 300, 100));
            pptx.write(out);
        }
        assertEquals(List.of("slides/0/image/0"), List.copyOf(service.pictures(file, "pptx").keySet()));
        Path replacement = Files.write(dir.resolve("new.png"), png(100, 100));

        service.replacePictures(file, java.util.Map.of("slides/0/image/0", replacement));

        try (var pptx = new XMLSlideShow(Files.newInputStream(file))) {
            var picture = (XSLFPictureShape) pptx.getSlides().getFirst().getShapes().getFirst();
            assertEquals(100, picture.getPictureData().getImageDimensionInPixels().width, "the new image is shown");
            assertEquals(new java.awt.Rectangle(50, 60, 300, 100), picture.getAnchor().getBounds(), "frame unchanged");
            var crop = ((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) picture.getXmlObject()).getBlipFill().getSrcRect();
            assertEquals(33_333, crop.getT(), "square image cropped top and bottom to fit a 3:1 frame");
            assertEquals(33_333, crop.getB());
        }
    }

    private static byte[] png(int width, int height) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
