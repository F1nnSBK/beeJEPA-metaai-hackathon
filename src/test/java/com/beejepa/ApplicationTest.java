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
