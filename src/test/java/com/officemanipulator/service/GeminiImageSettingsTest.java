package com.officemanipulator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.config.AppSettingsService;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.llm.LlmProvider;
import com.officemanipulator.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiImageSettingsTest {
    private final Map<String, String> stored = new HashMap<>();

    private GeminiImageService service(String envKey) throws Exception {
        var settings = mock(AppSettingsService.class);
        when(settings.get(anyString(), nullable(String.class))).thenAnswer(a -> stored.getOrDefault(a.getArgument(0), a.getArgument(1)));
        var images = new GeminiImageService(new FileStorageService("/tmp"), new ObjectMapper(), settings, envKey, "img-model", true);
        var llm = mock(LlmClientProvider.class);
        when(llm.configuredKey(LlmProvider.GEMINI)).thenReturn("llm-gemini-key");
        ReflectionTestUtils.setField(images, "llm", llm);
        ReflectionTestUtils.setField(images, "envInputPrice", BigDecimal.ZERO);
        ReflectionTestUtils.setField(images, "envOutputPrice", BigDecimal.ZERO);
        return images;
    }

    @Test void reusesTheLlmGeminiKeyWhenNoImageKeyIsSet() throws Exception {
        var images = service("");
        assertTrue(images.keyConfigured());
        assertTrue(images.keySharedWithLlm());
        assertFalse(service("own-image-key").keySharedWithLlm());
    }

    @Test void withoutPricesImagesAreSkippedInsteadOfFailingTheJob() throws Exception {
        var images = service("");
        assertFalse(images.pricesConfigured());
        assertFalse(images.isEnabled());
        assertTrue(images.generateImage("a cat", UUID.randomUUID(), "1").isEmpty(), "no paid call, no exception");

        stored.put(GeminiImageService.KEY_INPUT_PRICE, "0.30");
        stored.put(GeminiImageService.KEY_OUTPUT_PRICE, "30");
        assertTrue(images.pricesConfigured());
        assertTrue(images.isEnabled());
        assertEquals(new BigDecimal("30"), images.outputPrice());
    }
}
