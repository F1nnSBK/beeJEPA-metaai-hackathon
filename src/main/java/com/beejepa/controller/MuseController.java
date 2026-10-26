package com.beejepa.controller;

import com.beejepa.muse.BeeAcousticSynthesizer;
import com.beejepa.muse.MetaMuseService;
import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Map;

@Controller("/api/v1/muse")
@ExecuteOn(TaskExecutors.BLOCKING)
public class MuseController {

    private final MetaMuseService museService;
    private final BeeAcousticSynthesizer acousticSynthesizer;

    public MuseController(MetaMuseService museService, BeeAcousticSynthesizer acousticSynthesizer) {
        this.museService = museService;
        this.acousticSynthesizer = acousticSynthesizer;
    }

    @Get("/status")
    public Map<String, Object> getStatus() {
        boolean configured = museService.getConfig().isConfigured();
        return Map.of(
                "configured", configured,
                "baseUrl", museService.getConfig().getBaseUrl(),
                "status", configured ? "STANDBY" : "DORMANT_OFFLINE",
                "creditsConsumed", 0,
                "message", configured ? "Key detected. Ready for manual triggers." : "No active API key set. Zero credits consumed.",
                "thread", Thread.currentThread().toString()
        );
    }

    @Get(value = "/voice", produces = "audio/wav")
    public HttpResponse<byte[]> getBeeVoice(@QueryValue(defaultValue = "Brood nest stable at thirty-four degrees. Foraging traffic normal.") String text) {
        byte[] wavBytes = acousticSynthesizer.synthesizeWav(text);
        return HttpResponse.ok(wavBytes)
                .header("Content-Type", "audio/wav")
                .header("Cache-Control", "no-cache");
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

    @Post("/embeddings")
    public MuseInferenceResponse embeddings(@Body MuseInferenceRequest request) {
        return museService.callEmbedding(request);
    }
}

