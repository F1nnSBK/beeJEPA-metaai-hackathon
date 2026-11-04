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
import io.micronaut.serde.annotation.Serdeable;
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
import com.beejepa.muse.SamGeometry.BoundingBox;
import com.beejepa.muse.SamGeometry.ContainedBee;
import com.beejepa.muse.SamGeometry.EventSpatialSummary;
import com.beejepa.muse.VisualCropRenderer.RenderedVisuals;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonSchemaLocalValidation;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Singleton
@ExecuteOn(TaskExecutors.BLOCKING)
public class MetaMuseService {

    private static final Logger LOG = LoggerFactory.getLogger(MetaMuseService.class);
    private static final String BASETEN_MODEL = "meta-models/muse-spark-open-1026";
    private static final Pattern COMMENTARY_BLOCK = Pattern
            .compile(
                    "<\\|start\\|>assistant to=(?:commentary|final|user)[^<]*<\\|message\\|>(.*?)(?:<\\|eom\\|>|<\\|eot\\|>|$)",
                    Pattern.DOTALL);
    private static final Pattern INLINE_TOOL_BLOCK = Pattern
            .compile(
                    "<\\|start\\|>assistant to=[^<]*QuerySam31Concept[^<]*<\\|message\\|>\\s*(\\{.*?\\})\\s*(?:<\\|eom\\|>|<\\|eot\\|>|$)",
                    Pattern.DOTALL);
    private static final Pattern INTERNAL_TAG_BLOCK = Pattern
            .compile("<\\|start\\|>assistant to=[^<]+<\\|message\\|>.*?(?:<\\|eom\\|>|<\\|eot\\|>|$)", Pattern.DOTALL);

    private final MetaMuseClient client;
    private final MetaMuseConfig config;
    private final BasetenConfig basetenConfig;
    private final StateRepository stateRepository;
    private final com.beejepa.event.EventService eventService;
    private final Sam31Client sam31Client;
    private final ObjectMapper objectMapper;
    private final OpenAIClient basetenClient;
    private final String primaryApiKey;
    private final String fallbackApiKey;
    private final MuseDiskCache diskCache;
    private final ResilientApiExecutor resilientExecutor;
    private final com.beejepa.perception.ActivePerceptionService perceptionService;
    private final boolean allowLiveInference;
    private final long simulatedOfflineDelayMs;
    private final Map<String, EventSpatialSummary> spatialCache = new ConcurrentHashMap<>();

    @Serdeable
    public record MuseAnswerResult(
            String reportMarkdown,
            String spokenSummary,
            String sourceMode,
            boolean degradedFallback) {
        public MuseAnswerResult(
                String rawCombinedOrMarkdown,
                String sourceMode,
                boolean degradedFallback) {
            this(
                    TolerantPayloadParser.parseDualMuseOutput(rawCombinedOrMarkdown).displayReport(),
                    TolerantPayloadParser.parseDualMuseOutput(rawCombinedOrMarkdown).spokenSummary(),
                    sourceMode,
                    degradedFallback);
        }
    }

    @Inject
    public MetaMuseService(MetaMuseClient client,
            MetaMuseConfig config,
            @io.micronaut.core.annotation.Nullable BasetenConfig basetenConfig,
            StateRepository stateRepository,
            com.beejepa.event.EventService eventService,
            @io.micronaut.core.annotation.Nullable Sam31Client sam31Client,
            @io.micronaut.core.annotation.Nullable ObjectMapper objectMapper,
            @io.micronaut.core.annotation.Nullable MuseDiskCache diskCache,
            @io.micronaut.core.annotation.Nullable ResilientApiExecutor resilientExecutor,
            @io.micronaut.core.annotation.Nullable com.beejepa.perception.ActivePerceptionService perceptionService) {
        this.client = client;
        this.config = config;
        this.basetenConfig = basetenConfig != null ? basetenConfig : new BasetenConfig();
        this.stateRepository = stateRepository;
        this.eventService = eventService;
        this.objectMapper = objectMapper != null ? objectMapper
                : com.beejepa.config.JacksonFactory.createTolerantMapper();
        this.resilientExecutor = resilientExecutor != null ? resilientExecutor : new ResilientApiExecutor();
        this.sam31Client = sam31Client != null ? sam31Client
                : new Sam31Client(this.objectMapper, this.resilientExecutor);
        this.diskCache = diskCache != null ? diskCache : new MuseDiskCache(this.objectMapper);
        this.perceptionService = perceptionService;

        String envLive = Optional.ofNullable(System.getProperty("ALLOW_LIVE_API"))
                .or(() -> Optional.ofNullable(System.getenv("ALLOW_LIVE_API")))
                .orElse(null);
        this.allowLiveInference = envLive != null
                ? "true".equalsIgnoreCase(envLive)
                : this.basetenConfig.isAllowInferenceCalls();
        this.simulatedOfflineDelayMs = Long.parseLong(
                Optional.ofNullable(getEnvOrProp("UX_SIMULATE_LATENCY_MS")).orElse("450"));

        String primaryKey = Optional.ofNullable(getEnvOrProp("BASETEN_API_KEY1"))
                .or(() -> Optional.ofNullable(getEnvOrProp("BASETEN_API_KEY")))
                .or(() -> Optional.ofNullable(this.basetenConfig.getApiKey()))
                .orElse("");
        this.primaryApiKey = primaryKey;
        this.fallbackApiKey = Optional.ofNullable(getEnvOrProp("BASETEN_API_KEY2"))
                .or(() -> Optional.ofNullable(this.basetenConfig.getFallbackApiKey()))
                .orElse("");
        String baseUrl = Optional.ofNullable(getEnvOrProp("BASETEN_BASE_URL"))
                .or(() -> Optional.ofNullable(this.basetenConfig.getBaseUrl()))
                .orElse("https://inference.baseten.co/v1");

        LOG.info("MetaMuseService initialized: allowLiveInference={}, primaryKeyConfigured={}, baseUrl={}",
                this.allowLiveInference, !primaryKey.isBlank(), baseUrl);

        this.basetenClient = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(primaryKey.isBlank() ? "dummy-key-standby" : primaryKey)
                .timeout(Duration.ofSeconds(90))
                .maxRetries(1)
                .disableTelemetry(true)
                .build();
    }

    public MetaMuseService(MetaMuseClient client,
            MetaMuseConfig config,
            @io.micronaut.core.annotation.Nullable BasetenConfig basetenConfig,
            StateRepository stateRepository,
            com.beejepa.event.EventService eventService,
            @io.micronaut.core.annotation.Nullable Sam31Client sam31Client,
            @io.micronaut.core.annotation.Nullable ObjectMapper objectMapper,
            @io.micronaut.core.annotation.Nullable MuseDiskCache diskCache) {
        this(client, config, basetenConfig, stateRepository, eventService, sam31Client, objectMapper, diskCache,
                new ResilientApiExecutor(), null);
    }

    private static String getEnvOrProp(String key) {
        String val = Sam31Client.getEnvOrDotEnv(key);
        return val.isBlank() ? null : val;
    }

    public MetaMuseService(MetaMuseClient client,
            MetaMuseConfig config,
            BasetenConfig basetenConfig,
            StateRepository stateRepository,
            com.beejepa.event.EventService eventService,
            Sam31Client sam31Client,
            ObjectMapper objectMapper) {
        this(client, config, basetenConfig, stateRepository, eventService, sam31Client, objectMapper,
                new MuseDiskCache(objectMapper != null ? objectMapper : new ObjectMapper()));
    }

    public MetaMuseService(MetaMuseClient client,
            MetaMuseConfig config,
            BasetenConfig basetenConfig,
            StateRepository stateRepository,
            com.beejepa.event.EventService eventService) {
        this(client, config, basetenConfig, stateRepository, eventService, new Sam31Client(new ObjectMapper()),
                new ObjectMapper());
    }

    public MetaMuseService(MetaMuseClient client,
            MetaMuseConfig config,
            StateRepository stateRepository,
            com.beejepa.event.EventService eventService) {
        this(client, config, new BasetenConfig(), stateRepository, eventService);
    }

    public boolean isAllowLiveInference() {
        return allowLiveInference;
    }

    public MuseDiskCache getDiskCache() {
        return diskCache;
    }

    public MuseInferenceResponse callInference(MuseInferenceRequest request) {
        long startTime = System.currentTimeMillis();
        String logId = UUID.randomUUID().toString();
        String endpoint = "/v1/chat/completions";
        String promptKey = "infer:" + (request.model() != null ? request.model() : BASETEN_MODEL) + ":"
                + request.prompt();

        Optional<String> cachedText = diskCache != null ? diskCache.getMuseResponse(promptKey) : Optional.empty();
        if (cachedText.isPresent()) {
            long latency = System.currentTimeMillis() - startTime;
            String activeModel = Optional.ofNullable(request.model())
                    .filter(m -> !m.isBlank())
                    .orElseGet(() -> Optional.ofNullable(basetenConfig.getModel()).orElse(BASETEN_MODEL));
            if (stateRepository != null) {
                stateRepository
                        .logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 200, latency, Instant.now()));
            }
            return new MuseInferenceResponse(
                    "cache-" + UUID.randomUUID(),
                    activeModel,
                    List.of(),
                    cachedText.get(),
                    Map.of("status", "cache_hit", "cacheBackend", diskCache.getCacheBackendDescription(), "latencyMs",
                            latency));
        }

        if (!basetenConfig.isConfigured() && !config.isConfigured()) {
            throw new IllegalStateException("Neither Baseten nor Meta Muse API key is configured for live inference.");
        }

        try {
            if (basetenConfig.isConfigured()) {
                String activeModel = Optional.ofNullable(request.model())
                        .filter(m -> !m.isBlank())
                        .orElseGet(() -> Optional.ofNullable(basetenConfig.getModel()).orElse(BASETEN_MODEL));
                ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                        .model(activeModel)
                        .maxCompletionTokens(2048)
                        .addSystemMessage(getEffectiveSystemPrompt())
                        .addUserMessage(request.prompt())
                        .build();
                ChatCompletion completion = executeWithKeyFailover(params);
                String text = cleanMuseOutput(completion.choices().getFirst().message().content().orElse(""));
                if (diskCache != null && !text.isBlank()) {
                    diskCache.saveMuseResponse(promptKey, text);
                }
                long latency = System.currentTimeMillis() - startTime;
                if (stateRepository != null) {
                    stateRepository.logMuseCall(
                            new MuseLogEntry(logId, endpoint, request.prompt(), 200, latency, Instant.now()));
                }
                return new MuseInferenceResponse(
                        completion.id(),
                        activeModel,
                        List.of(),
                        text,
                        Map.of("status", "live_success", "provider", "baseten", "latencyMs", latency));
            }

            String authHeader = "Bearer " + config.getApiKey();
            HttpResponse<MuseInferenceResponse> response = client.infer(authHeader, request);
            long latency = System.currentTimeMillis() - startTime;

            int statusCode = response.getStatus().getCode();
            if (stateRepository != null) {
                stateRepository.logMuseCall(
                        new MuseLogEntry(logId, endpoint, request.prompt(), statusCode, latency, Instant.now()));
            }

            if (response.getBody().isPresent()) {
                MuseInferenceResponse body = response.getBody().get();
                if (diskCache != null && body.outputText() != null && !body.outputText().isBlank()) {
                    diskCache.saveMuseResponse(promptKey, body.outputText());
                }
                return body;
            } else {
                throw new RuntimeException("Empty response body from Meta Muse API");
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            LOG.error("Meta Muse API call failed: {}", e.getMessage());
            if (stateRepository != null) {
                stateRepository
                        .logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 500, latency, Instant.now()));
            }
            throw new RuntimeException("Meta Muse API call failed: " + e.getMessage(), e);
        }
    }

    public MuseInferenceResponse callEmbedding(MuseInferenceRequest request) {
        long startTime = System.currentTimeMillis();
        String logId = UUID.randomUUID().toString();
        String endpoint = "/v1/models/muse/embeddings";

        if (!config.isConfigured()) {
            throw new IllegalStateException("Meta Muse API key is not configured for live embeddings.");
        }

        try {
            String authHeader = "Bearer " + config.getApiKey();
            HttpResponse<MuseInferenceResponse> response = client.embed(authHeader, request);
            long latency = System.currentTimeMillis() - startTime;

            int statusCode = response.getStatus().getCode();
            if (stateRepository != null) {
                stateRepository.logMuseCall(
                        new MuseLogEntry(logId, endpoint, request.prompt(), statusCode, latency, Instant.now()));
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
                stateRepository
                        .logMuseCall(new MuseLogEntry(logId, endpoint, request.prompt(), 500, latency, Instant.now()));
            }
            throw new RuntimeException("Meta Muse embedding API call failed: " + e.getMessage(), e);
        }
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
                    "UNCONFIGURED",
                    503,
                    0,
                    basetenConfig.getBaseUrl(),
                    List.of(),
                    "No BASETEN_API_KEY or META_MUSE_API_KEY configured.");
        }
    }

    private MetaHealthCheckResult checkBasetenHealth() {
        long startTime = System.currentTimeMillis();
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            String rawBase = basetenConfig.getBaseUrl() != null ? basetenConfig.getBaseUrl().trim()
                    : "https://inference.baseten.co/v1";
            String base = rawBase.replaceAll("/+$", "");
            String targetUrl = base.endsWith("/models") ? base : base + "/models";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .header("Authorization", "Bearer " + basetenConfig.getApiKey())
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - startTime;
            int code = response.statusCode();

            if ((code == 401 || code == 403) && !basetenConfig.getFallbackApiKey().isBlank()
                    && !basetenConfig.getFallbackApiKey().equals(basetenConfig.getApiKey())) {
                LOG.info("Baseten primary API key returned HTTP {}. Retrying with secondary API key fallback...", code);
                request = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .header("Authorization", "Bearer " + basetenConfig.getFallbackApiKey())
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(6))
                        .GET()
                        .build();
                response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                latency = System.currentTimeMillis() - startTime;
                code = response.statusCode();
            }

            if (code >= 200 && code < 300) {
                List<String> models = parseModelIds(response.body());
                boolean hasMuseSpark = models.contains("meta-models/muse-spark-open")
                        || models.contains("meta-models/muse-spark-open-1026");
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
                        msg);
            } else if (code == 401 || code == 403) {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "AUTH_FAILED",
                        code,
                        latency,
                        basetenConfig.getBaseUrl(),
                        List.of(),
                        "Baseten API authentication failed (HTTP " + code + "): Invalid or expired BASETEN_API_KEY.");
            } else {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "API_ERROR",
                        code,
                        latency,
                        basetenConfig.getBaseUrl(),
                        List.of(),
                        "Baseten API returned status HTTP " + code);
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
                    "Unable to reach Baseten API endpoint: " + e.getMessage());
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

            java.net.http.HttpResponse<String> response = httpClient.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
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
                        "Successfully connected to Meta Model API (0 credits consumed).");
            } else if (code == 401 || code == 403) {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "AUTH_FAILED",
                        code,
                        latency,
                        config.getBaseUrl(),
                        List.of(),
                        "Meta API authentication failed (HTTP " + code + "): Invalid or expired META_MUSE_API_KEY.");
            } else {
                return new MetaHealthCheckResult(
                        true,
                        false,
                        "API_ERROR",
                        code,
                        latency,
                        config.getBaseUrl(),
                        List.of(),
                        "Meta API returned status HTTP " + code);
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
                    "Unable to reach Meta API endpoint: " + e.getMessage());
        }
    }

    private List<String> parseModelIds(String json) {
        List<String> models = new ArrayList<>();
        if (json == null || json.isBlank())
            return models;
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            models.add(m.group(1));
        }
        return models;
    }

    /**
     * Builds the exact Muse request that {@link #answerBeekeeperQuestionWithMeta}
     * would send for this event,
     * without transmitting it. Only already measured SAM results are included (no
     * live calls here);
     * image parts are listed with their size but their base64 payload is omitted
     * from the preview.
     */
    public StagedMuseRequest prepareMuseQuery(PrepareMuseQueryRequest request) {
        String eventId = request != null ? request.eventId() : null;
        String question = (request != null && request.question() != null && !request.question().isBlank())
                ? request.question().trim()
                : "";
        List<ManifestEventInput> inputs = new ArrayList<>();
        if (eventId != null && !eventId.isBlank() && eventService != null) {
            eventService.getEvent(eventId).ifPresent(ev -> inputs.add(toManifestInput(ev)));
        }
        List<EventSpatialSummary> summaries = inputs.stream().map(in -> {
            EventSpatialSummary s = spatialCache.get(in.eventId());
            if (s == null) {
                s = diskCache.loadSpatialSummary(in.eventId()).orElse(null);
            }
            return s != null ? s
                    : new EventSpatialSummary(in.eventId(), in.formattedTime(), in.surpriseScore(),
                            in.severity(), -1, -1, "not_segmented_yet", List.of(), List.of(), "", "");
        }).toList();

        Map<String, ManifestEventInput> inputsById = new HashMap<>();
        inputs.forEach(in -> inputsById.put(in.eventId(), in));
        List<Map<String, Object>> digest = buildDigest(summaries, inputsById);
        String headerText;
        try {
            headerText = buildHeaderText(question, digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize digest", e);
        }

        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", headerText));
        for (EventSpatialSummary s : summaries) {
            if (s.cropBase64() != null && !s.cropBase64().isBlank()) {
                userContent.add(Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:image/jpeg;base64,<" + s.cropBase64().length()
                                + " base64 chars omitted in preview>")));
            }
        }

        String activeModel = Optional.ofNullable(getEnvOrProp("BASETEN_MUSE_MODEL"))
                .or(() -> Optional.ofNullable(basetenConfig.getModel()))
                .orElse(BASETEN_MODEL);
        String rawBase = basetenConfig.getBaseUrl() != null ? basetenConfig.getBaseUrl().trim()
                : "https://inference.baseten.co/v1";
        String base = rawBase.replaceAll("/+$", "");
        String targetUrl = base.endsWith("/chat/completions") ? base : base + "/chat/completions";

        Map<String, Object> samToolSchema = Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "QuerySam31Concept",
                        "description",
                        "Calls Meta SAM 3.1 on the event's peak frame to count instances of a short noun concept (e.g. 'bee', 'wasp').",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "event_id", Map.of("type", "string"),
                                        "concept_prompt", Map.of("type", "string"),
                                        "include_baseline_bee_count", Map.of("type", "boolean")),
                                "required", List.of("event_id", "concept_prompt", "include_baseline_bee_count"))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", activeModel);
        payload.put("max_completion_tokens", 4096);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", getEffectiveSystemPrompt()),
                Map.of("role", "user", "content", userContent)));
        payload.put("tools", List.of(samToolSchema));

        String rawKey = basetenConfig.getApiKey();
        String maskedKey = (rawKey != null && !rawKey.isBlank())
                ? (rawKey.length() > 12 ? rawKey.substring(0, 12) : rawKey) + "****************"
                : "UNCONFIGURED_KEY";
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + maskedKey + " [STAGED: NOT TRANSMITTED]",
                "Content-Type", "application/json");

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("digest", digest);
        return new StagedMuseRequest(
                true,
                false,
                targetUrl,
                "POST",
                activeModel,
                headers,
                payload,
                null,
                headerText,
                eventId,
                eventData,
                Math.max(1, (getEffectiveSystemPrompt().length() + headerText.length()) / 4),
                0L,
                "Preview of the exact request sent on 'Ask' (not transmitted).",
                Instant.now().toString());
    }

    /** Maps an EventService event map onto the input record used for SAM + Muse. */
    @SuppressWarnings("unchecked")
    public static ManifestEventInput toManifestInput(Map<String, Object> ev) {
        com.beejepa.event.AnomalyEvent.Hotspot hs = ev
                .get("hotspot") instanceof com.beejepa.event.AnomalyEvent.Hotspot h ? h : null;
        return new ManifestEventInput(
                String.valueOf(ev.get("eventId")),
                String.valueOf(ev.getOrDefault("formattedTime", "")),
                ev.get("tokenDeltaScore") instanceof Number n ? n.doubleValue() : Double.NaN,
                ev.get("severity") != null ? String.valueOf(ev.get("severity")) : null,
                null,
                hs != null ? hs.normCenterX() : null,
                hs != null ? hs.normCenterY() : null,
                hs != null ? hs.boundingBoxPx() : null,
                ev.get("recordingId") != null ? String.valueOf(ev.get("recordingId")) : null,
                ev.get("boardPeaks") instanceof Number n ? n.intValue() : null,
                ev.get("decisionThreshold") instanceof Number n ? n.doubleValue() : null);
    }

    /**
     * Builds the exact SAM 3.1 request that {@link Sam31Client#queryConcept} sends
     * for this event's peak
     * frame, without transmitting it.
     */
    public com.beejepa.muse.dto.StagedSamRequest prepareSamQuery(com.beejepa.muse.dto.PrepareSamQueryRequest request) {
        if (request == null || request.eventId() == null || request.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required to stage SAM query");
        }
        String eventId = request.eventId().trim();
        String rawBase = config.getBaseUrl() != null && !config.getBaseUrl().isBlank() ? config.getBaseUrl().trim()
                : "https://api.meta.ai";
        String base = rawBase.replaceAll("/+$", "");
        String targetUrl = base.endsWith("/v1") ? base + "/responses" : base + "/v1/responses";
        String concept = request.prompt() != null && !request.prompt().isBlank() ? request.prompt().trim() : "bee";

        String rawKey = config.getApiKey();
        String maskedKey = (rawKey != null && !rawKey.isBlank())
                ? (rawKey.length() > 12 ? rawKey.substring(0, 12) : rawKey) + "****************"
                : "UNCONFIGURED_KEY";
        Map<String, String> headers = Map.of(
                "Authorization", "Bearer " + maskedKey + " [STAGED: NOT TRANSMITTED]",
                "Content-Type", "application/json");

        String peakFrameUrl = "/api/v1/events/" + eventId + "/media/peak_frame.jpg";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "sam-3.1");
        payload.put("input", List.of(Map.of(
                "type", "message",
                "role", "user",
                "content", List.of(
                        Map.of("type", "input_text", "text", concept),
                        Map.of("type", "input_image", "image_url",
                                "data:image/jpeg;base64,<peak frame of " + eventId + ", 688x688 top-left crop>")))));
        payload.put("metadata", Map.of("mask_encoding", "one_bit"));

        Map<String, Object> hotspot = new LinkedHashMap<>();
        if (request.customBox() != null && !request.customBox().isEmpty()) {
            hotspot.putAll(request.customBox());
        } else if (eventService != null) {
            eventService.getEvent(eventId).ifPresent(ev -> {
                if (ev.get("hotspot") instanceof com.beejepa.event.AnomalyEvent.Hotspot hs) {
                    hotspot.put("gridX", hs.tubeletGridX());
                    hotspot.put("gridY", hs.tubeletGridY());
                    hotspot.put("boxPx_y1x1y2x2", hs.boundingBoxPx());
                    hotspot.put("source", "measured_vjepa_overlay");
                }
            });
        }

        String curlCommand = "curl -X POST \"" + targetUrl + "\" \\\n"
                + "  -H \"Authorization: Bearer $META_MUSE_API_KEY\" \\\n"
                + "  -H \"Content-Type: application/json\" \\\n"
                + "  -d '{\"model\":\"sam-3.1\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":["
                + "{\"type\":\"input_text\",\"text\":\"" + concept.replace("'", "") + "\"},"
                + "{\"type\":\"input_image\",\"image_url\":\"data:image/jpeg;base64,...\"}]}],"
                + "\"metadata\":{\"mask_encoding\":\"one_bit\"}}'";

        return new com.beejepa.muse.dto.StagedSamRequest(
                true,
                false,
                targetUrl,
                "POST",
                "sam-3.1",
                headers,
                payload,
                null,
                concept,
                eventId,
                hotspot,
                peakFrameUrl,
                "/api/v1/events/" + eventId + "/media/clip_raw.mp4",
                curlCommand,
                0L,
                "Preview of the exact SAM 3.1 request (not transmitted). Bees inside the measured hotspot are counted with IoA >= 0.25.",
                Instant.now().toString());
    }

    public com.beejepa.muse.dto.StagedSamRequest prepareSamQuery(String eventId) {
        return prepareSamQuery(new com.beejepa.muse.dto.PrepareSamQueryRequest(eventId, null, null, "image"));
    }

    @Serdeable
    public record ManifestEventInput(
            String eventId,
            String formattedTime,
            double surpriseScore,
            String severity,
            Path keyframePath,
            Double normCenterX,
            Double normCenterY,
            int[] boundingBoxPx,
            String recordingId,
            Integer boardPeaks,
            Double decisionThreshold) {
        public ManifestEventInput(String eventId, String formattedTime, double surpriseScore, String severity,
                Path keyframePath) {
            this(eventId, formattedTime, surpriseScore, severity, keyframePath, null, null, null, null, null, null);
        }

        public ManifestEventInput(String eventId, String formattedTime, double surpriseScore, String severity,
                Path keyframePath,
                Double normCenterX, Double normCenterY, int[] boundingBoxPx) {
            this(eventId, formattedTime, surpriseScore, severity, keyframePath, normCenterX, normCenterY, boundingBoxPx,
                    null, null, null);
        }
    }

    @JsonTypeName("query_sam31_concept")
    @JsonClassDescription("Calls Meta SAM 3.1 Segment API on a specific event keyframe to verify and count instances of a short concrete noun concept (e.g. 'bee', 'wasp', 'fly').")
    public static class QuerySam31Concept {
        @JsonProperty("event_id")
        public String eventId;

        @JsonProperty("concept_prompt")
        public String conceptPrompt;

        @JsonProperty("include_baseline_bee_count")
        public boolean includeBaselineBeeCount;
    }

    public static final String SAM_UNAVAILABLE = "sam_unavailable";
    public static final String NO_MEASURED_HOTSPOT = "no_measured_hotspot";

    /**
     * Runs SAM 3.1 bee segmentation on the event peak frame and computes hotspot
     * containment.
     */
    public EventSpatialSummary analyzeSingleEvent(ManifestEventInput evt) {
        double score = Math.round(evt.surpriseScore() * 10000.0) / 10000.0;
        EventSpatialSummary inMemory = spatialCache.get(evt.eventId());
        if (inMemory != null) {
            return alignSummaryTimestamp(inMemory, evt.formattedTime(), score, evt.severity());
        }
        Optional<EventSpatialSummary> cachedOnDisk = diskCache.loadSpatialSummary(evt.eventId());
        if (cachedOnDisk.isPresent()) {
            EventSpatialSummary aligned = alignSummaryTimestamp(cachedOnDisk.get(), evt.formattedTime(), score,
                    evt.severity());
            spatialCache.put(evt.eventId(), aligned);
            return aligned;
        }
        BufferedImage canonical;
        try {
            Optional<byte[]> frameBytes = perceptionService != null
                    ? perceptionService.getBufferedFrameBytes(evt.eventId())
                    : Optional.empty();
            if (frameBytes.isEmpty() && eventService != null) {
                frameBytes = eventService.getOrExtractEventFrameBytes(evt.eventId());
            }
            if (frameBytes.isEmpty() || frameBytes.get().length == 0) {
                throw new IllegalStateException("no peak frame available for " + evt.eventId());
            }
            canonical = VisualCropRenderer.loadCanonicalView(frameBytes.get());
        } catch (Exception e) {
            LOG.warn("[MetaMuseService] Peak frame unavailable for {}: {}", evt.eventId(), e.getMessage());
            return unavailableSummary(evt, score, "", "");
        }

        String frameB64;
        try {
            frameB64 = VisualCropRenderer.encodeJpegBase64(canonical, 0.92f);
        } catch (Exception e) {
            return unavailableSummary(evt, score, "", "");
        }

        if (!allowLiveInference) {
            return unavailableSummary(evt, score, frameB64, "");
        }
        List<BoundingBox> boardBees;
        try {
            boardBees = sam31Client.queryConcept("bee", frameB64, canonical.getWidth(), canonical.getHeight(), true);
        } catch (Exception samEx) {
            LOG.warn("[MetaMuseService] SAM 3.1 segmentation failed for {}: {}", evt.eventId(), samEx.getMessage());
            return unavailableSummary(evt, score, frameB64, "");
        }

        BoundingBox hotspot = SamGeometry.fromManifestBoxPx(evt.boundingBoxPx(), canonical.getWidth(),
                canonical.getHeight());
        EventSpatialSummary summary;
        try {
            if (hotspot == null) {
                summary = new EventSpatialSummary(evt.eventId(), evt.formattedTime(), score, evt.severity(),
                        boardBees.size(), -1, NO_MEASURED_HOTSPOT, List.of(),
                        boardBees.stream().map(BoundingBox::toList).toList(), frameB64, "");
            } else {
                List<ContainedBee> contained = SamGeometry.findContainedBees(hotspot, boardBees);
                RenderedVisuals visuals = VisualCropRenderer.renderOverlaysAndCrop(canonical, hotspot, boardBees,
                        contained, 35);
                summary = new EventSpatialSummary(evt.eventId(), evt.formattedTime(), score, evt.severity(),
                        boardBees.size(), contained.size(), SamGeometry.classifyHotspot(contained), contained,
                        boardBees.stream().map(BoundingBox::toList).toList(),
                        visuals.canonicalFrameB64(), visuals.upscaledCropB64());
            }
        } catch (Exception renderEx) {
            LOG.warn("[MetaMuseService] Rendering SAM overlay failed for {}: {}", evt.eventId(), renderEx.getMessage());
            return unavailableSummary(evt, score, frameB64, "");
        }

        spatialCache.put(evt.eventId(), summary);
        diskCache.saveSpatialSummary(summary);
        if (perceptionService != null) {
            try {
                perceptionService.refreshTrajectory();
            } catch (Exception ignored) {
            }
        }
        return summary;
    }


    private static EventSpatialSummary alignSummaryTimestamp(EventSpatialSummary s, String formattedTime, double score,
            String severity) {
        String alignedTime = (formattedTime != null && !formattedTime.isBlank()) ? formattedTime : s.timestamp();
        double alignedScore = (!Double.isNaN(score) && score > 0.0) ? score : s.tokenDeltaScore();
        String alignedSeverity = (severity != null && !severity.isBlank()) ? severity : s.severity();
        if (Objects.equals(alignedTime, s.timestamp())
                && Double.compare(alignedScore, s.tokenDeltaScore()) == 0
                && Objects.equals(alignedSeverity, s.severity())) {
            return s;
        }
        return new EventSpatialSummary(
                s.eventId(),
                alignedTime,
                alignedScore,
                alignedSeverity,
                s.landingBoardBeeCount(),
                s.beesInsideHotspotCount(),
                s.geometricPreClassification(),
                s.containedBees(),
                s.boardBees(),
                s.frameBase64(),
                s.cropBase64());
    }

    private static EventSpatialSummary unavailableSummary(ManifestEventInput evt, double score, String frameB64,
            String cropB64) {
        return new EventSpatialSummary(evt.eventId(), evt.formattedTime(), score, evt.severity(),
                -1, -1, SAM_UNAVAILABLE, List.of(), List.of(), frameB64, cropB64);
    }

    public List<EventSpatialSummary> analyzeEventsInParallel(List<ManifestEventInput> topEvents) {
        if (topEvents == null || topEvents.isEmpty()) {
            return List.of();
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<EventSpatialSummary>> futures = topEvents.stream()
                    .map(evt -> executor.submit(() -> analyzeSingleEvent(evt)))
                    .toList();

            List<EventSpatialSummary> results = new ArrayList<>(futures.size());
            for (Future<EventSpatialSummary> f : futures) {
                results.add(f.get());
            }
            return results;
        } catch (Exception e) {
            LOG.error("[MetaMuseService] Parallel SAM 3.1 event processing failed: {}", e.getMessage());
            return topEvents.stream().map(this::analyzeSingleEvent).toList();
        }
    }

    public String answerBeekeeperQuestion(String beekeeperQuestion, List<ManifestEventInput> topEvents) {
        return answerBeekeeperQuestion(beekeeperQuestion, topEvents, 3, false);
    }

    public String answerBeekeeperQuestion(String beekeeperQuestion, List<ManifestEventInput> events, int maxEvents) {
        return answerBeekeeperQuestion(beekeeperQuestion, events, maxEvents, false);
    }

    public String answerBeekeeperQuestion(String beekeeperQuestion, List<ManifestEventInput> events, int maxEvents,
            boolean forceLive) {
        return answerBeekeeperQuestionWithMeta(beekeeperQuestion, events, maxEvents, forceLive).reportMarkdown();
    }

    public MuseAnswerResult answerBeekeeperQuestionWithMeta(
            String beekeeperQuestion,
            List<ManifestEventInput> events,
            int maxEvents,
            boolean forceLive) {
        List<ManifestEventInput> safeEvents = events != null ? events : List.of();
        List<ManifestEventInput> selectedEvents = safeEvents.stream()
                .limit(Math.max(1, maxEvents))
                .toList();

        String eventIdsKey = selectedEvents.stream().map(ManifestEventInput::eventId).reduce("", (a, b) -> a + "," + b);
        String cacheKey = MuseDiskCache.buildDeterministicKey(eventIdsKey, beekeeperQuestion, promptVersionKey());

        if (!forceLive) {
            Optional<String> cachedReport = diskCache.getMuseResponse(cacheKey);
            if (cachedReport.isPresent()) {
                return new MuseAnswerResult(cachedReport.get(), "DISK_CACHE", false);
            }
        }

        LOG.info("[MetaMuseService] LIVE QUERY to Meta Muse: question='{}', events={}",
                beekeeperQuestion, selectedEvents.stream().map(ManifestEventInput::eventId).toList());
        try {
            if (!allowLiveInference) {
                throw new IllegalStateException("Live inference disabled via configuration");
            }
            String liveReport = executeLiveBeekeeperQuery(beekeeperQuestion, selectedEvents);
            diskCache.saveMuseResponse(cacheKey, liveReport);
            return new MuseAnswerResult(liveReport, "LIVE_API", false);
        } catch (Exception e) {
            LOG.warn("[MetaMuseService] Live Meta Muse execution failed: {}", e.getMessage());
            return new MuseAnswerResult("", "", "UNAVAILABLE", true);
        }
    }

    /**
     * Computes a version tag from the active model identifier and system prompt.
     */
    String promptVersionKey() {
        String activeModel = Optional.ofNullable(getEnvOrProp("BASETEN_MUSE_MODEL"))
                .or(() -> Optional.ofNullable(basetenConfig.getModel()))
                .orElse(BASETEN_MODEL);
        return "v" + Integer.toHexString((activeModel + "\n" + getEffectiveSystemPrompt()).hashCode());
    }

    /** Builds the per-event summary digest payload for multimodal reasoning. */
    List<Map<String, Object>> buildDigest(List<EventSpatialSummary> summaries,
            Map<String, ManifestEventInput> inputsById) {
        return summaries.stream().map(s -> {
            ManifestEventInput in = inputsById.get(s.eventId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event_id", s.eventId());
            m.put("recording_id", in != null ? in.recordingId() : null);
            m.put("time_in_recording_mm_ss", s.timestamp());
            m.put("token_delta_score", Double.isNaN(s.tokenDeltaScore()) ? null : s.tokenDeltaScore());
            m.put("decision_threshold_tau_p99", in != null ? in.decisionThreshold() : null);
            m.put("above_threshold", s.severity() != null ? "ABOVE_TAU".equals(s.severity()) : null);
            m.put("vjepa_board_token_peaks", in != null ? in.boardPeaks() : null);
            m.put("sam_board_bee_count", s.landingBoardBeeCount() >= 0 ? s.landingBoardBeeCount() : null);
            m.put("sam_bees_inside_hotspot", s.beesInsideHotspotCount() >= 0 ? s.beesInsideHotspotCount() : null);
            m.put("sam_status", s.geometricPreClassification());
            return m;
        }).toList();
    }

    String buildHeaderText(String beekeeperQuestion, List<Map<String, Object>> digestList) throws Exception {
        return "Beekeeper Question: " + beekeeperQuestion + "\n\n"
                + "Event digest (" + digestList.size() + " V-JEPA peak events from the recorded dataset; "
                + "wall-clock time of day is not recorded, times are offsets within each recording; "
                + "null means the value could not be measured):\n"
                + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(digestList);
    }

    private String executeLiveBeekeeperQuery(String beekeeperQuestion, List<ManifestEventInput> topEvents) {
        List<EventSpatialSummary> summaries = analyzeEventsInParallel(topEvents);
        Map<String, ManifestEventInput> inputsById = new HashMap<>();
        for (ManifestEventInput in : topEvents) {
            inputsById.put(in.eventId(), in);
        }
        List<Map<String, Object>> digestList = buildDigest(summaries, inputsById);

        List<ChatCompletionContentPart> userParts = new ArrayList<>();
        try {
            userParts.add(ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(buildHeaderText(beekeeperQuestion, digestList))
                            .build()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        for (EventSpatialSummary s : summaries) {
            if (s.cropBase64() == null || s.cropBase64().isBlank()) {
                continue;
            }
            userParts.add(ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder()
                            .text("Hotspot crop (peak frame, measured V-JEPA hotspot, SAM bee boxes drawn) for "
                                    + s.eventId() + " at " + s.timestamp() + " into its recording (token-delta score "
                                    + s.tokenDeltaScore() + "):")
                            .build()));
            userParts.add(ChatCompletionContentPart.ofImageUrl(
                    ChatCompletionContentPartImage.builder()
                            .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                    .url("data:image/jpeg;base64," + s.cropBase64())
                                    .build())
                            .build()));
        }

        String systemPrompt = getEffectiveSystemPrompt();

        String activeModel = Optional.ofNullable(getEnvOrProp("BASETEN_MUSE_MODEL"))
                .or(() -> Optional.ofNullable(basetenConfig.getModel()))
                .orElse(BASETEN_MODEL);

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(activeModel)
                .maxCompletionTokens(8192)
                .addSystemMessage(systemPrompt)
                .addUserMessageOfArrayOfContentParts(userParts)
                .addTool(QuerySam31Concept.class, JsonSchemaLocalValidation.NO);

        ChatCompletion firstTurn = executeWithKeyFailover(paramsBuilder.build());
        if (firstTurn.choices() == null || firstTurn.choices().isEmpty()) {
            throw new IllegalStateException("Muse returned no choices");
        }
        ChatCompletionMessage assistantMsg = firstTurn.choices().getFirst().message();

        List<ChatCompletionMessageToolCall> toolCalls = assistantMsg.toolCalls()
                .stream()
                .flatMap(Collection::stream)
                .toList();

        String rawFirstContent = assistantMsg.content().orElse("");
        String firstTurnText = cleanMuseOutput(rawFirstContent);
        if (toolCalls.isEmpty()) {
            Matcher inlineToolMatcher = INLINE_TOOL_BLOCK.matcher(rawFirstContent);
            if (inlineToolMatcher.find()) {
                String inlineArgsJson = inlineToolMatcher.group(1);
                Map<String, Object> toolResult = executeSamConceptTool(inlineArgsJson, summaries);
                ChatCompletionCreateParams followUpParams = ChatCompletionCreateParams.builder()
                        .model(activeModel)
                        .maxCompletionTokens(8192)
                        .addSystemMessage(systemPrompt)
                        .addUserMessageOfArrayOfContentParts(userParts)
                        .addUserMessage("SAM 3.1 tool verification result: "
                                + writeToolJson(toolResult))
                        .build();
                ChatCompletion followUpTurn = executeWithKeyFailover(followUpParams);
                String followUpText = extractChoiceText(followUpTurn);
                if (!followUpText.isBlank()) {
                    return followUpText;
                }
            }
            if (firstTurnText.isBlank()) {
                firstTurnText = extractChoiceText(firstTurn);
            }
            if (firstTurnText.isBlank()) {
                LOG.warn(
                        "[MetaMuseService] First turn with tools returned empty text (finishReason={}); retrying direct completion without tools.",
                        firstTurn.choices().getFirst().finishReason());
                ChatCompletionCreateParams directParams = ChatCompletionCreateParams.builder()
                        .model(activeModel)
                        .maxCompletionTokens(8192)
                        .addSystemMessage(systemPrompt)
                        .addUserMessageOfArrayOfContentParts(userParts)
                        .build();
                ChatCompletion directTurn = executeWithKeyFailover(directParams);
                firstTurnText = extractChoiceText(directTurn);
            }
            if (firstTurnText.isBlank()) {
                throw new IllegalStateException("Muse returned an empty answer");
            }
            return firstTurnText;
        }

        ChatCompletionCreateParams.Builder secondTurnBuilder = ChatCompletionCreateParams.builder()
                .model(activeModel)
                .maxCompletionTokens(8192)
                .addSystemMessage(systemPrompt)
                .addUserMessageOfArrayOfContentParts(userParts)
                .addMessage(assistantMsg);

        for (ChatCompletionMessageToolCall tc : toolCalls) {
            if (!tc.isFunction()) {
                continue;
            }
            ChatCompletionMessageFunctionToolCall ftc = tc.asFunction();
            String rawArgsJson = ftc.function()._arguments().toString();
            Map<String, Object> toolResult = executeSamConceptTool(rawArgsJson, summaries);

            secondTurnBuilder.addMessage(
                    ChatCompletionToolMessageParam.builder()
                            .toolCallId(ftc.id())
                            .contentAsJson(toolResult)
                            .build());
        }

        ChatCompletion secondTurn = executeWithKeyFailover(secondTurnBuilder.build());
        if (secondTurn.choices() == null || secondTurn.choices().isEmpty()) {
            throw new IllegalStateException("Muse returned no choices after tool call");
        }
        String secondText = extractChoiceText(secondTurn);
        if (secondText.isBlank()) {
            throw new IllegalStateException("Muse returned an empty answer after tool call");
        }
        return secondText;
    }

    private Map<String, Object> executeSamConceptTool(String rawArgsJson, List<EventSpatialSummary> summaries) {
        TolerantPayloadParser.ParsedSamConceptArgs args = TolerantPayloadParser.parseSamConceptArgs(rawArgsJson,
                summaries);
        EventSpatialSummary targetEvent = summaries.stream()
                .filter(s -> s.eventId().equals(args.resolvedEventId()))
                .findFirst()
                .orElse(summaries.getFirst());

        boolean isBeeConcept = List.of("bee", "bees", "honey bee", "honeybee")
                .contains(args.conceptPrompt().trim().toLowerCase());
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("event_id", targetEvent.eventId());
        toolResult.put("queried_concept", args.conceptPrompt());
        try {
            Optional<byte[]> peak = eventService != null
                    ? eventService.getOrExtractEventFrameBytes(targetEvent.eventId())
                    : Optional.empty();
            if (peak.isEmpty()) {
                throw new IllegalStateException("peak frame unavailable");
            }
            BufferedImage clean = VisualCropRenderer.loadCanonicalView(peak.get());
            List<BoundingBox> matches = sam31Client.queryConcept(
                    args.conceptPrompt(), VisualCropRenderer.encodeJpegBase64(clean, 0.92f),
                    clean.getWidth(), clean.getHeight(), isBeeConcept);
            toolResult.put("sam_status", "SUCCESS");
            toolResult.put("matches", matches.size());
            toolResult.put("match_boxes_xyxy", matches.stream().map(BoundingBox::toList).toList());
        } catch (Exception samEx) {
            LOG.warn("[MetaMuseService] Tool SAM 3.1 concept query '{}' failed: {}", args.conceptPrompt(),
                    samEx.getMessage());
            toolResult.put("sam_status", "FAILED");
            toolResult.put("error", "SAM 3.1 could not be executed; the number of matches is unknown.");
        }
        toolResult.put("instruction",
                "Do NOT invoke further tools. Return the final response with BOTH <REPORT>...</REPORT> and <SPOKEN>...</SPOKEN>. If sam_status is FAILED, state that the check could not be performed.");
        return toolResult;
    }

    private String writeToolJson(Map<String, Object> toolResult) {
        try {
            return objectMapper.writeValueAsString(toolResult);
        } catch (Exception e) {
            return toolResult.toString();
        }
    }

    private static String extractChoiceText(ChatCompletion completion) {
        if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
            return "";
        }
        ChatCompletionMessage msg = completion.choices().getFirst().message();
        String cleaned = cleanMuseOutput(msg.content().orElse(""));
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        var rawReasoning = msg._allProperties().get("reasoning_content");
        if (rawReasoning != null) {
            String reasoningStr = rawReasoning.toString();
            if (reasoningStr.contains("<REPORT>") || reasoningStr.contains("### Assessment")) {
                int idx = reasoningStr.indexOf("<REPORT>");
                if (idx < 0) {
                    idx = reasoningStr.indexOf("### Assessment");
                }
                return cleanMuseOutput(reasoningStr.substring(idx));
            }
        }
        return "";
    }

    private ChatCompletion executeWithKeyFailover(ChatCompletionCreateParams params) {
        return resilientExecutor.executeWithRetryAndFailover(
                "BasetenMuseChatCompletion",
                primaryApiKey,
                fallbackApiKey,
                (activeKey, attempt) -> {
                    if (activeKey != null && !activeKey.isBlank() && !activeKey.equals(primaryApiKey)) {
                        return basetenClient
                                .withOptions(opts -> opts.apiKey(activeKey))
                                .chat()
                                .completions()
                                .create(params);
                    }
                    return basetenClient.chat().completions().create(params);
                });
    }

    public ResilientApiExecutor getResilientExecutor() {
        return resilientExecutor;
    }

    public static String cleanMuseOutput(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = COMMENTARY_BLOCK.matcher(raw).replaceAll("$1\n\n");
        cleaned = INTERNAL_TAG_BLOCK.matcher(cleaned).replaceAll("");
        if (cleaned.contains("</think>")) {
            cleaned = cleaned.substring(cleaned.indexOf("</think>") + 8).trim();
        }
        // Strip emojis and pictographs while preserving standard ASCII/Latin/Greek
        // symbols (sigma)
        cleaned = cleaned.replaceAll("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}]", "");
        // Strip standalone horizontal markdown divider lines (---)
        cleaned = cleaned.replaceAll("(?m)^\\s*---+\\s*$", "");
        return cleaned.trim();
    }

    public String getEffectiveSystemPrompt() {
        if (basetenConfig != null && basetenConfig.getSystemPrompt() != null
                && !basetenConfig.getSystemPrompt().isBlank()) {
            String cfgPrompt = basetenConfig.getSystemPrompt().trim();
            if (cfgPrompt.contains("<REPORT>")) {
                return cfgPrompt;
            }
        }
        String envPrompt = Optional.ofNullable(getEnvOrProp("META_MUSE_SYSTEM_PROMPT"))
                .or(() -> Optional.ofNullable(getEnvOrProp("MUSE_SYSTEM_PROMPT")))
                .orElse(null);

        if (envPrompt != null && !envPrompt.isBlank() && envPrompt.contains("<REPORT>")) {
            return envPrompt.trim();
        }

        return getDefaultSystemPrompt();
    }

    public static String getDefaultSystemPrompt() {
        return "You are BeeJEPA's apiculture reasoning engine powered by Meta Muse Spark Open. You interpret recorded hive-entrance events.\n\n"
                + "### What the data means (do not misstate it):\n"
                + "- `token_delta_score`: frame-to-frame change of frozen V-JEPA 2.1 (ViT-B/16) patch tokens. It is NOT a sigma value and NOT a prediction error. Events are compared to `decision_threshold_tau_p99`, the 99th percentile of all recorded windows.\n"
                + "- `vjepa_board_token_peaks`: number of strongly changing V-JEPA tokens on the landing board. It is NOT a bee count.\n"
                + "- `sam_board_bee_count` / `sam_bees_inside_hotspot`: real SAM 3.1 'bee' segmentation counts on the peak frame. `null` means SAM could not run; then say the count is unknown.\n"
                + "- Times are offsets within a recording. The time of day is unknown; never mention morning, afternoon or a clock time.\n"
                + "- SAM was only asked for 'bee'. Absence of other animals is NOT verified unless you call the tool and it returns sam_status SUCCESS.\n\n"
                + "### Tool Usage:\n"
                + "- Call `QuerySam31Concept` when the question concerns other organisms (e.g. 'wasp', 'hornet'). Report the real result, or that the check failed.\n\n"
                + "### Strict Formatting & Style Rules (CRITICAL):\n"
                + "1. NEVER use emojis or icons anywhere in your response.\n"
                + "2. NEVER output Markdown tables (`| col | col |`) or horizontal divider lines (`---`).\n"
                + "3. NEVER echo the user's question, NEVER print raw event IDs (`evt_rec...`), NEVER invent numbers that are not in the digest or tool results.\n"
                + "4. Write directly for an experienced beekeeper: crisp, factual, and explicit about what is measured and what is unknown.\n\n"
                + "### Mandatory Dual-Output Format (Single Message):\n"
                + "Wrap your response in `<REPORT>...</REPORT>` and `<SPOKEN>...</SPOKEN>` tags:\n"
                + "1. `<REPORT>...</REPORT>` — **Concise Visual Briefing (maximum 7 lines total, using only these 3 headings):**\n"
                + "   `### Assessment`\n"
                + "   1-2 direct sentences answering the beekeeper's question based only on the measured values.\n"
                + "   `### Evidence`\n"
                + "   1-3 short bullet points (`- **Rec <id> MM:SS** (score X.XX vs tau Y.YY): N SAM bees on board, M inside hotspot — <observation from the crop>.`).\n"
                + "   `### Action`\n"
                + "   1 clear sentence for colony management, or what to check manually if data is missing.\n"
                + "2. `<SPOKEN>...</SPOKEN>` — **Natural Spoken Narration for Audio Readout:**\n"
                + "   - 3-4 smooth sentences with the key measured numbers and the conclusion.\n"
                + "   - NO headings, NO bullets, NO Markdown symbols, NO raw event IDs, and NO digital `MM:SS` timestamps (say 'seven minutes thirty into recording 2123').";
    }

    public Map<String, EventSpatialSummary> getSpatialCache() {
        return spatialCache;
    }

    public Sam31Client getSam31Client() {
        return sam31Client;
    }

    public MetaMuseConfig getConfig() {
        return config;
    }

    public static String extractSpokenSummary(String markdownOrCombinedReport) {
        return TolerantPayloadParser.parseDualMuseOutput(markdownOrCombinedReport).spokenSummary();
    }

    public BasetenConfig getBasetenConfig() {
        return basetenConfig;
    }
}
