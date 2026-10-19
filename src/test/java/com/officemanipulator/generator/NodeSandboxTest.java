package com.officemanipulator.generator;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeSandboxTest {
    @TempDir Path tempDir;
    private final NodeScriptExecutor executor = new NodeScriptExecutor();

    @Test void cannotReadSiblingJobOrParentEnvironment() throws Exception {
        Path secret = tempDir.resolve("sibling-secret.txt");
        Files.writeString(secret, "fake-audit-value");
        Path out = tempDir.resolve("job/output.txt");
        String source = """
                const fs = require('fs');
                let leaked = 'blocked';
                try { leaked = fs.readFileSync('%s','utf8'); } catch (_) {}
                fs.writeFileSync(process.env.OUTPUT_PATH,
                  leaked + '|' + (process.env.AUDIT_PARENT_SECRET || 'absent'));
                """.formatted(secret);
        executor.execute(source, "probe.js", out);
        assertEquals("blocked|absent", Files.readString(out));
    }

    // Network access is intentionally NOT sandboxed (no Linux user/network namespaces are used —
    // see NodeScriptExecutor's class javadoc for why). Filesystem access, environment variables,
    // and child-process/worker spawning ARE still confined; network isolation is an accepted
    // residual risk since a script can only reach its own job's already-submitted content.

    @Test void rejectsEnvironmentInjection() {
        assertThrows(IOException.class, () -> executor.execute(
                "require('fs').writeFileSync(process.env.OUTPUT_PATH,'ok')", "env.js",
                tempDir.resolve("output.txt"), Map.of("NODE_OPTIONS", "--inspect=0")));
    }

    @Test void outputFloodHasBoundedDiagnostics() {
        IOException error = assertThrows(IOException.class, () -> executor.execute(
                "process.stdout.write('x'.repeat(2000000)); process.stderr.write('y'.repeat(2000000)); process.exitCode=1;",
                "flood.js", tempDir.resolve("output.txt")));
        assertTrue(error.getMessage().length() < 140000, "Child output must not accumulate without bounds");
        assertTrue(error.getMessage().contains("truncated"));
    }

    @Test void hungScriptActuallyTimesOutWithOpenStreams() {
        long start = System.nanoTime();
        IOException error = assertThrows(IOException.class, () -> executor.execute(
                "console.log('started');setTimeout(()=>process.exit(0),65000);setInterval(()=>{},1000)",
                "hang.js", tempDir.resolve("output.txt")));
        assertTrue(error.getMessage().contains("timed out"), error.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 64);
    }

    @Test void childProcessesAreDenied() throws Exception {
        Path out = tempDir.resolve("output.txt");
        executor.execute("""
                const fs = require('fs');
                let result = 'allowed';
                try { require('child_process').spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)'], {detached:true,stdio:'ignore'}).unref(); }
                catch (e) { result = e.code; }
                fs.writeFileSync(process.env.OUTPUT_PATH, result);
                """, "child.js", out);
        assertEquals("ERR_ACCESS_DENIED", Files.readString(out));
    }

    @Test void stagedImagesRemainAvailable() throws Exception {
        Path image = tempDir.resolve("img_1.png");
        Files.writeString(image, "test image fixture");
        Path out = tempDir.resolve("output.txt");
        executor.execute("require('fs').copyFileSync(process.env.IMG_1,process.env.OUTPUT_PATH)",
                "image.js", out, Map.of("IMG_1", image.toString()));
        assertEquals("test image fixture", Files.readString(out));
    }

    @Test void producesSearchableTurkishPdfWithBundledFontkit() throws Exception {
        Path out = tempDir.resolve("turkish.pdf");
        executor.execute("""
                const { PDFDocument } = require('pdf-lib');
                const fontkit = require('@pdf-lib/fontkit');
                const fs = require('fs');
                (async () => {
                  const doc = await PDFDocument.create();
                  doc.registerFontkit(fontkit);
                  const font = await doc.embedFont(fs.readFileSync(process.env.TURKISH_FONT_REGULAR));
                  doc.addPage().drawText('Türkçe: ğüşıöçĞÜŞİÖÇ', { x: 30, y: 600, font, size: 18 });
                  fs.writeFileSync(process.env.OUTPUT_PATH, await doc.save());
                })().catch(e => { console.error(e); process.exitCode = 1; });
                """, "unicode.js", out);
        try (var doc = Loader.loadPDF(out.toFile())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Türkçe: ğüşıöçĞÜŞİÖÇ"));
        }
    }
    @Test void pptxEmbedsImageWithPinnedDependencies() throws Exception {
        Path image = tempDir.resolve("img_1.png");
        Files.write(image, java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aEegAAAAASUVORK5CYII="));
        Path out = tempDir.resolve("image.pptx");
        executor.execute("""
                const PptxGenJS = require('pptxgenjs');
                const pptx = new PptxGenJS();
                pptx.addSlide().addImage({path:process.env.IMG_1,x:1,y:1,w:1,h:1});
                pptx.writeFile({fileName:process.env.OUTPUT_PATH});
                """, "image.js", out, Map.of("IMG_1", image.toString()));
        try (var deck = new org.apache.poi.xslf.usermodel.XMLSlideShow(Files.newInputStream(out))) {
            assertEquals(1, deck.getPictureData().size());
        }
    }

    @Test void xlsxExtendedConditionalFormattingUsesPinnedUuid() throws Exception {
        Path out = tempDir.resolve("format.xlsx");
        executor.execute("""
                const ExcelJS = require('exceljs');
                const workbook = new ExcelJS.Workbook();
                const sheet = workbook.addWorksheet('Rapor');
                sheet.addRow([10]);
                sheet.addConditionalFormatting({ref:'A1',rules:[{
                  type:'dataBar',minLength:0,maxLength:100,showValue:true,
                  cfvo:[{type:'min'},{type:'max'}],color:{argb:'FF00FF00'}
                }]});
                workbook.xlsx.writeFile(process.env.OUTPUT_PATH);
                """, "format.js", out);
        try (var zip = new java.util.zip.ZipFile(out.toFile())) {
            String xml = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(xml.contains("x14:conditionalFormatting"));
            assertTrue(xml.contains("dataBar"));
        }
    }

    @Test void missingPrlimitDependencyNeverRunsScript() throws Exception {
        String previous = System.getProperty("node.prlimit.binary");
        try {
            System.setProperty("node.prlimit.binary", tempDir.resolve("missing-prlimit").toString());
            Path out = tempDir.resolve("must-not-exist.txt");
            assertThrows(IOException.class, () -> executor.execute(
                    "require('fs').writeFileSync(process.env.OUTPUT_PATH,'unsafe fallback')", "blocked.js", out));
            assertFalse(Files.exists(out));
        } finally {
            if (previous == null) System.clearProperty("node.prlimit.binary");
            else System.setProperty("node.prlimit.binary", previous);
        }
    }

}
