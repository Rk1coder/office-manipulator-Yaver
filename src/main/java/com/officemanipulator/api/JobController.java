package com.officemanipulator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.api.dto.BudgetResponse;
import com.officemanipulator.api.dto.JobResponse;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.JobStatus;
import java.util.List;
import com.officemanipulator.claude.AgentOrchestrator;
import com.officemanipulator.event.JobStatusEvent;
import com.officemanipulator.service.BudgetService;
import com.officemanipulator.service.DocumentEditService;
import com.officemanipulator.service.JobService;
import com.officemanipulator.service.PlanningService;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api")
public class JobController {

    private java.math.BigDecimal inputPrice;
    private java.math.BigDecimal outputPrice;
    private final JobService jobService;
    private final BudgetService budgetService;
    private final DocumentEditService documentEditService;
    private final PlanningService planningService;
    private final ObjectMapper objectMapper;
    private final AgentOrchestrator orchestrator;

    @org.springframework.beans.factory.annotation.Autowired
    private com.officemanipulator.config.LlmClientProvider llm;

    private final Map<UUID, java.util.Set<reactor.core.publisher.FluxSink<Map<String,String>>>> subscribers = new java.util.HashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    void configurePrices(com.officemanipulator.config.ModelPricing pricing) {
        inputPrice = pricing.input(); outputPrice = pricing.output();
    }

    public JobController(JobService jobService, BudgetService budgetService,
                         DocumentEditService documentEditService, PlanningService planningService,
                         ObjectMapper objectMapper, AgentOrchestrator orchestrator) {
        this.jobService          = jobService;
        this.budgetService       = budgetService;
        this.documentEditService = documentEditService;
        this.planningService     = planningService;
        this.orchestrator        = orchestrator;
        this.objectMapper        = objectMapper;
    }

    @GetMapping("/jobs")
    public List<JobResponse> listJobs() {
        return jobService.findRecent().stream()
                .map(JobResponse::from)
                .toList();
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID id) {
        return jobService.findById(id)
                .map(job -> ResponseEntity.ok(JobResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, String>> streamEvents(@PathVariable UUID id) {
        return Flux.<Map<String,String>>create(emitter -> {
            synchronized (subscribers) {
                Job job = jobService.findById(id).orElse(null);
                if (job == null) { emitter.error(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)); return; }
                subscribers.computeIfAbsent(id, ignored -> new java.util.HashSet<>()).add(emitter);
                emitter.onDispose(() -> {
                    synchronized (subscribers) {
                        var listeners = subscribers.get(id);
                        if (listeners != null) {
                            listeners.remove(emitter);
                            if (listeners.isEmpty()) subscribers.remove(id);
                        }
                    }
                });
                emitter.next(Map.of("status", job.getStatus().name()));
                if (terminal(job.getStatus())) emitter.complete();
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.LATEST).timeout(Duration.ofMinutes(10));
    }


    /** The day the deprecated edit routes below stop working (HTTP Sunset header). */
    static final String SUNSET = "Tue, 1 Jun 2027 00:00:00 GMT";

    /**
     * The one edit endpoint. The document is {"jobId"} (JSON) or an uploaded "file" (multipart, other
     * fields as form fields), and the fields pick the kind of edit:
     * {"instruction"[, "selectedText"][, "format"]} AI edit (template kept when possible: text changes,
     * new pages anywhere, topic changes with new pictures), {"fragment", "instruction"} one fragment
     * rewritten and returned inline, {"content"} the visual editor's text saved into the original file,
     * {"format"} alone: conversion to another format.
     */
    @PostMapping(value = "/edit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> edit(@RequestBody Map<String, Object> body) {
        UUID id;
        try { id = UUID.fromString(String.valueOf(body.remove("jobId"))); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", "jobId is required (or upload a file)")); }
        return edit(id, body);
    }

    @PostMapping(value = "/edit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> editUpload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                        @RequestParam Map<String, String> fields) {
        if (file.isEmpty() || file.getSize() > com.officemanipulator.office.OfficeDocumentClient.MAX_BYTES)
            return ResponseEntity.status(413).body(Map.of("error", "Belge boş olamaz ve en fazla 100 MB olabilir"));
        Job source;
        try { source = jobService.importSourceDocument(file.getOriginalFilename(), file.getBytes()); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        catch (java.io.IOException e) { return ResponseEntity.badRequest().body(Map.of("error", "Belge okunamadı")); }
        Map<String, Object> body = new java.util.HashMap<>(fields);
        body.remove("jobId");
        return edit(source.getId(), body);
    }

    @Deprecated
    @PostMapping("/jobs/{id}/edit")
    public ResponseEntity<?> editLegacy(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return deprecated("POST /api/jobs/{id}/edit", edit(id, body));
    }

    @Deprecated
    @PostMapping("/jobs/{id}/edit-fragment")
    public ResponseEntity<?> editFragmentLegacy(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return deprecated("POST /api/jobs/{id}/edit-fragment", edit(id, body));
    }

    @Deprecated
    @PatchMapping("/jobs/{id}/content")
    public ResponseEntity<?> patchContentLegacy(@PathVariable UUID id, @RequestBody Map<String, Object> preview) {
        return deprecated("PATCH /api/jobs/{id}/content", edit(id, new java.util.HashMap<>(Map.of("content", preview))));
    }

    @Deprecated
    @PostMapping("/jobs/{id}/convert")
    public ResponseEntity<?> convertLegacy(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        body.remove("instruction");
        return deprecated("POST /api/jobs/{id}/convert", edit(id, body));
    }

    /** Same answer plus the standard deprecation headers, so API clients see where to move. */
    static ResponseEntity<?> deprecated(String route, ResponseEntity<?> response) {
        org.slf4j.LoggerFactory.getLogger(JobController.class).warn("Deprecated edit route {} called; use POST /api/edit (until {})", route, SUNSET);
        return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                .header("Deprecation", "true").header("Sunset", SUNSET)
                .header("Link", "</api/edit>; rel=\"successor-version\"")
                .body(response.getBody());
    }

    private ResponseEntity<?> edit(UUID id, Map<String, Object> body) {
        if (body.get("fragment") != null) return editFragment(id, body);
        if (body.get("content") != null) {
            if (!(body.get("content") instanceof Map<?, ?> content)) return ResponseEntity.badRequest().body(Map.of("error", "content must be an object"));
            @SuppressWarnings("unchecked") Map<String, Object> preview = new java.util.LinkedHashMap<>((Map<String, Object>) content);
            for (String key : List.of("llm_provider", "llm_api_key", "llm_model")) if (body.containsKey(key)) preview.put(key, body.get(key));
            return patchContent(id, preview);
        }
        if (body.get("instruction") == null && body.get("format") instanceof String format) return convert(id, format);
        return editJob(id, body);
    }

    private ResponseEntity<?> editJob(UUID id, Map<String, Object> body) {
        com.officemanipulator.llm.LlmSelection override;
        com.officemanipulator.domain.OutputFormat target = null;
        try {
            override = takeOverride(body);
            if (body.get("format") instanceof String format && !format.isBlank())
                target = com.officemanipulator.domain.OutputFormat.valueOf(format.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        String instruction   = body.get("instruction") instanceof String text ? text : null;
        String selectedText  = body.get("selectedText") instanceof String text ? text : null;
        if (instruction == null || instruction.isBlank() || instruction.length() > 10000 || (selectedText != null && selectedText.length() > 81920)) {
            return ResponseEntity.badRequest().body(Map.of("error", "instruction is required"));
        }
        try {
            Job editJob = documentEditService.createEditJob(id, instruction, selectedText, target);
            if (llm != null) llm.bindJob(editJob.getId(), override);
            documentEditService.processEditAsync(editJob.getId(), id);
            return ResponseEntity.accepted().body(Map.of("jobId", editJob.getId().toString(), "format", editJob.getFormat().name()));
        } catch (com.officemanipulator.exception.JobNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (com.officemanipulator.exception.BudgetExceededException e) {
            return ResponseEntity.status(402).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<?> cancelJob(@PathVariable UUID id) {
        return switch (jobService.cancel(id)) {
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case ALREADY_FINISHED -> ResponseEntity.status(409).body(Map.of("error", "Job already finished"));
            case CANCELLED -> ResponseEntity.ok(Map.of("jobId", id.toString(), "status", JobStatus.CANCELLED.name()));
        };
    }

    @GetMapping("/jobs/{id}/plan")
    public ResponseEntity<?> getPlan(@PathVariable UUID id) {
        return jobService.findById(id)
                .map(job -> {
                    if (job.getPlanJson() == null) {
                        return ResponseEntity.status(409).body(Map.of("error", "Plan not ready yet"));
                    }
                    try {
                        JsonNode plan = objectMapper.readTree(job.getPlanJson());
                        Map<String, Object> body = new java.util.LinkedHashMap<>();
                        body.put("status", job.getStatus().name());
                        body.put("plan", plan);
                        if (job.getDesignDirectionJson() != null)
                            body.put("designDirection", objectMapper.readTree(job.getDesignDirectionJson()));
                        return ResponseEntity.ok(body);
                    } catch (Exception e) {
                        return ResponseEntity.status(500).body(Map.of("error", "Plan could not be parsed"));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/jobs/{id}/plan/confirm")
    public ResponseEntity<?> confirmPlan(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> overrides
    ) {
        try {
            var override = takeOverride(overrides);
            planningService.confirmPlan(id, overrides);
            // A confirm without its own override keeps whatever the plan request bound.
            if (override != null) llm.bindJob(id, override);
            planningService.generateAfterPlanAsync(id);
            return ResponseEntity.accepted().body(Map.of("jobId", id.toString()));
        } catch (com.officemanipulator.exception.JobNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (com.officemanipulator.exception.BudgetExceededException e) {
            return ResponseEntity.status(402).body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> editFragment(UUID id, Map<String, Object> body) {
        com.officemanipulator.llm.LlmSelection override;
        try { override = takeOverride(body); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        Job job = jobService.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.getStatus() != JobStatus.DONE || job.getFilePath() == null
                || job.getExpiresAt().isBefore(java.time.OffsetDateTime.now()))
            return ResponseEntity.status(409).body(Map.of("error", "Document is not available for editing"));
        if (budgetService.isLimitReached()) {
            return ResponseEntity.status(402).body(Map.of("error",
                    "Budget limit of $" + budgetService.getLimit() + " has been reached."));
        }
        Object fragment = body.get("fragment");
        Object instructionObj = body.get("instruction");
        if (fragment == null || !(instructionObj instanceof String instruction) || instruction.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fragment and instruction are required"));
        }
        var before = com.officemanipulator.service.JobUsage.snapshot(job);
        try {
            String fragmentJson = objectMapper.writeValueAsString(fragment);
            if (fragmentJson.length() > 81920 || instruction.length() > 10000)
                return ResponseEntity.badRequest().body(Map.of("error", "Fragment or instruction is too large"));
            String resultJson = llm == null ? orchestrator.runFragmentEdit(job, fragmentJson, instruction)
                    : llm.callWith(override, () -> orchestrator.runFragmentEdit(job, fragmentJson, instruction));
            JsonNode result = objectMapper.readTree(resultJson);
            return ResponseEntity.ok(Map.of("fragment", result));
        } catch (com.officemanipulator.exception.BudgetExceededException e) {
            return ResponseEntity.status(402).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Fragment edit failed: " + e.getMessage()));
        } finally {
            jobService.recordFragmentUsage(id, before, job);
        }
    }

    private ResponseEntity<?> patchContent(UUID id, Map<String, Object> patchedPreviewData) {
        try {
            var override = takeOverride(patchedPreviewData);
            Job rebuildJob = documentEditService.createRebuildJob(id, patchedPreviewData);
            if (rebuildJob.getStatus() == JobStatus.PENDING) {
                if (llm != null) llm.bindJob(rebuildJob.getId(), override);
                documentEditService.processEditAsync(rebuildJob.getId(), id);
            }
            return ResponseEntity.accepted().body(Map.of("jobId", rebuildJob.getId().toString()));
        } catch (com.officemanipulator.exception.JobNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (com.officemanipulator.exception.BudgetExceededException e) {
            return ResponseEntity.status(402).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> convert(UUID id, String targetFormat) {
        try {
            var format = com.officemanipulator.domain.OutputFormat.valueOf(targetFormat.toUpperCase(java.util.Locale.ROOT));
            Job job = documentEditService.createConversionJob(id, format);
            documentEditService.processConversionAsync(job.getId(), id);
            return ResponseEntity.accepted().body(Map.of("jobId", job.getId().toString()));
        } catch (com.officemanipulator.exception.JobNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (com.officemanipulator.exception.BudgetExceededException e) {
            return ResponseEntity.status(402).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Desteklenmeyen hedef biçim veya dönüşüm"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/budget")
    public BudgetResponse getBudget() {
        return new BudgetResponse(
                budgetService.totalSpent(),
                budgetService.getLimit(),
                budgetService.getRemaining(),
                budgetService.isNearLimit(), inputPrice, outputPrice, budgetService.getReserved(), budgetService.getUncertain()
        );
    }

    @EventListener
    public void onJobStatusChanged(JobStatusEvent event) {
        synchronized (subscribers) {
            var listeners = subscribers.get(event.getJobId());
            if (listeners == null) return;
            for (var emitter : java.util.List.copyOf(listeners)) {
                emitter.next(Map.of("status", event.getStatus().name()));
                if (terminal(event.getStatus())) emitter.complete();
            }
        }
    }
    /** Pulls the optional llm_provider/llm_api_key/llm_model override out of a request body (removing
     * it, so it never reaches persisted job data); null when absent. Throws IllegalArgumentException. */
    private com.officemanipulator.llm.LlmSelection takeOverride(Map<String, ?> body) {
        if (body == null || llm == null) return null;
        Object provider = body.remove("llm_provider"), key = body.remove("llm_api_key"), model = body.remove("llm_model");
        return llm.resolveOverride(text(provider), text(key), text(model));
    }

    private static String text(Object value) {
        if (value != null && !(value instanceof String)) throw new IllegalArgumentException("llm_* alanları metin olmalı.");
        return (String) value;
    }

    private boolean terminal(JobStatus status) {
        return status == JobStatus.DONE || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }
}
