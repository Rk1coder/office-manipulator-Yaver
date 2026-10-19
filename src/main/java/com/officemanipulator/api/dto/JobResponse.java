package com.officemanipulator.api.dto;

import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.JobStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String status,
        String format,
        String request,
        String fileName,
        long fileSize,
        String downloadUrl,
        String error,
        String failedAt,
        BigDecimal costUsd,
        String cost,
        int tokenInput,
        int tokenOutput,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
) {
    public static JobResponse from(Job job) {
        String downloadUrl = (job.getFilePath() != null && job.getStatus() == JobStatus.DONE)
                ? "/api/files/" + job.getId() + "/download"
                : "";
        BigDecimal costUsd = job.getCostUsd() != null ? job.getCostUsd() : BigDecimal.ZERO;
        return new JobResponse(
                job.getId(),
                job.getStatus().name(),
                job.getFormat().name(),
                job.getRequest() != null ? job.getRequest() : "",
                job.getFileName() != null ? job.getFileName() : "",
                job.getFileSize() != null ? job.getFileSize() : 0L,
                downloadUrl,
                job.getError() != null ? job.getError() : "",
                job.getFailedAt(),
                costUsd,
                "$" + costUsd.toPlainString(),
                job.getTokenInput() != null ? job.getTokenInput() : 0,
                job.getTokenOutput() != null ? job.getTokenOutput() : 0,
                job.getCreatedAt(),
                job.getExpiresAt()
        );
    }
}
