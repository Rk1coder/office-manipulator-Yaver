package com.officemanipulator.api;

import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.JobStatus;
import com.officemanipulator.service.JobService;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final JobService jobService;

    public FileController(JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID jobId) {
        Job job = jobService.findById(jobId).orElse(null);

        if (job == null || job.getStatus() != JobStatus.DONE || job.getFilePath() == null) {
            return ResponseEntity.notFound().build();
        }

        if (job.getExpiresAt().isBefore(java.time.OffsetDateTime.now())) return ResponseEntity.status(HttpStatus.GONE).build();
        Path filePath = Paths.get(job.getFilePath());
        if (!Files.exists(filePath)) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        String fileName = job.getFileName() != null
                ? job.getFileName()
                : "output." + job.getFormat().fileExtension();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(job.getFormat().contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(new PathResource(filePath));
    }
}
