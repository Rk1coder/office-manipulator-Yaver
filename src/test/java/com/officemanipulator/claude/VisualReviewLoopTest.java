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
        final List<String> models = new ArrayList<>();
        public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
        public void verify(String apiKey, String model) {}
        public Response chat(LlmSelection selection, Request request) { requests.add(request); models.add(selection.model()); return answers.removeFirst(); }
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
        when(renderer.canRender(any())).thenReturn(true);
        when(renderer.render(any(), any(), anyInt())).thenReturn(List.of(new byte[]{1, 2, 3}, new byte[]{4, 5, 6}));
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
        assertFalse(Files.exists(result.outputPath().resolveSibling("best-output.pptx")));
        assertEquals(4 * 10, result.inputTokens());
    }

    @Test void pdfPagesAreReviewedAsADocumentAndTheRewriteUsesCreatePdf() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(call("create_pdf", "{\"script\":\"v1\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":false,\"summary\":\"s\",\"issues\":[{\"slide\":2,\"severity\":\"high\",\"problem\":\"table runs past the margin\",\"fix\":\"narrow it\"}]}"));
        llm.answers.add(call("create_pdf", "{\"script\":\"v2\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap())).thenAnswer(a -> { Files.createDirectories(((Path) a.getArgument(2)).getParent()); Files.writeString(a.getArgument(2), "%PDF"); return "OK"; });
        Job job = deckJob(); job.setFormat(OutputFormat.PDF);

        orchestrator(llm, dispatcher, 2).run(job);

        assertTrue(llm.requests.get(1).system().contains("document designer"), "documents get the document review prompt");
        assertFalse(llm.requests.get(1).system().contains("Visual review checklist"), "no slide checklist for documents");
        String feedback = llm.requests.get(2).messages().getLast().text();
        assertTrue(feedback.contains("- Page 2 [high]: table runs past the margin"));
        assertTrue(feedback.contains("Call create_pdf again"));
        assertFalse(feedback.contains("LAYOUT"));
    }

    @Test void offPaletteColorsGetOneRewriteRequest() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(call("create_presentation", "{\"script\":\"slide.addText('a', { color: '2E7D32' })\"}"));
        llm.answers.add(call("create_presentation", "{\"script\":\"slide.addText('a', { color: '1F4E79' })\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap())).thenAnswer(a -> { writeDeck(a.getArgument(2), 1); return "OK"; });
        var ai = orchestrator(llm, dispatcher, 2);
        var brand = mock(com.officemanipulator.config.BrandThemeService.class);
        when(brand.current()).thenReturn(new com.officemanipulator.config.BrandThemeService.BrandTheme("1F4E79", null, null, "Montserrat", null, null));
        ReflectionTestUtils.setField(ai, "brand", brand);

        ai.run(deckJob());

        assertTrue(llm.requests.get(0).messages().getFirst().text().contains("CORPORATE THEME"), "the theme is in the generation prompt");
        assertTrue(llm.requests.get(0).messages().getFirst().text().contains("Heading font: Montserrat"));
        String feedback = llm.requests.get(1).messages().getLast().text();
        assertTrue(feedback.contains("outside the corporate palette: 2E7D32"));
        assertEquals("submit_slide_review", llm.requests.get(2).forcedTool(), "the on-palette rewrite goes on to review");
    }

    @Test void aPlanAnswerWithoutTheToolCallGetsOneReminder() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(new LlmClient.Response("İşte plan: ...", List.of(), 5, 5));
        llm.answers.add(call("submit_plan", "{\"title\":\"Siber güvenlik\",\"outline\":[{\"title\":\"Özet\",\"content\":\"Metin\"}]}"));

        var plan = orchestrator(llm, mock(ToolDispatcher.class), 2).runPlan(deckJob());

        assertEquals("Siber güvenlik", mapper.readTree(plan.planJson()).path("title").asText());
        assertTrue(llm.requests.get(1).messages().getLast().text().contains("submit_plan"));
        assertEquals(15, plan.inputTokens(), "both calls are billed");
    }

    @Test void aSeparateReviewModelInspectsTheSlidesAndTheGeneratorGetsTextOnly() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(call("create_presentation", "{\"script\":\"v1\"}"));
        llm.answers.add(new LlmClient.Response(null, List.of(new LlmClient.ToolCall("r", "submit_slide_review", mapper.readTree(
                "{\"approved\":false,\"summary\":\"s\",\"issues\":[{\"slide\":1,\"severity\":\"high\",\"problem\":\"overflow\",\"fix\":\"shrink\"}]}"), null)), 1_000_000, 0));
        llm.answers.add(call("create_presentation", "{\"script\":\"v2\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap())).thenAnswer(a -> { writeDeck(a.getArgument(2), 3); return "OK"; });
        var ai = orchestrator(llm, dispatcher, 2);
        ReflectionTestUtils.setField(ai, "reviewModel", "google/gemma-4-31b-it");
        ReflectionTestUtils.setField(ai, "reviewInputPrice", new BigDecimal("0.20"));
        Job job = deckJob();

        ai.run(job);

        assertEquals(List.of("test", "google/gemma-4-31b-it", "test", "google/gemma-4-31b-it"), llm.models);
        assertEquals(2, llm.requests.get(1).messages().getFirst().images().size(), "the review model sees the slides");
        var feedback = llm.requests.get(2).messages().getLast();
        assertTrue(feedback.images().isEmpty(), "a text-only generator must not get images");
        assertTrue(feedback.text().contains("overflow"));
        // 1M review input tokens at the review model's $0.20, not the generator's $1.
        assertTrue(job.getCostUsd().compareTo(new BigDecimal("0.5")) < 0, job.getCostUsd().toString());
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

    @Test void aPlainTextScriptIsRetriedWithTheFormatsToolForcedInsteadOfFailingTheJob() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(new LlmClient.Response("```javascript\nconst pptxgen = require('pptxgenjs');\n```", List.of(), 10, 20));
        llm.answers.add(call("create_presentation", "{\"script\":\"v1\"}"));
        llm.answers.add(call("submit_slide_review", "{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap())).thenAnswer(a -> { writeDeck(a.getArgument(2), 2); return "OK"; });

        orchestrator(llm, dispatcher, 2).run(deckJob());

        var first = llm.requests.get(0);
        assertEquals("create_presentation", first.forcedTool(), "the format's tool is forced");
        assertEquals(List.of("create_presentation"), first.tools().stream().map(LlmClient.Tool::name).toList(), "only that tool is offered");
        assertTrue(llm.requests.get(1).messages().getLast().text().contains("Do not write the script as plain text"));
        verify(dispatcher).dispatch(eq("create_presentation"), anyString(), any(), anyMap());
    }

    @Test void repeatedPlainTextAnswersFailWithAShortError() throws Exception {
        var llm = new ScriptedLlm();
        for (int i = 0; i < 2; i++) llm.answers.add(new LlmClient.Response("x".repeat(5000), List.of(), 10, 20));
        var error = assertThrows(IllegalStateException.class, () -> orchestrator(llm, mock(ToolDispatcher.class), 2).run(deckJob()));
        assertTrue(error.getMessage().length() < 400, "the user-facing error must not dump the whole script");
    }

    @Test void theDesignDirectionsFontPairingReachesTheScriptWriter() throws Exception {
        var dispatcher = mock(ToolDispatcher.class);
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap())).thenAnswer(a -> { writeDeck(a.getArgument(2), 2); return "OK"; });
        for (var c : List.of(new String[]{"{\"fontPairing\":\"EDITORIAL\"}", "headings fontFace \"Georgia\""},
                             new String[]{"{\"designDirection\":\"stored before fontPairing existed\"}", "CLASSIC"})) {
            var llm = new ScriptedLlm();
            llm.answers.add(call("create_presentation", "{\"script\":\"v1\"}"));
            llm.answers.add(call("submit_slide_review", "{\"approved\":true,\"summary\":\"ok\",\"issues\":[]}"));
            Job job = deckJob(); job.setDesignDirectionJson(c[0]);

            orchestrator(llm, dispatcher, 2).run(job);

            assertTrue(llm.requests.get(0).messages().getFirst().text().contains(c[1]), c[1]);
            assertTrue(llm.requests.get(0).system().contains("Font pairing"), "the pairing table is in the guides");
        }
    }

    private static String issues(int high) {
        var list = new ArrayList<String>();
        for (int i = 0; i < high; i++) list.add("{\"slide\":" + (i + 1) + ",\"severity\":\"high\",\"problem\":\"p" + i + "\",\"fix\":\"f\"}");
        return "{\"approved\":false,\"summary\":\"s\",\"issues\":[" + String.join(",", list) + "]}";
    }

    @Test void whenRewritesMakeTheDeckWorseTheBestReviewedVersionShips() throws Exception {
        var llm = new ScriptedLlm();
        llm.answers.add(call("create_presentation", "{\"script\":\"v1\"}"));
        llm.answers.add(call("submit_slide_review", issues(1)));
        llm.answers.add(call("create_presentation", "{\"script\":\"v2\"}"));
        llm.answers.add(call("submit_slide_review", issues(2)));
        llm.answers.add(call("create_presentation", "{\"script\":\"v3\"}"));
        llm.answers.add(call("submit_slide_review", issues(3)));
        var dispatcher = mock(ToolDispatcher.class);
        int[] version = {0};
        when(dispatcher.dispatch(anyString(), anyString(), any(), anyMap())).thenAnswer(a -> { writeDeck(a.getArgument(2), 3 + version[0]++); return "OK"; });

        var result = orchestrator(llm, dispatcher, 2).run(deckJob());

        assertEquals(6, llm.requests.size(), "the last rewrite is reviewed too");
        try (var deck = new XMLSlideShow(Files.newInputStream(result.outputPath()))) {
            assertEquals(3, deck.getSlides().size(), "v1 had the fewest defects, so v1 ships");
        }
        assertFalse(Files.exists(result.outputPath().resolveSibling("best-output.pptx")));
    }
}
