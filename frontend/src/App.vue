<script setup>
import { ref, onMounted } from 'vue'
import beeLogo from './assets/bee.png'

// Auth state
const isAuthenticated = ref(false)
const currentUser = ref(null)
const loginUsername = ref('beekeeper')
const loginPassword = ref('')
const loginError = ref('')
const isLoggingIn = ref(false)

// Hardware & System state
const hardware = ref('Raspberry Pi 4 Model B')
const storageStatus = ref({
  isMounted: false,
  freeSpaceFormatted: 'Not Connected',
  isWritable: false
})

const museStatus = ref({
  status: 'DORMANT_OFFLINE',
  creditsConsumed: 0
})

// Real-time camera channels (Clean minimal state, no mock text)
const channels = ref([
  {
    id: 1,
    title: 'Camera 1: Hive Entrance',
    status: 'OFFLINE',
    statusColor: 'bg-amber-500/10 text-amber-400 border-amber-500/30'
  },
  {
    id: 2,
    title: 'V-JEPA Latent Predictor',
    status: 'IDLE',
    statusColor: 'bg-slate-500/10 text-slate-400 border-slate-700'
  },
  {
    id: 3,
    title: 'Spatio-Temporal Anomaly Detector',
    status: 'STANDBY',
    statusColor: 'bg-slate-500/10 text-slate-400 border-slate-700'
  }
])

const timelineEvents = ref([])

onMounted(async () => {
  const token = localStorage.getItem('beejepa_token')
  if (token) {
    await fetchUserProfile(token)
  }
  await fetchSystemStatus()
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

async function fetchSystemStatus() {
  try {
    const [storageRes, museRes] = await Promise.all([
      fetch('/api/v1/storage'),
      fetch('/api/v1/muse/status')
    ])
    if (storageRes.ok) storageStatus.value = await storageRes.json()
    if (museRes.ok) museStatus.value = await museRes.json()
  } catch (err) {}
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
      await fetchSystemStatus()
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

        <!-- Hardware Tag -->
        <div class="mb-6 p-3 bg-slate-900/80 rounded-lg border border-slate-800 text-xs flex items-center justify-between text-slate-400">
          <span>Target Device:</span>
          <span class="font-mono text-amber-400 font-semibold">{{ hardware }}</span>
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
    <!-- VIEW 2: AUTHENTICATED DASHBOARD                                   -->
    <!-- ================================================================= -->
    <div v-else class="flex-1 flex flex-col h-screen overflow-hidden">
      <!-- Header -->
      <header class="h-16 border-b border-slate-800/80 bg-[#0c121e] px-6 flex items-center justify-between shrink-0">
        <div class="flex items-center gap-3">
          <div class="w-9 h-9 rounded-lg bg-amber-500/15 border border-amber-500/30 p-1 flex items-center justify-center">
            <img :src="beeLogo" alt="BeeJEPA Logo" class="w-full h-full object-contain drop-shadow" />
          </div>
          <div class="flex items-center gap-2">
            <h1 class="text-sm font-semibold text-white">BeeJEPA</h1>
            <span class="text-[10px] font-mono uppercase bg-slate-800 text-slate-300 px-2 py-0.5 rounded border border-slate-700">
              {{ hardware }}
            </span>
          </div>
        </div>

        <!-- Telemetry Badges -->
        <div class="flex items-center gap-3 text-xs">
          <!-- SSD Storage Status -->
          <div class="flex items-center gap-2 px-3 py-1.5 rounded-md bg-slate-900 border border-slate-800">
            <span class="w-2 h-2 rounded-full" :class="storageStatus.isMounted ? 'bg-emerald-400' : 'bg-slate-600'"></span>
            <span class="text-slate-400">SSD:</span>
            <span class="font-mono text-slate-200">
              {{ storageStatus.isMounted ? storageStatus.freeSpaceFormatted + ' free' : 'Not Connected' }}
            </span>
          </div>

          <!-- Meta Muse Status -->
          <div class="flex items-center gap-2 px-3 py-1.5 rounded-md bg-slate-900 border border-slate-800">
            <span class="w-2 h-2 rounded-full bg-slate-500"></span>
            <span class="text-slate-400">Meta Muse:</span>
            <span class="font-mono text-slate-300 text-[11px]">DORMANT (0 Credits Used)</span>
          </div>

          <!-- Beekeeper Profile & Logout -->
          <div class="flex items-center gap-3 pl-3 border-l border-slate-800">
            <div class="text-right">
              <div class="text-xs font-semibold text-amber-400">{{ currentUser?.username || 'beekeeper' }}</div>
            </div>
            <button
              @click="handleLogout"
              class="px-2.5 py-1 text-xs text-slate-400 hover:text-white bg-slate-800 hover:bg-slate-700 rounded transition-colors"
            >
              Logout
            </button>
          </div>
        </div>
      </header>

      <!-- 3-Column Video Layout Placeholder -->
      <main class="flex-1 p-6 grid grid-cols-1 lg:grid-cols-3 gap-6 overflow-hidden">
        <div
          v-for="channel in channels"
          :key="channel.id"
          class="flex flex-col bg-[#0f172a] rounded-xl border border-slate-800 overflow-hidden shadow-lg"
        >
          <!-- Stream Header -->
          <div class="px-4 py-3 border-b border-slate-800/80 flex items-center justify-between bg-slate-900/60">
            <span class="text-xs font-medium text-slate-200">{{ channel.title }}</span>
            <span class="text-[10px] font-mono px-2 py-0.5 rounded border" :class="channel.statusColor">
              {{ channel.status }}
            </span>
          </div>

          <!-- Feed Screen -->
          <div class="relative flex-1 bg-black flex flex-col items-center justify-center p-6 text-center">
            <div class="w-12 h-12 rounded-xl border border-slate-800 bg-slate-900/60 flex items-center justify-center text-slate-600 mb-2">
              <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
            </div>
            <span class="text-xs text-slate-500 font-mono">Stream Offline</span>
          </div>
        </div>
      </main>

      <!-- Bottom Timeline Component -->
      <footer class="h-32 border-t border-slate-800/80 bg-[#0c121e] px-6 py-3.5 flex flex-col justify-between shrink-0">
        <div class="flex items-center justify-between text-xs">
          <span class="font-medium text-slate-300">Anomaly Timeline</span>
          <span class="text-[11px] text-slate-500 font-mono">0 Triggers</span>
        </div>

        <!-- Clean Empty Timeline Track -->
        <div class="relative w-full h-12 bg-[#080c14] rounded-lg border border-slate-800 p-2 flex flex-col justify-between">
          <div class="relative w-full h-3 flex items-center justify-center">
            <div class="w-full h-1 bg-slate-800/80 rounded"></div>
            <span class="text-[10px] font-mono text-slate-500 bg-[#080c14] px-3 z-10">
              No anomalies recorded
            </span>
          </div>

          <!-- Timeline Time Markers -->
          <div class="flex justify-between text-[10px] font-mono text-slate-500 px-2 border-t border-slate-800/50 pt-1">
            <span>T - 15m</span>
            <span>T - 10m</span>
            <span>T - 5m</span>
            <span>NOW</span>
          </div>
        </div>
      </footer>
    </div>
  </div>
</template>
