package com.officemanipulator.api;

import com.officemanipulator.api.dto.ErrorResponse;
import com.officemanipulator.api.dto.SettingsResponse;
import com.officemanipulator.config.AppSettingsService;
import com.officemanipulator.config.ClaudeKeyVerifier;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.llm.LlmProvider;
import org.springframework.http.HttpStatus;
import com.officemanipulator.service.GeminiImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the Settings page read and change the default LLM provider (whitelisted only), its API
 * key/model, and the optional Gemini image key at runtime.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public static final String RECOMMENDED_MODEL = "claude-sonnet-4-6";
    public static final List<String> AVAILABLE_MODELS = List.of("claude-sonnet-4-6", "claude-sonnet-5");

    private final LlmClientProvider claudeClientProvider;
    private final AppSettingsService settingsService;
    private final GeminiImageService geminiImageService;

    public SettingsController(LlmClientProvider claudeClientProvider, AppSettingsService settingsService,
            GeminiImageService geminiImageService) {
        this.claudeClientProvider = claudeClientProvider;
        this.settingsService = settingsService;
        this.geminiImageService = geminiImageService;
    }

    // shortcut: suggestions are a static list (any id is still accepted and checked against the provider's models API); refresh it as providers ship new models, or list live via the models API.
    private static final Map<LlmProvider, List<String>> SUGGESTED_MODELS = Map.of(
            LlmProvider.ANTHROPIC, AVAILABLE_MODELS,
            LlmProvider.GEMINI, List.of("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite"),
            LlmProvider.OPENAI, List.of("gpt-5", "gpt-5-mini", "gpt-4.1"));

    /** Suggested models for the Settings dropdown, plus the stored/.env model if it isn't one of them. */
    private List<String> modelsFor(LlmProvider provider) {
        List<String> models = new java.util.ArrayList<>(SUGGESTED_MODELS.get(provider));
        String current = claudeClientProvider.model(provider);
        if (current != null && !current.isBlank() && !models.contains(current)) models.add(0, current);
        return models;
    }

    /** Any plain model id (also typed by hand); "Kontrol et" proves the key can use it. */
    private static boolean modelAllowed(String model) {
        return model.matches("[a-zA-Z0-9._:-]{1,128}") && !model.contains("..");
    }

    public record SettingsRequest(String claudeApiKey, String model, String geminiApiKey, Boolean geminiEnabled) {}
    /** {@code claudeApiKey} keeps its old name for the existing UI; it is the key of {@code provider}. */
    public record VerificationRequest(String claudeApiKey, String model, String provider) {
        public VerificationRequest(String claudeApiKey, String model) { this(claudeApiKey, model, null); }
    }
    public record VerificationError(String error, SettingsResponse settings) {}

    @GetMapping
    public SettingsResponse get() {
        return currentView();
    }

    @PostMapping
    public synchronized ResponseEntity<?> save(@RequestBody SettingsRequest body) {
        if ((body.claudeApiKey() != null && !body.claudeApiKey().isBlank())
                || (body.model() != null && !body.model().isBlank() && !body.model().equals(claudeClientProvider.model()))) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("LLM anahtarını veya modelini değiştirmek için Kontrol et düğmesini kullanın."));
        }

        if (body.geminiApiKey() != null) settingsService.set(GeminiImageService.KEY_API_KEY, body.geminiApiKey().isBlank() ? null : body.geminiApiKey());
        if (body.geminiEnabled() != null) settingsService.set(GeminiImageService.KEY_ENABLED, String.valueOf(body.geminiEnabled()));

        return ResponseEntity.ok(currentView());
    }

    @PostMapping("/verify")
    public synchronized ResponseEntity<?> verify(@RequestBody VerificationRequest body) {
        LlmProvider provider;
        try {
            provider = body.provider() == null || body.provider().isBlank() ? claudeClientProvider.defaultProvider() : LlmProvider.parse(body.provider());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        }
        String key = body.claudeApiKey() == null || body.claudeApiKey().isBlank()
                ? claudeClientProvider.configuredKey(provider) : body.claudeApiKey().trim();
        String model = body.model() == null || body.model().isBlank() ? claudeClientProvider.model(provider) : body.model().trim();
        if (key == null || key.isBlank() || key.length() > 4096 || key.chars().anyMatch(c -> c <= 32 || c >= 127))
            return ResponseEntity.badRequest().body(ErrorResponse.of("Geçerli bir API anahtarı girin."));
        if (model == null || !modelAllowed(model)) return ResponseEntity.badRequest().body(ErrorResponse.of("Geçerli bir model seçin."));
        boolean current = provider == claudeClientProvider.defaultProvider();
        try {
            claudeClientProvider.client(provider).verify(key, model);
            claudeClientProvider.activateVerified(provider, key, model);
            return ResponseEntity.ok(currentView());
        } catch (ClaudeKeyVerifier.VerificationFailure failure) {
            // A failed check of another provider never touches the active one.
            if (current && java.util.Objects.equals(key, claudeClientProvider.configuredKey())
                    && (failure.status() == HttpStatus.UNAUTHORIZED
                    || (failure.status() == HttpStatus.FORBIDDEN && model.equals(claudeClientProvider.model())))) {
                claudeClientProvider.invalidateVerification();
            }
            return ResponseEntity.status(failure.status()).body(new VerificationError(failure.getMessage(), currentView()));
        }
    }

    private SettingsResponse currentView() {
        return new SettingsResponse(
                claudeClientProvider.keyConfigured(),
                claudeClientProvider.keyVerified(),
                claudeClientProvider.maskedKey(),
                claudeClientProvider.model(),
                claudeClientProvider.defaultProvider() == LlmProvider.ANTHROPIC ? RECOMMENDED_MODEL : null,
                modelsFor(claudeClientProvider.defaultProvider()),
                geminiImageService.isConfiguredFlag(),
                geminiImageService.keyConfigured(),
                geminiImageService.maskedApiKey(),
                claudeClientProvider.defaultProvider().id(),
                providerModels()
        );
    }

    private Map<String, List<String>> providerModels() {
        Map<String, List<String>> models = new LinkedHashMap<>();
        for (LlmProvider provider : LlmProvider.values()) models.put(provider.id(), modelsFor(provider));
        return models;
    }
}
