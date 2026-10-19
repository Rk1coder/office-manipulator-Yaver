package com.officemanipulator.api;

import com.officemanipulator.config.AppSettingsService;
import com.officemanipulator.config.ClaudeClientProvider;
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

    private ClaudeClientProvider provider(String key) {
        when(settings.get(anyString(), anyString())).thenAnswer(a -> stored.getOrDefault(a.getArgument(0), a.getArgument(1)));
        doAnswer(a -> { String value=a.getArgument(1); if(value==null || value.isBlank()) stored.remove(a.getArgument(0)); else stored.put(a.getArgument(0),value); return null; })
                .when(settings).set(anyString(), nullable(String.class));
        doAnswer(a -> { stored.putAll(a.getArgument(0)); return null; }).when(settings).setAll(anyMap());
        return new ClaudeClientProvider(settings,key,"claude-sonnet-4-6","https://api.anthropic.com");
    }

    @Test void savingArbitraryKeyMustNotActivateOrPersistIt() {
        var provider=provider("");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),mock(ClaudeKeyVerifier.class));
        var response=controller.save(new SettingsController.SettingsRequest("not-a-real-key","claude-sonnet-4-6",null,null));
        assertEquals(400,response.getStatusCode().value());
        assertFalse(provider.keyConfigured());
        assertTrue(stored.isEmpty());
    }

    @Test void configuredButUncheckedEnvironmentKeyMustNotEnableAi() {
        var provider=provider("unchecked-key");
        assertTrue(provider.keyConfigured());
        assertFalse(new AiAvailability(provider).enabled());
        assertThrows(IllegalStateException.class,provider::client);
    }

    @Test void verifiedKeyPersistsAndRemainsVerifiedAfterProviderReload() {
        var provider=provider("");
        var verifier=mock(ClaudeKeyVerifier.class);
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),verifier);
        var result=controller.verify(new SettingsController.VerificationRequest(" valid-key ","claude-sonnet-4-6"));
        assertEquals(200,result.getStatusCode().value());
        assertEquals("valid-key",stored.get(ClaudeClientProvider.KEY_API_KEY));
        assertTrue(controller.get().claudeKeyVerified());
        assertTrue(new AiAvailability(provider).enabled());
        assertTrue(new ClaudeClientProvider(settings,"","claude-sonnet-4-6","https://api.anthropic.com").keyVerified());
        verify(verifier).verify("valid-key","claude-sonnet-4-6");
        assertFalse(controller.get().toString().contains("valid-key"));
    }

    @Test void invalidReplacementPreservesPreviouslyVerifiedKeyAndModel() {
        var provider=provider("");
        provider.activateVerified("working-key","claude-sonnet-4-6");
        var verifier=mock(ClaudeKeyVerifier.class);
        doThrow(new ClaudeKeyVerifier.VerificationFailure(HttpStatus.UNAUTHORIZED,"Rejected"))
                .when(verifier).verify("wrong-key","claude-sonnet-5");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),verifier);
        assertEquals(401,controller.verify(new SettingsController.VerificationRequest("wrong-key","claude-sonnet-5")).getStatusCode().value());
        assertEquals("working-key",provider.configuredKey());
        assertEquals("claude-sonnet-4-6",provider.model());
        assertTrue(controller.get().claudeKeyVerified());
    }

    @Test void recheckingRevokedCurrentKeyRemovesActivation() {
        var provider=provider("");
        provider.activateVerified("revoked-key","claude-sonnet-4-6");
        var verifier=mock(ClaudeKeyVerifier.class);
        doThrow(new ClaudeKeyVerifier.VerificationFailure(HttpStatus.UNAUTHORIZED,"Rejected"))
                .when(verifier).verify("revoked-key","claude-sonnet-4-6");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),verifier);
        assertEquals(401,controller.verify(new SettingsController.VerificationRequest(null,null)).getStatusCode().value());
        assertFalse(controller.get().claudeKeyVerified());
        assertFalse(new AiAvailability(provider).enabled());
    }

    @Test void changingStoredKeyOrModelInvalidatesVerification() {
        var provider=provider("");
        provider.activateVerified("verified-key","claude-sonnet-4-6");
        stored.put(ClaudeClientProvider.KEY_API_KEY,"other-key");
        assertFalse(provider.keyVerified());
        stored.put(ClaudeClientProvider.KEY_API_KEY,"verified-key");
        stored.put(ClaudeClientProvider.KEY_MODEL,"claude-sonnet-5");
        provider.reload();
        assertFalse(provider.keyVerified());
    }

    @Test void revokedCurrentKeyIsDeactivatedEvenWhenCheckingADifferentModel() {
        var provider=provider("");
        provider.activateVerified("revoked-key","claude-sonnet-4-6");
        var verifier=mock(ClaudeKeyVerifier.class);
        doThrow(new ClaudeKeyVerifier.VerificationFailure(HttpStatus.UNAUTHORIZED,"Rejected"))
                .when(verifier).verify("revoked-key","claude-sonnet-5");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),verifier);
        assertEquals(401,controller.verify(new SettingsController.VerificationRequest(null,"claude-sonnet-5")).getStatusCode().value());
        assertFalse(controller.get().claudeKeyVerified());
    }

    @Test void emptyOptionalSavePreservesVerifiedClaudeKey() {
        var provider=provider("");
        provider.activateVerified("verified-key","claude-sonnet-4-6");
        var controller=new SettingsController(provider,settings,mock(GeminiImageService.class),mock(ClaudeKeyVerifier.class));
        assertEquals(200,controller.save(new SettingsController.SettingsRequest("",null,null,null)).getStatusCode().value());
        assertEquals("verified-key",provider.configuredKey());
        assertTrue(provider.keyVerified());
    }
}
