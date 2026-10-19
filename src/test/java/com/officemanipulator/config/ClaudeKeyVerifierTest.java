package com.officemanipulator.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import java.net.http.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClaudeKeyVerifierTest {
    @Test void checksOfficialModelEndpointWithoutSendingMessages() throws Exception {
        HttpClient http=mock(HttpClient.class);
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"type\":\"model\",\"id\":\"claude-sonnet-4-6\",\"display_name\":\"Claude Sonnet 4.6\",\"created_at\":\"2026-02-17T00:00:00Z\"}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(a -> {
            HttpRequest request=a.getArgument(0);
            assertEquals("https://api.anthropic.com/v1/models/claude-sonnet-4-6",request.uri().toString());
            assertEquals("GET",request.method());
            assertTrue(request.bodyPublisher().isEmpty());
            assertEquals("test-secret",request.headers().firstValue("x-api-key").orElseThrow());
            assertEquals("2023-06-01",request.headers().firstValue("anthropic-version").orElseThrow());
            assertTrue(request.timeout().orElseThrow().toSeconds() <= 20);
            return response;
        });
        assertDoesNotThrow(() -> new ClaudeKeyVerifier(http).verify("test-secret","claude-sonnet-4-6"));
    }

    @ParameterizedTest
    @CsvSource({"401,401","403,403","404,400","429,429","500,502","302,502"})
    void providerFailureMustNotVerifyAndMustNotEchoProviderBody(int upstream, int expected) throws Exception {
        HttpClient http=mock(HttpClient.class);
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(upstream);
        when(response.body()).thenReturn("secret-test-key private provider details");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        var error=assertThrows(ClaudeKeyVerifier.VerificationFailure.class, () -> new ClaudeKeyVerifier(http).verify("secret-test-key","claude-sonnet-4-6"));
        assertEquals(expected,error.status().value());
        assertFalse(error.getMessage().contains("secret-test-key"));
        assertFalse(error.getMessage().contains("private provider details"));
    }

    @Test void offlineFailureRemainsUnverifiedWithoutLeakingException() throws Exception {
        HttpClient http=mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenThrow(new IOException("secret-test-key"));
        var error=assertThrows(ClaudeKeyVerifier.VerificationFailure.class, () -> new ClaudeKeyVerifier(http).verify("secret-test-key","claude-sonnet-4-6"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,error.status());
        assertFalse(error.getMessage().contains("secret-test-key"));
    }

    @Test void successfulStatusWithoutModelObjectIsNotAccepted() throws Exception {
        HttpClient http=mock(HttpClient.class);
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        assertThrows(ClaudeKeyVerifier.VerificationFailure.class, () -> new ClaudeKeyVerifier(http).verify("test-secret","claude-sonnet-4-6"));
    }

    @Test void missingOrHeaderUnsafeKeysAreRejectedBeforeNetwork() {
        var verifier=new ClaudeKeyVerifier(mock(HttpClient.class));
        for(String key:new String[]{""," ","key\r\ninjected","x".repeat(4097)}) {
            var error=assertThrows(ClaudeKeyVerifier.VerificationFailure.class, () -> verifier.verify(key,"claude-sonnet-4-6"));
            assertEquals(HttpStatus.BAD_REQUEST,error.status());
        }
    }
}
