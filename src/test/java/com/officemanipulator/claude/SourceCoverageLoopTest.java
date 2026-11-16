package com.officemanipulator.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.llm.LlmClient;
import com.officemanipulator.llm.LlmProvider;
import com.officemanipulator.llm.LlmSelection;
import com.officemanipulator.preview.PreviewService;
import com.officemanipulator.storage.FileStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SourceCoverageLoopTest {
    @TempDir Path tmp;
    private final ObjectMapper mapper = new ObjectMapper();

    private ClaudeOrchestrator orchestrator(VisualReviewLoopTest.ScriptedLlm llm, ToolDispatcher dispatcher, int maxToolTurns) throws Exception {
        var provider = mock(LlmClientProvider.class);
        when(provider.selectionFor(any())).thenReturn(new LlmSelection(LlmProvider.ANTHROPIC, "k", "test", false));
        when(provider.client(LlmProvider.ANTHROPIC)).thenReturn(llm);
        var ai = new ClaudeOrchestrator(provider, new ToolRegistry(), dispatcher, new FileStorageService(tmp.toString()), mapper);
        ReflectionTestUtils.setField(ai, "maxTokens", 1000L);
        ReflectionTestUtils.setField(ai, "maxToolTurns", maxToolTurns);
        ReflectionTestUtils.setField(ai, "previewService", new PreviewService());
        ReflectionTestUtils.setField(ai, "inputPricePerMillion", BigDecimal.ONE);
        ReflectionTestUtils.setField(ai, "outputPricePerMillion", BigDecimal.ONE);
        return ai;
    }

    private LlmClient.Response csvCall(String csv) throws Exception {
        return new LlmClient.Response(null, List.of(new LlmClient.ToolCall("id", "create_csv",
                mapper.readTree(mapper.writeValueAsString(java.util.Map.of("csv", csv))), null)), 10, 20);
    }

    /** Writes the tool call's "csv" argument as the output file; "FAIL" is a broken script. */
    private ToolDispatcher writingDispatcher() throws Exception {
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), any())).thenAnswer(i -> {
            String csv = mapper.readTree((String) i.getArgument(1)).path("csv").asText();
            if (csv.equals("FAIL")) throw new IllegalStateException("script crashed");
            Path out = i.getArgument(2);
            Files.createDirectories(out.getParent());
            Files.writeString(out, csv);
            return "ok";
        });
        return dispatcher;
    }

    private static Job sourcedJob() {
        Job job = new Job(); job.setId(UUID.randomUUID()); job.setRequest("Tablo yap"); job.setFormat(OutputFormat.CSV);
        job.setSourceText("=== BELGE 1: a.docx ===\nKira 40.000 TL\n=== BELGE 2: b.docx ===\nPersonel 90.000 TL");
        return job;
    }

    @Test void missingSourceFiguresGetOneRewriteNamingThem() throws Exception {
        var llm = new VisualReviewLoopTest.ScriptedLlm();
        llm.answers.add(csvCall("Kalem,Tutar\nKira,40000"));
        llm.answers.add(csvCall("Kalem,Tutar\nKira,40000\nPersonel,90000"));

        var result = orchestrator(llm, writingDispatcher(), 3).run(sourcedJob());

        assertEquals(2, llm.requests.size());
        var feedback = llm.requests.get(1).messages().getLast().text();
        assertTrue(feedback.contains("90.000") && !feedback.contains("40.000"), feedback);
        assertTrue(Files.readString(result.outputPath()).contains("Personel"));
        assertEquals(20, result.inputTokens());
    }

    @Test void aFailedCoverageRewriteShipsThePreviousVersion() throws Exception {
        var llm = new VisualReviewLoopTest.ScriptedLlm();
        llm.answers.add(csvCall("Kalem,Tutar\nKira,40000"));
        llm.answers.add(csvCall("FAIL"));

        var result = orchestrator(llm, writingDispatcher(), 1).run(sourcedJob());

        assertEquals("Kalem,Tutar\nKira,40000", Files.readString(result.outputPath()));
        assertFalse(Files.exists(result.outputPath().resolveSibling("covered-" + result.outputPath().getFileName())));
    }

    @Test void aRewriteThatLosesMoreFiguresIsDiscarded() throws Exception {
        var llm = new VisualReviewLoopTest.ScriptedLlm();
        llm.answers.add(csvCall("Kalem,Tutar\nKira,40000"));
        // Seen with qwen2.5:7b: the feedback text itself written as the CSV.
        llm.answers.add(csvCall("Source Coverage Check\nThese figures do not appear: 90.000"));

        var result = orchestrator(llm, writingDispatcher(), 3).run(sourcedJob());

        assertEquals("Kalem,Tutar\nKira,40000", Files.readString(result.outputPath()));
    }

    @Test void aRewriteThatFixesSomeButDropsOthersIsDiscarded() throws Exception {
        var llm = new VisualReviewLoopTest.ScriptedLlm();
        var job = sourcedJob();
        job.setSourceText(job.getSourceText() + "\nCiro 186000 ve 197250");
        llm.answers.add(csvCall("Kalem,Tutar\nKira,40000\nPersonel,90000"));
        // Seen with qwen2.5:7b: adds the missing figures but loses ones the first version had.
        llm.answers.add(csvCall("Bölge,Hedef\nA,186000\nB,197250"));

        var result = orchestrator(llm, writingDispatcher(), 3).run(job);

        assertEquals("Kalem,Tutar\nKira,40000\nPersonel,90000", Files.readString(result.outputPath()));
    }

    @Test void completeOutputOrNoSourceNeedsNoRewrite() throws Exception {
        var llm = new VisualReviewLoopTest.ScriptedLlm();
        llm.answers.add(csvCall("Kalem,Tutar\nKira,40.000\nPersonel,90.000"));
        orchestrator(llm, writingDispatcher(), 3).run(sourcedJob());
        assertEquals(1, llm.requests.size());
    }
}
