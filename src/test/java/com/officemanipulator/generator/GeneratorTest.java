package com.officemanipulator.generator;

import com.officemanipulator.claude.dto.*;
import com.officemanipulator.validator.FileValidator;
import com.officemanipulator.domain.OutputFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {

    @TempDir
    Path tempDir;

    private final NodeScriptExecutor executor = new NodeScriptExecutor();
    private final CsvGenerator csvGen  = new CsvGenerator();
    private final WordGenerator wordGen = new WordGenerator(executor);
    private final PdfGenerator pdfGen  = new PdfGenerator(executor);
    private final PresentationGenerator pptGen = new PresentationGenerator(executor);
    private final XlsxGenerator xlsxGen = new XlsxGenerator(executor);
    private final FileValidator validator = new FileValidator();

    @Test
    void csv_produces_valid_file() throws Exception {
        CreateCsvParams p = new CreateCsvParams();
        p.setDelimiter(",");
        p.setColumns(List.of("Ad", "Soyad", "Yaş"));
        p.setRows(List.of(
                List.of("Ahmet", "Yılmaz", "30"),
                List.of("Fatma", "Kaya", "25")
        ));
        Path out = tempDir.resolve("test.csv");
        csvGen.generate(p, out);
        validator.validate(out, OutputFormat.CSV);
        assertTrue(out.toFile().length() > 0);
    }

    @Test
    void word_produces_valid_docx() throws Exception {
        CreateWordParams p = new CreateWordParams();
        p.setScript("""
                const { Document, Packer, Paragraph, TextRun, HeadingLevel } = require('docx');
                (async () => {
                  const doc = new Document({
                    sections: [{ children: [
                      new Paragraph({ text: 'Test Raporu', heading: HeadingLevel.TITLE }),
                      new Paragraph({ text: '1. Giriş', heading: HeadingLevel.HEADING_1 }),
                      new Paragraph({ children: [
                        new TextRun('Bu bir test paragrafıdır. Türkçe karakter: ğüşıöçĞÜŞİÖÇ')
                      ]})
                    ]}]
                  });
                  const buf = await Packer.toBuffer(doc);
                  require('fs').writeFileSync(process.env.OUTPUT_PATH, buf);
                })();
                """);
        Path out = tempDir.resolve("test.docx");
        wordGen.generate(p, out);
        validator.validate(out, OutputFormat.DOCX);
        assertTrue(out.toFile().length() > 0);
    }

    @Test
    void pdf_produces_valid_file() throws Exception {
        CreatePdfParams p = new CreatePdfParams();
        p.setScript("""
                const { PDFDocument, rgb, StandardFonts, PageSizes } = require('pdf-lib');
                const fs = require('fs');
                (async () => {
                  const pdfDoc = await PDFDocument.create();
                  const bold = await pdfDoc.embedFont(StandardFonts.HelveticaBold);
                  const regular = await pdfDoc.embedFont(StandardFonts.Helvetica);
                  const page = pdfDoc.addPage(PageSizes.A4);
                  const { width, height } = page.getSize();
                  page.drawText('PDF Test Belgesi', {
                    x: 50, y: height - 80, size: 22,
                    font: bold, color: rgb(0.12, 0.09, 0.51)
                  });
                  page.drawText('Bolum 1: Giris', {
                    x: 50, y: height - 130, size: 16,
                    font: bold, color: rgb(0.12, 0.09, 0.51)
                  });
                  page.drawText('Test icerigi. ASCII karakterler kullanildi.', {
                    x: 50, y: height - 160, size: 11,
                    font: regular, color: rgb(0.22, 0.20, 0.29)
                  });
                  page.drawText('1', { x: width / 2, y: 20, size: 9, font: regular, color: rgb(0.5, 0.5, 0.5) });
                  const pdfBytes = await pdfDoc.save();
                  fs.writeFileSync(process.env.OUTPUT_PATH, pdfBytes);
                })();
                """);
        Path out = tempDir.resolve("test.pdf");
        pdfGen.generate(p, out);
        validator.validate(out, OutputFormat.PDF);
        assertTrue(out.toFile().length() > 0);
    }

    @Test
    void pptx_produces_valid_file() throws Exception {
        CreatePresentationParams p = new CreatePresentationParams();
        p.setScript("""
                const pptxgen = require('pptxgenjs');
                (async () => {
                  const pptx = new pptxgen();
                  pptx.layout = 'LAYOUT_WIDE';
                  const s1 = pptx.addSlide();
                  s1.addShape(pptx.ShapeType.rect, {x:0,y:0,w:13.33,h:7.5,fill:{color:'1E1B4B'}});
                  s1.addText('Test Sunumu', {x:1,y:2.5,w:11,h:1.5,fontSize:44,bold:true,color:'FFFFFF',align:'center',fontFace:'Calibri'});
                  const s2 = pptx.addSlide();
                  s2.addText('İçerik Slaytı', {x:0.5,y:0.3,w:12,h:0.8,fontSize:28,bold:true,color:'1E1B4B',fontFace:'Cambria'});
                  s2.addText([
                    {text:'Madde 1', options:{bullet:true,breakLine:true}},
                    {text:'Madde 2', options:{bullet:true,breakLine:true}},
                    {text:'Türkçe: ğüş', options:{bullet:true}}
                  ], {x:0.5,y:1.5,w:12,h:4,fontSize:16,color:'374151',fontFace:'Calibri'});
                  await pptx.writeFile({fileName: process.env.OUTPUT_PATH});
                })();
                """);
        Path out = tempDir.resolve("test.pptx");
        pptGen.generate(p, out);
        validator.validate(out, OutputFormat.PPTX);
        assertTrue(out.toFile().length() > 0);
    }

    @Test
    void xlsx_produces_valid_file() throws Exception {
        CreateXlsxParams p = new CreateXlsxParams();
        p.setScript("""
                const ExcelJS = require('exceljs');
                (async () => {
                  const wb = new ExcelJS.Workbook();
                  wb.creator = 'Test';
                  const ws = wb.addWorksheet('Rapor');
                  ws.columns = [
                    { header: 'Ürün', key: 'urun', width: 20 },
                    { header: 'Adet', key: 'adet', width: 10 },
                    { header: 'Fiyat', key: 'fiyat', width: 12 },
                  ];
                  ws.getRow(1).eachCell(cell => {
                    cell.font = { bold: true, color: { argb: 'FFFFFFFF' }, name: 'Arial', size: 11 };
                    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF312E81' } };
                    cell.alignment = { horizontal: 'center' };
                  });
                  ws.getRow(1).height = 28;
                  ws.views = [{ state: 'frozen', xSplit: 0, ySplit: 1 }];
                  ws.addRow({ urun: 'Elma', adet: 100, fiyat: 5.5 });
                  ws.addRow({ urun: 'Armut', adet: 200, fiyat: 3.75 });
                  const total = ws.addRow({ urun: 'TOPLAM', adet: { formula: 'SUM(B2:B3)' }, fiyat: { formula: 'SUM(C2:C3)' } });
                  total.font = { bold: true };
                  ws.getColumn('fiyat').numFmt = '"₺"#,##0.00';
                  ws.autoFilter = { from: 'A1', to: 'C1' };
                  await wb.xlsx.writeFile(process.env.OUTPUT_PATH);
                })();
                """);
        Path out = tempDir.resolve("test.xlsx");
        xlsxGen.generate(p, out);
        validator.validate(out, OutputFormat.XLSX);
        assertTrue(out.toFile().length() > 0);
    }

    @Test
    void csv_with_semicolon_delimiter() throws Exception {
        CreateCsvParams p = new CreateCsvParams();
        p.setDelimiter(";");
        p.setColumns(List.of("Ürün", "Fiyat"));
        p.setRows(List.of(List.of("Elma", "5,50"), List.of("Armut", "3,00")));
        Path out = tempDir.resolve("semicolon.csv");
        csvGen.generate(p, out);
        String content = java.nio.file.Files.readString(out);
        assertTrue(content.contains(";"));
    }

    @Test
    void word_with_table() throws Exception {
        CreateWordParams p = new CreateWordParams();
        p.setScript("""
                const { Document, Packer, Paragraph, TextRun, HeadingLevel,
                        Table, TableRow, TableCell, WidthType, ShadingType } = require('docx');
                (async () => {
                  const doc = new Document({
                    sections: [{ children: [
                      new Paragraph({ text: 'Tablo Raporu', heading: HeadingLevel.TITLE }),
                      new Paragraph({ text: 'Veriler', heading: HeadingLevel.HEADING_1 }),
                      new Table({
                        columnWidths: [4500, 4500],
                        rows: [
                          new TableRow({ children: [
                            new TableCell({ width:{size:4500,type:WidthType.DXA},
                              shading:{type:ShadingType.CLEAR,color:'auto',fill:'312E81'},
                              children:[new Paragraph({children:[new TextRun({text:'Sütun A',color:'FFFFFF',bold:true})]})] }),
                            new TableCell({ width:{size:4500,type:WidthType.DXA},
                              shading:{type:ShadingType.CLEAR,color:'auto',fill:'312E81'},
                              children:[new Paragraph({children:[new TextRun({text:'Sütun B',color:'FFFFFF',bold:true})]})] }),
                          ]}),
                          new TableRow({ children: [
                            new TableCell({ width:{size:4500,type:WidthType.DXA}, children:[new Paragraph('1')] }),
                            new TableCell({ width:{size:4500,type:WidthType.DXA}, children:[new Paragraph('2')] }),
                          ]}),
                        ]
                      })
                    ]}]
                  });
                  const buf = await Packer.toBuffer(doc);
                  require('fs').writeFileSync(process.env.OUTPUT_PATH, buf);
                })();
                """);
        Path out = tempDir.resolve("table.docx");
        wordGen.generate(p, out);
        validator.validate(out, OutputFormat.DOCX);
    }
}
