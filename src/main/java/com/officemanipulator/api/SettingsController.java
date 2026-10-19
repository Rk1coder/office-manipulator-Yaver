package com.officemanipulator.api;

import com.officemanipulator.api.dto.ErrorResponse;
import com.officemanipulator.api.dto.SettingsResponse;
import com.officemanipulator.config.AppSettingsService;
import com.officemanipulator.config.ClaudeClientProvider;
import com.officemanipulator.config.ClaudeKeyVerifier;
import org.springframework.http.HttpStatus;
import com.officemanipulator.service.GeminiImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Lets the Settings page read and change the Claude API key/model (and optional Gemini image
 * key) at runtime — no app restart needed; see {@link ClaudeClientProvider#reload()}.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    public static final String RECOMMENDED_MODEL = "claude-sonnet-4-6";
    public static final List<String> AVAILABLE_MODELS = List.of("claude-sonnet-4-6", "claude-sonnet-5");

    private final ClaudeClientProvider claudeClientProvider;
    private final AppSettingsService settingsService;
    private final GeminiImageService geminiImageService;
    private final ClaudeKeyVerifier keyVerifier;

    public SettingsController(ClaudeClientProvider claudeClientProvider, AppSettingsService settingsService,
            GeminiImageService geminiImageService, ClaudeKeyVerifier keyVerifier) {
        this.claudeClientProvider = claudeClientProvider;
        this.settingsService = settingsService;
        this.geminiImageService = geminiImageService;
        this.keyVerifier = keyVerifier;
    }

    public record SettingsRequest(String claudeApiKey, String model, String geminiApiKey, Boolean geminiEnabled) {}
    public record VerificationRequest(String claudeApiKey, String model) {}
    public record VerificationError(String error, SettingsResponse settings) {}

    @GetMapping
    public SettingsResponse get() {
        return currentView();
    }

    @PostMapping
    public synchronized ResponseEntity<?> save(@RequestBody SettingsRequest body) {
        if ((body.claudeApiKey() != null && !body.claudeApiKey().isBlank())
                || (body.model() != null && !body.model().isBlank() && !body.model().equals(claudeClientProvider.model()))) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("Claude anahtarını veya modelini değiştirmek için Kontrol et düğmesini kullanın."));
        }

        if (body.geminiApiKey() != null) settingsService.set(GeminiImageService.KEY_API_KEY, body.geminiApiKey().isBlank() ? null : body.geminiApiKey());
        if (body.geminiEnabled() != null) settingsService.set(GeminiImageService.KEY_ENABLED, String.valueOf(body.geminiEnabled()));

        return ResponseEntity.ok(currentView());
    }

    @PostMapping("/verify")
    public synchronized ResponseEntity<?> verify(@RequestBody VerificationRequest body) {
        String key = body.claudeApiKey() == null || body.claudeApiKey().isBlank()
                ? claudeClientProvider.configuredKey() : body.claudeApiKey().trim();
        String model = body.model() == null || body.model().isBlank() ? claudeClientProvider.model() : body.model().trim();
        if (!AVAILABLE_MODELS.contains(model)) return ResponseEntity.badRequest().body(ErrorResponse.of("Geçerli bir model seçin."));
        try {
            keyVerifier.verify(key, model);
            claudeClientProvider.activateVerified(key, model);
            return ResponseEntity.ok(currentView());
        } catch (ClaudeKeyVerifier.VerificationFailure failure) {
            if (java.util.Objects.equals(key, claudeClientProvider.configuredKey())
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
                RECOMMENDED_MODEL,
                AVAILABLE_MODELS,
                geminiImageService.isConfiguredFlag(),
                geminiImageService.keyConfigured(),
                geminiImageService.maskedApiKey()
        );
    }
}
