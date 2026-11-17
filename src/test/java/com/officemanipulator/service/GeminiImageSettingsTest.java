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

    @Test void anOpenAiCompatibleEndpointGeneratesTheImageWithTheLlmKey(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        var seen = new java.util.ArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/chat/completions", ex -> {
            seen.add(ex.getRequestHeaders().getFirst("Authorization") + " " + new String(ex.getRequestBody().readAllBytes()));
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":\"\",\"images\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
                    + java.util.Base64.getEncoder().encodeToString(png) + "\"}}]}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":1290}}").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.start();
        try {
            var settings = mock(AppSettingsService.class);
            when(settings.get(anyString(), nullable(String.class))).thenAnswer(a -> stored.getOrDefault(a.getArgument(0), a.getArgument(1)));
            var images = new GeminiImageService(new FileStorageService(tmp.toString()), new ObjectMapper(), settings, "", "google/gemini-2.5-flash-image", true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/");
            var llm = mock(LlmClientProvider.class);
            when(llm.configuredKey(LlmProvider.OPENAI_COMPATIBLE)).thenReturn("sk-or-test");
            var billing = mock(ApiBillingService.class);
            when(billing.reserve(any(), anyString(), any())).thenReturn(UUID.randomUUID());
            ReflectionTestUtils.setField(images, "llm", llm);
            ReflectionTestUtils.setField(images, "billing", billing);
            ReflectionTestUtils.setField(images, "jobs", mock(com.officemanipulator.domain.JobRepository.class));
            ReflectionTestUtils.setField(images, "envInputPrice", new BigDecimal("0.30"));
            ReflectionTestUtils.setField(images, "envOutputPrice", new BigDecimal("30"));

            var path = images.generateImage("bir karınca kolonisi", UUID.randomUUID(), "2").orElseThrow();

            assertArrayEquals(png, java.nio.file.Files.readAllBytes(path));
            assertTrue(seen.get(0).startsWith("Bearer sk-or-test "), "OpenRouter uses the LLM's compatible key");
            assertTrue(seen.get(0).contains("\"modalities\":[\"image\",\"text\"]") && seen.get(0).contains("google/gemini-2.5-flash-image"));
            verify(billing).settle(any(), eq(new BigDecimal("0.038703")), eq(10L), eq(1290L));
        } finally {
            server.stop(0);
        }
        assertThrows(IllegalArgumentException.class, () -> new GeminiImageService(new FileStorageService("/tmp"), new ObjectMapper(),
                mock(AppSettingsService.class), "", "m", true, "file:///etc"));
    }

    @Test void imagesGenerationsStyleBillsPerImageAndStoresPng(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var jpeg = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
        var seen = new java.util.ArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/images/generations", ex -> {
            seen.add(new String(ex.getRequestBody().readAllBytes()));
            byte[] out = ("{\"data\":[{\"b64_json\":\"" + java.util.Base64.getEncoder().encodeToString(jpeg.toByteArray()) + "\"}]}").getBytes();
            ex.sendResponseHeaders(200, out.length); ex.getResponseBody().write(out); ex.close();
        });
        server.start();
        try {
            var settings = mock(AppSettingsService.class);
            when(settings.get(anyString(), nullable(String.class))).thenAnswer(a -> stored.getOrDefault(a.getArgument(0), a.getArgument(1)));
            var images = new GeminiImageService(new FileStorageService(tmp.toString()), new ObjectMapper(), settings, "", "seedream-v4.5", true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
            var llm = mock(LlmClientProvider.class);
            when(llm.configuredKey(LlmProvider.OPENAI_COMPATIBLE)).thenReturn("nano-key");
            var billing = mock(ApiBillingService.class);
            when(billing.reserve(any(), anyString(), any())).thenReturn(UUID.randomUUID());
            ReflectionTestUtils.setField(images, "llm", llm);
            ReflectionTestUtils.setField(images, "billing", billing);
            ReflectionTestUtils.setField(images, "jobs", mock(com.officemanipulator.domain.JobRepository.class));
            ReflectionTestUtils.setField(images, "envInputPrice", BigDecimal.ZERO);
            ReflectionTestUtils.setField(images, "envOutputPrice", BigDecimal.ZERO);
            ReflectionTestUtils.setField(images, "apiStyle", "images");
            assertFalse(images.isEnabled(), "no per-image price: no paid call");
            ReflectionTestUtils.setField(images, "pricePerImage", new BigDecimal("0.04"));

            var path = images.generateImage("kahve fincanı", UUID.randomUUID(), "1").orElseThrow();

            byte[] saved = java.nio.file.Files.readAllBytes(path);
            assertEquals((byte) 0x89, saved[0], "JPEG answer is stored as PNG");
            assertNotNull(javax.imageio.ImageIO.read(path.toFile()));
            assertTrue(seen.get(0).contains("\"model\":\"seedream-v4.5\"") && seen.get(0).contains("\"prompt\":\"kahve fincanı\""));
            verify(billing).settle(any(), eq(new BigDecimal("0.04")), eq(0L), eq(0L));
        } finally {
            server.stop(0);
        }
    }
}
