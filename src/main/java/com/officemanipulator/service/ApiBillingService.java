package com.officemanipulator.service;

import com.officemanipulator.exception.BudgetExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

/** Durable reservations serialize budget admission across threads AND application instances. */
@Service
public class ApiBillingService {
    private final JdbcTemplate jdbc;
    private final BigDecimal limit;
    public ApiBillingService(JdbcTemplate jdbc, @Value("${app.budget.limit-usd}") BigDecimal limit) {
        this.jdbc = jdbc; this.limit = limit;
    }
    public BigDecimal committed() {
        return jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM api_calls WHERE state IN ('RESERVED','SETTLED','UNCERTAIN')", BigDecimal.class);
    }
    public BigDecimal spent() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM api_calls WHERE state = 'SETTLED'", BigDecimal.class);
    }
    public BigDecimal uncertainAmount() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM api_calls WHERE state='UNCERTAIN'", BigDecimal.class);
    }
    public BigDecimal reservedAmount() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM api_calls WHERE state='RESERVED'", BigDecimal.class);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID reserve(UUID jobId, String provider, BigDecimal ceiling) {
        if (ceiling == null || ceiling.signum() <= 0) throw new IllegalArgumentException("A positive API cost ceiling is required");
        jdbc.execute("SELECT pg_advisory_xact_lock(492891744)");
        if (committed().add(ceiling).compareTo(limit) > 0)
            throw new BudgetExceededException("Remaining budget cannot cover this API call (maximum $" + ceiling + ")");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO api_calls (id,job_id,provider,state,amount) VALUES (?,?,?,'RESERVED',?)", id,jobId,provider,ceiling);
        return id;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(UUID callId, BigDecimal cost, long input, long output) {
        jdbc.update("UPDATE api_calls SET state='SETTLED', amount=?, input_tokens=?, output_tokens=? WHERE id=? AND state='RESERVED'",
            cost, input, output, callId);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void uncertain(UUID callId) {
        // A transport failure does not prove the provider did not charge. Retain the ceiling
        // for a while (see releaseStale()) rather than immediately discounting it.
        jdbc.update("UPDATE api_calls SET state='UNCERTAIN' WHERE id=? AND state='RESERVED'", callId);
    }

    /** Releases RESERVED/UNCERTAIN rows old enough that any real charge would have settled by
     * now, so a handful of transient failures can't permanently pin the budget near the limit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int releaseStale(int staleMinutes) {
        return jdbc.update("""
                UPDATE api_calls SET state='RELEASED'
                WHERE state IN ('RESERVED','UNCERTAIN') AND created_at < now() - (? || ' minutes')::interval
                """, staleMinutes);
    }
}
