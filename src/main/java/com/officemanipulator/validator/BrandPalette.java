package com.officemanipulator.validator;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Finds colors in a generator script that are neither a corporate palette color, a tint/shade of one, nor a neutral. */
public final class BrandPalette {
    // '1F4E79', "#1F4E79", exceljs argb 'FF1F4E79'.
    private static final Pattern HEX = Pattern.compile("['\"`]#?(?:[Ff]{2})?([0-9A-Fa-f]{6})['\"`]");
    // pdf-lib rgb(0.12, 0.09, 0.51)
    private static final Pattern RGB = Pattern.compile("\\brgb\\(\\s*([01]?\\.?\\d*)\\s*,\\s*([01]?\\.?\\d*)\\s*,\\s*([01]?\\.?\\d*)\\s*\\)");
    private static final float HUE_TOLERANCE = 0.04f; // ≈14°

    private BrandPalette() {}

    public static List<String> offPalette(String script, List<String> palette) {
        if (script == null || palette.isEmpty()) return List.of();
        Set<String> used = new LinkedHashSet<>();
        var hex = HEX.matcher(script);
        while (hex.find()) used.add(hex.group(1).toUpperCase(Locale.ROOT));
        var rgb = RGB.matcher(script);
        while (rgb.find()) {
            try {
                used.add(String.format("%02X%02X%02X", channel(rgb.group(1)), channel(rgb.group(2)), channel(rgb.group(3))));
            } catch (NumberFormatException ignored) {
                // rgb(r, g, b) with variables: nothing to check
            }
        }
        return used.stream().filter(color -> !allowed(color, palette)).limit(10).toList();
    }

    private static int channel(String value) {
        return Math.round(Math.min(1f, Math.max(0f, Float.parseFloat(value))) * 255);
    }

    static boolean allowed(String color, List<String> palette) {
        float[] hsb = hsb(color);
        // white, black, grays, and near-black text tones such as slate 38334A
        if (hsb[1] < 0.22f || hsb[2] < 0.15f || hsb[2] < 0.35f && hsb[1] < 0.45f) return true;
        for (String brand : palette) {
            float[] target = hsb(brand);
            if (target[1] < 0.15f) continue; // a gray brand color has no hue to match
            float distance = Math.abs(hsb[0] - target[0]);
            if (Math.min(distance, 1 - distance) <= HUE_TOLERANCE) return true;
        }
        return false;
    }

    private static float[] hsb(String hex) {
        int value = Integer.parseInt(hex, 16);
        return Color.RGBtoHSB(value >> 16 & 0xFF, value >> 8 & 0xFF, value & 0xFF, null);
    }
}
