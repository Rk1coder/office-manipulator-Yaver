package com.officemanipulator.config;

import com.officemanipulator.llm.LlmClient;
import com.officemanipulator.llm.LlmProvider;
import com.officemanipulator.llm.LlmSelection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Decides which provider/key/model each LLM call uses.
 * <ul>
 *   <li>Default: {@code LLM_PROVIDER} from .env with that provider's {@code *_API_KEY}/{@code *_MODEL};
 *       the Settings page can override the default provider's key/model at runtime (verified with
 *       "Kontrol et" before AI is enabled).</li>
 *   <li>Per request: user-facing endpoints may pass {@code llm_provider}/{@code llm_api_key}/{@code llm_model}
 *       ({@link #resolveOverride}). Synchronous calls run inside {@link #callWith}; async jobs started
 *       by that request get it via {@link #bindJob}, held in memory only — a request key is never
 *       written to the database.</li>
 *   <li>Anything else (scheduled/batch work, jobs with no binding) uses the .env default.</li>
 * </ul>
 * The provider is always one of the {@link LlmProvider} whitelist; endpoints are fixed per adapter.
 */
@Service
public class LlmClientProvider {
    public static final String KEY_API_KEY = "claude.api-key";
    public static final String KEY_MODEL = "claude.model";
    public static final String KEY_VERIFICATION = "claude.verified-fingerprint";
    private static final Pattern SAFE_KEY = Pattern.compile("[\\x21-\\x7e]{1,4096}");
    private static final Pattern SAFE_MODEL = Pattern.compile("[a-zA-Z0-9._:-]{1,128}");
    // shortcut: request keys bound to a job live in memory and expire after this TTL (or when the job ends); a restart drops them and the job falls back to the .env default — persist an encrypted marker if that matters.
    private static final Duration BINDING_TTL = Duration.ofHours(24);

    private record Binding(LlmSelection selection, Instant expiresAt) {}

    private final AppSettingsService settings;
    private final ModelPricing pricing;
    private final LlmProvider defaultProvider;
    private final Map<LlmProvider, String> envKeys = new EnumMap<>(LlmProvider.class);
    private final Map<LlmProvider, String> envModels = new EnumMap<>(LlmProvider.class);
    private final Map<LlmProvider, LlmClient> clients = new EnumMap<>(LlmProvider.class);
    private final Map<UUID, Binding> jobBindings = new ConcurrentHashMap<>();
    private final ThreadLocal<LlmSelection> current = new ThreadLocal<>();

    @Autowired
    public LlmClientProvider(AppSettingsService settings, ModelPricing pricing, List<LlmClient> adapters,
            @Value("${app.llm.provider:anthropic}") String provider,
            @Value("${app.llm.anthropic.api-key:}") String anthropicKey,
            @Value("${app.llm.anthropic.model:}") String anthropicModel,
            @Value("${app.llm.gemini.api-key:}") String geminiKey,
            @Value("${app.llm.gemini.model:}") String geminiModel,
            @Value("${app.llm.openai.api-key:}") String openaiKey,
            @Value("${app.llm.openai.model:}") String openaiModel) {
        this.settings = settings;
        this.pricing = pricing;
        this.defaultProvider = LlmProvider.parse(provider);
        envKeys.put(LlmProvider.ANTHROPIC, anthropicKey);
        envKeys.put(LlmProvider.GEMINI, geminiKey);
        envKeys.put(LlmProvider.OPENAI, openaiKey);
        envModels.put(LlmProvider.ANTHROPIC, anthropicModel);
        envModels.put(LlmProvider.GEMINI, geminiModel);
        envModels.put(LlmProvider.OPENAI, openaiModel);
        adapters.forEach(adapter -> clients.put(adapter.provider(), adapter));
        if (blank(envModels.get(defaultProvider))) {
            throw new IllegalArgumentException(defaultProvider.name() + "_MODEL ayarlanmalı (LLM_PROVIDER=" + defaultProvider.id() + ")");
        }
    }

    public LlmProvider defaultProvider() { return defaultProvider; }

    public LlmClient client(LlmProvider provider) {
        LlmClient client = clients.get(provider);
        if (client == null) throw new IllegalStateException("No adapter registered for " + provider.id());
        return client;
    }

    public String model() { return settings.get(key("model"), envModels.get(defaultProvider)); }

    public String configuredKey() { return settings.get(key("api-key"), envKeys.get(defaultProvider)); }

    public boolean keyConfigured() { return !blank(configuredKey()); }

    public boolean keyVerified() {
        return keyConfigured() && fingerprint(configuredKey(), model()).equals(settings.get(key("verified-fingerprint"), ""));
    }

    public synchronized void activateVerified(String apiKey, String selectedModel) {
        settings.setAll(Map.of(key("api-key"), apiKey, key("model"), selectedModel,
                key("verified-fingerprint"), fingerprint(apiKey, selectedModel)));
    }

    public void invalidateVerification() { settings.set(key("verified-fingerprint"), null); }

    /** Last 4 characters only — never expose the full key back to the frontend. */
    public String maskedKey() {
        String apiKey = configuredKey();
        if (blank(apiKey)) return null;
        return apiKey.length() <= 4 ? "****" : "****" + apiKey.substring(apiKey.length() - 4);
    }

    public LlmSelection defaultSelection() {
        if (!keyVerified()) throw new IllegalStateException("LLM API anahtarını Ayarlar bölümündeki Kontrol et düğmesiyle doğrulayın.");
        return new LlmSelection(defaultProvider, configuredKey(), model(), false);
    }

    /**
     * Validates the optional per-request override fields; returns null when none is given.
     * Missing fields fall back to .env: no key → that provider's .env key, no model → its .env model.
     * A server-side key may only be used with a model whose price is known, so the budget guard
     * can still account for it; with the caller's own key any model is allowed.
     */
    public LlmSelection resolveOverride(String rawProvider, String rawKey, String rawModel) {
        if (blank(rawProvider) && blank(rawKey) && blank(rawModel)) return null;
        LlmProvider provider = blank(rawProvider) ? defaultProvider : LlmProvider.parse(rawProvider);
        String apiKey = blank(rawKey) ? null : rawKey.trim();
        if (apiKey != null && !SAFE_KEY.matcher(apiKey).matches()) throw new IllegalArgumentException("Geçersiz llm_api_key.");
        String model = blank(rawModel) ? (provider == defaultProvider ? model() : envModels.get(provider)) : rawModel.trim();
        if (blank(model) || !SAFE_MODEL.matcher(model).matches() || model.contains("..")) {
            throw new IllegalArgumentException("Geçerli bir llm_model belirtin.");
        }
        if (apiKey != null) return new LlmSelection(provider, apiKey, model, true);

        String serverKey = provider == defaultProvider ? configuredKey() : envKeys.get(provider);
        if (blank(serverKey)) throw new IllegalArgumentException(provider.id() + " için sunucuda API anahtarı tanımlı değil; llm_api_key gönderin.");
        if (provider == defaultProvider && !keyVerified()) {
            throw new IllegalArgumentException("Sunucudaki LLM anahtarı doğrulanmamış; llm_api_key gönderin.");
        }
        if (!pricing.knows(model)) {
            throw new IllegalArgumentException("'" + model + "' için fiyat tanımlı değil; sunucu anahtarıyla kullanılamaz, llm_api_key gönderin.");
        }
        return new LlmSelection(provider, serverKey, model, false);
    }

    /** Whether an AI request can run: with an override it already passed {@link #resolveOverride}. */
    public boolean available(LlmSelection override) { return override != null || keyVerified(); }

    /** Runs a synchronous LLM-backed call with the request's override (null = default). */
    public <T> T callWith(LlmSelection override, Supplier<T> body) {
        if (override == null) return body.get();
        current.set(override);
        try {
            return body.get();
        } finally {
            current.remove();
        }
    }

    /** Carries a request's override to the async job(s) it started. */
    public void bindJob(UUID jobId, LlmSelection override) {
        Instant now = Instant.now();
        jobBindings.values().removeIf(b -> b.expiresAt().isBefore(now));
        if (override != null) jobBindings.put(jobId, new Binding(override, now.plus(BINDING_TTL)));
    }

    public void releaseJob(UUID jobId) { jobBindings.remove(jobId); }

    /** The selection an LLM call for {@code jobId} must use right now. */
    public LlmSelection selectionFor(UUID jobId) {
        LlmSelection override = current.get();
        if (override != null) return override;
        Binding binding = jobId == null ? null : jobBindings.get(jobId);
        if (binding != null && binding.expiresAt().isAfter(Instant.now())) return binding.selection();
        return defaultSelection();
    }

    private String key(String suffix) {
        // Anthropic keeps its pre-multi-provider setting names so existing installs stay verified.
        return (defaultProvider == LlmProvider.ANTHROPIC ? "claude." : "llm." + defaultProvider.id() + ".") + suffix;
    }

    private String fingerprint(String apiKey, String selectedModel) {
        String material = (defaultProvider == LlmProvider.ANTHROPIC ? "" : defaultProvider.id() + "\0") + apiKey + "\0" + selectedModel;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
