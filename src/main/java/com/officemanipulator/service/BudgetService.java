package com.officemanipulator.service;

import com.officemanipulator.domain.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class BudgetService {

    @org.springframework.beans.factory.annotation.Autowired
    private ApiBillingService billing;
    private final JobRepository jobRepository;
    private final BigDecimal limitUsd;
    private final BigDecimal warnThresholdUsd;

    public BudgetService(
            JobRepository jobRepository,
            @Value("${app.budget.limit-usd}") BigDecimal limitUsd,
            @Value("${app.budget.warn-threshold-usd}") BigDecimal warnThresholdUsd
    ) {
        this.jobRepository = jobRepository;
        this.limitUsd = limitUsd;
        this.warnThresholdUsd = warnThresholdUsd;
    }

    public BigDecimal totalSpent() {
        return billing == null ? jobRepository.sumTotalCost() : billing.spent();
    }

    public boolean isLimitReached() {
        return committed().compareTo(limitUsd) >= 0;
    }

    public boolean isNearLimit() {
        return committed().compareTo(warnThresholdUsd.min(limitUsd.multiply(new BigDecimal("0.975")))) >= 0;
    }

    private BigDecimal committed() { return billing == null ? totalSpent() : billing.committed(); }

    public BigDecimal getReserved() { return billing == null ? BigDecimal.ZERO : billing.reservedAmount(); }
    public BigDecimal getUncertain() { return billing == null ? BigDecimal.ZERO : billing.uncertainAmount(); }

    public BigDecimal getLimit() {
        return limitUsd;
    }

    public BigDecimal getRemaining() {
        return limitUsd.subtract(committed()).max(BigDecimal.ZERO);
    }
}
