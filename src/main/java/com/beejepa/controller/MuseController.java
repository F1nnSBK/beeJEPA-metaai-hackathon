package com.beejepa.controller;

import com.beejepa.muse.MetaMuseService;
import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Map;

@Controller("/api/v1/muse")
@ExecuteOn(TaskExecutors.BLOCKING)
public class MuseController {

    private final MetaMuseService museService;

    public MuseController(MetaMuseService museService) {
        this.museService = museService;
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

    @Post("/generate")
    public MuseInferenceResponse generate(@Body MuseInferenceRequest request) {
        return museService.callInference(request);
    }

    @Post("/embeddings")
    public MuseInferenceResponse embeddings(@Body MuseInferenceRequest request) {
        return museService.callEmbedding(request);
    }
}
