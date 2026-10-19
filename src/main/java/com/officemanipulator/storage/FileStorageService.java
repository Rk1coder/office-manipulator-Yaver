package com.officemanipulator.storage;

import com.officemanipulator.domain.OutputFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path basePath;

    public FileStorageService(@Value("${app.storage.base-path}") String basePath) throws IOException {
        this.basePath = Paths.get(basePath);
        Files.createDirectories(this.basePath);
    }

    public Path resolveOutputPath(UUID jobId, OutputFormat format) {
        return basePath.resolve(jobId.toString()).resolve("output." + format.fileExtension());
    }

    public Path resolveJobDir(UUID jobId) {
        return basePath.resolve(jobId.toString());
    }

    public void deleteJobFiles(UUID jobId) {
        Path dir = basePath.resolve(jobId.toString());
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not remove files for job " + jobId, e);
        }
    }

    public void cleanupFailedJob(com.officemanipulator.domain.Job job) {
        try {
            deleteJobFiles(job.getId());
            job.setFilePath(null);
            job.setFilesCleanedAt(java.time.OffsetDateTime.now());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(FileStorageService.class).warn("Cleanup will retry job {}: {}", job.getId(), e.getMessage());
        }
    }

    public long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }
}
