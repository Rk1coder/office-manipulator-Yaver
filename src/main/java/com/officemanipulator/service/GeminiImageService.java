package com.officemanipulator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Generates images via Gemini's native image generation (generateContent with
 * responseModalities: ["TEXT","IMAGE"] — the "Nano Banana" family of models) for use in
 * generated documents. Disabled (returns empty) when no API key is configured.
 */
@Service
public class GeminiImageService {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageService.class);

    @org.springframework.beans.factory.annotation.Autowired
    private ApiBillingService billing;
    @org.springframework.beans.factory.annotation.Autowired
    private com.officemanipulator.domain.JobRepository jobs;
    @Value("${app.gemini.input-price-per-million:0}")
    private java.math.BigDecimal inputPrice;
    @Value("${app.gemini.output-price-per-million:0}")
    private java.math.BigDecimal outputPrice;
    private static final int OUTPUT_TOKENS = 8192;
    private final RestClient restClient;
    private final FileStorageService storageService;
    private final ObjectMapper objectMapper;

    private final String model;
    private final String envApiKey;
    private final boolean envEnabled;
    private final com.officemanipulator.config.AppSettingsService settings;

    public GeminiImageService(FileStorageService storageService, ObjectMapper objectMapper,
            com.officemanipulator.config.AppSettingsService settings,
            @Value("${app.gemini.api-key:}") String envApiKey,
            @Value("${app.gemini.model:gemini-3.1-flash-image-preview}") String model,
            @Value("${app.gemini.enabled:false}") boolean envEnabled) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.envApiKey = envApiKey;
        this.model = model;
        this.envEnabled = envEnabled;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build());
        factory.setReadTimeout(java.time.Duration.ofMinutes(2));
        this.restClient = RestClient.builder().requestFactory(factory)
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    public static final String KEY_API_KEY = "gemini.api-key";
    public static final String KEY_ENABLED = "gemini.enabled";

    private String apiKey() {
        return settings.get(KEY_API_KEY, envApiKey);
    }

    private boolean enabled() {
        String override = settings.get(KEY_ENABLED, null);
        return override != null ? Boolean.parseBoolean(override) : envEnabled;
    }

    public boolean isEnabled() {
        String apiKey = apiKey();
        return enabled() && apiKey != null && !apiKey.isBlank();
    }

    public boolean isConfiguredFlag() { return enabled(); }

    public boolean keyConfigured() {
        String apiKey = apiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    public String maskedApiKey() {
        String apiKey = apiKey();
        if (apiKey == null || apiKey.isBlank()) return null;
        return apiKey.length() <= 4 ? "****" : "****" + apiKey.substring(apiKey.length() - 4);
    }

    public Optional<Path> generateImage(String prompt, UUID jobId, String index) {
        if (!isEnabled()) return Optional.empty();

        if (!index.matches("[1-9][0-9]{0,3}")) throw new IllegalArgumentException("Invalid image index");
        if (inputPrice == null || outputPrice == null || inputPrice.signum() <= 0 || outputPrice.signum() <= 0)
            throw new IllegalStateException("Configure Gemini token prices before enabling image generation");
        UUID reservation = billing.reserve(jobId, "gemini", cost(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 8192L, OUTPUT_TOKENS));
        boolean settled = false;
        try {
            Map<String, Object> body = Map.of(
                    "contents", java.util.List.of(
                            Map.of("parts", java.util.List.of(Map.of("text", prompt)))
                    ),
                    "generationConfig", Map.of("responseModalities", java.util.List.of("TEXT", "IMAGE"), "maxOutputTokens", OUTPUT_TOKENS)
            );

            String response = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .header("x-goog-api-key", apiKey())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode usage = root.path("usageMetadata");
            if (!usage.has("promptTokenCount") || !usage.has("totalTokenCount"))
                throw new IllegalStateException("Gemini response is missing usage; cost reservation retained");
            int input = usage.path("promptTokenCount").asInt();
            int output = Math.max(0, usage.path("totalTokenCount").asInt() - input);
            var amount = cost(input, output);
            billing.settle(reservation, amount, input, output);
            settled = true;
            jobs.addUsage(jobId, input, output, amount);
            JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
            String base64 = null;
            if (parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode inline = part.path("inlineData");
                    if (inline.has("data")) {
                        base64 = inline.path("data").asText(null);
                        break;
                    }
                }
            }
            if (base64 == null) {
                throw new IllegalStateException("Gemini returned no image for section " + index);
            }

            byte[] imageBytes = Base64.getDecoder().decode(base64);
            Path jobDir = storageService.resolveJobDir(jobId);
            Files.createDirectories(jobDir);
            Path imagePath = jobDir.resolve("img_" + index + ".png");
            Files.write(imagePath, imageBytes);

            return Optional.of(imagePath);

        } catch (Exception e) {
            if (!settled) billing.uncertain(reservation);
            throw new IllegalStateException("Image generation failed for section " + index + ": " + e.getMessage(), e);
        }
    }
    private java.math.BigDecimal cost(long input, long output) {
        return inputPrice.multiply(java.math.BigDecimal.valueOf(input))
            .add(outputPrice.multiply(java.math.BigDecimal.valueOf(output)))
            .divide(java.math.BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.UP);
    }
}
