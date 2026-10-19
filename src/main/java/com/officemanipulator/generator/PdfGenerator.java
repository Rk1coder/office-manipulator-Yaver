package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreatePdfParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Component
public class PdfGenerator {

    private final NodeScriptExecutor executor;

    public PdfGenerator(NodeScriptExecutor executor) {
        this.executor = executor;
    }

    public void generate(CreatePdfParams params, Path outputPath) throws IOException, InterruptedException {
        generate(params, outputPath, Map.of());
    }

    public void generate(CreatePdfParams params, Path outputPath, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        executor.execute(params.getScript(), "gen_pdf.js", outputPath, extraEnv);
    }
}
