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
        return fromBody(http.post(URI.create(endpoint() + "/chat/completions"), auth(selection.apiKey()),
                toBody(requireSafeModel(selection.model()), request, "max_tokens")));
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
