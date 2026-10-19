package com.officemanipulator.service;

import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.JobRepository;
import com.officemanipulator.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class FileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);

    private final JobRepository jobRepository;
    private final FileStorageService storageService;

    public FileCleanupService(JobRepository jobRepository, FileStorageService storageService) {
        this.jobRepository  = jobRepository;
        this.storageService = storageService;
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void deleteExpiredFiles() {
        List<Job> expired = jobRepository.findExpiredWithFile(OffsetDateTime.now());
        if (expired.isEmpty()) return;

        log.info("Cleaning up {} expired job file(s)", expired.size());
        for (Job job : expired) {
            try {
                storageService.deleteJobFiles(job.getId());
                jobRepository.clearFilePath(job.getId());
            } catch (RuntimeException e) {
                log.warn("Job {} cleanup failed; retaining it for retry: {}", job.getId(), e.getMessage());
            }
        }
    }
}
