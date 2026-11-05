package com.officemanipulator.llm;

import com.anthropic.client.AnthropicClient;
import com.officemanipulator.config.LlmClientProvider;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Routes an orchestrator's LLM calls through a (mocked) Anthropic SDK client. */
public final class TestLlm {
    private TestLlm() {}

    public static LlmClientProvider provider(AnthropicClient client, String model) {
        var provider = mock(LlmClientProvider.class);
        var selection = new LlmSelection(LlmProvider.ANTHROPIC, "test-key", model, false);
        when(provider.selectionFor(any())).thenReturn(selection);
        when(provider.client(LlmProvider.ANTHROPIC)).thenReturn(new AnthropicLlmClient(client));
        return provider;
    }
}
