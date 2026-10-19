package com.officemanipulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.domain.*;
import com.officemanipulator.preview.*;
import com.officemanipulator.service.DirectPatchService;
import com.officemanipulator.validator.FileValidator;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DocumentIntegrityRegressionTest {
 @TempDir Path tmp;
 final PreviewService preview=new PreviewService();
 final DirectPatchService patch=new DirectPatchService();
 PreviewData read(Path p,OutputFormat f)throws Exception {Job j=new Job();j.setFormat(f);j.setFilePath(p.toString());return preview.extract(j);}
 Map<String,Object> payload(PreviewData p){return new ObjectMapper().convertValue(p,Map.class);}
 void write(XWPFDocument doc,Path p)throws Exception {try(var os=Files.newOutputStream(p)){doc.write(os);}}
 @Test void duplicateHeadersAndMultilineCsvPreserveAllRowsAndNoopBytes()throws Exception {
  Path in=tmp.resolve("in.csv"),out=tmp.resolve("out.csv");StringBuilder s=new StringBuilder("\uFEFFname;name;\r\n");for(int i=0;i<100;i++)s.append(i).append(";\"line one\nline two\";end\r\n");Files.writeString(in,s);
  var p=read(in,OutputFormat.CSV);assertEquals(101,p.csvRows().size());assertEquals(List.of("name","name",""),p.csvRows().getFirst());
  assertDoesNotThrow(()->new FileValidator().validate(in,OutputFormat.CSV));patch.applyPatch(in,out,"csv",payload(p));assertArrayEquals(Files.readAllBytes(in),Files.readAllBytes(out));
 }
 @Test void changedDocxTextKeepsUnaffectedRunsAndStyles()throws Exception {
  Path in=tmp.resolve("in.docx"),out=tmp.resolve("out.docx");try(var d=new XWPFDocument()){var p=d.createParagraph();var a=p.createRun();a.setBold(true);a.setText("Prefix ");var b=p.createRun();b.setText("old");var c=p.createRun();c.setItalic(true);c.setText(" suffix");write(d,in);}
  var data=payload(read(in,OutputFormat.DOCX));((List<Map<String,Object>>)data.get("blocks")).getFirst().put("text","Prefix new suffix");patch.applyPatch(in,out,"docx",data);
  try(var d=new XWPFDocument(Files.newInputStream(out))){var runs=d.getParagraphs().getFirst().getRuns();assertEquals("Prefix new suffix",d.getParagraphs().getFirst().getText());assertEquals("Prefix ",runs.getFirst().text());assertTrue(runs.getFirst().isBold());assertEquals(" suffix",runs.getLast().text());assertTrue(runs.getLast().isItalic());}
 }
 @Test void changingDocxParagraphWithTabFailsWithoutDamagingOriginal()throws Exception {
  Path in=tmp.resolve("tab.docx"),out=tmp.resolve("out.docx");try(var d=new XWPFDocument()){var r=d.createParagraph().createRun();r.setText("before");r.addTab();r.setText("after");write(d,in);}byte[] bytes=Files.readAllBytes(in);
  var data=payload(read(in,OutputFormat.DOCX));((List<Map<String,Object>>)data.get("blocks")).getFirst().put("text","before\tchanged");assertThrows(IllegalArgumentException.class,()->patch.applyPatch(in,out,"docx",data));assertArrayEquals(bytes,Files.readAllBytes(in));assertFalse(Files.exists(out));
 }
 @Test void changingMultiparagraphDocxCellFailsExplicitly()throws Exception {
  Path in=tmp.resolve("table.docx");try(var d=new XWPFDocument()){var c=d.createTable(1,1).getRow(0).getCell(0);c.setText("first");c.addParagraph().createRun().setText("second");write(d,in);}
  var data=payload(read(in,OutputFormat.DOCX));((List<Map<String,Object>>)data.get("blocks")).getFirst().put("rows",List.of(List.of("changed")));assertThrows(IllegalArgumentException.class,()->patch.applyPatch(in,tmp.resolve("out.docx"),"docx",data));
 }
 @Test void xlsxNoopIsExactAndEditingOtherSheetPreservesFormulaAndNumberStyle()throws Exception {
  Path in=tmp.resolve("in.xlsx"),noop=tmp.resolve("noop.xlsx"),out=tmp.resolve("out.xlsx");try(var w=new XSSFWorkbook()){var row=w.createSheet("First").createRow(0);var c=row.createCell(0);c.setCellValue(12.3456);var style=w.createCellStyle();style.setDataFormat(w.createDataFormat().getFormat("0.00"));c.setCellStyle(style);row.createCell(1).setCellFormula("A1*2");w.createSheet("Second").createRow(0).createCell(0).setCellValue("old");try(var os=Files.newOutputStream(in)){w.write(os);}}
  var data=payload(read(in,OutputFormat.XLSX));patch.applyPatch(in,noop,"xlsx",data);assertArrayEquals(Files.readAllBytes(in),Files.readAllBytes(noop));
  ((List<Map<String,Object>>)data.get("sheets")).get(1).put("rows",List.of(List.of("new")));patch.applyPatch(in,out,"xlsx",data);try(var w=new XSSFWorkbook(Files.newInputStream(out))){assertEquals(12.3456,w.getSheetAt(0).getRow(0).getCell(0).getNumericCellValue());assertEquals("0.00",w.getSheetAt(0).getRow(0).getCell(0).getCellStyle().getDataFormatString());assertEquals("A1*2",w.getSheetAt(0).getRow(0).getCell(1).getCellFormula());assertEquals("new",w.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());}
 }
 @Test void changedMultilineCsvPreservesDuplicateColumnsAndAllRecords()throws Exception {
  Path in=tmp.resolve("multi.csv"),out=tmp.resolve("out.csv");Files.writeString(in,"\uFEFFname;name\r\n\"first\nsecond\";old\r\nlast;untouched\r\n");
  var data=payload(read(in,OutputFormat.CSV));((List<List<String>>)data.get("csvRows")).get(1).set(1,"new");patch.applyPatch(in,out,"csv",data);var result=read(out,OutputFormat.CSV);assertEquals(List.of(List.of("name","name"),List.of("first\nsecond","new"),List.of("last","untouched")),result.csvRows());assertTrue(Files.readString(out).startsWith("\uFEFF"));
 }
 @Test void pptxChangedTextKeepsUnaffectedFormatting()throws Exception {
  Path in=tmp.resolve("style.pptx"),out=tmp.resolve("out.pptx");try(var d=new XMLSlideShow()){var shape=d.createSlide().createTextBox();shape.clearText();var p=shape.addNewTextParagraph();var a=p.addNewTextRun();a.setText("Prefix ");a.setBold(true);p.addNewTextRun().setText("old");var b=p.addNewTextRun();b.setText(" suffix");b.setItalic(true);try(var os=Files.newOutputStream(in)){d.write(os);}}
  var data=payload(read(in,OutputFormat.PPTX));((List<Map<String,Object>>)data.get("slides")).getFirst().put("title","Prefix new suffix");patch.applyPatch(in,out,"pptx",data);try(var d=new XMLSlideShow(Files.newInputStream(out))){var shape=(XSLFTextShape)d.getSlides().getFirst().getShapes().getFirst();var runs=shape.getTextParagraphs().getFirst().getTextRuns();assertEquals("Prefix new suffix",shape.getText());assertEquals("Prefix ",runs.getFirst().getRawText());assertTrue(runs.getFirst().isBold());assertTrue(runs.getLast().isItalic());}
 }
 @Test void pptxNewNotesMustNotBeSilentlyIgnored()throws Exception {
  Path in=tmp.resolve("notes.pptx");try(var d=new XMLSlideShow()){d.createSlide().createTextBox().setText("Title");try(var os=Files.newOutputStream(in)){d.write(os);}}var data=payload(read(in,OutputFormat.PPTX));((List<Map<String,Object>>)data.get("slides")).getFirst().put("notes","new notes");assertThrows(IllegalArgumentException.class,()->patch.applyPatch(in,tmp.resolve("out.pptx"),"pptx",data));
 }
 @Test void missingTextIsRejectedInsteadOfBecomingLiteralNull()throws Exception {
  Path in=tmp.resolve("text.docx");try(var d=new XWPFDocument()){d.createParagraph().createRun().setText("keep me");write(d,in);}var p=read(in,OutputFormat.DOCX);var data=payload(p);((List<Map<String,Object>>)data.get("blocks")).getFirst().remove("text");assertFalse(patch.canPatch(p,data));
 }
 @Test void csvRowsCanBeAddedWithoutRegeneration()throws Exception {
  Path in=tmp.resolve("rows.csv"),out=tmp.resolve("out.csv");Files.writeString(in,"id,name\n1,one\n");var p=read(in,OutputFormat.CSV);var data=payload(p);((List<List<String>>)data.get("csvRows")).add(List.of("2","two"));assertTrue(patch.canPatch(p,data));patch.applyPatch(in,out,"csv",data);assertEquals(3,read(out,OutputFormat.CSV).csvRows().size());
 }
 @Test void editsOnBothSidesOfStyledRunKeepItsFormatting()throws Exception {
  Path in=tmp.resolve("middle.docx"),out=tmp.resolve("out.docx");try(var d=new XWPFDocument()){var p=d.createParagraph();p.createRun().setText("old ");var r=p.createRun();r.setBold(true);r.setText("KEEP");p.createRun().setText(" old");write(d,in);}var data=payload(read(in,OutputFormat.DOCX));((List<Map<String,Object>>)data.get("blocks")).getFirst().put("text","new KEEP new");patch.applyPatch(in,out,"docx",data);try(var d=new XWPFDocument(Files.newInputStream(out))){var p=d.getParagraphs().getFirst();assertEquals("new KEEP new",p.getText());assertEquals("KEEP",p.getRuns().get(1).text());assertTrue(p.getRuns().get(1).isBold());}
 }
 @Test void editingLaterPptxShapeDoesNotConsumeBlankLinesFromEarlierShape()throws Exception {
  Path in=tmp.resolve("blank.pptx"),out=tmp.resolve("out.pptx");try(var d=new XMLSlideShow()){var slide=d.createSlide();slide.createTextBox().setText("Title\n\nFirst");slide.createTextBox().setText("Second");try(var os=Files.newOutputStream(in)){d.write(os);}}var data=payload(read(in,OutputFormat.PPTX));((List<Map<String,Object>>)data.get("slides")).getFirst().put("bullets",List.of("First","Changed"));patch.applyPatch(in,out,"pptx",data);try(var d=new XMLSlideShow(Files.newInputStream(out))){assertEquals("Title\n\nFirst",((XSLFTextShape)d.getSlides().getFirst().getShapes().getFirst()).getText());assertEquals("Changed",((XSLFTextShape)d.getSlides().getFirst().getShapes().get(1)).getText());}
 }
 @Test void blankCsvRecordsAreKeptWhenAnotherRecordChanges()throws Exception {
  Path in=tmp.resolve("blank.csv"),out=tmp.resolve("out.csv");Files.writeString(in,"header\none\n\nthree\n");var data=payload(read(in,OutputFormat.CSV));assertEquals(4,((List<?>)data.get("csvRows")).size());((List<List<String>>)data.get("csvRows")).get(1).set(0,"changed");patch.applyPatch(in,out,"csv",data);assertEquals(List.of(List.of("header"),List.of("changed"),List.of(""),List.of("three")),read(out,OutputFormat.CSV).csvRows());
 }
 @Test void editingDocxTextKeepsSurroundingWhitespaceHiddenByPreview()throws Exception {
  Path in=tmp.resolve("padding.docx"),out=tmp.resolve("out.docx");try(var d=new XWPFDocument()){d.createParagraph().createRun().setText("  old  ");write(d,in);}var data=payload(read(in,OutputFormat.DOCX));((List<Map<String,Object>>)data.get("blocks")).getFirst().put("text","new");patch.applyPatch(in,out,"docx",data);try(var d=new XWPFDocument(Files.newInputStream(out))){assertEquals("  new  ",d.getParagraphs().getFirst().getText());}
 }
 @Test void semicolonDelimiterUsesRecordsWhenHeaderContainsComma()throws Exception {
  Path semi=tmp.resolve("semicolon-header.csv");Files.writeString(semi,"Last, First;Price\nSmith John;5\n");assertDoesNotThrow(()->new FileValidator().validate(semi,OutputFormat.CSV));assertEquals(";",read(semi,OutputFormat.CSV).delimiter());assertEquals(List.of("Last, First","Price"),read(semi,OutputFormat.CSV).csvRows().getFirst());
 }
 @Test void commaDelimiterUsesRecordsWhenHeaderContainsSemicolons()throws Exception {
  Path comma=tmp.resolve("comma-header.csv");Files.writeString(comma,"Last; First; Middle,Price\nSmith John,5\n");assertDoesNotThrow(()->new FileValidator().validate(comma,OutputFormat.CSV));assertEquals(",",read(comma,OutputFormat.CSV).delimiter());assertEquals(List.of("Last; First; Middle","Price"),read(comma,OutputFormat.CSV).csvRows().getFirst());
 }
 @Test void delimiterDetectionHandlesQuotedMultilineHeaderWithBom()throws Exception {
  Path in=tmp.resolve("quoted-header.csv"),out=tmp.resolve("quoted-out.csv");Files.writeString(in,"\uFEFF\"Last,\nFirst\";Price\n\"Smith,\nJohn\";5\n");var p=read(in,OutputFormat.CSV);assertEquals(";",p.delimiter());assertEquals(List.of("Last,\nFirst","Price"),p.csvRows().getFirst());var data=payload(p);((List<List<String>>)data.get("csvRows")).get(1).set(1,"6");patch.applyPatch(in,out,"csv",data);assertEquals(List.of("Smith,\nJohn","6"),read(out,OutputFormat.CSV).csvRows().get(1));
 }
 @Test void delimiterDetectionDoesNotHideMalformedMulticolumnRecords()throws Exception {
  Path in=tmp.resolve("ragged.csv");Files.writeString(in,"name;price\nonlyone\n");assertThrows(FileValidator.ValidationException.class,()->new FileValidator().validate(in,OutputFormat.CSV));assertEquals(',',CsvSupport.delimiter("header\nvalue\n"));
 }
 @Test void defaultCsvLimitRejects5001DataRows()throws Exception {Path p=tmp.resolve("large.csv");Files.writeString(p,"id\n"+"x\n".repeat(5001));assertThrows(FileValidator.ValidationException.class,()->new FileValidator().validate(p,OutputFormat.CSV));}
 @Test void emptyOutputsAreRejected()throws Exception {
  Path csv=tmp.resolve("empty.csv");Files.writeString(csv,"header\n");assertThrows(FileValidator.ValidationException.class,()->new FileValidator().validate(csv,OutputFormat.CSV));
  Path xlsx=tmp.resolve("empty.xlsx");try(var w=new XSSFWorkbook()){w.createSheet();try(var os=Files.newOutputStream(xlsx)){w.write(os);}}assertThrows(FileValidator.ValidationException.class,()->new FileValidator().validate(xlsx,OutputFormat.XLSX));
  Path docx=tmp.resolve("empty.docx");try(var d=new XWPFDocument()){d.createParagraph();write(d,docx);}assertThrows(FileValidator.ValidationException.class,()->new FileValidator().validate(docx,OutputFormat.DOCX));
  Path pdf=tmp.resolve("empty.pdf");try(var d=new PDDocument()){d.addPage(new PDPage());d.save(pdf.toFile());}assertThrows(FileValidator.ValidationException.class,()->new FileValidator().validate(pdf,OutputFormat.PDF));
 }
 @Test void configuredSizeAndFormatLimitsAreEnforced()throws Exception {
  var v=new FileValidator();ReflectionTestUtils.setField(v,"maxOutputBytes",3L);Path csv=tmp.resolve("size.csv");Files.writeString(csv,"id\nx\n");assertThrows(FileValidator.ValidationException.class,()->v.validate(csv,OutputFormat.CSV));
  var limits=new FileValidator();ReflectionTestUtils.setField(limits,"maxPptSlides",1);Path ppt=tmp.resolve("slides.pptx");try(var d=new XMLSlideShow()){d.createSlide().createTextBox().setText("one");d.createSlide().createTextBox().setText("two");try(var os=Files.newOutputStream(ppt)){d.write(os);}}assertThrows(FileValidator.ValidationException.class,()->limits.validate(ppt,OutputFormat.PPTX));
  ReflectionTestUtils.setField(limits,"maxWordSections",1);Path doc=tmp.resolve("sections.docx");try(var d=new XWPFDocument()){for(int i=0;i<2;i++){var p=d.createParagraph();p.setStyle("Heading1");p.createRun().setText("Section "+i);}write(d,doc);}assertThrows(FileValidator.ValidationException.class,()->limits.validate(doc,OutputFormat.DOCX));
  ReflectionTestUtils.setField(limits,"maxPdfBlocks",1);Path pdf=tmp.resolve("blocks.pdf");try(var d=new PDDocument()){var page=new PDPage();d.addPage(page);try(var c=new PDPageContentStream(d,page)){c.beginText();c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);c.newLineAtOffset(50,700);c.showText("one");c.newLineAtOffset(0,-20);c.showText("two");c.endText();}d.save(pdf.toFile());}assertThrows(FileValidator.ValidationException.class,()->limits.validate(pdf,OutputFormat.PDF));
  ReflectionTestUtils.setField(limits,"maxCsvRows",1);Path xlsx=tmp.resolve("rows.xlsx");try(var w=new XSSFWorkbook()){var sheet=w.createSheet();for(int i=0;i<3;i++)sheet.createRow(i).createCell(0).setCellValue("row"+i);try(var os=Files.newOutputStream(xlsx)){w.write(os);}}assertThrows(FileValidator.ValidationException.class,()->limits.validate(xlsx,OutputFormat.XLSX));
 }
}
