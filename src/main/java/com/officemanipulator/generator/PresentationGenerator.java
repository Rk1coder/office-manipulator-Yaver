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
        fixPointLineSpacing(outputPath);
    }

    /** pptxgenjs reads {@code lineSpacing} in points; models often pass a multiple (1.15), which stacks
     * every line onto the previous one. A spacing under 6pt is read as that multiple instead. Literal
     * markdown "**" markers, and a typed bullet character in an already bulleted paragraph, are removed as well. */
    static void fixPointLineSpacing(Path pptx) throws IOException {
        boolean changed = false;
        try (var deck = new org.apache.poi.xslf.usermodel.XMLSlideShow(java.nio.file.Files.newInputStream(pptx))) {
            for (var slide : deck.getSlides())
                for (var shape : slide.getShapes())
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape text)
                        for (var paragraph : text.getTextParagraphs()) {
                            for (var run : paragraph.getTextRuns())
                                if (run.getRawText() != null && run.getRawText().contains("**")) {
                                    run.setText(run.getRawText().replace("**", "")); // literal markdown bold markers
                                    changed = true;
                                }
                            // bullet: true plus a typed "• " shows two bullets.
                            var runs = paragraph.getTextRuns();
                            if (paragraph.isBullet() && !runs.isEmpty() && runs.getFirst().getRawText() != null
                                    && runs.getFirst().getRawText().matches("(?s)\\s*[•·▪◦‣\\-–]\\s+.*")) {
                                runs.getFirst().setText(runs.getFirst().getRawText().replaceFirst("^\\s*[•·▪◦‣\\-–]\\s+", ""));
                                changed = true;
                            }
                            Double spacing = paragraph.getLineSpacing();
                            // POI: negative = points, positive = percent.
                            if (spacing != null && spacing < 0 && -spacing < 6) {
                                paragraph.setLineSpacing((double) Math.round(-spacing * 100)); // 1.15 * 100 = 114.999…
                                changed = true;
                            }
                        }
            if (changed) try (var out = java.nio.file.Files.newOutputStream(pptx)) { deck.write(out); }
        }
    }
}
