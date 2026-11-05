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
                : "Belge." + job.getFormat().fileExtension();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(job.getFormat().contentType()))
                // RFC 6266/5987 encoding: names now carry Turkish characters, which a bare quoted filename can't.
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                        .filename(fileName, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .body(new PathResource(filePath));
    }
}
