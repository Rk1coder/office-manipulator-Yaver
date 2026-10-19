package com.officemanipulator.api.dto;

import java.math.BigDecimal;

public record BudgetResponse(
        BigDecimal spent,
        BigDecimal limit,
        BigDecimal remaining,
        boolean nearLimit,
        BigDecimal inputPricePerMillion,
        BigDecimal outputPricePerMillion,
        BigDecimal reserved,
        BigDecimal uncertain
) {}
