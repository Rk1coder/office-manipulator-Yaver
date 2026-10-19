package com.officemanipulator.claude.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateWordParams {

    /** Complete docx (npm) Node.js script that writes the DOCX to OUTPUT_PATH env var */
    @JsonProperty("script")
    private String script;
}
