package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.CreateCsvParams;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class CsvGenerator {

    public void generate(CreateCsvParams params, Path outputPath) throws IOException {
        String delimiter = ";".equals(params.getDelimiter()) ? ";" : ",";
        char sep = delimiter.charAt(0);

        CSVFormat format = CSVFormat.EXCEL.builder()
                .setDelimiter(sep)
                .setHeader(params.getColumns().toArray(String[]::new))
                .build();

        StringWriter sw = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(sw, format)) {
            for (var row : params.getRows()) {
                printer.printRecord(row);
            }
        }

        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, sw.toString(), StandardCharsets.UTF_8);
    }
}
