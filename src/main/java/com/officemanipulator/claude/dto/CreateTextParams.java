package com.officemanipulator.claude.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class CreateTextParams {

    @JsonProperty("content")
    private String content;
}
