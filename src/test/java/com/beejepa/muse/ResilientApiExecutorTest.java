package com.beejepa.muse;

import com.beejepa.muse.ResilientApiExecutor.CircuitState;
import com.beejepa.muse.ResilientApiExecutor.HttpStatusException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

class ResilientApiExecutorTest {

    @Test
    void testSecondaryKeyFailoverOn401And429() {
        ResilientApiExecutor executor = new ResilientApiExecutor(3, 10L, 40L, 4, 5000L);
        AtomicInteger callCount = new AtomicInteger(0);

        String result = executor.executeWithRetryAndFailover(
                "muse-test",
                "primary-key-123",
                "secondary-key-456",
                (apiKey, attempt) -> {
                    int call = callCount.incrementAndGet();
                    if ("primary-key-123".equals(apiKey)) {
                        throw new HttpStatusException(429, "Rate limit exceeded on primary key", "{\"error\":\"rate_limit\"}");
                    }
                    return "OK-with-" + apiKey + "-call-" + call;
                }
        );

        Assertions.assertEquals("OK-with-secondary-key-456-call-2", result);
        Assertions.assertEquals(1L, executor.getResilienceMetrics().get("totalKeyFailovers"));
    }

    @Test
    void testRetryOnTransient503AndCircuitBreakerOpen() {
        ResilientApiExecutor executor = new ResilientApiExecutor(2, 10L, 30L, 2, 60_000L);
        AtomicInteger counter = new AtomicInteger(0);

        // First call: 2 attempts both fail with 503 -> failure #1
        Assertions.assertThrows(HttpStatusException.class, () ->
                executor.executeWithRetryAndFailover(
                        "sam-endpoint",
                        "key-1",
                        null,
                        (key, attempt) -> {
                            throw new HttpStatusException(503, "Service Unavailable", "503");
                        }
                )
        );
        Assertions.assertEquals(CircuitState.CLOSED, executor.getCircuitState());

        // Second call: 2 attempts both fail with IOException -> failure #2 -> opens circuit (threshold = 2)
        Assertions.assertThrows(RuntimeException.class, () ->
                executor.executeWithRetryAndFailover(
                        "sam-endpoint",
                        "key-1",
                        null,
                        (key, attempt) -> {
                            counter.incrementAndGet();
                            throw new IOException("Connection reset");
                        }
                )
        );
        Assertions.assertEquals(CircuitState.OPEN, executor.getCircuitState());

        // Third call: fast-fails immediately without invoking the supplier
        int beforeCalls = counter.get();
        IllegalStateException openEx = Assertions.assertThrows(IllegalStateException.class, () ->
                executor.executeWithRetryAndFailover(
                        "sam-endpoint",
                        "key-1",
                        null,
                        (key, attempt) -> {
                            counter.incrementAndGet();
                            return "Should not run";
                        }
                )
        );
        Assertions.assertTrue(openEx.getMessage().contains("Circuit breaker OPEN"));
        Assertions.assertEquals(beforeCalls, counter.get());
    }
}
