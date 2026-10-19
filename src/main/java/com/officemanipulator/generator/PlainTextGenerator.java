package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreateTextParams;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PlainTextGenerator {

    public void generate(CreateTextParams params, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, params.getContent() == null ? "" : params.getContent(), StandardCharsets.UTF_8);
    }
}
