package com.officemanipulator.claude;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Presentation design guides kept as Markdown in {@code resources/presentation-skills/} — editable
 * without touching Java and usable with every provider (unlike Anthropic's Claude-only Skills API).
 * File name prefixes decide where each guide goes:
 * {@code plan-*} → plan step and generation, {@code gen-*} → generation only, {@code review-*} → visual review.
 */
@Component
public class PresentationSkills {
    private final String plan;
    private final String generation;
    private final String review;

    public PresentationSkills() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:presentation-skills/*.md");
            Arrays.sort(files, Comparator.comparing(Resource::getFilename));
            this.plan = join(files, "plan-");
            this.generation = plan + join(files, "gen-");
            this.review = join(files, "review-");
        } catch (IOException e) {
            throw new UncheckedIOException("presentation-skills could not be loaded", e);
        }
    }

    public String planGuide() { return plan; }
    public String generationGuide() { return generation; }
    public String reviewChecklist() { return review; }

    private static String join(Resource[] files, String prefix) {
        return Arrays.stream(files).filter(f -> f.getFilename() != null && f.getFilename().startsWith(prefix))
                .map(PresentationSkills::read)
                .map(body -> "\n\n═══ DESIGN GUIDE ═══\n" + body.strip())
                .collect(Collectors.joining());
    }

    private static String read(Resource file) {
        try (var in = file.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.getFilename(), e);
        }
    }
}
