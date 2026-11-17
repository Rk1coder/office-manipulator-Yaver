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
 * With IMAGE_OPENAI_COMPATIBLE_BASE_URL set (e.g. OpenRouter), the same image models are called through
 * an OpenAI-compatible chat/completions endpoint instead ({@code modalities: ["image","text"]}), or with
 * IMAGE_API_STYLE=images through its /images/generations endpoint (FLUX, Seedream, GPT Image... billed per image).
 */
@Service
public class GeminiImageService {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageService.class);

    @org.springframework.beans.factory.annotation.Autowired
    private ApiBillingService billing;
    @org.springframework.beans.factory.annotation.Autowired
    private com.officemanipulator.domain.JobRepository jobs;
    @Value("${app.gemini.input-price-per-million:0}")
    private java.math.BigDecimal envInputPrice;
    @Value("${app.gemini.output-price-per-million:0}")
    private java.math.BigDecimal envOutputPrice;
    /** "images": the compatible endpoint's /images/generations, billed by IMAGE_PRICE_PER_IMAGE instead of tokens. */
    @Value("${app.gemini.api-style:chat}")
    private String apiStyle = "chat";
    @Value("${app.gemini.price-per-image:0}")
    private java.math.BigDecimal pricePerImage = java.math.BigDecimal.ZERO;
    /** Lets image generation reuse the Gemini key entered for the LLM provider. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.officemanipulator.config.LlmClientProvider llm;
    private static final int OUTPUT_TOKENS = 8192;
    private final RestClient restClient;
    private final FileStorageService storageService;
    private final ObjectMapper objectMapper;

    private final String model;
    /** Operator .env only (never from a request); blank = Google's native Gemini API. */
    private final String compatibleBaseUrl;
    private final String envApiKey;
    private final boolean envEnabled;
    private final com.officemanipulator.config.AppSettingsService settings;

    public GeminiImageService(FileStorageService storageService, ObjectMapper objectMapper,
            com.officemanipulator.config.AppSettingsService settings,
            @Value("${app.gemini.api-key:}") String envApiKey,
            @Value("${app.gemini.model:gemini-3.1-flash-image-preview}") String model,
            @Value("${app.gemini.enabled:false}") boolean envEnabled) {
        this(storageService, objectMapper, settings, envApiKey, model, envEnabled, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GeminiImageService(FileStorageService storageService, ObjectMapper objectMapper,
            com.officemanipulator.config.AppSettingsService settings,
            @Value("${app.gemini.api-key:}") String envApiKey,
            @Value("${app.gemini.model:gemini-3.1-flash-image-preview}") String model,
            @Value("${app.gemini.enabled:false}") boolean envEnabled,
            @Value("${app.gemini.openai-compatible-base-url:}") String compatibleBaseUrl) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.envApiKey = envApiKey;
        this.model = model;
        this.envEnabled = envEnabled;
        String base = compatibleBaseUrl == null ? "" : compatibleBaseUrl.trim().replaceAll("/+$", "");
        if (!base.isEmpty() && !base.matches("https?://[^/\\s]+(/.*)?"))
            throw new IllegalArgumentException("IMAGE_OPENAI_COMPATIBLE_BASE_URL http(s)://host[:port]/v1 biçiminde olmalı");
        this.compatibleBaseUrl = base;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(20)).build());
        factory.setReadTimeout(java.time.Duration.ofMinutes(2));
        this.restClient = RestClient.builder().requestFactory(factory)
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    public static final String KEY_API_KEY = "gemini.api-key";
    public static final String KEY_ENABLED = "gemini.enabled";
    public static final String KEY_INPUT_PRICE = "gemini.input-price-per-million";
    public static final String KEY_OUTPUT_PRICE = "gemini.output-price-per-million";

    /** Image key, else .env GEMINI_API_KEY, else the Gemini key saved for the LLM provider. */
    private String apiKey() {
        String key = settings.get(KEY_API_KEY, envApiKey);
        if ((key == null || key.isBlank()) && llm != null) key = llm.configuredKey(compatibleBaseUrl.isEmpty()
                ? com.officemanipulator.llm.LlmProvider.GEMINI : com.officemanipulator.llm.LlmProvider.OPENAI_COMPATIBLE);
        return key;
    }

    /** Host of the OpenAI-compatible image endpoint, or null when images come from Google's Gemini API. */
    public String endpointHost() {
        return compatibleBaseUrl.isEmpty() ? null : java.net.URI.create(compatibleBaseUrl).getHost();
    }

    public String model() { return model; }

    /** True when the key comes from the LLM provider settings rather than the image settings. */
    public boolean keySharedWithLlm() {
        String own = settings.get(KEY_API_KEY, envApiKey);
        return (own == null || own.isBlank()) && keyConfigured();
    }

    public java.math.BigDecimal inputPrice() { return price(KEY_INPUT_PRICE, envInputPrice); }
    public java.math.BigDecimal outputPrice() { return price(KEY_OUTPUT_PRICE, envOutputPrice); }

    private boolean perImage() { return !compatibleBaseUrl.isEmpty() && "images".equals(apiStyle); }

    /** The budget guard needs a positive rate (per token, or per image for /images/generations) before any paid image call. */
    public boolean pricesConfigured() {
        return perImage() ? pricePerImage.signum() > 0 : inputPrice().signum() > 0 && outputPrice().signum() > 0;
    }

    private java.math.BigDecimal price(String key, java.math.BigDecimal envDefault) {
        String stored = settings.get(key, null);
        try {
            return stored == null || stored.isBlank() ? (envDefault == null ? java.math.BigDecimal.ZERO : envDefault) : new java.math.BigDecimal(stored.trim());
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private boolean enabled() {
        String override = settings.get(KEY_ENABLED, null);
        return override != null ? Boolean.parseBoolean(override) : envEnabled;
    }

    /** Enabled, keyed and priced; without prices images are skipped instead of failing the job. */
    public boolean isEnabled() {
        String apiKey = apiKey();
        return enabled() && apiKey != null && !apiKey.isBlank() && pricesConfigured();
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
        if (!pricesConfigured())
            throw new IllegalStateException("Configure Gemini token prices before enabling image generation");
        UUID reservation = billing.reserve(jobId, "gemini", perImage() ? pricePerImage
                : cost(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 8192L, OUTPUT_TOKENS));
        boolean settled = false;
        try {
            String base64 = null;
            int input, output;
            java.math.BigDecimal amount = null;
            if (perImage()) {
                Map<String, Object> body = Map.of("model", model, "prompt", prompt, "n", 1, "response_format", "b64_json");
                String response = restClient.post()
                        .uri(java.net.URI.create(compatibleBaseUrl + "/images/generations"))
                        .header("Authorization", "Bearer " + apiKey())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);
                JsonNode image = objectMapper.readTree(response).path("data").path(0);
                input = 0; output = 0; amount = pricePerImage;
                base64 = image.path("b64_json").asText(null);
                String url = image.path("url").asText("");
                // Some models ignore response_format and answer with a link: only https, read with the same client.
                if (base64 == null && url.startsWith("https://")) base64 = Base64.getEncoder().encodeToString(
                        restClient.get().uri(java.net.URI.create(url)).retrieve().body(byte[].class));
            } else if (compatibleBaseUrl.isEmpty()) {
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
                input = usage.path("promptTokenCount").asInt();
                output = Math.max(0, usage.path("totalTokenCount").asInt() - input);
                JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        JsonNode inline = part.path("inlineData");
                        if (inline.has("data")) {
                            base64 = inline.path("data").asText(null);
                            break;
                        }
                    }
                }
            } else {
                Map<String, Object> body = Map.of("model", model, "modalities", java.util.List.of("image", "text"),
                        "messages", java.util.List.of(Map.of("role", "user", "content", prompt)));
                String response = restClient.post()
                        .uri(java.net.URI.create(compatibleBaseUrl + "/chat/completions"))
                        .header("Authorization", "Bearer " + apiKey())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                JsonNode root = objectMapper.readTree(response);
                JsonNode usage = root.path("usage");
                if (!usage.has("prompt_tokens") || !usage.has("completion_tokens"))
                    throw new IllegalStateException("Image response is missing usage; cost reservation retained");
                input = usage.path("prompt_tokens").asInt();
                output = usage.path("completion_tokens").asInt();
                // OpenRouter: message.images[].image_url.url = "data:image/png;base64,...."
                String url = root.path("choices").path(0).path("message").path("images").path(0).path("image_url").path("url").asText("");
                if (url.startsWith("data:image/") && url.contains(";base64,")) base64 = url.substring(url.indexOf(";base64,") + 8);
            }
            if (amount == null) amount = cost(input, output);
            billing.settle(reservation, amount, input, output);
            settled = true;
            jobs.addUsage(jobId, input, output, amount);
            if (base64 == null) {
                throw new IllegalStateException("Gemini returned no image for section " + index);
            }

            byte[] imageBytes = asPng(Base64.getDecoder().decode(base64));
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
    /** Documents embed img_N.png as PNG; JPEG answers (common on /images/generations) are converted. */
    static byte[] asPng(byte[] bytes) throws IOException {
        if (bytes.length >= 4 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return bytes;
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("Unsupported image format (PNG or JPEG expected)");
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private java.math.BigDecimal cost(long input, long output) {
        return inputPrice().multiply(java.math.BigDecimal.valueOf(input))
            .add(outputPrice().multiply(java.math.BigDecimal.valueOf(output)))
            .divide(java.math.BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.UP);
    }
}
