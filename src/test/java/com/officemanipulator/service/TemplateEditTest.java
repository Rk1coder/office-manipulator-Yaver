package com.officemanipulator.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemplateEditTest {
    private static Map<String, Object> docx() {
        return Map.of("type", "docx", "blocks", List.of(
                Map.of("type", "h1", "text", "Başlık"),
                Map.of("type", "table", "rows", List.of(List.of("a", "b"), List.of("c", "d")))));
    }

    @Test void slotsCoverTextAndCellsButNotStructure() {
        var slots = TemplateEdit.slots(docx());
        assertEquals(List.of("blocks/0/text", "blocks/1/rows/0/0", "blocks/1/rows/0/1", "blocks/1/rows/1/0", "blocks/1/rows/1/1"),
                new ArrayList<>(slots.keySet()));
        assertEquals("Başlık", slots.get("blocks/0/text"));
        assertFalse(slots.containsKey("blocks/0/type"));
    }

    @Test void applyWritesOnlyKnownSlotsIntoACopy() {
        var original = docx();
        var patched = TemplateEdit.apply(original, Map.of("blocks/0/text", "Yeni", "blocks/1/rows/1/0", "x"));
        assertEquals("Yeni", TemplateEdit.slots(patched).get("blocks/0/text"));
        assertEquals("x", TemplateEdit.slots(patched).get("blocks/1/rows/1/0"));
        assertEquals("Başlık", TemplateEdit.slots(original).get("blocks/0/text"), "original must not change");
        for (String bad : new String[]{"blocks/0/type", "blocks/2/text", "blocks/1/rows/2/0", "../x"}) {
            assertThrows(IllegalArgumentException.class, () -> TemplateEdit.apply(original, Map.of(bad, "z")), bad);
        }
    }

    @Test void onlyThePatchableRootOfEachTypeIsSlotted() {
        var xlsx = Map.<String, Object>of("type", "xlsx", "sheets", List.of(Map.of("name", "S1", "rows", List.of(List.of("v")))),
                "csvRows", List.of(List.of("v")));
        assertEquals(List.of("sheets/0/rows/0/0"), new ArrayList<>(TemplateEdit.slots(xlsx).keySet()));
        assertTrue(TemplateEdit.slots(Map.of("type", "pdf", "lines", List.of("text"))).isEmpty());
    }
}
