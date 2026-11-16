package com.officemanipulator.llm;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Closed whitelist of supported LLM providers. Each one maps to a code-side adapter
 * ({@link LlmClient}) with a fixed endpoint — never a URL taken from a request, which would let
 * a caller point the server at an arbitrary host (SSRF). {@link #OPENAI_COMPATIBLE} (vLLM, Ollama,
 * LM Studio, Groq, Mistral, DeepSeek, OpenRouter, ...) is the one exception: its endpoint is
 * operator configuration ({@code OPENAI_COMPATIBLE_BASE_URL} in .env), still never request input.
 */
public enum LlmProvider {
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    OPENAI("openai"),
    OPENAI_COMPATIBLE("openai_compatible");

    private final String id;

    LlmProvider(String id) { this.id = id; }

    public String id() { return id; }

    public static LlmProvider parse(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(p -> p.id.equals(normalized)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Desteklenmeyen LLM sağlayıcısı: '" + raw
                        + "'. Geçerli değerler: " + Arrays.stream(values()).map(LlmProvider::id).collect(Collectors.joining(", "))));
    }
}
