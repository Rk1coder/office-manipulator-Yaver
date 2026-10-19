package com.officemanipulator.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Holds the live {@link AnthropicClient}, rebuilt on demand (see {@link #reload()}) so the
 * Settings page can change the API key/model at runtime without an app restart. Falls back to
 * the env/application.yml defaults whenever no override is stored in {@link AppSettingsService}.
 */
@Service
public class ClaudeClientProvider {
    public static final String KEY_API_KEY = "claude.api-key";
    public static final String KEY_MODEL = "claude.model";
    public static final String KEY_VERIFICATION = "claude.verified-fingerprint";

    private final AppSettingsService settings;
    private final String envApiKey;
    private final String envModel;
    private final String baseUrl;
    private volatile AnthropicClient client;
    private volatile String model;

    public ClaudeClientProvider(AppSettingsService settings,
            @Value("${app.claude.api-key:}") String envApiKey,
            @Value("${app.claude.model}") String envModel,
            @Value("${app.claude.base-url}") String baseUrl) {
        this.settings = settings;
        this.envApiKey = envApiKey;
        this.envModel = envModel;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.anthropic.com" : baseUrl;
        reload();
    }

    public synchronized void reload() {
        String apiKey = settings.get(KEY_API_KEY, envApiKey);
        this.model = settings.get(KEY_MODEL, envModel);
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey == null ? "" : apiKey)
                .baseUrl(baseUrl)
                .timeout(Duration.ofMinutes(10))
                .maxRetries(0)
                .build();
    }

    public AnthropicClient client() {
        if (!keyVerified()) throw new IllegalStateException("Claude API anahtarını Ayarlar bölümündeki Kontrol et düğmesiyle doğrulayın.");
        return client;
    }

    public String model() { return model; }

    public boolean keyConfigured() {
        String apiKey = configuredKey();
        return apiKey != null && !apiKey.isBlank();
    }

    public String configuredKey() { return settings.get(KEY_API_KEY, envApiKey); }

    public boolean keyVerified() {
        return keyConfigured() && fingerprint(configuredKey(), model).equals(settings.get(KEY_VERIFICATION, ""));
    }

    public synchronized void activateVerified(String key, String selectedModel) {
        settings.setAll(Map.of(KEY_API_KEY, key, KEY_MODEL, selectedModel, KEY_VERIFICATION, fingerprint(key, selectedModel)));
        reload();
    }

    public void invalidateVerification() { settings.set(KEY_VERIFICATION, null); }

    private static String fingerprint(String key, String selectedModel) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((key + "\0" + selectedModel).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    /** Last 4 characters only — never expose the full key back to the frontend. */
    public String maskedKey() {
        String apiKey = settings.get(KEY_API_KEY, envApiKey);
        if (apiKey == null || apiKey.isBlank()) return null;
        return apiKey.length() <= 4 ? "****" : "****" + apiKey.substring(apiKey.length() - 4);
    }
}
