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

const diveDarkness = computed(() => smoothstep(4.55, 5.0, storyPosition.value))

function actOpacity(act) {
  if (reducedMotion) return 1
  const pos = storyPosition.value
  const fadeOutStart = act === ACT.SACCADE ? act + 0.7 : act + 0.48
  const fadeOutEnd = act === ACT.SACCADE ? act + 0.92 : act + 0.78
  if (pos <= fadeOutStart) return 1
  return 1 - smoothstep(fadeOutStart, fadeOutEnd, pos)
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
    tau: source.tau.toFixed(1),
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
  sectionRefs.value[ACT.ENTER]?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth' })
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
  if (!hiveScene) return
  hiveScene.setStory(position)
  const fullyInsideHive = position > ACT.ENTER + 0.05
  if (fullyInsideHive) hiveScene.pauseWhenSettled()
  else hiveScene.start()
})

onMounted(async () => {
  window.addEventListener('pointermove', handlePointer, { passive: true })
  try {
    snapshot.value = await loadSnapshot()
  } catch (error) {
    console.error('beeJEPA landing snapshot could not be loaded:', error)
    loadError.value = true
    return
  }
  await nextTick()
  await startScene()
})

onBeforeUnmount(() => {
  window.removeEventListener('pointermove', handlePointer)
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

    <main v-if="facts" class="landing__story">
      <section :ref="bindSection(ACT.ARRIVAL)" class="act act--arrival" :class="{ 'is-seen': isSeen(ACT.ARRIVAL) }">
        <div class="act__content">
          <div class="act__inner" :style="{ opacity: actOpacity(ACT.ARRIVAL) }">
            <p class="eyebrow">Hive entrance monitoring</p>
            <h1 class="act__title act__title--hero">
              <CalligraphyWord word="beeJEPA" :drawn="sceneState !== 'loading'" />
            </h1>
            <p class="lead">
              V-JEPA 2.1 predicts the next latent state of the hive entrance. Where the prediction error peaks, SAM 3.1
              and Muse Spark take a closer look.
            </p>
            <p class="data">
              {{ facts.durationSec }} s · {{ facts.fps }} fps · {{ facts.sequenceCount }} sequences ·
              {{ facts.eventCount }} peak events · τ = {{ facts.tau }}σ
            </p>
            <span class="scroll-cue" aria-hidden="true">Scroll</span>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.PERIPHERY)" class="act" :class="{ 'is-seen': isSeen(ACT.PERIPHERY) }">
        <div class="act__content">
          <div class="act__inner" :style="{ opacity: actOpacity(ACT.PERIPHERY) }">
            <p class="eyebrow">Act I · System 1</p>
            <h2 class="act__title"><CalligraphyWord word="Periphery" :drawn="isSeen(ACT.PERIPHERY)" /></h2>
            <p class="body">
              V-JEPA 2.1 encodes the flight board recorded at {{ facts.fps }} fps. It does not label objects. It
              predicts the next latent state and measures the prediction error.
            </p>
            <p class="data">{{ facts.measurementCount }} measurements of S(t) · {{ facts.dataset }}</p>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.SURPRISE)" class="act" :class="{ 'is-seen': isSeen(ACT.SURPRISE) }">
        <div class="act__content">
          <div class="act__inner" :style="{ opacity: actOpacity(ACT.SURPRISE) }">
            <p class="eyebrow">Act II · Latent prediction error</p>
            <h2 class="act__title"><CalligraphyWord word="Surprise" :drawn="isSeen(ACT.SURPRISE)" /></h2>
            <p class="formula">S(t) = ‖z<sub>pred</sub> − z<sub>target</sub>‖<sub>2</sub></p>
            <p class="body">
              S(t) rises when observed dynamics differ from prediction ({{ facts.eventCount }} peaks in
              {{ facts.measurementCount }} windows). At 00:00 ({{ facts.maxSurprise }}σ), camera placement shakes the
              whole frame including the grass; at {{ facts.focusTime }} (<strong>{{ facts.focusSurprise }}σ</strong>,
              frame #{{ facts.focusFrame }}), the camera is stationary and attention localizes on the flight board.
            </p>
            <p class="data">Peak events glow vermilion · height = recorded S(t)</p>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.SACCADE)" class="act act--tall" :class="{ 'is-seen': isSeen(ACT.SACCADE) }">
        <div class="act__content">
          <div class="act__inner" :style="{ opacity: actOpacity(ACT.SACCADE) }">
            <p class="eyebrow">Act III · Foveal crop · {{ facts.focusTime }} ({{ facts.focusSurprise }}<span class="sigma">σ</span>)</p>
            <h2 class="act__title"><CalligraphyWord word="Saccade" :drawn="isSeen(ACT.SACCADE)" /></h2>
            <p class="body">
              The view moves to the region marked by the V-JEPA attention map at {{ facts.focusTime }}. SAM 3.1
              segments the bees on the landing board and isolates the active cluster inside the attention hotspot.
            </p>
            <dl class="readout">
              <div>
                <dt>SAM 3.1 · board</dt>
                <dd>{{ facts.boardBeesSam }} <small>bees</small></dd>
              </div>
              <div>
                <dt>Hotspot · IoA ≥ {{ IOA_THRESHOLD }}</dt>
                <dd>{{ facts.hotspotBeesSam }} <small>bees</small></dd>
              </div>
              <div>
                <dt>V-JEPA detector · board</dt>
                <dd>{{ facts.boardBeesDetector }} <small>bees</small></dd>
              </div>
              <div class="readout__wide">
                <dt>Geometric pre-classification</dt>
                <dd class="readout__code">{{ facts.classification }}</dd>
              </div>
            </dl>
          </div>
        </div>
      </section>

      <section :ref="bindSection(ACT.REPORT)" class="act" :class="{ 'is-seen': isSeen(ACT.REPORT) }">
        <div class="act__content">
          <div class="act__inner" :style="{ opacity: actOpacity(ACT.REPORT) }">
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
          <div class="act__inner act__inner--centered">
            <p class="eyebrow">🐝 Beekeeper console</p>
            <h2 class="enter-title">Inside the hive</h2>
            <LoginCard @authenticated="emit('authenticated', $event)" />
          </div>
        </div>
      </section>
    </main>

    <main v-else-if="loadError" class="landing__error">
      <div class="act__inner act__inner--centered">
        <h1 class="enter-title">beeJEPA</h1>
        <p class="body">The hive recording could not be loaded. You can still sign in.</p>
        <LoginCard @authenticated="emit('authenticated', $event)" />
      </div>
    </main>
  </div>
</template>

<style scoped>
.landing {
  --propolis: #0b0906;
  --honey: #e8a33d;
  --wax: #f3e9d2;
  --wax-muted: #8c7a5b;
  --vermilion: #d9472b;
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
  background: radial-gradient(circle at 50% 38%, rgba(11, 9, 6, 0.82), var(--propolis) 68%);
}

/* Fixed to the viewport so the text area stays dark no matter where a section is scrolled to. */
.landing__scrim {
  position: fixed;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background: linear-gradient(to top, rgba(11, 9, 6, 0.97) 0%, rgba(11, 9, 6, 0.9) 34%, rgba(11, 9, 6, 0) 56%);
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
  background: linear-gradient(to bottom, rgba(11, 9, 6, 0.88) 0%, rgba(11, 9, 6, 0.5) 60%, rgba(11, 9, 6, 0) 100%);
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
  border: 1px solid rgba(232, 163, 61, 0.35);
  border-radius: 999px;
  background: rgba(11, 9, 6, 0.55);
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

/* ---------- Acts: mobile first, text panel pinned at the bottom, scene above it ---------- */
/* Sections are taller than the viewport so each text holds in place while the camera moves. */
.act {
  position: relative;
  min-height: 150svh;
}

.act--tall {
  min-height: 260svh;
}

.act--enter {
  min-height: 100svh;
}

.act__content {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  min-height: 100svh;
  padding: 5.5rem calc(env(safe-area-inset-right) + var(--gutter)) calc(env(safe-area-inset-bottom) + 2rem)
    calc(env(safe-area-inset-left) + var(--gutter));
}

.act__inner {
  display: grid;
  gap: 0.9rem;
  width: 100%;
  max-width: 34rem;
}

.act:not(.act--enter) .act__inner {
  padding: 1.15rem 1.25rem;
  border: 1px solid rgba(232, 163, 61, 0.16);
  border-radius: 1rem;
  background: linear-gradient(180deg, rgba(11, 9, 6, 0.88) 0%, rgba(11, 9, 6, 0.96) 100%);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: 0 18px 42px rgba(0, 0, 0, 0.72);
}

.act__inner > * {
  margin: 0;
  opacity: 0;
  transform: translateY(14px);
  transition:
    opacity 0.7s ease,
    transform 0.7s cubic-bezier(0.2, 0.7, 0.2, 1);
}

.is-seen .act__inner > * {
  opacity: 1;
  transform: none;
}

.is-seen .act__inner > :nth-child(2) { transition-delay: 0.08s; }
.is-seen .act__inner > :nth-child(3) { transition-delay: 0.9s; }
.is-seen .act__inner > :nth-child(4) { transition-delay: 1.05s; }
.is-seen .act__inner > :nth-child(5) { transition-delay: 1.2s; }

.act--enter .act__content {
  position: relative;
  justify-content: center;
  align-items: center;
}

.act__inner--centered {
  justify-items: center;
  text-align: center;
  max-width: 24rem;
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

.sigma {
  text-transform: none;
}

.act__title {
  width: clamp(11rem, 56vw, 26rem);
  font-weight: inherit;
}

.act__title--hero {
  width: clamp(16rem, 86vw, 40rem);
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
  color: rgba(243, 233, 210, 0.88);
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
  font-family: var(--mono);
  font-size: clamp(0.95rem, 3.4vw, 1.15rem);
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
  transform: translateY(0.3em);
  transition:
    opacity 0.5s ease,
    transform 0.5s ease;
  transition-delay: calc(1.2s + var(--i) * 70ms);
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
    background: linear-gradient(to right, rgba(11, 9, 6, 0.95) 0%, rgba(11, 9, 6, 0.75) 34%, rgba(11, 9, 6, 0) 52%);
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
</style>
