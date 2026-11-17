package com.officemanipulator.claude;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceCoverageTest {
    @Test void reportsSourceFiguresTheOutputDropped() {
        String source = "=== BELGE 1: a.docx ===\nKira 40.000 TL, personel 90.000 TL.\n=== BELGE 2: b.csv ===\nAy,Gider\n1,1250.5\n2,980";
        String output = "Kira aylık 40,000 TL. Giderler: 1250,5 ve 980.";
        assertEquals(List.of("90.000"), SourceCoverage.missing(source, output));
    }

    @Test void aDroppedDateIsReportedEvenWhenItsDayNumberAppearsElsewhere() {
        String source = "=== BELGE 1: a.docx ===\nAçılış 3 Mart 2026.\n=== BELGE 2: b.pptx ===\nAçılış 10 Mart 2026, ikinci şube EYLÜL 2027";
        assertEquals(List.of("10 Mart 2026"), SourceCoverage.missing(source, "Açılış 3 Mart 2026. 10 slayt. 2026, 2027"));
        assertTrue(SourceCoverage.missing(source, "Açılış: 03.03.2026 ve 2026-03-10; 2027").isEmpty(), "numeric dates count");
        assertTrue(SourceCoverage.missing(source, "Opening 3 march 2026 and 10 March 2026; 2027").isEmpty(), "English month names count");
    }

    @Test void datesAreListedPerDocumentOnlyWhenTwoDocumentsHaveDates() {
        String two = "=== BELGE 1: butce.docx ===\nKafenin açılış tarihi 3 Mart 2026 olarak belirlenmiştir.\n"
                + "=== BELGE 2: hedefler.pptx ===\nAçılış 10 Mart 2026, ikinci şube Eylül 2027";
        assertEquals("- butce.docx: 3 Mart 2026 (\"…Kafenin açılış tarihi\")\n- hedefler.pptx: 10 Mart 2026 (\"…Açılış\")\n",
                SourceCoverage.datesBySource(two));
        assertEquals("", SourceCoverage.datesBySource("=== BELGE 1: a.docx ===\n3 Mart 2026\n=== BELGE 2: b.csv ===\nKira,40000"));
        assertEquals("", SourceCoverage.datesBySource("3 Mart 2026 ve 10 Mart 2026"));
    }

    @Test void nothingToCheckWithoutSource() {
        assertTrue(SourceCoverage.missing(null, "x").isEmpty());
        assertTrue(SourceCoverage.missing("Madde 1 ve 2", "").isEmpty(), "single digits are list numbers, not facts");
    }
}
