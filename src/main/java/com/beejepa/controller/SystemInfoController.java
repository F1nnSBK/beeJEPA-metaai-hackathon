package com.beejepa.controller;

import com.beejepa.Application;
import com.beejepa.state.StateService;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Map;

@Controller("/api/v1")
@ExecuteOn(TaskExecutors.BLOCKING)
public class SystemInfoController {

    private final StateService stateService;

    public SystemInfoController(StateService stateService) {
        this.stateService = stateService;
    }

    @Get("/overview")
    public Map<String, Object> getOverview() {
        return stateService.getFullSystemOverview();
    }

    @Get("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "status", "UP",
                "app", "beeJEPA",
                "virtualThreads", Application.isVirtualThreadsSupported(),
                "jvm", Runtime.version().toString(),
                "currentVirtualThread", Thread.currentThread().isVirtual(),
                "threadName", Thread.currentThread().toString()
        );
    }
}
