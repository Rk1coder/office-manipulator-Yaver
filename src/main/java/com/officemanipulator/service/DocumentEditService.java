package com.officemanipulator.service;

import com.officemanipulator.claude.ClaudeOrchestrator;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.domain.*;
import com.officemanipulator.event.JobStatusEvent;
import com.officemanipulator.preview.PreviewData;
import com.officemanipulator.preview.PreviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.storage.FileStorageService;
import com.officemanipulator.validator.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentEditService {

    private static final Logger log = LoggerFactory.getLogger(DocumentEditService.class);

    private final JobRepository jobRepository;
    private final ClaudeOrchestrator orchestrator;
    private final PreviewService previewService;
    private final FileValidator validator;
    private final FileStorageService storageService;
    private final BudgetService budgetService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final DirectPatchService directPatchService;
    private final JobCancellationRegistry cancellationRegistry;
    private final java.util.Optional<com.officemanipulator.office.DocumentConversionService> conversionService;

    @Value("${app.storage.file-ttl-hours:72}")
    private long fileTtlHours;

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentEditService(
            JobRepository jobRepository,
            ClaudeOrchestrator orchestrator,
            PreviewService previewService,
            FileValidator validator,
            FileStorageService storageService,
            BudgetService budgetService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            DirectPatchService directPatchService,
            JobCancellationRegistry cancellationRegistry,
            java.util.Optional<com.officemanipulator.office.DocumentConversionService> conversionService
    ) {
        this.jobRepository  = jobRepository;
        this.orchestrator   = orchestrator;
        this.previewService = previewService;
        this.validator      = validator;
        this.storageService = storageService;
        this.budgetService  = budgetService;
        this.eventPublisher = eventPublisher;
        this.objectMapper   = objectMapper;
        this.directPatchService = directPatchService;
        this.cancellationRegistry = cancellationRegistry;
        this.conversionService = conversionService;
    }

    public DocumentEditService(
            JobRepository jobRepository,
            ClaudeOrchestrator orchestrator,
            PreviewService previewService,
            FileValidator validator,
            FileStorageService storageService,
            BudgetService budgetService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            DirectPatchService directPatchService,
            JobCancellationRegistry cancellationRegistry
    ) {
        this(jobRepository, orchestrator, previewService, validator, storageService, budgetService, eventPublisher,
                objectMapper, directPatchService, cancellationRegistry, java.util.Optional.empty());
    }

    @Transactional
    public Job createEditJob(UUID originalJobId, String instruction, String selectedText) {
        return createEditJob(originalJobId, instruction, selectedText, null);
    }

    @Transactional
    public Job createEditJob(UUID originalJobId, String instruction, String selectedText, OutputFormat targetFormat) {
        if (budgetService.isLimitReached()) {
            throw new com.officemanipulator.exception.BudgetExceededException(
                    "Budget limit of $" + budgetService.getLimit() + " has been reached.");
        }
        Job original = jobRepository.findById(originalJobId)
                .orElseThrow(() -> new com.officemanipulator.exception.JobNotFoundException(originalJobId));
        requireEditable(original);

        String fullInstruction = instruction;
        if (selectedText != null && !selectedText.isBlank()) {
            fullInstruction = "Selected text context: \"" + selectedText.strip() + "\"\n\nEdit instruction: " + instruction;
        }

        Job editJob = new Job();
        editJob.setRequest(fullInstruction);
        editJob.setFormat(targetFormat != null ? targetFormat : original.getFormat());
        editJob.setParentJobId(originalJobId);
        editJob.setExpiresAt(OffsetDateTime.now().plusHours(fileTtlHours));
        return jobRepository.save(editJob);
    }

    public Job createRebuildJob(UUID originalJobId, Map<String, Object> patchedPreviewData) {
        Job original = jobRepository.findById(originalJobId)
                .orElseThrow(() -> new com.officemanipulator.exception.JobNotFoundException(originalJobId));
        requireEditable(original);

        Job patched = tryDirectPatch(original, patchedPreviewData);
        if (patched != null) return patched;
        if (original.getFormat() != OutputFormat.PDF)
            throw new IllegalArgumentException("This structural edit cannot preserve the original formatting. Use an explicit document edit instead.");

        if (budgetService.isLimitReached()) throw new com.officemanipulator.exception.BudgetExceededException("Budget limit reached");
        return createSlowRebuildJob(original, patchedPreviewData);
    }

    /**
     * Fast path: if the WYSIWYG edit kept the same structure as the original (same block/slide/row
     * count — pure text-value changes), patch the existing file directly with POI instead of asking
     * Claude to regenerate the whole document. Unsupported structure returns null;
     * failures persist a FAILED child and never silently regenerate the original.
     */
    private Job tryDirectPatch(Job original, Map<String, Object> patchedPreviewData) {
        if (original.getFilePath() == null) return null;
        Job patchJob = null;
        try {
            PreviewData originalPreview = previewService.extract(original);
            if (originalPreview == null || !directPatchService.canPatch(originalPreview, patchedPreviewData)) {
                return null;
            }

            patchJob = new Job();
            patchJob.setRequest("DIRECT_PATCH: structural-safe field edit applied without Claude");
            patchJob.setFormat(original.getFormat());
            patchJob.setParentJobId(original.getId());
            patchJob.setExpiresAt(OffsetDateTime.now().plusHours(fileTtlHours));
            updateStatus(patchJob, JobStatus.PROCESSING);

            Path outputPath = storageService.resolveOutputPath(patchJob.getId(), patchJob.getFormat());
            directPatchService.applyPatch(Paths.get(original.getFilePath()), outputPath, originalPreview.type(), patchedPreviewData);

            updateStatus(patchJob, JobStatus.VALIDATING);
            validator.validate(outputPath, patchJob.getFormat());

            patchJob.setFilePath(outputPath.toString());
            patchJob.setFileName("output." + patchJob.getFormat().fileExtension());
            patchJob.setFileSize(storageService.fileSize(outputPath));
            patchJob.setTokenInput(0);
            patchJob.setTokenOutput(0);
            patchJob.setCostUsd(BigDecimal.ZERO);
            patchJob.setCompletedAt(OffsetDateTime.now());
            updateStatus(patchJob, JobStatus.DONE);
            return patchJob;

        } catch (Exception e) {
            if (patchJob == null) throw new IllegalArgumentException("Cannot read document for editing", e);
            patchJob.setFailedAt(patchJob.getStatus().name());
            patchJob.setError("Direct patch failed: " + e.getMessage());
            patchJob.setCompletedAt(OffsetDateTime.now());
            storageService.cleanupFailedJob(patchJob);
            updateStatus(patchJob, JobStatus.FAILED);
            return patchJob;
        }
    }

    private Job createSlowRebuildJob(Job original, Map<String, Object> patchedPreviewData) {
        String patchedJson;
        try {
            patchedJson = objectMapper.writeValueAsString(patchedPreviewData);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid content payload: " + e.getMessage());
        }

        String instruction = "WYSIWYG_REBUILD: The user edited the document directly in a visual editor. "
                + "Ignore the existing document content below and rebuild the document EXACTLY from this "
                + "structured content instead (same fields, same order, same text):\n\n" + patchedJson;

        Job rebuildJob = new Job();
        rebuildJob.setRequest(instruction);
        rebuildJob.setFormat(original.getFormat());
        rebuildJob.setParentJobId(original.getId());
        rebuildJob.setExpiresAt(OffsetDateTime.now().plusHours(fileTtlHours));
        return jobRepository.save(rebuildJob);
    }

    @Async("jobExecutor")
    public void processEditAsync(UUID editJobId, UUID originalJobId) {
        startProcessing(editJobId, originalJobId, false);
    }

    @Async("jobExecutor")
    public void processConversionAsync(UUID editJobId, UUID originalJobId) {
        startProcessing(editJobId, originalJobId, true);
    }

    private void startProcessing(UUID editJobId, UUID originalJobId, boolean nativeConversion) {
        try {
            if (jobRepository.transition(editJobId, JobStatus.PENDING, JobStatus.PROCESSING) != 1) return;
            Job editJob    = jobRepository.findById(editJobId).orElseThrow();
            Job originalJob = jobRepository.findById(originalJobId).orElseThrow();
            processEdit(editJob, originalJob, nativeConversion);
        } catch (Exception e) {
            log.error("Edit job {} failed to start: {}", editJobId, e.getMessage());
            if (jobRepository.transition(editJobId, JobStatus.PROCESSING, JobStatus.FAILED) == 1) {
                jobRepository.findById(editJobId).ifPresent(job -> {
                    job.setError(e.getMessage()); job.setFailedAt(JobStatus.PROCESSING.name());
                    job.setCompletedAt(OffsetDateTime.now()); storageService.cleanupFailedJob(job); updateStatus(job, JobStatus.FAILED);
                });
            }
        }
    }

    private void processEdit(Job editJob, Job originalJob, boolean nativeConversion) {
        editJob.setStatus(JobStatus.PROCESSING);
        eventPublisher.publishEvent(new JobStatusEvent(this, editJob.getId(), JobStatus.PROCESSING));
        cancellationRegistry.register(editJob.getId());
        try {
            Path outputPath;
            if (nativeConversion) {
                requireEditable(originalJob);
                outputPath = storageService.resolveOutputPath(editJob.getId(), editJob.getFormat());
                conversionService.orElseThrow(() -> new IllegalStateException("ONLYOFFICE etkin olmalı"))
                        .convert(Path.of(originalJob.getFilePath()), outputPath, originalJob.getFormat(), editJob.getFormat());
                editJob.setTokenInput(0);
                editJob.setTokenOutput(0);
                editJob.setCostUsd(BigDecimal.ZERO);
            } else {
                PreviewData content = previewService.extract(originalJob);
                String documentContext = formatForEdit(content, originalJob.getFormat());
                var before = JobUsage.snapshot(editJob);
                OrchestratorResult result = orchestrator.runEdit(editJob, documentContext);
                before.apply(editJob, result.inputTokens(), result.outputTokens(), result.cost());
                outputPath = result.outputPath();
                com.officemanipulator.office.DocumentConversionService.applyIfNeeded(conversionService, editJob, outputPath);
            }

            if (jobRepository.transition(editJob.getId(), JobStatus.PROCESSING, JobStatus.VALIDATING) != 1) {
                storageService.cleanupFailedJob(editJob);
                return;
            }
            editJob.setStatus(JobStatus.VALIDATING);
            eventPublisher.publishEvent(new JobStatusEvent(this, editJob.getId(), JobStatus.VALIDATING));
            validator.validate(outputPath, editJob.getFormat());

            editJob.setFilePath(outputPath.toString());
            editJob.setFileName("output." + editJob.getFormat().fileExtension());
            editJob.setFileSize(storageService.fileSize(outputPath));
            editJob.setCompletedAt(OffsetDateTime.now());
            // A concurrent cancel() may already own this row; only finish a status we still hold.
            if (jobRepository.transition(editJob.getId(), editJob.getStatus(), JobStatus.DONE) == 1) {
                editJob.setStatus(JobStatus.DONE);
                jobRepository.save(editJob);
                eventPublisher.publishEvent(new JobStatusEvent(this, editJob.getId(), JobStatus.DONE));
            } else {
                storageService.cleanupFailedJob(editJob);
            }

        } catch (Exception e) {
            log.error("Edit job {} failed: {}", editJob.getId(), e.getMessage(), e);
            String failedAt = editJob.getStatus().name();
            // A concurrent cancel() may already own this row; only overwrite a status we still hold.
            if (jobRepository.transition(editJob.getId(), editJob.getStatus(), JobStatus.FAILED) == 1) {
                editJob.setStatus(JobStatus.FAILED);
                editJob.setFailedAt(failedAt);
                editJob.setError(e.getMessage());
                editJob.setCompletedAt(OffsetDateTime.now());
                jobRepository.save(editJob);
                storageService.cleanupFailedJob(editJob);
                eventPublisher.publishEvent(new JobStatusEvent(this, editJob.getId(), JobStatus.FAILED));
            }
        } finally {
            cancellationRegistry.unregister(editJob.getId());
        }
    }

    public Job createConversionJob(UUID originalId, OutputFormat target) {
        Job original = jobRepository.findById(originalId)
                .orElseThrow(() -> new com.officemanipulator.exception.JobNotFoundException(originalId));
        requireEditable(original);
        if (conversionService.isEmpty()) throw new IllegalStateException("Biçim dönüşümü için ONLYOFFICE etkin olmalı");
        com.officemanipulator.office.DocumentConversionService.requireSupported(original.getFormat(), target);
        Job job = new Job();
        job.setRequest("Biçim dönüşümü: " + original.getFormat() + " → " + target);
        job.setFormat(target);
        job.setParentJobId(originalId);
        job.setExpiresAt(OffsetDateTime.now().plusHours(fileTtlHours));
        return jobRepository.save(job);
    }

    private void requireEditable(Job job) {
        if (job.getStatus() != JobStatus.DONE || job.getFilePath() == null
                || !java.nio.file.Files.isRegularFile(Path.of(job.getFilePath()))
                || job.getExpiresAt().isBefore(OffsetDateTime.now()))
            throw new IllegalStateException("The source document is not available for editing");
    }

    private void updateStatus(Job job, JobStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
        eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), status));
    }

    @SuppressWarnings("unchecked")
    private String formatForEdit(PreviewData data, OutputFormat format) {
        if (data == null) throw new IllegalStateException("Document content is unavailable");
        StringBuilder sb = new StringBuilder();

        if (data.slides() != null) {
            for (int i = 0; i < data.slides().size(); i++) {
                var slide = data.slides().get(i);
                sb.append("SLIDE ").append(i + 1).append(": ")
                  .append(slide.getOrDefault("title", "")).append("\n");
                var bullets = (List<?>) slide.get("bullets");
                if (bullets != null) bullets.forEach(b -> sb.append("  - ").append(b).append("\n"));
                var table = (List<?>) slide.get("table");
                if (table != null) {
                    sb.append("  [TABLE]\n");
                    ((List<List<String>>) table).forEach(row ->
                            sb.append("  | ").append(String.join(" | ", row)).append("\n"));
                }
                String notes = String.valueOf(slide.getOrDefault("notes", "")).strip();
                if (!notes.isEmpty()) sb.append("  Notes: ").append(notes).append("\n");
                sb.append("\n");
            }

        } else if (data.blocks() != null) {
            for (var block : data.blocks()) {
                String type = String.valueOf(block.get("type"));
                String text = String.valueOf(block.get("text"));
                if ("table".equals(type)) {
                    sb.append("[TABLE]\n");
                    var rows = (List<List<String>>) block.get("rows");
                    if (rows != null) rows.forEach(row ->
                            sb.append("  | ").append(String.join(" | ", row)).append("\n"));
                } else {
                    sb.append("[").append(type.toUpperCase()).append("] ").append(text).append("\n");
                }
            }

        } else if (data.lines() != null) {
            data.lines().forEach(l -> sb.append(l).append("\n"));

        } else if (data.csvRows() != null) {
            data.csvRows().forEach(row -> sb.append(String.join(",", row)).append("\n"));

        } else if (data.sheets() != null) {
            sb.append(objectMapper.valueToTree(data.sheets()).toString());
        }

        if (sb.isEmpty()) throw new IllegalStateException("Document content is empty");
        return sb.toString();
    }
}
