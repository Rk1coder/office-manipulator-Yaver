package com.officemanipulator.claude;

import java.util.Arrays;
import java.util.List;

/**
 * Font pairings the design director picks from. Every face here ships with Windows/Office and is
 * installed in the ONLYOFFICE renderer, so the visual review sees what the user will see and
 * Turkish glyphs render; a free choice would fall back to a substitute font on either side.
 */
public enum FontPairing {
    EDITORIAL("Georgia", "Calibri", "Georgia", "magazine-like, storytelling, nature, history, culture"),
    BOLD_IMPACT("Arial Black", "Calibri", "Arial Black", "punchy, data-heavy, sports, marketing, pitch"),
    MODERN("Trebuchet MS", "Calibri", "Trebuchet MS", "tech, product, startup, education"),
    WARM_HUMANIST("Candara", "Corbel", "Candara", "friendly, health, community, children, food"),
    ELEGANT("Constantia", "Corbel", "Constantia", "premium, art, law, finance, formal reports"),
    CLASSIC("Cambria", "Calibri", "Cambria", "neutral corporate — only when nothing else fits");

    public final String heading, body, display, mood;

    FontPairing(String heading, String body, String display, String mood) {
        this.heading = heading; this.body = body; this.display = display; this.mood = mood;
    }

    public static List<String> names() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }

    /** Unknown or missing (directions stored before this field existed) → CLASSIC. */
    public static FontPairing parse(String name) {
        return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(CLASSIC);
    }

    public String describe() {
        return name() + ": headings fontFace \"" + heading + "\", body \"" + body + "\", big numbers/eyebrow labels \"" + display + "\"";
    }
}
