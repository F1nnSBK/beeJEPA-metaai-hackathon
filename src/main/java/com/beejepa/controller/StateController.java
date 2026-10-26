package com.beejepa.controller;

import com.beejepa.state.StateService;
import com.beejepa.state.model.ProcessingRun;
import com.beejepa.state.model.SystemState;
import com.beejepa.storage.StorageService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.List;
import java.util.Map;

@Controller("/api/v1")
@ExecuteOn(TaskExecutors.BLOCKING)
public class StateController {

    private final StateService stateService;
    private final StorageService storageService;

    public StateController(StateService stateService, StorageService storageService) {
        this.stateService = stateService;
        this.storageService = storageService;
    }

    @Get("/state")
    public List<SystemState> getAllState() {
        return stateService.getAllState();
    }

    @Get("/state/{key}")
    public HttpResponse<String> getState(@PathVariable String key) {
        return stateService.getState(key)
                .map(HttpResponse::ok)
                .orElse(HttpResponse.notFound());
    }

    @Put("/state/{key}")
    public HttpResponse<Void> setState(@PathVariable String key, @Body String value) {
        stateService.setState(key, value);
        return HttpResponse.ok();
    }

    @Get("/runs")
    public List<ProcessingRun> getRecentRuns() {
        return stateService.getRecentRuns(20);
    }

    @Post("/runs")
    public ProcessingRun recordRun(@Body Map<String, String> body) {
        String taskType = body.getOrDefault("taskType", "general_task");
        String details = body.getOrDefault("details", "");
        return stateService.recordRunStart(taskType, details);
    }

    @Get("/storage")
    public Map<String, Object> getStorageStatus() {
        return storageService.getStorageStatus();
    }
}
