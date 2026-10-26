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
        return Map.of(
                "configured", museService.getConfig().isConfigured(),
                "baseUrl", museService.getConfig().getBaseUrl(),
                "mode", museService.getConfig().isConfigured() ? "LIVE" : "MOCK",
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
