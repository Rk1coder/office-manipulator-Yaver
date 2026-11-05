package com.officemanipulator.llm;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Closed whitelist of supported LLM providers. Each one maps to a code-side adapter
 * ({@link LlmClient}) with a fixed, hard-coded endpoint — never a URL taken from a request,
 * which would let a caller point the server at an arbitrary host (SSRF). Adding a provider
 * means adding an enum constant plus its adapter class, not a configurable endpoint.
 */
public enum LlmProvider {
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    OPENAI("openai");

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
