/**
 * Maps recording time and V-JEPA surprise onto world coordinates.
 *
 * Every scene element that visualises the timeline uses this single mapping,
 * so the ribbon, the pillars and the honeycomb glow always agree with each other.
 */
const WORLD_LENGTH = 24
const SURPRISE_HEIGHT_PER_SIGMA = 0.42

export class TimelineSpace {
  constructor(source, timeline) {
    this.durationSec = source.durationSec
    this.tau = source.tau
    this.timeline = timeline
    this.minSurprise = Math.min(...timeline.map(([, surprise]) => surprise))
    this.length = WORLD_LENGTH
  }

  xAt(timeSec) {
    return (timeSec / this.durationSec - 0.5) * this.length
  }

  heightOf(surprise) {
    return (surprise - this.minSurprise) * SURPRISE_HEIGHT_PER_SIGMA + 0.15
  }

  /** Surprise of the recorded timeline point nearest to the given world x. */
  surpriseNearX(worldX) {
    const fraction = Math.min(1, Math.max(0, worldX / this.length + 0.5))
    const timeSec = fraction * this.durationSec
    let nearest = this.timeline[0]
    for (const point of this.timeline) {
      if (Math.abs(point[0] - timeSec) < Math.abs(nearest[0] - timeSec)) {
        nearest = point
      }
    }
    return nearest[1]
  }

  /** 0 at the quietest recorded moment, 1 at the strongest surprise. */
  normalizedSurprise(surprise, maxSurprise) {
    return (surprise - this.minSurprise) / (maxSurprise - this.minSurprise)
  }
}
