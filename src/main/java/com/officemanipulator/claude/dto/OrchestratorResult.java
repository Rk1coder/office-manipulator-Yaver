package com.officemanipulator.claude.dto;

import java.math.BigDecimal;
import java.nio.file.Path;

public record OrchestratorResult(
        Path outputPath,
        int inputTokens,
        int outputTokens,
        BigDecimal cost
) {}
