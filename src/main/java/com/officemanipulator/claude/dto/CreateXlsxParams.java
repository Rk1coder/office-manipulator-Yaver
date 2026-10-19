package com.officemanipulator.claude.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateXlsxParams {

    /** Complete exceljs Node.js script that writes the XLSX to OUTPUT_PATH env var */
    @JsonProperty("script")
    private String script;
    private java.util.List<Chart> charts = java.util.List.of();
    public record Chart(String sheet, String title, String categories, String values, String type) {}
}
