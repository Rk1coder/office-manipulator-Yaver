package com.officemanipulator.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.claude.dto.OrchestratorResult;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.config.ModelPricing;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.storage.FileStorageService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeepAgentDelegationTest {
    @Test void stepsThePythonServiceLacksRunOnTheLegacyOrchestrator() throws Exception {
        var legacy = mock(ClaudeOrchestrator.class);
        var deep = new DeepAgentOrchestrator(new ObjectMapper(), mock(FileStorageService.class), mock(ModelPricing.class),
                mock(LlmClientProvider.class), legacy, "http://127.0.0.1:1");
        Job pptx = new Job(); pptx.setFormat(OutputFormat.PPTX);
        var made = new OrchestratorResult(Path.of("x.pptx"), 1, 2, java.math.BigDecimal.ZERO);
        when(legacy.run(pptx)).thenReturn(made);

        assertSame(made, deep.run(pptx));
        deep.runPlan(pptx);
        deep.runTemplateEdit(pptx, "[blocks/0/text] a");
        deep.runEdit(pptx, "ctx");
        verify(legacy).runPlan(pptx);
        verify(legacy).runTemplateEdit(pptx, "[blocks/0/text] a");
        verify(legacy).runEdit(pptx, "ctx");
    }
}
