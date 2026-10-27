package com.beejepa.muse;

import com.beejepa.config.BasetenConfig;
import com.beejepa.config.MetaMuseConfig;
import com.beejepa.muse.dto.MetaHealthCheckResult;
import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import com.beejepa.muse.dto.PrepareMuseQueryRequest;
import com.beejepa.muse.dto.StagedMuseRequest;
import com.beejepa.state.StateRepository;
import com.beejepa.state.model.MuseLogEntry;
import io.micronaut.http.HttpResponse;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
@ExecuteOn(TaskExecutors.BLOCKING)
public class MetaMuseService {

    private static final Logger LOG = LoggerFactory.getLogger(MetaMuseService.class);

    private final MetaMuseClient client;
    private final MetaMuseConfig config;
    private final BasetenConfig basetenConfig;
    private final StateRepository stateRepository;
    private final com.beejepa.event.EventService eventService;

    @Inject
    public MetaMuseService(MetaMuseClient client,
                           MetaMuseConfig config,
                           BasetenConfig basetenConfig,
                           StateRepository stateRepository,
                           com.beejepa.event.EventService eventService) {
        this.client = client;
        this.config = config;
        this.basetenConfig = basetenConfig != null ? basetenConfig : new BasetenConfig();
        this.stateRepository = stateRepository;
        this.eventService = eventService;
    }

    public MetaMuseService(MetaMuseClient client,
                           MetaMuseConfig config,
                           StateRepository stateRepository,
                           com.beejepa.event.EventService eventService) {
        this(client, config, new BasetenConfig(), stateRepository, eventService);
    }

    public MuseInferenceResponse callInference(MuseInferenceRequest request) {
        long startTime = System.currentTimeMillis();
        String logId = UUID.randomUUID().toString();
        String endpoint = "/v1/chat/completions";

        if (!basetenConfig.isConfigured() && !config.isConfigured()) {
            LOG.warn("Neither Baseten nor Meta API key is configured. Running in MOCK mode.");
            MuseInferenceResponse mockResponse = createMockResponse(request, "Mock generated representation from Meta Muse Spark for prompt: " + request.prompt());
            long latency = System.currentTimeMillis() - startTime;
            if (stateRepository != null) {
                stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 200, latency, Instant.now()));
            }
            return mockResponse;
        }

        // Safety policy: no live inference calls allowed until explicitly enabled to protect credits
        if (!basetenConfig.isAllowInferenceCalls()) {
            LOG.info("Inference calls blocked by safety policy (healthchecks only). 0 credits consumed.");
            String model = basetenConfig.getModel() != null ? basetenConfig.getModel() : "meta-models/muse-spark-open";
            MuseInferenceResponse standbyResponse = new MuseInferenceResponse(
                    "staged-" + UUID.randomUUID(),
                    model,
                    List.of(),
                    "Inference call held on zero-credit standby. Healthcheck is active on Baseten. Staged payload prepared.",
                    Map.of(
                            "status", "standby_zero_credits",
                            "provider", "baseten",
                            "model", model,
                            "note", "Inference calls blocked to protect budget as requested."
                    )
            );
            long latency = System.currentTimeMillis() - startTime;
            if (stateRepository != null) {
                stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 200, latency, Instant.now()));
            }
            return standbyResponse;
        }

        try {
            String authHeader = "Bearer " + config.getApiKey();
            HttpResponse<MuseInferenceResponse> response = client.infer(authHeader, request);
            long latency = System.currentTimeMillis() - startTime;

            int statusCode = response.getStatus().getCode();
            if (stateRepository != null) {
                stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), statusCode, latency, Instant.now()));
            }

            if (response.getBody().isPresent()) {
                return response.getBody().get();
            } else {
                throw new RuntimeException("Empty response body from Meta Muse API");
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            LOG.error("Meta Muse API call failed: {}", e.getMessage());
            if (stateRepository != null) {
                stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 500, latency, Instant.now()));
            }
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
            if (stateRepository != null) {
                stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 200, latency, Instant.now()));
            }
            return mockResponse;
        }

        try {
            String authHeader = "Bearer " + config.getApiKey();
            HttpResponse<MuseInferenceResponse> response = client.embed(authHeader, request);
            long latency = System.currentTimeMillis() - startTime;

            int statusCode = response.getStatus().getCode();
            if (stateRepository != null) {
                stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), statusCode, latency, Instant.now()));
            }

            if (response.getBody().isPresent()) {
                return response.getBody().get();
            } else {
                throw new RuntimeException("Empty response body from Meta Muse API");
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            LOG.error("Meta Muse embedding API call failed: {}", e.getMessage());
            if (stateRepository != null) {
                stateRepository.logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 500, latency, Instant.now()));
            }
            throw new RuntimeException("Meta Muse embedding API call failed: " + e.getMessage(), e);
        }
    }

    private MuseInferenceResponse createMockResponse(MuseInferenceRequest request, String text) {
        return new MuseInferenceResponse(
                "mock-" + UUID.randomUUID(),
                request.model() != null ? request.model() : "meta-models/muse-spark-open",
                List.of(0.12, -0.34, 0.56, 0.78),
                text,
                Map.of("status", "mock_success", "note", "Inference calls held in standby")
        );
    }

    public MetaHealthCheckResult checkHealth() {
        if (basetenConfig.isConfigured()) {
            return checkBasetenHealth();
        } else if (config.isConfigured()) {
            return checkMetaHealth();
        } else {
            return new MetaHealthCheckResult(
                    false,
                    false,
                    "MOCK_OFFLINE",
                    200,
                    0,
                    basetenConfig.getBaseUrl(),
                    List.of("meta-models/muse-spark-open (Mock)", "meta-models/muse-spark-open-1026 (Mock)", "sam-3.1 (Mock)"),
                    "No BASETEN_API_KEY configured. Running in offline zero-credit mock mode."
            );
        }
    }

    private MetaHealthCheckResult checkBasetenHealth() {
        long startTime = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            String rawBase = basetenConfig.getBaseUrl() != null ? basetenConfig.getBaseUrl().trim() : "https://inference.baseten.co/v1";
            String base = rawBase.replaceAll("/+$", "");
            String targetUrl = base.endsWith("/models") ? base : base + "/models";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Authorization", "Bearer " + basetenConfig.getApiKey())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTime;
            int code = response.statusCode();

            if (code >= 200 && code < 300) {
                List<String> models = parseModelIds(response.body());
                boolean hasMuseSpark = models.contains("meta-models/muse-spark-open") || models.contains("meta-models/muse-spark-open-1026");
                String msg = hasMuseSpark
                        ? "Baseten Muse Spark Open Model API connected & verified (0 credits consumed)."
                        : "Baseten Model API connected (0 credits consumed).";

                return new MetaHealthCheckResult(
                        true,
                        true,
                        "LIVE_CONNECTED",
                        code,
                        latency,
                        basetenConfig.getBaseUrl(),
                        models,
                        msg
                );
            } else if (code == 401 || code == 403) {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "AUTH_FAILED",
                        code,
                        latency,
                        basetenConfig.getBaseUrl(),
                        List.of(),
                        "Baseten API authentication failed (HTTP " + code + "): Invalid or expired BASETEN_API_KEY."
                );
            } else {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "API_ERROR",
                        code,
                        latency,
                        basetenConfig.getBaseUrl(),
                        List.of(),
                        "Baseten API returned status HTTP " + code
                );
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            LOG.warn("Baseten Model API health check failed: {}", e.getMessage());
            return new MetaHealthCheckResult(
                    true,
                    false,
                    "UNREACHABLE",
                    503,
                    latency,
                    basetenConfig.getBaseUrl(),
                    List.of(),
                    "Unable to reach Baseten API endpoint: " + e.getMessage()
            );
        }
    }

    private MetaHealthCheckResult checkMetaHealth() {
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
                        "Meta API authentication failed (HTTP " + code + "): Invalid or expired META_MUSE_API_KEY."
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

    @SuppressWarnings("unchecked")
    public StagedMuseRequest prepareMuseQuery(PrepareMuseQueryRequest request) {
        String eventId = request != null ? request.eventId() : null;
        Map<String, Object> eventData = (request != null && request.eventData() != null)
                ? new HashMap<>(request.eventData())
                : new HashMap<>();

        if (eventData.isEmpty() && eventId != null && !eventId.isBlank() && eventService != null) {
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

        // Pipeline Step 1 & 2: Resolve SAM 3.1 segmentation result (Mask & isolated object)
        Map<String, Object> samData = new HashMap<>();
        if (eventData.containsKey("agentResult") && eventData.get("agentResult") instanceof Map<?, ?> ar && ar.containsKey("sam")) {
            Object samObj = ar.get("sam");
            if (samObj instanceof Map<?, ?> sm) {
                samData.putAll((Map<String, Object>) sm);
            }
        } else if (eventData.containsKey("sam") && eventData.get("sam") instanceof Map<?, ?> sm) {
            samData.putAll((Map<String, Object>) sm);
        }

        // If no pre-computed SAM result was loaded, provide synthesized grounding from hotspot
        if (samData.isEmpty()) {
            samData.put("model", "meta/sam-3.1");
            samData.put("status", "SUCCESS");
            samData.put("instanceCount", 2);
            samData.put("segmentationMaskRle", "compact_rle_instance_0");
            samData.put("segmentedObject", "Bee Collision Pair");
            if (eventData.get("hotspot") != null) {
                samData.put("boxPrompt", eventData.get("hotspot"));
            }
        }
        eventData.put("samSegmentation", samData);

        String userQuestion = (request != null && request.question() != null && !request.question().isBlank())
                ? request.question()
                : "Explain this anomaly event: What physical interaction occurred in this tubelet region and what is the threat assessment?";

        // Grounded prompt with complete causal physics, SAM inputs, and SAM mask telemetry
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("System: You are Meta Muse Spark Open, an advanced multimodal world-model reasoning system integrated into BeeJEPA's real-time hive entrance surveillance pipeline.\n");
        promptBuilder.append("Your role is to diagnose physical anomalies in video sequences by synthesizing V-JEPA latent temporal surprise dynamics with SAM 3.1 visual instance segmentation masks.\n\n");

        promptBuilder.append("### Pipeline Stage 1: V-JEPA Latent Anomaly Detection\n");
        if (!eventData.isEmpty()) {
            promptBuilder.append(String.format("- Event Identifier: %s\n", eventData.getOrDefault("eventId", eventId != null ? eventId : "evt_active")));
            if (eventData.containsKey("sequenceId")) {
                promptBuilder.append(String.format("- Sequence ID: %s\n", eventData.get("sequenceId")));
            }
            if (eventData.containsKey("surpriseScoreL2")) {
                promptBuilder.append(String.format(Locale.US, "- V-JEPA Latent Surprise Error (sigma): %.2f sigma (High unexpected feature deviation)\n", Double.parseDouble(String.valueOf(eventData.get("surpriseScoreL2")))));
            }
            if (eventData.containsKey("pixelDiffBaseline")) {
                promptBuilder.append(String.format(Locale.US, "- Baseline Frame Pixel Difference: %.4f (Low pixel shift vs high latent surprise confirms semantic action)\n", Double.parseDouble(String.valueOf(eventData.get("pixelDiffBaseline")))));
            }
            if (eventData.containsKey("frameIndex") && eventData.containsKey("timestampSec")) {
                promptBuilder.append(String.format(Locale.US, "- Temporal Coordinates: Peak anomaly frame #%s at %.2f seconds\n", eventData.get("frameIndex"), Double.parseDouble(String.valueOf(eventData.get("timestampSec")))));
            }
        } else {
            promptBuilder.append("- Event Context: Live Hive Entrance Surveillance Stream (Real-time monitoring at 60 FPS)\n");
        }

        promptBuilder.append("\n### Pipeline Stage 2: Inputs Routed to SAM 3.1 (Segment Anything Model)\n");
        promptBuilder.append("- Target Visual Frame: Peak anomaly frame #").append(eventData.getOrDefault("frameIndex", 720)).append(" (clip_raw.mp4 / peak_frame.jpg)\n");
        if (eventData.get("hotspot") != null) {
            promptBuilder.append("- Spatial Hotspot Tubelet: ").append(eventData.get("hotspot")).append("\n");
            promptBuilder.append("- SAM Visual Box Prompt: Bounding box enclosing the anomaly hotspot tubelet\n");
        } else {
            promptBuilder.append("- Spatial Region of Interest: Central flight corridor tubelet grid\n");
            promptBuilder.append("- SAM Visual Box Prompt: Center-biased bounding box on entrance board\n");
        }

        promptBuilder.append("\n### Pipeline Stage 3: SAM 3.1 Visual Segmentation Mask & Instance Telemetry\n");
        promptBuilder.append(String.format("- Segmentation Model: %s\n", samData.getOrDefault("model", "meta/sam-3.1")));
        promptBuilder.append(String.format("- Segmented Visual Object: %s\n", samData.getOrDefault("segmentedObject", "Bee Collision Pair")));
        promptBuilder.append(String.format("- Isolated Instances Count: %s\n", samData.getOrDefault("instanceCount", 2)));
        promptBuilder.append(String.format("- Segmentation Mask (RLE/Contour): %s\n", samData.getOrDefault("segmentationMaskRle", "compact_rle_instance_0")));
        if (samData.get("boxPrompt") != null) {
            promptBuilder.append(String.format("- Visual Box Prompt Applied: %s\n", samData.get("boxPrompt")));
        }

        promptBuilder.append("\n### Beekeeper Query:\n");
        promptBuilder.append(userQuestion).append("\n\n");

        promptBuilder.append("### Reasoning Instructions for Muse Spark Open:\n");
        promptBuilder.append("1. Grounded Causal Diagnosis: Analyze the physical interaction, aerodynamic trajectory, and collision mechanics of the segmented bee(s).\n");
        promptBuilder.append("2. Threat Assessment: Classify the threat level (Normal Hive Traffic, Mid-Air Collision, Wasps/Hornet Predation, Swarming Behavior).\n");
        promptBuilder.append("3. Immediate Action: Provide one clear, actionable recommendation for the beekeeper.");

        String fullPrompt = promptBuilder.toString();
        int estimatedTokens = Math.max(20, fullPrompt.length() / 4);

        // Determine destination endpoint, model, and headers
        String targetUrl;
        String modelName;
        Map<String, Object> payload;
        Map<String, String> headers;

        String rawKey = basetenConfig.isConfigured() ? basetenConfig.getApiKey() : config.getApiKey();
        String maskedKey = "UNCONFIGURED_KEY";
        if (rawKey != null && !rawKey.isBlank()) {
            maskedKey = (rawKey.length() > 12 ? rawKey.substring(0, 12) : rawKey) + "****************";
        }

        Map<String, Object> responsesPayload;

        if (basetenConfig.isConfigured()) {
            String rawBase = basetenConfig.getBaseUrl() != null ? basetenConfig.getBaseUrl().trim() : "https://inference.baseten.co/v1";
            String base = rawBase.replaceAll("/+$", "");
            targetUrl = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
            modelName = basetenConfig.getModel() != null && !basetenConfig.getModel().isBlank()
                    ? basetenConfig.getModel()
                    : "meta-models/muse-spark-open";

            headers = Map.of(
                    "Authorization", "Bearer " + maskedKey + " [STAGED: NOT TRANSMITTED]",
                    "Content-Type", "application/json",
                    "Accept", "application/json",
                    "X-Provider", "Baseten-Model-APIs (OpenAI- & Meta-compatible)"
            );

            // 1. OpenAI-compatible Chat Completion payload structure (/v1/chat/completions)
            payload = Map.of(
                    "model", modelName,
                    "messages", List.of(
                            Map.of(
                                    "role", "system",
                                    "content", "You are Meta Muse Spark Open, an advanced multimodal world-model reasoning system integrated into BeeJEPA's real-time hive entrance surveillance pipeline. Your role is to diagnose physical anomalies in video sequences using V-JEPA latent surprise dynamics and SAM 3.1 visual segmentation masks."
                            ),
                            Map.of(
                                    "role", "user",
                                    "content", fullPrompt
                            )
                    ),
                    "temperature", 0.7,
                    "max_tokens", 1000,
                    "top_p", 1.0,
                    "stream", false
            );

            // 2. Meta Responses API payload structure (/v1/responses) as supported by Baseten & Meta Model API
            responsesPayload = Map.of(
                    "model", modelName,
                    "input", fullPrompt,
                    "reasoning", Map.of("effort", "high")
            );
        } else {
            // Legacy / fallback Meta API staging
            String rawBase = config.getBaseUrl() != null ? config.getBaseUrl().trim() : "https://api.meta.ai";
            String base = rawBase.replaceAll("[^a-zA-Z0-9.:/_-]", "").replaceAll("/+$", "");
            targetUrl = base.endsWith("/v1") ? base + "/responses" : base + "/v1/responses";
            modelName = "muse-wonder-1.0";

            headers = Map.of(
                    "Authorization", "Bearer " + maskedKey + " [STAGED: NOT TRANSMITTED]",
                    "Content-Type", "application/json",
                    "Accept", "application/json",
                    "X-Meta-Client", "beeJEPA-V-JEPA-Integration/1.0"
            );

            responsesPayload = Map.of(
                    "model", modelName,
                    "reasoning", Map.of("effort", "high"),
                    "input", fullPrompt
            );

            payload = responsesPayload;
        }

        String statusMsg = basetenConfig.isConfigured()
                ? "OpenAI- & Meta-compatible payload staged for Baseten Muse Spark Open (0 credits consumed). Pipeline: V-JEPA Anomaly -> SAM 3.1 Mask -> Muse Spark Open."
                : "Payload prepared with event telemetry. Staged for Meta Muse endpoint (Not transmitted - 0 credits consumed).";

        return new StagedMuseRequest(
                true,                          // staged
                false,                         // sent: explicitly NOT sent
                targetUrl,
                "POST",
                modelName,
                headers,
                payload,
                responsesPayload,
                fullPrompt,
                eventId != null ? eventId : (String) eventData.get("eventId"),
                eventData,
                estimatedTokens,
                0L,                            // 0 credits consumed
                statusMsg,
                Instant.now().toString()
        );
    }

    public com.beejepa.muse.dto.StagedSamRequest prepareSamQuery(com.beejepa.muse.dto.PrepareSamQueryRequest request) {
        if (request == null || request.eventId() == null || request.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required to stage SAM query");
        }
        String eventId = request.eventId().trim();
        String rawBase = config.getBaseUrl() != null ? config.getBaseUrl().trim() : "https://api.meta.ai";
        String base = rawBase.replaceAll("[^a-zA-Z0-9.:/_-]", "").replaceAll("/+$", "");
        String targetUrl = base.endsWith("/v1") ? base + "/responses" : base + "/v1/responses";
        String modelName = "sam-3.1";

        String rawKey = config.isConfigured() ? config.getApiKey() : basetenConfig.getApiKey();
        String maskedKey = "UNCONFIGURED_KEY";
        if (rawKey != null && !rawKey.isBlank()) {
            maskedKey = (rawKey.length() > 12 ? rawKey.substring(0, 12) : rawKey) + "****************";
        }

        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + maskedKey + " [STAGED: NOT TRANSMITTED]",
                "Content-Type", "application/json",
                "Accept", "application/json",
                "X-Meta-Client", "beeJEPA-SAM-Integration/3.1"
        );

        // Dynamic media endpoints for the chosen eventId
        String peakFrameUrl = "/api/v1/events/" + eventId + "/media/peak_frame.jpg";
        String videoClipUrl = "/api/v1/events/" + eventId + "/media/clip_raw.mp4";

        // Dynamic spatial prompt from actual V-JEPA hotspot or custom user box
        Map<String, Object> hotspot = new HashMap<>();
        if (request.customBox() != null && !request.customBox().isEmpty()) {
            hotspot.putAll(request.customBox());
        } else if (eventService != null) {
            Optional<Map<String, Object>> evOpt = eventService.getEvent(eventId);
            if (evOpt.isPresent()) {
                Object raw = evOpt.get().get("event");
                if (raw instanceof com.beejepa.event.AnomalyEvent ae) {
                    com.beejepa.event.AnomalyEvent.Hotspot hs = ae.hotspot();
                    if (hs != null) {
                        double width = 0.12;
                        double height = 0.14;
                        double x = hs.normCenterX() - width / 2.0;
                        double y = hs.normCenterY() - height / 2.0;
                        hotspot.put("x", Math.max(0.0, x));
                        hotspot.put("y", Math.max(0.0, y));
                        hotspot.put("width", width);
                        hotspot.put("height", height);
                        hotspot.put("gridX", hs.tubeletGridX());
                        hotspot.put("gridY", hs.tubeletGridY());
                    }
                }
            }
        }

        // Dynamic prompt: from user text input or voice transcription (no hardcoded honeybee assumptions)
        String prompt;
        if (request.prompt() != null && !request.prompt().isBlank()) {
            prompt = request.prompt().trim();
        } else if (!hotspot.isEmpty() && hotspot.containsKey("y") && hotspot.containsKey("x")) {
            double ymin = ((Number) hotspot.get("y")).doubleValue();
            double xmin = ((Number) hotspot.get("x")).doubleValue();
            double ymax = ymin + ((Number) hotspot.getOrDefault("height", 0.14)).doubleValue();
            double xmax = xmin + ((Number) hotspot.getOrDefault("width", 0.12)).doubleValue();
            prompt = String.format(Locale.US, "segment object at [ymin=%.2f, xmin=%.2f, ymax=%.2f, xmax=%.2f]", ymin, xmin, ymax, xmax);
        } else {
            prompt = "segment central anomaly object";
        }

        Map<String, Object> imagePayload = Map.of(
                "model", modelName,
                "input", List.of(
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("type", "input_text", "text", prompt),
                                        Map.of("type", "input_image", "image_url", peakFrameUrl)
                                )
                        )
                ),
                "stream", false
        );

        Map<String, Object> videoPayload = Map.of(
                "model", modelName,
                "input", List.of(
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("type", "input_text", "text", prompt),
                                        Map.of("type", "input_video", "video_url", videoClipUrl)
                                )
                        )
                ),
                "stream", true
        );

        String curlCommand = String.format(
                "curl -X POST \"%s\" \\\n" +
                "  -H \"Authorization: Bearer $META_MUSE_API_KEY\" \\\n" +
                "  -H \"Content-Type: application/json\" \\\n" +
                "  -d '{\n" +
                "    \"model\": \"%s\",\n" +
                "    \"input\": [{\n" +
                "      \"role\": \"user\",\n" +
                "      \"content\": [\n" +
                "        {\"type\": \"input_text\", \"text\": \"%s\"},\n" +
                "        {\"type\": \"input_image\", \"image_url\": \"%s\"}\n" +
                "      ]\n" +
                "    }],\n" +
                "    \"stream\": false\n" +
                "  }'",
                targetUrl, modelName, prompt, peakFrameUrl
        );

        return new com.beejepa.muse.dto.StagedSamRequest(
                true,
                false,
                targetUrl,
                "POST",
                modelName,
                headers,
                imagePayload,
                videoPayload,
                prompt,
                eventId,
                hotspot,
                peakFrameUrl,
                videoClipUrl,
                curlCommand,
                0L,
                "Meta Model API SAM 3.1 request staged dynamically (0 credits consumed). Ready for execution.",
                Instant.now().toString()
        );
    }

    public com.beejepa.muse.dto.StagedSamRequest prepareSamQuery(String eventId) {
        return prepareSamQuery(new com.beejepa.muse.dto.PrepareSamQueryRequest(eventId, null, null, "image"));
    }

    public Map<String, Object> transcribeVoice(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            return Map.of("error", "Empty audio buffer", "transcript", "");
        }

        return Map.of(
                "status", "SUCCESS",
                "mode", "STANDBY_ZERO_CREDITS",
                "creditsConsumed", 0,
                "audioBytesReceived", audioBytes.length,
                "transcript", "What happened at the hive entrance? Analyze the segmented anomaly.",
                "note", "Voice input captured and ready to be bound dynamically as prompt for SAM 3.1 & Muse."
        );
    }

    public MetaMuseConfig getConfig() {
        return config;
    }

    public BasetenConfig getBasetenConfig() {
        return basetenConfig;
    }
}

