package com.officemanipulator.office;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, in-memory registry handing out a one-time ticketed URL for an arbitrary local
 * file so ONLYOFFICE's ConvertService (which only fetches by URL) can read it — used to convert
 * an in-progress generation's intermediate file (not yet a completed {@code Job}) to ODT/ODS/ODP/DOC.
 */
@Component
@ConditionalOnProperty(name = "app.office.enabled", havingValue = "true")
public class ConversionSourceRegistry {
    private record Entry(Path path, Instant expiresAt) {}
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public UUID register(Path path) {
        UUID id = UUID.randomUUID();
        entries.put(id, new Entry(path, Instant.now().plusSeconds(60)));
        return id;
    }

    public Path take(UUID id) {
        Entry entry = entries.remove(id);
        entries.values().removeIf(e -> e.expiresAt().isBefore(Instant.now()));
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) return null;
        return entry.path();
    }
}
