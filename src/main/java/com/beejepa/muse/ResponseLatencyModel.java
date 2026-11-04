package com.beejepa.muse;

import jakarta.inject.Singleton;

/**
 * Provides response pacing parameters for acoustic turn-taking.
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
