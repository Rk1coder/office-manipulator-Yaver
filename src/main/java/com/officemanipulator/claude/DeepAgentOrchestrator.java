package com.officemanipulator.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.dto.OrchestratorDesignResult;
import com.officemanipulator.claude.dto.OrchestratorDocumentEditResult;
import com.officemanipulator.claude.dto.OrchestratorPlanResult;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.config.ModelPricing;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.storage.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Experimental Deep Agents orchestrator (see experiment/deep-agents-migration). Calls the
 * standalone Python Deep Agents service (deep-agent-service/) over HTTP; the Python side
 * writes a docx-npm script and calls back into this app's AgentToolsController to actually
 * generate/validate the file, reusing the same WordGenerator sandbox the legacy path uses.
 * Only DOCX generation (run()) is implemented so far — a PoC of one capability, not a full
 * replacement. All other AgentOrchestrator methods remain unimplemented for this phase.
 */
@Service
public class DeepAgentOrchestrator implements AgentOrchestrator {

    private static final String NOT_IMPLEMENTED =
            "DeepAgentOrchestrator is a routing stub only - not yet implemented. "
                    + "Set app.agent.provider=legacy to use ClaudeOrchestrator.";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final FileStorageService storageService;
    private final ModelPricing pricing;

    public DeepAgentOrchestrator(
            ObjectMapper objectMapper,
            FileStorageService storageService,
            ModelPricing pricing,
            @Value("${app.agent.deep-agents.base-url}") String baseUrl
    ) {
        this.objectMapper = objectMapper;
        this.storageService = storageService;
        this.pricing = pricing;
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
        factory.setReadTimeout(Duration.ofMinutes(5));
        this.restClient = RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
    }

    @Override
    public OrchestratorResult run(Job job) throws Exception {
        if (job.getFormat() != OutputFormat.DOCX) {
            throw new UnsupportedOperationException(
                    "DeepAgentOrchestrator currently supports DOCX only (PoC scope). "
                            + "Set app.agent.provider=legacy for other formats.");
        }
        Map<String, Object> body = Map.of(
                "jobId", job.getId().toString(),
                "request", job.getRequest(),
                "sourceText", job.getSourceText() == null ? "" : job.getSourceText()
        );
        try {
            String response = restClient.post()
                    .uri("/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            if (!root.path("success").asBoolean(false)) {
                String message = root.path("message").asText();
                throw new IllegalStateException(
                        "Deep agent service failed [" + classifyFailure(message) + "]: " + message);
            }
            int inputTokens = root.path("inputTokens").asInt(0);
            int outputTokens = root.path("outputTokens").asInt(0);
            Path outputPath = storageService.resolveOutputPath(job.getId(), OutputFormat.DOCX);
            return new OrchestratorResult(outputPath, inputTokens, outputTokens, cost(inputTokens, outputTokens));
        } catch (Exception e) {
            throw new IllegalStateException("Deep agent orchestration failed for job " + job.getId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Coarse classification of a Python-side failure message, matched against the fixed
     * strings deep-agent-service/app/agent.py returns for each failure mode (timeout,
     * recursion-limit, no tool call, or a plain script error) — lets Phase 4's benchmark
     * tell these apart instead of lumping every failure into one bucket.
     */
    private static String classifyFailure(String message) {
        if (message == null) return "unknown";
        if (message.contains("exceeded the maximum of") && message.contains("turns")) return "max_retries_exceeded";
        if (message.contains("exceeded the") && message.contains("timeout")) return "timeout";
        if (message.contains("did not call create_word_document")) return "no_tool_call";
        return "script_error";
    }

    private BigDecimal cost(long inputTokens, long outputTokens) {
        return pricing.input().multiply(BigDecimal.valueOf(inputTokens))
                .add(pricing.output().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    @Override
    public OrchestratorResult runEdit(Job job, String documentContext) throws Exception {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public String runFragmentEdit(Job job, String fragmentJson, String instruction) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public OrchestratorDesignResult runDesignDirection(Job job) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public OrchestratorDocumentEditResult runDocumentEdit(Job job, String currentText, String instruction) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public OrchestratorPlanResult runPlan(Job job) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }
}
