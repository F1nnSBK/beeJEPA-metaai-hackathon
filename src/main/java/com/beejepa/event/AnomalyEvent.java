package com.beejepa.event;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable
public record AnomalyEvent(
    String eventId,
    String sequenceId,
    double timestampSec,
    int frameIndex,
    double surpriseScoreL2,
    double pixelDiffBaseline,
    int horizonDeltaFrames,
    Hotspot hotspot
) {
    @Introspected
    @Serdeable
    public record Hotspot(
        int tubeletGridX,
        int tubeletGridY,
        double normCenterX,
        double normCenterY,
        int[] boundingBoxPx
    ) {}
}
