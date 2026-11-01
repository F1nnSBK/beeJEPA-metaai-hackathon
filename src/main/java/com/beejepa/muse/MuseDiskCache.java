package com.beejepa.muse;

import com.beejepa.config.JacksonFactory;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MuseDiskCache {

    private static final Logger LOG = LoggerFactory.getLogger(MuseDiskCache.class);

    /**
     * Canonical entrance events where V-JEPA detected dense landing-board activity
     * and where a 0-bee hotspot count indicates a legacy off-center coordinate cache.
     */
    private static final Set<String> ENTRANCE_CLUSTER_EVENTS = Set.of(
            "evt_rec2125_seq000_f0000",
            "evt_rec2125_seq000_f1217",
            "evt_rec2125_seq001_f0320"
    );

    private final ObjectMapper objectMapper;
    private final Path cacheDir;
    private final Path museResponsesFile;
    private final Path museResponsesBackupFile;
    private final Map<String, String> responseMemoryCache = new ConcurrentHashMap<>();

    @jakarta.inject.Inject
    public MuseDiskCache(ObjectMapper objectMapper) {
        this(objectMapper != null ? objectMapper : JacksonFactory.createTolerantMapper(),
                Path.of("data", "hackathon_sample", "cache"));
    }

    public MuseDiskCache() {
        this(JacksonFactory.createTolerantMapper());
    }

    public MuseDiskCache(ObjectMapper objectMapper, Path cacheDir) {
        this.objectMapper = objectMapper != null ? objectMapper : JacksonFactory.createTolerantMapper();
        this.cacheDir = cacheDir;
        this.museResponsesFile = cacheDir.resolve("muse_responses.json");
        this.museResponsesBackupFile = cacheDir.resolve("muse_responses.json.bak");
        initCacheDirectory();
    }

    private void initCacheDirectory() {
        try {
            Files.createDirectories(cacheDir);
            if (Files.exists(museResponsesFile)) {
                try {
                    Map<String, String> loaded = objectMapper.readValue(
                            museResponsesFile.toFile(),
                            new TypeReference<LinkedHashMap<String, String>>() {}
                    );
                    if (loaded != null) {
                        responseMemoryCache.putAll(loaded);
                    }
                } catch (IOException primaryEx) {
                    LOG.warn("[MuseDiskCache] Primary muse_responses.json corrupted ({}). Attempting recovery from .bak...",
                            primaryEx.getMessage());
                    recoverFromBackup();
                }
            } else if (Files.exists(museResponsesBackupFile)) {
                recoverFromBackup();
            }
        } catch (IOException e) {
            LOG.warn("[MuseDiskCache] Could not initialize disk cache: {}", e.getMessage());
        }
    }

    private void recoverFromBackup() {
        if (!Files.exists(museResponsesBackupFile)) {
            return;
        }
        try {
            Map<String, String> loaded = objectMapper.readValue(
                    museResponsesBackupFile.toFile(),
                    new TypeReference<LinkedHashMap<String, String>>() {}
            );
            if (loaded != null && !loaded.isEmpty()) {
                responseMemoryCache.putAll(loaded);
                Files.copy(museResponsesBackupFile, museResponsesFile, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("[MuseDiskCache] Successfully restored {} responses from muse_responses.json.bak", loaded.size());
            }
        } catch (IOException backupEx) {
            LOG.error("[MuseDiskCache] Backup recovery also failed: {}", backupEx.getMessage());
        }
    }

    public Optional<EventSpatialSummary> loadSpatialSummary(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        Path file = cacheDir.resolve("sam_spatial_" + eventId + ".json");
        Path bakFile = cacheDir.resolve("sam_spatial_" + eventId + ".json.bak");
        if (!Files.exists(file) && !Files.exists(bakFile)) {
            return Optional.empty();
        }
        if (Files.exists(file)) {
            try {
                EventSpatialSummary summary = objectMapper.readValue(file.toFile(), EventSpatialSummary.class);
                if (isValidSpatialSummary(summary)) {
                    return Optional.of(summary);
                }
                LOG.warn("[MuseDiskCache] Rejecting invalid/drifted spatial cache for {}", eventId);
            } catch (IOException e) {
                LOG.warn("[MuseDiskCache] Corrupted spatial summary file for {} ({}). Attempting .bak recovery...",
                        eventId, e.getMessage());
            }
        }
        if (Files.exists(bakFile)) {
            try {
                EventSpatialSummary recovered = objectMapper.readValue(bakFile.toFile(), EventSpatialSummary.class);
                if (isValidSpatialSummary(recovered)) {
                    Files.copy(bakFile, file, StandardCopyOption.REPLACE_EXISTING);
                    return Optional.of(recovered);
                }
            } catch (IOException bakEx) {
                LOG.warn("[MuseDiskCache] Spatial backup recovery failed for {}: {}", eventId, bakEx.getMessage());
            }
        }
        return Optional.empty();
    }

    public boolean isValidSpatialSummary(EventSpatialSummary summary) {
        if (summary == null || summary.eventId() == null || summary.eventId().isBlank()) {
            return false;
        }
        if (summary.landingBoardBeeCount() < 0 || summary.beesInsideHotspotCount() < 0) {
            return false;
        }
        if (summary.frameBase64() == null || summary.frameBase64().isBlank()
                || summary.cropBase64() == null || summary.cropBase64().isBlank()) {
            return false;
        }
        // Detect the 688-vs-960 coordinate bug on entrance cluster events where >15 bees are on the board
        // yet 0 bees were found inside the hotspot due to the y-shifted box
        if (ENTRANCE_CLUSTER_EVENTS.contains(summary.eventId())
                && summary.landingBoardBeeCount() > 15
                && summary.beesInsideHotspotCount() == 0) {
            return false;
        }
        return true;
    }

    public synchronized void saveSpatialSummary(EventSpatialSummary summary) {
        if (!isValidSpatialSummary(summary)) {
            return;
        }
        Path targetFile = cacheDir.resolve("sam_spatial_" + summary.eventId() + ".json");
        Path bakFile = cacheDir.resolve("sam_spatial_" + summary.eventId() + ".json.bak");
        Path tmpFile = cacheDir.resolve("sam_spatial_" + summary.eventId() + ".json.tmp");
        try {
            Files.createDirectories(cacheDir);
            if (Files.exists(targetFile) && Files.size(targetFile) > 32) {
                Files.copy(targetFile, bakFile, StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary);
            Files.write(tmpFile, jsonBytes);
            moveAtomically(tmpFile, targetFile);
        } catch (IOException e) {
            LOG.warn("[MuseDiskCache] Failed to write spatial cache for {}: {}", summary.eventId(), e.getMessage());
            deleteQuietly(tmpFile);
        }
    }

    public Optional<String> getMuseResponse(String cacheKey) {
        return Optional.ofNullable(responseMemoryCache.get(normalizeKey(cacheKey)));
    }

    public Optional<String> getAnyFallbackResponse() {
        String defaultReport = responseMemoryCache.get("default");
        if (defaultReport != null && !defaultReport.isBlank()) {
            return Optional.of(defaultReport);
        }
        return responseMemoryCache.values().stream().findFirst();
    }

    public synchronized void saveMuseResponse(String cacheKey, String markdownReport) {
        if (markdownReport == null || markdownReport.isBlank()) {
            return;
        }
        responseMemoryCache.put(normalizeKey(cacheKey), markdownReport);
        Path tmpFile = cacheDir.resolve("muse_responses.json.tmp");
        try {
            Files.createDirectories(cacheDir);
            byte[] jsonBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(responseMemoryCache);
            Files.write(tmpFile, jsonBytes);
            if (Files.exists(museResponsesFile) && Files.size(museResponsesFile) > 10) {
                Files.copy(museResponsesFile, museResponsesBackupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            moveAtomically(tmpFile, museResponsesFile);
        } catch (IOException e) {
            LOG.warn("[MuseDiskCache] Failed to persist muse_responses.json: {}", e.getMessage());
            deleteQuietly(tmpFile);
        }
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicEx) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }

    public String normalizeKey(String raw) {
        return raw == null ? "default" : raw.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
