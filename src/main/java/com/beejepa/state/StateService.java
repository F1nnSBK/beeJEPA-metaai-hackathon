package com.beejepa.state;

import com.beejepa.Application;
import com.beejepa.state.model.ProcessingRun;
import com.beejepa.state.model.SystemState;
import com.beejepa.storage.StorageService;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Singleton
@ExecuteOn(TaskExecutors.BLOCKING)
public class StateService {

    private final StateRepository repository;
    private final StorageService storageService;

    public StateService(StateRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    public void setState(String key, String value) {
        repository.set(key, value);
    }

    public Optional<String> getState(String key) {
        return repository.get(key).map(SystemState::value);
    }

    public List<SystemState> getAllState() {
        return repository.getAll();
    }

    public ProcessingRun recordRunStart(String taskType, String details) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ProcessingRun run = new ProcessingRun(runId, taskType, "RUNNING", details, now, now);
        repository.saveRun(run);
        return run;
    }

    public ProcessingRun updateRunStatus(String runId, String taskType, String status, String details, Instant createdAt) {
        ProcessingRun run = new ProcessingRun(runId, taskType, status, details, createdAt, Instant.now());
        repository.saveRun(run);
        return run;
    }

    public List<ProcessingRun> getRecentRuns(int limit) {
        return repository.getRecentRuns(limit);
    }

    public Map<String, Object> getFullSystemOverview() {
        Map<String, Object> overview = new HashMap<>();
        overview.put("storage", storageService.getStorageStatus());
        overview.put("stateEntries", repository.getAll());
        overview.put("recentRuns", repository.getRecentRuns(5));
        overview.put("recentMuseLogs", repository.getRecentMuseLogs(5));
        overview.put("virtualThreadSupported", Application.isVirtualThreadsSupported());
        overview.put("currentExecutionThread", Thread.currentThread().toString());
        return overview;
    }
}
