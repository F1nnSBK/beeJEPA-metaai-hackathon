package com.beejepa.muse;

import com.beejepa.config.JacksonFactory;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.beejepa.state.StateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Dual-tier live cache backed by Redis (L1 distributed cache) and SQLite WAL (L2 persistent state.db).
 * Replaces all static JSON/MP3 mock cache files on disk.
 */
@Singleton
public class MuseDiskCache {

    private static final Logger LOG = LoggerFactory.getLogger(MuseDiskCache.class);
    private static final long DEFAULT_TTL_SECONDS = 86_400L * 7; // 7 days default TTL

    private static final Set<String> ENTRANCE_CLUSTER_EVENTS = Set.of(
            "evt_rec2125_seq000_f0000",
            "evt_rec2125_seq000_f1217",
            "evt_rec2125_seq001_f0320"
    );

    private final ObjectMapper objectMapper;
    private final StateRepository stateRepository;
    private final RedisCacheClient redisCacheClient;
    private final Path cacheDir;
    private final long ttlSeconds;
    private final Map<String, String> l0ResponseCache = new ConcurrentHashMap<>();

    @Inject
    public MuseDiskCache(
            ObjectMapper objectMapper,
            @Nullable StateRepository stateRepository,
            @Nullable RedisCacheClient redisCacheClient
    ) {
        this(
                objectMapper != null ? objectMapper : JacksonFactory.createTolerantMapper(),
                stateRepository != null ? stateRepository : createDefaultStateRepository(null),
                redisCacheClient != null ? redisCacheClient : new RedisCacheClient(),
                resolveDefaultCacheDir(),
                resolveDefaultTtlSeconds()
        );
    }

    public MuseDiskCache(
            ObjectMapper objectMapper,
            StateRepository stateRepository,
            RedisCacheClient redisCacheClient,
            long ttlSeconds
    ) {
        this(
                objectMapper != null ? objectMapper : JacksonFactory.createTolerantMapper(),
                stateRepository != null ? stateRepository : createDefaultStateRepository(null),
                redisCacheClient != null ? redisCacheClient : new RedisCacheClient("127.0.0.1", 6379, false),
                resolveDefaultCacheDir(),
                ttlSeconds
        );
    }

    public MuseDiskCache(ObjectMapper objectMapper, Path cacheDir) {
        this(
                objectMapper != null ? objectMapper : JacksonFactory.createTolerantMapper(),
                createDefaultStateRepository(cacheDir),
                new RedisCacheClient("127.0.0.1", 6379, false),
                cacheDir,
                resolveDefaultTtlSeconds()
        );
    }

    public MuseDiskCache(ObjectMapper objectMapper) {
        this(
                objectMapper != null ? objectMapper : JacksonFactory.createTolerantMapper(),
                createDefaultStateRepository(null),
                new RedisCacheClient("127.0.0.1", 6379, false),
                resolveDefaultCacheDir(),
                resolveDefaultTtlSeconds()
        );
    }

    public MuseDiskCache() {
        this(JacksonFactory.createTolerantMapper());
    }

    private MuseDiskCache(
            ObjectMapper objectMapper,
            StateRepository stateRepository,
            RedisCacheClient redisCacheClient,
            Path cacheDir,
            long ttlSeconds
    ) {
        this.objectMapper = objectMapper;
        this.stateRepository = stateRepository;
        this.redisCacheClient = redisCacheClient;
        this.cacheDir = cacheDir;
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    private static StateRepository createDefaultStateRepository(Path customDir) {
        try {
            if (customDir != null) {
                Files.createDirectories(customDir);
                return new StateRepository(customDir.resolve("state.db"));
            }
            Path dir = resolveDefaultCacheDir();
            Files.createDirectories(dir);
            return new StateRepository(dir.resolve("state.db"));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize SQLite cache repository", e);
        }
    }

    private static Path resolveDefaultCacheDir() {
        String sqliteEnv = Optional.ofNullable(System.getProperty("BEEJEPA_SQLITE_PATH"))
                .or(() -> Optional.ofNullable(System.getenv("BEEJEPA_SQLITE_PATH")))
                .orElse(null);
        if (sqliteEnv != null && !sqliteEnv.isBlank()) {
            Path p = Path.of(sqliteEnv.trim()).toAbsolutePath().normalize();
            return p.getParent() != null ? p.getParent() : Path.of(".");
        }
        String envPath = Optional.ofNullable(System.getProperty("BEEJEPA_DATA_PATH"))
                .or(() -> Optional.ofNullable(System.getenv("BEEJEPA_DATA_PATH")))
                .orElse(null);
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath.trim());
        }
        return Path.of("data");
    }

    private static long resolveDefaultTtlSeconds() {
        String raw = Optional.ofNullable(System.getProperty("CACHE_TTL_SECONDS"))
                .or(() -> Optional.ofNullable(System.getenv("CACHE_TTL_SECONDS")))
                .orElse(null);
        if (raw != null && !raw.isBlank()) {
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_TTL_SECONDS;
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public boolean isRedisActive() {
        return redisCacheClient != null && redisCacheClient.isAvailable();
    }

    public String getCacheBackendDescription() {
        return isRedisActive() ? "REDIS_L1+SQLITE_L2" : "SQLITE_WAL";
    }

    public Optional<EventSpatialSummary> loadSpatialSummary(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        String redisKey = "beejepa:sam:" + eventId.trim();
        if (redisCacheClient != null) {
            Optional<String> redisJson = redisCacheClient.getString(redisKey);
            if (redisJson.isPresent()) {
                try {
                    EventSpatialSummary summary = objectMapper.readValue(redisJson.get(), EventSpatialSummary.class);
                    if (isValidSpatialSummary(summary)) {
                        return Optional.of(summary);
                    }
                } catch (IOException e) {
                    LOG.warn("[MuseDiskCache] Invalid spatial JSON in Redis for {}: {}", eventId, e.getMessage());
                }
            }
        }
        if (stateRepository != null) {
            Optional<String> sqliteJson = stateRepository.getCachedSpatialSummaryJson(eventId.trim());
            if (sqliteJson.isPresent()) {
                try {
                    EventSpatialSummary summary = objectMapper.readValue(sqliteJson.get(), EventSpatialSummary.class);
                    if (isValidSpatialSummary(summary)) {
                        if (redisCacheClient != null) {
                            redisCacheClient.setString(redisKey, sqliteJson.get(), ttlSeconds);
                        }
                        return Optional.of(summary);
                    }
                } catch (IOException e) {
                    LOG.warn("[MuseDiskCache] Invalid spatial JSON in SQLite for {}: {}", eventId, e.getMessage());
                }
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
        try {
            String json = objectMapper.writeValueAsString(summary);
            if (stateRepository != null) {
                stateRepository.saveCachedSpatialSummaryJson(
                        summary.eventId(),
                        json,
                        summary.landingBoardBeeCount(),
                        summary.beesInsideHotspotCount(),
                        ttlSeconds
                );
            }
            if (redisCacheClient != null) {
                redisCacheClient.setString("beejepa:sam:" + summary.eventId(), json, ttlSeconds);
            }
        } catch (IOException e) {
            LOG.warn("[MuseDiskCache] Failed to serialize spatial cache for {}: {}", summary.eventId(), e.getMessage());
        }
    }

    public Optional<String> getMuseResponse(String cacheKey) {
        String norm = normalizeKey(cacheKey);
        String redisKey = "beejepa:muse:" + norm;
        if (redisCacheClient != null) {
            Optional<String> redisHit = redisCacheClient.getString(redisKey);
            if (redisHit.isPresent() && !redisHit.get().isBlank()) {
                l0ResponseCache.put(norm, redisHit.get());
                return redisHit;
            }
        }
        if (stateRepository != null) {
            Optional<String> sqliteHit = stateRepository.getCachedMuseResponse(norm);
            if (sqliteHit.isPresent() && !sqliteHit.get().isBlank()) {
                l0ResponseCache.put(norm, sqliteHit.get());
                if (redisCacheClient != null) {
                    redisCacheClient.setString(redisKey, sqliteHit.get(), ttlSeconds);
                }
                return sqliteHit;
            }
        }
        return Optional.empty();
    }

    public Optional<String> getAnyFallbackResponse() {
        Optional<String> defaultHit = getMuseResponse("default");
        if (defaultHit.isPresent()) {
            return defaultHit;
        }
        if (stateRepository != null) {
            Optional<String> latest = stateRepository.getLatestCachedMuseResponse();
            if (latest.isPresent() && !latest.get().isBlank()) {
                return latest;
            }
        }
        return l0ResponseCache.values().stream().findFirst();
    }

    public synchronized void saveMuseResponse(String cacheKey, String markdownReport) {
        if (markdownReport == null || markdownReport.isBlank()) {
            return;
        }
        String norm = normalizeKey(cacheKey);
        l0ResponseCache.put(norm, markdownReport);
        if (stateRepository != null) {
            stateRepository.saveCachedMuseResponse(norm, markdownReport, ttlSeconds);
        }
        if (redisCacheClient != null) {
            redisCacheClient.setString("beejepa:muse:" + norm, markdownReport, ttlSeconds);
        }
    }

    public Optional<byte[]> getTtsAudio(String audioHash) {
        if (audioHash == null || audioHash.isBlank()) {
            return Optional.empty();
        }
        String key = audioHash.trim();
        String redisKey = "beejepa:tts:" + key;
        if (redisCacheClient != null) {
            Optional<byte[]> redisBytes = redisCacheClient.getBytes(redisKey);
            if (redisBytes.isPresent() && redisBytes.get().length > 0) {
                return redisBytes;
            }
        }
        if (stateRepository != null) {
            Optional<byte[]> sqliteBytes = stateRepository.getCachedTtsAudio(key);
            if (sqliteBytes.isPresent() && sqliteBytes.get().length > 0) {
                if (redisCacheClient != null) {
                    redisCacheClient.setBytes(redisKey, sqliteBytes.get(), ttlSeconds);
                }
                return sqliteBytes;
            }
        }
        return Optional.empty();
    }

    public synchronized void saveTtsAudio(String audioHash, String spokenText, byte[] audioBytes) {
        if (audioHash == null || audioHash.isBlank() || audioBytes == null || audioBytes.length == 0) {
            return;
        }
        String key = audioHash.trim();
        if (stateRepository != null) {
            stateRepository.saveCachedTtsAudio(key, spokenText, audioBytes, ttlSeconds);
        }
        if (redisCacheClient != null) {
            redisCacheClient.setBytes("beejepa:tts:" + key, audioBytes, ttlSeconds);
        }
    }

    public Optional<byte[]> getFrameBytes(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        String key = eventId.trim();
        String redisKey = "beejepa:frame:" + key;
        if (redisCacheClient != null) {
            Optional<byte[]> redisBytes = redisCacheClient.getBytes(redisKey);
            if (redisBytes.isPresent() && redisBytes.get().length > 0) {
                return redisBytes;
            }
        }
        if (stateRepository != null) {
            Optional<byte[]> sqliteBytes = stateRepository.getCachedFrameBytes(key);
            if (sqliteBytes.isPresent() && sqliteBytes.get().length > 0) {
                if (redisCacheClient != null) {
                    redisCacheClient.setBytes(redisKey, sqliteBytes.get(), ttlSeconds);
                }
                return sqliteBytes;
            }
        }
        return Optional.empty();
    }

    public synchronized void saveFrameBytes(String eventId, byte[] frameBytes) {
        if (eventId == null || eventId.isBlank() || frameBytes == null || frameBytes.length == 0) {
            return;
        }
        String key = eventId.trim();
        if (stateRepository != null) {
            stateRepository.saveCachedFrameBytes(key, frameBytes, ttlSeconds);
        }
        if (redisCacheClient != null) {
            redisCacheClient.setBytes("beejepa:frame:" + key, frameBytes, ttlSeconds);
        }
    }

    public String normalizeKey(String raw) {
        return raw == null ? "default" : raw.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
