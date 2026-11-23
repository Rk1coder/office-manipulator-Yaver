package com.officemanipulator.config;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The organisation's theme (palette, fonts, logo) set once on the Settings page and applied to every
 * generated document. Stored in app_settings; the logo as base64 PNG.
 */
@Service
public class BrandThemeService {
    public record BrandTheme(String primary, String secondary, String accent, String headingFont, String bodyFont, byte[] logo) {
        public boolean isEmpty() { return palette().isEmpty() && headingFont == null && bodyFont == null && logo == null; }

        public List<String> palette() {
            var colors = new ArrayList<String>();
            for (String color : new String[]{primary, secondary, accent}) if (color != null) colors.add(color);
            return colors;
        }
    }

    static final int MAX_LOGO_BYTES = 300 * 1024;
    private static final Pattern HEX = Pattern.compile("[0-9A-F]{6}");
    private static final Pattern FONT = Pattern.compile("[\\p{L}0-9 .-]{1,40}");
    private static final List<String> KEYS = List.of("brand.primary", "brand.secondary", "brand.accent", "brand.headingFont", "brand.bodyFont");
    private final AppSettingsService settings;

    public BrandThemeService(AppSettingsService settings) {
        this.settings = settings;
    }

    public BrandTheme current() {
        Map<String, String> all = settings.getAll();
        String logo = all.get("brand.logo");
        return new BrandTheme(all.get(KEYS.get(0)), all.get(KEYS.get(1)), all.get(KEYS.get(2)), all.get(KEYS.get(3)), all.get(KEYS.get(4)),
                logo == null ? null : Base64.getDecoder().decode(logo));
    }

    /** Replaces the theme. Blank fields clear it; {@code logo} null keeps the stored logo, empty removes it. */
    public BrandTheme save(String primary, String secondary, String accent, String headingFont, String bodyFont, byte[] logo) {
        var values = new java.util.LinkedHashMap<String, String>();
        values.put(KEYS.get(0), color(primary, "Ana renk"));
        values.put(KEYS.get(1), color(secondary, "İkincil renk"));
        values.put(KEYS.get(2), color(accent, "Vurgu rengi"));
        values.put(KEYS.get(3), font(headingFont, "Başlık yazı tipi"));
        values.put(KEYS.get(4), font(bodyFont, "Metin yazı tipi"));
        if (logo != null) {
            if (logo.length > MAX_LOGO_BYTES) throw new IllegalArgumentException("Logo en fazla 300 KB olabilir.");
            if (logo.length > 0 && !isPng(logo)) throw new IllegalArgumentException("Logo PNG dosyası olmalı.");
            values.put("brand.logo", logo.length == 0 ? null : Base64.getEncoder().encodeToString(logo));
        }
        settings.setAll(values);
        return current();
    }

    private static String color(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String hex = value.strip().replaceFirst("^#", "").toUpperCase(java.util.Locale.ROOT);
        if (!HEX.matcher(hex).matches()) throw new IllegalArgumentException(label + " 6 haneli bir renk kodu olmalı (ör. 1F4E79).");
        return hex;
    }

    private static String font(String value, String label) {
        if (value == null || value.isBlank()) return null;
        if (!FONT.matcher(value.strip()).matches()) throw new IllegalArgumentException(label + " yalnızca harf, rakam ve boşluk içerebilir.");
        return value.strip();
    }

    private static boolean isPng(byte[] data) {
        return data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }
}
