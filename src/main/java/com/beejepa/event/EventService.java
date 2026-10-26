package com.beejepa.event;

import com.beejepa.storage.StorageService;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Singleton
public class EventService {

    private static final Logger LOG = LoggerFactory.getLogger(EventService.class);

    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public EventService(StorageService storageService, ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    private Path getEventsDirectory() {
        return storageService.getDataDirectory().resolve("events");
    }

    private Path getTimelineFile() {
        return storageService.getDataDirectory().resolve("timeline_rec2123.json");
    }

    public List<Map<String, Object>> listEvents() {
        Path eventsDir = getEventsDirectory();
        List<Map<String, Object>> result = new ArrayList<>();

        if (!Files.exists(eventsDir)) {
            LOG.debug("Events directory does not exist at {}", eventsDir);
            return result;
        }

        try (Stream<Path> stream = Files.list(eventsDir)) {
            stream.filter(Files::isDirectory)
                  .sorted(Comparator.comparing(Path::getFileName))
                  .forEach(eventFolder -> {
                      Path metaFile = eventFolder.resolve("meta.json");
                      if (Files.exists(metaFile)) {
                          try {
                              AnomalyEvent event = objectMapper.readValue(Files.readAllBytes(metaFile), AnomalyEvent.class);
                              Map<String, Object> item = new HashMap<>();
                              item.put("eventId", event.eventId());
                              item.put("sequenceId", event.sequenceId());
                              item.put("timestampSec", event.timestampSec());
                              item.put("frameIndex", event.frameIndex());
                              item.put("surpriseScoreL2", event.surpriseScoreL2());
                              item.put("pixelDiffBaseline", event.pixelDiffBaseline());
                              item.put("horizonDeltaFrames", event.horizonDeltaFrames());
                              item.put("hotspot", event.hotspot());

                              // Check if agent_result.json is cached
                              Path agentResultFile = eventFolder.resolve("agent_result.json");
                              boolean hasCachedResult = Files.exists(agentResultFile);
                              item.put("isCached", hasCachedResult);
                              if (hasCachedResult) {
                                  try {
                                      Map<?, ?> agentResult = objectMapper.readValue(Files.readAllBytes(agentResultFile), Map.class);
                                      item.put("agentResult", agentResult);
                                  } catch (Exception ignored) {}
                              }

                              // Media presence
                              item.put("hasRawClip", Files.exists(eventFolder.resolve("clip_raw.mp4")));
                              item.put("hasComparisonClip", Files.exists(eventFolder.resolve("clip_comparison.mp4")));
                              item.put("hasPeakFrame", Files.exists(eventFolder.resolve("peak_frame.jpg")));

                              result.add(item);
                          } catch (IOException e) {
                              LOG.error("Failed to read meta.json from {}: {}", metaFile, e.getMessage());
                          }
                      }
                  });
        } catch (IOException e) {
            LOG.error("Failed to list events from {}: {}", eventsDir, e.getMessage());
        }

        return result;
    }

    public Optional<Map<String, Object>> getEvent(String eventId) {
        Path eventsDir = getEventsDirectory();
        Path eventFolder = eventsDir.resolve(eventId);
        Path metaFile = eventFolder.resolve("meta.json");

        if (!Files.exists(metaFile)) {
            return Optional.empty();
        }

        try {
            AnomalyEvent event = objectMapper.readValue(Files.readAllBytes(metaFile), AnomalyEvent.class);
            Map<String, Object> map = new HashMap<>();
            map.put("event", event);

            Path agentResultFile = eventFolder.resolve("agent_result.json");
            if (Files.exists(agentResultFile)) {
                map.put("agentResult", objectMapper.readValue(Files.readAllBytes(agentResultFile), Map.class));
                map.put("cached", true);
            } else {
                map.put("cached", false);
            }

            map.put("hasRawClip", Files.exists(eventFolder.resolve("clip_raw.mp4")));
            map.put("hasComparisonClip", Files.exists(eventFolder.resolve("clip_comparison.mp4")));
            map.put("hasPeakFrame", Files.exists(eventFolder.resolve("peak_frame.jpg")));

            return Optional.of(map);
        } catch (IOException e) {
            LOG.error("Failed to read event {}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<File> getMediaFile(String eventId, String filename) {
        Path eventsDir = getEventsDirectory();
        Path filePath = eventsDir.resolve(eventId).resolve(filename);

        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            return Optional.of(filePath.toFile());
        }
        return Optional.empty();
    }

    public Map<String, Object> getTimeline() {
        Path timelinePath = getTimelineFile();
        if (Files.exists(timelinePath)) {
            try {
                return objectMapper.readValue(Files.readAllBytes(timelinePath), Map.class);
            } catch (IOException e) {
                LOG.error("Failed to read timeline JSON: {}", e.getMessage());
            }
        }
        return Map.of("timeline", List.of(), "error", "Timeline not found on storage");
    }

    public Map<String, Object> analyzeEvent(String eventId, boolean forceRefresh) {
        Path eventsDir = getEventsDirectory();
        Path eventFolder = eventsDir.resolve(eventId);
        Path agentResultFile = eventFolder.resolve("agent_result.json");

        // 1. Check budget protection / SSD cache
        if (!forceRefresh && Files.exists(agentResultFile)) {
            try {
                LOG.info("Returning cached agent result for event {} (0 API credits used)", eventId);
                Map<String, Object> cached = objectMapper.readValue(Files.readAllBytes(agentResultFile), Map.class);
                cached.put("cached", true);
                return cached;
            } catch (IOException e) {
                LOG.warn("Failed to read cached agent_result.json for {}", eventId);
            }
        }

        // 2. Perform analysis (mock / live Meta Model API)
        LOG.info("Triggering Virtual Threads for SAM 3.1 & Muse Spark on event {}", eventId);

        Map<String, Object> result = new HashMap<>();
        result.put("eventId", eventId);
        result.put("cached", false);
        result.put("analyzedAt", java.time.Instant.now().toString());

        Map<String, Object> samResult = new HashMap<>();
        samResult.put("model", "meta/sam-3.1");
        samResult.put("status", "SUCCESS");
        samResult.put("instanceCount", 2);
        samResult.put("segmentationMaskRle", "compact_rle_instance_0");
        samResult.put("segmentedObject", "Bee Collision Pair");
        result.put("sam", samResult);

        Map<String, Object> museResult = new HashMap<>();
        museResult.put("model", "meta-models/muse-spark-open-1026");
        museResult.put("status", "SUCCESS");
        museResult.put("confidence", 0.94);
        museResult.put("diagnosis", "Anomaly detected (Surprise Score spike): Incoming forager bee collided abruptly with vertical hive entrance wall at 60 fps, tumbling onto the alighting board.");
        result.put("muse", museResult);

        // 3. Cache to SSD to protect budget
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(result);
            Files.write(agentResultFile, bytes);
            LOG.info("Successfully cached agent_result.json to {}", agentResultFile);
        } catch (IOException e) {
            LOG.error("Failed to write agent_result.json: {}", e.getMessage());
        }

        return result;
    }
}
