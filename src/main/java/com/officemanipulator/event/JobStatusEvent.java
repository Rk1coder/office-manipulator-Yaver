package com.officemanipulator.event;

import com.officemanipulator.domain.JobStatus;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class JobStatusEvent extends ApplicationEvent {

    private final UUID jobId;
    private final JobStatus status;

    public JobStatusEvent(Object source, UUID jobId, JobStatus status) {
        super(source);
        this.jobId = jobId;
        this.status = status;
    }

    public UUID getJobId()     { return jobId; }
    public JobStatus getStatus() { return status; }
}
