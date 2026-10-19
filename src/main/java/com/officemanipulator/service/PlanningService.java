package com.officemanipulator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.ClaudeOrchestrator;
import com.officemanipulator.claude.dto.OrchestratorDesignResult;
import com.officemanipulator.claude.dto.OrchestratorPlanResult;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.JobRepository;
import com.officemanipulator.domain.JobStatus;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.event.JobStatusEvent;
import com.officemanipulator.exception.JobNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Drives the planning phase: Claude proposes a content/design outline before any file
 * is generated, the user reviews and confirms it, then (optionally) Gemini renders the
 * images the plan called for, and generation proceeds via JobService.
 */
@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    @org.springframework.beans.factory.annotation.Autowired
    private com.officemanipulator.storage.FileStorageService storage;
    private final JobRepository jobRepository;
    private final ClaudeOrchestrator orchestrator;
    private final JobService jobService;
    private final GeminiImageService geminiImageService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final JobCancellationRegistry cancellationRegistry;

    public PlanningService(
            JobRepository jobRepository,
            ClaudeOrchestrator orchestrator,
            JobService jobService,
            GeminiImageService geminiImageService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            JobCancellationRegistry cancellationRegistry
    ) {
        this.jobRepository = jobRepository;
        this.orchestrator = orchestrator;
        this.jobService = jobService;
        this.geminiImageService = geminiImageService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Async("jobExecutor")
    public void createPlanAsync(UUID jobId) {
        if (jobRepository.transition(jobId, JobStatus.PENDING, JobStatus.PLANNING) == 1)
            jobRepository.findById(jobId).ifPresent(this::createPlan);
    }

    private void createPlan(Job job) {
        updateStatus(job, JobStatus.PLANNING);
        cancellationRegistry.register(job.getId());
        try {
            if (job.getFormat() != null && job.getFormat().generationFormat() == OutputFormat.PPTX) {
                try {
                    var beforeDesign = JobUsage.snapshot(job);
                    OrchestratorDesignResult design = orchestrator.runDesignDirection(job);
                    beforeDesign.apply(job, design.inputTokens(), design.outputTokens(), design.cost());
                    job.setDesignDirectionJson(design.designJson());
                    jobRepository.save(job);
                } catch (Exception e) {
                    // Non-essential enrichment step: fall back to the un-directed pipeline rather
                    // than failing the whole job over a design-direction hiccup.
                    log.warn("Job {} design direction step failed, continuing without it: {}", job.getId(), e.getMessage());
                }
            }
            var before = JobUsage.snapshot(job);
            OrchestratorPlanResult result = orchestrator.runPlan(job);
            before.apply(job, result.inputTokens(), result.outputTokens(), result.cost());
            jobRepository.save(job);
            validatePlan(result.planJson(), job);

            job.setPlanJson(result.planJson());
            // A concurrent cancel() may already own this row; only finish a status we still hold.
            if (jobRepository.transition(job.getId(), job.getStatus(), JobStatus.PLAN_READY) == 1) {
                job.setStatus(JobStatus.PLAN_READY);
                jobRepository.save(job);
                eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), JobStatus.PLAN_READY));
            }

        } catch (Exception e) {
            log.error("Job {} planning failed: {}", job.getId(), e.getMessage(), e);
            String failedAt = job.getStatus().name();
            // A concurrent cancel() may already own this row; only overwrite a status we still hold.
            if (jobRepository.transition(job.getId(), job.getStatus(), JobStatus.FAILED) == 1) {
                job.setStatus(JobStatus.FAILED);
                job.setFailedAt(failedAt);
                job.setError(e.getMessage());
                job.setCompletedAt(OffsetDateTime.now());
                jobRepository.save(job);
                eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), JobStatus.FAILED));
            }
        } finally {
            cancellationRegistry.unregister(job.getId());
        }
    }

    @Transactional
    public Job confirmPlan(UUID jobId, Map<String, Object> overrides) {
        Job job = jobRepository.findForUpdate(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));
        if (job.getStatus() != JobStatus.PLAN_READY || job.getPlanConfirmedAt() != null)
            throw new IllegalStateException("Only a ready, unconfirmed plan can be confirmed");
        jobService.ensureBudgetAvailable();
        String plan = job.getPlanJson();
        if (overrides != null && !overrides.isEmpty()) {
            try { plan = objectMapper.writeValueAsString(overrides); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid plan", e); }
        }
        validatePlan(plan, job);
        job.setPlanJson(plan);
        job.setPlanConfirmedAt(OffsetDateTime.now());
        job.setStatus(JobStatus.QUEUED);
        jobRepository.save(job);
        return job;
    }

    @Async("jobExecutor")
    public void generateAfterPlanAsync(UUID jobId) {
        if (jobRepository.transition(jobId, JobStatus.QUEUED, JobStatus.PREPARING) == 1)
            jobRepository.findById(jobId).ifPresent(this::generateWithImages);
    }

    private void generateWithImages(Job job) {
        if (job.getStatus() != JobStatus.PREPARING) return;
        if (geminiImageService.isEnabled() && job.getPlanJson() != null) {
            cancellationRegistry.register(job.getId());
            try {
                Map<String, String> imagePaths = generateImagesForPlan(job);
                if (!imagePaths.isEmpty()) {
                    job = jobRepository.findById(job.getId()).orElseThrow();
                    job.setImagePathsJson(objectMapper.writeValueAsString(imagePaths));
                    jobRepository.save(job);
                }
            } catch (Exception e) {
                // A concurrent cancel() may already own this row; only overwrite a status we still hold.
                if (jobRepository.transition(job.getId(), JobStatus.PREPARING, JobStatus.FAILED) == 1) {
                    job = jobRepository.findById(job.getId()).orElseThrow();
                    job.setFailedAt("PREPARING"); job.setError(e.getMessage());
                    job.setCompletedAt(OffsetDateTime.now()); job.setStatus(JobStatus.FAILED);
                    jobRepository.save(job); storage.cleanupFailedJob(job);
                    eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), JobStatus.FAILED));
                }
                return;
            } finally {
                cancellationRegistry.unregister(job.getId());
            }
        }
        jobService.processAsync(job.getId());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> generateImagesForPlan(Job job) throws Exception {
        JsonNode plan = objectMapper.readTree(job.getPlanJson());
        JsonNode outline = plan.path("outline");
        if (!outline.isArray()) return Map.of();

        Map<String, String> result = new HashMap<>();
        for (JsonNode entry : outline) {
            if (!entry.path("imageNeeded").asBoolean(false)) continue;
            String order = entry.path("order").asText();
            String prompt = entry.path("imagePrompt").asText("");
            if (!prompt.isBlank()) geminiImageService.generateImage(prompt, job.getId(), order)
                .ifPresent(path -> result.put(order, path.toString()));
        }
        return result;
    }

    private void validatePlan(String json, Job job) {
        try {
            if (json == null || json.length() > 100_000) throw new IllegalArgumentException("Plan is missing or too large");
            JsonNode plan = objectMapper.readTree(json);
            if (!plan.isObject() || !plan.path("title").isTextual() || plan.path("title").asText().isBlank()
                    || !plan.path("format").asText().equalsIgnoreCase(job.getFormat().name())
                    || !plan.path("outline").isArray() || plan.path("outline").isEmpty()
                    || plan.path("outline").size() > 40) throw new IllegalArgumentException("Invalid plan structure");
            java.util.Set<Integer> orders = new java.util.HashSet<>();
            for (JsonNode item : plan.path("outline")) {
                if (!item.isObject() || !item.path("title").isTextual()
                        || !item.path("summary").isTextual() || !item.path("order").canConvertToInt()
                        || item.path("order").asInt() < 1 || !orders.add(item.path("order").asInt())
                        || (item.path("imageNeeded").asBoolean() && item.path("imagePrompt").asText("").isBlank()))
                    throw new IllegalArgumentException("Invalid plan outline entry");
            }
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Plan must be valid JSON", e); }
    }

    private void updateStatus(Job job, JobStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
        eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), status));
    }
}
