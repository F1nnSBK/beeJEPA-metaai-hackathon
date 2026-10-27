package com.beejepa.muse;

import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MuseDiskCache {

    private final ObjectMapper objectMapper;
    private final Path cacheDir;
    private final Path museResponsesFile;
    private final Map<String, String> responseMemoryCache = new ConcurrentHashMap<>();

    public MuseDiskCache(ObjectMapper objectMapper) {
        this(objectMapper, Path.of("data", "hackathon_sample", "cache"));
    }

    public MuseDiskCache(ObjectMapper objectMapper, Path cacheDir) {
        this.objectMapper = objectMapper;
        this.cacheDir = cacheDir;
        this.museResponsesFile = cacheDir.resolve("muse_responses.json");
        initCacheDirectory();
    }

    private void initCacheDirectory() {
        try {
            Files.createDirectories(cacheDir);
            if (Files.exists(museResponsesFile)) {
                Map<String, String> loaded = objectMapper.readValue(
                        museResponsesFile.toFile(),
                        new TypeReference<LinkedHashMap<String, String>>() {}
                );
                responseMemoryCache.putAll(loaded);
            }
        } catch (IOException e) {
            System.err.println("[MuseDiskCache] Could not initialize disk cache: " + e.getMessage());
        }
    }

    public Optional<EventSpatialSummary> loadSpatialSummary(String eventId) {
        Path file = cacheDir.resolve("sam_spatial_" + eventId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), EventSpatialSummary.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void saveSpatialSummary(EventSpatialSummary summary) {
        Path file = cacheDir.resolve("sam_spatial_" + summary.eventId() + ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), summary);
        } catch (IOException e) {
            System.err.println("[MuseDiskCache] Failed to write spatial cache for " + summary.eventId());
        }
    }

    public Optional<String> getMuseResponse(String cacheKey) {
        return Optional.ofNullable(responseMemoryCache.get(normalizeKey(cacheKey)));
    }

    public Optional<String> getAnyFallbackResponse() {
        return responseMemoryCache.values().stream().findFirst();
    }

    public synchronized void saveMuseResponse(String cacheKey, String markdownReport) {
        if (markdownReport == null || markdownReport.isBlank()) {
            return;
        }
        responseMemoryCache.put(normalizeKey(cacheKey), markdownReport);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(museResponsesFile.toFile(), responseMemoryCache);
        } catch (IOException e) {
            System.err.println("[MuseDiskCache] Failed to persist muse_responses.json: " + e.getMessage());
        }
    }

    public String normalizeKey(String raw) {
        return raw == null ? "default" : raw.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
