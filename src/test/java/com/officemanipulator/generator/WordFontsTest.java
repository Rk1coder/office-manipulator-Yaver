package com.officemanipulator.generator;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordFontsTest {
    @TempDir Path dir;

    @Test void bodyFontBecomesTheDefaultAndHeadingFontGoesToHeadingStyles() throws Exception {
        Path file = dir.resolve("doc.docx");
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            var ct = CTStyle.Factory.newInstance();
            ct.setStyleId("Heading1");
            doc.createStyles().addStyle(new XWPFStyle(ct));
            var heading = doc.createParagraph(); heading.setStyle("Heading1"); heading.createRun().setText("Amaç");
            doc.createParagraph().createRun().setText("Metin");
            doc.write(out);
        }

        WordGenerator.applyFonts(file, "Montserrat", "Open Sans");

        try (var doc = new XWPFDocument(Files.newInputStream(file))) {
            assertEquals("Montserrat", doc.getStyles().getStyle("Heading1").getCTStyle().getRPr().getRFontsArray(0).getAscii());
            assertEquals("Open Sans", doc.getStyle().getDocDefaults().getRPrDefault().getRPr().getRFontsArray(0).getAscii());
        }
    }

    @Test void aParagraphGivenBothTextAndChildrenKeepsOnlyTheStyledCopy() throws Exception {
        Path file = dir.resolve("doubled.docx");
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            var doubled = doc.createParagraph();
            doubled.createRun().setText("Amaç");
            var styled = doubled.createRun(); styled.setBold(true); styled.setText("Amaç");
            var repeat = doc.createParagraph();
            var a = repeat.createRun(); a.setBold(true); a.setText("ha ");
            var b = repeat.createRun(); b.setBold(true); b.setText("ha ");
            doc.createParagraph().createRun().setText("**Esnek Çalışma:** saatler");
            doc.write(out);
        }

        WordGenerator.removeDoubledText(file);

        try (var doc = new XWPFDocument(Files.newInputStream(file))) {
            assertEquals("Amaç", doc.getParagraphs().get(0).getText());
            assertEquals(true, doc.getParagraphs().get(0).getRuns().getFirst().isBold());
            assertEquals("ha ha ", doc.getParagraphs().get(1).getText(), "two styled runs are real repeated text");
            assertEquals("Esnek Çalışma: saatler", doc.getParagraphs().get(2).getText());
        }
    }

    @Test void clearShadingGivenOnlyAColorGetsItAsFillOnlyBehindLightText() throws Exception {
        Path file = dir.resolve("shading.docx");
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            var table = doc.createTable(1, 2);
            for (int c = 0; c < 2; c++) {
                var cell = table.getRow(0).getCell(c);
                var shd = cell.getCTTc().addNewTcPr().addNewShd();
                shd.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
                shd.setColor("0B4F6C");
                var run = cell.getParagraphs().getFirst().createRun();
                run.setText(c == 0 ? "Gün" : "Amaç");
                run.setColor(c == 0 ? "FFFFFF" : "0B4F6C"); // light text: a fill was meant; dark text: it would vanish
            }
            doc.write(out);
        }

        WordGenerator.fillClearShading(file);

        try (var doc = new XWPFDocument(Files.newInputStream(file))) {
            assertEquals("0B4F6C", doc.getTables().getFirst().getRow(0).getCell(0).getColor());
            assertEquals(false, doc.getTables().getFirst().getRow(0).getCell(1).getCTTc().getTcPr().getShd().isSetFill(), "dark text keeps its unfilled background");
        }
    }

    @Test void shadingWhereTheSchemaHasNoneIsSkippedNotACrash() throws Exception {
        Path file = dir.resolve("row-shading.docx");
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            var row = doc.createTable(1, 1).getRow(0).getCtRow();
            try (var cursor = (row.isSetTrPr() ? row.getTrPr() : row.addNewTrPr()).newCursor()) {
                cursor.toEndToken();
                cursor.insertElementWithText(new javax.xml.namespace.QName(
                        "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "shd", "w"), "");
            }
            doc.write(out);
        }
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> WordGenerator.fillClearShading(file));
    }

    @Test void anOversizedHeaderLogoIsScaledDownKeepingItsRatio() throws Exception {
        Path file = dir.resolve("logo.docx");
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(240, 120, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(file)) {
            var header = doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            header.createParagraph().createRun().addPicture(new java.io.ByteArrayInputStream(png.toByteArray()),
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "logo.png",
                    org.apache.poi.util.Units.toEMU(1188), org.apache.poi.util.Units.toEMU(594)); // 16.5in × 8.25in
            doc.createParagraph().createRun().setText("Gövde");
            doc.write(out);
        }

        WordGenerator.shrinkHeaderPictures(file);

        try (var doc = new XWPFDocument(Files.newInputStream(file))) {
            var picture = doc.getHeaderList().getFirst().getParagraphs().getFirst().getRuns().getFirst().getEmbeddedPictures().getFirst();
            assertEquals(548_640, picture.getCTPicture().getSpPr().getXfrm().getExt().getCy());
            assertEquals(1_097_280, picture.getCTPicture().getSpPr().getXfrm().getExt().getCx(), "2:1 ratio kept");
            String xml = doc.getHeaderList().getFirst()._getHdrFtr().xmlText();
            org.junit.jupiter.api.Assertions.assertTrue(xml.contains("cx=\"1097280\" cy=\"548640\""), "the layout extent shrinks too");
        }
    }
}
