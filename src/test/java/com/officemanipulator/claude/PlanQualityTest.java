package com.officemanipulator.claude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PlanQualityTest {
 @Test void rejectsShortContentAndRepairsOrdinalNumbers() throws Exception {
  var mapper=new ObjectMapper();var plan=mapper.readTree("""
    {"title":"Rapor","format":"pdf","outline":[{"order":0,"type":"section","title":"Özet","summary":"Kısa","content":"Çok kısa rapor.","imageNeeded":false},{"order":0,"type":"section","title":"Sonuç","summary":"Kısa","content":"Eksik."}]}
    """);
  Job job=new Job();job.setFormat(OutputFormat.PDF);job.setRequest("650-850 kelimelik rapor");
  assertThrows(IllegalArgumentException.class,()->PlanQuality.prepare(plan,job));
  job.setRequest("Kısa rapor");PlanQuality.prepare(plan,job);
  assertEquals(1,plan.path("outline").get(0).path("order").asInt());assertEquals(2,plan.path("outline").get(1).path("order").asInt());
 }
 @Test void rejectsPlanThatExceedsRequestedWordBudget() throws Exception {
  var plan=new ObjectMapper().readTree("{\"outline\":[{\"title\":\"Özet\",\"content\":\""+"sözcük ".repeat(1100)+"\"}]}");
  Job job=new Job();job.setFormat(OutputFormat.PDF);job.setRequest("650-850 kelimelik rapor");
  assertThrows(IllegalArgumentException.class,()->PlanQuality.prepare(plan,job));
 }

}
