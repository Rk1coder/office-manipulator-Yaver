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
        String llmProvider,
        /** Whitelisted providers (keys, in display order) with their suggested models. */
        java.util.Map<String, List<String>> providerModels
) {}
