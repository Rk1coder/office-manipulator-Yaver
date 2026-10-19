package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreateWordParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@Component
public class WordGenerator {

    private final NodeScriptExecutor executor;

    public WordGenerator(NodeScriptExecutor executor) {
        this.executor = executor;
    }

    public void generate(CreateWordParams params, Path outputPath) throws IOException, InterruptedException {
        generate(params, outputPath, Map.of());
    }

    public void generate(CreateWordParams params, Path outputPath, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        executor.execute(params.getScript(), "gen_docx.js", outputPath, extraEnv);
    }
}
