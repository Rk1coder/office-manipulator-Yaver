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

    @Test void nothingToCheckWithoutSource() {
        assertTrue(SourceCoverage.missing(null, "x").isEmpty());
        assertTrue(SourceCoverage.missing("Madde 1 ve 2", "").isEmpty(), "single digits are list numbers, not facts");
    }
}
