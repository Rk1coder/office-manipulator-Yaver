package com.officemanipulator.claude.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreatePresentationParams {

    /** Complete pptxgenjs Node.js script that writes the PPTX to OUTPUT_PATH env var */
    @JsonProperty("script")
    private String script;
}
