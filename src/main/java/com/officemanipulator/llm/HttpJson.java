package com.officemanipulator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.config.ClaudeKeyVerifier.VerificationFailure;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/** Minimal JSON-over-HTTPS transport shared by the plain-HTTP adapters (OpenAI, Gemini). */
final class HttpJson {
    static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_MODEL = Pattern.compile("[a-zA-Z0-9._:-]{1,128}");

    private final HttpClient http;
    private final String providerName;

    HttpJson(HttpClient http, String providerName) {
        this.http = http;
        this.providerName = providerName;
    }

    static HttpClient defaultClient() {
        // No redirects: a fixed endpoint must not be able to bounce the request (and its key) elsewhere.
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The model id ends up in a URL path (Gemini), so only plain identifier characters pass. */
    static String requireSafeModel(String model) {
        if (model == null || !SAFE_MODEL.matcher(model).matches() || model.contains("..")) {
            throw new IllegalArgumentException("Geçersiz model adı.");
        }
        return model;
    }

    JsonNode post(URI uri, Map<String, String> headers, JsonNode body) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        headers.forEach(builder::header);
        HttpResponse<String> response = send(builder.build());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(providerName + " isteği başarısız (HTTP " + response.statusCode() + "): " + errorMessage(response));
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException(providerName + " yanıtı okunamadı.", e);
        }
    }

    /** Models-API lookup for key verification; maps status codes the same way ClaudeKeyVerifier does. */
    void verify(URI uri, Map<String, String> headers) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET();
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = send(builder.build());
        } catch (IllegalStateException e) {
            throw new VerificationFailure(HttpStatus.SERVICE_UNAVAILABLE, providerName + "'a bağlanılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin.");
        }
        switch (response.statusCode()) {
            case 200 -> { }
            // Gemini reports an invalid key as 400 API_KEY_INVALID rather than 401.
            case 400 -> throw response.body().contains("API_KEY_INVALID")
                    ? new VerificationFailure(HttpStatus.UNAUTHORIZED, providerName + " API anahtarı geçersiz. Anahtarı kontrol edin.")
                    : new VerificationFailure(HttpStatus.BAD_REQUEST, "Seçili model bu anahtar için kullanılamıyor. Başka bir model seçin.");
            case 401 -> throw new VerificationFailure(HttpStatus.UNAUTHORIZED, providerName + " API anahtarı geçersiz veya iptal edilmiş. Anahtarı kontrol edin.");
            case 403 -> throw new VerificationFailure(HttpStatus.FORBIDDEN, "Bu anahtarın seçili modele erişim izni yok.");
            case 404 -> throw new VerificationFailure(HttpStatus.BAD_REQUEST, "Seçili model bu anahtar için kullanılamıyor. Başka bir model seçin.");
            case 429 -> throw new VerificationFailure(HttpStatus.TOO_MANY_REQUESTS, providerName + " istek sınırına ulaşıldı. Biraz sonra tekrar deneyin.");
            default -> throw new VerificationFailure(HttpStatus.BAD_GATEWAY, providerName + " doğrulaması tamamlanamadı. Daha sonra tekrar deneyin.");
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(providerName + "'a bağlanılamadı: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Üretim iptal edildi");
        }
    }

    private static String errorMessage(HttpResponse<String> response) {
        // Auth errors can quote (part of) the key back; never relay those bodies.
        if (response.statusCode() == 401 || response.statusCode() == 403) return "API anahtarı reddedildi.";
        try {
            String message = JSON.readTree(response.body()).path("error").path("message").asText("");
            return message.length() > 300 ? message.substring(0, 300) : message;
        } catch (IOException e) {
            return "";
        }
    }
}
