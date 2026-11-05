package com.officemanipulator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gemini generateContent with function calling. Fixed endpoint, by design. */
@Component
public class GeminiLlmClient implements LlmClient {
    static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";

    private final HttpJson http;

    public GeminiLlmClient() { this(HttpJson.defaultClient()); }

    GeminiLlmClient(HttpClient client) { this.http = new HttpJson(client, "Gemini"); }

    @Override public LlmProvider provider() { return LlmProvider.GEMINI; }

    @Override
    public void verify(String apiKey, String model) {
        http.verify(URI.create(BASE_URL + HttpJson.requireSafeModel(model)), auth(apiKey));
    }

    @Override
    public Response chat(LlmSelection selection, Request request) {
        URI uri = URI.create(BASE_URL + HttpJson.requireSafeModel(selection.model()) + ":generateContent");
        return fromBody(http.post(uri, auth(selection.apiKey()), toBody(request)));
    }

    // Header, not ?key=: keeps the key out of URLs (and any proxy/access logs).
    private static Map<String, String> auth(String apiKey) { return Map.of("x-goog-api-key", apiKey); }

    static ObjectNode toBody(Request request) {
        ObjectNode body = HttpJson.JSON.createObjectNode();
        if (request.system() != null) body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.system());
        ArrayNode contents = body.putArray("contents");
        String lastRole = null;
        ArrayNode parts = null;
        for (Message message : request.messages()) {
            String role = message.role() == Message.Role.ASSISTANT ? "model" : "user";
            // Gemini expects alternating turns; fold consecutive same-role messages into one.
            if (!role.equals(lastRole)) {
                parts = contents.addObject().put("role", role).putArray("parts");
                lastRole = role;
            }
            if (message.toolResult() != null) {
                ObjectNode response = parts.addObject().putObject("functionResponse");
                response.put("name", message.toolResult().toolName());
                if (message.toolResult().toolCallId() != null) response.put("id", message.toolResult().toolCallId());
                response.putObject("response").put("result", message.toolResult().content());
                continue;
            }
            for (Image image : message.images()) {
                parts.addObject().putObject("inlineData").put("mimeType", image.mediaType()).put("data", image.base64());
            }
            if (message.text() != null) parts.addObject().put("text", message.text());
            for (ToolCall call : message.toolCalls()) {
                ObjectNode part = parts.addObject();
                ObjectNode functionCall = part.putObject("functionCall").put("name", call.name());
                if (call.id() != null) functionCall.put("id", call.id());
                functionCall.set("args", call.input());
                // Required by thinking models: the signature must come back with its function call.
                if (call.signature() != null) part.put("thoughtSignature", call.signature());
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
            for (Tool tool : request.tools()) {
                declarations.addObject().put("name", tool.name()).put("description", tool.description())
                        .set("parametersJsonSchema", tool.inputSchema());
            }
            ObjectNode config = body.putObject("toolConfig").putObject("functionCallingConfig");
            if (request.forcedTool() != null) {
                config.put("mode", "ANY").putArray("allowedFunctionNames").add(request.forcedTool());
            } else {
                config.put("mode", "AUTO");
            }
        }
        body.putObject("generationConfig").put("maxOutputTokens", request.maxTokens());
        return body;
    }

    static Response fromBody(JsonNode body) {
        String text = null;
        List<ToolCall> calls = new ArrayList<>();
        int index = 0;
        for (JsonNode part : body.path("candidates").path(0).path("content").path("parts")) {
            if (part.has("functionCall")) {
                JsonNode call = part.path("functionCall");
                String id = call.hasNonNull("id") ? call.path("id").asText() : "call_" + index;
                JsonNode args = call.path("args").isObject() ? call.path("args") : HttpJson.JSON.createObjectNode();
                calls.add(new ToolCall(id, call.path("name").asText(), args,
                        part.hasNonNull("thoughtSignature") ? part.path("thoughtSignature").asText() : null));
                index++;
            } else if (text == null && part.has("text") && !part.path("thought").asBoolean(false)) {
                text = part.path("text").asText();
            }
        }
        JsonNode usage = body.path("usageMetadata");
        long output = usage.path("candidatesTokenCount").asLong() + usage.path("thoughtsTokenCount").asLong();
        return new Response(text, calls, usage.path("promptTokenCount").asLong(), output);
    }
}
