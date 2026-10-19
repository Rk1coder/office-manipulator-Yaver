package com.officemanipulator.api.dto;

import com.officemanipulator.domain.Job;

import java.util.UUID;

public record JobCreatedResponse(UUID jobId, String status, String format) {

    public static JobCreatedResponse from(Job job) {
        return new JobCreatedResponse(
                job.getId(),
                job.getStatus().name(),
                job.getFormat().name()
        );
    }
}
