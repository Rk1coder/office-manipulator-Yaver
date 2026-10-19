package com.officemanipulator;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.api.JobController;
import com.officemanipulator.claude.*;
import com.officemanipulator.domain.*;
import com.officemanipulator.service.*;
import com.officemanipulator.storage.FileStorageService;
import com.officemanipulator.validator.FileValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendLifecycleTest {
 @TempDir Path tmp;
 final ObjectMapper mapper=new ObjectMapper();
 Job job(JobStatus state) {Job j=new Job();j.setId(UUID.randomUUID());j.setFormat(OutputFormat.CSV);j.setRequest("test");j.setStatus(state);return j;}
 @Test void failedValidationDeletesOnlyFailedChildFiles() throws Exception {
  Job j=job(JobStatus.PENDING);FileStorageService storage=new FileStorageService(tmp.toString());
  Path original=storage.resolveJobDir(UUID.randomUUID());Files.createDirectories(original);Files.writeString(original.resolve("keep.txt"),"original");
  Path bad=storage.resolveOutputPath(j.getId(),OutputFormat.CSV);Files.createDirectories(bad.getParent());Files.writeString(bad,"name,value\nwrong\n");
  JobRepository repo=mock(JobRepository.class);when(repo.findById(j.getId())).thenReturn(Optional.of(j));when(repo.transition(any(),any(),any())).thenReturn(1);
  ClaudeOrchestrator ai=mock(ClaudeOrchestrator.class);when(ai.run(j)).thenReturn(new com.officemanipulator.claude.dto.OrchestratorResult(bad,1,2,new BigDecimal("0.01")));
  new JobService(repo,ai,new FileValidator(),storage,mock(BudgetService.class),mock(ApplicationEventPublisher.class),new JobCancellationRegistry()).processAsync(j.getId());
  assertEquals(JobStatus.FAILED,j.getStatus());assertFalse(Files.exists(bad.getParent()));assertNotNull(j.getFilesCleanedAt());assertTrue(Files.exists(original.resolve("keep.txt")));assertEquals(new BigDecimal("0.01"),j.getCostUsd());
 }
 @Test void pricesFollowSelectedModelAndUnknownModelsRequireExplicitPrices() {
  assertEquals(new BigDecimal("3.00"),new com.officemanipulator.config.ModelPricing("claude-sonnet-4-6","","").input());
  assertEquals(new BigDecimal("2.00"),new com.officemanipulator.config.ModelPricing("claude-sonnet-5","","").input());
  assertThrows(IllegalArgumentException.class,()->new com.officemanipulator.config.ModelPricing("unknown","",""));
 }
 @Test void workerCannotProcessAlreadyCompletedJob() throws Exception {
  Job j=job(JobStatus.DONE);JobRepository repo=mock(JobRepository.class);when(repo.findById(j.getId())).thenReturn(Optional.of(j));
  ClaudeOrchestrator ai=mock(ClaudeOrchestrator.class);
  new JobService(repo,ai,new FileValidator(),new FileStorageService(tmp.toString()),mock(BudgetService.class),mock(ApplicationEventPublisher.class),new JobCancellationRegistry()).processAsync(j.getId());
  verifyNoInteractions(ai);
 }
 @Test void successfulCallUsageSurvivesSubsequentProviderFailure() throws Exception {
  AnthropicClient client=mock(AnthropicClient.class,RETURNS_DEEP_STUBS);
  Message message=mock(Message.class);Usage usage=mock(Usage.class);when(usage.inputTokens()).thenReturn(100L);when(usage.outputTokens()).thenReturn(200L);when(message.usage()).thenReturn(usage);
  ToolUseBlock tool=mock(ToolUseBlock.class);when(tool.id()).thenReturn("id");when(tool.name()).thenReturn("create_csv");when(tool._input()).thenReturn(com.anthropic.core.JsonValue.from(Map.of()));
  when(message.content()).thenReturn(List.of(ContentBlock.ofToolUse(tool)));
  when(client.messages().create(any(MessageCreateParams.class))).thenReturn(message).thenThrow(new IllegalStateException("transport error"));
  ToolDispatcher dispatcher=mock(ToolDispatcher.class);when(dispatcher.dispatch(anyString(),anyString(),any(),anyMap())).thenThrow(new IllegalStateException("bad script"));
  com.officemanipulator.config.ClaudeClientProvider provider=mock(com.officemanipulator.config.ClaudeClientProvider.class);
  when(provider.client()).thenReturn(client);when(provider.model()).thenReturn("test");
  ClaudeOrchestrator ai=new ClaudeOrchestrator(provider,new ToolRegistry(),dispatcher,new FileStorageService(tmp.toString()),mapper);
  ReflectionTestUtils.setField(ai,"maxTokens",1000L);ReflectionTestUtils.setField(ai,"maxToolTurns",2);
  ReflectionTestUtils.setField(ai,"inputPricePerMillion",BigDecimal.valueOf(2));ReflectionTestUtils.setField(ai,"outputPricePerMillion",BigDecimal.TEN);
  Job j=job(JobStatus.PROCESSING);j.setCostUsd(new BigDecimal("0.25"));
  assertThrows(IllegalStateException.class,()->ai.run(j));
  assertEquals(0,new BigDecimal("0.252200").compareTo(j.getCostUsd()));assertEquals(100,j.getTokenInput());
 }
 @Test void conversionUsesCurrentDocumentWithoutAiEvenWhenBudgetIsExhausted() throws Exception {
  Job original=job(JobStatus.DONE);Path path=tmp.resolve("current.csv");Files.writeString(path,"name\nLATEST_EDIT\n");original.setFilePath(path.toString());
  JobRepository repo=mock(JobRepository.class);when(repo.findById(original.getId())).thenReturn(Optional.of(original));
  Map<UUID,Job> rows=new HashMap<>();
  when(repo.save(any())).thenAnswer(a->{Job j=a.getArgument(0);if(j.getId()==null)j.setId(UUID.randomUUID());rows.put(j.getId(),j);return j;});
  when(repo.transition(any(),any(),any())).thenReturn(1);
  var conversion=mock(com.officemanipulator.office.DocumentConversionService.class);
  doAnswer(a->{Path out=a.getArgument(1);Files.createDirectories(out.getParent());Files.copy((Path)a.getArgument(0),out);return null;})
      .when(conversion).convert(eq(path),any(),eq(OutputFormat.CSV),eq(OutputFormat.XLSX));
  var ai=mock(ClaudeOrchestrator.class);var budget=mock(BudgetService.class);when(budget.isLimitReached()).thenReturn(true);
  // Validator is kept out here because the transport fake copies bytes; native converter byte preservation is tested separately.
  var validator=mock(FileValidator.class);
  DocumentEditService edits=new DocumentEditService(repo,ai,new com.officemanipulator.preview.PreviewService(),validator,new FileStorageService(tmp.toString()),budget,mock(ApplicationEventPublisher.class),mapper,new DirectPatchService(),new JobCancellationRegistry(),Optional.of(conversion));
  Job converted=edits.createConversionJob(original.getId(),OutputFormat.XLSX);
  when(repo.findById(converted.getId())).thenReturn(Optional.of(converted));
  edits.processConversionAsync(converted.getId(),original.getId());
  assertEquals(original.getId(),converted.getParentJobId());assertEquals(OutputFormat.XLSX,converted.getFormat());
  assertEquals(JobStatus.DONE,converted.getStatus());assertEquals(BigDecimal.ZERO,converted.getCostUsd());
  assertEquals("name\nLATEST_EDIT\n",Files.readString(Path.of(converted.getFilePath())));
  assertEquals("name\nLATEST_EDIT\n",Files.readString(path));verifyNoInteractions(ai,budget);
 }
 @Test void cancelledConversionIsNotResurrectedWhenParentLookupFails() throws Exception {
  var repo=mock(JobRepository.class);UUID child=UUID.randomUUID(),parent=UUID.randomUUID();
  Job cancelled=job(JobStatus.CANCELLED);cancelled.setId(child);
  when(repo.transition(child,JobStatus.PENDING,JobStatus.PROCESSING)).thenReturn(1);
  when(repo.findById(child)).thenReturn(Optional.of(cancelled));when(repo.findById(parent)).thenReturn(Optional.empty());
  var edits=new DocumentEditService(repo,mock(ClaudeOrchestrator.class),new com.officemanipulator.preview.PreviewService(),new FileValidator(),new FileStorageService(tmp.toString()),mock(BudgetService.class),mock(ApplicationEventPublisher.class),mapper,new DirectPatchService(),new JobCancellationRegistry());
  edits.processConversionAsync(child,parent);
  assertEquals(JobStatus.CANCELLED,cancelled.getStatus());verify(repo,never()).save(any());
 }
 @Test void fragmentUsageSavedEvenIfReturnedJsonIsInvalid() throws Exception {
  Job j=job(JobStatus.DONE);j.setFilePath(tmp.resolve("done.csv").toString());
  JobService jobs=mock(JobService.class);when(jobs.findById(j.getId())).thenReturn(Optional.of(j));
  ClaudeOrchestrator ai=mock(ClaudeOrchestrator.class);when(ai.runFragmentEdit(eq(j),anyString(),anyString())).thenAnswer(a->{JobUsage.snapshot(j).apply(j,10,20,new BigDecimal("0.02"));return "invalid";});
  JobController api=new JobController(jobs,mock(BudgetService.class),mock(DocumentEditService.class),mock(PlanningService.class),mapper,ai);
  assertEquals(500,api.editFragment(j.getId(),Map.of("fragment",Map.of("text","hello"),"instruction","edit")).getStatusCode().value());
  verify(jobs).recordFragmentUsage(eq(j.getId()),any(),eq(j));
 }
}
