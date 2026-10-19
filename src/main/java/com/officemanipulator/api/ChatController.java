package com.officemanipulator.api;

import com.officemanipulator.api.dto.ErrorResponse;
import com.officemanipulator.api.dto.JobCreatedResponse;
import com.officemanipulator.config.AiAvailability;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.domain.Job;
import com.officemanipulator.exception.BudgetExceededException;
import com.officemanipulator.service.DocumentEditService;
import com.officemanipulator.service.JobService;
import com.officemanipulator.service.PlanningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final JobService jobService;
    private final PlanningService planningService;
    private final AiAvailability aiAvailability;
    private final DocumentEditService documentEditService;

    public ChatController(JobService jobService, PlanningService planningService, AiAvailability aiAvailability,
            DocumentEditService documentEditService) {
        this.jobService = jobService;
        this.planningService = planningService;
        this.aiAvailability = aiAvailability;
        this.documentEditService = documentEditService;
    }

    @PostMapping
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest req) {
        if (!aiAvailability.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("Claude etkin değil. Ayarlar bölümündeki Kontrol et düğmesiyle API anahtarınızı doğrulayın."));
        }

        if (req.sourceText() != null && req.sourceText().getBytes().length > 81_920) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("Source text exceeds 80 KB limit."));
        }

        OutputFormat format = parseFormat(req.format());
        if (format == null && req.format() != null) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("Invalid format: " + req.format()
                    + ". Valid values: DOCX, PPTX, PDF, CSV, XLSX, ODT, ODS, ODP, ODB, DOC, TXT, MD, JSON, XML"));
        }

        try {
            var job = jobService.createJob(req.message(), req.sourceText(), format);
            planningService.createPlanAsync(job.getId());
            return ResponseEntity.accepted().body(JobCreatedResponse.from(job));
        } catch (BudgetExceededException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ErrorResponse.of(e.getMessage()));
        }
    }

    @PostMapping(value = "/with-file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chatWithFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("message") String message,
            @RequestParam(value = "format", required = false) String formatParam
    ) {
        if (!aiAvailability.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("Claude etkin değil. Ayarlar bölümündeki Kontrol et düğmesiyle API anahtarınızı doğrulayın."));
        }
        if (message == null || message.isBlank() || message.length() > 10_000) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("message is required (max 10,000 characters)"));
        }
        if (file.isEmpty() || file.getSize() > com.officemanipulator.office.OfficeDocumentClient.MAX_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of("Belge boş olamaz ve en fazla 100 MB olabilir"));
        }
        OutputFormat targetFormat = parseFormat(formatParam);
        if (targetFormat == null && formatParam != null && !formatParam.isBlank() && !"AUTO".equalsIgnoreCase(formatParam)) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("Invalid format: " + formatParam));
        }
        try {
            Job sourceJob = jobService.importSourceDocument(file.getOriginalFilename(), file.getBytes());
            Job editJob = documentEditService.createEditJob(sourceJob.getId(), message, null, targetFormat);
            documentEditService.processEditAsync(editJob.getId(), sourceJob.getId());
            return ResponseEntity.accepted().body(JobCreatedResponse.from(editJob));
        } catch (BudgetExceededException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ErrorResponse.of(e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("Belge okunamadı"));
        }
    }

    private OutputFormat parseFormat(String raw) {
        if (raw == null) return null;
        try {
            return OutputFormat.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record ChatRequest(
            @NotBlank @Size(max = 10_000) String message,
            @Size(max = 81_920)          String sourceText,
                                          String format
    ) {}
}
