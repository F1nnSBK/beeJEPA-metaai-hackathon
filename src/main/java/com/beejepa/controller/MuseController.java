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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
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

    @Post(value = "/acoustic-mirror", consumes = {"audio/wav", "application/octet-stream", "audio/*"}, produces = "audio/wav")
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
    public HttpResponse<byte[]> getBeeVoice(@QueryValue(defaultValue = "Brood nest stable at thirty-four degrees. Foraging traffic normal.") String text) {
        byte[] wavBytes = acousticSynthesizer.synthesizeWav(text);
        return HttpResponse.ok(wavBytes)
                .header("Content-Type", "audio/wav")
                .header("Cache-Control", "no-cache");
    }

    @Get(value = "/tts", produces = "audio/mpeg")
    public HttpResponse<byte[]> getBriefingTts(@QueryValue(defaultValue = "") String text) {
        String cleanText = text != null ? text.trim() : "";
        if (cleanText.isBlank()) {
            return HttpResponse.badRequest();
        }

        String spokenText = cleanText
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("[*`~()\\[\\]\"]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(spokenText.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            hash = sb.toString();
        } catch (Exception e) {
            hash = Integer.toHexString(spokenText.hashCode());
        }

        MuseDiskCache cache = museService != null ? museService.getDiskCache() : null;
        if (cache != null) {
            Optional<byte[]> cachedAudio = cache.getTtsAudio(hash);
            if (cachedAudio.isPresent() && cachedAudio.get().length > 512) {
                return HttpResponse.ok(cachedAudio.get())
                        .header("Content-Type", "audio/mpeg")
                        .header("X-Cache", cache.getCacheBackendDescription())
                        .header("Cache-Control", "public, max-age=86400");
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
                        "--text", spokenText,
                        "--write-media", tmpFile.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                }
                if (finished && proc.exitValue() == 0 && Files.exists(tmpFile) && Files.size(tmpFile) > 512) {
                    byte[] audioBytes = Files.readAllBytes(tmpFile);
                    Files.deleteIfExists(tmpFile);
                    if (cache != null) {
                        cache.saveTtsAudio(hash, spokenText, audioBytes);
                    }
                    return HttpResponse.ok(audioBytes)
                            .header("Content-Type", "audio/mpeg")
                            .header("X-Cache", "MISS_SYNTHESIZED")
                            .header("Cache-Control", "public, max-age=86400");
                }
            }
        } catch (Exception e) {
            LOG.warn("edge-tts synthesis failed ({}); falling back to live acoustic synthesizer.", e.getMessage());
        } finally {
            if (tmpFile != null) {
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (Exception ignored) {
                }
            }
        }

        if (acousticSynthesizer != null) {
            byte[] synthesized = acousticSynthesizer.synthesizeWav(spokenText);
            if (synthesized != null && synthesized.length > 512) {
                if (cache != null) {
                    cache.saveTtsAudio(hash, spokenText, synthesized);
                }
                return HttpResponse.ok(synthesized)
                        .header("Content-Type", "audio/mpeg")
                        .header("X-Cache", "MISS_ACOUSTIC_SYNTH")
                        .header("Cache-Control", "public, max-age=86400");
            }
        }

        return HttpResponse.notFound();
    }

    @Post("/generate")
    public MuseInferenceResponse generate(@Body MuseInferenceRequest request) {
        return museService.callInference(request);
    }

    @Post("/query-voice")
    public Map<String, Object> queryVoice(@Body Map<String, String> payload) {
        String prompt = payload != null ? payload.getOrDefault("prompt", "How are my bees doing?") : "How are my bees doing?";
        List<MetaMuseService.ManifestEventInput> topEvents = resolveTopEvents(null, 1);
        MetaMuseService.MuseAnswerResult answer = museService.answerBeekeeperQuestionWithMeta(prompt, topEvents, 1, false);
        String spoken = answer.spokenSummary();

        return Map.of(
                "prompt", prompt,
                "response", spoken,
                "mode", answer.sourceMode(),
                "audioEndpoint", "/api/v1/muse/tts?text=" + java.net.URLEncoder.encode(spoken, StandardCharsets.UTF_8)
        );
    }

    @Post("/prepare-query")
    public HttpResponse<com.beejepa.muse.dto.StagedMuseRequest> prepareQuery(@Body com.beejepa.muse.dto.PrepareMuseQueryRequest request) {
        com.beejepa.muse.dto.StagedMuseRequest staged = museService.prepareMuseQuery(request);
        return HttpResponse.ok(staged);
    }

    @Get("/events/{eventId}/sam/staged")
    public HttpResponse<com.beejepa.muse.dto.StagedSamRequest> stageSam(@io.micronaut.http.annotation.PathVariable String eventId) {
        return HttpResponse.ok(museService.prepareSamQuery(eventId));
    }

    @Post("/prepare-sam")
    public HttpResponse<com.beejepa.muse.dto.StagedSamRequest> prepareSam(@Body com.beejepa.muse.dto.PrepareSamQueryRequest request) {
        if (request == null || request.eventId() == null || request.eventId().isBlank()) {
            return HttpResponse.badRequest();
        }
        return HttpResponse.ok(museService.prepareSamQuery(request));
    }

    @Post(value = "/transcribe", consumes = {"audio/wav", "audio/*", "application/octet-stream"}, produces = "application/json")
    public HttpResponse<Map<String, Object>> transcribeVoice(@Body byte[] audioBytes) {
        if (voiceClient == null || !voiceClient.isConfigured()) {
            return HttpResponse.<Map<String, Object>>status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "UNAVAILABLE", "error", "Meta voice transcription is not configured."));
        }
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

    private List<MetaMuseService.ManifestEventInput> resolveTopEvents(String requestedEventId, int maxEventsToTake) {
        List<MetaMuseService.ManifestEventInput> topEvents = new ArrayList<>();
        if (eventService == null) {
            return topEvents;
        }
        boolean specificEventRequested = requestedEventId != null && !requestedEventId.isBlank();
        List<Map<String, Object>> rawEvents = new ArrayList<>(eventService.listEvents());

        if (specificEventRequested) {
            rawEvents.sort((a, b) -> {
                String idA = (String) a.get("eventId");
                String idB = (String) b.get("eventId");
                if (requestedEventId.equals(idA)) return -1;
                if (requestedEventId.equals(idB)) return 1;
                return 0;
            });
        } else {
            List<String> canonicalIds = List.of(
                    "evt_rec2125_seq000_f0000",
                    "evt_rec2125_seq000_f1217",
                    "evt_rec2125_seq001_f0320"
            );

            rawEvents.sort((a, b) -> {
                String idA = (String) a.get("eventId");
                String idB = (String) b.get("eventId");
                int idxA = canonicalIds.indexOf(idA);
                int idxB = canonicalIds.indexOf(idB);
                if (idxA >= 0 && idxB >= 0) return Integer.compare(idxA, idxB);
                if (idxA >= 0) return -1;
                if (idxB >= 0) return 1;
                return 0;
            });
        }

        for (Map<String, Object> ev : rawEvents) {
            if (topEvents.size() >= maxEventsToTake) break;
            String eventId = (String) ev.get("eventId");
            String formattedTime = (String) ev.getOrDefault("formattedTime", "00:00");
            double surprise = ev.get("surpriseScoreL2") instanceof Number n ? n.doubleValue() : 0.0;
            String severity = (String) ev.getOrDefault("severity", "MEDIUM");

            com.beejepa.event.AnomalyEvent.Hotspot hs = ev.get("hotspot") instanceof com.beejepa.event.AnomalyEvent.Hotspot h ? h : null;
            Double ncx = hs != null ? hs.normCenterX() : null;
            Double ncy = hs != null ? hs.normCenterY() : null;
            int[] bb = hs != null ? hs.boundingBoxPx() : null;

            Path keyframePath = eventService.findExactEventFrameFile(eventId)
                    .map(File::toPath)
                    .orElse(null);

            topEvents.add(new MetaMuseService.ManifestEventInput(
                    eventId,
                    formattedTime,
                    surprise,
                    severity,
                    keyframePath,
                    ncx,
                    ncy,
                    bb
            ));
        }
        return topEvents;
    }

    @Post("/ask")
    public HttpResponse<Map<String, Object>> askMuse(@Body Map<String, Object> payload) {
        long startTime = System.currentTimeMillis();
        String logId = UUID.randomUUID().toString();
        String question = payload != null && payload.get("question") != null
                ? String.valueOf(payload.get("question"))
                : "What happened at my hive today?";
        String requestedEventId = payload != null && payload.get("eventId") != null
                ? String.valueOf(payload.get("eventId"))
                : null;
        boolean specificEventRequested = requestedEventId != null && !requestedEventId.isBlank();
        int maxEventsToTake = specificEventRequested ? 1 : 3;

        List<MetaMuseService.ManifestEventInput> topEvents = resolveTopEvents(requestedEventId, maxEventsToTake);

        boolean forceLive = payload != null && ("true".equalsIgnoreCase(String.valueOf(payload.get("forceLive")))
                || Boolean.TRUE.equals(payload.get("forceLive")));
        MetaMuseService.MuseAnswerResult answerResult = museService.answerBeekeeperQuestionWithMeta(
                question, topEvents, maxEventsToTake, forceLive);
        List<SamGeometry.EventSpatialSummary> summaries = museService.analyzeEventsInParallel(topEvents);
        String reportMarkdown = answerResult.reportMarkdown();
        String spokenSummary = answerResult.spokenSummary();

        long latency = System.currentTimeMillis() - startTime;
        if (stateRepository != null) {
            stateRepository.logMuseCall(new MuseLogEntry(
                    logId,
                    "/api/v1/muse/ask",
                    question,
                    200,
                    latency,
                    Instant.now()
            ));
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

        return HttpResponse.ok(responseMap);
    }
}
