package com.officemanipulator.service;

import com.officemanipulator.claude.AgentOrchestrator;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.domain.*;
import com.officemanipulator.event.JobStatusEvent;
import com.officemanipulator.exception.BudgetExceededException;
import com.officemanipulator.storage.FileStorageService;
import com.officemanipulator.validator.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final AgentOrchestrator orchestrator;
    private final FileValidator validator;
    private final FileStorageService storageService;
    private final BudgetService budgetService;
    private final ApplicationEventPublisher eventPublisher;
    private final JobCancellationRegistry cancellationRegistry;
    private final Optional<com.officemanipulator.office.DocumentConversionService> conversionService;

    @Value("${app.storage.file-ttl-hours:72}")
    private long fileTtlHours;

    @org.springframework.beans.factory.annotation.Autowired
    public JobService(
            JobRepository jobRepository,
            AgentOrchestrator orchestrator,
            FileValidator validator,
            FileStorageService storageService,
            BudgetService budgetService,
            ApplicationEventPublisher eventPublisher,
            JobCancellationRegistry cancellationRegistry,
            Optional<com.officemanipulator.office.DocumentConversionService> conversionService
    ) {
        this.jobRepository  = jobRepository;
        this.orchestrator   = orchestrator;
        this.validator      = validator;
        this.storageService = storageService;
        this.budgetService  = budgetService;
        this.eventPublisher = eventPublisher;
        this.cancellationRegistry = cancellationRegistry;
        this.conversionService = conversionService;
    }

    public JobService(
            JobRepository jobRepository,
            AgentOrchestrator orchestrator,
            FileValidator validator,
            FileStorageService storageService,
            BudgetService budgetService,
            ApplicationEventPublisher eventPublisher,
            JobCancellationRegistry cancellationRegistry
    ) {
        this(jobRepository, orchestrator, validator, storageService, budgetService, eventPublisher, cancellationRegistry, Optional.empty());
    }

    /** Formats a source document can be uploaded as (chat file attach, independent of the
     * ONLYOFFICE editor module). Mirrors {@code OfficeService.EDITABLE_FORMATS}. */
    private static final Set<OutputFormat> IMPORTABLE_SOURCE_FORMATS = EnumSet.of(
            OutputFormat.DOCX, OutputFormat.PPTX, OutputFormat.XLSX, OutputFormat.CSV, OutputFormat.PDF,
            OutputFormat.ODT, OutputFormat.ODS, OutputFormat.ODP, OutputFormat.DOC, OutputFormat.TXT);

    private static OutputFormat detectImportFormat(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(Locale.ROOT);
        for (OutputFormat format : IMPORTABLE_SOURCE_FORMATS) if (lower.endsWith("." + format.fileExtension())) return format;
        return null;
    }

    /** Persists an uploaded file as a completed source job (status DONE), so it can be used as
     * the input to a chat-driven edit/conversion request ({@code DocumentEditService.createEditJob}). */
    @Transactional
    public Job importSourceDocument(String fileName, byte[] bytes) {
        OutputFormat format = detectImportFormat(fileName);
        if (format == null)
            throw new IllegalArgumentException("Desteklenmeyen belge türü: " + IMPORTABLE_SOURCE_FORMATS);
        try {
            com.officemanipulator.office.OfficeDocumentClient.validate(bytes, format);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Geçerli bir " + format + " belgesi seçin (en fazla 100 MB)", e);
        }
        String safeName = fileName.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (safeName.length() > 180) safeName = "Belge." + format.fileExtension();

        Job job = new Job();
        job.setFormat(format);
        job.setStatus(JobStatus.DONE);
        job.setRequest("Yüklenen belge: " + safeName);
        job.setFileName(safeName);
        job.setCompletedAt(OffsetDateTime.now());
        job.setExpiresAt(OffsetDateTime.now().plusHours(fileTtlHours));
        jobRepository.save(job);
        Path path = storageService.resolveOutputPath(job.getId(), format);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
        } catch (Exception e) {
            throw new IllegalStateException("Belge diske yazılamadı", e);
        }
        job.setFilePath(path.toString());
        job.setFileSize((long) bytes.length);
        return jobRepository.save(job);
    }

    public enum CancelOutcome { CANCELLED, ALREADY_FINISHED, NOT_FOUND }

    public CancelOutcome cancel(UUID jobId) {
        Job job = jobRepository.findById(jobId).orElse(null);
        if (job == null) return CancelOutcome.NOT_FOUND;
        if (jobRepository.cancelIfActive(jobId) == 0) return CancelOutcome.ALREADY_FINISHED;
        cancellationRegistry.interruptIfRunning(jobId);
        job = jobRepository.findById(jobId).orElseThrow();
        job.setCompletedAt(OffsetDateTime.now());
        jobRepository.save(job);
        storageService.cleanupFailedJob(job);
        eventPublisher.publishEvent(new JobStatusEvent(this, jobId, JobStatus.CANCELLED));
        return CancelOutcome.CANCELLED;
    }

    @Transactional
    public Job createJob(String message, String sourceText, OutputFormat format) {
        if (budgetService.isLimitReached()) {
            throw new BudgetExceededException("Budget limit of $" + budgetService.getLimit() + " has been reached.");
        }
        Job job = new Job();
        job.setRequest(message);
        job.setSourceText(sourceText);
        job.setFormat(format != null ? format : detectFormat(message));
        job.setExpiresAt(OffsetDateTime.now().plusHours(fileTtlHours));
        return jobRepository.save(job);
    }

    public void recordFragmentUsage(UUID jobId, JobUsage before, Job after) {
        JobUsage now = JobUsage.snapshot(after);
        jobRepository.addUsage(jobId, now.input() - before.input(), now.output() - before.output(), now.cost().subtract(before.cost()));
    }

    public void ensureBudgetAvailable() {
        if (budgetService.isLimitReached()) throw new BudgetExceededException("Budget limit reached");
    }

    public Optional<Job> findById(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    public List<Job> findRecent() {
        return jobRepository.findRecent();
    }

    @Async("jobExecutor")
    public void processAsync(UUID jobId) {
        if (jobRepository.transition(jobId, JobStatus.PREPARING, JobStatus.PROCESSING) == 1
                || jobRepository.transition(jobId, JobStatus.PENDING, JobStatus.PROCESSING) == 1) {
            jobRepository.findById(jobId).ifPresent(this::process);
        }
    }

    private void process(Job job) {
        updateStatus(job, JobStatus.PROCESSING);
        cancellationRegistry.register(job.getId());
        try {
            var before = JobUsage.snapshot(job);
            OrchestratorResult result = orchestrator.run(job);
            before.apply(job, result.inputTokens(), result.outputTokens(), result.cost());
            jobRepository.save(job);

            com.officemanipulator.office.DocumentConversionService.applyIfNeeded(conversionService, job, result.outputPath());

            updateStatus(job, JobStatus.VALIDATING);
            validator.validate(result.outputPath(), job.getFormat());

            job.setFilePath(result.outputPath().toString());
            job.setFileName("output." + job.getFormat().fileExtension());
            job.setFileSize(storageService.fileSize(result.outputPath()));
            job.setCompletedAt(OffsetDateTime.now());
            // A concurrent cancel() may already own this row; only finish a status we still hold.
            if (jobRepository.transition(job.getId(), job.getStatus(), JobStatus.DONE) == 1) {
                job.setStatus(JobStatus.DONE);
                jobRepository.save(job);
                eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), JobStatus.DONE));
            } else {
                storageService.cleanupFailedJob(job);
            }

        } catch (Exception e) {
            log.error("Job {} failed: {}", job.getId(), e.getMessage(), e);
            String failedAt = job.getStatus().name();
            if (jobRepository.transition(job.getId(), job.getStatus(), JobStatus.FAILED) == 1) {
                job.setStatus(JobStatus.FAILED);
                job.setFailedAt(failedAt);
                job.setError(e.getMessage());
                job.setCompletedAt(OffsetDateTime.now());
                jobRepository.save(job);
                storageService.cleanupFailedJob(job);
                eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), JobStatus.FAILED));
            }
        } finally {
            cancellationRegistry.unregister(job.getId());
        }
    }

    private void updateStatus(Job job, JobStatus status) {
        job.setStatus(status);
        jobRepository.save(job);
        eventPublisher.publishEvent(new JobStatusEvent(this, job.getId(), status));
    }

    private OutputFormat detectFormat(String message) {
        String lower = message.toLowerCase();
        if (lower.contains("pptx") || lower.contains("sunum") || lower.contains("slayt")
                || lower.contains("powerpoint") || lower.contains("presentation")) return OutputFormat.PPTX;
        if (lower.contains("pdf"))                                                   return OutputFormat.PDF;
        if (lower.contains("xlsx") || lower.contains("excel") || lower.contains("spreadsheet")
                || lower.contains("tablo") || lower.contains("hesap"))               return OutputFormat.XLSX;
        if (lower.contains("csv") || lower.contains("veri listesi"))                 return OutputFormat.CSV;
        if (lower.contains("odt"))                                                   return OutputFormat.ODT;
        if (lower.contains("ods"))                                                   return OutputFormat.ODS;
        if (lower.contains("odp"))                                                   return OutputFormat.ODP;
        if (lower.contains("odb"))                                                   return OutputFormat.ODB;
        if (lower.contains(".doc") || lower.equals("doc"))                           return OutputFormat.DOC;
        if (lower.contains("markdown") || lower.contains(".md"))                     return OutputFormat.MD;
        if (lower.contains("json"))                                                  return OutputFormat.JSON;
        if (lower.contains("xml"))                                                   return OutputFormat.XML;
        if (lower.contains("txt") || lower.contains("düz metin") || lower.contains("duz metin")) return OutputFormat.TXT;
        return OutputFormat.DOCX;
    }
}
