package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreatePresentationParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Component
public class PresentationGenerator {

    private final NodeScriptExecutor executor;

    public PresentationGenerator(NodeScriptExecutor executor) {
        this.executor = executor;
    }

    public void generate(CreatePresentationParams params, Path outputPath) throws IOException, InterruptedException {
        generate(params, outputPath, Map.of());
    }

    public void generate(CreatePresentationParams params, Path outputPath, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        // PptxGenJS 4 exposes enums on instances; accept the static form commonly emitted by generators.
        String compatibility = "{ const P = require('pptxgenjs'); const enums = new P(); "
                + "P.ShapeType = P.ShapeType || enums.ShapeType; P.ChartType = P.ChartType || enums.ChartType; }\n";
        executor.execute(compatibility + params.getScript(), "gen_pptx.js", outputPath, extraEnv);
    }
}
