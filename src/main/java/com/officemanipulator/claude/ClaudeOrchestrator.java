package com.officemanipulator.claude;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.dto.OrchestratorDesignResult;
import com.officemanipulator.claude.dto.OrchestratorDocumentEditResult;
import com.officemanipulator.claude.dto.OrchestratorPlanResult;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.domain.Job;
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
 * Drives the Claude tool-use loop: sends the user request, receives a tool call,
 * delegates execution to ToolDispatcher, then feeds the result back to Claude.
 * System prompt lives in SystemPrompts; tool routing lives in ToolDispatcher.
 */
@Service
public class ClaudeOrchestrator implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeOrchestrator.class);

    @org.springframework.beans.factory.annotation.Autowired
    private com.officemanipulator.service.ApiBillingService billing;
    private final com.officemanipulator.config.ClaudeClientProvider provider;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher dispatcher;
    private final FileStorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${app.claude.max-tokens}")
    private long maxTokens;
    @Value("${app.claude.max-tool-turns}")
    private int maxToolTurns;
    private String pricingModel;
    private BigDecimal inputPricePerMillion;
    private BigDecimal outputPricePerMillion;

    @org.springframework.beans.factory.annotation.Autowired
    void configurePrices(com.officemanipulator.config.ModelPricing pricing) {
        pricingModel = pricing.model();
        inputPricePerMillion = pricing.input(); outputPricePerMillion = pricing.output();
    }

    public ClaudeOrchestrator(
            com.officemanipulator.config.ClaudeClientProvider provider,
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
        List<MessageParam> messages = new ArrayList<>();
        messages.add(buildUserMessage(job));
        return runLoop(job, messages, SystemPrompts.MAIN);
    }

    public OrchestratorResult runEdit(Job job, String documentContext) throws Exception {
        List<MessageParam> messages = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        content.append("EXISTING DOCUMENT CONTENT:\n").append(documentContext);
        content.append("\n\nEDIT INSTRUCTION: ").append(job.getRequest());
        content.append("\n\nOutput format: ").append(job.getFormat().generationFormat().name());
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(content.toString())
                .build());
        return runLoop(job, messages, SystemPrompts.EDIT);
    }

    /**
     * Edits a single small JSON fragment (one paragraph, one slide, one row) in isolation —
     * used for fast, surgical edits from the preview instead of regenerating the whole document.
     */
    public String runFragmentEdit(Job job, String fragmentJson, String instruction) {
        String userMsg = "CURRENT FRAGMENT (JSON):\n" + fragmentJson + "\n\nINSTRUCTION: " + instruction;
        MessageParam userMessage = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userMsg)
                .build();

        MessageCreateParams params = MessageCreateParams.builder()
                .model(provider.model())
                .maxTokens(2000)
                .system(SystemPrompts.FRAGMENT_EDIT)
                .messages(List.of(userMessage))
                .build();

        Message response = billedRequest(job, params);

        String text = response.content().stream()
                .flatMap(b -> b.text().stream())
                .map(TextBlock::text)
                .findFirst()
                .orElse(fragmentJson);

        return extractJson(text);
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
        MessageParam userMessage = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(content.toString())
                .build();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(provider.model()).maxTokens(1200).system(SystemPrompts.DESIGN_DIRECTOR)
                .addTool(toolRegistry.designDirectionTool())
                .toolChoice(ToolChoiceTool.builder().name("submit_design_direction").disableParallelToolUse(true).build())
                .messages(List.of(userMessage)).build();
        Message response = billedRequest(job, params);
        ToolUseBlock direction = response.content().stream().flatMap(block -> block.toolUse().stream())
                .filter(tool -> tool.name().equals("submit_design_direction")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude design direction çağrısı tamamlanmadı."));
        try {
            String json = objectMapper.writeValueAsString(direction._input());
            return new OrchestratorDesignResult(json, (int) response.usage().inputTokens(), (int) response.usage().outputTokens(),
                    computeCost(response.usage().inputTokens(), response.usage().outputTokens()));
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
        MessageParam userMessage = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userMsg)
                .build();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(provider.model()).maxTokens(2000).system(SystemPrompts.DOCUMENT_AGENT)
                .addTool(toolRegistry.documentEditTool())
                .toolChoice(ToolChoiceTool.builder().name("submit_document_edit").disableParallelToolUse(true).build())
                .messages(List.of(userMessage)).build();
        Message response = billedRequest(job, params);
        ToolUseBlock edit = response.content().stream().flatMap(block -> block.toolUse().stream())
                .filter(tool -> tool.name().equals("submit_document_edit")).findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude döküman düzenleme çağrısı tamamlanmadı."));
        try {
            String json = objectMapper.writeValueAsString(edit._input());
            return new OrchestratorDocumentEditResult(json, (int) response.usage().inputTokens(), (int) response.usage().outputTokens(),
                    computeCost(response.usage().inputTokens(), response.usage().outputTokens()));
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
        MessageParam userMessage = MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(content.toString())
                .build();

        List<MessageParam> messages = new ArrayList<>(List.of(userMessage));
        long inputTokens = 0, outputTokens = 0;
        for (int attempt = 0; attempt < 2; attempt++) {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(provider.model()).maxTokens(maxTokens).system(SystemPrompts.PLAN)
                    .addTool(toolRegistry.planTool(job.getFormat().name()))
                    .toolChoice(ToolChoiceTool.builder().name("submit_plan").disableParallelToolUse(true).build())
                    .messages(messages).build();
            Message response = billedRequest(job, params);
            inputTokens += response.usage().inputTokens(); outputTokens += response.usage().outputTokens();
            ToolUseBlock plan = response.content().stream().flatMap(block -> block.toolUse().stream())
                    .filter(tool -> tool.name().equals("submit_plan")).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Claude tamamlanmış bir plan döndürmedi; çıktı sınırı aşılmış olabilir."));
            try {
                var tree = objectMapper.readTree(objectMapper.writeValueAsString(plan._input()));
                PlanQuality.prepare(tree, job);
                return new OrchestratorPlanResult(objectMapper.writeValueAsString(tree), (int)inputTokens, (int)outputTokens, computeCost(inputTokens, outputTokens));
            } catch (Exception e) {
                if (attempt == 1) throw new IllegalStateException("Plan is incomplete after correction: " + e.getMessage(), e);
                messages.add(toAssistantParam(response));
                messages.add(toToolResultParam(plan.id(), "ERROR: " + e.getMessage() + " Return the entire corrected plan, preserving the source and requested content."));
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

    private OrchestratorResult runLoop(Job job, List<MessageParam> messages, String systemPrompt) throws Exception {
        long totalInputTokens  = 0;
        long totalOutputTokens = 0;
        Path outputPath = null;
        boolean succeeded = false;
        String lastError = "No tool attempts were configured";

        for (int turn = 0; turn < maxToolTurns; turn++) {
            Message response = sendRequest(job, messages, systemPrompt);

            totalInputTokens  += response.usage().inputTokens();
            totalOutputTokens += response.usage().outputTokens();

            messages.add(toAssistantParam(response));

            var toolUseOpt = response.content().stream()
                    .filter(b -> b.toolUse().isPresent())
                    .findFirst();

            if (toolUseOpt.isEmpty()) {
                String text = response.content().stream()
                        .flatMap(b -> b.text().stream())
                        .map(TextBlock::text)
                        .findFirst().orElse("(no content)");
                throw new IllegalStateException("Claude did not call a tool: " + text);
            }

            ToolUseBlock toolUse = toolUseOpt.get().toolUse().get();
            String inputJson = objectMapper.writeValueAsString(toolUse._input());

            log.info("Job {} turn {}: Claude called {}", job.getId(), turn, toolUse.name());

            outputPath = storageService.resolveOutputPath(job.getId(), job.getFormat());

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

            messages.add(toToolResultParam(toolUse.id(), toolResult));

            if (!toolResult.startsWith("ERROR")) { succeeded = true; break; }
            lastError = toolResult;
        }

        if (!succeeded) throw new IllegalStateException("All generation attempts failed: " + lastError);
        return new OrchestratorResult(
                outputPath,
                (int) totalInputTokens,
                (int) totalOutputTokens,
                computeCost(totalInputTokens, totalOutputTokens)
        );
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private MessageParam buildUserMessage(Job job) {
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
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(content.toString())
                .build();
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

    private Message sendRequest(Job job, List<MessageParam> messages, String systemPrompt) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(provider.model())
                .maxTokens(maxTokens)
                .system(systemPrompt)
                .messages(messages)
                .toolChoice(ToolChoiceAuto.builder().build());

        toolRegistry.allTools().forEach(builder::addTool);
        return billedRequest(job, builder.build());
    }

    private Message billedRequest(Job job, MessageCreateParams params) {
        // UTF-8 bytes bound text token count; reserve extra space for message/tool framing.
        long inputCeiling = params.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 2L + 8192;
        java.util.UUID reservation = billing == null ? null : billing.reserve(job.getId(), "anthropic",
            computeCost(inputCeiling, params.maxTokens()));
        Message response;
        try {
            if (params.maxTokens() > 8192) {
                var accumulator = com.anthropic.helpers.MessageAccumulator.create();
                try (var stream = provider.client().messages().createStreaming(params)) {
                    stream.stream().forEach(event -> {
                        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Üretim iptal edildi");
                        accumulator.accumulate(event);
                    });
                    response = accumulator.message();
                }
            } else response = provider.client().messages().create(params);
        }
        catch (RuntimeException e) { if (reservation != null) billing.uncertain(reservation); throw e; }
        long input = response.usage().inputTokens(), output = response.usage().outputTokens();
        BigDecimal cost = computeCost(input, output);
        if (reservation != null) billing.settle(reservation, cost, input, output);
        com.officemanipulator.service.JobUsage.snapshot(job).apply(job, (int) input, (int) output, cost);
        return response;
    }

    private MessageParam toAssistantParam(Message response) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t ->
                    blocks.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(t.text()).build())));
            block.toolUse().ifPresent(tu -> {
                Map<String, JsonValue> inputMap = tu._input()
                        .convert(new TypeReference<>() {});
                blocks.add(ContentBlockParam.ofToolUse(
                        ToolUseBlockParam.builder()
                                .id(tu.id())
                                .name(tu.name())
                                .input(ToolUseBlockParam.Input.builder()
                                        .additionalProperties(inputMap != null ? inputMap : Map.of())
                                        .build())
                                .build()));
            });
        }
        return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }

    private MessageParam toToolResultParam(String toolUseId, String content) {
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(List.of(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUseId)
                                .content(content)
                                .build())))
                .build();
    }

    private BigDecimal computeCost(long inputTokens, long outputTokens) {
        String activeModel = provider.model();
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
