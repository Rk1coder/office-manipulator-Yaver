package com.officemanipulator.validator;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LayoutCheckTest {
    @TempDir Path dir;

    @Test void stackedTextBoxesAreFoundButNeighboursAndPartialTouchesAreNot() throws Exception {
        Path file = dir.resolve("deck.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var slide = deck.createSlide();
            for (String bullet : List.of("VPN zorunlu", "Halka açık Wi-Fi")) {
                var box = slide.createTextBox(); box.setAnchor(new Rectangle(50, 100, 600, 200)); box.setText(bullet);
            }
            var clean = deck.createSlide();
            var title = clean.createTextBox(); title.setAnchor(new Rectangle(50, 20, 600, 60)); title.setText("Başlık");
            var body = clean.createTextBox(); body.setAnchor(new Rectangle(50, 70, 600, 300)); body.setText("Gövde"); // touches the title by 10pt
            deck.write(out);
        }

        var findings = LayoutCheck.overlappingText(file);

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("slide 1: text boxes \"VPN zorunlu\" and \"Halka açık Wi-Fi\""));
    }

    @Test void aTitleRunningUnderTheLogoIsFound() throws Exception {
        Path file = dir.resolve("logo.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var slide = deck.createSlide();
            var title = slide.createTextBox(); title.setAnchor(new Rectangle(40, 20, 880, 50)); title.setText("Uzun başlık");
            var png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
            var logo = slide.createPicture(deck.addPicture(png.toByteArray(), org.apache.poi.sl.usermodel.PictureData.PictureType.PNG));
            logo.setAnchor(new Rectangle(800, 30, 90, 30));
            ((org.openxmlformats.schemas.presentationml.x2006.main.CTPicture) logo.getXmlObject()).getNvPicPr().getCNvPr().setDescr("/tmp/office-node-1/IMG_LOGO.png");
            deck.write(out);
        }

        var findings = LayoutCheck.overlappingText(file);

        assertEquals(List.of("slide 1: text box \"Uzun başlık\" runs under the logo; end it left of the logo (x + w below 11.11in)"), findings);
    }

    @Test void whiteTextIsUnreadableOnlyWithoutSomethingDarkBehindIt() throws Exception {
        Path file = dir.resolve("contrast.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var bare = deck.createSlide();
            var lost = bare.createTextBox(); lost.setAnchor(new Rectangle(50, 100, 600, 80)); lost.setText("Kapak başlığı");
            lost.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontColor(java.awt.Color.WHITE);
            var card = deck.createSlide();
            var rect = card.createAutoShape(); rect.setAnchor(new Rectangle(40, 90, 620, 100)); rect.setFillColor(new java.awt.Color(0x0B, 0x4F, 0x6C));
            var onCard = card.createTextBox(); onCard.setAnchor(new Rectangle(50, 100, 600, 80)); onCard.setText("Kart");
            onCard.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontColor(java.awt.Color.WHITE);
            deck.write(out);
        }

        var findings = LayoutCheck.invisibleText(file);

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("slide 1: light text \"Kapak başlığı\""));
    }

    @Test void aPictureOnTopOfTextIsFoundButOneBehindItIsNot() throws Exception {
        Path file = dir.resolve("pictures.pptx");
        try (var deck = new XMLSlideShow(); var out = Files.newOutputStream(file)) {
            var png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
            var data = deck.addPicture(png.toByteArray(), org.apache.poi.sl.usermodel.PictureData.PictureType.PNG);
            var covered = deck.createSlide();
            var title = covered.createTextBox(); title.setAnchor(new Rectangle(50, 100, 600, 80)); title.setText("Kapak");
            covered.createPicture(data).setAnchor(new Rectangle(20, 50, 300, 180));
            var behind = deck.createSlide();
            behind.createPicture(data).setAnchor(new Rectangle(0, 0, 960, 540));
            var onImage = behind.createTextBox(); onImage.setAnchor(new Rectangle(50, 100, 600, 80)); onImage.setText("Görselin üstünde");
            deck.write(out);
        }

        assertEquals(List.of("slide 1: a picture is drawn on top of the text \"Kapak\" and hides it; move the picture beside the text, or add it before the text so it sits behind"),
                LayoutCheck.overlappingText(file));
    }

    @Test void pdfTextRunningPastTheRightEdgeIsFound() throws Exception {
        Path file = dir.resolve("doc.pdf");
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var text = new PDPageContentStream(doc, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                text.newLineAtOffset(50, 700);
                text.showText("Kisa satir");
                text.newLineAtOffset(0, -20);
                text.showText("Bu satir cok uzun ve sayfanin sag kenarindan tasiyor cunku hic sarilmadan tek seferde cizildi ve devam ediyor ve hala devam ediyor sayfanin disina dogru");
                text.endText();
            }
            doc.save(file.toFile());
        }

        var findings = LayoutCheck.textOffPage(file);

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.getFirst().startsWith("page 1: \"Bu satir cok uzun"));
        assertTrue(findings.getFirst().endsWith("right edge of the page"));
    }

    @Test void spreadsheetFormulaErrorsAndAShiftedHeaderAreFoundAndACleanTableIsNot() throws Exception {
        Path file = dir.resolve("budget.xlsx");
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = Files.newOutputStream(file)) {
            var shifted = wb.createSheet("Bütçe");
            var header = shifted.createRow(0);
            header.createCell(1).setCellValue("Departman");  // titles start one column right of the data
            header.createCell(2).setCellValue("Q1");
            header.createCell(3).setCellValue("Pay");
            for (int r = 1; r <= 2; r++) {
                var row = shifted.createRow(r);
                row.createCell(0).setCellValue("İK");
                row.createCell(1).setCellValue(100);
                row.createCell(2).setCellFormula("B" + (r + 1) + "/$B$9");  // the total is not in row 9
            }
            var clean = wb.createSheet("Temiz");
            var titles = clean.createRow(0);
            titles.createCell(0).setCellValue("Departman");
            titles.createCell(1).setCellValue("Tutar");
            titles.createCell(2).setCellValue("Not");  // an unused title alone is not a shift
            var row = clean.createRow(1);
            row.createCell(0).setCellValue("BT");
            row.createCell(1).setCellFormula("2*50");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.write(out);
        }

        List<String> findings = LayoutCheck.spreadsheetDefects(file);

        assertEquals(2, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("C2 =B2/$B$9 gives #DIV/0!"), findings.get(0));
        assertTrue(findings.get(1).contains("column(s) A hold data") && findings.get(1).contains("\"Pay\" has no data"), findings.get(1));
    }
}
