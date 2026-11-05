package com.officemanipulator.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Provider-neutral chat + tool-use API the orchestrator talks to. One implementation per
 * {@link LlmProvider}; each owns its wire format and its fixed endpoint.
 */
public interface LlmClient {

    LlmProvider provider();

    Response chat(LlmSelection selection, Request request);

    /** Cheap credential check (a models-API lookup, no generation). Throws
     * {@link com.officemanipulator.config.ClaudeKeyVerifier.VerificationFailure} on failure. */
    void verify(String apiKey, String model);

    /** {@code inputSchema} is a JSON Schema object; {@code strict} asks providers that support it
     * to guarantee schema-conformant arguments. */
    record Tool(String name, String description, JsonNode inputSchema, boolean strict) {}

    /** {@code signature} is opaque provider state that must be echoed back with the call on the
     * next turn (Gemini's thoughtSignature); null for providers that don't need it. */
    record ToolCall(String id, String name, JsonNode input, String signature) {}

    record ToolResult(String toolCallId, String toolName, String content) {}

    /** Exactly one of: user text, assistant turn (text and/or tool calls), or a tool result. */
    record Message(Role role, String text, List<ToolCall> toolCalls, ToolResult toolResult) {
        public enum Role { USER, ASSISTANT }

        public static Message user(String text) { return new Message(Role.USER, text, List.of(), null); }

        public static Message assistant(Response response) {
            return new Message(Role.ASSISTANT, response.text(), response.toolCalls(), null);
        }

        public static Message toolResult(ToolCall call, String content) {
            return new Message(Role.USER, null, List.of(), new ToolResult(call.id(), call.name(), content));
        }
    }

    /** {@code forcedTool}: name of the tool the model must call, or null for automatic choice. */
    record Request(String system, List<Message> messages, List<Tool> tools, String forcedTool, long maxTokens) {}

    record Response(String text, List<ToolCall> toolCalls, long inputTokens, long outputTokens) {
        public ToolCall firstToolCall(String name) {
            return toolCalls.stream().filter(c -> name == null || c.name().equals(name)).findFirst().orElse(null);
        }
    }
}
