package com.officemanipulator.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.llm.LlmClient;
import com.officemanipulator.llm.LlmProvider;
import com.officemanipulator.llm.LlmSelection;
import com.officemanipulator.office.SlideRenderer;
import com.officemanipulator.storage.FileStorageService;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisualReviewLoopTest {
    @TempDir Path tmp;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Answers chat calls from a fixed script and records every request. */
    static class ScriptedLlm implements LlmClient {
        final Deque<Response> answers = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();
        public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
        public void verify(String apiKey, String model) {}
        public Response chat(LlmSelection selection, Request request) { requests.add(request); return answers.removeFirst(); }
    }

    private LlmClient.Response call(String tool, String json) throws Exception {
        return new LlmClient.Response(null, List.of(new LlmClient.ToolCall("id-" + tool, tool, mapper.readTree(json), null)), 10, 20);
    }

    private static void writeDeck(Path path, int slides) throws Exception {
        Files.createDirectories(path.getParent());
        try (var deck = new XMLSlideShow(); OutputStream out = Files.newOutputStream(path)) {
            for (int i = 0; i < slides; i++) deck.createSlide();
            deck.write(out);
        }
    }

    private ClaudeOrchestrator orchestrator(ScriptedLlm llm, ToolDispatcher dispatcher, int maxToolTurns) throws Exception {
        var provider = mock(LlmClientProvider.class);
        when(provider.selectionFor(any())).thenReturn(new LlmSelection(LlmProvider.ANTHROPIC, "k", "test", false));
        when(provider.client(LlmProvider.ANTHROPIC)).thenReturn(llm);
        var renderer = mock(SlideRenderer.class);
        when(renderer.renderPptx(any(), anyInt())).thenReturn(List.of(new byte[]{1, 2, 3}, new byte[]{4, 5, 6}));
        var ai = new ClaudeOrchestrator(provider, new ToolRegistry(), dispatcher, new FileStorageService(tmp.toString()), mapper);
        ReflectionTestUtils.setField(ai, "maxTokens", 1000L);
        ReflectionTestUtils.setField(ai, "maxToolTurns", maxToolTurns);
        ReflectionTestUtils.setField(ai, "reviewRounds", 2);
        ReflectionTestUtils.setField(ai, "renderer", renderer);
        ReflectionTestUtils.setField(ai, "skills", new PresentationSkills());
        ReflectionTestUtils.setField(ai, "inputPricePerMillion", BigDecimal.ONE);
        ReflectionTestUtils.setField(ai, "outputPricePerMillion", BigDecimal.ONE);
        return ai;
    }

    private static Job deckJob() {
        Job job = new Job(); job.setId(UUID.randomUUID()); job.setRequest("Karıncalar hakkında sunum"); job.setFormat(OutputFormat.PPTX);
        return job;
    }

    @Test void blockingDefectsTriggerARewriteThatSeesTheRenderedSlides() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(call("create_presentation", "{\"script\":\"v1\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":false,\"summary\":\"s\",\"issues\":[{\"slide\":2,\"severity\":\"high\",\"problem\":\"body text overflows the card\",\"fix\":\"shrink to 14pt\"},{\"slide\":1,\"severity\":\"low\",\"problem\":\"nitpick\",\"fix\":\"x\"}]}"));
        llm.answers.add(call("create_presentation", "{\"script\":\"v2\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap())).thenAnswer(a -> { writeDeck(a.getArgument(2), 3); return "OK"; });

        var result = orchestrator(llm, dispatcher, 2).run(deckJob());

        verify(dispatcher, times(2)).dispatch(eq("create_presentation"), anyString(), any(), anyMap());
        assertTrue(llm.requests.get(0).system().contains("DESIGN GUIDE"), "generation gets the presentation guides");
        assertEquals("submit_slide_review", llm.requests.get(1).forcedTool());
        assertEquals(2, llm.requests.get(1).messages().getFirst().images().size(), "reviewer sees every rendered slide");
        assertTrue(llm.requests.get(1).system().contains("Visual review checklist"));
        var feedback = llm.requests.get(2).messages().getLast();
        assertEquals(2, feedback.images().size(), "the rewrite sees the slides too");
        assertTrue(feedback.text().contains("body text overflows the card"));
        assertFalse(feedback.text().contains("nitpick"), "low-severity polish does not force a rewrite");
        assertTrue(Files.exists(result.outputPath()));
        assertFalse(Files.exists(result.outputPath().resolveSibling("approved-output.pptx")));
        assertEquals(4 * 10, result.inputTokens());
    }

    @Test void aBrokenRewriteFallsBackToTheApprovedDeck() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(call("create_presentation", "{\"script\":\"v1\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":false,\"summary\":\"s\",\"issues\":[{\"slide\":1,\"severity\":\"medium\",\"problem\":\"cramped\",\"fix\":\"space\"}]}"));
        llm.answers.add(call("create_presentation", "{\"script\":\"v2\"}"));
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap()))
                .thenAnswer(a -> { writeDeck(a.getArgument(2), 3); return "OK"; })
                .thenAnswer(a -> { Files.writeString(a.getArgument(2), "broken"); throw new IllegalStateException("script crashed"); });

        var result = orchestrator(llm, dispatcher, 1).run(deckJob());

        try (var deck = new XMLSlideShow(Files.newInputStream(result.outputPath()))) {
            assertEquals(3, deck.getSlides().size(), "the deck that passed validation is delivered");
        }
    }
}
