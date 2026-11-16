package com.officemanipulator.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Template-preserving AI edit: the document's preview content is flattened into numbered text
 * slots ("blocks/3/text", "slides/0/bullets/2", "sheets/0/rows/1/2"), the model returns only the
 * slots it changes, and those are written back into the preview map, which
 * {@link DirectPatchService} then applies to the ORIGINAL file — styles, template, images and
 * layout stay as they were. Structural keys (block type, sheet name) are never slots.
 */
final class TemplateEdit {
    private static final Set<String> STRUCTURAL_KEYS = Set.of("type", "name");

    private TemplateEdit() {}

    /** Slot id → current text, in document order; empty for a type DirectPatchService can't write (PDF). */
    static Map<String, String> slots(Map<String, Object> preview) {
        // The same key DirectPatchService patches; XLSX previews also carry a legacy csvRows copy of sheet 1.
        String root = switch (String.valueOf(preview.get("type"))) {
            case "docx" -> "blocks"; case "pptx" -> "slides"; case "xlsx" -> "sheets"; case "csv" -> "csvRows";
            default -> null;
        };
        Map<String, String> slots = new LinkedHashMap<>();
        if (root != null && preview.get(root) != null) collect(root, preview.get(root), slots);
        return slots;
    }

    private static void collect(String path, Object node, Map<String, String> slots) {
        if (node instanceof String text) {
            slots.put(path, text);
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) collect(path + "/" + i, list.get(i), slots);
        } else if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (!STRUCTURAL_KEYS.contains(String.valueOf(key))) collect(path + "/" + key, value, slots);
            });
        }
    }

    /**
     * Writes {@code changes} (slot id → new text) into a deep copy of {@code preview}. An id that is
     * not one of {@link #slots} is rejected, so the model can't add, drop or restructure anything.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> apply(Map<String, Object> preview, Map<String, String> changes) {
        Map<String, Object> copy = (Map<String, Object>) deepCopy(preview);
        Set<String> allowed = slots(preview).keySet();
        changes.forEach((id, text) -> {
            if (!allowed.contains(id)) throw new IllegalArgumentException("Bilinmeyen metin alanı: " + id);
            String[] parts = id.split("/");
            Object parent = copy;
            for (int i = 0; i < parts.length - 1; i++) parent = child(parent, parts[i]);
            String last = parts[parts.length - 1];
            if (parent instanceof List<?> list) ((List<Object>) list).set(Integer.parseInt(last), text);
            else ((Map<String, Object>) parent).put(last, text);
        });
        return copy;
    }

    private static Object child(Object parent, String key) {
        return parent instanceof List<?> list ? list.get(Integer.parseInt(key)) : ((Map<?, ?>) parent).get(key);
    }

    private static Object deepCopy(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), deepCopy(value)));
            return copy;
        }
        if (node instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(value -> copy.add(deepCopy(value)));
            return copy;
        }
        return node;
    }
}
