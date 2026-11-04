package com.beejepa.controller;

import com.beejepa.event.EventService;
import com.beejepa.muse.AudioSpectralAnalyzer;
import com.beejepa.muse.BeeAcousticSynthesizer;
import com.beejepa.muse.MetaMuseService;
import com.beejepa.muse.MuseDiskCache;
import com.beejepa.muse.ResponseLatencyModel;
import com.beejepa.muse.SamGeometry;
import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import com.beejepa.state.model.MuseLogEntry;
import com.beejepa.state.StateRepository;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Controller("/api/v1/muse")
@ExecuteOn(TaskExecutors.BLOCKING)
public class MuseController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MuseController.class);

    private final MetaMuseService museService;
    private final BeeAcousticSynthesizer acousticSynthesizer;
    private final AudioSpectralAnalyzer spectralAnalyzer;
    private final ResponseLatencyModel latencyModel;
    private final EventService eventService;
    private final StateRepository stateRepository;
    private final com.beejepa.muse.MetaVoiceTranscribeClient voiceClient;
    private final Map<String, CompletableFuture<byte[]>> inFlightTts = new ConcurrentHashMap<>();

    @jakarta.inject.Inject
    public MuseController(MetaMuseService museService,
            BeeAcousticSynthesizer acousticSynthesizer,
            AudioSpectralAnalyzer spectralAnalyzer,
            ResponseLatencyModel latencyModel,
            EventService eventService,
            StateRepository stateRepository,
            com.beejepa.muse.MetaVoiceTranscribeClient voiceClient) {
        this.museService = museService;
        this.acousticSynthesizer = acousticSynthesizer;
        this.spectralAnalyzer = spectralAnalyzer;
        this.latencyModel = latencyModel;
        this.eventService = eventService;
        this.stateRepository = stateRepository;
        this.voiceClient = voiceClient;
    }

    public MuseController(MetaMuseService museService,
            BeeAcousticSynthesizer acousticSynthesizer,
            AudioSpectralAnalyzer spectralAnalyzer,
            ResponseLatencyModel latencyModel) {
        this(museService, acousticSynthesizer, spectralAnalyzer, latencyModel, null, null, null);
    }

    @Post(value = "/acoustic-mirror", consumes = { "audio/wav", "application/octet-stream",
            "audio/*" }, produces = "audio/wav")
    public HttpResponse<byte[]> acousticMirror(@Body byte[] inputAudioBytes) {
        if (inputAudioBytes == null || inputAudioBytes.length == 0) {
            return HttpResponse.badRequest();
        }

        AudioSpectralAnalyzer.SpectralProfile profile = spectralAnalyzer.analyzeAudio(inputAudioBytes, 16000);
        long delayMs = latencyModel.applyConversationalPause();
        byte[] responseWav = acousticSynthesizer.synthesizeFromSpectralProfile(profile);

        boolean isQuestion = profile.pitchTiltHz() > 25.0f;
        String prosody = isQuestion ? "Question (Rising)" : "Statement (Cadence)";

        return HttpResponse.ok(responseWav)
                .header("Content-Type", "audio/wav")
                .header("X-Bee-Syllables", String.valueOf(profile.estimatedSyllables()))
                .header("X-Bee-Centroid-Hz", String.format(java.util.Locale.US, "%.1f", profile.meanCentroidHz()))
                .header("X-Bee-Prosody", prosody)
                .header("X-Bee-Latency-Ms", String.valueOf(delayMs))
                .header("Cache-Control", "no-cache");
    }

    @Get("/health")
    public com.beejepa.muse.dto.MetaHealthCheckResult checkHealth() {
        return museService.checkHealth();
    }

    @Get("/status")
    public Map<String, Object> getStatus() {
        com.beejepa.muse.dto.MetaHealthCheckResult health = museService.checkHealth();
        Map<String, Object> statusMap = new LinkedHashMap<>();
        statusMap.put("configured", health.configured());
        statusMap.put("liveConnection", health.liveConnection());
        statusMap.put("baseUrl", health.baseUrl());
        statusMap.put("status", health.status());
        statusMap.put("statusCode", health.statusCode());
        statusMap.put("latencyMs", health.latencyMs());
        statusMap.put("availableModels", health.availableModels());
        statusMap.put("creditsConsumed", 0);
        statusMap.put("message", health.message());
        statusMap.put("thread", Thread.currentThread().toString());
        if (museService.getDiskCache() != null) {
            statusMap.put("cacheBackend", museService.getDiskCache().getCacheBackendDescription());
            statusMap.put("redisActive", museService.getDiskCache().isRedisActive());
        }
        if (museService.getResilientExecutor() != null) {
            statusMap.put("resilience", museService.getResilientExecutor().getResilienceMetrics());
        }
        return statusMap;
    }

    @Get(value = "/voice", produces = "audio/wav")

    public HttpResponse<byte[]> getBeeVoice(@QueryValue(defaultValue = "") String text) {
        if (text == null || text.isBlank()) {
            return HttpResponse.badRequest();
        }
        byte[] wavBytes = acousticSynthesizer.synthesizeWav(text.trim());
        return HttpResponse.ok(wavBytes)
                .header("Content-Type", "audio/wav")
                .header("Cache-Control", "no-cache");
    }

    @Get(value = "/tts", produces = "audio/mpeg")
    public HttpResponse<byte[]> getBriefingTts(@QueryValue(defaultValue = "") String text) {
        String spokenText = normalizeSpokenText(text);
        if (spokenText.isBlank()) {
            return HttpResponse.badRequest();
        }
        String hash = computeTtsHash(spokenText);

        MuseDiskCache cache = museService != null ? museService.getDiskCache() : null;
        if (cache != null) {
            Optional<byte[]> cachedAudio = cache.getTtsAudio(hash);
            if (cachedAudio.isPresent() && cachedAudio.get().length > 512 && !isRiffWav(cachedAudio.get())) {
                return HttpResponse.ok(cachedAudio.get())
                        .header("Content-Type", "audio/mpeg")
                        .header("X-Cache", cache.getCacheBackendDescription())
                        .header("Cache-Control", "public, max-age=86400");
            }
        }

        byte[] audioBytes = synthesizeOrJoinTts(hash, spokenText, cache);
        if (audioBytes != null && audioBytes.length > 512) {
            return HttpResponse.ok(audioBytes)
                    .header("Content-Type", "audio/mpeg")
                    .header("X-Cache", "MISS_SYNTHESIZED")
                    .header("Cache-Control", "public, max-age=86400");
        }

        return HttpResponse.status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static String normalizeSpokenText(String text) {
        String cleanText = text != null ? text.trim() : "";
        if (cleanText.isBlank()) {
            return "";
        }
        return cleanText
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("[*`~()\\[\\]\"]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String computeTtsHash(String spokenText) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(spokenText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(spokenText.hashCode());
        }
    }

    private void warmTtsAsync(String rawText) {
        String spokenText = normalizeSpokenText(rawText);
        if (spokenText.isBlank()) {
            return;
        }
        String hash = computeTtsHash(spokenText);
        MuseDiskCache cache = museService != null ? museService.getDiskCache() : null;
        if (cache != null) {
            Optional<byte[]> cachedAudio = cache.getTtsAudio(hash);
            if (cachedAudio.isPresent() && cachedAudio.get().length > 512 && !isRiffWav(cachedAudio.get())) {
                return;
            }
        }
        Thread.startVirtualThread(() -> synthesizeOrJoinTts(hash, spokenText, cache));
    }

    private byte[] synthesizeOrJoinTts(String hash, String spokenText, MuseDiskCache cache) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inFlightTts.putIfAbsent(hash, future);
        if (existing != null) {
            try {
                return existing.get(16, TimeUnit.SECONDS);
            } catch (Exception e) {
                return null;
            }
        }

        Path tmpFile = null;
        try {
            tmpFile = Files.createTempFile("beejepa_tts_" + hash + "_", ".mp3");
            String edgeTtsBin = "./.venv/bin/edge-tts";
            if (!Files.exists(Path.of(".venv", "bin", "edge-tts"))) {
                if (Files.exists(Path.of("/opt/beejepa/.venv/bin/edge-tts"))) {
                    edgeTtsBin = "/opt/beejepa/.venv/bin/edge-tts";
                } else {
                    edgeTtsBin = "edge-tts";
                }
            }
            for (int attempt = 1; attempt <= 2; attempt++) {
                ProcessBuilder pb = new ProcessBuilder(
                        edgeTtsBin,
                        "--voice", "en-US-AvaNeural",
                        "--text=" + spokenText,
                        "--write-media", tmpFile.toAbsolutePath().toString());
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                boolean finished = proc.waitFor(15, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                }
                if (finished && proc.exitValue() == 0 && Files.exists(tmpFile) && Files.size(tmpFile) > 512) {
                    byte[] audioBytes = Files.readAllBytes(tmpFile);
                    Files.deleteIfExists(tmpFile);
                    if (cache != null) {
                        cache.saveTtsAudio(hash, spokenText, audioBytes);
                    }
                    future.complete(audioBytes);
                    return audioBytes;
                }
            }
            future.complete(null);
            return null;
        } catch (Exception e) {
            LOG.warn("edge-tts synthesis failed: {}", e.getMessage());
            future.complete(null);
            return null;
        } finally {
            inFlightTts.remove(hash, future);
            if (tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean isRiffWav(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F';
    }

    @Post("/warm")
    public HttpResponse<Map<String, Object>> warmPipeline(@Body @io.micronaut.core.annotation.Nullable Map<String, Object> payload) {
        String requestedEventId = payload != null && payload.get("eventId") != null
                ? String.valueOf(payload.get("eventId")).trim()
                : null;
        if (requestedEventId != null && (requestedEventId.isBlank() || "null".equalsIgnoreCase(requestedEventId))) {
            requestedEventId = null;
        }
        List<MetaMuseService.ManifestEventInput> eventsToWarm = resolveTopEvents(requestedEventId, 3);
        museService.warmPipelineAsync(eventsToWarm);
        return HttpResponse.accepted().body(Map.of(
                "status", "WARMING",
                "events", eventsToWarm.stream().map(MetaMuseService.ManifestEventInput::eventId).toList()));
    }

    @Post("/generate")
    public MuseInferenceResponse generate(@Body MuseInferenceRequest request) {
        return museService.callInference(request);
    }

    @Post("/query-voice")

    public HttpResponse<Map<String, Object>> queryVoice(@Body Map<String, String> payload) {
        String prompt = payload != null && payload.get("prompt") != null ? payload.get("prompt").trim() : "";
        if (prompt.isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "prompt is required"));
        }
        List<MetaMuseService.ManifestEventInput> topEvents = resolveTopEvents(null, 1);
        MetaMuseService.MuseAnswerResult answer = museService.answerBeekeeperQuestionWithMeta(prompt, topEvents, 1,
                false);
        if ("UNAVAILABLE".equals(answer.sourceMode())) {
            return HttpResponse.<Map<String, Object>>status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Meta Muse is currently unavailable.", "mode", "UNAVAILABLE"));
        }
        String spoken = answer.spokenSummary();
        warmTtsAsync(spoken);

        return HttpResponse.ok(Map.of(
                "prompt", prompt,
                "response", spoken,
                "mode", answer.sourceMode(),
                "audioEndpoint",
                "/api/v1/muse/tts?text=" + java.net.URLEncoder.encode(spoken, StandardCharsets.UTF_8)));
    }

    @Post("/prepare-query")
    public HttpResponse<com.beejepa.muse.dto.StagedMuseRequest> prepareQuery(
            @Body com.beejepa.muse.dto.PrepareMuseQueryRequest request) {
        com.beejepa.muse.dto.StagedMuseRequest staged = museService.prepareMuseQuery(request);
        return HttpResponse.ok(staged);
    }

    @Get("/events/{eventId}/sam/staged")
    public HttpResponse<com.beejepa.muse.dto.StagedSamRequest> stageSam(
            @io.micronaut.http.annotation.PathVariable String eventId) {
        return HttpResponse.ok(museService.prepareSamQuery(eventId));
    }

    @Post("/prepare-sam")
    public HttpResponse<com.beejepa.muse.dto.StagedSamRequest> prepareSam(
            @Body com.beejepa.muse.dto.PrepareSamQueryRequest request) {
        if (request == null || request.eventId() == null || request.eventId().isBlank()) {
            return HttpResponse.badRequest();
        }
        return HttpResponse.ok(museService.prepareSamQuery(request));
    }

    @Post(value = "/transcribe", consumes = { "audio/wav", "audio/*",
            "application/octet-stream" }, produces = "application/json")
    public HttpResponse<Map<String, Object>> transcribeVoice(@Body byte[] audioBytes) {
        if (voiceClient == null || !voiceClient.isConfigured()) {
            return HttpResponse.<Map<String, Object>>status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "UNAVAILABLE", "error", "Meta voice transcription is not configured."));
        }
        Thread.startVirtualThread(museService::warmBasetenConnection);
        try {
            com.beejepa.muse.MetaVoiceTranscribeClient.Transcription t = voiceClient.transcribe(audioBytes);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "SUCCESS");
            body.put("model", com.beejepa.muse.MetaVoiceTranscribeClient.MODEL);
            body.put("transcript", t.transcript());
            body.put("audioDurationMs", t.audioDurationMs());
            return HttpResponse.ok(body);
        } catch (IllegalArgumentException badAudio) {
            return HttpResponse.<Map<String, Object>>badRequest()
                    .body(Map.of("status", "INVALID_AUDIO", "error", badAudio.getMessage()));
        } catch (Exception e) {
            LOG.warn("Meta voice transcription failed: {}", e.getMessage());
            return HttpResponse.<Map<String, Object>>status(io.micronaut.http.HttpStatus.BAD_GATEWAY)
                    .body(Map.of("status", "FAILED", "error", "Transcription failed: " + e.getMessage()));
        }
    }

    @Post("/embeddings")
    public MuseInferenceResponse embeddings(@Body MuseInferenceRequest request) {
        return museService.callEmbedding(request);
    }

    /**
     * Resolves up to {@code maxEventsToTake} catalog events ordered by token-delta
     * score, placing {@code requestedEventId} first when specified.
     */
    private List<MetaMuseService.ManifestEventInput> resolveTopEvents(String requestedEventId, int maxEventsToTake) {
        List<MetaMuseService.ManifestEventInput> topEvents = new ArrayList<>();
        if (eventService == null) {
            return topEvents;
        }
        List<Map<String, Object>> rawEvents = new ArrayList<>(eventService.listEvents());
        rawEvents.sort((a, b) -> Double.compare(
                b.get("tokenDeltaScore") instanceof Number nb ? nb.doubleValue() : Double.NEGATIVE_INFINITY,
                a.get("tokenDeltaScore") instanceof Number na ? na.doubleValue() : Double.NEGATIVE_INFINITY));
        if (requestedEventId != null && !requestedEventId.isBlank()) {
            rawEvents.stream()
                    .filter(ev -> requestedEventId.equals(ev.get("eventId")))
                    .findFirst()
                    .ifPresent(ev -> topEvents.add(MetaMuseService.toManifestInput(ev)));
        }
        for (Map<String, Object> ev : rawEvents) {
            if (topEvents.size() >= maxEventsToTake)
                break;
            if (requestedEventId != null && requestedEventId.equals(ev.get("eventId")))
                continue;
            topEvents.add(MetaMuseService.toManifestInput(ev));
        }
        return topEvents;
    }

    @Post("/ask")
    public HttpResponse<Map<String, Object>> askMuse(@Body Map<String, Object> payload) {
        long startTime = System.currentTimeMillis();
        String logId = UUID.randomUUID().toString();
        String question = payload != null && payload.get("question") != null
                ? String.valueOf(payload.get("question")).trim()
                : "";
        if (question.isBlank()) {
            return HttpResponse.<Map<String, Object>>badRequest().body(Map.of("error", "question is required"));
        }
        String requestedEventId = payload.get("eventId") != null ? String.valueOf(payload.get("eventId")).trim() : null;
        if (requestedEventId != null && (requestedEventId.isBlank() || "null".equalsIgnoreCase(requestedEventId))) {
            requestedEventId = null;
        }
        int defaultMaxEvents = requestedEventId != null ? 1 : 3;
        int maxEventsToTake = payload.get("maxEvents") instanceof Number n
                ? Math.max(1, Math.min(5, n.intValue()))
                : defaultMaxEvents;

        List<MetaMuseService.ManifestEventInput> topEvents = resolveTopEvents(requestedEventId, maxEventsToTake);

        boolean forceLive = "true".equalsIgnoreCase(String.valueOf(payload.get("forceLive")))
                || Boolean.TRUE.equals(payload.get("forceLive"));
        MetaMuseService.MuseAnswerResult answerResult = museService.answerBeekeeperQuestionWithMeta(
                question, topEvents, maxEventsToTake, forceLive);
        String reportMarkdown = answerResult.reportMarkdown();
        String spokenSummary = answerResult.spokenSummary();
        if (!"UNAVAILABLE".equals(answerResult.sourceMode())) {
            warmTtsAsync(spokenSummary);
        }
        List<SamGeometry.EventSpatialSummary> summaries = museService.analyzeEventsInParallel(topEvents);

        long latency = System.currentTimeMillis() - startTime;
        if (stateRepository != null) {
            stateRepository.logMuseCall(new MuseLogEntry(
                    logId,
                    "/api/v1/muse/ask",
                    question,
                    "UNAVAILABLE".equals(answerResult.sourceMode()) ? 503 : 200,
                    latency,
                    Instant.now()));
        }

        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("logId", logId);
        telemetry.put("latencyMs", latency);
        telemetry.put("eventCount", summaries.size());
        telemetry.put("offlineLocked", !museService.isAllowLiveInference());
        telemetry.put("sourceMode", answerResult.sourceMode());
        telemetry.put("degradedFallback", answerResult.degradedFallback());
        if (museService.getDiskCache() != null) {
            telemetry.put("cacheBackend", museService.getDiskCache().getCacheBackendDescription());
            telemetry.put("redisActive", museService.getDiskCache().isRedisActive());
        }

        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("question", question);
        responseMap.put("report", reportMarkdown);
        responseMap.put("spokenSummary", spokenSummary);
        responseMap.put("events", summaries);
        responseMap.put("offlineLocked", !museService.isAllowLiveInference());
        responseMap.put("sourceMode", answerResult.sourceMode());
        responseMap.put("degradedFallback", answerResult.degradedFallback());
        responseMap.put("telemetry", telemetry);

        if ("UNAVAILABLE".equals(answerResult.sourceMode())) {
            responseMap.put("error", "Meta Muse is currently unavailable. No answer was generated.");
            return HttpResponse.<Map<String, Object>>status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(responseMap);
        }
        return HttpResponse.ok(responseMap);
    }
}
