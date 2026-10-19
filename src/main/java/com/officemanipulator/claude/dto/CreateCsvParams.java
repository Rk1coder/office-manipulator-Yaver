package com.officemanipulator.claude.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class CreateCsvParams {

    @JsonProperty("delimiter")
    private String delimiter = ",";

    @JsonProperty("columns")
    private List<String> columns;

    @JsonProperty("rows")
    private List<List<String>> rows;
}
