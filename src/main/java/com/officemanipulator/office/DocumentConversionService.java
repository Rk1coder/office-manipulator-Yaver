package com.officemanipulator.office;

import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Converts a just-generated intermediate file (DOCX/XLSX/PPTX) to the user's actually requested
 * format (ODT/ODS/ODP/DOC) via ONLYOFFICE's ConvertService, in place on disk. A no-op for every
 * format that doesn't need conversion ({@link OutputFormat#generationFormat()} equal to itself).
 */
@Service
@ConditionalOnProperty(name = "app.office.enabled", havingValue = "true")
public class DocumentConversionService {
    private final OfficeDocumentClient docs;
    private final OfficeTokens tokens;
    private final ConversionSourceRegistry registry;
    private final String appInternal;

    public DocumentConversionService(OfficeDocumentClient docs, OfficeTokens tokens, ConversionSourceRegistry registry,
            @Value("${app.office.app-internal-url}") String appInternal) {
        this.docs = docs; this.tokens = tokens; this.registry = registry; this.appInternal = appInternal;
    }

    /** If {@code job.getFormat()} needs conversion from its generation format, converts the file at
     * {@code outputPath} in place; otherwise does nothing. */
    public static void applyIfNeeded(Optional<DocumentConversionService> service, Job job, Path outputPath) throws IOException {
        OutputFormat target = job.getFormat();
        if (target.generationFormat() == target) return;
        DocumentConversionService svc = service.orElseThrow(() -> new IllegalStateException(
                target + " formatını üretmek için ONLYOFFICE modülü (app.office.enabled) etkin olmalı"));
        svc.convert(outputPath, outputPath, target.generationFormat(), target);
    }

    public static void requireSupported(OutputFormat from, OutputFormat to) {
        boolean supported = from != to && switch (from) {
            case DOCX, DOC, ODT -> java.util.Set.of(OutputFormat.DOCX, OutputFormat.DOC, OutputFormat.ODT, OutputFormat.PDF, OutputFormat.TXT).contains(to);
            case XLSX, ODS, CSV -> java.util.Set.of(OutputFormat.XLSX, OutputFormat.ODS, OutputFormat.CSV, OutputFormat.PDF).contains(to);
            case PPTX, ODP -> java.util.Set.of(OutputFormat.PPTX, OutputFormat.ODP, OutputFormat.PDF).contains(to);
            default -> false;
        };
        if (!supported) throw new IllegalArgumentException("Bu biçimler arasında yerel dönüşüm desteklenmiyor: " + from + " → " + to);
    }

    public void convert(Path source, Path destination, OutputFormat from, OutputFormat to) throws IOException {
        requireSupported(from, to);
        UUID id = registry.register(source);
        String ticket = tokens.ticket(id, "convert-source", Instant.now().plusSeconds(60));
        String url = appInternal + "/api/office/convert-source/" + id + "?ticket=" + ticket;
        byte[] converted = docs.convertTo(url, from.fileExtension(), to.fileExtension(), "convert-" + id);
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Files.write(destination, converted);
    }
}
