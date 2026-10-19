package com.officemanipulator.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statically scans the pptxgenjs script Claude wrote for the measurable design rules spelled out
 * in SystemPrompts (slide-pattern repetition, bullet-slide ratio), driven by the
 * {@code // LAYOUT: <PATTERN>} comment Claude is instructed to place before every
 * {@code pptx.addSlide()} call. Violations are reported back to Claude as a tool error, which the
 * existing retry loop in ClaudeOrchestrator.runLoop then gives it a chance to correct.
 */
public final class PresentationDesignValidator {
    private PresentationDesignValidator() {}

    private static final Pattern LAYOUT_TAG = Pattern.compile("//\\s*LAYOUT:\\s*([A-Z_]+)");
    private static final double DEFAULT_MAX_BULLET_RATIO = 0.35;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void validate(Job job, String toolInputJson) throws Exception {
        if (job.getFormat() == null || job.getFormat().generationFormat() != OutputFormat.PPTX) return;
        JsonNode node = MAPPER.readTree(toolInputJson);
        String script = node.path("script").asText("");
        if (script.isBlank()) return;

        List<String> layouts = new ArrayList<>();
        Matcher m = LAYOUT_TAG.matcher(script);
        while (m.find()) layouts.add(m.group(1));
        // Older callers (e.g. edit flows re-sending an untagged script) aren't held to this rule.
        if (layouts.isEmpty()) return;

        List<String> violations = new ArrayList<>();

        int consecutive = 1;
        for (int i = 1; i < layouts.size(); i++) {
            if (layouts.get(i).equals(layouts.get(i - 1))) {
                consecutive++;
                if (consecutive > 2) {
                    violations.add("Slide pattern '" + layouts.get(i) + "' is used more than twice consecutively (around slide " + (i + 1) + ").");
                    break;
                }
            } else consecutive = 1;
        }

        long bulletSlides = layouts.stream().filter("TITLE_BULLETS"::equals).count();
        double ratio = (double) bulletSlides / layouts.size();
        double maxRatio = resolveMaxBulletRatio(job);
        if (ratio > maxRatio) {
            violations.add(String.format(Locale.ROOT,
                    "Too many bullet-led (TITLE_BULLETS) slides: %.0f%% of %d slides, max allowed is %.0f%%. " +
                    "Replace some with BIG_NUMBER, COMPARISON, TIMELINE, PROCESS, METRIC_GRID, CHART_FOCUS, TABLE_FOCUS, or QUOTE.",
                    ratio * 100, layouts.size(), maxRatio * 100));
        }

        if (!violations.isEmpty())
            throw new IllegalArgumentException("Design rule violations — revise the script: " + String.join(" ", violations));
    }

    private static double resolveMaxBulletRatio(Job job) {
        if (job.getDesignDirectionJson() == null) return DEFAULT_MAX_BULLET_RATIO;
        try {
            double v = MAPPER.readTree(job.getDesignDirectionJson()).path("maxBulletSlideRatio").asDouble(DEFAULT_MAX_BULLET_RATIO);
            return v > 0 && v <= 1 ? v : DEFAULT_MAX_BULLET_RATIO;
        } catch (Exception e) {
            return DEFAULT_MAX_BULLET_RATIO;
        }
    }
}
