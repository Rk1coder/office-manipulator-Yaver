package com.officemanipulator.llm;

import com.officemanipulator.config.ClaudeKeyVerifier.VerificationFailure;
import com.officemanipulator.config.ModelPricing;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleLlmClientTest {
    private HttpServer server;

    /** Fake vLLM: serves one model and echoes a plain chat answer. */
    private String startFakeVllm(AtomicReference<String> lastChatBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> reply(ex, 200, """
                {"object":"list","data":[{"id":"Qwen/Qwen2.5-7B-Instruct","object":"model"}]}"""));
        server.createContext("/v1/chat/completions", ex -> {
            lastChatBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, """
                    {"choices":[{"message":{"content":"merhaba"}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void verifiesAgainstServedModelsAndLeavesOutputCapToServer() throws Exception {
        var chatBody = new AtomicReference<String>();
        var client = new OpenAiCompatibleLlmClient(startFakeVllm(chatBody));

        client.verify("EMPTY", "Qwen/Qwen2.5-7B-Instruct");
        var notServed = assertThrows(VerificationFailure.class, () -> client.verify("EMPTY", "llama-3"));
        assertTrue(notServed.getMessage().contains("yüklü değil"));

        var selection = new LlmSelection(LlmProvider.OPENAI_COMPATIBLE, "EMPTY", "Qwen/Qwen2.5-7B-Instruct", false);
        var response = client.chat(selection, new LlmClient.Request("SYS", List.of(LlmClient.Message.user("hi")), List.of(), null, 500));
        assertEquals("merhaba", response.text());
        assertEquals(3, response.inputTokens());
        var sent = HttpJson.JSON.readTree(chatBody.get());
        // A self-hosted context (vLLM --max-model-len) is smaller than the app's 32k cap; the server picks it.
        assertTrue(sent.path("max_tokens").isMissingNode());
        assertTrue(sent.path("max_completion_tokens").isMissingNode());
        assertEquals("Qwen/Qwen2.5-7B-Instruct", sent.path("model").asText());
    }

    @Test void forcedToolIsStatedInPromptAndPlainJsonAnswerCountsAsTheCall() throws Exception {
        var chatBody = new AtomicReference<String>();
        var client = new OpenAiCompatibleLlmClient(startFakeVllm(chatBody));
        var tool = new LlmClient.Tool("submit_plan", "d", HttpJson.JSON.readTree("{\"type\":\"object\"}"), false);
        client.chat(new LlmSelection(LlmProvider.OPENAI_COMPATIBLE, "EMPTY", "m", false),
                new LlmClient.Request("SYS", List.of(LlmClient.Message.user("hi")), List.of(tool), "submit_plan", 10));
        var system = HttpJson.JSON.readTree(chatBody.get()).at("/messages/0/content").asText();
        assertTrue(system.startsWith("SYS") && system.contains("`submit_plan`"), system);

        var fenced = OpenAiCompatibleLlmClient.asForcedCall(new LlmClient.Response("```json\n{\"title\":\"T\"}\n```", List.of(), 1, 2), "submit_plan");
        assertEquals("T", fenced.firstToolCall("submit_plan").input().path("title").asText());
        var prose = new LlmClient.Response("merhaba", List.of(), 1, 2);
        assertSame(prose, OpenAiCompatibleLlmClient.asForcedCall(prose, "submit_plan"));
    }

    @Test void baseUrlComesOnlyFromConfigAndMustBeHttp() {
        for (String bad : new String[]{"file:///etc/passwd", "ftp://host/v1", "vllm:8000/v1"}) {
            assertThrows(IllegalArgumentException.class, () -> new OpenAiCompatibleLlmClient(bad), bad);
        }
        var unconfigured = new OpenAiCompatibleLlmClient("");
        assertThrows(VerificationFailure.class, () -> unconfigured.verify("EMPTY", "m"));
        var selection = new LlmSelection(LlmProvider.OPENAI_COMPATIBLE, "EMPTY", "m", false);
        assertThrows(IllegalStateException.class,
                () -> unconfigured.chat(selection, new LlmClient.Request(null, List.of(LlmClient.Message.user("x")), List.of(), null, 10)));
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, LlmProvider.parse("openai_compatible"));
        // vLLM (uvicorn) loses the POST body on the JDK's h2c upgrade over plain http://.
        assertEquals(java.net.http.HttpClient.Version.HTTP_1_1, HttpJson.defaultClient().version());
    }

    @Test void modelIdsMayHaveSlashButNoTraversal() {
        assertEquals("Qwen/Qwen2.5-7B-Instruct", OpenAiCompatibleLlmClient.requireSafeModel("Qwen/Qwen2.5-7B-Instruct"));
        for (String bad : new String[]{"../admin", "a b", "x?y=1", ""}) {
            assertThrows(IllegalArgumentException.class, () -> OpenAiCompatibleLlmClient.requireSafeModel(bad), bad);
        }
    }

    @Test void selfHostedModelIsFreeUnlessPricesAreSet() {
        var free = new ModelPricing("openai_compatible", "", "", "", "Qwen/Qwen2.5-7B-Instruct", "", "");
        assertEquals(0, free.cost("Qwen/Qwen2.5-7B-Instruct", 1_000_000, 1_000_000).signum());
        assertTrue(free.knows("Qwen/Qwen2.5-7B-Instruct"));
        var hosted = new ModelPricing("openai_compatible", "", "", "", "llama-3.3-70b", "0.59", "0.79");
        assertEquals(0, new java.math.BigDecimal("1.38").compareTo(hosted.cost("llama-3.3-70b", 1_000_000, 1_000_000)));
        assertThrows(IllegalArgumentException.class, () -> new ModelPricing("m", "-1", "1"));
    }
}
