import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * Translates the window scroll position into a continuous story position aligned
 * with 100svh scroll-snap sections:
 * - When snapped at section `k`, `storyPosition` is `k` (0.0, 1.0, 2.0, 3.0, 4.0, 5.0).
 * - While scrolling between `k` and `k + 1`, `storyPosition` interpolates linearly
 *   from `k` to `k + 1`, and `activeAct` switches as soon as the incoming section
 *   crosses 45% of the viewport so its text is already revealed upon arrival.
 */
export function useScrollStory(sectionElements) {
  const storyPosition = ref(0)
  const activeAct = ref(0)
  let sectionBounds = []
  let frameRequest = null
  const resizeObserver = new ResizeObserver(() => measure())
  const observedElements = new Set()

  function measure() {
    sectionBounds = sectionElements.value.filter(Boolean).map((element) => {
      const rect = element.getBoundingClientRect()
      return { top: rect.top + window.scrollY, height: Math.max(1, rect.height) }
    })
    update()
  }

  function update() {
    frameRequest = null
    if (sectionBounds.length === 0) return
    const scrollTop = Math.max(0, window.scrollY)
    const lastIndex = sectionBounds.length - 1
    const index = Math.max(0, sectionBounds.findLastIndex((bounds) => scrollTop >= bounds.top - 2))

    const bounds = sectionBounds[index]
    const nextBounds = index < lastIndex ? sectionBounds[index + 1] : null
    const span = nextBounds
      ? Math.max(1, nextBounds.top - bounds.top)
      : Math.max(1, bounds.height)
    const local = Math.min(1, Math.max(0, (scrollTop - bounds.top) / span))

    storyPosition.value = Math.min(lastIndex, index + local)
    activeAct.value = local >= 0.45 && index < lastIndex ? index + 1 : index
  }

  function scheduleUpdate() {
    if (frameRequest === null) {
      frameRequest = requestAnimationFrame(update)
    }
  }

  function observeSections(elements) {
    for (const element of elements.filter(Boolean)) {
      if (!observedElements.has(element)) {
        observedElements.add(element)
        resizeObserver.observe(element)
      }
    }
    measure()
  }

  watch(sectionElements, observeSections, { deep: true, flush: 'post' })

  onMounted(() => {
    window.addEventListener('scroll', scheduleUpdate, { passive: true })
    window.addEventListener('resize', measure, { passive: true })
    observeSections(sectionElements.value)
  })

  onBeforeUnmount(() => {
    resizeObserver.disconnect()
    window.removeEventListener('scroll', scheduleUpdate)
    window.removeEventListener('resize', measure)
    if (frameRequest !== null) cancelAnimationFrame(frameRequest)
  })

  return { storyPosition, activeAct, measure }
}
