package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreatePdfParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Component
public class PdfGenerator {

    private final NodeScriptExecutor executor;

    /** pdf-lib draws one line per call; models that skip wrapping run text off the page. One line, so
     * script error line numbers shift by only one. A script's own wrapText (module scope) shadows it. */
    static final String WRAP_HELPER = "globalThis.wrapText = (text, font, size, maxWidth) => { const lines = []; "
            + "for (const para of String(text).split('\\n')) { let line = ''; "
            + "for (const word of para.split(/\\s+/).filter(Boolean)) { const next = line ? line + ' ' + word : word; "
            + "if (!line || font.widthOfTextAtSize(next, size) <= maxWidth) line = next; else { lines.push(line); line = word; } } "
            + "lines.push(line); } return lines; }; "
            // Markdown bold markers drawn literally ("**VPN**") are never wanted in a PDF.
            + "{ const { PDFPage } = require('pdf-lib'); const draw = PDFPage.prototype.drawText; "
            + "PDFPage.prototype.drawText = function (text, options) { return draw.call(this, String(text).replace(/\\*\\*/g, ''), options); }; }\n";

    public PdfGenerator(NodeScriptExecutor executor) {
        this.executor = executor;
    }

    public void generate(CreatePdfParams params, Path outputPath) throws IOException, InterruptedException {
        generate(params, outputPath, Map.of());
    }

    public void generate(CreatePdfParams params, Path outputPath, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        executor.execute(WRAP_HELPER + params.getScript(), "gen_pdf.js", outputPath, extraEnv);
    }
}
