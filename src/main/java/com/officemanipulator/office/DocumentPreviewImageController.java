package com.officemanipulator.office;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
@ConditionalOnProperty(name="app.office.enabled",havingValue="true")
public class DocumentPreviewImageController {
    private final DocumentPreviewImageService images;
    public DocumentPreviewImageController(DocumentPreviewImageService images) {this.images=images;}

    @GetMapping("/{jobId}/preview/images")
    public ResponseEntity<Map<String,Object>> pageCount(@PathVariable UUID jobId) {
        try {
            return ResponseEntity.ok(Map.of("pageCount",images.ensureImages(jobId)));
        } catch(ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",e.getReason()==null?"İşlem tamamlanamadı":e.getReason()));
        } catch(Exception e) {
            org.slf4j.LoggerFactory.getLogger(DocumentPreviewImageController.class).warn("Preview image render failed ({})",e.getClass().getSimpleName());
            return ResponseEntity.status(502).body(Map.of("error","Gerçek görünüm oluşturulamadı"));
        }
    }

    @GetMapping("/{jobId}/preview/images/{page}")
    public ResponseEntity<Resource> page(@PathVariable UUID jobId,@PathVariable int page) {
        if(page<1) return ResponseEntity.notFound().build();
        Path path=images.imagePath(jobId,page);
        if(!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
            .header(HttpHeaders.CACHE_CONTROL,"private, max-age=3600")
            .body(new PathResource(path));
    }
}
