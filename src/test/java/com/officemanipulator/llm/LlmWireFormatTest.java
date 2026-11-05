package com.officemanipulator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.officemanipulator.claude.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmWireFormatTest {
    private static JsonNode json(String s) throws Exception { return HttpJson.JSON.readTree(s); }

    private static LlmClient.Request planTurn(LlmClient.Response previous) {
        var call = previous.toolCalls().getFirst();
        return new LlmClient.Request("SYS", List.of(LlmClient.Message.user("hi"), LlmClient.Message.assistant(previous),
                LlmClient.Message.toolResult(call, "ERROR: fix it")), List.of(new ToolRegistry().planTool("DOCX")), "submit_plan", 1000);
    }

    @Test void openAiRoundTripsForcedToolCalls() throws Exception {
        var response = OpenAiLlmClient.fromBody(json("""
                {"choices":[{"message":{"content":null,"tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"submit_plan","arguments":"{\\"title\\":\\"T\\"}"}}]}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7}}"""));
        assertEquals("T", response.firstToolCall("submit_plan").input().path("title").asText());
        assertEquals(11, response.inputTokens());
        assertEquals(7, response.outputTokens());

        JsonNode body = OpenAiLlmClient.toBody("gpt-x", planTurn(response));
        assertEquals("system", body.at("/messages/0/role").asText());
        assertEquals("call_1", body.at("/messages/2/tool_calls/0/id").asText());
        assertEquals("{\"title\":\"T\"}", body.at("/messages/2/tool_calls/0/function/arguments").asText());
        assertEquals("tool", body.at("/messages/3/role").asText());
        assertEquals("call_1", body.at("/messages/3/tool_call_id").asText());
        assertEquals("submit_plan", body.at("/tool_choice/function/name").asText());
        assertEquals("object", body.at("/tools/0/function/parameters/type").asText());
    }

    @Test void geminiKeepsThoughtSignatureAndAlternatesRoles() throws Exception {
        var response = GeminiLlmClient.fromBody(json("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"thinking","thought":true},
                 {"functionCall":{"name":"submit_plan","args":{"title":"T"}},"thoughtSignature":"SIG"}]}}],
                 "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"thoughtsTokenCount":2}}"""));
        assertNull(response.text(), "thought summaries are not answer text");
        assertEquals(5, response.outputTokens(), "thinking tokens are billed as output");

        JsonNode body = GeminiLlmClient.toBody(planTurn(response));
        assertEquals("SYS", body.at("/systemInstruction/parts/0/text").asText());
        assertEquals("model", body.at("/contents/1/role").asText());
        assertEquals("SIG", body.at("/contents/1/parts/0/thoughtSignature").asText());
        assertEquals("user", body.at("/contents/2/role").asText());
        assertEquals("submit_plan", body.at("/contents/2/parts/0/functionResponse/name").asText());
        assertEquals("ANY", body.at("/toolConfig/functionCallingConfig/mode").asText());
        assertEquals("object", body.at("/tools/0/functionDeclarations/0/parametersJsonSchema/type").asText());
    }

    @Test void adapterEndpointsAreFixedHttpsHosts() {
        assertEquals("https://api.openai.com/v1", OpenAiLlmClient.BASE_URL);
        assertTrue(GeminiLlmClient.BASE_URL.startsWith("https://generativelanguage.googleapis.com/"));
        assertThrows(IllegalArgumentException.class, () -> HttpJson.requireSafeModel("x/../../y"));
    }

    @Test void registryToolsKeepTheirJsonSchema() {
        var tools = new ToolRegistry().allTools();
        assertFalse(tools.isEmpty());
        tools.forEach(t -> assertEquals("object", t.inputSchema().path("type").asText(), t.name()));
        assertTrue(new ToolRegistry().planTool("DOCX").strict());
    }
}
