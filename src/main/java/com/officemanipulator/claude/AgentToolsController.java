package com.officemanipulator.claude;

import com.officemanipulator.claude.dto.AgentToolCreateWordRequest;
import com.officemanipulator.claude.dto.AgentToolCreateWordResponse;
import com.officemanipulator.claude.dto.CreateWordParams;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.JobRepository;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.generator.WordGenerator;
import com.officemanipulator.storage.FileStorageService;
import com.officemanipulator.validator.FileValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Internal HTTP surface for the experimental Deep Agents service (see
 * experiment/deep-agents-migration, DeepAgentOrchestrator). Not part of the
 * user-facing API — exposes the SAME generation tools the legacy
 * ClaudeOrchestrator/ToolDispatcher use, so the Deep Agents side reuses the
 * existing Node sandbox and validation instead of reimplementing DOCX generation.
 * Guarded by a shared-secret header. Every call is audit-logged; a per-job call
 * cap backstops the Python-side LangGraph recursion limit independently, in case
 * that side is ever misconfigured.
 */
@RestController
@RequestMapping("/internal/agent-tools")
public class AgentToolsController {

    private static final Logger log = LoggerFactory.getLogger(AgentToolsController.class);

    /** Independent backstop behind the Deep Agent's own LangGraph recursion_limit
     * (deep-agent-service/app/agent.py) — a second, unrelated enforcement point in
     * case that side is ever misconfigured or bypassed. Not meant to be the primary
     * turn-count control. */
    private static final int MAX_CALLS_PER_JOB = 15;

    private final JobRepository jobRepository;
    private final FileStorageService storageService;
    private final WordGenerator wordGenerator;
    private final FileValidator fileValidator;
    private final String internalToken;
    private final ConcurrentHashMap<UUID, AtomicInteger> callsPerJob = new ConcurrentHashMap<>();

    public AgentToolsController(
            JobRepository jobRepository,
            FileStorageService storageService,
            WordGenerator wordGenerator,
            FileValidator fileValidator,
            @Value("${app.agent.deep-agents.internal-token:}") String internalToken
    ) {
        this.jobRepository = jobRepository;
        this.storageService = storageService;
        this.wordGenerator = wordGenerator;
        this.fileValidator = fileValidator;
        this.internalToken = internalToken;
    }

    @PostMapping("/create-word")
    public ResponseEntity<AgentToolCreateWordResponse> createWord(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody AgentToolCreateWordRequest request
    ) {
        if (internalToken.isBlank() || !internalToken.equals(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Job job = jobRepository.findById(request.jobId()).orElse(null);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        int callNumber = callsPerJob.computeIfAbsent(job.getId(), id -> new AtomicInteger()).incrementAndGet();
        if (callNumber > MAX_CALLS_PER_JOB) {
            log.warn("Job {} exceeded {} create-word calls; rejecting.", job.getId(), MAX_CALLS_PER_JOB);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        String scriptHash = sha256(request.script());
        long startedAt = System.currentTimeMillis();
        Path outputPath = storageService.resolveOutputPath(job.getId(), OutputFormat.DOCX);
        try {
            Files.createDirectories(outputPath.getParent());
            CreateWordParams params = new CreateWordParams();
            params.setScript(request.script());
            wordGenerator.generate(params, outputPath, Map.of());
            fileValidator.validate(outputPath, OutputFormat.DOCX);
            log.info("Job {} create-word call #{} succeeded (script sha256={}, {} chars, {} ms).",
                    job.getId(), callNumber, scriptHash, request.script().length(), System.currentTimeMillis() - startedAt);
            return ResponseEntity.ok(new AgentToolCreateWordResponse(true, "Word document created successfully."));
        } catch (FileValidator.ValidationException e) {
            deleteQuietly(outputPath);
            log.info("Job {} create-word call #{} failed validation (script sha256={}, {} chars): {}",
                    job.getId(), callNumber, scriptHash, request.script().length(), e.getMessage());
            return ResponseEntity.ok(new AgentToolCreateWordResponse(false, e.getMessage()));
        } catch (IOException | InterruptedException e) {
            deleteQuietly(outputPath);
            log.info("Job {} create-word call #{} failed to execute (script sha256={}, {} chars): {}",
                    job.getId(), callNumber, scriptHash, request.script().length(), e.getMessage());
            return ResponseEntity.ok(new AgentToolCreateWordResponse(false, e.getMessage()));
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
