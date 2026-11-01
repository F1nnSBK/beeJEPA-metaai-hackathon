package com.beejepa.controller;

import com.beejepa.event.EventService;
import com.beejepa.muse.AudioSpectralAnalyzer;
import com.beejepa.muse.BeeAcousticSynthesizer;
import com.beejepa.muse.MetaMuseService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

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

    @jakarta.inject.Inject
    public MuseController(MetaMuseService museService,
                          BeeAcousticSynthesizer acousticSynthesizer,
                          AudioSpectralAnalyzer spectralAnalyzer,
                          ResponseLatencyModel latencyModel,
                          EventService eventService,
                          StateRepository stateRepository) {
        this.museService = museService;
        this.acousticSynthesizer = acousticSynthesizer;
        this.spectralAnalyzer = spectralAnalyzer;
        this.latencyModel = latencyModel;
        this.eventService = eventService;
        this.stateRepository = stateRepository;
    }

    public MuseController(MetaMuseService museService,
                          BeeAcousticSynthesizer acousticSynthesizer,
                          AudioSpectralAnalyzer spectralAnalyzer,
                          ResponseLatencyModel latencyModel) {
        this(museService, acousticSynthesizer, spectralAnalyzer, latencyModel, null, null);
    }

    @Post(value = "/acoustic-mirror", consumes = {"audio/wav", "application/octet-stream", "audio/*"}, produces = "audio/wav")
    public HttpResponse<byte[]> acousticMirror(@Body byte[] inputAudioBytes) {
        if (inputAudioBytes == null || inputAudioBytes.length == 0) {
            return HttpResponse.badRequest();
        }

        // 1. Fast FFT & Prosody feature extraction
        AudioSpectralAnalyzer.SpectralProfile profile = spectralAnalyzer.analyzeAudio(inputAudioBytes, 16000);

        // 2. Draw from log-normal conversational latency distribution
        long delayMs = latencyModel.applyConversationalPause();

        // 3. Synthesize bioacoustic bee response mirroring the input speech
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
        String cleanText = text != null && !text.isBlank() ? text.trim() : "";
        if (cleanText.isBlank()) {
            cleanText = "Hive entrance briefing. Flight traffic peaked at 38 bees at the start of the recording, with 15 bees clustering inside the entrance hotspot. Traffic then stabilized between 23 and 26 bees. All kinematic peaks were caused by normal multi-bee jostling and guarding at the entrance slot, with no foreign intruders detected. No intervention is required.";
        }

        String spokenText = cleanText
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("[*`~()\\[\\]\"]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        String hash;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(spokenText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            hash = sb.toString();
        } catch (Exception e) {
            hash = Integer.toHexString(spokenText.hashCode());
        }

        Path cacheDir = Path.of("data", "hackathon_sample", "cache");
        Path ttsFile = cacheDir.resolve("tts_" + hash + ".mp3");

        if (!Files.exists(ttsFile)) {
            Path tmpFile = cacheDir.resolve("tts_" + hash + ".mp3.tmp");
            try {
                Files.createDirectories(cacheDir);
                String edgeTtsBin = "./.venv/bin/edge-tts";
                if (!Files.exists(Path.of(".venv", "bin", "edge-tts"))) {
                    edgeTtsBin = "edge-tts";
                }
                for (int attempt = 1; attempt <= 2; attempt++) {
                    Files.deleteIfExists(tmpFile);
                    ProcessBuilder pb = new ProcessBuilder(
                            edgeTtsBin,
                            "--voice", "en-US-AvaNeural",
                            "--text", spokenText,
                            "--write-media", tmpFile.toAbsolutePath().toString()
                    );
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();
                    boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (!finished) {
                        proc.destroyForcibly();
                    }
                    if (finished && proc.exitValue() == 0 && Files.exists(tmpFile) && Files.size(tmpFile) > 512) {
                        try {
                            Files.move(tmpFile, ttsFile,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                            Files.move(tmpFile, ttsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                        break;
                    }
                    LOG.warn("edge-tts attempt {}/2 failed or produced invalid file. finished={}", attempt, finished);
                }
                Files.deleteIfExists(tmpFile);
            } catch (Exception e) {
                LOG.error("Failed to generate TTS via edge-tts ProcessBuilder: {}", e.getMessage(), e);
                try {
                    Files.deleteIfExists(tmpFile);
                } catch (Exception ignored) {
                }
            }
        }

        if (Files.exists(ttsFile)) {
            try {
                if (Files.size(ttsFile) <= 512) {
                    Files.deleteIfExists(ttsFile);
                    return HttpResponse.notFound();
                }
                byte[] audioBytes = Files.readAllBytes(ttsFile);
                return HttpResponse.ok(audioBytes)
                        .header("Content-Type", "audio/mpeg")
                        .header("Cache-Control", "public, max-age=86400");
            } catch (Exception e) {
                return HttpResponse.serverError();
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
        String prompt = payload != null ? payload.getOrDefault("prompt", "How are my bees doing?").toLowerCase() : "how are my bees doing?";
        String response;

        if (prompt.contains("queen") || prompt.contains("piping") || prompt.contains("swarm")) {
            response = "Queen piping confirmed in lower frames. Swarm preparation likely within twenty-four hours?";
        } else if (prompt.contains("alert") || prompt.contains("danger") || prompt.contains("wasp") || prompt.contains("bad") || prompt.contains("anomaly")) {
            response = "Alert! Rapid cluster vibration detected at tubelet four. Possible wasp intrusion!";
        } else {
            response = "Brood nest stable at thirty-four degrees. Foraging traffic high, zero threats.";
        }

        return Map.of(
                "prompt", prompt,
                "response", response,
                "creditsConsumed", 0,
                "mode", "MOCK_TEST_ZERO_CREDITS",
                "audioEndpoint", "/api/v1/muse/voice?text=" + java.net.URLEncoder.encode(response, java.nio.charset.StandardCharsets.UTF_8)
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
        return HttpResponse.ok(museService.transcribeVoice(audioBytes));
    }

    @Post("/embeddings")
    public MuseInferenceResponse embeddings(@Body MuseInferenceRequest request) {
        return museService.callEmbedding(request);
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

        List<MetaMuseService.ManifestEventInput> topEvents = new ArrayList<>();
        if (eventService != null) {
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
                // Canonical 3-event storyboard for Hive Briefing:
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

                Path keyframePath = eventService.getMediaFile(eventId, "peak_frame.jpg")
                        .map(File::toPath)
                        .orElseGet(() -> {
                            Path fallback = Path.of("data", "hackathon_sample", "event_frames", eventId + ".jpg");
                            if (Files.exists(fallback)) {
                                return fallback;
                            }
                            return null;
                        });

                if (keyframePath != null) {
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
            }
        }

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

