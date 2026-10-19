package com.officemanipulator.api;

import com.officemanipulator.domain.JobStatus;
import com.officemanipulator.preview.PreviewData;
import com.officemanipulator.preview.PreviewService;
import com.officemanipulator.service.JobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class PreviewController {

    private final JobService jobService;
    private final PreviewService previewService;

    public PreviewController(JobService jobService, PreviewService previewService) {
        this.jobService    = jobService;
        this.previewService = previewService;
    }

    @GetMapping("/{jobId}/preview")
    public ResponseEntity<PreviewData> preview(@PathVariable UUID jobId) {
        var jobOpt = jobService.findById(jobId);
        if (jobOpt.isEmpty()) return ResponseEntity.notFound().build();
        var job = jobOpt.get();
        if (job.getStatus() != JobStatus.DONE || job.getFilePath() == null)
            return ResponseEntity.notFound().build();
        if (job.getExpiresAt().isBefore(java.time.OffsetDateTime.now())) return ResponseEntity.status(410).build();
        try {
            PreviewData data = previewService.extract(job);
            return data != null ? ResponseEntity.ok(data) : ResponseEntity.<PreviewData>notFound().build();
        } catch (Exception e) {
            return ResponseEntity.<PreviewData>internalServerError().build();
        }
    }
}
