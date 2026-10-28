/**
 * Chooses rendering settings that keep the landing scene fluid on phones.
 *
 * The tier is derived once from capabilities the browser reports; nothing is
 * benchmarked at runtime, so the first frame is already rendered at the right cost.
 */
const LOW_MEMORY_GB = 4
const LOW_CORE_COUNT = 4

export function detectQualityProfile(win = window) {
  const coarsePointer = win.matchMedia('(pointer: coarse)').matches
  const reducedMotion = win.matchMedia('(prefers-reduced-motion: reduce)').matches
  const memory = win.navigator.deviceMemory ?? 8
  const cores = win.navigator.hardwareConcurrency ?? 8
  const constrained = coarsePointer || memory <= LOW_MEMORY_GB || cores <= LOW_CORE_COUNT

  return Object.freeze({
    tier: constrained ? 'mobile' : 'desktop',
    reducedMotion,
    maxPixelRatio: constrained ? 1.5 : 2,
    antialias: !constrained,
    honeycombCellRadius: constrained ? 0.62 : 0.42,
    pointerParallax: !coarsePointer && !reducedMotion
  })
}

export function isWebGlAvailable(doc = document) {
  try {
    const canvas = doc.createElement('canvas')
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'))
  } catch {
    return false
  }
}
