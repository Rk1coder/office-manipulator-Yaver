package com.officemanipulator.office;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OfficeSecurityTest {
    final OfficeTokens tokens = new OfficeTokens(new ObjectMapper(), "test-secret-at-least-thirty-two-characters");
    @Test void tamperedExpiredAndWrongPurposeTicketsFail() {
        UUID id=UUID.randomUUID();
        String ticket=tokens.ticket(id,"document",Instant.now().plusSeconds(120));
        assertDoesNotThrow(()->tokens.requireTicket(ticket,id,"document"));
        assertThrows(RuntimeException.class,()->tokens.requireTicket(ticket,id,"agent"));
        assertThrows(RuntimeException.class,()->tokens.requireTicket(ticket,UUID.randomUUID(),"document"));
        assertThrows(RuntimeException.class,()->tokens.verify(ticket.substring(0,ticket.length()-5)+"AAAAA"));
        assertThrows(RuntimeException.class,()->tokens.verify(tokens.sign(Map.of("exp",1))));
    }
    @Test void callbackUsesSignedPayloadAndRequiresKeyBinding() {
        UUID id=UUID.randomUUID(); String key="office-"+id;
        var signed=Map.of("key",key,"status",6,"url","http://office-docs/cache/files/x/output.docx");
        var body=new ObjectMapper().valueToTree(Map.of("status",2,"url","http://evil/"));
        var verified=tokens.callback("Bearer "+tokens.sign(Map.of("payload",signed)),body,key);
        assertEquals(6,verified.path("status").asInt());
        assertThrows(RuntimeException.class,()->tokens.callback(null,body,key));
        assertThrows(RuntimeException.class,()->tokens.callback("Bearer "+tokens.sign(Map.of("payload",signed)),body,"wrong"));
        assertThrows(RuntimeException.class,()->tokens.callback("Bearer "+tokens.ticket(id,"agent",Instant.now().plusSeconds(90)),body,key));
    }
    @Test void downloadOriginPathAndRedirectTargetsCannotEscapeDocs() {
        var client=new OfficeDocumentClient(new ObjectMapper(),tokens,"http://office-docs","http://localhost:8082");
        assertEquals("http://office-docs/cache/files/a/output.docx?token=x",client.safeDownloadUri("http://localhost:8082/cache/files/a/output.docx?token=x").toString());
        for (String url: new String[]{"http://evil/cache/files/a", "http://office-docs@evil/cache/files/a", "http://office-docs:81/cache/files/a", "http://office-docs/healthcheck", "http://office-docs/cache/files/../../secret", "http://office-docs/cache/files/%2e%2e/secret", "file:///etc/passwd"})
            assertThrows(RuntimeException.class,()->client.safeDownloadUri(url),url);
    }
    @Test void rejectsNonDocxAndKeepsValidWordBytes() throws Exception {
        assertThrows(RuntimeException.class,()->OfficeDocumentClient.validate("not a document".getBytes(),com.officemanipulator.domain.OutputFormat.DOCX));
        try(var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var out=new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Türkçe belge");document.write(out);
            assertDoesNotThrow(()->OfficeDocumentClient.validate(out.toByteArray(),com.officemanipulator.domain.OutputFormat.DOCX));
        }
    }
    @Test void validatesOtherEditableFormatsAndRejectsCrossFormatBytes() throws Exception {
        try(var pptx=new org.apache.poi.xslf.usermodel.XMLSlideShow();var out=new java.io.ByteArrayOutputStream()) {
            pptx.createSlide();pptx.write(out);
            byte[] bytes=out.toByteArray();
            assertDoesNotThrow(()->OfficeDocumentClient.validate(bytes,com.officemanipulator.domain.OutputFormat.PPTX));
            assertThrows(RuntimeException.class,()->OfficeDocumentClient.validate(bytes,com.officemanipulator.domain.OutputFormat.XLSX));
        }
        try(var wb=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=new java.io.ByteArrayOutputStream()) {
            wb.createSheet();wb.write(out);
            assertDoesNotThrow(()->OfficeDocumentClient.validate(out.toByteArray(),com.officemanipulator.domain.OutputFormat.XLSX));
        }
        assertDoesNotThrow(()->OfficeDocumentClient.validate("a,b\n1,2\n".getBytes(),com.officemanipulator.domain.OutputFormat.CSV));
        assertThrows(RuntimeException.class,()->OfficeDocumentClient.validate("".getBytes(),com.officemanipulator.domain.OutputFormat.CSV));
    }
}
