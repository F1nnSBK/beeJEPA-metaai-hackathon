<script setup>
import { ref, onMounted, onUnmounted, computed, watch, nextTick } from 'vue'
import beeLogo from './assets/bee.png'

// Auth state
const isAuthenticated = ref(false)
const currentUser = ref(null)
const loginUsername = ref('beekeeper')
const loginPassword = ref('')
const loginError = ref('')
const isLoggingIn = ref(false)

// Hardware & Storage state
const hardware = ref('Raspberry Pi 4 Model B')
const storageStatus = ref({
  isMounted: false,
  freeSpaceFormatted: 'Not Connected',
  isWritable: false
})

// Timeline & Events state
const timelineData = ref([])
const timelineMeta = ref(null)
const events = ref([])
const selectedEvent = ref(null)
const activeView = ref('comparison') // 'comparison', 'raw', 'sam'
const isAnalyzing = ref(false)

// Muse Voice & Bee Mode Dynamic Audio (Java Synthesizer & Live Device Microphone)
const isBeeModePlaying = ref(false)
const isRecording = ref(false)
const isProcessing = ref(false)
let beeAudioInstance = null
let activeStopRecording = null

function encodeWavBuffer(samples, sampleRate) {
  const buffer = new ArrayBuffer(44 + samples.length * 2)
  const view = new DataView(buffer)

  // "RIFF"
  view.setUint32(0, 0x52494646, false)
  view.setUint32(4, 36 + samples.length * 2, true)
  // "WAVE"
  view.setUint32(8, 0x57415645, false)
  // "fmt "
  view.setUint32(12, 0x666d7420, false)
  view.setUint32(16, 16, true)
  view.setUint16(20, 1, true) // PCM
  view.setUint16(22, 1, true) // Mono
  view.setUint32(24, sampleRate, true) // Sample rate
  view.setUint32(28, sampleRate * 2, true) // Byte rate
  view.setUint16(32, 2, true) // Block align
  view.setUint16(34, 16, true) // Bits per sample
  // "data"
  view.setUint32(36, 0x64617461, false)
  view.setUint32(40, samples.length * 2, true)

  let offset = 44
  for (let i = 0; i < samples.length; i++, offset += 2) {
    const s = Math.max(-1, Math.min(1, samples[i]))
    view.setInt16(offset, s < 0 ? s * 0x8000 : s * 0x7FFF, true)
  }
  return buffer
}

async function toggleMicrophoneListening() {
  if (isRecording.value) {
    // If clicked while recording, user manually finishes speaking
    if (activeStopRecording) {
      activeStopRecording()
    }
    return
  }

  // Stop any currently playing audio before starting recording
  if (beeAudioInstance) {
    beeAudioInstance.pause()
    beeAudioInstance = null
    isBeeModePlaying.value = false
  }

  // Check for Secure Context / MediaDevices support
  const isLocalHost = location.hostname === 'localhost' || location.hostname === '127.0.0.1'
  if (window.isSecureContext === false && !isLocalHost) {
    console.warn('Microphone requires a secure context (http://localhost:8080 or HTTPS).')
    return
  }

  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    console.warn('Microphone API not available in this browser.')
    return
  }

  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const AudioCtx = window.AudioContext || window.webkitAudioContext
    const audioContext = new AudioCtx()
    await audioContext.resume()
    const sampleRate = audioContext.sampleRate || 44100

    const source = audioContext.createMediaStreamSource(stream)
    const processor = audioContext.createScriptProcessor(4096, 1, 1)

    const floatChunks = []
    let hasSpoken = false
    let speechStartTime = 0
    let lastSpeechTime = 0
    let silenceCheckInterval = null
    let stopped = false

    isRecording.value = true
    isProcessing.value = false

    processor.onaudioprocess = (e) => {
      if (stopped) return
      const input = e.inputBuffer.getChannelData(0)
      floatChunks.push(new Float32Array(input))

      // Compute RMS audio volume
      let sumSq = 0
      for (let i = 0; i < input.length; i++) {
        sumSq += input[i] * input[i]
      }
      const rms = Math.sqrt(sumSq / input.length)

      const now = Date.now()
      if (rms > 0.015) {
        if (!hasSpoken) {
          hasSpoken = true
          speechStartTime = now
        }
        lastSpeechTime = now
      }
    }

    source.connect(processor)
    processor.connect(audioContext.destination)

    const finishAndSend = async () => {
      if (stopped) return
      stopped = true
      activeStopRecording = null
      if (silenceCheckInterval) clearInterval(silenceCheckInterval)

      isRecording.value = false
      isProcessing.value = true

      try {
        source.disconnect()
        processor.disconnect()
        stream.getTracks().forEach(t => t.stop())
        audioContext.close()
      } catch (e) {
        console.debug('Error closing audio input:', e)
      }

      let totalLen = 0
      for (const c of floatChunks) totalLen += c.length
      if (totalLen < sampleRate * 0.3) {
        // Less than 300ms recorded, ignore
        isProcessing.value = false
        return
      }

      const allSamples = new Float32Array(totalLen)
      let off = 0
      for (const c of floatChunks) {
        allSamples.set(c, off)
        off += c.length
      }

      const wavBytes = encodeWavBuffer(allSamples, sampleRate)

      try {
        const res = await fetch('/api/v1/muse/acoustic-mirror', {
          method: 'POST',
          headers: { 'Content-Type': 'audio/wav' },
          body: wavBytes
        })

        isProcessing.value = false

        if (res.ok) {
          const audioBlob = await res.blob()
          const audioUrl = URL.createObjectURL(audioBlob)

          if (beeAudioInstance) {
            beeAudioInstance.pause()
          }
          beeAudioInstance = new Audio(audioUrl)
          isBeeModePlaying.value = true

          beeAudioInstance.onended = () => {
            isBeeModePlaying.value = false
          }
          beeAudioInstance.play().catch(e => console.debug('Audio playback error:', e))
        }
      } catch (err) {
        console.debug('Acoustic mirror network error:', err)
        isProcessing.value = false
      }
    }

    activeStopRecording = finishAndSend
    const startTimestamp = Date.now()

    // Check for speech completion / silence every 150ms
    silenceCheckInterval = setInterval(() => {
      if (stopped) return
      const now = Date.now()

      // If user has spoken and then paused for >= 1000ms, finish
      if (hasSpoken && (now - lastSpeechTime) >= 1000) {
        finishAndSend()
        return
      }

      // Max timeout: 10s if speech occurred, 7s if no speech
      if (hasSpoken && (now - speechStartTime) >= 10000) {
        finishAndSend()
        return
      }
      if (!hasSpoken && (now - startTimestamp) >= 7000) {
        finishAndSend()
      }
    }, 150)

  } catch (err) {
    console.debug('Microphone error:', err)
    isRecording.value = false
    isProcessing.value = false
  }
}

function toggleBeeMode() {
  toggleMicrophoneListening()
}

// Live Bee Swarm Simulation (Low-Temperature Random Walk)
const beeCanvasRef = ref(null)
let beeAnimationId = null

const beeSprites = [
  '/bee.png',
  '/bee_fluent3d.png'
]

function initBeeSwarm() {
  const canvas = beeCanvasRef.value
  if (!canvas) return

  const ctx = canvas.getContext('2d')
  let width = 0
  let height = 0

  const updateDimensions = () => {
    if (!canvas) return
    const rect = canvas.getBoundingClientRect()
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    width = rect.width || window.innerWidth
    height = rect.height || window.innerHeight
    canvas.width = width * dpr
    canvas.height = height * dpr
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.scale(dpr, dpr)
  }
  updateDimensions()
  window.addEventListener('resize', updateDimensions)

  // Preload premium 3D bee sprites
  const loadedImages = beeSprites.map(src => {
    const img = new Image()
    img.src = src
    return img
  })

  // 12 bees evenly distributed across a 3x4 stratified grid with organic jitter
  const cols = 3
  const rows = 4
  const count = cols * rows
  const bees = Array.from({ length: count }, (_, i) => {
    const col = i % cols
    const row = Math.floor(i / cols)
    const cellW = (width || window.innerWidth) / cols
    const cellH = (height || window.innerHeight) / rows

    // Stratified position across screen zones with organic jitter
    const spawnX = (col + 0.15 + Math.random() * 0.7) * cellW
    const spawnY = (row + 0.15 + Math.random() * 0.7) * cellH

    const angle = Math.random() * Math.PI * 2
    const cruiseSpeed = 0.32 + Math.random() * 0.32 // calm, gentle drift
    const z = 0.80 + Math.random() * 0.38

    return {
      x: spawnX,
      y: spawnY,
      z: z,
      targetZ: z,
      depthTimer: Math.floor(Math.random() * 300),
      vx: Math.cos(angle) * cruiseSpeed,
      vy: Math.sin(angle) * cruiseSpeed,
      cruiseSpeed: cruiseSpeed,
      baseSize: 32 + (i % 3) * 4, // 32px, 36px, 40px
      imgIndex: i % loadedImages.length,
      wobblePhase: Math.random() * Math.PI * 2,
      wobbleSpeed: 0.13 + Math.random() * 0.05,
      wanderPhase: Math.random() * Math.PI * 2,
      wanderSpeed: 0.006 + Math.random() * 0.008
    }
  })

  function animate() {
    if (isAuthenticated.value) return
    ctx.clearRect(0, 0, width, height)

    // 1. Physics update: separation, wander, boundary avoidance & speed damping
    for (let i = 0; i < bees.length; i++) {
      const bee = bees[i]

      // Organic smooth wandering curve (individual steer per bee)
      bee.wanderPhase += bee.wanderSpeed
      const wanderSteer = Math.sin(bee.wanderPhase) * 0.012
      const perpX = -bee.vy
      const perpY = bee.vx
      const perpLen = Math.hypot(perpX, perpY) || 1
      bee.vx += (perpX / perpLen) * wanderSteer
      bee.vy += (perpY / perpLen) * wanderSteer

      // Mutual separation force (Boids): ensures bees never clump or overlap
      for (let j = 0; j < bees.length; j++) {
        if (i === j) continue
        const other = bees[j]
        const dx = bee.x - other.x
        const dy = bee.y - other.y
        const distSq = dx * dx + dy * dy
        const minDist = 110 // personal space radius in px
        if (distSq < minDist * minDist && distSq > 0) {
          const dist = Math.sqrt(distSq)
          const repelForce = ((minDist - dist) / minDist) * 0.035
          bee.vx += (dx / dist) * repelForce
          bee.vy += (dy / dist) * repelForce
        }
      }

      // Gentle avoidance of card center (encourages bees to circulate in visible perimeter)
      const centerX = width / 2
      const centerY = height / 2
      const cdx = bee.x - centerX
      const cdy = bee.y - centerY
      const cardDist = Math.hypot(cdx, cdy)
      const cardRadius = Math.min(width * 0.42, 220)
      if (cardDist < cardRadius && cardDist > 0) {
        const cardPush = ((cardRadius - cardDist) / cardRadius) * 0.02
        bee.vx += (cdx / cardDist) * cardPush
        bee.vy += (cdy / cardDist) * cardPush
      }

      // Smooth soft boundary steering away from screen edges
      const margin = 45
      if (bee.x < margin) bee.vx += 0.03
      else if (bee.x > width - margin) bee.vx -= 0.03
      if (bee.y < margin) bee.vy += 0.03
      else if (bee.y > height - margin) bee.vy -= 0.03

      // Normalize velocity towards calm cruise speed
      const curSpeed = Math.hypot(bee.vx, bee.vy) || 0.001
      const newSpeed = curSpeed + (bee.cruiseSpeed - curSpeed) * 0.04
      bee.vx = (bee.vx / curSpeed) * newSpeed
      bee.vy = (bee.vy / curSpeed) * newSpeed

      // Very slow and gentle depth drift (changes target depth every 6-12s)
      bee.depthTimer--
      if (bee.depthTimer <= 0) {
        bee.targetZ = 0.80 + Math.random() * 0.38
        bee.depthTimer = 360 + Math.floor(Math.random() * 360)
      }
      bee.z += (bee.targetZ - bee.z) * 0.003

      // Update position scaled subtly by depth
      const depthSpeedScale = 0.75 + bee.z * 0.25
      bee.x += bee.vx * depthSpeedScale
      bee.y += bee.vy * depthSpeedScale

      // Wing flutter phase
      bee.wobblePhase += bee.wobbleSpeed
    }

    // 2. Sort by depth (distant bees behind, foreground bees on top)
    bees.sort((a, b) => a.z - b.z)

    // 3. Render each bee
    for (const bee of bees) {
      const img = loadedImages[bee.imgIndex]
      if (!img.complete || img.naturalWidth === 0) continue

      const jitterY = Math.sin(bee.wobblePhase) * (0.7 * bee.z)
      const jitterRot = Math.cos(bee.wobblePhase * 1.2) * 0.03

      const currentSize = bee.baseSize * bee.z
      const currentOpacity = 0.30 + (bee.z - 0.80) * 0.22 // subtle ambient opacity

      ctx.save()
      ctx.globalAlpha = currentOpacity
      ctx.translate(bee.x, bee.y + jitterY)

      // Flight heading & pitch tilt
      const flyingRight = bee.vx > 0
      if (flyingRight) {
        ctx.scale(-1, 1) // flip horizontally to face right
        ctx.rotate(-Math.atan2(bee.vy, bee.vx) + jitterRot)
      } else {
        ctx.rotate(Math.atan2(bee.vy, -bee.vx) + jitterRot)
      }

      ctx.drawImage(img, -currentSize / 2, -currentSize / 2, currentSize, currentSize)
      ctx.restore()
    }

    beeAnimationId = requestAnimationFrame(animate)
  }

  beeAnimationId = requestAnimationFrame(animate)
}

watch(isAuthenticated, (val) => {
  if (!val) {
    nextTick(() => initBeeSwarm())
  } else if (beeAnimationId) {
    cancelAnimationFrame(beeAnimationId)
    beeAnimationId = null
  }
})

onMounted(async () => {
  const token = localStorage.getItem('beejepa_token')
  if (token) {
    await fetchUserProfile(token)
  }
  if (!isAuthenticated.value) {
    nextTick(() => initBeeSwarm())
  }
  await loadSystemAndData()
})

onUnmounted(() => {
  if (beeAnimationId) {
    cancelAnimationFrame(beeAnimationId)
  }
})

async function fetchUserProfile(token) {
  try {
    const res = await fetch('/api/v1/auth/me', {
      headers: { Authorization: `Bearer ${token}` }
    })
    if (res.ok) {
      currentUser.value = await res.json()
      isAuthenticated.value = true
    } else {
      localStorage.removeItem('beejepa_token')
      isAuthenticated.value = false
    }
  } catch (err) {
    isAuthenticated.value = false
  }
}

async function loadSystemAndData() {
  try {
    const [storageRes, timelineRes, eventsRes] = await Promise.all([
      fetch('/api/v1/storage'),
      fetch('/api/v1/timeline'),
      fetch('/api/v1/events')
    ])
    if (storageRes.ok) storageStatus.value = await storageRes.json()
    if (timelineRes.ok) {
      const data = await timelineRes.json()
      timelineData.value = data.timeline || []
      timelineMeta.value = data
    }
    if (eventsRes.ok) {
      const list = await eventsRes.json()
      events.value = list
      if (list.length > 0) {
        selectEvent(list[0])
      }
    }
  } catch (err) {
    console.debug('Failed loading initial data:', err)
  }
}

function selectEvent(ev) {
  selectedEvent.value = ev
}

function selectEventById(id) {
  const found = events.value.find(e => e.eventId === id)
  if (found) {
    selectEvent(found)
  }
}

async function runMetaAnalysis() {
  if (!selectedEvent.value) return
  isAnalyzing.value = true
  try {
    const res = await fetch(`/api/v1/events/${selectedEvent.value.eventId}/analyze?forceRefresh=true`, {
      method: 'POST'
    })
    if (res.ok) {
      const data = await res.json()
      selectedEvent.value.agentResult = data
      selectedEvent.value.isCached = true
      // update in list
      const idx = events.value.findIndex(e => e.eventId === selectedEvent.value.eventId)
      if (idx !== -1) {
        events.value[idx].agentResult = data
        events.value[idx].isCached = true
      }
    }
  } catch (err) {
    console.error('Meta AI analysis failed:', err)
  } finally {
    isAnalyzing.value = false
  }
}

// Media URLs for current event
const rawClipUrl = computed(() => {
  if (!selectedEvent.value) return ''
  return `/api/v1/events/${selectedEvent.value.eventId}/media/clip_raw.mp4`
})

const comparisonClipUrl = computed(() => {
  if (!selectedEvent.value) return ''
  return `/api/v1/events/${selectedEvent.value.eventId}/media/clip_comparison.mp4`
})

const peakFrameUrl = computed(() => {
  if (!selectedEvent.value) return ''
  return `/api/v1/events/${selectedEvent.value.eventId}/media/peak_frame.jpg`
})

// Current video source based on view toggle
const activeMediaSource = computed(() => {
  if (activeView.value === 'raw') return rawClipUrl.value
  if (activeView.value === 'comparison') return comparisonClipUrl.value
  return ''
})

// Hotspot Bounding Box style in percentage
const hotspotBoxStyle = computed(() => {
  if (!selectedEvent.value?.hotspot) return {}
  const hs = selectedEvent.value.hotspot
  const cx = hs.normCenterX * 100
  const cy = hs.normCenterY * 100
  const w = 16
  const h = 16
  return {
    left: `${Math.max(0, Math.min(100 - w, cx - w / 2)).toFixed(1)}%`,
    top: `${Math.max(0, Math.min(100 - h, cy - h / 2)).toFixed(1)}%`,
    width: `${w}%`,
    height: `${h}%`
  }
})

const maxDuration = computed(() => timelineMeta.value?.totalDurationSec || 2250)
const maxScore = 5.5
const svgWidth = 1000
const svgHeight = 100

// Area path for gradient fill under the curve
const svgAreaPath = computed(() => {
  if (!timelineData.value || timelineData.value.length === 0) return ''
  const points = timelineData.value.map(pt => {
    const x = (pt.timestampSec / maxDuration.value) * svgWidth
    const y = svgHeight - 8 - (Math.min(pt.surpriseScoreL2, maxScore) / maxScore) * (svgHeight - 16)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })

  return `M 0,${svgHeight} L ${points.join(' L ')} L ${svgWidth},${svgHeight} Z`
})

// Stroke path for the fever curve
const svgCurvePath = computed(() => {
  if (!timelineData.value || timelineData.value.length === 0) return ''
  const points = timelineData.value.map(pt => {
    const x = (pt.timestampSec / maxDuration.value) * svgWidth
    const y = svgHeight - 8 - (Math.min(pt.surpriseScoreL2, maxScore) / maxScore) * (svgHeight - 16)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })

  return `M ${points.join(' L ')}`
})

// Spikes with exact HTML coordinates (to keep circles 100% round)
const timelineSpikes = computed(() => {
  if (!timelineData.value) return []
  return timelineData.value
    .filter(pt => pt.eventId)
    .map(pt => {
      const normY = 1.0 - (Math.min(pt.surpriseScoreL2, maxScore) / maxScore) * 0.84 - 0.08
      return {
        ...pt,
        normX: pt.timestampSec / maxDuration.value,
        normY
      }
    })
})

function formatTime(sec) {
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

async function handleLogin() {
  loginError.value = ''
  isLoggingIn.value = true

  try {
    const res = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: loginUsername.value,
        password: loginPassword.value
      })
    })

    const data = await res.json()
    if (res.ok && data.success) {
      localStorage.setItem('beejepa_token', data.token)
      currentUser.value = { username: data.username, role: data.role }
      isAuthenticated.value = true
      loginPassword.value = ''
      await loadSystemAndData()
    } else {
      loginError.value = data.message || 'Invalid username or password'
    }
  } catch (err) {
    loginError.value = 'Failed to connect to beeJEPA server at port 8080.'
  } finally {
    isLoggingIn.value = false
  }
}

async function handleLogout() {
  const token = localStorage.getItem('beejepa_token')
  if (token) {
    try {
      await fetch('/api/v1/auth/logout', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` }
      })
    } catch (e) {}
  }
  localStorage.removeItem('beejepa_token')
  isAuthenticated.value = false
  currentUser.value = null
}
</script>

<template>
  <div class="min-h-screen bg-[#080c14] text-slate-100 font-sans flex flex-col selection:bg-amber-500 selection:text-black">
    <!-- ================================================================= -->
    <!-- VIEW 1: BEEKEEPER LOGIN                                           -->
    <!-- ================================================================= -->
    <div v-if="!isAuthenticated" class="flex-1 flex flex-col items-center justify-center p-6 relative overflow-hidden">
      <!-- Ambient Glow Behind Modal -->
      <div class="absolute -top-32 left-1/2 -translate-x-1/2 w-96 h-96 bg-amber-500/10 rounded-full blur-3xl pointer-events-none"></div>

      <!-- Animated Real-Time Bee Swarm Simulation Canvas -->
      <canvas
        ref="beeCanvasRef"
        class="absolute inset-0 w-full h-full pointer-events-none z-0"
      ></canvas>

      <div class="w-full max-w-md bg-[#0f172a]/95 backdrop-blur-md border border-slate-800 rounded-2xl p-6 sm:p-8 shadow-2xl relative z-10">
        <!-- Logo & Title -->
        <div class="flex items-center gap-3 mb-6">
          <div class="w-11 h-11 rounded-xl bg-amber-500/15 border border-amber-500/30 p-1.5 flex items-center justify-center shrink-0">
            <img :src="beeLogo" alt="BeeJEPA Logo" class="w-full h-full object-contain drop-shadow" />
          </div>
          <div>
            <h1 class="text-xl font-bold text-white tracking-tight">BeeJEPA</h1>
          </div>
        </div>

        <!-- Error Banner -->
        <div v-if="loginError" class="mb-4 p-3 bg-rose-500/10 border border-rose-500/30 rounded-lg text-rose-400 text-xs">
          {{ loginError }}
        </div>

        <!-- Login Form -->
        <form @submit.prevent="handleLogin" class="space-y-4">
          <div>
            <label class="block text-xs font-medium text-slate-300 mb-1.5">Beekeeper Username</label>
            <input
              v-model="loginUsername"
              type="text"
              required
              autocomplete="username"
              class="w-full px-3.5 py-2.5 bg-[#0b0f19] border border-slate-700 rounded-lg text-base sm:text-sm text-white focus:outline-none focus:border-amber-400 transition-colors"
              placeholder="beekeeper"
            />
          </div>

          <div>
            <label class="block text-xs font-medium text-slate-300 mb-1.5">Password</label>
            <input
              v-model="loginPassword"
              type="password"
              required
              autocomplete="current-password"
              class="w-full px-3.5 py-2.5 bg-[#0b0f19] border border-slate-700 rounded-lg text-base sm:text-sm text-white focus:outline-none focus:border-amber-400 transition-colors"
              placeholder="••••••••••••"
            />
          </div>

          <button
            type="submit"
            :disabled="isLoggingIn"
            class="w-full py-2.5 px-4 bg-amber-500 hover:bg-amber-400 disabled:opacity-50 text-slate-950 font-semibold text-sm rounded-lg transition-colors shadow-lg shadow-amber-500/10 flex items-center justify-center gap-2 mt-2"
          >
            <span v-if="isLoggingIn" class="w-4 h-4 border-2 border-slate-950 border-t-transparent rounded-full animate-spin"></span>
            <span>Sign In</span>
          </button>
        </form>

        <!-- Initial Credentials Note -->
        <div class="mt-6 pt-5 border-t border-slate-800 text-[11px] text-slate-400 text-center">
          Default credentials: <code class="text-amber-400 font-mono">beekeeper</code> / <code class="text-amber-400 font-mono">beejepa2026</code>
        </div>
      </div>
    </div>

    <!-- ================================================================= -->
    <!-- VIEW 2: AUTHENTICATED HACKATHON DASHBOARD                         -->
    <!-- ================================================================= -->
    <div v-else class="flex-1 flex flex-col min-h-screen lg:h-screen lg:overflow-hidden bg-[#080c14]">
      <!-- Responsive Header -->
      <header class="border-b border-slate-800/80 bg-[#0c121e] px-4 sm:px-6 py-2.5 sm:py-3 flex flex-wrap items-center justify-between gap-3 shrink-0">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 rounded-lg bg-amber-500/15 border border-amber-500/30 p-1 flex items-center justify-center shrink-0">
            <img :src="beeLogo" alt="BeeJEPA Logo" class="w-full h-full object-contain drop-shadow" />
          </div>
          <div class="flex items-center gap-2">
            <h1 class="text-sm font-semibold text-white tracking-tight">BeeJEPA</h1>
            <span class="text-[10px] font-mono uppercase bg-slate-800 text-slate-300 px-2 py-0.5 rounded border border-slate-700 whitespace-nowrap">
              <span class="hidden sm:inline">Raspberry </span>Pi 4
            </span>
          </div>
        </div>

        <!-- Telemetry Badges (Responsive row) -->
        <div class="flex items-center gap-2 sm:gap-3 text-xs flex-wrap sm:flex-nowrap">
          <!-- SSD Storage Status -->
          <div class="flex items-center gap-1.5 sm:gap-2 px-2.5 py-1 rounded-md bg-slate-900 border border-slate-800">
            <span class="w-2 h-2 rounded-full" :class="storageStatus.isMounted ? 'bg-emerald-400' : 'bg-slate-600'"></span>
            <span class="text-slate-400">SSD:</span>
            <span class="font-mono text-slate-200 text-[11px]">
              {{ storageStatus.isMounted ? storageStatus.freeSpaceFormatted + ' free' : 'Not Connected' }}
            </span>
          </div>

          <!-- Meta Model APIs Status -->
          <div class="hidden md:flex items-center gap-2 px-2.5 py-1 rounded-md bg-slate-900 border border-slate-800">
            <span class="w-2 h-2 rounded-full bg-emerald-400"></span>
            <span class="text-slate-400">Meta APIs:</span>
            <span class="font-mono text-slate-300 text-[11px]">SAM 3.1 & Muse Ready</span>
          </div>

          <!-- Voice / Bee Mode Button Group -->
          <div class="flex items-center gap-1 bg-slate-900/90 border border-slate-800 rounded-lg p-0.5">
            <!-- Ask Muse Microphone Button (Live Device Mic) -->
            <button
              @click="toggleMicrophoneListening"
              title="Voice Query: Click to speak into device microphone"
              :class="isRecording ? 'bg-rose-500/25 text-rose-300 border border-rose-500/50 shadow-sm shadow-rose-500/20' : (isProcessing ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40' : 'text-slate-300 hover:text-white hover:bg-slate-800 border border-transparent')"
              class="flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs transition-all active:scale-95"
            >
              <span v-if="isRecording" class="w-2 h-2 rounded-full bg-rose-500 animate-ping"></span>
              <svg v-else class="w-3.5 h-3.5 text-rose-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
              </svg>
              <span class="hidden sm:inline font-mono text-[11px] font-medium">
                {{ isRecording ? 'Listening...' : (isProcessing ? 'Thinking...' : 'Ask Muse') }}
              </span>
            </button>

            <!-- Bee Mode Button: 🐝 -->
            <button
              @click="toggleBeeMode"
              title="Bee Mode: Mikrofon aktivieren und mit Bienenstock sprechen"
              :class="isRecording ? 'bg-amber-500/25 text-amber-300 border border-amber-500/50 shadow-sm shadow-amber-500/20' : (isBeeModePlaying ? 'bg-amber-500/25 text-amber-300 border border-amber-500/50' : (isProcessing ? 'bg-amber-500/20 text-amber-300 border border-amber-500/40' : 'text-slate-300 hover:text-amber-400 hover:bg-slate-800 border border-transparent'))"
              class="flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs transition-all active:scale-95"
            >
              <span class="text-sm select-none" :class="{ 'animate-bounce': isRecording || isBeeModePlaying }">🐝</span>
              <span class="font-mono text-[11px] font-medium">
                {{ isRecording ? 'Listening...' : (isProcessing ? 'Thinking...' : (isBeeModePlaying ? 'Answering...' : 'Bee Mode')) }}
              </span>
              <span v-if="isBeeModePlaying" class="flex gap-0.5 items-end h-2.5 ml-0.5">
                <span class="w-0.5 bg-amber-400 h-2.5 animate-pulse"></span>
                <span class="w-0.5 bg-amber-400 h-1.5 animate-pulse delay-75"></span>
                <span class="w-0.5 bg-amber-400 h-3 animate-pulse delay-150"></span>
              </span>
            </button>
          </div>

          <!-- Beekeeper Profile & Logout -->
          <div class="flex items-center gap-2 sm:gap-3 pl-2 sm:pl-3 border-l border-slate-800">
            <span class="text-xs font-medium text-amber-400">{{ currentUser?.username || 'beekeeper' }}</span>
            <button
              @click="handleLogout"
              class="px-2 py-0.5 text-xs text-slate-400 hover:text-white bg-slate-800 hover:bg-slate-700 rounded transition-colors"
            >
              Logout
            </button>
          </div>
        </div>
      </header>

      <!-- Main Layout: Video Player + Meta AI Causal Analysis -->
      <main class="flex-1 p-3 sm:p-5 flex flex-col lg:grid lg:grid-cols-12 gap-4 lg:gap-5 overflow-y-auto lg:overflow-hidden">
        <!-- Left Column: Video & Event Viewer (7 Cols) -->
        <div class="lg:col-span-7 flex flex-col bg-[#0f172a] rounded-xl border border-slate-800 overflow-hidden shadow-xl shrink-0 lg:shrink">
          <!-- View Toggle Header (Responsive) -->
          <div class="px-3 sm:px-4 py-2.5 border-b border-slate-800/80 bg-slate-900/80 flex flex-col sm:flex-row sm:items-center justify-between gap-2 shrink-0">
            <!-- Tabs with Horizontal Scroll on Mobile -->
            <div class="flex items-center gap-1.5 sm:gap-2 overflow-x-auto no-scrollbar py-0.5">
              <button
                @click="activeView = 'comparison'"
                :class="activeView === 'comparison' ? 'bg-amber-500 text-slate-950 font-semibold' : 'text-slate-400 hover:text-white bg-slate-800/60'"
                class="px-2.5 sm:px-3 py-1 text-xs rounded transition-colors whitespace-nowrap shrink-0"
              >
                Side-by-Side: Pixel vs. V-JEPA
              </button>
              <button
                @click="activeView = 'raw'"
                :class="activeView === 'raw' ? 'bg-amber-500 text-slate-950 font-semibold' : 'text-slate-400 hover:text-white bg-slate-800/60'"
                class="px-2.5 sm:px-3 py-1 text-xs rounded transition-colors whitespace-nowrap shrink-0"
              >
                Raw 60fps Clip
              </button>
              <button
                @click="activeView = 'sam'"
                :class="activeView === 'sam' ? 'bg-amber-500 text-slate-950 font-semibold' : 'text-slate-400 hover:text-white bg-slate-800/60'"
                class="px-2.5 sm:px-3 py-1 text-xs rounded transition-colors whitespace-nowrap shrink-0"
              >
                SAM 3.1 Peak Frame
              </button>
            </div>

            <!-- Sequence & Time Info -->
            <div v-if="selectedEvent" class="flex sm:block items-center justify-between text-right text-[11px] font-mono text-slate-400 shrink-0">
              <span class="text-amber-400 font-semibold">{{ selectedEvent.eventId }}</span>
              <span class="mx-1.5">•</span>
              <span>{{ formatTime(selectedEvent.timestampSec) }} (f{{ selectedEvent.frameIndex }})</span>
            </div>
          </div>

          <!-- Video / Frame Canvas -->
          <div class="relative w-full aspect-[4/3] sm:aspect-video lg:aspect-auto lg:flex-1 bg-black flex items-center justify-center overflow-hidden min-h-[240px] sm:min-h-[300px]">
            <!-- Video Mode: Raw or Side-by-Side -->
            <video
              v-if="activeView !== 'sam' && activeMediaSource"
              ref="videoPlayer"
              :key="activeMediaSource"
              :src="activeMediaSource"
              autoplay
              loop
              muted
              playsinline
              controls
              class="w-full h-full object-contain"
            ></video>

            <!-- SAM 3.1 Peak Frame Mode -->
            <div v-else-if="activeView === 'sam' && peakFrameUrl" class="relative w-full h-full flex items-center justify-center">
              <img :src="peakFrameUrl" alt="SAM Peak Frame" class="w-full h-full object-contain" />
              <!-- Hotspot Bounding Box Overlay -->
              <div
                class="absolute border-2 border-amber-400 bg-amber-400/20 rounded shadow-lg pointer-events-none animate-pulse"
                :style="hotspotBoxStyle"
              >
                <div class="absolute -top-5 left-0 bg-amber-500 text-slate-950 font-mono text-[9px] font-bold px-1.5 py-0.5 rounded-t whitespace-nowrap">
                  SAM 3.1 Box Prompt
                </div>
              </div>
            </div>

            <!-- Empty State -->
            <div v-else class="text-center p-6 text-slate-500">
              <svg class="w-10 h-10 mx-auto mb-2 text-slate-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
              <p class="text-xs font-mono">No event media available.</p>
            </div>
          </div>
        </div>

        <!-- Right Column: Meta AI Causal Analysis & Metrics (5 Cols) -->
        <div class="lg:col-span-5 flex flex-col bg-[#0f172a] rounded-xl border border-slate-800 p-4 sm:p-5 overflow-y-auto shadow-xl">
          <div class="flex items-center justify-between pb-3 border-b border-slate-800 shrink-0">
            <h2 class="text-xs font-semibold text-slate-200 uppercase tracking-wider">Meta AI Causal Analysis</h2>
            <span
              v-if="selectedEvent?.agentResult"
              class="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/30"
            >
              SSD CACHED (0 CREDITS)
            </span>
            <span
              v-else
              class="text-[10px] font-mono px-2 py-0.5 rounded bg-amber-500/10 text-amber-400 border border-amber-500/30"
            >
              AWAITING INFERENCE
            </span>
          </div>

          <div v-if="selectedEvent" class="mt-4 space-y-4">
            <!-- V-JEPA Latent Metrics Grid -->
            <div class="grid grid-cols-2 gap-3">
              <div class="p-3 bg-slate-900/80 rounded-lg border border-slate-800">
                <div class="text-[10px] font-mono text-slate-400 uppercase">V-JEPA Surprise Score</div>
                <div class="text-xl font-bold font-mono text-rose-400 mt-1">
                  {{ Number(selectedEvent.surpriseScoreL2).toFixed(2) }}σ
                </div>
                <div class="text-[10px] text-slate-500 mt-0.5">Latent Error ||ẑ - z||₂</div>
              </div>

              <div class="p-3 bg-slate-900/80 rounded-lg border border-slate-800">
                <div class="text-[10px] font-mono text-slate-400 uppercase">Pixel-Diff Baseline</div>
                <div class="text-xl font-bold font-mono text-slate-300 mt-1">
                  {{ Number(selectedEvent.pixelDiffBaseline).toFixed(4) }}
                </div>
                <div class="text-[10px] text-slate-500 mt-0.5">|I(t+Δt) - I(t)|</div>
              </div>
            </div>

            <!-- Tubelet Hotspot Location -->
            <div class="p-3 bg-slate-900/80 rounded-lg border border-slate-800 text-xs">
              <div class="flex items-center justify-between text-slate-400 mb-1">
                <span>Tubelet Hotspot Grid:</span>
                <span class="font-mono text-amber-400 font-semibold">
                  ({{ selectedEvent.hotspot.tubeletGridX }}, {{ selectedEvent.hotspot.tubeletGridY }})
                </span>
              </div>
              <div class="flex items-center justify-between text-slate-400">
                <span>Normalized Center:</span>
                <span class="font-mono text-slate-200">
                  ({{ Number(selectedEvent.hotspot.normCenterX).toFixed(3) }}, {{ Number(selectedEvent.hotspot.normCenterY).toFixed(3) }})
                </span>
              </div>
            </div>

            <!-- Trigger Button -->
            <button
              @click="runMetaAnalysis"
              :disabled="isAnalyzing"
              class="w-full py-2.5 px-4 bg-amber-500 hover:bg-amber-400 disabled:opacity-50 text-slate-950 font-semibold text-xs rounded-lg transition-colors shadow-lg shadow-amber-500/10 flex items-center justify-center gap-2"
            >
              <span v-if="isAnalyzing" class="w-4 h-4 border-2 border-slate-950 border-t-transparent rounded-full animate-spin"></span>
              <span>{{ selectedEvent?.agentResult ? '🐝 Re-run Live Inference' : '🐝 Analyze Anomaly with Meta AI' }}</span>
            </button>

            <!-- SAM 3.1 Segmentation Isolation Card -->
            <div v-if="selectedEvent.agentResult?.sam" class="p-3.5 bg-slate-900/90 rounded-lg border border-slate-800 space-y-1.5">
              <div class="flex items-center justify-between">
                <span class="text-[11px] font-semibold text-amber-400">SAM 3.1 Instance Segmentation</span>
                <span class="text-[10px] font-mono text-slate-400">{{ selectedEvent.agentResult.sam.model }}</span>
              </div>
              <p class="text-xs text-slate-300">
                Isolated Object: <span class="font-semibold text-white">{{ selectedEvent.agentResult.sam.segmentedObject }}</span>
              </p>
              <p class="text-[11px] text-slate-400 font-mono">
                Instances: {{ selectedEvent.agentResult.sam.instanceCount }} • Box Prompt Applied
              </p>
            </div>

            <!-- Muse Spark Causal Scene Diagnosis Card -->
            <div v-if="selectedEvent.agentResult?.muse" class="p-3.5 bg-slate-900/90 rounded-lg border border-amber-500/30 space-y-2">
              <div class="flex items-center justify-between">
                <span class="text-[11px] font-semibold text-amber-400">Muse Spark Causal Diagnosis</span>
                <span class="text-[10px] font-mono text-slate-400">Confidence: {{ Math.round(selectedEvent.agentResult.muse.confidence * 100) }}%</span>
              </div>
              <p class="text-xs text-slate-200 leading-relaxed italic bg-[#0b0f19] p-3 rounded border border-slate-800">
                "{{ selectedEvent.agentResult.muse.diagnosis }}"
              </p>
            </div>
          </div>

          <!-- Empty State when no event is selected / present -->
          <div v-else class="flex-1 flex flex-col items-center justify-center text-center p-6 text-slate-500 my-auto">
            <div class="w-12 h-12 rounded-xl border border-slate-800 bg-slate-900/60 flex items-center justify-center text-slate-600 mb-3">
              <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </div>
            <p class="text-xs font-mono text-slate-400">No Events Detected</p>
            <p class="text-[11px] text-slate-500 max-w-xs mt-1">
              Awaiting anomaly events on SSD storage.
            </p>
          </div>
        </div>
      </main>

      <!-- Bottom: 37.5-Minute Anomaly Fever Curve (High Quality & Responsive) -->
      <footer class="border-t border-slate-800/80 bg-[#0c121e] px-4 sm:px-6 py-3 shrink-0">
        <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-1 mb-2 text-xs">
          <div class="flex items-center gap-2.5">
            <span class="font-semibold text-slate-200">37.5-Minute Latent Surprise Timeline (rec2123)</span>
            <span class="text-[10px] font-mono text-rose-400 bg-rose-500/10 border border-rose-500/20 px-1.5 py-0.5 rounded">
              Threshold τ = 3.0σ
            </span>
          </div>
          <div class="text-[11px] text-slate-400 font-mono hidden sm:block">
            Click peak hotspot to inspect anomaly
          </div>
        </div>

        <!-- SVG Timeline Curve Container with Beautiful Gradient & Breathing Room -->
        <div class="relative w-full h-24 sm:h-28 bg-[#080c14] rounded-lg border border-slate-800 px-3 py-1 flex items-center overflow-hidden shadow-inner">
          <div v-if="!timelineData || timelineData.length === 0" class="absolute inset-0 flex items-center justify-center text-slate-500 text-xs font-mono">
            Awaiting timeline artifacts from SSD
          </div>

          <svg v-else viewBox="0 0 1000 100" preserveAspectRatio="none" class="w-full h-full">
            <defs>
              <!-- Gradient under the fever curve -->
              <linearGradient id="curveGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#f59e0b" stop-opacity="0.3" />
                <stop offset="60%" stop-color="#f59e0b" stop-opacity="0.08" />
                <stop offset="100%" stop-color="#f59e0b" stop-opacity="0.0" />
              </linearGradient>
            </defs>

            <!-- Grid Lines -->
            <line x1="0" y1="92" x2="1000" y2="92" stroke="#1e293b" stroke-width="1" />
            <line x1="0" y1="54" x2="1000" y2="54" stroke="#f43f5e" stroke-dasharray="4 4" stroke-width="1" opacity="0.4" />

            <!-- Filled Area under Curve -->
            <path
              v-if="svgAreaPath"
              :d="svgAreaPath"
              fill="url(#curveGradient)"
            />

            <!-- Main Curve Line -->
            <path
              v-if="svgCurvePath"
              :d="svgCurvePath"
              fill="none"
              stroke="#f59e0b"
              stroke-width="2"
              vector-effect="non-scaling-stroke"
            />
          </svg>

          <!-- Hotspot Interactive HTML Pins (100% Perfectly Round, Never Squished) -->
          <div
            v-for="spike in timelineSpikes"
            :key="spike.eventId"
            class="absolute -translate-x-1/2 -translate-y-1/2 cursor-pointer group z-20"
            :style="{ left: `${spike.normX * 100}%`, top: `${spike.normY * 100}%` }"
            @click="selectEventById(spike.eventId)"
          >
            <!-- Pin Dot -->
            <div class="relative flex items-center justify-center p-2">
              <span v-if="selectedEvent?.eventId === spike.eventId" class="absolute w-6 h-6 rounded-full bg-rose-500/40 animate-ping"></span>
              <span
                class="w-3.5 h-3.5 rounded-full border-2 transition-transform group-hover:scale-125"
                :class="selectedEvent?.eventId === spike.eventId ? 'bg-rose-500 border-white ring-2 ring-rose-400' : 'bg-amber-400 border-slate-900 shadow-md'"
              ></span>
            </div>

            <!-- Tooltip Hover Badge -->
            <div class="absolute -top-7 left-1/2 -translate-x-1/2 opacity-0 group-hover:opacity-100 transition-opacity bg-slate-900 border border-slate-700 px-2 py-0.5 rounded text-[10px] font-mono text-amber-300 whitespace-nowrap shadow-xl pointer-events-none">
              {{ spike.surpriseScoreL2 }}σ ({{ formatTime(spike.timestampSec) }})
            </div>
          </div>
        </div>

        <!-- Timeline Time Markers -->
        <div class="flex justify-between text-[10px] font-mono text-slate-500 px-3 pt-1">
          <span>0:00 (Start)</span>
          <span class="hidden sm:inline">7:30</span>
          <span>15:00</span>
          <span class="hidden sm:inline">22:30</span>
          <span class="hidden sm:inline">30:00</span>
          <span>37:30 (End)</span>
        </div>
      </footer>
    </div>
  </div>
</template>
