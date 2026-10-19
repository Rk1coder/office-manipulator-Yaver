package com.officemanipulator.office;

import com.officemanipulator.domain.OutputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeConversionTest {
    @TempDir Path temp;
    @Test void convertsToSeparateFileWithoutOverwritingSource() throws Exception {
        var client = mock(OfficeDocumentClient.class);
        var tokens = mock(OfficeTokens.class);
        var registry = new ConversionSourceRegistry();
        var service = new DocumentConversionService(client, tokens, registry, "http://app:8080");
        Path source = temp.resolve("original.docx"), target = temp.resolve("child/output.pdf");
        Files.writeString(source, "original bytes");
        when(tokens.ticket(any(), eq("convert-source"), any())).thenReturn("ticket");
        when(client.convertTo(startsWith("http://app:8080/api/office/convert-source/"), eq("docx"), eq("pdf"), startsWith("convert-")))
                .thenReturn("%PDF-converted".getBytes());
        service.convert(source, target, OutputFormat.DOCX, OutputFormat.PDF);
        assertEquals("original bytes", Files.readString(source));
        assertEquals("%PDF-converted", Files.readString(target));
    }
    @Test void rejectsCrossFamilyConversionBeforeContactingOffice() {
        var client = mock(OfficeDocumentClient.class);
        var service = new DocumentConversionService(client, mock(OfficeTokens.class), new ConversionSourceRegistry(), "http://app:8080");
        assertThrows(IllegalArgumentException.class, () -> service.convert(temp.resolve("a.xlsx"),temp.resolve("b.pptx"),OutputFormat.XLSX,OutputFormat.PPTX));
        verifyNoInteractions(client);
    }
}
