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
        String geminiKeyMasked
) {}
