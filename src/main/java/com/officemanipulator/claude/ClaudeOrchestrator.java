package com.officemanipulator.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.dto.OrchestratorDesignResult;
import com.officemanipulator.claude.dto.OrchestratorDocumentEditResult;
import com.officemanipulator.claude.dto.OrchestratorPlanResult;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.domain.Job;
import com.officemanipulator.llm.LlmClient;
import com.officemanipulator.llm.LlmClient.Message;
import com.officemanipulator.llm.LlmClient.Response;
import com.officemanipulator.llm.LlmClient.ToolCall;
import com.officemanipulator.llm.LlmSelection;
import com.officemanipulator.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives the LLM tool-use loop: sends the user request, receives a tool call,
 * delegates execution to ToolDispatcher, then feeds the result back to the model.
 * Provider-neutral: each call goes to the {@link LlmClient} adapter of the job's
 * {@link LlmSelection} (.env default or the request's override, see LlmClientProvider).
 * System prompt lives in SystemPrompts; tool routing lives in ToolDispatcher.
 */
@Service
public class ClaudeOrchestrator implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeOrchestrator.class);

    @org.springframework.beans.factory.annotation.Autowired
    private com.officemanipulator.service.ApiBillingService billing;
    private final com.officemanipulator.config.LlmClientProvider provider;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher dispatcher;
    private final FileStorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${app.claude.max-tokens}")
    private long maxTokens;
    @Value("${app.claude.max-tool-turns}")
    private int maxToolTurns;
    /** Render → review → rewrite rounds after a deck first succeeds (0 disables the visual review). */
    @Value("${app.presentation.review-rounds:2}")
    private int reviewRounds;
    private static final int MAX_REVIEW_SLIDES = 24;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.officemanipulator.office.SlideRenderer renderer;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PresentationSkills skills;
    private String pricingModel;
    private BigDecimal inputPricePerMillion;
    private BigDecimal outputPricePerMillion;

    @org.springframework.beans.factory.annotation.Autowired
    void configurePrices(com.officemanipulator.config.ModelPricing pricing) {
        pricingModel = pricing.model();
        inputPricePerMillion = pricing.input(); outputPricePerMillion = pricing.output();
    }

    public ClaudeOrchestrator(
            com.officemanipulator.config.LlmClientProvider provider,
            ToolRegistry toolRegistry,
            ToolDispatcher dispatcher,
            FileStorageService storageService,
            ObjectMapper objectMapper
    ) {
        this.provider      = provider;
        this.toolRegistry  = toolRegistry;
        this.dispatcher    = dispatcher;
        this.storageService = storageService;
        this.objectMapper  = objectMapper;
    }

    public OrchestratorResult run(Job job) throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(buildUserMessage(job));
        return runLoop(job, messages, withSkills(job, SystemPrompts.MAIN));
    }

    public OrchestratorResult runEdit(Job job, String documentContext) throws Exception {
        List<Message> messages = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        content.append("EXISTING DOCUMENT CONTENT:\n").append(documentContext);
        content.append("\n\nEDIT INSTRUCTION: ").append(job.getRequest());
        content.append("\n\nOutput format: ").append(job.getFormat().generationFormat().name());
        messages.add(Message.user(content.toString()));
        return runLoop(job, messages, withSkills(job, SystemPrompts.EDIT));
    }

    /**
     * Edits a single small JSON fragment (one paragraph, one slide, one row) in isolation —
     * used for fast, surgical edits from the preview instead of regenerating the whole document.
     */
    public String runFragmentEdit(Job job, String fragmentJson, String instruction) {
        String userMsg = "CURRENT FRAGMENT (JSON):\n" + fragmentJson + "\n\nINSTRUCTION: " + instruction;
        Response response = billedRequest(job, new LlmClient.Request(SystemPrompts.FRAGMENT_EDIT,
                List.of(Message.user(userMsg)), List.of(), null, 2000));
        return extractJson(response.text() != null ? response.text() : fragmentJson);
    }

    /**
     * Design-director step: a single, content-free Claude call that chooses ONLY the visual and
     * narrative direction for a presentation (style, layout vocabulary, hero slides, bullet-ratio
     * budget). Its JSON is persisted on the job and then threaded into both runPlan and the final
     * generation call (see appendDesignDirection), so the whole pipeline commits to one direction
     * instead of each step improvising its own look.
     */
    public OrchestratorDesignResult runDesignDirection(Job job) {
        StringBuilder content = new StringBuilder(job.getRequest());
        if (job.getSourceText() != null && !job.getSourceText().isBlank()) {
            String snippet = job.getSourceText().substring(0, Math.min(job.getSourceText().length(), 4000));
            content.append("\n\n--- SOURCE CONTENT (for context only; do not write content from this) ---\n").append(snippet);
        }
        Response response = billedRequest(job, new LlmClient.Request(SystemPrompts.DESIGN_DIRECTOR,
                List.of(Message.user(content.toString())), List.of(toolRegistry.designDirectionTool()), "submit_design_direction", 1200));
        ToolCall direction = response.firstToolCall("submit_design_direction");
        if (direction == null) throw new IllegalStateException("Design direction çağrısı tamamlanmadı.");
        try {
            String json = objectMapper.writeValueAsString(direction.input());
            return new OrchestratorDesignResult(json, (int) response.inputTokens(), (int) response.outputTokens(),
                    computeCost(job, response.inputTokens(), response.outputTokens()));
        } catch (Exception e) {
            throw new IllegalStateException("Design direction could not be serialized", e);
        }
    }

    /** Renders the job's stored design direction (if any) into the given prompt as a binding block
     * — shared by runPlan (shapes the outline) and buildUserMessage (shapes the final script). */
    private void appendDesignDirection(StringBuilder content, Job job) {
        if (job.getDesignDirectionJson() == null || job.getDesignDirectionJson().isBlank()) return;
        try {
            var d = objectMapper.readTree(job.getDesignDirectionJson());
            content.append("\n\nDESIGN DIRECTION — generate strictly within this decision (set by an earlier design-director step; do not override it):\n");
            content.append("designDirection: ").append(d.path("designDirection").asText("")).append('\n');
            content.append("narrativeStyle: ").append(d.path("narrativeStyle").asText("")).append('\n');
            content.append("layoutStrategy: ").append(d.path("layoutStrategy").asText("")).append('\n');
            content.append("visualDensity: ").append(d.path("visualDensity").asText("")).append('\n');
            content.append("textDensity: ").append(d.path("textDensity").asText("")).append('\n');
            content.append("accentUsage: ").append(d.path("accentUsage").asText("")).append('\n');
            content.append("heroSlides: ").append(d.path("heroSlides").toString()).append('\n');
            content.append("preferredLayouts: ").append(d.path("preferredLayouts").toString()).append('\n');
            content.append("maxBulletSlideRatio: ").append(d.path("maxBulletSlideRatio").asText("0.35")).append('\n');
            content.append("reason: ").append(d.path("reason").asText("")).append('\n');
            content.append("\nDo not use the same slide pattern more than twice consecutively. Favor preferredLayouts above generic title+bullets.\n");
        } catch (Exception e) {
            log.warn("Job {} had unparsable designDirectionJson: {}", job.getId(), e.getMessage());
        }
    }

    /**
     * Yaver in-editor chat panel's edit call: given the currently selected text and an
     * instruction, returns both the rewritten text and any structured formatting change the
     * instruction asked for (bold/italic/underline/color/highlight/font) — see
     * SystemPrompts.DOCUMENT_AGENT and ToolRegistry.documentEditTool. Unlike runFragmentEdit
     * (shared with generic whole-document preview edits across all formats), this path is
     * specific to a live ONLYOFFICE selection and can express real visual formatting, which the
     * bridge plugin applies through ONLYOFFICE's internal document API.
     */
    public OrchestratorDocumentEditResult runDocumentEdit(Job job, String currentText, String instruction) {
        String userMsg = "CURRENT SELECTED TEXT:\n" + currentText + "\n\nINSTRUCTION: " + instruction;
        Response response = billedRequest(job, new LlmClient.Request(SystemPrompts.DOCUMENT_AGENT,
                List.of(Message.user(userMsg)), List.of(toolRegistry.documentEditTool()), "submit_document_edit", 2000));
        ToolCall edit = response.firstToolCall("submit_document_edit");
        if (edit == null) throw new IllegalStateException("Döküman düzenleme çağrısı tamamlanmadı.");
        try {
            String json = objectMapper.writeValueAsString(edit.input());
            return new OrchestratorDocumentEditResult(json, (int) response.inputTokens(), (int) response.outputTokens(),
                    computeCost(job, response.inputTokens(), response.outputTokens()));
        } catch (Exception e) {
            throw new IllegalStateException("Document edit could not be serialized", e);
        }
    }

    public OrchestratorPlanResult runPlan(Job job) {
        StringBuilder content = new StringBuilder(job.getRequest());
        if (job.getSourceText() != null && !job.getSourceText().isBlank()) {
            content.append("\n\n--- SOURCE CONTENT ---\n").append(job.getSourceText());
        }
        if (job.getFormat() != null) {
            content.append("\n\nOutput format: ").append(job.getFormat().name());
        }
        appendComputedFacts(content, job);
        appendDesignDirection(content, job);
        List<Message> messages = new ArrayList<>(List.of(Message.user(content.toString())));
        long inputTokens = 0, outputTokens = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            Response response = billedRequest(job, new LlmClient.Request(withPlanSkills(job, SystemPrompts.PLAN), List.copyOf(messages),
                    List.of(toolRegistry.planTool(job.getFormat().name())), "submit_plan", maxTokens));
            inputTokens += response.inputTokens(); outputTokens += response.outputTokens();
            ToolCall plan = response.firstToolCall("submit_plan");
            if (plan == null) throw new IllegalStateException("Model tamamlanmış bir plan döndürmedi; çıktı sınırı aşılmış olabilir.");
            try {
                var tree = plan.input().deepCopy();
                PlanQuality.prepare(tree, job);
                return new OrchestratorPlanResult(objectMapper.writeValueAsString(tree), (int)inputTokens, (int)outputTokens, computeCost(job, inputTokens, outputTokens));
            } catch (Exception e) {
                if (attempt == 1) throw new IllegalStateException("Plan is incomplete after correction: " + e.getMessage(), e);
                messages.add(Message.assistant(response));
                messages.add(Message.toolResult(plan, "ERROR: " + e.getMessage() + " Return the entire corrected plan, preserving the source and requested content."));
            }
        }
        throw new IllegalStateException("Plan could not be completed");
    }

    private String extractJson(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private OrchestratorResult runLoop(Job job, List<Message> messages, String systemPrompt) throws Exception {
        long totalInputTokens  = 0;
        long totalOutputTokens = 0;
        Path outputPath = storageService.resolveOutputPath(job.getId(), job.getFormat());
        // The last file that passed validation; a review-driven rewrite that fails falls back to it.
        Path approved = outputPath.resolveSibling("approved-" + outputPath.getFileName());
        boolean succeeded = false;
        String lastError = "No tool attempts were configured";
        int failures = 0;
        boolean lastFailed = false;
        int reviewsLeft = reviewable(job) ? reviewRounds : 0;

        for (int turn = 0; failures < maxToolTurns; turn++) {
            Response response = billedRequest(job, new LlmClient.Request(systemPrompt, List.copyOf(messages),
                    toolRegistry.allTools(), null, maxTokens));

            totalInputTokens  += response.inputTokens();
            totalOutputTokens += response.outputTokens();

            messages.add(Message.assistant(response));

            ToolCall toolUse = response.firstToolCall(null);
            if (toolUse == null) {
                if (succeeded) break; // a reviewed deck already exists; keep it rather than fail the job
                throw new IllegalStateException("Model did not call a tool: " + (response.text() == null ? "(no content)" : response.text()));
            }

            String inputJson = objectMapper.writeValueAsString(toolUse.input());

            log.info("Job {} turn {}: model called {}", job.getId(), turn, toolUse.name());

            String toolResult;
            try {
                toolResult = dispatcher.dispatch(toolUse.name(), inputJson, outputPath, imageEnvVars(job));
                com.officemanipulator.validator.GenerationRequirements.validate(job, outputPath);
                if (toolUse.name().equals("create_presentation"))
                    com.officemanipulator.validator.PresentationDesignValidator.validate(job, inputJson);
            } catch (Exception e) {
                toolResult = "ERROR: " + e.getMessage();
                log.warn("Job {} tool error on turn {}: {}", job.getId(), turn, e.getMessage());
            }

            messages.add(Message.toolResult(toolUse, toolResult));

            lastFailed = toolResult.startsWith("ERROR");
            if (lastFailed) { lastError = toolResult; failures++; continue; }
            succeeded = true;
            if (reviewsLeft-- <= 0) break;
            java.nio.file.Files.copy(outputPath, approved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long[] reviewUsage = new long[2];
            Message feedback = reviewSlides(job, outputPath, reviewUsage);
            // JobService rewrites the job's usage from this result, so review calls must be counted here.
            totalInputTokens += reviewUsage[0];
            totalOutputTokens += reviewUsage[1];
            if (feedback == null) break;
            messages.add(feedback);
        }

        if (!succeeded) throw new IllegalStateException("All generation attempts failed: " + lastError);
        if (java.nio.file.Files.exists(approved)) {
            // The last attempt may have been a failed rewrite that overwrote the file; ship the approved one.
            if (lastFailed || !java.nio.file.Files.exists(outputPath)) {
                java.nio.file.Files.move(approved, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            java.nio.file.Files.deleteIfExists(approved);
        }
        return new OrchestratorResult(
                outputPath,
                (int) totalInputTokens,
                (int) totalOutputTokens,
                computeCost(job, totalInputTokens, totalOutputTokens)
        );
    }

    /** Presentations get the design guides from presentation-skills/ appended to the system prompt. */
    private String withSkills(Job job, String prompt) {
        return skills != null && isPresentation(job) ? prompt + skills.generationGuide() : prompt;
    }

    private String withPlanSkills(Job job, String prompt) {
        return skills != null && isPresentation(job) ? prompt + skills.planGuide() : prompt;
    }

    private static boolean isPresentation(Job job) {
        return job.getFormat() != null && job.getFormat().generationFormat() == com.officemanipulator.domain.OutputFormat.PPTX;
    }

    private boolean reviewable(Job job) {
        return renderer != null && isPresentation(job);
    }

    /**
     * Visual review: renders the deck, has the model inspect the slide images against the plan, and
     * returns a correction message (slides + defects) for the generator, or null when the deck is
     * approved or the review itself is unavailable — a review problem never fails the job.
     */
    private Message reviewSlides(Job job, Path deck, long[] usage) {
        try {
            List<LlmClient.Image> slides = renderer.renderPptx(deck, MAX_REVIEW_SLIDES).stream()
                    .map(bytes -> new LlmClient.Image("image/jpeg", java.util.Base64.getEncoder().encodeToString(bytes))).toList();
            if (slides.isEmpty()) return null;
            StringBuilder context = new StringBuilder("REQUEST:\n").append(job.getRequest());
            if (job.getPlanJson() != null) context.append("\n\nAPPROVED PLAN:\n").append(job.getPlanJson());
            context.append("\n\nThe ").append(slides.size()).append(" images above are the rendered slides, in order.");
            String system = SystemPrompts.SLIDE_REVIEW + (skills == null ? "" : skills.reviewChecklist());
            Response review = billedRequest(job, new LlmClient.Request(system, List.of(Message.user(context.toString(), slides)),
                    List.of(toolRegistry.slideReviewTool()), "submit_slide_review", 4000));
            usage[0] += review.inputTokens();
            usage[1] += review.outputTokens();
            ToolCall verdict = review.firstToolCall("submit_slide_review");
            if (verdict == null) return null;
            var issues = new ArrayList<String>();
            for (var issue : verdict.input().path("issues")) {
                String severity = issue.path("severity").asText("low");
                if (severity.equals("low")) continue;
                issues.add("- Slide " + issue.path("slide").asInt() + " [" + severity + "]: " + issue.path("problem").asText()
                        + " → FIX: " + issue.path("fix").asText());
            }
            log.info("Job {} visual review: approved={}, blocking issues={}", job.getId(), verdict.input().path("approved").asBoolean(), issues.size());
            if (verdict.input().path("approved").asBoolean(false) && issues.isEmpty()) return null;
            if (issues.isEmpty()) return null;
            return Message.user("""
                    VISUAL REVIEW — the images above are how your previous script actually renders (image 1 = slide 1).
                    An art director found these defects:
                    %s

                    Call create_presentation again with the COMPLETE corrected script. Fix every listed defect, keep
                    everything that already works (content, order, palette, layouts, images), and keep the
                    // LAYOUT: comments.""".formatted(String.join("\n", issues)), slides);
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Üretim iptal edildi", e);
            log.warn("Job {} visual review skipped: {}", job.getId(), e.getMessage());
            return null;
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private Message buildUserMessage(Job job) {
        StringBuilder content = new StringBuilder(job.getRequest());
        if (job.getSourceText() != null && !job.getSourceText().isBlank()) {
            content.append("\n\n--- SOURCE CONTENT ---\n").append(job.getSourceText());
        }
        if (job.getFormat() != null) {
            content.append("\n\nOutput format: ").append(job.getFormat().generationFormat().name());
        }
        if (job.getPlanJson() != null) content.append("\n\nAPPROVED PLAN — follow this structure and content:\n").append(job.getPlanJson());
        appendComputedFacts(content, job);
        appendDesignDirection(content, job);
        appendImageInstructions(content, job);
        return Message.user(content.toString());
    }

    private void appendComputedFacts(StringBuilder content, Job job) {
        String facts = SourceTableFacts.summarize(job.getSourceText());
        if (!facts.isBlank()) content.append("\n\nCOMPUTED SOURCE TABLE SUMS (use these exact sums; do not confuse expenses with the budget):\n").append(facts);
    }

    @SuppressWarnings("unchecked")
    private void appendImageInstructions(StringBuilder content, Job job) {
        if (job.getImagePathsJson() == null || job.getImagePathsJson().isBlank()) return;
        try {
            Map<String, String> imagePaths = objectMapper.readValue(job.getImagePathsJson(), Map.class);
            if (imagePaths.isEmpty()) return;
            content.append("\n\nAVAILABLE GENERATED IMAGES (PNG files, already on disk — see the format-specific ")
                    .append("IMAGES instructions above for how to embed them):\n");
            imagePaths.forEach((index, path) ->
                    content.append("- Section/slide ").append(index)
                            .append(": env var IMG_").append(index).append("\n"));
        } catch (Exception e) {
            log.warn("Job {} had unparsable imagePathsJson: {}", job.getId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> imageEnvVars(Job job) {
        if (job.getImagePathsJson() == null || job.getImagePathsJson().isBlank()) return Map.of();
        try {
            Map<String, String> imagePaths = objectMapper.readValue(job.getImagePathsJson(), Map.class);
            Map<String, String> env = new java.util.HashMap<>();
            imagePaths.forEach((index, path) -> env.put("IMG_" + index, path));
            return env;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Response billedRequest(Job job, LlmClient.Request request) {
        LlmSelection selection = provider.selectionFor(job.getId());
        // A caller's own key bills the caller, not this server's budget.
        boolean billable = billing != null && !selection.requestKey();
        // UTF-8 bytes bound text token count; reserve extra space for message/tool framing.
        long images = request.messages().stream().mapToLong(m -> m.images().size()).sum();
        // Images print as short placeholders in toString(); budget ~1600 tokens each (a 960×540 slide is well under).
        long inputCeiling = request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 2L + images * 1600 + 8192;
        java.util.UUID reservation = billable ? billing.reserve(job.getId(), selection.provider().id(),
            computeCost(selection.model(), inputCeiling, request.maxTokens())) : null;
        Response response;
        try { response = provider.client(selection.provider()).chat(selection, request); }
        catch (RuntimeException e) { if (reservation != null) billing.uncertain(reservation); throw e; }
        long input = response.inputTokens(), output = response.outputTokens();
        BigDecimal cost = computeCost(selection.model(), input, output);
        if (reservation != null) billing.settle(reservation, cost, input, output);
        com.officemanipulator.service.JobUsage.snapshot(job).apply(job, (int) input, (int) output, cost);
        return response;
    }

    private BigDecimal computeCost(Job job, long inputTokens, long outputTokens) {
        return computeCost(provider.selectionFor(job.getId()).model(), inputTokens, outputTokens);
    }

    private BigDecimal computeCost(String activeModel, long inputTokens, long outputTokens) {
        boolean sameAsConfigured = activeModel != null && activeModel.equals(pricingModel);
        BigDecimal[] known = sameAsConfigured ? null : com.officemanipulator.config.ModelPricing.priceFor(activeModel);
        BigDecimal inputRate = known != null ? known[0] : inputPricePerMillion;
        BigDecimal outputRate = known != null ? known[1] : outputPricePerMillion;
        BigDecimal input  = inputRate
                .multiply(BigDecimal.valueOf(inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal output = outputRate
                .multiply(BigDecimal.valueOf(outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return input.add(output);
    }
}
