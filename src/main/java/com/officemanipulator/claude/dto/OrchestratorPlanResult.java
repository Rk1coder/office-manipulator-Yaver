package com.officemanipulator.claude.dto;

import java.math.BigDecimal;

public record OrchestratorPlanResult(
        String planJson,
        int inputTokens,
        int outputTokens,
        BigDecimal cost
) {}
