package com.officemanipulator.validator;

import org.apache.poi.hwpf.HWPFDocument;
import org.odftoolkit.simple.PresentationDocument;
import org.odftoolkit.simple.SpreadsheetDocument;
import org.odftoolkit.simple.TextDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Shared structural checks for ODF (ODT/ODS/ODP), legacy binary DOC and plain-text formats,
 * used by both {@link FileValidator} (freshly generated/converted output) and
 * {@link com.officemanipulator.office.OfficeDocumentClient} (bytes re-downloaded from the
 * ONLYOFFICE editor, or imported by a user).
 */
public final class DocumentSignatures {
    private DocumentSignatures() {}

    public static void requireNonBlankOdt(byte[] bytes) throws IOException {
        try (var doc = TextDocument.loadDocument(new ByteArrayInputStream(bytes))) {
            Iterator<org.odftoolkit.simple.text.Paragraph> it = doc.getParagraphIterator();
            boolean meaningful = false;
            while (it.hasNext()) {
                String text = it.next().getTextContent();
                if (text != null && !text.isBlank()) { meaningful = true; break; }
            }
            if (!meaningful) throw new IllegalArgumentException("ODT has no meaningful content");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IOException("Invalid ODT", e);
        }
    }

    public static void requireNonBlankOds(byte[] bytes) throws IOException {
        try (var doc = SpreadsheetDocument.loadDocument(new ByteArrayInputStream(bytes))) {
            if (doc.getSheetCount() == 0) throw new IllegalArgumentException("ODS has no sheets");
            boolean meaningful = false;
            for (int s = 0; s < doc.getSheetCount() && !meaningful; s++) {
                var sheet = doc.getSheetByIndex(s);
                int rows = Math.min(sheet.getRowCount(), 2000);
                int cols = Math.min(sheet.getColumnCount(), 500);
                for (int r = 0; r < rows && !meaningful; r++)
                    for (int c = 0; c < cols; c++) {
                        String text = sheet.getCellByPosition(c, r).getDisplayText();
                        if (text != null && !text.isBlank()) { meaningful = true; break; }
                    }
            }
            if (!meaningful) throw new IllegalArgumentException("ODS has no meaningful cells");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IOException("Invalid ODS", e);
        }
    }

    public static void requireNonBlankOdp(byte[] bytes) throws IOException {
        try (var doc = PresentationDocument.loadDocument(new ByteArrayInputStream(bytes))) {
            if (doc.getSlideCount() == 0) throw new IllegalArgumentException("ODP has no slides");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IOException("Invalid ODP", e);
        }
    }

    public static void requireNonBlankDoc(byte[] bytes) throws IOException {
        try (var doc = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = doc.getRange().text();
            if (text == null || text.isBlank()) throw new IllegalArgumentException("DOC has no meaningful content");
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IOException("Invalid DOC", e);
        }
    }

    public static void requireNonBlankText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.isBlank()) throw new IllegalArgumentException("File has no content");
    }

    public static void requireValidJson(byte[] bytes, com.fasterxml.jackson.databind.ObjectMapper json) {
        try {
            json.readTree(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    public static void requireWellFormedXml(byte[] bytes) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML", e);
        }
    }

    /** ODB has no real parser available; this only checks it is a zip with an OpenDocument-database mimetype entry. */
    public static void requireOdfZipSignature(byte[] bytes, String expectedMimePrefix) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || !"mimetype".equals(entry.getName()))
                throw new IllegalArgumentException("Missing OpenDocument mimetype entry");
            String mime = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
            if (!mime.startsWith(expectedMimePrefix)) throw new IllegalArgumentException("Unexpected mimetype: " + mime);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid OpenDocument package", e);
        }
    }
}
