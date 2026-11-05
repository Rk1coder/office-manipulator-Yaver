package com.officemanipulator.llm;

import com.officemanipulator.config.AppSettingsService;
import com.officemanipulator.config.ClaudeKeyVerifier;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.config.ModelPricing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmOverrideTest {
    private final Map<String, String> stored = new HashMap<>();

    private LlmClientProvider provider(String openaiKey) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.get(anyString(), nullable(String.class))).thenAnswer(a -> stored.getOrDefault(a.getArgument(0), a.getArgument(1)));
        doAnswer(a -> { stored.putAll(a.getArgument(0)); return null; }).when(settings).setAll(anyMap());
        List<LlmClient> adapters = List.of(new AnthropicLlmClient("", mock(ClaudeKeyVerifier.class)), new OpenAiLlmClient(), new GeminiLlmClient());
        return new LlmClientProvider(settings, new ModelPricing("claude-sonnet-4-6", "", ""), adapters,
                "anthropic", "env-key", "claude-sonnet-4-6", "", "", openaiKey, "gpt-test");
    }

    @Test void providerWhitelistRejectsUnknownNamesAndUrls() {
        assertEquals(LlmProvider.GEMINI, LlmProvider.parse(" Gemini "));
        for (String bad : new String[]{"azure", "http://169.254.169.254/latest", "https://evil.example/v1", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> LlmProvider.parse(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> provider("").resolveOverride("http://evil.example", "k", "m"));
    }

    @Test void noOverrideFieldsMeansEnvDefault() {
        var provider = provider("");
        assertNull(provider.resolveOverride(null, " ", ""));
        provider.activateVerified("env-key", "claude-sonnet-4-6");
        var selection = provider.selectionFor(UUID.randomUUID());
        assertEquals(LlmProvider.ANTHROPIC, selection.provider());
        assertEquals("env-key", selection.apiKey());
        assertFalse(selection.requestKey());
    }

    @Test void requestKeyAllowsAnyModelAndIsMarkedAsCallerBilled() {
        var selection = provider("").resolveOverride("openai", " sk-caller ", "gpt-anything");
        assertEquals(LlmProvider.OPENAI, selection.provider());
        assertEquals("sk-caller", selection.apiKey());
        assertTrue(selection.requestKey());
        assertFalse(selection.toString().contains("sk-caller"));
    }

    @Test void serverKeyNeedsAPricedModelAndAConfiguredKey() {
        var provider = provider("");
        provider.activateVerified("env-key", "claude-sonnet-4-6");
        assertEquals("claude-sonnet-5", provider.resolveOverride(null, null, "claude-sonnet-5").model());
        assertThrows(IllegalArgumentException.class, () -> provider.resolveOverride(null, null, "claude-unpriced"));
        assertThrows(IllegalArgumentException.class, () -> provider.resolveOverride("openai", null, null), "no OPENAI_API_KEY");
        assertThrows(IllegalArgumentException.class, () -> provider("sk-env").resolveOverride("openai", null, null), "gpt-test has no price");
    }

    @Test void modelAndKeyCannotSmuggleUrlOrHeaderCharacters() {
        var provider = provider("");
        assertThrows(IllegalArgumentException.class, () -> provider.resolveOverride("gemini", "k", "../../evil"));
        assertThrows(IllegalArgumentException.class, () -> provider.resolveOverride("gemini", "k", "m/x?y=1"));
        assertThrows(IllegalArgumentException.class, () -> provider.resolveOverride("gemini", "k\r\nX-Evil: 1", "m"));
    }

    @Test void syncOverrideIsScopedToTheCallAndJobBindingsToTheirJob() {
        var provider = provider("");
        provider.activateVerified("env-key", "claude-sonnet-4-6");
        var override = provider.resolveOverride("gemini", "caller-key", "gemini-x");
        UUID job = UUID.randomUUID(), other = UUID.randomUUID();

        assertEquals("caller-key", provider.callWith(override, () -> provider.selectionFor(job).apiKey()));
        assertEquals("env-key", provider.selectionFor(job).apiKey());

        provider.bindJob(job, override);
        assertEquals("caller-key", provider.selectionFor(job).apiKey());
        assertEquals("env-key", provider.selectionFor(other).apiKey());
        provider.releaseJob(job);
        assertEquals("env-key", provider.selectionFor(job).apiKey());
    }
}
