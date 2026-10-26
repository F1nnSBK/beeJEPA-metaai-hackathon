<script setup>
import { ref, onMounted, computed } from 'vue'
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
  isMounted: true,
  freeSpaceFormatted: 'Connected',
  isWritable: true
})

// Timeline & Events state
const timelineData = ref([])
const timelineMeta = ref(null)
const events = ref([])
const selectedEvent = ref(null)
const activeView = ref('comparison') // 'raw', 'comparison', 'sam'
const isAnalyzing = ref(false)
const videoPlayer = ref(null)

onMounted(async () => {
  const token = localStorage.getItem('beejepa_token')
  if (token) {
    await fetchUserProfile(token)
  }
  await loadSystemAndData()
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
  return {
    left: `${Math.round(hs.normCenterX * 100) - 10}%`,
    top: `${Math.round(hs.normCenterY * 100) - 10}%`,
    width: '20%',
    height: '20%'
  }
})

// SVG Fever curve path generator
const svgCurvePath = computed(() => {
  if (!timelineData.value || timelineData.value.length === 0) return ''
  const maxTime = timelineMeta.value?.totalDurationSec || 2250
  const width = 1000
  const height = 80
  const maxScore = 5.5

  const points = timelineData.value.map(pt => {
    const x = (pt.timestampSec / maxTime) * width
    const y = height - (Math.min(pt.surpriseScoreL2, maxScore) / maxScore) * (height - 10)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  })

  return `M 0,${height} L ${points.join(' L ')}`
})

// Spikes on the timeline
const timelineSpikes = computed(() => {
  if (!timelineData.value) return []
  const maxTime = timelineMeta.value?.totalDurationSec || 2250
  const width = 1000
  const height = 80
  const maxScore = 5.5

  return timelineData.value
    .filter(pt => pt.eventId)
    .map(pt => {
      const x = (pt.timestampSec / maxTime) * width
      const y = height - (Math.min(pt.surpriseScoreL2, maxScore) / maxScore) * (height - 10)
      return {
        ...pt,
        x,
        y
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
      <div class="absolute -top-32 left-1/2 -translate-x-1/2 w-96 h-96 bg-amber-500/10 rounded-full blur-3xl pointer-events-none"></div>

      <div class="w-full max-w-md bg-[#0f172a] border border-slate-800 rounded-2xl p-8 shadow-2xl relative z-10">
        <!-- Logo & Title -->
        <div class="flex items-center gap-3 mb-6">
          <div class="w-11 h-11 rounded-xl bg-amber-500/15 border border-amber-500/30 p-1.5 flex items-center justify-center">
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
              class="w-full px-3.5 py-2.5 bg-[#0b0f19] border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:border-amber-400 transition-colors"
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
              class="w-full px-3.5 py-2.5 bg-[#0b0f19] border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:border-amber-400 transition-colors"
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
    <div v-else class="flex-1 flex flex-col h-screen overflow-hidden">
      <!-- Header -->
      <header class="h-14 border-b border-slate-800/80 bg-[#0c121e] px-6 flex items-center justify-between shrink-0">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 rounded-lg bg-amber-500/15 border border-amber-500/30 p-1 flex items-center justify-center">
            <img :src="beeLogo" alt="BeeJEPA Logo" class="w-full h-full object-contain drop-shadow" />
          </div>
          <div class="flex items-center gap-2">
            <h1 class="text-sm font-semibold text-white tracking-tight">BeeJEPA</h1>
            <span class="text-[10px] font-mono uppercase bg-slate-800 text-slate-300 px-2 py-0.5 rounded border border-slate-700">
              {{ hardware }}
            </span>
          </div>
        </div>

        <!-- Telemetry Badges -->
        <div class="flex items-center gap-3 text-xs">
          <!-- SSD Storage Status -->
          <div class="flex items-center gap-2 px-3 py-1 rounded-md bg-slate-900 border border-slate-800">
            <span class="w-2 h-2 rounded-full" :class="storageStatus.isMounted ? 'bg-emerald-400' : 'bg-slate-600'"></span>
            <span class="text-slate-400">SSD:</span>
            <span class="font-mono text-slate-200">
              {{ storageStatus.isMounted ? (storageStatus.freeSpaceFormatted === 'Not Connected' ? 'Mock Data (Ready)' : storageStatus.freeSpaceFormatted + ' free') : 'Disconnected' }}
            </span>
          </div>

          <!-- Meta Model APIs Status -->
          <div class="flex items-center gap-2 px-3 py-1 rounded-md bg-slate-900 border border-slate-800">
            <span class="w-2 h-2 rounded-full bg-emerald-400"></span>
            <span class="text-slate-400">Meta APIs:</span>
            <span class="font-mono text-slate-300 text-[11px]">SAM 3.1 & Muse Spark Ready</span>
          </div>

          <!-- Beekeeper Profile & Logout -->
          <div class="flex items-center gap-3 pl-3 border-l border-slate-800">
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
      <main class="flex-1 p-5 grid grid-cols-1 lg:grid-cols-12 gap-5 overflow-hidden">
        <!-- Left Column: Video & Event Viewer (7 Cols) -->
        <div class="lg:col-span-7 flex flex-col bg-[#0f172a] rounded-xl border border-slate-800 overflow-hidden shadow-xl">
          <!-- View Toggle Header -->
          <div class="px-4 py-2.5 border-b border-slate-800/80 bg-slate-900/80 flex items-center justify-between shrink-0">
            <div class="flex items-center gap-2">
              <button
                @click="activeView = 'comparison'"
                :class="activeView === 'comparison' ? 'bg-amber-500 text-slate-950 font-semibold' : 'text-slate-400 hover:text-white bg-slate-800/60'"
                class="px-3 py-1 text-xs rounded transition-colors"
              >
                Side-by-Side: Pixel vs. V-JEPA
              </button>
              <button
                @click="activeView = 'raw'"
                :class="activeView === 'raw' ? 'bg-amber-500 text-slate-950 font-semibold' : 'text-slate-400 hover:text-white bg-slate-800/60'"
                class="px-3 py-1 text-xs rounded transition-colors"
              >
                Raw 60fps Clip
              </button>
              <button
                @click="activeView = 'sam'"
                :class="activeView === 'sam' ? 'bg-amber-500 text-slate-950 font-semibold' : 'text-slate-400 hover:text-white bg-slate-800/60'"
                class="px-3 py-1 text-xs rounded transition-colors"
              >
                SAM 3.1 Peak Frame
              </button>
            </div>

            <!-- Sequence & Time Info -->
            <div v-if="selectedEvent" class="text-right text-[11px] font-mono text-slate-400">
              <span class="text-amber-400 font-semibold">{{ selectedEvent.eventId }}</span>
              <span class="mx-1.5">•</span>
              <span>{{ formatTime(selectedEvent.timestampSec) }} (f{{ selectedEvent.frameIndex }})</span>
            </div>
          </div>

          <!-- Video / Frame Canvas -->
          <div class="relative flex-1 bg-black flex items-center justify-center overflow-hidden">
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
              class="max-h-full max-w-full object-contain"
            ></video>

            <!-- SAM 3.1 Peak Frame Mode -->
            <div v-else-if="activeView === 'sam' && peakFrameUrl" class="relative max-h-full max-w-full flex items-center justify-center">
              <img :src="peakFrameUrl" alt="SAM Peak Frame" class="max-h-full max-w-full object-contain" />
              <!-- Hotspot Bounding Box Overlay -->
              <div
                class="absolute border-2 border-amber-400 bg-amber-400/20 rounded shadow-lg pointer-events-none animate-pulse"
                :style="hotspotBoxStyle"
              >
                <div class="absolute -top-5 left-0 bg-amber-500 text-slate-950 font-mono text-[9px] font-bold px-1.5 py-0.5 rounded-t">
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
        <div class="lg:col-span-5 flex flex-col bg-[#0f172a] rounded-xl border border-slate-800 p-5 overflow-y-auto shadow-xl">
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
                  {{ selectedEvent.surpriseScoreL2 }}σ
                </div>
                <div class="text-[10px] text-slate-500 mt-0.5">Latent Error ||ẑ - z||₂</div>
              </div>

              <div class="p-3 bg-slate-900/80 rounded-lg border border-slate-800">
                <div class="text-[10px] font-mono text-slate-400 uppercase">Pixel-Diff Baseline</div>
                <div class="text-xl font-bold font-mono text-slate-300 mt-1">
                  {{ selectedEvent.pixelDiffBaseline }}
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
                  ({{ selectedEvent.hotspot.normCenterX }}, {{ selectedEvent.hotspot.normCenterY }})
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
              <span>{{ selectedEvent?.agentResult ? '⚡ Re-run Live Inference' : '⚡ Analyze Anomaly with Meta AI' }}</span>
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
        </div>
      </main>

      <!-- Bottom: 37.5-Minute Anomaly Fever Curve -->
      <footer class="h-32 border-t border-slate-800/80 bg-[#0c121e] px-6 py-2.5 flex flex-col justify-between shrink-0">
        <div class="flex items-center justify-between text-xs">
          <div class="flex items-center gap-3">
            <span class="font-semibold text-slate-200">37.5-Minute Latent Surprise Timeline (rec2123)</span>
            <span class="text-[11px] text-slate-400 font-mono">Threshold τ = 3.0σ</span>
          </div>
          <div class="text-[11px] text-slate-400 font-mono">
            Click any peak to load event clip & SAM prompt
          </div>
        </div>

        <!-- SVG Timeline Curve Container -->
        <div class="relative w-full h-16 bg-[#080c14] rounded-lg border border-slate-800 px-3 py-1 flex items-center overflow-hidden">
          <svg viewBox="0 0 1000 80" preserveAspectRatio="none" class="w-full h-full">
            <!-- Threshold Line at 3.0σ (y = 80 - (3.0/5.5)*70 = 41.8) -->
            <line x1="0" y1="42" x2="1000" y2="42" stroke="#f43f5e" stroke-dasharray="3 3" stroke-width="1" opacity="0.4" />

            <!-- Curve -->
            <path
              v-if="svgCurvePath"
              :d="svgCurvePath"
              fill="none"
              stroke="#f59e0b"
              stroke-width="1.8"
            />

            <!-- Event Spike Hotspots -->
            <g v-for="spike in timelineSpikes" :key="spike.eventId">
              <circle
                :cx="spike.x"
                :cy="spike.y"
                r="5"
                class="cursor-pointer transition-transform hover:scale-150"
                :class="selectedEvent?.eventId === spike.eventId ? 'fill-rose-500 stroke-white stroke-2' : 'fill-amber-400 stroke-slate-900 stroke-1'"
                @click="selectEventById(spike.eventId)"
              />
            </g>
          </svg>
        </div>

        <!-- Timeline Time Markers -->
        <div class="flex justify-between text-[10px] font-mono text-slate-500 px-2 pt-0.5">
          <span>0:00 (Start)</span>
          <span>7:30</span>
          <span>15:00</span>
          <span>22:30</span>
          <span>30:00</span>
          <span>37:30 (End)</span>
        </div>
      </footer>
    </div>
  </div>
</template>
