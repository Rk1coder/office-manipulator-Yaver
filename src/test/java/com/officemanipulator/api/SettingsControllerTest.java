package com.officemanipulator.api;

import com.officemanipulator.config.AppSettingsService;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.config.ModelPricing;
import com.officemanipulator.llm.AnthropicLlmClient;
import com.officemanipulator.config.AiAvailability;
import com.officemanipulator.config.ClaudeKeyVerifier;
import com.officemanipulator.service.GeminiImageService;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsControllerTest {
    private final Map<String,String> stored = new HashMap<>();
    private final AppSettingsService settings = mock(AppSettingsService.class);

    private final ModelPricing pricing = new ModelPricing("claude-sonnet-4-6","","");

    private LlmClientProvider provider(String key) { return provider(key, mock(ClaudeKeyVerifier.class)); }

    private LlmClientProvider provider(String key, ClaudeKeyVerifier verifier) {
        when(settings.get(anyString(), anyString())).thenAnswer(a -> stored.getOrDefault(a.getArgument(0), a.getArgument(1)));
        doAnswer(a -> { String value=a.getArgument(1); if(value==null || value.isBlank()) stored.remove(a.getArgument(0)); else stored.put(a.getArgument(0),value); return null; })
                .when(settings).set(anyString(), nullable(String.class));
        doAnswer(a -> { stored.putAll(a.getArgument(0)); return null; }).when(settings).setAll(anyMap());
        return newProvider(key, verifier);
    }

    private LlmClientProvider newProvider(String key, ClaudeKeyVerifier verifier) {
        return new LlmClientProvider(settings,pricing,java.util.List.of(new AnthropicLlmClient("",verifier)),
                "anthropic",key,"claude-sonnet-4-6","","","","");
    }

    @Test void savingArbitraryKeyMustNotActivateOrPersistIt() {
        var provider=provider("");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),pricing);
        var response=controller.save(new SettingsController.SettingsRequest("not-a-real-key","claude-sonnet-4-6",null,null));
        assertEquals(400,response.getStatusCode().value());
        assertFalse(provider.keyConfigured());
        assertTrue(stored.isEmpty());
    }

    @Test void configuredButUncheckedEnvironmentKeyMustNotEnableAi() {
        var provider=provider("unchecked-key");
        assertTrue(provider.keyConfigured());
        assertFalse(new AiAvailability(provider).enabled());
        assertThrows(IllegalStateException.class,provider::defaultSelection);
    }

    @Test void verifiedKeyPersistsAndRemainsVerifiedAfterProviderReload() {
        var verifier=mock(ClaudeKeyVerifier.class);
        var provider=provider("",verifier);
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),pricing);
        var result=controller.verify(new SettingsController.VerificationRequest(" valid-key ","claude-sonnet-4-6"));
        assertEquals(200,result.getStatusCode().value());
        assertEquals("valid-key",stored.get(LlmClientProvider.KEY_API_KEY));
        assertTrue(controller.get().claudeKeyVerified());
        assertTrue(new AiAvailability(provider).enabled());
        assertTrue(newProvider("",mock(ClaudeKeyVerifier.class)).keyVerified());
        verify(verifier).verify("valid-key","claude-sonnet-4-6");
        assertFalse(controller.get().toString().contains("valid-key"));
    }

    @Test void invalidReplacementPreservesPreviouslyVerifiedKeyAndModel() {
        var verifier=mock(ClaudeKeyVerifier.class);
        var provider=provider("",verifier);
        provider.activateVerified("working-key","claude-sonnet-4-6");
        doThrow(new ClaudeKeyVerifier.VerificationFailure(HttpStatus.UNAUTHORIZED,"Rejected"))
                .when(verifier).verify("wrong-key","claude-sonnet-5");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),pricing);
        assertEquals(401,controller.verify(new SettingsController.VerificationRequest("wrong-key","claude-sonnet-5")).getStatusCode().value());
        assertEquals("working-key",provider.configuredKey());
        assertEquals("claude-sonnet-4-6",provider.model());
        assertTrue(controller.get().claudeKeyVerified());
    }

    @Test void recheckingRevokedCurrentKeyRemovesActivation() {
        var verifier=mock(ClaudeKeyVerifier.class);
        var provider=provider("",verifier);
        provider.activateVerified("revoked-key","claude-sonnet-4-6");
        doThrow(new ClaudeKeyVerifier.VerificationFailure(HttpStatus.UNAUTHORIZED,"Rejected"))
                .when(verifier).verify("revoked-key","claude-sonnet-4-6");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),pricing);
        assertEquals(401,controller.verify(new SettingsController.VerificationRequest(null,null)).getStatusCode().value());
        assertFalse(controller.get().claudeKeyVerified());
        assertFalse(new AiAvailability(provider).enabled());
    }

    @Test void changingStoredKeyOrModelInvalidatesVerification() {
        var provider=provider("");
        provider.activateVerified("verified-key","claude-sonnet-4-6");
        stored.put(LlmClientProvider.KEY_API_KEY,"other-key");
        assertFalse(provider.keyVerified());
        stored.put(LlmClientProvider.KEY_API_KEY,"verified-key");
        stored.put(LlmClientProvider.KEY_MODEL,"claude-sonnet-5");
        assertFalse(provider.keyVerified());
    }

    @Test void revokedCurrentKeyIsDeactivatedEvenWhenCheckingADifferentModel() {
        var verifier=mock(ClaudeKeyVerifier.class);
        var provider=provider("",verifier);
        provider.activateVerified("revoked-key","claude-sonnet-4-6");
        doThrow(new ClaudeKeyVerifier.VerificationFailure(HttpStatus.UNAUTHORIZED,"Rejected"))
                .when(verifier).verify("revoked-key","claude-sonnet-5");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),pricing);
        assertEquals(401,controller.verify(new SettingsController.VerificationRequest(null,"claude-sonnet-5")).getStatusCode().value());
        assertFalse(controller.get().claudeKeyVerified());
    }

    @Test void emptyOptionalSavePreservesVerifiedClaudeKey() {
        var provider=provider("");
        provider.activateVerified("verified-key","claude-sonnet-4-6");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),pricing);
        assertEquals(200,controller.save(new SettingsController.SettingsRequest("",null,null,null)).getStatusCode().value());
        assertEquals("verified-key",provider.configuredKey());
        assertTrue(provider.keyVerified());
    }
}
