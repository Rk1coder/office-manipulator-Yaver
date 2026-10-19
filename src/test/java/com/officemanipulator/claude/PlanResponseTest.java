package com.officemanipulator.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
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

class PlanResponseTest {
    @Test void readsStructuredPlanPreservingQuotesInsteadOfUntrustedText() throws Exception {
        var client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        var provider = mock(ClaudeClientProvider.class);
        when(provider.client()).thenReturn(client); when(provider.model()).thenReturn("test");
        var response = mock(Message.class); var usage = mock(Usage.class);
        when(response.usage()).thenReturn(usage);
        var tool = mock(ToolUseBlock.class);
        when(tool.name()).thenReturn("submit_plan");
        when(tool._input()).thenReturn(JsonValue.from(Map.of("title", "Atölye: \"Doğru kutu\"", "outline",List.of(Map.of("title","Özet","content","Metin")))));

        when(response.content()).thenReturn(List.of(ContentBlock.ofToolUse(tool)));
        when(client.messages().create(any(MessageCreateParams.class))).thenAnswer(inv -> {
            MessageCreateParams params = inv.getArgument(0);
            assertTrue(params.toolChoice().orElseThrow().isTool());
            assertEquals("submit_plan", params.toolChoice().orElseThrow().tool().orElseThrow().name());
            return response;
        });
        var mapper = new ObjectMapper();
        var ai = new ClaudeOrchestrator(provider,new ToolRegistry(),null,new FileStorageService("/tmp"),mapper);
        ReflectionTestUtils.setField(ai,"maxTokens",2000L);
        ReflectionTestUtils.setField(ai,"inputPricePerMillion",BigDecimal.valueOf(3));
        ReflectionTestUtils.setField(ai,"outputPricePerMillion",BigDecimal.valueOf(15));
        var job = new Job(); job.setRequest("Kısa sunum hazırla"); job.setFormat(OutputFormat.PPTX);
        assertEquals("Atölye: \"Doğru kutu\"",mapper.readTree(ai.runPlan(job).planJson()).path("title").asText());
    }
}
