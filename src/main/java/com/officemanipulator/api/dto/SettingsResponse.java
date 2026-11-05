package com.officemanipulator.api.dto;

import java.util.List;

public record SettingsResponse(
        boolean claudeKeyConfigured,
        boolean claudeKeyVerified,
        String claudeKeyMasked,
        String model,
        String recommendedModel,
        List<String> availableModels,
        boolean geminiEnabled,
        boolean geminiKeyConfigured,
        String geminiKeyMasked,
        /** Image generation reuses the LLM provider's Gemini key (no separate image key set). */
        boolean geminiKeyShared,
        boolean geminiPricesConfigured,
        String geminiInputPrice,
        String geminiOutputPrice,
        String llmProvider,
        /** Whitelisted providers (keys, in display order) with their suggested models. */
        java.util.Map<String, List<String>> providerModels
) {}
