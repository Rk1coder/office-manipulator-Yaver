package com.officemanipulator.service;

import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OutputFileNamesTest {
    private static Job job(String request, String planJson, OutputFormat format) {
        Job j = new Job(); j.setRequest(request); j.setPlanJson(planJson); j.setFormat(format); return j;
    }

    @Test void usesThePlanTitleWithTurkishCharacters() {
        assertEquals("Karıncaların Yaşamı ve Koloni Düzeni.pptx",
                OutputFileNames.forJob(job("bana sunum yap", "{\"title\":\"Karıncaların Yaşamı ve Koloni Düzeni\"}", OutputFormat.PPTX)));
    }

    @Test void fallsBackToTheRequestsFirstWords() {
        assertEquals("bana karıncalarla ilgili 7 sayfalık bir sunum hazırla.pptx",
                OutputFileNames.forJob(job("bana karıncalarla ilgili 7 sayfalık bir sunum hazırla lütfen çok güzel olsun", null, OutputFormat.PPTX)));
    }

    @Test void stripsPathAndForbiddenCharactersAndBoundsLength() {
        assertEquals("etc passwd a b.docx", OutputFileNames.name("../../etc/passwd: a*b?", "docx"));
        assertEquals("Belge.pdf", OutputFileNames.name(" .. ", "pdf"));
        assertEquals("con_.csv", OutputFileNames.name("con", "csv"));
        String longName = OutputFileNames.name("kelime ".repeat(40), "xlsx");
        assertTrue(longName.length() <= 85, longName);
        assertFalse(longName.contains("  "));
    }

    @Test void editsKeepTheSourceNameWithTheNewExtension() {
        Job original = job("x", null, OutputFormat.DOCX); original.setFileName("Bütçe Raporu 2026.docx");
        assertEquals("Bütçe Raporu 2026.pdf", OutputFileNames.forEdit(job("pdf yap", null, OutputFormat.PDF), original));
        original.setFileName("output.docx");
        assertEquals("kısalt.docx", OutputFileNames.forEdit(job("kısalt", null, OutputFormat.DOCX), original));
        original.setPlanJson("{\"title\":\"Yıllık Bütçe\"}");
        assertEquals("Yıllık Bütçe.pdf", OutputFileNames.forEdit(job("pdf yap", null, OutputFormat.PDF), original), "old output.* jobs fall back to their plan title");
    }
}
