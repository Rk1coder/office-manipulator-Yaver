package com.officemanipulator.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.JobRepository;
import com.officemanipulator.domain.JobStatus;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.validator.FileValidator;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deep Agents migration Phase 4: compares ClaudeOrchestrator (legacy) against
 * DeepAgentOrchestrator for DOCX generation, using the same plan-less run(Job) call on
 * both (DeepAgentOrchestrator only implements DOCX/run() so far). Produces a report under
 * evaluation/results/ for human review against the acceptance table in evaluation/README.md
 * -- this test does NOT assert pass/fail on the metrics themselves.
 *
 * Opt-in only: needs a disposable Postgres, a real ANTHROPIC_API_KEY, and the
 * deep-agent-service Python process already running (see evaluation/README.md).
 */
@EnabledIfEnvironmentVariable(named = "RUN_AGENT_BENCHMARK", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.datasource.url=${OFFICE_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=office-test-only",
        "app.storage.base-path=/tmp/office-agent-benchmark-files",
        // Fixed (not random) so deep-agent-service's JAVA_APP_BASE_URL can be set before this test starts.
        "server.port=18095"
})
class AgentBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(AgentBenchmarkTest.class);

    @Autowired ClaudeOrchestrator claudeOrchestrator;
    @Autowired DeepAgentOrchestrator deepAgentOrchestrator;
    @Autowired JobRepository jobRepository;
    @Autowired FileValidator fileValidator;
    @Autowired ObjectMapper objectMapper;
    @Autowired com.officemanipulator.config.ClaudeClientProvider claudeClientProvider;
    @Autowired com.officemanipulator.config.ClaudeKeyVerifier claudeKeyVerifier;

    /** ClaudeOrchestrator refuses to run against an unverified key (same gate the Settings
     * page's "Kontrol et" button satisfies in normal use) -- replicate that one-time,
     * low-cost verification call here so the benchmark's real key is usable. */
    @org.junit.jupiter.api.BeforeEach
    void verifyClaudeKey() {
        String key = claudeClientProvider.configuredKey();
        String model = claudeClientProvider.model();
        claudeKeyVerifier.verify(key, model);
        claudeClientProvider.activateVerified(key, model);
    }

    record TestCase(String id, String request) {}

    record BenchmarkResult(
            String caseId, String implementation, boolean success, long durationMs,
            int inputTokens, int outputTokens, BigDecimal cost, boolean structurallyValid,
            int paragraphCount, int tableCount, int textLength, String error
    ) {}

    @Test
    void compareLegacyAndDeepAgentsOnDocxCases() throws Exception {
        List<TestCase> cases = loadTestCases();
        List<BenchmarkResult> results = new ArrayList<>();

        for (TestCase testCase : cases) {
            results.add(runOne(testCase, "legacy", claudeOrchestrator::run));
            results.add(runOne(testCase, "deep-agents", deepAgentOrchestrator::run));
        }

        writeReport(results);
        printSummary(results);
    }

    private interface Runner {
        OrchestratorResult run(Job job) throws Exception;
    }

    private BenchmarkResult runOne(TestCase testCase, String implementation, Runner runner) {
        Job job = new Job();
        job.setRequest(testCase.request());
        job.setFormat(OutputFormat.DOCX);
        job.setStatus(JobStatus.PENDING);
        job = jobRepository.saveAndFlush(job);

        long start = System.currentTimeMillis();
        try {
            OrchestratorResult result = runner.run(job);
            long duration = System.currentTimeMillis() - start;

            boolean structurallyValid;
            try {
                fileValidator.validate(result.outputPath(), OutputFormat.DOCX);
                structurallyValid = true;
            } catch (FileValidator.ValidationException e) {
                structurallyValid = false;
            }

            int paragraphCount = 0, tableCount = 0, textLength = 0;
            try (var doc = new XWPFDocument(Files.newInputStream(result.outputPath()))) {
                tableCount = doc.getTables().size();
                StringBuilder text = new StringBuilder();
                for (XWPFParagraph p : doc.getParagraphs()) {
                    if (!p.getText().isBlank()) {
                        paragraphCount++;
                        text.append(p.getText());
                    }
                }
                textLength = text.length();
            } catch (Exception ignored) {
                // Leave structural signals at 0 if the file couldn't even be opened by POI.
            }

            return new BenchmarkResult(testCase.id(), implementation, true, duration,
                    result.inputTokens(), result.outputTokens(), result.cost(),
                    structurallyValid, paragraphCount, tableCount, textLength, null);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("Case {} / {} failed: {}", testCase.id(), implementation, e.getMessage());
            return new BenchmarkResult(testCase.id(), implementation, false, duration,
                    0, 0, BigDecimal.ZERO, false, 0, 0, 0, e.getMessage());
        }
    }

    private List<TestCase> loadTestCases() throws Exception {
        Path path = Paths.get("evaluation/test_cases/docx_cases.json");
        JsonNode root = objectMapper.readTree(path.toFile());
        List<TestCase> cases = new ArrayList<>();
        for (JsonNode node : root) {
            cases.add(new TestCase(node.path("id").asText(), node.path("request").asText()));
        }
        return cases;
    }

    private void writeReport(List<BenchmarkResult> results) throws Exception {
        Path dir = Paths.get("evaluation/results");
        Files.createDirectories(dir);
        Path out = dir.resolve(Instant.now().toString().replace(":", "-") + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), results);
        log.info("Benchmark report written to {}", out);
    }

    private void printSummary(List<BenchmarkResult> results) {
        for (String impl : List.of("legacy", "deep-agents")) {
            List<BenchmarkResult> subset = results.stream().filter(r -> r.implementation().equals(impl)).toList();
            long successCount = subset.stream().filter(BenchmarkResult::success).count();
            double avgDurationMs = subset.stream().filter(BenchmarkResult::success)
                    .mapToLong(BenchmarkResult::durationMs).average().orElse(0);
            BigDecimal totalCost = subset.stream().map(BenchmarkResult::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("[{}] success={}/{} avgDurationMs={} totalCost=${}",
                    impl, successCount, subset.size(), String.format("%.0f", avgDurationMs), totalCost);
        }
    }
}
