import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * Height of the reading line as a fraction of the viewport. A low line means an act
 * starts once its text has scrolled into place; the camera prepares the transition
 * while the next text is still scrolling in.
 */
export const READING_LINE = 0.2

/**
 * Translates the window scroll position into a continuous story position.
 * The integer part is the index of the section crossing the reading line,
 * the fractional part is how far that section has been scrolled through.
 *
 * Section bounds are re-measured whenever a section is added or changes size,
 * so asynchronously rendered sections and orientation changes are always tracked.
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
      return { top: rect.top + window.scrollY, height: rect.height }
    })
    update()
  }

  function update() {
    frameRequest = null
    if (sectionBounds.length === 0) return
    const readingLine = window.scrollY + window.innerHeight * READING_LINE
    const lastIndex = sectionBounds.length - 1
    const index = Math.max(0, sectionBounds.findLastIndex((bounds) => readingLine >= bounds.top))

    const bounds = sectionBounds[index]
    const span =
      index === lastIndex
        ? Math.max(1, document.documentElement.scrollHeight - window.innerHeight * (1 - READING_LINE) - bounds.top)
        : bounds.height
    const local = Math.min(1, Math.max(0, (readingLine - bounds.top) / span))

    storyPosition.value = index + local
    activeAct.value = index
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
