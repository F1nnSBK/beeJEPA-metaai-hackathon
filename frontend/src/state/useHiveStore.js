import { ref, computed, watch } from 'vue'
import {
  apiFetch,
  normalizeEvent,
  normalizeTimeline,
  normalizeSaccade,
  normalizeMuseDiagnosis,
  normalizeStorageStatus,
  normalizeMetaHealth,
  normalizeRingBuffer
} from '../api/client.js'

const UI_PREFS_KEY = 'beejepa_ui_prefs_v1'
const SESSION_STATE_KEY = 'beejepa_session_state_v1'

function loadUiPrefs() {
  try {
    const raw = localStorage.getItem(UI_PREFS_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch (e) {
    return {}
  }
}

function saveUiPrefs(prefs) {
  try {
    localStorage.setItem(UI_PREFS_KEY, JSON.stringify(prefs))
  } catch (e) {}
}

function loadSessionState() {
  try {
    const raw = sessionStorage.getItem(SESSION_STATE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch (e) {
    return {}
  }
}

function saveSessionState(state) {
  try {
    sessionStorage.setItem(SESSION_STATE_KEY, JSON.stringify(state))
  } catch (e) {}
}

const initialPrefs = loadUiPrefs()
const initialSession = loadSessionState()

// Auth state
const isAuthenticated = ref(false)
const isAuthResolved = ref(false)
const currentUser = ref(null)

// System Telemetry state
const storageStatus = ref({
  isMounted: false,
  freeSpaceFormatted: 'Not Connected',
  isWritable: false
})
const metaHealth = ref({
  configured: false,
  liveConnection: false,
  status: 'CHECKING',
  statusCode: 200,
  latencyMs: 0,
  baseUrl: '',
  availableModels: [],
  message: 'Checking Meta Model API...'
})
const resilienceTelemetry = ref(null)
const ringBufferTelemetry = ref({
  capacity: 600,
  currentSequence: 0,
  activeSlotsCount: 0,
  totalAllocatedBytes: 0,
  memoryPerSlotBytes: 0,
  isOffHeap: true,
  droppedFrames: 0
})

// Timeline, Events & Saccadic Perception state
const timelineData = ref([])
const timelineMeta = ref(null)
const events = ref([])
const selectedEvent = ref(null)
const currentTimelineSec = ref(typeof initialSession.currentTimelineSec === 'number' ? initialSession.currentTimelineSec : 0)
const activeView = ref(initialPrefs.activeView || 'comparison')
const isAnalyzing = ref(false)
const saccadicTargets = ref([])
const activeSaccade = ref(null)
const ablationPyramid = ref(null)
const selectedPyramidTier = ref('TIER_1_VJEPA_WORLD_MODEL')
const selectedCounterfactual = ref('FULL_PYRAMID')

// Muse Diagnosis & Staging state
const stagedMuseModalOpen = ref(false)
const stagedMuseQuery = ref(null)
const stagedSamQuery = ref(null)
const isPreparingMuse = ref(false)
const isTranscribing = ref(false)
const voiceTranscript = ref('')
const museQuestionInput = ref(initialSession.museQuestionInput || '')
const stagedTab = ref('prompt')
const museDiagnosis = ref(null)
const isAskingMuse = ref(false)
const showRawWirePayload = ref(false)

// Unified Audio & Voice Controller state
const beeModeActive = ref(Boolean(initialPrefs.beeModeActive ?? false))
const autoReadAloud = ref(Boolean(initialPrefs.autoReadAloud ?? true))
const isBeeModePlaying = ref(false)
const isPlayingBeeBuzz = ref(false)
const isSpeakingAloud = ref(false)
const isRecording = ref(false)
const isProcessing = ref(false)

let activeAudioElement = null

// Persist UI preferences when changed
watch([beeModeActive, autoReadAloud, activeView], ([beeMode, autoRead, view]) => {
  saveUiPrefs({
    beeModeActive: beeMode,
    autoReadAloud: autoRead,
    activeView: view
  })
})

// Persist session state when changed
watch([selectedEvent, currentTimelineSec, museQuestionInput], ([ev, sec, q]) => {
  saveSessionState({
    selectedEventId: ev?.eventId || null,
    currentTimelineSec: sec,
    museQuestionInput: q
  })
})

watch(selectedEvent, (ev, prevEv) => {
  updateActiveSaccade()
  if (ev?.eventId && ev.eventId !== prevEv?.eventId) {
    fetchAblationPyramid(ev.eventId)
  }
})

function stopAllAudio() {
  if (activeAudioElement) {
    try {
      activeAudioElement.pause()
      activeAudioElement.src = ''
    } catch (e) {}
    activeAudioElement = null
  }
  isSpeakingAloud.value = false
  isPlayingBeeBuzz.value = false
  isBeeModePlaying.value = false
}

async function readAloud(text = null) {
  if (isSpeakingAloud.value) {
    stopAllAudio()
    return
  }

  const content = text || museDiagnosis.value?.spokenSummary || museDiagnosis.value?.diagnosis || ''
  if (!content) return

  stopAllAudio()
  try {
    isSpeakingAloud.value = true
    const url = '/api/v1/muse/tts?text=' + encodeURIComponent(content)
    const audio = new Audio(url)
    activeAudioElement = audio
    audio.onended = () => {
      if (activeAudioElement === audio) {
        activeAudioElement = null
      }
      isSpeakingAloud.value = false
    }
    audio.onerror = (err) => {
      console.warn('TTS playback error:', err)
      if (activeAudioElement === audio) {
        activeAudioElement = null
      }
      isSpeakingAloud.value = false
    }
    await audio.play()
  } catch (err) {
    console.warn('Failed to play TTS audio:', err)
    stopAllAudio()
  }
}

function stopReadAloud() {
  stopAllAudio()
}

async function playBeeBuzz(text = null) {
  stopAllAudio()
  try {
    isPlayingBeeBuzz.value = true
    isBeeModePlaying.value = true
    const promptText = text || museDiagnosis.value?.spokenSummary || museDiagnosis.value?.diagnosis || 'Bee colony activity report.'
    const url = `/api/v1/muse/voice?text=${encodeURIComponent(promptText)}`
    const audio = new Audio(url)
    activeAudioElement = audio
    audio.onended = () => {
      if (activeAudioElement === audio) {
        activeAudioElement = null
      }
      isPlayingBeeBuzz.value = false
      isBeeModePlaying.value = false
    }
    audio.onerror = () => {
      if (activeAudioElement === audio) {
        activeAudioElement = null
      }
      isPlayingBeeBuzz.value = false
      isBeeModePlaying.value = false
    }
    await audio.play()
  } catch (err) {
    console.error('Failed to play bee buzz:', err)
    stopAllAudio()
  }
}

function updateActiveSaccade() {
  if (!selectedEvent.value || !saccadicTargets.value.length) {
    activeSaccade.value = null
    return
  }
  const id = selectedEvent.value.eventId
  activeSaccade.value = saccadicTargets.value.find(s => s.eventId === id) || null
}

async function fetchAblationPyramid(eventId = null) {
  const targetId = eventId || selectedEvent.value?.eventId || ''
  const query = targetId ? `?eventId=${encodeURIComponent(targetId)}` : ''
  const res = await apiFetch(`/api/v1/perception/ablation-pyramid${query}`, {
    cancelKey: 'ablationPyramid',
    retries: 1,
    timeoutMs: 6000
  })
  if (res.ok && res.data) {
    ablationPyramid.value = res.data
  }
}

async function checkMetaHealth() {
  const [healthRes, statusRes] = await Promise.all([
    apiFetch('/api/v1/muse/health', { cancelKey: 'metaHealth', retries: 1, timeoutMs: 8000 }),
    apiFetch('/api/v1/muse/status', { cancelKey: 'museStatus', retries: 1, timeoutMs: 6000 })
  ])
  if (healthRes.ok && healthRes.data) {
    metaHealth.value = normalizeMetaHealth(healthRes.data)
  }
  if (statusRes.ok && statusRes.data?.resilience) {
    resilienceTelemetry.value = statusRes.data.resilience
  }
}

async function fetchPerceptionTelemetry() {
  const res = await apiFetch('/api/v1/perception/ring-buffer', {
    cancelKey: 'ringBuffer',
    retries: 1,
    timeoutMs: 6000
  })
  if (res.ok && res.data) {
    ringBufferTelemetry.value = normalizeRingBuffer(res.data)
  }
}

async function fetchSaccadicTargets() {
  const res = await apiFetch('/api/v1/perception/saccades', {
    cancelKey: 'saccades',
    retries: 1,
    timeoutMs: 6000
  })
  if (res.ok && Array.isArray(res.data)) {
    saccadicTargets.value = res.data.map(normalizeSaccade).filter(Boolean)
    updateActiveSaccade()
  }
}

async function fetchUserProfile(token) {
  const res = await apiFetch('/api/v1/auth/me', {
    headers: { Authorization: `Bearer ${token}` },
    cancelKey: 'authMe',
    retries: 1,
    timeoutMs: 6000
  })
  if (res.ok && res.data) {
    currentUser.value = res.data
    isAuthenticated.value = true
  } else if (res.status === 401 || res.status === 403) {
    localStorage.removeItem('beejepa_token')
    isAuthenticated.value = false
  }
}

async function loadSystemAndData() {
  const [storageRes, timelineRes, eventsRes] = await Promise.all([
    apiFetch('/api/v1/storage', { cancelKey: 'storage', retries: 2 }),
    apiFetch('/api/v1/timeline', { cancelKey: 'timeline', retries: 2 }),
    apiFetch('/api/v1/events', { cancelKey: 'events', retries: 2 })
  ])

  if (storageRes.ok && storageRes.data) {
    storageStatus.value = normalizeStorageStatus(storageRes.data)
  }
  if (timelineRes.ok && timelineRes.data) {
    const normalized = normalizeTimeline(timelineRes.data)
    timelineData.value = normalized.timeline
    timelineMeta.value = normalized
  }
  if (eventsRes.ok && Array.isArray(eventsRes.data)) {
    const normalizedList = eventsRes.data.map(normalizeEvent).filter(Boolean)
    events.value = normalizedList
    if (normalizedList.length > 0 && !selectedEvent.value) {
      const savedId = initialSession.selectedEventId
      const restored = savedId ? normalizedList.find(e => e.eventId === savedId) : null
      selectedEvent.value = restored || normalizedList[0]
    }
  }

  checkMetaHealth()
  fetchPerceptionTelemetry()
  fetchSaccadicTargets()
  fetchAblationPyramid(selectedEvent.value?.eventId)

  if (!museDiagnosis.value) {
    askMuse('What happened at my hive today?', { silentAutoLoad: true })
  }
}

async function askMuse(question = null, options = {}) {
  const silentAutoLoad = Boolean(options?.silentAutoLoad)
  const q = (question || museQuestionInput.value || 'What happened at my hive today?').trim()
  isAskingMuse.value = true
  try {
    const res = await apiFetch('/api/v1/muse/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question: q,
        eventId: selectedEvent.value?.eventId,
        maxEvents: 3
      }),
      cancelKey: 'askMuse',
      retries: 1,
      timeoutMs: 45000
    })
    if (res.ok && res.data) {
      const normalized = normalizeMuseDiagnosis(res.data)
      museDiagnosis.value = normalized
      fetchSaccadicTargets()

      if (!silentAutoLoad) {
        if (beeModeActive.value) {
          const textToBuzz = normalized.spokenSummary || normalized.diagnosis || 'Anomaly diagnosed by Meta Muse.'
          playBeeBuzz(textToBuzz)
        } else if (autoReadAloud.value && normalized.spokenSummary) {
          readAloud(normalized.spokenSummary)
        }
      }
    }
  } finally {
    isAskingMuse.value = false
  }
}

async function prepareMusePayload(event = null, question = null) {
  const ev = event || selectedEvent.value
  const q = question || museQuestionInput.value

  isPreparingMuse.value = true
  try {
    const res = await apiFetch('/api/v1/muse/prepare-query', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        eventId: ev?.eventId || (events.value.length > 0 ? events.value[0].eventId : null),
        question: q,
        eventData: ev || (events.value.length > 0 ? events.value[0] : null),
        dryRun: true
      }),
      cancelKey: 'prepareMuse',
      retries: 1
    })
    if (res.ok && res.data) {
      stagedMuseQuery.value = res.data
      stagedTab.value = 'prompt'
      stagedMuseModalOpen.value = true
    }
  } finally {
    isPreparingMuse.value = false
  }
}

async function prepareSamPayload(event = null, prompt = null) {
  const ev = event || selectedEvent.value
  if (!ev) return
  const p = prompt || museQuestionInput.value

  isPreparingMuse.value = true
  try {
    const res = await apiFetch('/api/v1/muse/prepare-sam', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        eventId: ev.eventId,
        prompt: p,
        customBox: ev.hotspot ? {
          x: ev.hotspot.normCenterX - 0.06,
          y: ev.hotspot.normCenterY - 0.07,
          width: 0.12,
          height: 0.14
        } : null,
        mediaType: 'image'
      }),
      cancelKey: 'prepareSam',
      retries: 1
    })
    if (res.ok && res.data) {
      stagedSamQuery.value = res.data
      stagedTab.value = 'payload'
      stagedMuseModalOpen.value = true
    }
  } finally {
    isPreparingMuse.value = false
  }
}

async function runMetaAnalysis() {
  if (!selectedEvent.value) return
  isAnalyzing.value = true
  try {
    const res = await apiFetch(`/api/v1/events/${selectedEvent.value.eventId}/analyze?forceRefresh=true`, {
      method: 'POST',
      cancelKey: 'runMetaAnalysis',
      retries: 1,
      timeoutMs: 45000
    })
    if (res.ok && res.data) {
      selectedEvent.value.agentResult = res.data
      selectedEvent.value.isCached = true
      const idx = events.value.findIndex(e => e.eventId === selectedEvent.value.eventId)
      if (idx !== -1) {
        events.value[idx].agentResult = res.data
        events.value[idx].isCached = true
      }
      fetchSaccadicTargets()
    }
  } finally {
    isAnalyzing.value = false
  }
}

export function useHiveStore() {
  return {
    // Auth
    isAuthenticated,
    isAuthResolved,
    currentUser,
    fetchUserProfile,

    // System & Health
    storageStatus,
    metaHealth,
    resilienceTelemetry,
    ringBufferTelemetry,
    checkMetaHealth,
    fetchPerceptionTelemetry,
    loadSystemAndData,

    // Timeline, Events & Saccades
    timelineData,
    timelineMeta,
    events,
    selectedEvent,
    currentTimelineSec,
    activeView,
    isAnalyzing,
    saccadicTargets,
    activeSaccade,
    ablationPyramid,
    selectedPyramidTier,
    selectedCounterfactual,
    fetchSaccadicTargets,
    fetchAblationPyramid,
    updateActiveSaccade,
    runMetaAnalysis,

    // Muse & Staging
    stagedMuseModalOpen,
    stagedMuseQuery,
    stagedSamQuery,
    isPreparingMuse,
    isTranscribing,
    voiceTranscript,
    museQuestionInput,
    stagedTab,
    museDiagnosis,
    isAskingMuse,
    showRawWirePayload,
    askMuse,
    prepareMusePayload,
    prepareSamPayload,

    // Unified Audio & Voice
    beeModeActive,
    autoReadAloud,
    isBeeModePlaying,
    isPlayingBeeBuzz,
    isSpeakingAloud,
    isRecording,
    isProcessing,
    stopAllAudio,
    readAloud,
    stopReadAloud,
    playBeeBuzz
  }
}

