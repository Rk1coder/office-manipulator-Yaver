package com.officemanipulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.ClaudeOrchestrator;
import com.officemanipulator.domain.*;
import com.officemanipulator.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in against a DISPOSABLE PostgreSQL database, never the user's database. */
@EnabledIfEnvironmentVariable(named="OFFICE_TEST_DB_URL", matches=".*office_audit_test.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.datasource.url=${OFFICE_TEST_DB_URL}","spring.datasource.username=postgres","spring.datasource.password=office-test-only",
 "app.claude.api-key=fake-test-key","app.budget.limit-usd=2.00","app.gemini.enabled=false",
 "app.storage.base-path=/tmp/office-backend-integration-files"})
class DatabaseIntegrationTest {
 @Autowired JobRepository jobs;
 @Autowired PlanningService plans;
 @Autowired ApiBillingService billing;
 @Autowired JdbcTemplate jdbc;
 @Autowired TestRestTemplate http;
 @Autowired FileCleanupService cleanup;
 @Autowired com.officemanipulator.storage.FileStorageService storage;
 @MockBean ClaudeOrchestrator ai;
 @BeforeEach void clean() {jdbc.update("DELETE FROM api_calls");jdbc.update("DELETE FROM jobs");}
 Job job(JobStatus state) {
  Job j=new Job();j.setStatus(state);j.setRequest("integration");j.setFormat(OutputFormat.CSV);
  j.setPlanJson("{\"title\":\"Current\",\"format\":\"csv\",\"outline\":[{\"order\":1,\"title\":\"Data\",\"summary\":\"Current data\"}]}");return jobs.saveAndFlush(j);
 }
 @Test void concurrentConfirmationsAcceptExactlyOne() throws Exception {
  Job j=job(JobStatus.PLAN_READY);
  try(var workers=Executors.newFixedThreadPool(6)) {
   CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
   for(int i=0;i<6;i++)results.add(workers.submit(()->{start.await();try {plans.confirmPlan(j.getId(),Map.of());return true;}catch(IllegalStateException e){return false;}}));
   start.countDown();int accepted=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS))accepted++;
   assertEquals(1,accepted);assertEquals(JobStatus.QUEUED,jobs.findById(j.getId()).orElseThrow().getStatus());
  }
 }
 @Test void competingReservationsCannotExceedBudgetAndSurviveNewServiceInstance() throws Exception {
  Job j=job(JobStatus.PENDING);
  try(var workers=Executors.newFixedThreadPool(4)) {
   CountDownLatch start=new CountDownLatch(1);List<Future<UUID>> results=new ArrayList<>();
   for(int i=0;i<4;i++)results.add(workers.submit(()->{start.await();try{return billing.reserve(j.getId(),"fake",new BigDecimal("0.75"));}catch(com.officemanipulator.exception.BudgetExceededException e){return null;}}));
   start.countDown();List<UUID> accepted=new ArrayList<>();for(var result:results){UUID id=result.get(15,TimeUnit.SECONDS);if(id!=null)accepted.add(id);}
   assertEquals(2,accepted.size());assertEquals(0,new BigDecimal("1.50").compareTo(billing.committed()));
   ApiBillingService restarted=new ApiBillingService(jdbc,BigDecimal.valueOf(2));assertEquals(0,restarted.committed().compareTo(billing.committed()));
   billing.settle(accepted.getFirst(),new BigDecimal("0.10"),10,20);
   assertEquals(0,new BigDecimal("0.85").compareTo(billing.committed()));
   billing.uncertain(accepted.getLast());assertEquals(0,new BigDecimal("0.10").compareTo(billing.spent()));
   assertEquals(0,new BigDecimal("0.75").compareTo(billing.uncertainAmount()));
  }
 }
 @Test void expiryCleansFailedJobsWithoutPublishedFilePath() throws Exception {
  Job j=job(JobStatus.FAILED);j.setExpiresAt(java.time.OffsetDateTime.now().minusHours(1));jobs.saveAndFlush(j);
  var file=storage.resolveOutputPath(j.getId(),OutputFormat.CSV);java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"unpublished output");
  assertNull(j.getFilePath());cleanup.deleteExpiredFiles();
  assertFalse(java.nio.file.Files.exists(file.getParent()));assertNotNull(jobs.findById(j.getId()).orElseThrow().getFilesCleanedAt());
  assertTrue(jobs.findExpiredWithFile(java.time.OffsetDateTime.now()).isEmpty());
 }
 @Test void atomicWorkerClaimOnlySucceedsOnce() {
  Job j=job(JobStatus.PENDING);
  assertEquals(1,jobs.transition(j.getId(),JobStatus.PENDING,JobStatus.PROCESSING));
  assertEquals(0,jobs.transition(j.getId(),JobStatus.PENDING,JobStatus.PROCESSING));
 }
 @Test void completeHttpPlanGeneratePatchConvertDownloadFlow() throws Exception {
  when(ai.runPlan(any())).thenAnswer(a -> {
   Job j=a.getArgument(0);
   return new com.officemanipulator.claude.dto.OrchestratorPlanResult("{\"title\":\"Current\",\"format\":\"csv\",\"outline\":[{\"order\":1,\"title\":\"Data\",\"summary\":\"Values\"}]}",10,20,new BigDecimal("0.01"));
  });
  when(ai.run(any())).thenAnswer(a -> {
   Job j=a.getArgument(0);var path=java.nio.file.Path.of("/tmp/office-backend-integration-files",j.getId().toString(),"output.csv");java.nio.file.Files.createDirectories(path.getParent());java.nio.file.Files.writeString(path,"name\nORIGINAL\n");
   assertNotNull(j.getPlanConfirmedAt());
   return new com.officemanipulator.claude.dto.OrchestratorResult(path,20,30,new BigDecimal("0.02"));
  });
  when(ai.runEdit(any(),anyString())).thenAnswer(a -> {
   Job j=a.getArgument(0);String content=a.getArgument(1);assertTrue(content.contains("LATEST_EDIT"));assertEquals(OutputFormat.PDF,j.getFormat());
   var path=java.nio.file.Path.of("/tmp/office-backend-integration-files",j.getId().toString(),"output.pdf");java.nio.file.Files.createDirectories(path.getParent());
   try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()){
    var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
    try(var cs=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)){cs.beginText();cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);cs.newLineAtOffset(40,700);cs.showText("LATEST_EDIT");cs.endText();}pdf.save(path.toFile());
   }
   return new com.officemanipulator.claude.dto.OrchestratorResult(path,30,40,new BigDecimal("0.03"));
  });
  var created=http.postForEntity("/api/chat",Map.of("message","Make a CSV","format","CSV"),com.fasterxml.jackson.databind.JsonNode.class);
  assertEquals(HttpStatus.ACCEPTED,created.getStatusCode());UUID id=UUID.fromString(created.getBody().path("jobId").asText());
  awaitState(id,JobStatus.PLAN_READY);
  assertEquals(HttpStatus.ACCEPTED,http.postForEntity("/api/jobs/"+id+"/plan/confirm",Map.of(),String.class).getStatusCode());
  awaitState(id,JobStatus.DONE);assertEquals(0,new BigDecimal("0.03").compareTo(jobs.findById(id).orElseThrow().getCostUsd()));
  var preview=http.getForObject("/api/files/"+id+"/preview",com.fasterxml.jackson.databind.node.ObjectNode.class);
  ((com.fasterxml.jackson.databind.node.ArrayNode)preview.path("csvRows").get(1)).set(0,"LATEST_EDIT");
  var patched=http.exchange("/api/jobs/"+id+"/content",HttpMethod.PATCH,new HttpEntity<>(preview),com.fasterxml.jackson.databind.JsonNode.class);
  assertEquals(HttpStatus.ACCEPTED,patched.getStatusCode());UUID current=UUID.fromString(patched.getBody().path("jobId").asText());awaitState(current,JobStatus.DONE);
  var converted=http.postForEntity("/api/jobs/"+current+"/convert",Map.of("format","pdf"),com.fasterxml.jackson.databind.JsonNode.class);
  assertEquals(HttpStatus.ACCEPTED,converted.getStatusCode());UUID pdfId=UUID.fromString(converted.getBody().path("jobId").asText());awaitState(pdfId,JobStatus.DONE);
  var file=http.getForEntity("/api/files/"+pdfId+"/download",byte[].class);assertEquals(HttpStatus.OK,file.getStatusCode());assertTrue(file.getBody().length>100);
 }
 void awaitState(UUID id,JobStatus expected) throws Exception {
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
  while(System.nanoTime()<end){var j=jobs.findById(id).orElseThrow();if(j.getStatus()==expected)return;if(j.getStatus()==JobStatus.FAILED)fail(j.getError());Thread.sleep(20);}
  fail("Job did not reach "+expected);
 }
 @Test void httpRejectsCrossOriginAndSupportsSameOriginPatch() {
  assertEquals(HttpStatus.OK,http.getForEntity("/api/budget",String.class).getStatusCode());
  HttpHeaders cross=new HttpHeaders();cross.setOrigin("https://attacker.example");
  assertEquals(HttpStatus.FORBIDDEN,http.exchange("/api/budget",HttpMethod.GET,new HttpEntity<>(cross),String.class).getStatusCode());
  HttpHeaders local=new HttpHeaders();local.setContentType(MediaType.APPLICATION_JSON);
  // A missing job returns 404 rather than a CORS/method error for PATCH.
  var response=http.exchange("/api/jobs/"+UUID.randomUUID()+"/content",HttpMethod.PATCH,new HttpEntity<>("{}",local),String.class);
  assertEquals(HttpStatus.NOT_FOUND,response.getStatusCode());
 }
}
