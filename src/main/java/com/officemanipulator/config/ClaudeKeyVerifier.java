package com.officemanipulator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Checks credentials against the Models API without generating content or sending user documents. */
@Service
public class ClaudeKeyVerifier {
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private final HttpClient http;
    private final String baseUrl;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public ClaudeKeyVerifier(@Value("${app.claude.base-url}") String baseUrl) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build(), baseUrl);
    }

    /** Test-only convenience overload — defaults to the official endpoint. */
    ClaudeKeyVerifier(HttpClient http) { this(http, DEFAULT_BASE_URL); }

    ClaudeKeyVerifier(HttpClient http, String baseUrl) {
        this.http = http;
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
    }

    public void verify(String key, String model) {
        if (key == null || key.isBlank() || key.length() > 4096 || key.chars().anyMatch(c -> c <= 32 || c >= 127)) {
            throw new VerificationFailure(HttpStatus.BAD_REQUEST, "Geçerli bir Claude API anahtarı girin.");
        }
        if (model == null || !model.matches("[a-zA-Z0-9._-]{1,128}")) {
            throw new VerificationFailure(HttpStatus.BAD_REQUEST, "Geçerli bir model seçin.");
        }
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models/" + model))
                .timeout(Duration.ofSeconds(15)).header("x-api-key", key)
                .header("anthropic-version", "2023-06-01").header("Accept", "application/json").GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            switch (response.statusCode()) {
                case 200 -> {
                    var body = json.readTree(response.body());
                    if (body == null || !"model".equals(body.path("type").asText()) || body.path("id").asText().isBlank()) {
                        throw new VerificationFailure(HttpStatus.BAD_GATEWAY, "Anthropic doğrulama yanıtı geçersiz. Tekrar deneyin.");
                    }
                }
                case 401 -> throw new VerificationFailure(HttpStatus.UNAUTHORIZED, "Claude API anahtarı geçersiz veya iptal edilmiş. Anahtarı kontrol edin.");
                case 403 -> throw new VerificationFailure(HttpStatus.FORBIDDEN, "Bu anahtarın seçili modele erişim izni yok.");
                case 404 -> throw new VerificationFailure(HttpStatus.BAD_REQUEST, "Seçili model bu anahtar için kullanılamıyor. Başka bir model seçin.");
                case 429 -> throw new VerificationFailure(HttpStatus.TOO_MANY_REQUESTS, "Anthropic istek sınırına ulaşıldı. Biraz sonra tekrar deneyin.");
                default -> throw new VerificationFailure(HttpStatus.BAD_GATEWAY, "Anthropic doğrulaması tamamlanamadı. Daha sonra tekrar deneyin.");
            }
        } catch (IOException e) {
            throw new VerificationFailure(HttpStatus.SERVICE_UNAVAILABLE, "Anthropic'e bağlanılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VerificationFailure(HttpStatus.SERVICE_UNAVAILABLE, "Doğrulama kesildi. Tekrar deneyin.");
        }
    }

    public static class VerificationFailure extends RuntimeException {
        private final HttpStatus status;
        public VerificationFailure(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }
}
