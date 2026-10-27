package com.beejepa;

import com.beejepa.state.StateService;
import com.beejepa.storage.StorageService;
import io.micronaut.runtime.EmbeddedApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

@MicronautTest
class ApplicationTest {

    @Inject
    EmbeddedApplication<?> application;

    @Inject
    StateService stateService;

    @Inject
    StorageService storageService;

    @Inject
    com.beejepa.controller.MuseController museController;

    @Inject
    @io.micronaut.http.client.annotation.Client("/")
    io.micronaut.http.client.HttpClient httpClient;

    @Test
    void testSequenceVideoStreaming() {
        var req = io.micronaut.http.HttpRequest.GET("/api/v1/sequences/clip_00_rec2125_seq000_00_00_to_00_30.mp4")
                .header("Range", "bytes=0-100");
        var resp = httpClient.toBlocking().exchange(req, byte[].class);
        Assertions.assertTrue(resp.getStatus().getCode() == 200 || resp.getStatus().getCode() == 206);
    }

    @Test
    void testMuseControllerWiring() {
        Assertions.assertNotNull(museController);
    }

    @Test
    void testItWorks() {
        Assertions.assertTrue(application.isRunning());
    }

    @Test
    void testStatePersistence() {
        stateService.setState("test_key", "bee_value_123");
        var val = stateService.getState("test_key");
        Assertions.assertTrue(val.isPresent());
        Assertions.assertEquals("bee_value_123", val.get());
    }

    @Test
    void testStorageStatus() {
        Map<String, Object> status = storageService.getStorageStatus();
        Assertions.assertNotNull(status);
        Assertions.assertTrue(status.containsKey("sqliteDatabasePath"));
    }
}
