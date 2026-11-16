package com.officemanipulator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.officemanipulator.config.ClaudeKeyVerifier.VerificationFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.regex.Pattern;

/**
 * Any server speaking the OpenAI Chat Completions API: vLLM, Ollama, LM Studio, Groq, Mistral,
 * DeepSeek, OpenRouter, ... The endpoint comes only from {@code OPENAI_COMPATIBLE_BASE_URL}
 * (operator .env), never from a request. Tool calling must be enabled on the server
 * (vLLM: {@code --enable-auto-tool-choice --tool-call-parser <parser>}).
 */
@Component
public class OpenAiCompatibleLlmClient extends OpenAiLlmClient {
    // Hugging Face style ids ("Qwen/Qwen2.5-72B-Instruct") have a slash; the id only goes in the JSON body.
    private static final Pattern SAFE_MODEL = Pattern.compile("[a-zA-Z0-9._:/-]{1,128}");

    private final String baseUrl;

    @Autowired
    public OpenAiCompatibleLlmClient(@Value("${app.llm.openai-compatible.base-url:}") String baseUrl) {
        this(HttpJson.defaultClient(), baseUrl);
    }

    OpenAiCompatibleLlmClient(HttpClient client, String baseUrl) {
        super(client, "OpenAI-uyumlu sunucu");
        String trimmed = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        if (!trimmed.isEmpty()) {
            URI uri = URI.create(trimmed);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("OPENAI_COMPATIBLE_BASE_URL http(s)://host[:port]/v1 biçiminde olmalı");
            }
        }
        this.baseUrl = trimmed;
    }

    @Override public LlmProvider provider() { return LlmProvider.OPENAI_COMPATIBLE; }

    /** Lists the server's models (vLLM has no per-model lookup) and checks the chosen one is served. */
    @Override
    public void verify(String apiKey, String model) {
        if (baseUrl.isEmpty()) throw new VerificationFailure(HttpStatus.BAD_REQUEST, "Sunucu adresi tanımlı değil: .env dosyasında OPENAI_COMPATIBLE_BASE_URL ayarlayın.");
        String wanted = requireSafeModel(model);
        JsonNode models = http.verify(URI.create(endpoint() + "/models"), auth(apiKey));
        for (JsonNode served : models.path("data")) {
            if (served.path("id").asText().equals(wanted)) return;
        }
        throw new VerificationFailure(HttpStatus.BAD_REQUEST, "Seçili model bu sunucuda yüklü değil. Sunucunun /models listesindeki adı girin.");
    }

    @Override
    public Response chat(LlmSelection selection, Request request) {
        String forced = request.forcedTool();
        if (forced == null) return send(selection, request, false);
        // Ollama/LM Studio ignore a named tool_choice; saying it in the prompt makes them call it.
        String rule = "You must respond ONLY by calling the function `" + forced + "`. Never answer in plain text.";
        Response response = asForcedCall(send(selection, withRule(request, rule), false), forced);
        if (!response.toolCalls().isEmpty() || (response.text() != null && !response.text().isBlank())) return response;
        // Ollama drops a tool call its parser can't read and returns nothing at all (seen with
        // qwen2.5:7b); one retry without tools, in JSON mode, gets the same arguments as text.
        Tool tool = request.tools().stream().filter(t -> t.name().equals(forced)).findFirst().orElse(null);
        if (tool == null) return response;
        String jsonRule = "Reply with ONLY one JSON object: the arguments of `" + forced + "`, matching this JSON schema:\n" + tool.inputSchema();
        Request retry = withRule(new Request(request.system(), request.messages(), java.util.List.of(), null, request.maxTokens()), jsonRule);
        Response second = asForcedCall(send(selection, retry, true), forced);
        return new Response(second.text(), second.toolCalls(),
                response.inputTokens() + second.inputTokens(), response.outputTokens() + second.outputTokens());
    }

    private static Request withRule(Request request, String rule) {
        return new Request(request.system() == null ? rule : request.system() + "\n\n" + rule,
                request.messages(), request.tools(), request.forcedTool(), request.maxTokens());
    }

    private Response send(LlmSelection selection, Request request, boolean jsonMode) {
        var body = toBody(requireSafeModel(selection.model()), request);
        // No output cap: the app asks for 32k, which a self-hosted model's context (vLLM: 8k-32k) rejects
        // outright; left out, the server caps output at whatever context remains.
        body.remove("max_completion_tokens");
        if (jsonMode) body.putObject("response_format").put("type", "json_object");
        try {
            return fromBody(http.post(URI.create(endpoint() + "/chat/completions"), auth(selection.apiKey()), body));
        } catch (IllegalStateException e) {
            // Some servers reject a forced tool_choice outright (OpenRouter's AtlasCloud route for
            // qwen3.5-122b: HTTP 400); the system prompt already demands the call, so retry with "auto".
            if (!body.path("tool_choice").isObject() || e.getMessage() == null || !e.getMessage().contains("(HTTP 400)")) throw e;
            body.put("tool_choice", "auto");
            return fromBody(http.post(URI.create(endpoint() + "/chat/completions"), auth(selection.apiKey()), body));
        }
    }

    /** A model that still put the arguments in plain text (often fenced JSON) gets them treated as the forced call. */
    static Response asForcedCall(Response response, String forced) {
        if (!response.toolCalls().isEmpty() || response.text() == null) return response;
        String text = response.text().strip().replaceAll("(?s)^```(?:json)?\\s*(.*?)\\s*```$", "$1");
        try {
            JsonNode input = HttpJson.JSON.readTree(text);
            if (!input.isObject()) return response;
            return new Response(null, java.util.List.of(new ToolCall("text_" + forced, forced, input, null)),
                    response.inputTokens(), response.outputTokens());
        } catch (java.io.IOException e) {
            return response;
        }
    }

    private String endpoint() {
        if (baseUrl.isEmpty()) throw new IllegalStateException("OPENAI_COMPATIBLE_BASE_URL ayarlanmalı (örn. http://vllm:8000/v1)");
        return baseUrl;
    }

    static String requireSafeModel(String model) {
        if (model == null || !SAFE_MODEL.matcher(model).matches() || model.contains("..")) {
            throw new IllegalArgumentException("Geçersiz model adı.");
        }
        return model;
    }
}
