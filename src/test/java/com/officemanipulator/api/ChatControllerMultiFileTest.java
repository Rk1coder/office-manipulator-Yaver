package com.officemanipulator.api;

import com.officemanipulator.config.AiAvailability;
import com.officemanipulator.config.LlmClientProvider;
import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.service.DocumentEditService;
import com.officemanipulator.service.JobService;
import com.officemanipulator.service.PlanningService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatControllerMultiFileTest {
    private final JobService jobs = mock(JobService.class);
    private final PlanningService planning = mock(PlanningService.class);
    private final DocumentEditService edits = mock(DocumentEditService.class);
    private final AiAvailability ai = mock(AiAvailability.class);
    private final ChatController controller = new ChatController(jobs, planning, ai, edits, mock(LlmClientProvider.class));

    private static Job job(String name) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setFileName(name);
        job.setFormat(OutputFormat.DOCX);
        return job;
    }

    private static MultipartFile file(String name) {
        return new MockMultipartFile("file", name, null, new byte[]{1});
    }

    @Test void severalFilesBecomeOneGenerationJobWithEverySourceInFull() throws Exception {
        when(ai.enabled(any())).thenReturn(true);
        when(jobs.importSourceDocument(anyString(), any())).thenAnswer(i -> job(i.getArgument(0)));
        when(edits.sourceText(any())).thenAnswer(i -> "içerik of " + ((Job) i.getArgument(0)).getFileName());
        when(jobs.createJob(anyString(), anyString(), any())).thenReturn(job("out"));

        var response = controller.chatWithFile(List.of(file("a.docx"), file("b.xlsx")), "Rapor yap", "PPTX", null, null, null);

        assertEquals(202, response.getStatusCode().value());
        var source = ArgumentCaptor.forClass(String.class);
        verify(jobs).createJob(eq("Rapor yap"), source.capture(), eq(OutputFormat.PPTX));
        assertEquals("=== BELGE 1: a.docx ===\niçerik of a.docx\n=== BELGE 2: b.xlsx ===\niçerik of b.xlsx\n", source.getValue());
        verify(planning).createPlanAsync(any());
        verify(edits, never()).createEditJob(any(), any(), any(), any());
    }

    @Test void tooManyFilesOrTooMuchTextIsRefusedNotTruncated() throws Exception {
        when(ai.enabled(any())).thenReturn(true);
        var eleven = new ArrayList<MultipartFile>();
        for (int i = 0; i < ChatController.MAX_SOURCE_FILES + 1; i++) eleven.add(file(i + ".docx"));
        assertEquals(400, controller.chatWithFile(eleven, "x", null, null, null, null).getStatusCode().value());

        when(jobs.importSourceDocument(anyString(), any())).thenAnswer(i -> job(i.getArgument(0)));
        when(edits.sourceText(any())).thenReturn("x".repeat(ChatController.MAX_COMBINED_SOURCE_CHARS / 2));
        assertEquals(413, controller.chatWithFile(List.of(file("a.docx"), file("b.docx")), "x", null, null, null, null).getStatusCode().value());
        verify(jobs, never()).createJob(any(), any(), any());
    }
}
