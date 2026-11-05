package com.officemanipulator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** OpenAI Chat Completions with function calling. Fixed endpoint, by design. */
@Component
public class OpenAiLlmClient implements LlmClient {
    static final String BASE_URL = "https://api.openai.com/v1";

    private final HttpJson http;

    public OpenAiLlmClient() { this(HttpJson.defaultClient()); }

    OpenAiLlmClient(HttpClient client) { this.http = new HttpJson(client, "OpenAI"); }

    @Override public LlmProvider provider() { return LlmProvider.OPENAI; }

    @Override
    public void verify(String apiKey, String model) {
        http.verify(URI.create(BASE_URL + "/models/" + HttpJson.requireSafeModel(model)), auth(apiKey));
    }

    @Override
    public Response chat(LlmSelection selection, Request request) {
        JsonNode response = http.post(URI.create(BASE_URL + "/chat/completions"), auth(selection.apiKey()),
                toBody(HttpJson.requireSafeModel(selection.model()), request));
        return fromBody(response);
    }

    private static Map<String, String> auth(String apiKey) { return Map.of("Authorization", "Bearer " + apiKey); }

    static ObjectNode toBody(String model, Request request) {
        ObjectNode body = HttpJson.JSON.createObjectNode().put("model", model).put("max_completion_tokens", request.maxTokens());
        ArrayNode messages = body.putArray("messages");
        if (request.system() != null) messages.addObject().put("role", "system").put("content", request.system());
        for (Message message : request.messages()) {
            if (message.toolResult() != null) {
                messages.addObject().put("role", "tool").put("tool_call_id", message.toolResult().toolCallId())
                        .put("content", message.toolResult().content());
            } else if (message.role() == Message.Role.USER && message.images().isEmpty()) {
                messages.addObject().put("role", "user").put("content", message.text());
            } else if (message.role() == Message.Role.USER) {
                ArrayNode content = messages.addObject().put("role", "user").putArray("content");
                for (Image image : message.images()) {
                    content.addObject().put("type", "image_url").putObject("image_url")
                            .put("url", "data:" + image.mediaType() + ";base64," + image.base64());
                }
                content.addObject().put("type", "text").put("text", message.text());
            } else {
                ObjectNode assistant = messages.addObject().put("role", "assistant");
                if (message.text() != null) assistant.put("content", message.text()); else assistant.putNull("content");
                if (!message.toolCalls().isEmpty()) {
                    ArrayNode calls = assistant.putArray("tool_calls");
                    for (ToolCall call : message.toolCalls()) {
                        calls.addObject().put("id", call.id()).put("type", "function").putObject("function")
                                .put("name", call.name()).put("arguments", call.input().toString());
                    }
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            // strict is not forwarded: OpenAI's strict mode accepts a narrower schema subset than
            // these tools use; arguments are validated downstream (PlanQuality, ToolDispatcher) anyway.
            for (Tool tool : request.tools()) {
                tools.addObject().put("type", "function").putObject("function").put("name", tool.name())
                        .put("description", tool.description()).set("parameters", tool.inputSchema());
            }
            body.put("parallel_tool_calls", false);
            if (request.forcedTool() != null) {
                body.putObject("tool_choice").put("type", "function").putObject("function").put("name", request.forcedTool());
            } else {
                body.put("tool_choice", "auto");
            }
        }
        return body;
    }

    static Response fromBody(JsonNode body) {
        JsonNode message = body.path("choices").path(0).path("message");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            JsonNode input;
            try {
                input = HttpJson.JSON.readTree(function.path("arguments").asText("{}"));
            } catch (IOException e) {
                // Truncated/invalid arguments: surface as empty input so the tool's own validation reports it.
                input = HttpJson.JSON.createObjectNode();
            }
            calls.add(new ToolCall(call.path("id").asText(), function.path("name").asText(), input, null));
        }
        String text = message.path("content").isTextual() ? message.path("content").asText() : null;
        JsonNode usage = body.path("usage");
        return new Response(text, calls, usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
    }
}
