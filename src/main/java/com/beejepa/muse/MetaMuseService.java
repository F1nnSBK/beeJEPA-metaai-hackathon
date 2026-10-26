package com.beejepa.muse;

import com.beejepa.config.MetaMuseConfig;
import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import com.beejepa.state.StateRepository;
import com.beejepa.state.model.MuseLogEntry;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beejepa.muse.dto.MetaHealthCheckResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
@ExecuteOn(TaskExecutors.BLOCKING)
public class MetaMuseService {

    private static final Logger LOG = LoggerFactory.getLogger(MetaMuseService.class);

    private final MetaMuseClient client;
    private final MetaMuseConfig config;
    private final StateRepository stateRepository;
    private final com.beejepa.event.EventService eventService;

    public MetaMuseService(MetaMuseClient client, MetaMuseConfig config, StateRepository stateRepository, com.beejepa.event.EventService eventService) {
        this.client = client;
        this.config = config;
        this.stateRepository = stateRepository;
        this.eventService = eventService;
    }

    public MuseInferenceResponse callInference(MuseInferenceRequest request) {
        long startTime = System.currentTimeMillis();
        String logId = UUID.randomUUID().toString();
        String endpoint = "/v1/models/muse/generate";

        if (!config.isConfigured()) {
            LOG.warn("Meta Muse API key is not configured. Running in MOCK mode. Provide META_MUSE_API_KEY in .env");
            MuseInferenceResponse mockResponse = createMockResponse(request, "Mock generated representation from Meta Muse for prompt: " + request.prompt());
            long latency = System.currentTimeMillis() - startTime;
            stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 200, latency, Instant.now()));
            return mockResponse;
        }

        try {
            String authHeader = "Bearer " + config.getApiKey();
            HttpResponse<MuseInferenceResponse> response = client.infer(authHeader, request);
            long latency = System.currentTimeMillis() - startTime;

            int statusCode = response.getStatus().getCode();
            stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), statusCode, latency, Instant.now()));

            if (response.getBody().isPresent()) {
                return response.getBody().get();
            } else {
                throw new RuntimeException("Empty response body from Meta Muse API");
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            LOG.error("Meta Muse API call failed: {}", e.getMessage());
            stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 500, latency, Instant.now()));
            throw new RuntimeException("Meta Muse API call failed: " + e.getMessage(), e);
        }
    }

    public MuseInferenceResponse callEmbedding(MuseInferenceRequest request) {
        long startTime = System.currentTimeMillis();
        String logId = UUID.randomUUID().toString();
        String endpoint = "/v1/models/muse/embeddings";

        if (!config.isConfigured()) {
            LOG.warn("Meta Muse API key is not configured. Generating synthetic embedding vector for development.");
            MuseInferenceResponse mockResponse = new MuseInferenceResponse(
                    "mock-emb-" + UUID.randomUUID(),
                    "muse-embedding-v1",
                    List.of(0.012, -0.453, 0.891, 0.124, -0.092, 0.671),
                    null,
                    Map.of("mode", "synthetic_mock", "dimensions", 6)
            );
            long latency = System.currentTimeMillis() - startTime;
            stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 200, latency, Instant.now()));
            return mockResponse;
        }

        try {
            String authHeader = "Bearer " + config.getApiKey();
            HttpResponse<MuseInferenceResponse> response = client.embed(authHeader, request);
            long latency = System.currentTimeMillis() - startTime;

            int statusCode = response.getStatus().getCode();
            stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), statusCode, latency, Instant.now()));

            if (response.getBody().isPresent()) {
                return response.getBody().get();
            } else {
                throw new RuntimeException("Empty response body from Meta Muse API");
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            LOG.error("Meta Muse embedding API call failed: {}", e.getMessage());
            stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 500, latency, Instant.now()));
            throw new RuntimeException("Meta Muse embedding API call failed: " + e.getMessage(), e);
        }
    }

    private MuseInferenceResponse createMockResponse(MuseInferenceRequest request, String text) {
        return new MuseInferenceResponse(
                "mock-" + UUID.randomUUID(),
                request.model() != null ? request.model() : "meta-muse-v1",
                List.of(0.12, -0.34, 0.56, 0.78),
                text,
                Map.of("status", "mock_success", "note", "Configure META_MUSE_API_KEY in .env for live API calls")
        );
    }

    public MetaHealthCheckResult checkHealth() {
        if (!config.isConfigured()) {
            return new MetaHealthCheckResult(
                    false,
                    false,
                    "MOCK_OFFLINE",
                    200,
                    0,
                    config.getBaseUrl(),
                    List.of("muse-wonder-1.0 (Mock)", "muse-spark-1.0 (Mock)", "sam-3.1 (Mock)"),
                    "No MODEL_API_KEY configured. Running in offline zero-credit mock mode."
            );
        }

        long startTime = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            String rawBase = config.getBaseUrl() != null ? config.getBaseUrl().trim() : "https://api.meta.ai";
            String base = rawBase.replaceAll("[^a-zA-Z0-9.:/_-]", "").replaceAll("/+$", "");
            String targetUrl = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTime;
            int code = response.statusCode();

            if (code >= 200 && code < 300) {
                List<String> models = parseModelIds(response.body());
                return new MetaHealthCheckResult(
                        true,
                        true,
                        "LIVE_CONNECTED",
                        code,
                        latency,
                        config.getBaseUrl(),
                        models,
                        "Successfully connected to Meta Model API (0 credits consumed)."
                );
            } else if (code == 401 || code == 403) {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "AUTH_FAILED",
                        code,
                        latency,
                        config.getBaseUrl(),
                        List.of(),
                        "Meta API authentication failed (HTTP " + code + "): Invalid or expired MODEL_API_KEY."
                );
            } else {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "API_ERROR",
                        code,
                        latency,
                        config.getBaseUrl(),
                        List.of(),
                        "Meta API returned status HTTP " + code
                );
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            LOG.warn("Meta Model API health check failed: {}", e.getMessage());
            return new MetaHealthCheckResult(
                    true,
                    false,
                    "UNREACHABLE",
                    503,
                    latency,
                    config.getBaseUrl(),
                    List.of(),
                    "Unable to reach Meta API endpoint: " + e.getMessage()
            );
        }
    }

    private List<String> parseModelIds(String json) {
        List<String> models = new ArrayList<>();
        if (json == null || json.isBlank()) return models;
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            models.add(m.group(1));
        }
        return models;
    }

    public com.beejepa.muse.dto.StagedMuseRequest prepareMuseQuery(com.beejepa.muse.dto.PrepareMuseQueryRequest request) {
        String eventId = request != null ? request.eventId() : null;
        Map<String, Object> eventData = (request != null && request.eventData() != null)
                ? new java.util.HashMap<>(request.eventData())
                : new java.util.HashMap<>();

        if (eventData.isEmpty() && eventId != null && !eventId.isBlank()) {
            eventService.getEvent(eventId).ifPresent(ev -> {
                Object rawEvent = ev.get("event");
                if (rawEvent instanceof com.beejepa.event.AnomalyEvent ae) {
                    eventData.put("eventId", ae.eventId());
                    eventData.put("sequenceId", ae.sequenceId());
                    eventData.put("timestampSec", ae.timestampSec());
                    eventData.put("frameIndex", ae.frameIndex());
                    eventData.put("surpriseScoreL2", ae.surpriseScoreL2());
                    eventData.put("pixelDiffBaseline", ae.pixelDiffBaseline());
                    eventData.put("hotspot", ae.hotspot());
                }
                if (ev.containsKey("agentResult")) {
                    eventData.put("agentResult", ev.get("agentResult"));
                }
            });
        }

        String rawBase = config.getBaseUrl() != null ? config.getBaseUrl().trim() : "https://api.meta.ai";
        String base = rawBase.replaceAll("[^a-zA-Z0-9.:/_-]", "").replaceAll("/+$", "");
        String targetUrl = base.endsWith("/v1") ? base + "/responses" : base + "/v1/responses";

        String maskedKey = "UNCONFIGURED_KEY";
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            String key = config.getApiKey();
            maskedKey = (key.length() > 14 ? key.substring(0, 14) : key) + "****************";
        }

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + maskedKey + " [STAGED: NOT TRANSMITTED]",
                "Content-Type", "application/json",
                "Accept", "application/json",
                "X-Meta-Client", "beeJEPA-V-JEPA-Integration/1.0"
        );

        String userQuestion = (request != null && request.question() != null && !request.question().isBlank())
                ? request.question()
                : "Explain this anomaly event: What physical interaction occurred in this tubelet region and what is the threat assessment?";

        // Grounded prompt with complete causal physics and event telemetry
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("System: You are Meta Muse (multimodal world-model reasoning system), integrated into the BeeJEPA real-time hive entrance surveillance pipeline.\n");
        promptBuilder.append("Your role is to diagnose physical anomalies in video sequences using V-JEPA latent surprise dynamics and SAM 3.1 visual grounding.\n\n");
        promptBuilder.append("### Active Anomaly Telemetry:\n");

        if (!eventData.isEmpty()) {
            promptBuilder.append(String.format("- Event Identifier: %s\n", eventData.getOrDefault("eventId", eventId != null ? eventId : "evt_active")));
            if (eventData.containsKey("surpriseScoreL2")) {
                promptBuilder.append(String.format(java.util.Locale.US, "- V-JEPA Latent Surprise Error (sigma): %.2f sigma\n", Double.parseDouble(String.valueOf(eventData.get("surpriseScoreL2")))));
            }
            if (eventData.containsKey("pixelDiffBaseline")) {
                promptBuilder.append(String.format(java.util.Locale.US, "- Baseline Frame Pixel Difference: %.4f (Low pixel shift vs high latent surprise confirms semantic action)\n", Double.parseDouble(String.valueOf(eventData.get("pixelDiffBaseline")))));
            }
            if (eventData.containsKey("frameIndex") && eventData.containsKey("timestampSec")) {
                promptBuilder.append(String.format(java.util.Locale.US, "- Temporal Coordinates: Peak frame #%s at %.2f seconds\n", eventData.get("frameIndex"), Double.parseDouble(String.valueOf(eventData.get("timestampSec")))));
            }
            if (eventData.get("hotspot") != null) {
                promptBuilder.append(String.format("- Spatial Hotspot Tubelet: %s\n", eventData.get("hotspot")));
            }
            if (eventData.get("agentResult") != null) {
                promptBuilder.append(String.format("- SAM 3.1 Visual Segmentation: %s\n", eventData.get("agentResult")));
            }
        } else {
            promptBuilder.append("- Event Context: Real-time Live Hive Entrance Stream (No historical anomaly selected)\n");
            promptBuilder.append("- Monitoring: Standard foraging traffic at 60 FPS\n");
        }

        promptBuilder.append("\n### Beekeeper Query:\n");
        promptBuilder.append(userQuestion).append("\n\n");
        promptBuilder.append("### Instructions for Muse:\n");
        promptBuilder.append("1. Provide a concise, physics-grounded diagnosis of the bee flight trajectory and interaction.\n");
        promptBuilder.append("2. Classify the threat level (Normal Hive Traffic, Mid-Air Collision, Wasps/Hornet Predation, Swarming Behavior).\n");
        promptBuilder.append("3. Provide one immediate action recommendation for the beekeeper.");

        String fullPrompt = promptBuilder.toString();
        int estimatedTokens = Math.max(20, fullPrompt.length() / 4);

        Map<String, Object> payload = Map.of(
                "model", "muse-wonder-1.0",
                "reasoning", Map.of("effort", "high"),
                "input", fullPrompt
        );

        return new com.beejepa.muse.dto.StagedMuseRequest(
                true,                          // staged
                false,                         // sent: explicitly NOT sent
                targetUrl,
                "POST",
                "muse-wonder-1.0",
                headers,
                payload,
                fullPrompt,
                eventId != null ? eventId : (String) eventData.get("eventId"),
                eventData,
                estimatedTokens,
                0L,                            // 0 credits consumed
                "Payload prepared with event telemetry. Staged for Meta Muse endpoint (Not transmitted - 0 credits consumed).",
                Instant.now().toString()
        );
    }

    public MetaMuseConfig getConfig() {
        return config;
    }
}
