package com.officemanipulator.claude;

import java.util.Arrays;
import java.util.List;

/**
 * The fixed vocabulary of slide layout patterns Claude may choose from when it writes a
 * pptxgenjs script. Without a named vocabulary, a free-form prompt tends to collapse onto
 * "title + bullets" for every slide regardless of how creative the instructions sound.
 * See SystemPrompts for the concrete pptxgenjs recipe behind each pattern, and
 * PresentationDesignValidator for how the "// LAYOUT: X" tags these names are used in are checked.
 */
public enum SlidePattern {
    COVER, SECTION_DIVIDER, BIG_STATEMENT, BIG_NUMBER, TITLE_BULLETS, IMAGE_LEFT_TEXT_RIGHT,
    TEXT_LEFT_IMAGE_RIGHT, FULL_BLEED_IMAGE, TWO_COLUMN, THREE_COLUMN, COMPARISON, TIMELINE,
    PROCESS, QUOTE, METRIC_GRID, CHART_FOCUS, TABLE_FOCUS, BEFORE_AFTER, PROBLEM_SOLUTION,
    ROADMAP, SUMMARY;

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
