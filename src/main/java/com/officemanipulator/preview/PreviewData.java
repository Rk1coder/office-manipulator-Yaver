package com.officemanipulator.preview;

import java.util.List;
import java.util.Map;

public record PreviewData(
        String type,
        List<Map<String, Object>> slides,   // PPTX
        List<Map<String, Object>> blocks,   // DOCX
        List<String> lines,                 // PDF
        Integer pageCount,
        List<List<String>> csvRows,         // CSV or first XLSX sheet (legacy display)
        List<Map<String, Object>> sheets,
        String delimiter
) {
    public PreviewData(String type, List<Map<String, Object>> slides,
                       List<Map<String, Object>> blocks, List<String> lines, Integer pageCount,
                       List<List<String>> csvRows) {
        this(type, slides, blocks, lines, pageCount, csvRows, null, null);
    }
    public PreviewData(String type, List<Map<String, Object>> slides,
                       List<Map<String, Object>> blocks, List<String> lines, Integer pageCount) {
        this(type, slides, blocks, lines, pageCount, null, null, null);
    }
}
