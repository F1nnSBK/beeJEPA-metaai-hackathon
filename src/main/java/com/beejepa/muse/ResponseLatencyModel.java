package com.beejepa.muse;

import jakarta.inject.Singleton;

/**
 * Deterministic zero-delay latency model (no artificial random sleep).
 */
@Singleton
public class ResponseLatencyModel {

    public long sampleConversationalDelayMs() {
        return 0L;
    }

    public long applyConversationalPause() {
        return 0L;
    }
}
