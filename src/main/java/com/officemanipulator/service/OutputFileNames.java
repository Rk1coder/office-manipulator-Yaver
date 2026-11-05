package com.officemanipulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.domain.Job;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Download names derived from the content ("Karıncaların Yaşamı.pptx") instead of "output.pptx".
 * Only the name the user sees changes; files on disk keep their fixed storage path.
 */
final class OutputFileNames {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LENGTH = 80;
    private static final String FALLBACK = "Belge";
    private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private OutputFileNames() {}

    /** Approved plan's title when there is one, else the request text. */
    static String forJob(Job job) {
        return name(planTitle(job) != null ? planTitle(job) : firstWords(job.getRequest(), 8), job.getFormat().fileExtension());
    }

    /** Edits/patches/conversions keep the source document's name (or its plan title), with the new extension. */
    static String forEdit(Job edit, Job original) {
        String base = baseName(original.getFileName());
        if (base == null) base = planTitle(original);
        return base != null ? name(base, edit.getFormat().fileExtension()) : forJob(edit);
    }

    static String name(String raw, String extension) {
        String base = raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC)
                // Path separators, characters Windows forbids, and control characters.
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").strip();
        if (base.length() > MAX_LENGTH) {
            int cut = base.lastIndexOf(' ', MAX_LENGTH);
            base = base.substring(0, cut > MAX_LENGTH / 2 ? cut : MAX_LENGTH).strip();
        }
        base = base.replaceAll("^[. ]+|[. ]+$", "");
        if (base.isEmpty()) base = FALLBACK;
        if (WINDOWS_RESERVED.contains(base.toUpperCase(Locale.ROOT))) base = base + "_";
        return base + "." + extension;
    }

    private static String planTitle(Job job) {
        if (job.getPlanJson() == null || job.getPlanJson().isBlank()) return null;
        try {
            String title = JSON.readTree(job.getPlanJson()).path("title").asText("").strip();
            return title.isEmpty() ? null : title;
        } catch (Exception e) {
            return null;
        }
    }

    /** Name without extension, or null when it carries no meaning (missing or the old "output.*"). */
    private static String baseName(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0 ? fileName.substring(0, dot) : fileName).strip();
        return base.isEmpty() || base.equalsIgnoreCase("output") ? null : base;
    }

    private static String firstWords(String text, int words) {
        if (text == null) return null;
        String[] parts = text.strip().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(parts, Math.min(words, parts.length)));
    }
}
