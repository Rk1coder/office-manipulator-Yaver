package com.officemanipulator.claude;
import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.config.ClaudeClientProvider;
import com.officemanipulator.domain.*;
import com.officemanipulator.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StreamingCompletionTest {
 @Test void longPlanIsAccumulatedFromStreamBeforeUsageAndResultAreCommitted() throws Exception {
  var client=mock(AnthropicClient.class,RETURNS_DEEP_STUBS);var provider=mock(ClaudeClientProvider.class);
  when(provider.client()).thenReturn(client);when(provider.model()).thenReturn("test");
  String[] json={
   """ 
   {"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant","model":"test","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":100,"output_tokens":0}}}
   """,
   """
   {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool_1","name":"submit_plan","input":{},"caller":{"type":"direct"}}}
   """,
   """
   {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"title\\":\\"Test\\",\\"outline\\":[{\\"title\\":\\"Özet\\",\\"content\\":\\"Metin\\"}]}"}}
   """,
   "{\"type\":\"content_block_stop\",\"index\":0}",
   "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":20}}",
   "{\"type\":\"message_stop\"}"};
  List<RawMessageStreamEvent> events=new ArrayList<>();
  for(String item:json)events.add(ObjectMappers.jsonMapper().readValue(item,RawMessageStreamEvent.class));
  StreamResponse<RawMessageStreamEvent> stream=mock(StreamResponse.class);
  when(stream.stream()).thenReturn(events.stream());when(client.messages().createStreaming(any(MessageCreateParams.class))).thenReturn(stream);
  var ai=new ClaudeOrchestrator(provider,new ToolRegistry(),null,new FileStorageService("/tmp"),new ObjectMapper());
  ReflectionTestUtils.setField(ai,"maxTokens",32000L);ReflectionTestUtils.setField(ai,"inputPricePerMillion",new BigDecimal("3"));ReflectionTestUtils.setField(ai,"outputPricePerMillion",new BigDecimal("15"));
  Job job=new Job();job.setRequest("Plan hazırla");job.setFormat(OutputFormat.DOCX);
  var result=ai.runPlan(job);
  assertEquals("Test",new ObjectMapper().readTree(result.planJson()).get("title").asText());
  assertEquals(100,result.inputTokens());assertEquals(20,result.outputTokens());
  verify(stream).close();verify(client.messages(),never()).create(any(MessageCreateParams.class));
 }
}
