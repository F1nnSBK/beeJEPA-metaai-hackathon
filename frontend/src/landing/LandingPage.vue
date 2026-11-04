<script setup>
import '@fontsource/cormorant-garamond/latin-400.css'
import '@fontsource/cormorant-garamond/latin-400-italic.css'
import '@fontsource/cormorant-garamond/latin-500.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import beeLogo from '../assets/bee.png'
import LoginCard from '../components/LoginCard.vue'
import CalligraphyWord from './CalligraphyWord.vue'
import { detectQualityProfile, isWebGlAvailable } from './scene/qualityProfile.js'
import { ACT } from './story.js'
import { useScrollStory } from './useScrollStory.js'

const emit = defineEmits(['authenticated'])

const ASSET_BASE_URL = '/landing'
/** Containment threshold applied by SamGeometry.findContainedBees in the backend. */
const IOA_THRESHOLD = 0.25

const canvasRef = ref(null)
const sectionRefs = ref([])
const snapshot = shallowRef(null)
const sceneState = ref('loading')
const loadError = ref(false)
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
let hiveScene = null

const { storyPosition, activeAct } = useScrollStory(sectionRefs)
const seenActs = ref(new Set([ACT.ARRIVAL]))

const smoothstep = (edge0, edge1, x) => {
  const t = Math.min(1, Math.max(0, (x - edge0) / (edge1 - edge0)))
  return t * t * (3 - 2 * t)
}

const diveDarkness = computed(() => smoothstep(4.2, 4.95, storyPosition.value))

const ACT_LABELS = [
  { act: ACT.ARRIVAL, label: 'Arrival' },
  { act: ACT.PERIPHERY, label: 'Periphery' },
  { act: ACT.SURPRISE, label: 'Surprise' },
  { act: ACT.SACCADE, label: 'Saccade' },
  { act: ACT.REPORT, label: 'Report' },
  { act: ACT.ENTER, label: 'Console' }
]

function actOpacity(act) {
  if (reducedMotion) return activeAct.value === act ? 1 : 0
  const dist = Math.abs(storyPosition.value - act)
  return 1 - smoothstep(0.12, 0.46, dist)
}

function actStyle(act) {
  const opacity = actOpacity(act)
  const visible = opacity > 0.005
  if (reducedMotion) {
    return {
      opacity,
      visibility: visible ? 'visible' : 'hidden',
      pointerEvents: activeAct.value === act ? 'auto' : 'none'
    }
  }
  const delta = storyPosition.value - act
  const shiftY = Math.max(-14, Math.min(14, -delta * 24))
  return {
    opacity,
    transform: `translate3d(0, ${shiftY.toFixed(1)}px, 0)`,
    visibility: visible ? 'visible' : 'hidden',
    pointerEvents: activeAct.value === act ? 'auto' : 'none'
  }
}

const MAX_ACT = ACT.ENTER
const SNAP_DURATION_MS = 520
const STEP_HOLD_MS = 640
const WHEEL_GAP_RESET_MS = 110
const MAX_GESTURE_LOCK_MS = 1050

let currentTargetAct = ACT.ARRIVAL
let scrollAnimFrame = null
let snapIdleTimer = null
let isProgrammaticScroll = false

let gestureLockedUntil = 0
let waitingForGestureEnd = false
let lastWheelTime = 0
let lastWheelAbsDelta = 0
let wheelDeltaBuffer = 0

let touchStartY = null
let touchStartX = null
let touchHandled = false

function getActScrollTop(act) {
  const el = sectionRefs.value[act]
  if (el) return el.offsetTop
  return act * window.innerHeight
}

function animateToAct(act, duration = SNAP_DURATION_MS) {
  const clampedAct = Math.max(ACT.ARRIVAL, Math.min(MAX_ACT, act))
  currentTargetAct = clampedAct
  seenActs.value = new Set([...seenActs.value, clampedAct])

  if (scrollAnimFrame !== null) {
    cancelAnimationFrame(scrollAnimFrame)
    scrollAnimFrame = null
  }
  if (snapIdleTimer !== null) {
    clearTimeout(snapIdleTimer)
    snapIdleTimer = null
  }

  const targetTop = getActScrollTop(clampedAct)
  const startTop = window.scrollY
  const distance = targetTop - startTop

  if (reducedMotion || duration <= 0 || Math.abs(distance) < 1) {
    isProgrammaticScroll = true
    window.scrollTo({ top: targetTop, behavior: 'auto' })
    requestAnimationFrame(() => {
      isProgrammaticScroll = false
    })
    return
  }

  isProgrammaticScroll = true
  const startTime = performance.now()

  const tick = (now) => {
    const elapsed = Math.min(1, (now - startTime) / duration)
    const eased = 1 - Math.pow(1 - elapsed, 3)
    const nextTop = startTop + distance * eased
    window.scrollTo({ top: nextTop, behavior: 'auto' })

    if (elapsed < 1) {
      scrollAnimFrame = requestAnimationFrame(tick)
    } else {
      window.scrollTo({ top: targetTop, behavior: 'auto' })
      scrollAnimFrame = null
      requestAnimationFrame(() => {
        isProgrammaticScroll = false
      })
    }
  }

  scrollAnimFrame = requestAnimationFrame(tick)
}

function scrollToAct(act) {
  gestureLockedUntil = performance.now() + STEP_HOLD_MS
  waitingForGestureEnd = true
  animateToAct(act)
}

function isInteractiveTarget(target) {
  return target instanceof Element && Boolean(target.closest('input, button, a, textarea, select, label'))
}

function handleWheel(event) {
  if (event.ctrlKey || event.metaKey) return
  if (loadError.value || !facts.value) return

  event.preventDefault()

  let deltaY = event.deltaY
  if (event.deltaMode === 1) deltaY *= 32
  else if (event.deltaMode === 2) deltaY *= window.innerHeight

  const now = performance.now()
  const dt = now - lastWheelTime
  const absDelta = Math.abs(deltaY)
  lastWheelTime = now

  if (dt > WHEEL_GAP_RESET_MS) {
    waitingForGestureEnd = false
    wheelDeltaBuffer = 0
    lastWheelAbsDelta = 0
  }

  if (now < gestureLockedUntil) {
    lastWheelAbsDelta = absDelta
    return
  }

  if (waitingForGestureEnd) {
    const isFreshPush = absDelta > Math.max(18, lastWheelAbsDelta * 1.55)
    const lockExpired = now - (gestureLockedUntil - STEP_HOLD_MS) > MAX_GESTURE_LOCK_MS
    if (!isFreshPush && !lockExpired) {
      lastWheelAbsDelta = absDelta
      return
    }
    waitingForGestureEnd = false
    wheelDeltaBuffer = 0
  }

  lastWheelAbsDelta = absDelta
  wheelDeltaBuffer += deltaY

  if (Math.abs(wheelDeltaBuffer) < 16) return

  const direction = wheelDeltaBuffer > 0 ? 1 : -1
  wheelDeltaBuffer = 0

  const baseAct = scrollAnimFrame !== null ? currentTargetAct : Math.round(storyPosition.value)
  const nextAct = Math.max(ACT.ARRIVAL, Math.min(MAX_ACT, baseAct + direction))

  if (nextAct !== baseAct || Math.abs(storyPosition.value - nextAct) > 0.02) {
    gestureLockedUntil = now + STEP_HOLD_MS
    waitingForGestureEnd = true
    animateToAct(nextAct)
  }
}

function handleTouchStart(event) {
  if (event.touches.length !== 1 || isInteractiveTarget(event.target)) {
    touchStartY = null
    return
  }
  touchStartY = event.touches[0].clientY
  touchStartX = event.touches[0].clientX
  touchHandled = false
}

function handleTouchMove(event) {
  if (touchStartY === null || event.touches.length !== 1) return
  if (loadError.value || !facts.value) return
  const dy = touchStartY - event.touches[0].clientY
  const dx = touchStartX - event.touches[0].clientX
  if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 8) {
    event.preventDefault()
  }
  if (!touchHandled && Math.abs(dy) >= 34 && Math.abs(dy) > Math.abs(dx)) {
    touchHandled = true
    const direction = dy > 0 ? 1 : -1
    const baseAct = scrollAnimFrame !== null ? currentTargetAct : Math.round(storyPosition.value)
    const nextAct = Math.max(ACT.ARRIVAL, Math.min(MAX_ACT, baseAct + direction))
    animateToAct(nextAct)
  }
}

function handleKeyDown(event) {
  if (loadError.value || !facts.value) return
  if (isInteractiveTarget(event.target)) return
  const baseAct = scrollAnimFrame !== null ? currentTargetAct : Math.round(storyPosition.value)
  if (event.key === 'ArrowDown' || event.key === 'PageDown' || event.key === ' ') {
    event.preventDefault()
    animateToAct(Math.min(MAX_ACT, baseAct + 1))
  } else if (event.key === 'ArrowUp' || event.key === 'PageUp') {
    event.preventDefault()
    animateToAct(Math.max(ACT.ARRIVAL, baseAct - 1))
  } else if (event.key === 'Home') {
    event.preventDefault()
    animateToAct(ACT.ARRIVAL)
  } else if (event.key === 'End') {
    event.preventDefault()
    animateToAct(ACT.ENTER)
  }
}

function handleNativeScroll() {
  if (isProgrammaticScroll || scrollAnimFrame !== null) return
  currentTargetAct = Math.round(storyPosition.value)
  if (snapIdleTimer !== null) clearTimeout(snapIdleTimer)
  snapIdleTimer = setTimeout(() => {
    const nearestAct = Math.max(ACT.ARRIVAL, Math.min(MAX_ACT, Math.round(storyPosition.value)))
    if (Math.abs(storyPosition.value - nearestAct) > 0.015) {
      animateToAct(nearestAct, 320)
    }
  }, 140)
}

const facts = computed(() => {
  const data = snapshot.value
  if (!data) return null
  const { source, focus, timeline, muse } = data
  return {
    durationSec: source.durationSec,
    fps: source.fps,
    sequenceCount: source.sequenceCount,
    measurementCount: timeline.length,
    eventCount: source.eventCount,
    tau: source.tau.toFixed(2),
    maxSurprise: source.maxSurprise.toFixed(2),
    dataset: source.dataset,
    focusTime: focus.formattedTime,
    focusSurprise: focus.surprise.toFixed(2),
    focusFrame: focus.frameIdx,
    boardBeesSam: focus.boardBeesSam,
    hotspotBeesSam: focus.hotspotBeesSam,
    boardBeesDetector: focus.boardBeesVjepaDetector,
    classification: focus.classification,
    statementWords: muse.statement.split(' '),
    museModel: muse.model
  }
})

function bindSection(index) {
  return (element) => {
    if (element) sectionRefs.value[index] = element
  }
}

function isSeen(act) {
  return seenActs.value.has(act)
}

function skipIntro() {
  scrollToAct(ACT.ENTER)
}

function handlePointer(event) {
  hiveScene?.setPointer((event.clientX / window.innerWidth) * 2 - 1, (event.clientY / window.innerHeight) * 2 - 1)
}

function fallBackToPoster() {
  hiveScene?.dispose()
  hiveScene = null
  sceneState.value = 'fallback'
}

async function loadSnapshot() {
  const response = await fetch(`${ASSET_BASE_URL}/snapshot.json`)
  if (!response.ok) throw new Error(`Snapshot request failed with HTTP ${response.status}`)
  return response.json()
}

async function startScene() {
  if (!isWebGlAvailable()) {
    sceneState.value = 'fallback'
    return
  }
  try {
    const { HiveScene } = await import('./scene/HiveScene.js')
    hiveScene = await HiveScene.create({
      canvas: canvasRef.value,
      snapshot: snapshot.value,
      quality: detectQualityProfile(),
      assetBaseUrl: ASSET_BASE_URL
    })
    canvasRef.value.addEventListener('webglcontextlost', fallBackToPoster, { once: true })
    hiveScene.setStory(storyPosition.value)
    hiveScene.start()
    sceneState.value = 'ready'
  } catch (error) {
    console.error('beeJEPA landing scene could not start:', error)
    fallBackToPoster()
  }
}

watch(activeAct, (act) => {
  if (!seenActs.value.has(act)) seenActs.value = new Set([...seenActs.value, act])
})

watch(storyPosition, (position) => {
  const nearestAct = Math.round(position)
  if (Math.abs(position - nearestAct) < 0.48 && !seenActs.value.has(nearestAct)) {
    seenActs.value = new Set([...seenActs.value, nearestAct])
  }
  if (!hiveScene) return
  hiveScene.setStory(position)
  const fullyInsideHive = position > ACT.ENTER + 0.05
  if (fullyInsideHive) hiveScene.pauseWhenSettled()
  else hiveScene.start()
})

onMounted(async () => {
  document.documentElement.classList.add('landing-scroll-snap')
  window.addEventListener('pointermove', handlePointer, { passive: true })
  window.addEventListener('wheel', handleWheel, { passive: false })
  window.addEventListener('touchstart', handleTouchStart, { passive: true })
  window.addEventListener('touchmove', handleTouchMove, { passive: false })
  window.addEventListener('keydown', handleKeyDown)
  window.addEventListener('scroll', handleNativeScroll, { passive: true })
  try {
    snapshot.value = await loadSnapshot()
  } catch (error) {
    console.error('beeJEPA landing snapshot could not be loaded:', error)
    loadError.value = true
    return
  }
  await nextTick()
  currentTargetAct = Math.round(storyPosition.value)
  await startScene()
})

onBeforeUnmount(() => {
  document.documentElement.classList.remove('landing-scroll-snap')
  window.removeEventListener('pointermove', handlePointer)
  window.removeEventListener('wheel', handleWheel)
  window.removeEventListener('touchstart', handleTouchStart)
  window.removeEventListener('touchmove', handleTouchMove)
  window.removeEventListener('keydown', handleKeyDown)
  window.removeEventListener('scroll', handleNativeScroll)
  if (scrollAnimFrame !== null) cancelAnimationFrame(scrollAnimFrame)
  if (snapIdleTimer !== null) clearTimeout(snapIdleTimer)
  hiveScene?.dispose()
  hiveScene = null
})
</script>

<template>
  <div class="landing">
    <canvas v-show="sceneState !== 'fallback'" ref="canvasRef" class="landing__canvas" aria-hidden="true"></canvas>
    <img
      v-if="sceneState === 'fallback'"
      class="landing__poster"
      :src="`${ASSET_BASE_URL}/keyframe.webp`"
      alt=""
      aria-hidden="true"
    />
    <div class="landing__scrim" aria-hidden="true"></div>
    <div class="landing__dive" :style="{ opacity: diveDarkness }" aria-hidden="true"></div>

    <header class="landing__bar">
      <div class="landing__brand">
        <img :src="beeLogo" alt="" class="landing__logo" />
        <span>beeJEPA</span>
      </div>
      <button v-if="activeAct < ACT.ENTER" type="button" class="landing__skip" @click="skipIntro">Skip intro</button>
    </header>

    <nav v-if="facts" class="landing__dots" aria-label="Story sections">
      <button
        v-for="item in ACT_LABELS"
        :key="item.act"
        type="button"
        class="landing__dot"
        :class="{ 'is-active': activeAct === item.act }"
        :aria-label="item.label"
        :aria-current="activeAct === item.act ? 'step' : undefined"
        :title="item.label"
        @click="scrollToAct(item.act)"
      >
        <span class="landing__dot-pip"></span>
      </button>
    </nav>

    <main v-if="facts" class="landing__story">
      <section :ref="bindSection(ACT.ARRIVAL)" class="act act--arrival" :class="{ 'is-seen': isSeen(ACT.ARRIVAL) }">
        <div class="act__content">
          <div class="act__inner" :style="actStyle(ACT.ARRIVAL)">
            <p class="eyebrow">Hive entrance monitoring</p>
            <h1 class="act__title act__title--hero">
              <CalligraphyWord word="beeJEPA" :drawn="sceneState !== 'loading'" />
            </h1>
            <p class="lead">
              V-JEPA 2.1 (ViT-B/16) tracks frame-to-frame token changes across the hive entrance. Where the token-delta score peaks, SAM 3.1
              and Muse Spark take a closer look.
            </p>
            <p class="data">
              {{ facts.durationSec }} s · {{ facts.fps }} fps · {{ facts.sequenceCount }} sequences ·
              {{ facts.eventCount }} peak events · <span class="math">𝜏 = {{ facts.tau }}</span> (P99)
            </p>
            <span class="scroll-cue" aria-hidden="true">Scroll</span>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.PERIPHERY)" class="act" :class="{ 'is-seen': isSeen(ACT.PERIPHERY) }">
        <div class="act__content">
          <div class="act__inner" :style="actStyle(ACT.PERIPHERY)">
            <p class="eyebrow">Act I · System 1</p>
            <h2 class="act__title"><CalligraphyWord word="Periphery" :drawn="isSeen(ACT.PERIPHERY)" /></h2>
            <p class="body">
              V-JEPA 2.1 (ViT-B/16) encodes the flight board recorded at {{ facts.fps }} fps on a 43×43 token grid. It does not label objects. It
              measures frame-to-frame token persistence across adjacent windows.
            </p>
            <p class="data">{{ facts.measurementCount }} measurements of <span class="math">Token-Δ</span> · {{ facts.dataset }}</p>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.SURPRISE)" class="act" :class="{ 'is-seen': isSeen(ACT.SURPRISE) }">
        <div class="act__content">
          <div class="act__inner" :style="actStyle(ACT.SURPRISE)">
            <p class="eyebrow">Act II · Token-delta persistence</p>
            <h2 class="act__title"><CalligraphyWord word="Surprise" :drawn="isSeen(ACT.SURPRISE)" /></h2>
            <p class="formula math">Token-Δ (43×43 ViT-B/16 grid) · P99 𝜏 = {{ facts.tau }}</p>
            <p class="body">
              The token-delta score rises when patch tokens change rapidly ({{ facts.eventCount }} peaks in
              {{ facts.measurementCount }} windows). At 00:00 (<span class="math">{{ facts.maxSurprise }}</span>), camera placement shifts the
              whole frame including the grass; at {{ facts.focusTime }} (<strong class="math">{{ facts.focusSurprise }}</strong>,
              frame #{{ facts.focusFrame }}), the camera is stationary and token change localizes on the flight board.
            </p>
            <p class="data">Peak events glow ember · height = recorded <span class="math">Token-Δ</span></p>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.SACCADE)" class="act act--tall" :class="{ 'is-seen': isSeen(ACT.SACCADE) }">
        <div class="act__content">
          <div class="act__inner" :style="actStyle(ACT.SACCADE)">
            <p class="eyebrow">Act III · Foveal crop · {{ facts.focusTime }} (Token-Δ <span class="math">{{ facts.focusSurprise }}</span>)</p>
            <h2 class="act__title"><CalligraphyWord word="Saccade" :drawn="isSeen(ACT.SACCADE)" /></h2>
            <p class="body">
              The view moves to the region marked by the V-JEPA overlay at {{ facts.focusTime }}. SAM 3.1
              segments the bees on the landing board and isolates the active cluster inside the measured hotspot.
            </p>
            <dl class="readout">
              <div>
                <dt>SAM 3.1 · board</dt>
                <dd>{{ facts.boardBeesSam }} <small>bees</small></dd>
              </div>
              <div>
                <dt>Hotspot · <span class="math">IoA ≥ {{ IOA_THRESHOLD }}</span></dt>
                <dd>{{ facts.hotspotBeesSam }} <small>bees</small></dd>
              </div>
              <div>
                <dt>V-JEPA board · tokens</dt>
                <dd>{{ facts.boardBeesDetector }} <small>peaks</small></dd>
              </div>
              <div class="readout__wide">
                <dt>Geometric classification</dt>
                <dd class="readout__code">{{ facts.classification }}</dd>
              </div>
            </dl>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.REPORT)" class="act" :class="{ 'is-seen': isSeen(ACT.REPORT) }">
        <div class="act__content">
          <div class="act__inner" :style="actStyle(ACT.REPORT)">
            <p class="eyebrow">Act IV · System 2</p>
            <h2 class="act__title"><CalligraphyWord word="Report" :drawn="isSeen(ACT.REPORT)" /></h2>
            <blockquote class="quote">
              <p>
                <span v-for="(word, index) in facts.statementWords" :key="index" :style="{ '--i': index }">{{ word }}</span>
              </p>
              <cite>Cached response · Meta Muse Spark Open · {{ facts.museModel }}</cite>
            </blockquote>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.ENTER)" class="act act--enter" :class="{ 'is-seen': isSeen(ACT.ENTER) }">
        <div class="act__content">
          <div class="act__inner act__inner--centered" :style="actStyle(ACT.ENTER)">
            <p class="eyebrow">Beekeeper console</p>
            <h2 class="enter-title">Inside the hive</h2>
            <LoginCard @authenticated="emit('authenticated', $event)" />
            <nav class="landing__legal" aria-label="Legal">
              <a href="https://www.finn-hertsch.de/impressum" target="_blank" rel="noopener noreferrer">Impressum</a>
              <span aria-hidden="true">·</span>
              <a href="https://www.finn-hertsch.de/datenschutz" target="_blank" rel="noopener noreferrer">Datenschutz</a>
            </nav>
          </div>
        </div>
      </section>
    </main>

    <main v-else-if="loadError" class="landing__error">
      <div class="act__inner act__inner--centered">
        <h1 class="enter-title">beeJEPA</h1>
        <p class="body">The hive recording could not be loaded. You can still sign in.</p>
        <LoginCard @authenticated="emit('authenticated', $event)" />
        <nav class="landing__legal" aria-label="Legal">
          <a href="https://www.finn-hertsch.de/impressum" target="_blank" rel="noopener noreferrer">Impressum</a>
          <span aria-hidden="true">·</span>
          <a href="https://www.finn-hertsch.de/datenschutz" target="_blank" rel="noopener noreferrer">Datenschutz</a>
        </nav>
      </div>
    </main>
  </div>
</template>

<style scoped>
.landing {
  --propolis: #060709;
  --honey: #eaa23a;
  --electric: #77b7fe;
  --wax: #eaebed;
  --wax-muted: #939599;
  --vermilion: #f36c47;
  --serif: 'Cormorant Garamond', Georgia, serif;
  --mono: 'JetBrains Mono', ui-monospace, monospace;
  --gutter: clamp(1.25rem, 5vw, 2.5rem);

  position: relative;
  min-height: 100svh;
  background: var(--propolis);
  color: var(--wax);
}

/* Large-viewport height keeps the canvas stable while mobile browser bars collapse. */
.landing__canvas,
.landing__poster {
  position: fixed;
  inset: 0 auto auto 0;
  width: 100%;
  height: 100vh;
  height: 100lvh;
  display: block;
  z-index: 0;
}

.landing__poster {
  object-fit: cover;
  object-position: 55% 38%;
  opacity: 0.4;
}

.landing__dive {
  position: fixed;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background: radial-gradient(circle at 50% 38%, rgba(6, 7, 9, 0.82), var(--propolis) 68%);
}

/* Fixed to the viewport so the text area stays dark no matter where a section is scrolled to. */
.landing__scrim {
  position: fixed;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background: linear-gradient(to top, rgba(6, 7, 9, 0.97) 0%, rgba(6, 7, 9, 0.9) 34%, rgba(6, 7, 9, 0) 56%);
}

.landing__bar {
  position: fixed;
  inset: 0 0 auto 0;
  z-index: 4;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(env(safe-area-inset-top) + 0.75rem) calc(env(safe-area-inset-right) + var(--gutter)) 1.5rem
    calc(env(safe-area-inset-left) + var(--gutter));
  background: linear-gradient(to bottom, rgba(6, 7, 9, 0.88) 0%, rgba(6, 7, 9, 0.5) 60%, rgba(6, 7, 9, 0) 100%);
  pointer-events: none;
}

.landing__brand {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  font-family: var(--serif);
  font-size: 1.15rem;
  letter-spacing: 0.02em;
}

.landing__logo {
  width: 1.6rem;
  height: 1.6rem;
  object-fit: contain;
}

.landing__skip {
  pointer-events: auto;
  min-height: 44px;
  padding: 0 1.1rem;
  border: 1px solid #333638;
  border-radius: 999px;
  background: rgba(17, 19, 21, 0.65);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  color: var(--wax);
  font-family: var(--mono);
  font-size: 0.7rem;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  cursor: pointer;
}

.landing__skip:focus-visible {
  outline: 2px solid var(--honey);
  outline-offset: 2px;
}

.landing__dots {
  position: fixed;
  right: calc(env(safe-area-inset-right) + 0.75rem);
  top: 50%;
  transform: translateY(-50%);
  z-index: 4;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.landing__dot {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.landing__dot-pip {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: rgba(234, 235, 237, 0.28);
  transition:
    transform 0.25s ease,
    background-color 0.25s ease,
    height 0.25s ease;
}

.landing__dot:hover .landing__dot-pip {
  background: rgba(234, 235, 237, 0.65);
}

.landing__dot.is-active .landing__dot-pip {
  height: 18px;
  background: var(--honey);
}

.landing__dot:focus-visible {
  outline: 2px solid var(--honey);
  outline-offset: 2px;
  border-radius: 999px;
}

.landing__story,
.landing__error {
  position: relative;
  z-index: 2;
}

.landing__error {
  display: grid;
  place-items: center;
  min-height: 100svh;
  padding: var(--gutter);
}

/* ---------- Acts: 100svh scroll-snap stops with viewport-pinned cards ---------- */
.act {
  position: relative;
  height: 100svh;
  min-height: 100svh;
  scroll-snap-align: start;
  scroll-snap-stop: always;
}

.act--tall {
  height: 100svh;
  min-height: 100svh;
}

.act--enter {
  height: 100svh;
  min-height: 100svh;
}

.act__content {
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  min-height: 100svh;
  padding: 5.5rem calc(env(safe-area-inset-right) + var(--gutter)) calc(env(safe-area-inset-bottom) + 2rem)
    calc(env(safe-area-inset-left) + var(--gutter));
  pointer-events: none;
}

.act__inner {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 0.9rem;
  width: 100%;
  max-width: 34rem;
  will-change: opacity, transform;
}

.act:not(.act--enter) .act__inner {
  padding: 1.15rem 1.25rem;
  border: 1px solid #202224;
  border-radius: 1rem;
  background: linear-gradient(180deg, rgba(17, 19, 21, 0.88) 0%, rgba(6, 7, 9, 0.96) 100%);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.72);
}

.act__inner > * {
  margin: 0;
}

.act--enter .act__content {
  justify-content: center;
  align-items: center;
}

.act__inner--centered {
  justify-items: center;
  text-align: center;
  max-width: 25.5rem;
}

@media (max-width: 640px) {
  .act--enter .act__content {
    justify-content: flex-start;
    padding-top: calc(env(safe-area-inset-top) + 4.75rem);
  }
}

.act__inner--centered > * {
  width: 100%;
}

.eyebrow {
  font-family: var(--mono);
  font-size: 0.7rem;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--wax-muted);
}

.act__title {
  width: clamp(11rem, 56vw, 26rem);
  font-weight: inherit;
}

/* Fira Math has a taller x-height than Cormorant and a smaller one than JetBrains Mono. */
.body .math {
  font-size: 0.86em;
}

.eyebrow .math,
.data .math,
.readout dt .math {
  font-size: 1.12em;
}

.act__title--hero {
  width: clamp(16rem, 86vw, 40rem);
}

.act__title,
.act__title--hero {
  max-width: 100%;
}

.lead {
  font-family: var(--serif);
  font-size: clamp(1.35rem, 4.8vw, 2rem);
  line-height: 1.22;
}

.body {
  font-family: var(--serif);
  font-size: clamp(1.15rem, 3.9vw, 1.45rem);
  line-height: 1.42;
  color: rgba(234, 235, 237, 0.9);
}

.body strong {
  font-weight: 500;
  color: var(--vermilion);
}

.data {
  font-family: var(--mono);
  font-size: 0.74rem;
  line-height: 1.6;
  color: var(--wax-muted);
}

.formula {
  padding-left: 0.9rem;
  border-left: 2px solid var(--vermilion);
  font-size: clamp(1.15rem, 4vw, 1.4rem);
  line-height: 1.5;
}

.readout {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.7rem 1rem;
}

.readout div {
  display: grid;
  align-content: start;
  gap: 0.15rem;
}

.readout .readout__wide {
  grid-column: 1 / -1;
}

.readout dt {
  font-family: var(--mono);
  font-size: 0.62rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--wax-muted);
}

.readout dd {
  margin: 0;
  font-family: var(--serif);
  font-size: 1.5rem;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: var(--honey);
}

.readout dd small {
  font-size: 0.95rem;
  color: var(--wax-muted);
}

.readout .readout__code {
  font-family: var(--mono);
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--wax);
}

.quote {
  margin: 0;
}

.quote p {
  margin: 0 0 0.8rem;
  font-family: var(--serif);
  font-style: italic;
  font-size: clamp(1.5rem, 5.6vw, 2.4rem);
  line-height: 1.2;
}

.quote span {
  display: inline-block;
  /* inline-block swallows trailing whitespace, so word spacing is applied as margin. */
  margin-right: 0.24em;
  opacity: 0;
  transform: translateY(0.2em);
  transition:
    opacity 0.35s ease,
    transform 0.35s ease;
  transition-delay: calc(var(--i) * 18ms);
}

.is-seen .quote span {
  opacity: 1;
  transform: none;
}

.quote cite {
  font-family: var(--mono);
  font-size: 0.68rem;
  font-style: normal;
  color: var(--wax-muted);
}

.enter-title {
  margin: 0;
  font-family: var(--serif);
  font-weight: 400;
  font-size: clamp(2rem, 8vw, 3rem);
}

.scroll-cue {
  position: relative;
  padding-left: 2.2rem;
  font-family: var(--mono);
  font-size: 0.65rem;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  color: var(--wax-muted);
}

.scroll-cue::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 1.6rem;
  height: 1px;
  background: var(--honey);
  transform-origin: left;
  animation: cue 2.4s ease-in-out infinite;
}

@keyframes cue {
  0%, 100% { transform: scaleX(0.3); }
  50% { transform: scaleX(1); }
}

/* ---------- Landscape and desktop: text column left, scene on the right ---------- */
@media (min-aspect-ratio: 9/10) and (min-width: 720px) {
  .landing__scrim {
    background: linear-gradient(to right, rgba(6, 7, 9, 0.95) 0%, rgba(6, 7, 9, 0.75) 34%, rgba(6, 7, 9, 0) 52%);
  }

  .act__content {
    justify-content: center;
    padding-top: 6rem;
    padding-bottom: 4rem;
  }

  .act__inner {
    max-width: min(34rem, 38vw);
  }

  .act__inner--centered {
    max-width: 24rem;
  }
}

/* ---------- Phones held sideways: very little height ---------- */
@media (max-height: 520px) and (min-aspect-ratio: 9/10) {
  .act__content {
    padding-top: 4.25rem;
    padding-bottom: 1.25rem;
  }

  .act__inner {
    gap: 0.45rem;
  }

  .act__title,
  .act__title--hero {
    width: clamp(9rem, 26vw, 15rem);
  }

  .lead,
  .body {
    font-size: 1.02rem;
  }

  .act--tall .body,
  .act__inner .eyebrow,
  .scroll-cue,
  .readout .readout__wide {
    display: none;
  }

  .quote p {
    font-size: 1.3rem;
  }

  .act--enter .act__content {
    justify-content: flex-start;
  }

  .enter-title {
    font-size: 1.6rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .act__inner > *,
  .quote span {
    transition: none;
  }

  .scroll-cue::before {
    animation: none;
  }
}

.landing__legal {
  margin-top: 1.5rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.45rem;
  font-family: var(--mono);
  font-size: 0.65rem;
  letter-spacing: 0.04em;
  color: rgba(147, 149, 153, 0.38);
}

.landing__legal a {
  color: inherit;
  text-decoration: none;
  transition: color 0.18s ease;
}

.landing__legal a:hover {
  color: rgba(234, 235, 237, 0.65);
}
</style>
