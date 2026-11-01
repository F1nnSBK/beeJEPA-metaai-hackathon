package com.beejepa.muse;

import com.openai.errors.OpenAIServiceException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Resilient API execution coordinator providing exponential backoff with jitter,
 * automatic primary-to-secondary API key failover, and circuit-breaker protection.
 */
@Singleton
public class ResilientApiExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientApiExecutor.class);

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    @FunctionalInterface
    public interface KeyedOperation<T> {
        T execute(String apiKey, int attempt) throws Exception;
    }

    public static class HttpStatusException extends RuntimeException {
        private final int statusCode;
        private final String responseBody;

        public HttpStatusException(int statusCode, String message, String responseBody) {
            super(message);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int statusCode() {
            return statusCode;
        }

        public String responseBody() {
            return responseBody;
        }
    }

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final int circuitFailureThreshold;
    private final long circuitOpenDurationMs;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenedAtMs = new AtomicLong(0);
    private final AtomicReference<CircuitState> circuitState = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicLong totalRetriesExecuted = new AtomicLong(0);
    private final AtomicLong totalKeyFailovers = new AtomicLong(0);

    public ResilientApiExecutor() {
        this(3, 350L, 3500L, 5, 20_000L);
    }

    public ResilientApiExecutor(
            int maxAttempts,
            long baseDelayMs,
            long maxDelayMs,
            int circuitFailureThreshold,
            long circuitOpenDurationMs
    ) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(10L, baseDelayMs);
        this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
        this.circuitFailureThreshold = Math.max(2, circuitFailureThreshold);
        this.circuitOpenDurationMs = Math.max(500L, circuitOpenDurationMs);
    }

    public <T> T executeWithRetryAndFailover(
            String operationName,
            String primaryKey,
            String fallbackKey,
            KeyedOperation<T> operation
    ) {
        checkCircuitAllowed(operationName);

        String activeKey = (primaryKey != null && !primaryKey.isBlank()) ? primaryKey : fallbackKey;
        boolean hasFallback = fallbackKey != null && !fallbackKey.isBlank() && !fallbackKey.equals(activeKey);
        boolean switchedToFallback = false;

        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = operation.execute(activeKey, attempt);
                onSuccess();
                return result;
            } catch (Exception e) {
                lastException = e;
                int statusCode = extractStatusCode(e);

                // Immediate key failover on 401, 403, or 429 when a distinct fallback key is configured
                if ((statusCode == 401 || statusCode == 403 || statusCode == 429) && hasFallback && !switchedToFallback) {
                    switchedToFallback = true;
                    activeKey = fallbackKey;
                    totalKeyFailovers.incrementAndGet();
                    LOG.warn("[ResilientApiExecutor] {} received HTTP {} on primary key (attempt {}). Failing over to secondary API key...",
                            operationName, statusCode, attempt);
                    try {
                        T fallbackResult = operation.execute(activeKey, attempt);
                        onSuccess();
                        return fallbackResult;
                    } catch (Exception fallbackEx) {
                        lastException = fallbackEx;
                        statusCode = extractStatusCode(fallbackEx);
                    }
                }

                boolean retryable = isRetryable(lastException, statusCode);
                if (!retryable || attempt >= maxAttempts) {
                    break;
                }

                totalRetriesExecuted.incrementAndGet();
                long backoffMs = computeBackoffWithJitter(attempt);
                LOG.warn("[ResilientApiExecutor] {} transient failure on attempt {}/{} (status={}, err={}). Retrying in {} ms...",
                        operationName, attempt, maxAttempts, statusCode, lastException.getMessage(), backoffMs);
                sleepQuietly(backoffMs);
            }
        }

        onFailure(operationName, lastException);
        if (lastException instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(operationName + " failed after " + maxAttempts + " attempts: "
                + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    private void checkCircuitAllowed(String operationName) {
        CircuitState current = circuitState.get();
        if (current == CircuitState.OPEN) {
            long elapsed = System.currentTimeMillis() - circuitOpenedAtMs.get();
            if (elapsed >= circuitOpenDurationMs) {
                circuitState.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
                LOG.info("[ResilientApiExecutor] Circuit transitioned OPEN -> HALF_OPEN for {}", operationName);
            } else {
                throw new IllegalStateException("Circuit breaker OPEN for " + operationName
                        + " (cooldown remaining: " + (circuitOpenDurationMs - elapsed) + "ms)");
            }
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
        circuitState.set(CircuitState.CLOSED);
    }

    private void onFailure(String operationName, Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= circuitFailureThreshold) {
            circuitOpenedAtMs.set(System.currentTimeMillis());
            circuitState.set(CircuitState.OPEN);
            LOG.error("[ResilientApiExecutor] Circuit breaker OPENED after {} consecutive failures (last op: {}, err: {})",
                    failures, operationName, e != null ? e.getMessage() : "n/a");
        }
    }

    public static int extractStatusCode(Throwable t) {
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof OpenAIServiceException oai) {
                return oai.statusCode();
            }
            if (curr instanceof HttpStatusException hse) {
                return hse.statusCode();
            }
            String msg = curr.getMessage();
            if (msg != null) {
                if (msg.contains("HTTP 429") || msg.contains("status 429")) return 429;
                if (msg.contains("HTTP 401") || msg.contains("status 401")) return 401;
                if (msg.contains("HTTP 403") || msg.contains("status 403")) return 403;
                if (msg.contains("HTTP 500") || msg.contains("status 500")) return 500;
                if (msg.contains("HTTP 502") || msg.contains("status 502")) return 502;
                if (msg.contains("HTTP 503") || msg.contains("status 503")) return 503;
                if (msg.contains("HTTP 504") || msg.contains("status 504")) return 504;
            }
            curr = curr.getCause();
        }
        return -1;
    }

    public static boolean isRetryable(Throwable t, int statusCode) {
        if (statusCode == 408 || statusCode == 429 || statusCode == 500
                || statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return true;
        }
        if (statusCode == 400 || statusCode == 401 || statusCode == 403 || statusCode == 404 || statusCode == 422) {
            return false;
        }
        Throwable curr = t;
        while (curr != null) {
            if (curr instanceof HttpTimeoutException || curr instanceof IOException) {
                return true;
            }
            curr = curr.getCause();
        }
        return statusCode < 0;
    }

    private long computeBackoffWithJitter(int attempt) {
        long exp = baseDelayMs * (1L << Math.min(6, attempt - 1));
        long capped = Math.min(maxDelayMs, exp);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(25L, capped / 3));
        return Math.min(maxDelayMs, capped + jitter);
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public CircuitState getCircuitState() {
        if (circuitState.get() == CircuitState.OPEN) {
            long elapsed = System.currentTimeMillis() - circuitOpenedAtMs.get();
            if (elapsed >= circuitOpenDurationMs) {
                circuitState.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
            }
        }
        return circuitState.get();
    }

    public void resetCircuit() {
        consecutiveFailures.set(0);
        circuitState.set(CircuitState.CLOSED);
    }

    public Map<String, Object> getResilienceMetrics() {
        return Map.of(
                "circuitState", getCircuitState().name(),
                "consecutiveFailures", consecutiveFailures.get(),
                "totalRetriesExecuted", totalRetriesExecuted.get(),
                "totalKeyFailovers", totalKeyFailovers.get(),
                "timestamp", Instant.now().toString()
        );
    }
}

