package com.officemanipulator.claude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.officemanipulator.domain.*;
import java.util.regex.Pattern;

final class PlanQuality {
 private PlanQuality() {}
 static void prepare(JsonNode plan, Job job) {
  JsonNode outline=plan.path("outline");
  if(!outline.isArray() || outline.isEmpty() || outline.size()>40) throw new IllegalArgumentException("Plan must contain 1-40 complete sections.");
  int words=0,index=0;
  for(JsonNode entry:outline) {
   if(!(entry instanceof ObjectNode object) || entry.path("title").asText().isBlank() || !entry.path("content").isTextual())
    throw new IllegalArgumentException("Every section needs a title and complete textual content.");
   object.put("order",++index);
   if(entry.path("imageNeeded").asBoolean() && entry.path("imagePrompt").asText("").isBlank())
    throw new IllegalArgumentException("Requested image is missing its imagePrompt.");
   String content=entry.path("content").asText().strip();
   words+=content.isEmpty()?0:content.split("\\s+").length;
  }
  String request=job.getRequest()==null?"":job.getRequest();
  var count=Pattern.compile("(?iu)(\\d{2,4})\\s*[-–]\\s*(\\d{2,4})\\s*(?:kelime|word)").matcher(request);
  if(count.find() && (job.getFormat().generationFormat()==OutputFormat.DOCX || job.getFormat()==OutputFormat.PDF)) {
   int maximum=Integer.parseInt(count.group(2));
   if(words>maximum) throw new IllegalArgumentException("Plan is too long: " + words + " words. The user requested " + count.group(1) + "-" + maximum + " words. Shorten the complete content to at most " + maximum + " whitespace-separated words INCLUDING tables and headings; remove repetition, not key facts.");
   int minimum=(int)(Integer.parseInt(count.group(1))*.8);
   if(words<minimum) throw new IllegalArgumentException("Incomplete plan: only " + words + " words. Write all requested sections with at least " + minimum + " words of final content; do not stop after an outline or table.");
  }
  if(job.getFormat().generationFormat()==OutputFormat.PPTX) {
   var slides=Pattern.compile("(?iu)\\b(\\d{1,2})\\s*(?:slayt|slides?)").matcher(request);
   if(slides.find() && outline.size()!=Integer.parseInt(slides.group(1))) throw new IllegalArgumentException("Plan must contain exactly " + slides.group(1) + " slides.");
  }
 }
}
