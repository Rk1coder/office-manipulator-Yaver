package com.officemanipulator.service;
import com.officemanipulator.domain.Job;
import java.math.BigDecimal;
/** Snapshot-based addition also handles orchestrators that record usage during a call. */
public record JobUsage(int input, int output, BigDecimal cost) {
    public static JobUsage snapshot(Job job) {
        return new JobUsage(job.getTokenInput() == null ? 0 : job.getTokenInput(),
            job.getTokenOutput() == null ? 0 : job.getTokenOutput(),
            job.getCostUsd() == null ? BigDecimal.ZERO : job.getCostUsd());
    }
    public void apply(Job job, int in, int out, BigDecimal amount) {
        job.setTokenInput(input + in); job.setTokenOutput(output + out); job.setCostUsd(cost.add(amount));
    }
}
