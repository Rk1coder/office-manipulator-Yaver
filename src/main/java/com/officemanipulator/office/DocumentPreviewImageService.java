package com.officemanipulator.office;

import com.officemanipulator.domain.Job;
import com.officemanipulator.domain.OutputFormat;
import com.officemanipulator.storage.FileStorageService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

/** Renders a real, pixel-accurate preview of a job's document (background, shapes, layout) by
 * converting it to PDF through ONLYOFFICE and rasterizing each page, since the lightweight
 * structural preview used for in-app editing intentionally discards all visual design. */
@Service
@ConditionalOnProperty(name="app.office.enabled",havingValue="true")
public class DocumentPreviewImageService {
    private static final int MAX_PAGES=60;
    private static final int DPI=110;
    private final OfficeService office;
    private final OfficeDocumentClient docs;
    private final OfficeTokens tokens;
    private final FileStorageService storage;
    private final String appInternal;

    public DocumentPreviewImageService(OfficeService office,OfficeDocumentClient docs,OfficeTokens tokens,FileStorageService storage,
            @Value("${app.office.app-internal-url}") String appInternal) {
        this.office=office;this.docs=docs;this.tokens=tokens;this.storage=storage;this.appInternal=appInternal;
    }

    private Path imageDir(UUID jobId) {
        return storage.resolveJobDir(jobId).resolve("preview-images");
    }

    /** Idempotent: returns the cached page count if images already exist, otherwise renders and caches them. */
    public synchronized int ensureImages(UUID jobId) throws IOException {
        Job job=office.availableJob(jobId);
        Path dir=imageDir(jobId);
        int cached=cachedPageCount(dir);
        if(cached>0) return cached;

        byte[] pdfBytes=job.getFormat()==OutputFormat.PDF
            ? Files.readAllBytes(Path.of(job.getFilePath()))
            : docs.convertToPdf(previewSourceUrl(jobId),job.getFormat().fileExtension(),"preview-"+jobId);

        Files.createDirectories(dir);
        try(PDDocument pdf=Loader.loadPDF(pdfBytes)) {
            PDFRenderer renderer=new PDFRenderer(pdf);
            int pages=Math.min(pdf.getNumberOfPages(),MAX_PAGES);
            for(int i=0;i<pages;i++) {
                var image=renderer.renderImageWithDPI(i,DPI);
                Path tmp=Files.createTempFile(dir,"tmp-",".png");
                try {
                    ImageIO.write(image,"png",tmp.toFile());
                    Files.move(tmp,dir.resolve("page-"+(i+1)+".png"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                } finally {Files.deleteIfExists(tmp);}
            }
            return pages;
        }
    }

    public Path imagePath(UUID jobId,int page) {
        return imageDir(jobId).resolve("page-"+page+".png");
    }

    private String previewSourceUrl(UUID jobId) {
        String ticket=tokens.ticket(jobId,"preview",Instant.now().plusSeconds(120));
        return appInternal+"/api/office/preview/"+jobId+"/document?ticket="+ticket;
    }

    private static int cachedPageCount(Path dir) throws IOException {
        if(!Files.isDirectory(dir)) return 0;
        try(var files=Files.list(dir)) {
            return (int)files.filter(p->p.getFileName().toString().matches("page-\\d+\\.png")).count();
        }
    }
}
