package com.officemanipulator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Periodically releases stale RESERVED/UNCERTAIN budget reservations (see
 * {@link ApiBillingService#releaseStale}) so a handful of transient API failures can't
 * permanently pin the budget near its limit. */
@Service
public class BudgetReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BudgetReconciliationService.class);
    private static final int STALE_MINUTES = 30;

    private final ApiBillingService billing;

    public BudgetReconciliationService(ApiBillingService billing) {
        this.billing = billing;
    }

    @Scheduled(fixedDelay = 300_000)
    public void releaseStaleReservations() {
        int released = billing.releaseStale(STALE_MINUTES);
        if (released > 0) log.info("Released {} stale budget reservation(s) older than {} minutes", released, STALE_MINUTES);
    }
}
