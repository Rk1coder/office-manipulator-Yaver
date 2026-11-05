package com.officemanipulator.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.officemanipulator.config.ClaudeKeyVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anthropic Messages API via the official SDK. The base URL comes only from .env
 * (ANTHROPIC_BASE_URL, an operator-run proxy) — never from a request. */
@Component
public class AnthropicLlmClient implements LlmClient {
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final String baseUrl;
    private final ClaudeKeyVerifier verifier;
    private final AnthropicClient fixedClient;
    private String cachedKey;
    private AnthropicClient cachedClient;

    @Autowired
    public AnthropicLlmClient(@Value("${app.llm.anthropic.base-url:}") String baseUrl, ClaudeKeyVerifier verifier) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl;
        this.verifier = verifier;
        this.fixedClient = null;
    }

    /** Tests: route every call through a (mocked) SDK client. */
    public AnthropicLlmClient(AnthropicClient fixedClient) {
        this.baseUrl = DEFAULT_BASE_URL;
        this.verifier = null;
        this.fixedClient = fixedClient;
    }

    @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }

    @Override public void verify(String apiKey, String model) { verifier.verify(apiKey, model); }

    @Override
    public Response chat(LlmSelection selection, Request request) {
        MessageCreateParams params = toParams(selection.model(), request);
        AnthropicClient client = fixedClient != null ? fixedClient
                : selection.requestKey() ? build(selection.apiKey()) : cached(selection.apiKey());
        try {
            return fromMessage(send(client, params));
        } finally {
            // A caller's own key is used for this one request only; don't keep a client holding it.
            if (fixedClient == null && selection.requestKey()) client.close();
        }
    }

    private static com.anthropic.models.messages.Message send(AnthropicClient client, MessageCreateParams params) {
        if (params.maxTokens() <= 8192) return client.messages().create(params);
        // The SDK rejects long non-streaming requests; stream and accumulate, honouring cancellation.
        var accumulator = MessageAccumulator.create();
        try (var stream = client.messages().createStreaming(params)) {
            stream.stream().forEach(event -> {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Üretim iptal edildi");
                accumulator.accumulate(event);
            });
            return accumulator.message();
        }
    }

    private synchronized AnthropicClient cached(String apiKey) {
        if (cachedClient == null || !apiKey.equals(cachedKey)) {
            if (cachedClient != null) cachedClient.close();
            cachedClient = build(apiKey);
            cachedKey = apiKey;
        }
        return cachedClient;
    }

    private AnthropicClient build(String apiKey) {
        return AnthropicOkHttpClient.builder().apiKey(apiKey == null ? "" : apiKey).baseUrl(baseUrl)
                .timeout(Duration.ofMinutes(10)).maxRetries(0).build();
    }

    static MessageCreateParams toParams(String model, Request request) {
        var builder = MessageCreateParams.builder().model(model).maxTokens(request.maxTokens());
        if (request.system() != null) builder.system(request.system());
        builder.messages(request.messages().stream().map(AnthropicLlmClient::toParam).toList());
        for (Tool tool : request.tools()) builder.addTool(toSdkTool(tool));
        if (request.forcedTool() != null) {
            builder.toolChoice(ToolChoiceTool.builder().name(request.forcedTool()).disableParallelToolUse(true).build());
        } else if (!request.tools().isEmpty()) {
            builder.toolChoice(ToolChoiceAuto.builder().build());
        }
        return builder.build();
    }

    private static com.anthropic.models.messages.Tool toSdkTool(Tool tool) {
        ObjectNode node = ObjectMappers.jsonMapper().createObjectNode();
        node.put("name", tool.name());
        if (tool.description() != null) node.put("description", tool.description());
        node.set("input_schema", tool.inputSchema());
        if (tool.strict()) node.put("strict", true);
        try {
            return ObjectMappers.jsonMapper().treeToValue(node, com.anthropic.models.messages.Tool.class);
        } catch (Exception e) {
            throw new IllegalStateException("Tool " + tool.name() + " could not be converted", e);
        }
    }

    private static MessageParam toParam(LlmClient.Message message) {
        if (message.toolResult() != null) {
            return MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(List.of(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(message.toolResult().toolCallId()).content(message.toolResult().content()).build())))
                    .build();
        }
        if (message.role() == LlmClient.Message.Role.USER) {
            if (message.images().isEmpty()) return MessageParam.builder().role(MessageParam.Role.USER).content(message.text()).build();
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (Image image : message.images()) {
                blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder().source(Base64ImageSource.builder()
                        .data(image.base64()).mediaType(Base64ImageSource.MediaType.of(image.mediaType())).build()).build()));
            }
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(message.text()).build()));
            return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build();
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        if (message.text() != null) blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(message.text()).build()));
        for (ToolCall call : message.toolCalls()) {
            Map<String, JsonValue> input = new LinkedHashMap<>();
            call.input().fields().forEachRemaining(e -> input.put(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(call.id()).name(call.name())
                    .input(ToolUseBlockParam.Input.builder().additionalProperties(input).build()).build()));
        }
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build();
    }

    private static Response fromMessage(com.anthropic.models.messages.Message message) {
        String text = null;
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (text == null && block.text().isPresent()) text = block.text().get().text();
            block.toolUse().ifPresent(tu -> {
                JsonNode input = ObjectMappers.jsonMapper().valueToTree(tu._input());
                calls.add(new ToolCall(tu.id(), tu.name(), input == null || input.isNull() ? ObjectMappers.jsonMapper().createObjectNode() : input, null));
            });
        }
        return new Response(text, calls, message.usage().inputTokens(), message.usage().outputTokens());
    }
}
