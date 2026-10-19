package com.officemanipulator.claude.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreatePdfParams {

    /** Complete pdf-lib Node.js script that writes the PDF to OUTPUT_PATH env var */
    @JsonProperty("script")
    private String script;
}
