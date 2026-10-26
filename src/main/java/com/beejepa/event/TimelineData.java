package com.beejepa.event;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Introspected
@Serdeable
public record TimelineData(
    String sequence,
    int totalDurationSec,
    int fps,
    double thresholdTau,
    int eventCount,
    List<TimelinePoint> timeline
) {
    @Introspected
    @Serdeable
    public record TimelinePoint(
        double timestampSec,
        double surpriseScoreL2,
        double pixelDiff,
        String eventId // null, falls kein Anomaly-Trigger
    ) {}
}
