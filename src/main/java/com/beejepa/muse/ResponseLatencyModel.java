package com.beejepa.muse;

import jakarta.inject.Singleton;
import java.util.Random;

/**
 * Models realistic conversational turn-taking latency using a Log-Normal distribution.
 * Simulates the biological cognitive delay of the hive responding to human vocal input,
 * preventing robotic constant-latency replies.
 */
@Singleton
public class ResponseLatencyModel {

    private final Random random = new Random();

    // Log-normal parameters calibrated for conversational turn-taking:
    // Base floor = 280ms
    // Mu = 5.4, Sigma = 0.38 yields a median of ~500ms and 95th percentile ~950ms.
    private static final double FLOOR_MS = 280.0;
    private static final double MU = 5.4;
    private static final double SIGMA = 0.36;
    private static final double MAX_CEILING_MS = 1400.0;

    /**
     * Draws a randomized latency from the log-normal conversational distribution.
     * @return delay in milliseconds
     */
    public long sampleConversationalDelayMs() {
        double z = random.nextGaussian();
        double logNormalVal = Math.exp(MU + SIGMA * z);
        double totalDelay = FLOOR_MS + logNormalVal;
        return (long) Math.max(FLOOR_MS, Math.min(MAX_CEILING_MS, totalDelay));
    }

    /**
     * Simulates natural conversational delay on the current virtual thread.
     * @return the exact elapsed delay in ms
     */
    public long applyConversationalPause() {
        long delayMs = sampleConversationalDelayMs();
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return delayMs;
    }
}
