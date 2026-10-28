package com.beejepa.perception;

import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * Represents an autonomous saccadic fixation target identified by the Active Perception System.
 * Projects the foveal attention window onto the hive entrance flight board.
 */
@Serdeable
public record SaccadicTarget(
        String eventId,
        long frameIndex,
        double timestampSec,
        String formattedTime,
        double surpriseScoreSigma,
        String severity,
        int focusCenterX,
        int focusCenterY,
        int foveaRadiusPx,
        List<Integer> foveaCropBox, // [y1, x1, y2, x2]
        int boardBeesCount,
        int hotspotBeesCount,
        String classification,
        double saccadicJumpDistancePx
) {}

