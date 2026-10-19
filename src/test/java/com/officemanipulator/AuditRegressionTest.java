package com.officemanipulator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.api.JobController;
import com.officemanipulator.claude.*;
import com.officemanipulator.claude.dto.*;
import com.officemanipulator.domain.*;
import com.officemanipulator.event.JobStatusEvent;
import com.officemanipulator.generator.*;
import com.officemanipulator.preview.*;
import com.officemanipulator.service.*;
import com.officemanipulator.storage.FileStorageService;
import com.officemanipulator.validator.FileValidator;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Expected-correctness regressions promoted from the 2026-10-15 system audit. */
class AuditRegressionTest {
 @TempDir Path tmp;
 final ObjectMapper mapper = new ObjectMapper();
 final PreviewService preview = new PreviewService();
 final DirectPatchService patch = new DirectPatchService();
 Job fileJob(Path path, OutputFormat format) {
  Job j=new Job(); j.setId(UUID.randomUUID()); j.setStatus(JobStatus.DONE); j.setFormat(format); j.setFilePath(path.toString()); j.setRequest("audit"); return j;
 }
 Map<String,Object> payload(PreviewData p) {return mapper.convertValue(p, Map.class);}
 JobRepository repo(Job j) {
  JobRepository r=mock(JobRepository.class);
  when(r.findById(j.getId())).thenReturn(Optional.of(j));
  when(r.findForUpdate(j.getId())).thenReturn(Optional.of(j));
  when(r.transition(any(),any(),any())).thenReturn(1);
  when(r.save(any(Job.class))).thenAnswer(a->{Job x=a.getArgument(0); if(x.getId()==null)x.setId(UUID.randomUUID()); return x;});
  when(r.sumTotalCost()).thenReturn(BigDecimal.ZERO); return r;
 }
 ClaudeOrchestrator orchestrator(AnthropicClient client, ToolDispatcher dispatcher) throws Exception {
  com.officemanipulator.config.ClaudeClientProvider provider=mock(com.officemanipulator.config.ClaudeClientProvider.class);
  when(provider.client()).thenReturn(client);when(provider.model()).thenReturn("audit-model");
  ClaudeOrchestrator o=new ClaudeOrchestrator(provider,new ToolRegistry(),dispatcher,new FileStorageService(tmp.toString()),mapper);
  ReflectionTestUtils.setField(o,"maxTokens",1000L);
  ReflectionTestUtils.setField(o,"maxToolTurns",2);
  ReflectionTestUtils.setField(o,"inputPricePerMillion",new BigDecimal("2"));
  ReflectionTestUtils.setField(o,"outputPricePerMillion",new BigDecimal("10"));
  return o;
 }
 Message toolResponse() {
  Message result=mock(Message.class);
  Usage usage=mock(Usage.class); when(usage.inputTokens()).thenReturn(100L);when(usage.outputTokens()).thenReturn(200L);
  when(result.usage()).thenReturn(usage);
  ToolUseBlock tool=mock(ToolUseBlock.class);when(tool.id()).thenReturn("audit-tool");when(tool.name()).thenReturn("create_csv");
  when(tool._input()).thenReturn(com.anthropic.core.JsonValue.from(Map.of()));
  var content=List.of(ContentBlock.ofToolUse(tool));
  when(result.content()).thenReturn(content);
  return result;
 }
 @Test void confirmedPlanMustReachGenerationRequest() throws Exception {
  AnthropicClient client=mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
  var captured=new ArrayList<MessageCreateParams>();
  when(client.messages().create(any(MessageCreateParams.class))).thenAnswer(a->{captured.add(a.getArgument(0));return toolResponse();});
  ToolDispatcher dispatcher=mock(ToolDispatcher.class); when(dispatcher.dispatch(anyString(),anyString(),any(),anyMap())).thenReturn("OK");
  Job j=fileJob(tmp.resolve("x.csv"),OutputFormat.CSV); j.setPlanJson("{\"title\":\"APPROVED_PLAN_SENTINEL\",\"outline\":[]}");
  orchestrator(client,dispatcher).run(j);
  assertTrue(captured.getFirst().messages().toString().contains("APPROVED_PLAN_SENTINEL"),"Approved plan absent from Claude generation request");
 }
 @Test void generationMustPreservePlanningCost() throws Exception {
  Path out=tmp.resolve("x.csv"); Files.writeString(out,"name\nvalue\n");
  Job j=fileJob(out,OutputFormat.CSV);j.setCostUsd(new BigDecimal("0.25"));j.setTokenInput(10);
  ClaudeOrchestrator o=mock(ClaudeOrchestrator.class);when(o.run(j)).thenReturn(new OrchestratorResult(out,20,30,new BigDecimal("0.50")));
  JobRepository r=repo(j);
  new JobService(r,o,new FileValidator(),new FileStorageService(tmp.toString()),new BudgetService(r,BigDecimal.TEN,BigDecimal.TEN),mock(ApplicationEventPublisher.class),new JobCancellationRegistry()).processAsync(j.getId());
  assertEquals(new BigDecimal("0.75"),j.getCostUsd(),"Planning cost overwritten instead of accumulated");
 }
 @Test void validationFailureMustRetainConsumedApiCost() throws Exception {
  Path out=tmp.resolve("missing.csv");Job j=fileJob(out,OutputFormat.CSV);j.setCostUsd(new BigDecimal("0.25"));
  ClaudeOrchestrator o=mock(ClaudeOrchestrator.class);when(o.run(j)).thenReturn(new OrchestratorResult(out,20,30,new BigDecimal("0.50")));
  JobRepository r=repo(j);
  new JobService(r,o,new FileValidator(),new FileStorageService(tmp.toString()),new BudgetService(r,BigDecimal.TEN,BigDecimal.TEN),mock(ApplicationEventPublisher.class),new JobCancellationRegistry()).processAsync(j.getId());
  assertEquals(JobStatus.FAILED,j.getStatus());
  assertEquals(new BigDecimal("0.75"),j.getCostUsd(),"API consumption lost when file validation fails");
 }
 @Test void completedJobMustNotGenerateTwiceOnPlanConfirm() {
  Job j=fileJob(tmp.resolve("x.csv"),OutputFormat.CSV);JobRepository r=repo(j);
  PlanningService service=spy(new PlanningService(r,mock(ClaudeOrchestrator.class),mock(JobService.class),mock(GeminiImageService.class),mock(ApplicationEventPublisher.class),mapper,new JobCancellationRegistry()));
  doNothing().when(service).generateAfterPlanAsync(any());
  JobController c=new JobController(mock(JobService.class),mock(BudgetService.class),mock(DocumentEditService.class),service,mapper,mock(ClaudeOrchestrator.class));
  int first=c.confirmPlan(j.getId(),Map.of()).getStatusCode().value();
  int second=c.confirmPlan(j.getId(),Map.of()).getStatusCode().value();
  assertTrue(first>=400 && second>=400,"DONE job accepted twice: "+first+", "+second);
 }
 @Test void fragmentEditMustRejectUnknownJobBeforeApiCall() {
  JobService jobs=mock(JobService.class);ClaudeOrchestrator o=mock(ClaudeOrchestrator.class);
  when(o.runFragmentEdit(any(Job.class),anyString(),anyString())).thenReturn("{\"text\":\"ok\"}");
  JobController c=new JobController(jobs,mock(BudgetService.class),mock(DocumentEditService.class),mock(PlanningService.class),mapper,o);
  var response=c.editFragment(UUID.randomUUID(),Map.of("fragment",Map.of("text","x"),"instruction","audit"));
  assertEquals(404,response.getStatusCode().value(),"Unknown job invokes paid fragment API and returns success");
 }
 @Test void csvEditMustPreserveRowsOutsidePreview() throws Exception {
  Path in=tmp.resolve("full.csv"),out=tmp.resolve("patched.csv");
  StringBuilder content=new StringBuilder("id,name\n");for(int i=0;i<100;i++)content.append(i).append(",item").append(i).append('\n');Files.writeString(in,content);
  PreviewData p=preview.extract(fileJob(in,OutputFormat.CSV));var data=payload(p);
  assertTrue(patch.canPatch(p,data));patch.applyPatch(in,out,"csv",data);
  assertEquals(101,Files.readAllLines(out).size(),"Preview-to-file patch drops rows after preview limit");
 }
 @Test void semicolonCsvMustValidate() throws Exception {
  CreateCsvParams p=new CreateCsvParams();p.setDelimiter(";");p.setColumns(List.of("Product","Price"));p.setRows(List.of(List.of("Apple","5,50")));
  Path out=tmp.resolve("semicolon.csv");new CsvGenerator().generate(p,out);
  assertDoesNotThrow(()->new FileValidator().validate(out,OutputFormat.CSV));
 }
 @Test void semicolonCsvMustPreviewAsTwoColumns() throws Exception {
  Path in=tmp.resolve("s.csv");Files.writeString(in,"Product;Price\nApple;5\n");
  assertEquals(2,preview.extract(fileJob(in,OutputFormat.CSV)).csvRows().getFirst().size());
 }
 @Test void docxNoOpPatchMustPreserveMixedFormatting() throws Exception {
  Path in=tmp.resolve("styled.docx"),out=tmp.resolve("patched.docx");
  try(XWPFDocument doc=new XWPFDocument()) {var p=doc.createParagraph();var a=p.createRun();a.setBold(true);a.setText("Bold ");var b=p.createRun();b.setItalic(true);b.setText("italic");try(var os=Files.newOutputStream(in)){doc.write(os);}}
  PreviewData p=preview.extract(fileJob(in,OutputFormat.DOCX));patch.applyPatch(in,out,"docx",payload(p));
  try(XWPFDocument doc=new XWPFDocument(Files.newInputStream(out))) {assertEquals(2,doc.getParagraphs().getFirst().getRuns().size(),"No-op save removed mixed run formatting");}
 }
 @Test void pptxTableEditMustUpdateCell() throws Exception {
  Path in=tmp.resolve("table.pptx"),out=tmp.resolve("patched.pptx");
  try(XMLSlideShow ppt=new XMLSlideShow()){var s=ppt.createSlide();s.createTextBox().setText("Title");var table=s.createTable(1,1);table.getCell(0,0).setText("old");try(var os=Files.newOutputStream(in)){ppt.write(os);}}
  PreviewData p=preview.extract(fileJob(in,OutputFormat.PPTX));var data=payload(p);
  var slides=(List<Map<String,Object>>)data.get("slides");slides.getFirst().put("table",List.of(List.of("new")));
  assertTrue(patch.canPatch(p,data));patch.applyPatch(in,out,"pptx",data);
  var after=preview.extract(fileJob(out,OutputFormat.PPTX));assertEquals("new",((List<List<String>>)after.slides().getFirst().get("table")).getFirst().getFirst());
 }
 @Test void pptxNoOpPatchMustPreserveTextFormatting() throws Exception {
  Path in=tmp.resolve("styled.pptx"),out=tmp.resolve("patched.pptx");
  try(XMLSlideShow ppt=new XMLSlideShow()){var shape=ppt.createSlide().createTextBox();shape.setText("Title");shape.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontSize(42.0);try(var os=Files.newOutputStream(in)){ppt.write(os);}}
  PreviewData p=preview.extract(fileJob(in,OutputFormat.PPTX));patch.applyPatch(in,out,"pptx",payload(p));
  try(XMLSlideShow ppt=new XMLSlideShow(Files.newInputStream(out))){var shape=(XSLFTextShape)ppt.getSlides().getFirst().getShapes().getFirst();assertEquals(42.0,shape.getTextParagraphs().getFirst().getTextRuns().getFirst().getFontSize());}
 }
 @Test void fullPdfEditMustSupplyAllPagesToModel() throws Exception {
  Path in=tmp.resolve("four-pages.pdf");
  try(PDDocument pdf=new PDDocument()){for(int i=1;i<=4;i++){PDPage p=new PDPage();pdf.addPage(p);try(var stream=new PDPageContentStream(pdf,p)){stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(50,700);stream.showText("PAGE_"+i+"_SENTINEL");stream.endText();}}pdf.save(in.toFile());}
  Job original=fileJob(in,OutputFormat.PDF);Job edit=fileJob(tmp.resolve("edited.pdf"),OutputFormat.PDF);JobRepository r=repo(original);when(r.findById(edit.getId())).thenReturn(Optional.of(edit));
  ClaudeOrchestrator o=mock(ClaudeOrchestrator.class);var contexts=new ArrayList<String>();
  when(o.runEdit(eq(edit),anyString())).thenAnswer(a->{contexts.add(a.getArgument(1));return new OrchestratorResult(in,0,0,BigDecimal.ZERO);});
  new DocumentEditService(r,o,preview,new FileValidator(),new FileStorageService(tmp.toString()),mock(BudgetService.class),mock(ApplicationEventPublisher.class),mapper,patch,new JobCancellationRegistry()).processEditAsync(edit.getId(),original.getId());
  assertTrue(contexts.getFirst().contains("PAGE_4_SENTINEL"),"Full edit model only receives preview's first three pages");
 }
 @Test void xlsxEditMustSupplyActualWorkbookContent() throws Exception {
  Path in=tmp.resolve("sheet.xlsx");
  try(var wb=new org.apache.poi.xssf.usermodel.XSSFWorkbook()){wb.createSheet().createRow(0).createCell(0).setCellValue("WORKBOOK_SENTINEL");try(var os=Files.newOutputStream(in)){wb.write(os);}}
  Job original=fileJob(in,OutputFormat.XLSX);Job edit=fileJob(tmp.resolve("edit.xlsx"),OutputFormat.XLSX);JobRepository r=repo(original);when(r.findById(edit.getId())).thenReturn(Optional.of(edit));
  ClaudeOrchestrator o=mock(ClaudeOrchestrator.class);var contexts=new ArrayList<String>();
  when(o.runEdit(eq(edit),anyString())).thenAnswer(a->{contexts.add(a.getArgument(1));return new OrchestratorResult(in,0,0,BigDecimal.ZERO);});
  new DocumentEditService(r,o,preview,new FileValidator(),new FileStorageService(tmp.toString()),mock(BudgetService.class),mock(ApplicationEventPublisher.class),mapper,patch,new JobCancellationRegistry()).processEditAsync(edit.getId(),original.getId());
  assertTrue(contexts.getFirst().contains("WORKBOOK_SENTINEL"),"XLSX edit receives unsupported-preview placeholder, not cells");
 }
 @Test void closingOneEventSubscriberMustNotDisconnectOthers() {
  Job j=fileJob(tmp.resolve("x.csv"),OutputFormat.CSV); j.setStatus(JobStatus.PENDING); UUID id=j.getId();
  JobService jobs=mock(JobService.class);when(jobs.findById(id)).thenReturn(Optional.of(j));
  JobController c=new JobController(jobs,mock(BudgetService.class),mock(DocumentEditService.class),mock(PlanningService.class),mapper,mock(ClaudeOrchestrator.class));
  Disposable first=c.streamEvents(id).subscribe();var received=new ArrayList<Map<String,String>>();Disposable second=c.streamEvents(id).subscribe(received::add);
  first.dispose();c.onJobStatusChanged(new JobStatusEvent(this,id,JobStatus.PROCESSING));second.dispose();
  assertEquals(List.of(Map.of("status","PENDING"),Map.of("status","PROCESSING")),received,"First subscriber's close removed shared sink while second remained connected");
 }
 @Test void lateEventSubscriberMustReceiveCurrentTerminalState() {
  Job j=fileJob(tmp.resolve("done.csv"),OutputFormat.CSV);JobService jobs=mock(JobService.class);when(jobs.findById(j.getId())).thenReturn(Optional.of(j));
  JobController c=new JobController(jobs,mock(BudgetService.class),mock(DocumentEditService.class),mock(PlanningService.class),mapper,mock(ClaudeOrchestrator.class));
  c.onJobStatusChanged(new JobStatusEvent(this,j.getId(),JobStatus.DONE));var received=new ArrayList<Map<String,String>>();Disposable d=c.streamEvents(j.getId()).subscribe(received::add);d.dispose();
  assertFalse(received.isEmpty(),"Completed job's state is not replayed to late subscribers");
 }
 @Test void modelScriptMustNotInheritBackendSecretEnvironment() throws Exception {
  assertEquals("fake-audit-value",System.getenv("AUDIT_PARENT_SECRET"));Path out=tmp.resolve("env.txt");
  new NodeScriptExecutor().execute("require('fs').writeFileSync(process.env.OUTPUT_PATH, String(Boolean(process.env.AUDIT_PARENT_SECRET)))","env_probe.js",out);
  assertEquals("false",Files.readString(out),"Generated script inherits parent environment including secrets");
 }
 @Test void generatedScriptMustNotReadOutsideJobDirectory() throws Exception {
  Path marker=tmp.resolve("private-marker.txt");Files.writeString(marker,"fake-private-marker");Path out=tmp.resolve("job/out.txt");
  String script="const fs=require('fs');fs.writeFileSync(process.env.OUTPUT_PATH,fs.readFileSync("+mapper.writeValueAsString(marker.toString())+"));";
  assertThrows(java.io.IOException.class, () -> new NodeScriptExecutor().execute(script,"read_probe.js",out));
  assertFalse(Files.exists(out), "Sandbox must not expose the sibling marker");
 }
 @Test void exhaustedToolFailuresMustNotReportSuccessWithOldFile() throws Exception {
  Path out=tmp.resolve("existing.csv");Files.writeString(out,"name\nold\n");Job j=fileJob(out,OutputFormat.CSV);
  AnthropicClient client=mock(AnthropicClient.class,RETURNS_DEEP_STUBS);Message response=toolResponse();when(client.messages().create(any(MessageCreateParams.class))).thenReturn(response);
  ToolDispatcher dispatcher=mock(ToolDispatcher.class);when(dispatcher.dispatch(anyString(),anyString(),any(),anyMap())).thenThrow(new IllegalArgumentException("intentional audit failure"));
  ClaudeOrchestrator o=orchestrator(client,dispatcher);
  assertThrows(Exception.class,()->o.run(j),"All tool attempts failed but orchestrator returned a normal result");
 }
 @Test void malformedPlanMustNotBeMarkedReady() {
  Job j=fileJob(tmp.resolve("x.csv"),OutputFormat.CSV);JobRepository r=repo(j);ClaudeOrchestrator o=mock(ClaudeOrchestrator.class);
  when(o.runPlan(j)).thenReturn(new OrchestratorPlanResult("not JSON",10,20,new BigDecimal("0.01")));
  new PlanningService(r,o,mock(JobService.class),mock(GeminiImageService.class),mock(ApplicationEventPublisher.class),mapper,new JobCancellationRegistry()).createPlanAsync(j.getId());
  assertNotEquals(JobStatus.PLAN_READY,j.getStatus(),"Malformed model plan marked PLAN_READY; plan endpoint cannot parse it");
 }
 @Test void failedDirectPatchMustNotLeaveOrphanProcessingJob() throws Exception {
  Path in=tmp.resolve("original.csv");Files.writeString(in,"name\nitem\n");Job original=fileJob(in,OutputFormat.CSV);JobRepository r=repo(original);var saved=new ArrayList<Job>();
  when(r.save(any())).thenAnswer(a->{Job x=a.getArgument(0);if(x.getId()==null)x.setId(UUID.randomUUID());saved.add(x);return x;});
  DirectPatchService broken=mock(DirectPatchService.class);when(broken.canPatch(any(),anyMap())).thenReturn(true);
  doThrow(new java.io.IOException("audit simulated disk failure")).when(broken).applyPatch(any(),any(),anyString(),anyMap());
  new DocumentEditService(r,mock(ClaudeOrchestrator.class),preview,new FileValidator(),new FileStorageService(tmp.toString()),mock(BudgetService.class),mock(ApplicationEventPublisher.class),mapper,broken,new JobCancellationRegistry())
   .createRebuildJob(original.getId(),payload(preview.extract(original)));
  assertFalse(saved.stream().anyMatch(j->j.getStatus()==JobStatus.PROCESSING),"Failed direct patch left PROCESSING child and created another PENDING child");
 }
 @Test void csvRowLimitMustBeEnforced() throws Exception {
  CreateCsvParams p=new CreateCsvParams();p.setDelimiter(",");p.setColumns(List.of("id"));p.setRows(Collections.nCopies(5001,List.of("x")));
  Path out=tmp.resolve("oversize.csv");
  assertThrows(Exception.class,()->{new CsvGenerator().generate(p,out);new FileValidator().validate(out,OutputFormat.CSV);},"Configured 5000-row maximum not enforced");
 }
 @Test void controlExpiredFilesAreDeleted() throws Exception {
  FileStorageService storage=new FileStorageService(tmp.toString());Job j=fileJob(tmp.resolve("unused"),OutputFormat.CSV);Path out=storage.resolveOutputPath(j.getId(),OutputFormat.CSV);Files.createDirectories(out.getParent());Files.writeString(out,"name\nvalue\n");j.setFilePath(out.toString());JobRepository r=repo(j);when(r.findExpiredWithFile(any())).thenReturn(List.of(j));
  new FileCleanupService(r,storage).deleteExpiredFiles();assertFalse(Files.exists(out));verify(r).clearFilePath(j.getId());
 }
 @Test void controlMissingGeneratedOutputIsRejected() {
  assertThrows(java.io.IOException.class,()->new NodeScriptExecutor().execute("// no output", "empty.js",tmp.resolve("empty.txt")));
 }
}
