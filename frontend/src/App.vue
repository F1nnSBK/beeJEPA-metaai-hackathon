<script setup>
import { ref, onMounted } from 'vue'

const isPlaying = ref(true)
const selectedTime = ref('16:42:15')
const surpriseScore = ref(0.24)
const storageStatus = ref({
  isSymlink: true,
  targetRealPath: '/mnt/ssd/beejepa_data',
  freeSpaceFormatted: '412.50 GiB'
})

const channels = ref([
  {
    id: 1,
    title: 'Camera 1: Hive Entrance (Raw 60 FPS)',
    badge: 'LIVE INPUT',
    badgeColor: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
    description: 'High-frequency optical stream (200Hz wing-beat resolving)'
  },
  {
    id: 2,
    title: 'V-JEPA 2.1 Latent Forecast',
    badge: 'LATENT EMBEDDING',
    badgeColor: 'bg-cyan-500/10 text-cyan-400 border-cyan-500/20',
    description: 'Spatio-temporal tubelet embedding forecast ẑ(t+Δt)'
  },
  {
    id: 3,
    title: 'Anomaly Localization (Surprise)',
    badge: 'SURPRISE DETECTOR',
    badgeColor: 'bg-amber-500/10 text-amber-400 border-amber-500/20',
    description: 'Residual prediction error: Landing crashes & aggressive interactions'
  }
])

const timelineEvents = ref([
  { time: '16:38:10', type: 'normal', label: 'Routine Fanning' },
  { time: '16:39:45', type: 'anomaly', label: 'Landing Crash (#42)' },
  { time: '16:41:20', type: 'normal', label: 'Pollen Forager Inflow' },
  { time: '16:42:05', type: 'anomaly', label: 'Robber Bee Clash' },
  { time: '16:43:30', type: 'normal', label: 'Steady State' }
])

onMounted(async () => {
  try {
    const res = await fetch('/api/v1/storage')
    if (res.ok) {
      storageStatus.value = await res.json()
    }
  } catch (err) {
    console.debug('Running in standalone Vite dev mode, using placeholder storage status.')
  }
})
</script>

<template>
  <div class="min-h-screen flex flex-col bg-[#0b0f19] text-slate-100 font-sans">
    <!-- Top Navigation Bar -->
    <header class="h-16 border-b border-slate-800 bg-[#0f172a]/70 backdrop-blur-md px-6 flex items-center justify-between shrink-0">
      <div class="flex items-center gap-3">
        <div class="w-8 h-8 rounded-lg bg-amber-500/20 border border-amber-500/30 flex items-center justify-center text-amber-400 font-bold">
          🐝
        </div>
        <div>
          <div class="flex items-center gap-2">
            <h1 class="text-base font-semibold tracking-wide text-white">beeJEPA</h1>
            <span class="text-[10px] font-mono uppercase bg-amber-500/10 text-amber-400 px-2 py-0.5 rounded border border-amber-500/20">Edge Node</span>
          </div>
          <p class="text-xs text-slate-400 font-mono">Raspberry Pi 5 • Micronaut AOT • Virtual Threads</p>
        </div>
      </div>

      <!-- System Status Badges -->
      <div class="flex items-center gap-3 text-xs">
        <div class="flex items-center gap-2 px-3 py-1.5 rounded-md bg-slate-900 border border-slate-800">
          <span class="w-2 h-2 rounded-full bg-emerald-400 animate-pulse"></span>
          <span class="text-slate-300 font-mono">SSD Storage:</span>
          <span class="text-emerald-400 font-mono truncate max-w-[140px]">{{ storageStatus.targetRealPath }}</span>
          <span class="text-slate-500 font-mono">({{ storageStatus.freeSpaceFormatted }})</span>
        </div>

        <div class="flex items-center gap-2 px-3 py-1.5 rounded-md bg-slate-900 border border-slate-800">
          <span class="text-slate-400">Meta Muse API:</span>
          <span class="text-cyan-400 font-semibold font-mono">ONLINE</span>
        </div>

        <div class="flex items-center gap-2 px-3 py-1.5 rounded-md bg-slate-900 border border-slate-800">
          <span class="text-slate-400">Surprise Index:</span>
          <span :class="surpriseScore > 0.5 ? 'text-rose-400' : 'text-emerald-400'" class="font-mono font-bold">
            {{ surpriseScore.toFixed(2) }}
          </span>
        </div>
      </div>
    </header>

    <!-- Main Content Area: 3-Column Video Layout Placeholder -->
    <main class="flex-1 p-6 grid grid-cols-1 lg:grid-cols-3 gap-6 overflow-hidden">
      <div
        v-for="channel in channels"
        :key="channel.id"
        class="flex flex-col bg-[#111827] rounded-xl border border-slate-800 overflow-hidden shadow-xl"
      >
        <!-- Card Header -->
        <div class="px-4 py-3 border-b border-slate-800 flex items-center justify-between bg-slate-900/50">
          <span class="text-xs font-medium text-slate-200 truncate">{{ channel.title }}</span>
          <span class="text-[10px] font-mono px-2 py-0.5 rounded border" :class="channel.badgeColor">
            {{ channel.badge }}
          </span>
        </div>

        <!-- Video Placeholder Container -->
        <div class="relative flex-1 bg-black flex items-center justify-center min-h-[300px] overflow-hidden group">
          <!-- Subtle Grid Background -->
          <div class="absolute inset-0 bg-[radial-gradient(#1e293b_1px,transparent_1px)] [background-size:16px_16px] opacity-40"></div>

          <!-- Video Center Graphic / Crosshair -->
          <div class="relative z-10 flex flex-col items-center gap-3 text-slate-500">
            <div class="w-16 h-16 rounded-full border border-dashed border-slate-700 flex items-center justify-center group-hover:border-amber-500/50 transition-colors">
              <svg class="w-6 h-6 text-slate-600 group-hover:text-amber-400 transition-colors" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
            </div>
            <span class="text-xs font-mono text-slate-400">Stream Channel #{{ channel.id }}</span>
          </div>

          <!-- Live HUD Overlay -->
          <div class="absolute top-3 left-3 flex items-center gap-2 bg-black/60 backdrop-blur px-2.5 py-1 rounded text-[11px] font-mono text-slate-300 border border-white/10">
            <span class="w-2 h-2 rounded-full bg-red-500 animate-ping"></span>
            <span>REC 60 FPS</span>
          </div>

          <div class="absolute top-3 right-3 bg-black/60 backdrop-blur px-2.5 py-1 rounded text-[11px] font-mono text-slate-400 border border-white/10">
            1920x1080
          </div>

          <div class="absolute bottom-3 left-3 right-3 bg-black/60 backdrop-blur px-3 py-1.5 rounded text-[11px] font-mono text-slate-400 border border-white/10 truncate">
            {{ channel.description }}
          </div>
        </div>
      </div>
    </main>

    <!-- Bottom Timeline Component Placeholder -->
    <footer class="h-44 border-t border-slate-800 bg-[#0f172a] px-6 py-4 flex flex-col justify-between shrink-0">
      <!-- Timeline Header & Controls -->
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-4">
          <button
            @click="isPlaying = !isPlaying"
            class="flex items-center gap-2 px-3 py-1.5 bg-amber-500 hover:bg-amber-600 text-slate-950 font-semibold text-xs rounded transition-colors"
          >
            <span>{{ isPlaying ? 'Pause' : 'Play' }}</span>
          </button>
          <div class="text-xs font-mono text-slate-400">
            Current Scrubber: <span class="text-white font-bold">{{ selectedTime }}</span>
          </div>
        </div>

        <div class="flex items-center gap-4 text-xs font-mono">
          <div class="flex items-center gap-2">
            <span class="w-3 h-3 rounded-full bg-rose-500/30 border border-rose-500"></span>
            <span class="text-slate-400">Surprise Spike (Anomaly)</span>
          </div>
          <div class="flex items-center gap-2">
            <span class="w-3 h-3 rounded-full bg-cyan-500/30 border border-cyan-500"></span>
            <span class="text-slate-400">Routine Dynamics</span>
          </div>
        </div>
      </div>

      <!-- Scrubber Track & Markers -->
      <div class="relative w-full h-16 bg-[#0b0f19] rounded-lg border border-slate-800 p-2 flex flex-col justify-between overflow-hidden">
        <!-- Event Markers on Timeline -->
        <div class="relative w-full h-8 flex items-center">
          <div class="absolute inset-x-0 h-1 bg-slate-800 rounded"></div>

          <!-- Anomaly spikes and event points -->
          <div
            v-for="(event, idx) in timelineEvents"
            :key="idx"
            class="absolute transform -translate-x-1/2 flex flex-col items-center group cursor-pointer"
            :style="{ left: `${(idx + 1) * 16}%` }"
          >
            <div
              class="w-3.5 h-3.5 rounded-full border-2 transition-transform group-hover:scale-125"
              :class="event.type === 'anomaly' ? 'bg-rose-500 border-rose-300 shadow-[0_0_8px_rgba(244,63,94,0.6)]' : 'bg-cyan-500 border-cyan-300'"
            ></div>
            <span class="text-[9px] font-mono text-slate-500 group-hover:text-slate-300 mt-1 whitespace-nowrap">
              {{ event.time }}
            </span>
          </div>

          <!-- Current Playhead Scrubber -->
          <div class="absolute left-1/2 h-10 w-0.5 bg-amber-400 shadow-[0_0_10px_#f59e0b] -top-1 pointer-events-none">
            <div class="w-2.5 h-2.5 bg-amber-400 rotate-45 -ml-1 -top-1"></div>
          </div>
        </div>

        <!-- Timeline Tick Labels -->
        <div class="flex justify-between text-[10px] font-mono text-slate-500 px-1 border-t border-slate-800/60 pt-1">
          <span>16:35:00</span>
          <span>16:37:30</span>
          <span>16:40:00</span>
          <span class="text-amber-400 font-bold">16:42:15 (NOW)</span>
          <span>16:45:00</span>
          <span>16:47:30</span>
          <span>16:50:00</span>
        </div>
      </div>
    </footer>
  </div>
</template>
