package com.officemanipulator.claude;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    public List<Tool> allTools() {
        return List.of(
                buildCreateWord(),
                buildCreatePresentation(),
                buildCreatePdf(),
                buildCreateCsv(),
                buildCreateXlsx(),
                buildCreateText(),
                buildCreateOdb()
        );
    }

    public Tool planTool(String format) {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> nullableText = Map.of("type", List.of("string", "null"));
        Map<String, Object> item = objectSchema(Map.of(
                "order", Map.of("type", "integer"), "type", Map.of("type", "string", "enum", List.of("slide", "section", "sheet")),
                "title", text, "summary", text, "content", text, "speakerNotes", nullableText,
                "imageNeeded", Map.of("type", "boolean"), "imagePrompt", nullableText));
        Map<String, Object> properties = Map.of(
                "title", text, "format", Map.of("type", "string", "enum", List.of(format.toLowerCase(java.util.Locale.ROOT))),
                "language", text, "colorPalette", objectSchema(Map.of("primary", text, "accent", text, "background", text)),
                "outline", Map.of("type", "array", "items", item), "estimatedSlides", Map.of("type", "integer"));
        var builder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return Tool.builder().name("submit_plan").description("Submit the complete document plan using this schema.").strict(true)
                .inputSchema(Tool.InputSchema.builder().properties(builder.build()).required(List.copyOf(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build()).build();
    }

    /** Design-director tool: Claude's first-pass call for a presentation, choosing ONLY the visual
     * and narrative direction — never slide content. See SystemPrompts.DESIGN_DIRECTOR. */
    public Tool designDirectionTool() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> boolField = Map.of("type", "boolean");
        Map<String, Object> density = Map.of("type", "string", "enum", List.of("low", "medium", "high"));
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        properties.put("designDirection", text);
        properties.put("narrativeStyle", text);
        properties.put("layoutStrategy", text);
        properties.put("visualDensity", density);
        properties.put("textDensity", density);
        properties.put("accentUsage", Map.of("type", "string", "enum", List.of("sparse", "medium", "bold")));
        properties.put("heroSlides", Map.of("type", "array", "items", Map.of("type", "integer")));
        properties.put("preferredLayouts", Map.of("type", "array", "items", Map.of("type", "string", "enum", SlidePattern.names())));
        properties.put("useLargeNumbers", boolField);
        properties.put("useQuoteSlides", boolField);
        properties.put("useComparisonSlides", boolField);
        properties.put("imageStyle", text);
        properties.put("chartStyle", text);
        properties.put("maxBulletSlideRatio", Map.of("type", "number"));
        properties.put("reason", text);
        var builder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return Tool.builder().name("submit_design_direction")
                .description("Submit ONLY the visual and narrative design direction for this presentation — no titles, bullets, or any slide content.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder().properties(builder.build()).required(List.copyOf(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                .build();
    }

    /** Yaver in-editor chat panel's edit call: returns both the rewritten text AND structured
     * formatting instructions the ONLYOFFICE bridge can apply via its internal document API. */
    public Tool documentEditTool() {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> nullableText = Map.of("type", List.of("string", "null"));
        Map<String, Object> nullableBool = Map.of("type", List.of("boolean", "null"));
        Map<String, Object> nullableNumber = Map.of("type", List.of("number", "null"));
        List<Object> highlightValues = new java.util.ArrayList<>(List.of(
                "black", "blue", "cyan", "darkBlue", "darkCyan", "darkGray", "darkGreen", "darkMagenta",
                "darkRed", "darkYellow", "green", "lightGray", "magenta", "none", "red", "white", "yellow"));
        highlightValues.add(null);
        Map<String, Object> nullableHighlight = Map.of("type", List.of("string", "null"), "enum", highlightValues);
        Map<String, Object> formattingProperties = Map.of(
                "bold", nullableBool, "italic", nullableBool, "underline", nullableBool,
                "color", nullableText, "highlightColor", nullableHighlight,
                "fontFamily", nullableText, "fontSize", nullableNumber);
        Map<String, Object> formatting = Map.of("type", "object", "properties", formattingProperties,
                "required", List.copyOf(formattingProperties.keySet()), "additionalProperties", false);
        Map<String, Object> properties = Map.of("text", text, "formatting", formatting);
        var builder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return Tool.builder().name("submit_document_edit")
                .description("Submit the rewritten text and any requested visual formatting for the selected fragment.")
                .strict(true)
                .inputSchema(Tool.InputSchema.builder().properties(builder.build()).required(List.copyOf(properties.keySet()))
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false)).build())
                .build();
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    private Tool buildCreateWord() {
        return Tool.builder()
                .name("create_word")
                .description("""
                        Create a professional Word (.docx) document by writing a complete docx (npm) Node.js script.
                        The script MUST write the file to: process.env.OUTPUT_PATH

                        docx npm API essentials:
                        const { Document, Packer, Paragraph, TextRun, HeadingLevel, Table, TableRow,
                                TableCell, WidthType, ShadingType, AlignmentType, BorderStyle,
                                ImageRun, PageBreak, NumberingLevel, LevelFormat } = require('docx');
                        const doc = new Document({ sections: [{ children: [...] }] });
                        const buf = await Packer.toBuffer(doc);
                        require('fs').writeFileSync(process.env.OUTPUT_PATH, buf);

                        CRITICAL GOTCHAS (violations produce corrupt files or wrong output):
                        - Never use '\\n' — use separate Paragraph elements
                        - PageBreak must be inside a Paragraph
                        - Lists: use a numbering config with LevelFormat.BULLET — never literal '•'
                        - Table shading: ShadingType.CLEAR only, never SOLID (renders black)
                        - Tables need BOTH columnWidths on the table AND width on every cell (WidthType.DXA)
                        - Column widths must sum to the table width
                        - ImageRun requires type: "png" or "jpg"
                        - Headings: use HeadingLevel.HEADING_1/2/3 for TOC compatibility

                        CONTENT QUALITY RULES:
                        - Follow the requested language, length and structure. Add bilingual abstracts or references ONLY when requested and supported by actual sources.
                        - Write full substantive paragraphs (3–5 sentences) — not one-liners
                        - Use HeadingLevel.TITLE for the document title (large, centered)
                        - Use shaded Table cells (light blue/indigo tint) for callout boxes and key findings
                        - Tables for ALL comparisons, benchmarks, specs, model lists
                        - Never invent citations, dates, names, measurements or causal explanations. Distinguish observations from proposals.
                        - The script must produce output indistinguishable from a professional consultant's report
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("script", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Complete Node.js docx (npm) script. Must write to process.env.OUTPUT_PATH."
                                )))
                                .build())
                        .required(List.of("script"))
                        .build())
                .build();
    }

    private Tool buildCreatePresentation() {
        return Tool.builder()
                .name("create_presentation")
                .description("""
                        Create a visually stunning PowerPoint (.pptx) presentation by writing a complete pptxgenjs Node.js script.
                        The script MUST write the file to: process.env.OUTPUT_PATH

                        pptxgenjs API reference:
                        - const pptx = new pptxgen(); pptx.layout = 'LAYOUT_WIDE'; // 13.3x7.5 inches
                        - const slide = pptx.addSlide();
                        - slide.addText("text", {x, y, w, h, fontSize, bold, color, align, fontFace});
                        - slide.addShape(pptx.ShapeType.rect, {x, y, w, h, fill:{color}, line:{color,width}});
                        - slide.addImage({path, x, y, w, h}); // or data: base64
                        - await pptx.writeFile({fileName: process.env.OUTPUT_PATH});

                        CRITICAL RULES (violations corrupt the file):
                        - Colors: 6-digit hex ONLY, NEVER '#' prefix. "FF0000" ✓  "#FF0000" ✗
                        - Never share options objects across addText/addShape calls (pptxgenjs mutates them)
                        - Bullet lists: use bullet:true on each text array item with breakLine:true, never literal '•'
                        - Use await pptx.writeFile() NOT pptx.writeFile() — it's async
                        - Script must end with: pptx.writeFile({fileName: process.env.OUTPUT_PATH})

                        DESIGN RULES (produce stunning slides):
                        - Commit to a bold color palette (not generic blue); pick colors that fit the topic
                        - DARK backgrounds for title/conclusion slides; light for content slides
                        - Every slide needs at least one visual element: shape, icon row, stat callout, or chart
                        - NEVER add decorative stripes/color bars along slide edges
                        - NEVER use Aptos font; use Calibri (body), Cambria (headers)
                        - Titles: 30-36pt bold; Body: 20-24pt; Captions: at least 14pt. Move detail into speaker notes.
                        - Vary layouts: two-column, card grids, stat callouts, comparison tables, flow diagrams
                        - For data: use large stat callouts (60-72pt numbers) or native pptx charts
                        - Leave 0.5" margins; 0.3-0.5" gaps between content blocks
                        - Respect the exact slide count requested, including the cover and conclusion.
                        - Pick each slide's pattern from the fixed vocabulary in the system prompt and tag
                          it with a `// LAYOUT: <PATTERN>` comment right before that slide's addSlide() call
                          — do not default every slide to TITLE_BULLETS (see the anti-repetition/anti-bullet
                          rules in the system prompt; violations are rejected and sent back for revision).
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("script", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Complete Node.js pptxgenjs script. Must write to process.env.OUTPUT_PATH using await pptx.writeFile()."
                                )))
                                .build())
                        .required(List.of("script"))
                        .build())
                .build();
    }

    private Tool buildCreatePdf() {
        return Tool.builder()
                .name("create_pdf")
                .description("""
                        Create a professional PDF document by writing a complete pdf-lib Node.js script.
                        The script MUST write the file to: process.env.OUTPUT_PATH

                        pdf-lib API essentials:
                        const { PDFDocument, rgb, StandardFonts, PageSizes } = require('pdf-lib');
                        const pdfDoc = await PDFDocument.create();
                        const helvetica = await pdfDoc.embedFont(StandardFonts.Helvetica);
                        const helveticaBold = await pdfDoc.embedFont(StandardFonts.HelveticaBold);
                        const page = pdfDoc.addPage(PageSizes.A4); // [595.28, 841.89] points
                        const { width, height } = page.getSize();
                        // y=0 is BOTTOM of page; text baseline at y means top of text is higher
                        page.drawText('Hello', { x: 50, y: height-80, size: 24, font: helveticaBold, color: rgb(0.12, 0.09, 0.51) });
                        page.drawRectangle({ x: 40, y: height-110, width: width-80, height: 30, color: rgb(0.93, 0.94, 1.0) });
                        const pdfBytes = await pdfDoc.save();
                        require('fs').writeFileSync(process.env.OUTPUT_PATH, pdfBytes);

                        COORDINATE SYSTEM: y=0 is BOTTOM of page. A4 = 595×842pt. 72pt = 1 inch.
                        For text at top: y = height - margin - fontSize

                        FONT SUPPORT: preserve Turkish/Unicode with embedded TTF fonts. Never transliterate.
                        pdfDoc.registerFontkit(require('@pdf-lib/fontkit'));
                        const regular = await pdfDoc.embedFont(require('fs').readFileSync(process.env.TURKISH_FONT_REGULAR));
                        const bold = await pdfDoc.embedFont(require('fs').readFileSync(process.env.TURKISH_FONT_BOLD));
                        Measure wrapped text before drawing; create a new page before reaching the bottom margin.
                        Keep headings with the following paragraph and size callout boxes from actual text height.

                        DESIGN RULES:
                        - Use drawRectangle with light fill (rgb(0.93, 0.94, 1.0)) for section headers
                        - Use rgb(0.12, 0.09, 0.51) for title/heading text (deep indigo)
                        - Use rgb(0.26, 0.22, 0.76) for subheadings
                        - Use rgb(0.22, 0.20, 0.29) for body text
                        - Draw horizontal lines (drawLine) between sections
                        - For tables: draw rectangles for cells, bold font for header row with colored background
                        - Add page numbers at bottom: page.drawText(`${i+1}`, {x: width/2, y: 20, ...})
                        - Implement multi-page logic: track current y position, call pdfDoc.addPage() when y < 80
                        - For callout boxes: drawRectangle with colored left border (drawLine) and light fill
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("script", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Complete Node.js pdf-lib script. Must write to process.env.OUTPUT_PATH."
                                )))
                                .build())
                        .required(List.of("script"))
                        .build())
                .build();
    }

    private Tool buildCreateXlsx() {
        return Tool.builder()
                .name("create_xlsx")
                .description("""
                        Create a professional Excel (.xlsx) spreadsheet by writing a complete exceljs Node.js script.
                        The script MUST write the file to: process.env.OUTPUT_PATH

                        exceljs API reference:
                        const ExcelJS = require('exceljs');
                        const wb = new ExcelJS.Workbook();
                        wb.creator = 'Office Manipulator';
                        const ws = wb.addWorksheet('Sheet Name');

                        // Column definitions (set width + header):
                        ws.columns = [
                          { header: 'Name', key: 'name', width: 20 },
                          { header: 'Value', key: 'value', width: 12 },
                        ];

                        // Add rows (by key or as array):
                        ws.addRow({ name: 'Ahmet', value: 42 });
                        ws.addRow([null, null]); // spacer

                        // Cell access and styling:
                        const cell = ws.getCell('A1');
                        cell.value = 'Header';
                        cell.font = { bold: true, size: 12, color: { argb: 'FFFFFFFF' }, name: 'Arial' };
                        cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF312E81' } };
                        cell.alignment = { horizontal: 'center', vertical: 'middle', wrapText: true };
                        cell.border = { bottom: { style: 'thin', color: { argb: 'FFD1D5DB' } } };

                        // Formulas:
                        ws.getCell('C10').value = { formula: 'SUM(C2:C9)' };

                        // Merge cells:
                        ws.mergeCells('A1:D1');

                        // Row height:
                        ws.getRow(1).height = 30;

                        // Freeze panes:
                        ws.views = [{ state: 'frozen', xSplit: 0, ySplit: 1 }];

                        // Auto-filter:
                        ws.autoFilter = { from: 'A1', to: 'D1' };

                        await wb.xlsx.writeFile(process.env.OUTPUT_PATH);

                        CHARTS: ExcelJS cannot create charts: NEVER call wb.addChart or sheet.addChart.
                        Return requested editable charts in the charts tool parameter; Java adds native charts after the script.
                        Each chart: {sheet:"Veriler",title:"Haftalık atık",categories:"A2:A7",values:"C2:C7",type:"line"}.
                        Ranges must be single columns on the named worksheet, same length, excluding headers/totals.
                        Use numeric source values or supported formulas. Do not replace requested charts with a table.
                        CRITICAL RULES:
                        - Colors: ARGB format with FF alpha prefix. "FF312E81" ✓  "#312E81" ✗  "312E81" ✗
                        - Always use Arial or Times New Roman as fontFace
                        - Formulas: use SUM, AVERAGE, IF, SUMIF, COUNTIF, INDEX/MATCH — never XLOOKUP
                        - Percentages: store as decimals (0.15 → displays 15% with numFmt:'0.0%')
                        - Numbers with commas: numFmt: '#,##0' or '#,##0.00'
                        - Currency: numFmt: '"₺"#,##0.00' or '"$"#,##0.00'

                        DESIGN RULES (professional spreadsheet):
                        - Header row: bold white text on deep indigo fill (FF312E81), height 28, frozen
                        - Data rows: alternate light fill (FFEFF6FF / FFFFFFFF) for readability
                        - Totals row: bold, top border (medium), indigo text color (FF312E81)
                        - Key assumption cells: yellow fill (FFFFF3CD) — cells the user should edit
                        - Input cells: blue font (FF1D4ED8) to distinguish from formula cells (black)
                        - Column widths: set explicitly — never leave at default 8
                        - Add auto-filter to all data tables
                        - Multiple logical sections → multiple worksheets with descriptive names
                        - Financial models: every hardcoded number in its own labeled assumption cell,
                          referenced by formulas (=B5*(1+$B$6)), never inline literals
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("script", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "Complete Node.js exceljs script. Must write to process.env.OUTPUT_PATH using await wb.xlsx.writeFile()."
                                )))
                                .putAdditionalProperty("charts", JsonValue.from(Map.of("type", "array", "items", objectSchema(Map.of(
                                        "sheet", Map.of("type", "string"), "title", Map.of("type", "string"),
                                        "categories", Map.of("type", "string"), "values", Map.of("type", "string"),
                                        "type", Map.of("type", "string", "enum", List.of("line", "bar")))))))
                                .build())
                        .required(List.of("script"))
                        .build())
                .build();
    }

    private Tool buildCreateCsv() {
        return Tool.builder()
                .name("create_csv")
                .description("Create a CSV file with columns and rows.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("delimiter", JsonValue.from(Map.of("type", "string", "enum", List.of(",", ";"))))
                                .putAdditionalProperty("columns", JsonValue.from(Map.of("type", "array", "items", Map.of("type", "string"))))
                                .putAdditionalProperty("rows", JsonValue.from(Map.of("type", "array", "items", Map.of("type", "array", "items", Map.of("type", "string")))))
                                .build())
                        .required(List.of("columns", "rows"))
                        .build())
                .build();
    }

    private Tool buildCreateText() {
        return Tool.builder()
                .name("create_text")
                .description("""
                        Create a plain-text file (.txt, .md, .json, or .xml) by writing its exact final content.
                        This is NOT a script — "content" is the literal file content that will be written as-is.

                        - For TXT: plain prose, no markup.
                        - For MD: valid, well-structured Markdown (headings, lists, tables, code fences as needed).
                        - For JSON: a single syntactically valid JSON document (object or array), no comments, no trailing commas.
                        - For XML: a single well-formed XML document with a root element and a valid prolog if needed.
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("content", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The complete, final file content, written verbatim."
                                )))
                                .build())
                        .required(List.of("content"))
                        .build())
                .build();
    }

    private Tool buildCreateOdb() {
        return Tool.builder()
                .name("create_odb")
                .description("""
                        Create an experimental LibreOffice Base (.odb) database file with a single table.
                        Same shape as create_csv: one table named DATA_TABLE with the given columns and rows.
                        This format has limited compatibility — keep columns/values simple text.
                        """)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("columns", JsonValue.from(Map.of("type", "array", "items", Map.of("type", "string"))))
                                .putAdditionalProperty("rows", JsonValue.from(Map.of("type", "array", "items", Map.of("type", "array", "items", Map.of("type", "string")))))
                                .build())
                        .required(List.of("columns", "rows"))
                        .build())
                .build();
    }
}
