package com.officemanipulator.api;

import com.officemanipulator.api.dto.ErrorResponse;
import com.officemanipulator.api.dto.JobCreatedResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.officemanipulator.config.AiAvailability;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.llm.LlmSelection;
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
    private final LlmClientProvider llm;

    public ChatController(JobService jobService, PlanningService planningService, AiAvailability aiAvailability,
            DocumentEditService documentEditService, LlmClientProvider llm) {
        this.jobService = jobService;
        this.planningService = planningService;
        this.aiAvailability = aiAvailability;
        this.documentEditService = documentEditService;
        this.llm = llm;
    }

    @PostMapping
    public ResponseEntity<?> chat(@Valid @RequestBody ChatRequest req) {
        LlmSelection override;
        try {
            override = llm.resolveOverride(req.llmProvider(), req.llmApiKey(), req.llmModel());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        }
        if (!aiAvailability.enabled(override)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("AI etkin değil. Ayarlar bölümündeki Kontrol et düğmesiyle API anahtarınızı doğrulayın ya da istekte llm_api_key gönderin."));
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
            llm.bindJob(job.getId(), override);
            planningService.createPlanAsync(job.getId());
            return ResponseEntity.accepted().body(JobCreatedResponse.from(job));
        } catch (BudgetExceededException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(ErrorResponse.of(e.getMessage()));
        }
    }

    /** Several files (repeat the "file" part) together are the sources of one new document. */
    static final int MAX_SOURCE_FILES = 10;
    /** Combined source text cap (~100k tokens); larger sources are refused, never truncated (truncation loses information).
     * Lower it (MAX_SOURCE_CHARS) for a self-hosted model with a small context. */
    static final int MAX_COMBINED_SOURCE_CHARS = 400_000;
    @org.springframework.beans.factory.annotation.Value("${app.limits.source-chars:400000}")
    int maxSourceChars = MAX_COMBINED_SOURCE_CHARS;

    /** One file: edit or convert it. Several files: one new document is generated from all of them. */
    @PostMapping(value = "/with-file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chatWithFile(
            @RequestParam("file") java.util.List<MultipartFile> files,
            @RequestParam("message") String message,
            @RequestParam(value = "format", required = false) String formatParam,
            @RequestParam(value = "llm_provider", required = false) String llmProvider,
            @RequestParam(value = "llm_api_key", required = false) String llmApiKey,
            @RequestParam(value = "llm_model", required = false) String llmModel
    ) {
        LlmSelection override;
        try {
            override = llm.resolveOverride(llmProvider, llmApiKey, llmModel);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(e.getMessage()));
        }
        if (!aiAvailability.enabled(override)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of("AI etkin değil. Ayarlar bölümündeki Kontrol et düğmesiyle API anahtarınızı doğrulayın ya da istekte llm_api_key gönderin."));
        }
        if (message == null || message.isBlank() || message.length() > 10_000) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("message is required (max 10,000 characters)"));
        }
        if (files.isEmpty() || files.size() > MAX_SOURCE_FILES) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("1 ile " + MAX_SOURCE_FILES + " arasında belge gönderin"));
        }
        for (MultipartFile file : files) {
            if (file.isEmpty() || file.getSize() > com.officemanipulator.office.OfficeDocumentClient.MAX_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of("Belge boş olamaz ve en fazla 100 MB olabilir"));
            }
        }
        OutputFormat targetFormat = parseFormat(formatParam);
        if (targetFormat == null && formatParam != null && !formatParam.isBlank() && !"AUTO".equalsIgnoreCase(formatParam)) {
            return ResponseEntity.badRequest().body(ErrorResponse.of("Invalid format: " + formatParam));
        }
        try {
            if (files.size() > 1) {
                StringBuilder sources = new StringBuilder();
                for (int i = 0; i < files.size(); i++) {
                    Job source = jobService.importSourceDocument(files.get(i).getOriginalFilename(), files.get(i).getBytes());
                    sources.append("=== BELGE ").append(i + 1).append(": ").append(source.getFileName()).append(" ===\n")
                            .append(documentEditService.sourceText(source)).append("\n");
                }
                if (sources.length() > maxSourceChars) {
                    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.of(
                            "Belgelerin toplam metni çok uzun (" + sources.length() + " karakter, en fazla " + maxSourceChars
                                    + "). Bilgi kaybı olmaması için kesilmedi; daha az belgeyle deneyin."));
                }
                Job job = jobService.createJob(message, sources.toString(), targetFormat);
                llm.bindJob(job.getId(), override);
                planningService.createPlanAsync(job.getId());
                return ResponseEntity.accepted().body(JobCreatedResponse.from(job));
            }
            MultipartFile file = files.get(0);
            Job sourceJob = jobService.importSourceDocument(file.getOriginalFilename(), file.getBytes());
            Job editJob = documentEditService.createEditJob(sourceJob.getId(), message, null, targetFormat);
            llm.bindJob(editJob.getId(), override);
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

    /** llm_*: optional per-request override of the .env LLM provider/key/model (see LlmClientProvider). */
    public record ChatRequest(
            @NotBlank @Size(max = 10_000) String message,
            @Size(max = 81_920)          String sourceText,
                                          String format,
            @JsonProperty("llm_provider") String llmProvider,
            @JsonProperty("llm_api_key")  String llmApiKey,
            @JsonProperty("llm_model")    String llmModel
    ) {
        @Override public String toString() { return "ChatRequest[format=" + format + ", llmProvider=" + llmProvider + ", llmModel=" + llmModel + "]"; }
    }
}
