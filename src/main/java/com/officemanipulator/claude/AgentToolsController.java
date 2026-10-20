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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Internal HTTP surface for the experimental Deep Agents service (see
 * experiment/deep-agents-migration, DeepAgentOrchestrator). Not part of the
 * user-facing API — exposes the SAME generation tools the legacy
 * ClaudeOrchestrator/ToolDispatcher use, so the Deep Agents side reuses the
 * existing Node sandbox and validation instead of reimplementing DOCX generation.
 * Guarded by a shared-secret header; full hardening is a later migration phase.
 */
@RestController
@RequestMapping("/internal/agent-tools")
public class AgentToolsController {

    private final JobRepository jobRepository;
    private final FileStorageService storageService;
    private final WordGenerator wordGenerator;
    private final FileValidator fileValidator;
    private final String internalToken;

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

        Path outputPath = storageService.resolveOutputPath(job.getId(), OutputFormat.DOCX);
        try {
            Files.createDirectories(outputPath.getParent());
            CreateWordParams params = new CreateWordParams();
            params.setScript(request.script());
            wordGenerator.generate(params, outputPath, Map.of());
            fileValidator.validate(outputPath, OutputFormat.DOCX);
            return ResponseEntity.ok(new AgentToolCreateWordResponse(true, "Word document created successfully."));
        } catch (FileValidator.ValidationException e) {
            deleteQuietly(outputPath);
            return ResponseEntity.ok(new AgentToolCreateWordResponse(false, e.getMessage()));
        } catch (IOException | InterruptedException e) {
            deleteQuietly(outputPath);
            return ResponseEntity.ok(new AgentToolCreateWordResponse(false, e.getMessage()));
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
