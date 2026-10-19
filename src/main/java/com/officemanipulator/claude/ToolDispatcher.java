package com.officemanipulator.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.dto.*;
import com.officemanipulator.generator.*;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * Routes a Claude tool-use call to the appropriate generator.
 * Extracted from ClaudeOrchestrator to give each class a single responsibility.
 */
@Component
public class ToolDispatcher {

    private final ObjectMapper objectMapper;
    private final WordGenerator wordGenerator;
    private final PresentationGenerator presentationGenerator;
    private final PdfGenerator pdfGenerator;
    private final CsvGenerator csvGenerator;
    private final XlsxGenerator xlsxGenerator;
    private final PlainTextGenerator plainTextGenerator;
    private final OdbGenerator odbGenerator;

    public ToolDispatcher(
            ObjectMapper objectMapper,
            WordGenerator wordGenerator,
            PresentationGenerator presentationGenerator,
            PdfGenerator pdfGenerator,
            CsvGenerator csvGenerator,
            XlsxGenerator xlsxGenerator,
            PlainTextGenerator plainTextGenerator,
            OdbGenerator odbGenerator
    ) {
        this.objectMapper        = objectMapper;
        this.wordGenerator       = wordGenerator;
        this.presentationGenerator = presentationGenerator;
        this.pdfGenerator        = pdfGenerator;
        this.csvGenerator        = csvGenerator;
        this.xlsxGenerator       = xlsxGenerator;
        this.plainTextGenerator  = plainTextGenerator;
        this.odbGenerator        = odbGenerator;
    }

    /**
     * @return a human-readable success message passed back to Claude as the tool result
     */
    public String dispatch(String toolName, String inputJson, Path outputPath) throws Exception {
        return dispatch(toolName, inputJson, outputPath, Map.of());
    }

    public String dispatch(String toolName, String inputJson, Path outputPath, Map<String, String> extraEnv) throws Exception {
        return switch (toolName) {
            case "create_word" -> {
                wordGenerator.generate(objectMapper.readValue(inputJson, CreateWordParams.class), outputPath, extraEnv);
                yield "Word document created successfully.";
            }
            case "create_presentation" -> {
                presentationGenerator.generate(objectMapper.readValue(inputJson, CreatePresentationParams.class), outputPath, extraEnv);
                yield "Presentation created successfully.";
            }
            case "create_pdf" -> {
                pdfGenerator.generate(objectMapper.readValue(inputJson, CreatePdfParams.class), outputPath, extraEnv);
                yield "PDF created successfully.";
            }
            case "create_csv" -> {
                CreateCsvParams p = objectMapper.readValue(inputJson, CreateCsvParams.class);
                csvGenerator.generate(p, outputPath);
                yield "CSV created: " + p.getColumns().size() + " columns, " + p.getRows().size() + " rows.";
            }
            case "create_xlsx" -> {
                xlsxGenerator.generate(objectMapper.readValue(inputJson, CreateXlsxParams.class), outputPath);
                yield "XLSX spreadsheet created successfully.";
            }
            case "create_text" -> {
                plainTextGenerator.generate(objectMapper.readValue(inputJson, CreateTextParams.class), outputPath);
                yield "Text file created successfully.";
            }
            case "create_odb" -> {
                CreateCsvParams p = objectMapper.readValue(inputJson, CreateCsvParams.class);
                odbGenerator.generate(p, outputPath);
                yield "ODB database file created (experimental): " + p.getColumns().size() + " columns, " + p.getRows().size() + " rows.";
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }
}
