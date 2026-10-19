package com.officemanipulator.claude.dto;

import java.math.BigDecimal;

/** Result of the design-director Claude call: a visual/narrative design decision JSON, produced
 * before any slide content exists. See ClaudeOrchestrator.runDesignDirection. */
public record OrchestratorDesignResult(
        String designJson,
        int inputTokens,
        int outputTokens,
        BigDecimal cost
) {}
