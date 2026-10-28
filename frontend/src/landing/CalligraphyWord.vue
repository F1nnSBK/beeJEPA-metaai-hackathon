<script setup>
import { computed, nextTick, onMounted, ref, useId } from 'vue'
import calligraphyPaths from './calligraphy/paths.json'

const props = defineProps({
  word: { type: String, required: true },
  drawn: { type: Boolean, default: false }
})

const TOTAL_DRAW_SEC = 1.9
const GLYPH_OVERLAP = 0.35

const uid = useId()
const gradientId = `ink-gradient-${uid}`
const maskId = `ink-mask-${uid}`
const glyphs = computed(() => calligraphyPaths[props.word].glyphs)
const viewBox = computed(() => calligraphyPaths[props.word].viewBox.join(' '))

const penPaths = ref([])
const timings = ref([])

/** Distributes the writing time over the glyphs in proportion to their outline length. */
function computeTimings(lengths) {
  const total = lengths.reduce((sum, length) => sum + length, 0)
  let cursor = 0
  return lengths.map((length) => {
    const duration = (length / total) * TOTAL_DRAW_SEC
    const timing = { length, duration, delay: cursor }
    cursor += duration * (1 - GLYPH_OVERLAP)
    return timing
  })
}

const fillDelay = computed(() => {
  const last = timings.value.at(-1)
  return last ? last.delay + last.duration * 0.6 : TOTAL_DRAW_SEC
})

function strokeStyle(index) {
  const timing = timings.value[index]
  if (!timing) return { visibility: 'hidden' }
  return {
    '--length': timing.length,
    '--duration': `${timing.duration}s`,
    '--delay': `${timing.delay}s`
  }
}

onMounted(async () => {
  await nextTick()
  timings.value = computeTimings(penPaths.value.map((path) => Math.ceil(path.getTotalLength())))
})
</script>

<template>
  <svg
    class="calligraphy"
    :class="{ 'is-drawn': drawn && timings.length > 0 }"
    :viewBox="viewBox"
    role="img"
    :aria-label="word"
    :style="{ '--fill-delay': `${fillDelay}s` }"
  >
    <defs>
      <linearGradient :id="gradientId" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0%" stop-color="#F7DB9A" />
        <stop offset="45%" stop-color="#E8A33D" />
        <stop offset="100%" stop-color="#B7731F" />
      </linearGradient>
      <mask :id="maskId" maskUnits="userSpaceOnUse">
        <path
          v-for="(glyph, index) in glyphs"
          :key="`mask-${index}`"
          class="calligraphy__mask-stroke"
          :d="glyph"
          :style="strokeStyle(index)"
        />
      </mask>
    </defs>

    <g :mask="`url(#${maskId})`">
      <path v-for="(glyph, index) in glyphs" :key="`ink-${index}`" :d="glyph" :fill="`url(#${gradientId})`" />
    </g>
    <g class="calligraphy__solid">
      <path v-for="(glyph, index) in glyphs" :key="`solid-${index}`" :d="glyph" :fill="`url(#${gradientId})`" />
    </g>
    <path
      v-for="(glyph, index) in glyphs"
      :key="`pen-${index}`"
      ref="penPaths"
      class="calligraphy__pen"
      :d="glyph"
      :style="strokeStyle(index)"
    />
  </svg>
</template>

<style scoped>
.calligraphy {
  display: block;
  width: 100%;
  height: auto;
  overflow: visible;
  filter: drop-shadow(0 0 18px rgba(232, 163, 61, 0.22));
}

.calligraphy__mask-stroke,
.calligraphy__pen {
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-dasharray: var(--length);
  stroke-dashoffset: var(--length);
  transition: stroke-dashoffset var(--duration) cubic-bezier(0.55, 0.08, 0.3, 1) var(--delay);
}

.calligraphy__mask-stroke {
  stroke: #fff;
  stroke-width: 34;
}

.calligraphy__pen {
  stroke: #f7db9a;
  stroke-width: 1.4;
  opacity: 0.9;
}

.calligraphy__solid {
  opacity: 0;
  transition: opacity 0.6s ease var(--fill-delay);
}

.is-drawn .calligraphy__mask-stroke,
.is-drawn .calligraphy__pen {
  stroke-dashoffset: 0;
}

.is-drawn .calligraphy__pen {
  opacity: 0;
  transition:
    stroke-dashoffset var(--duration) cubic-bezier(0.55, 0.08, 0.3, 1) var(--delay),
    opacity 0.8s ease var(--fill-delay);
}

.is-drawn .calligraphy__solid {
  opacity: 1;
}

@media (prefers-reduced-motion: reduce) {
  .calligraphy__mask-stroke,
  .calligraphy__pen,
  .calligraphy__solid {
    transition: none;
  }
}
</style>
