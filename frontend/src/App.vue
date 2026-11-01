<script setup>
import { ref, onMounted, onUnmounted, computed, nextTick, defineAsyncComponent } from 'vue'
import beeLogo from './assets/bee.png'
import { apiFetch } from './api/client.js'
import { useHiveStore } from './state/useHiveStore.js'

// The landing page carries three.js; loading it lazily keeps the dashboard bundle lean.
const LandingPage = defineAsyncComponent(() => import('./landing/LandingPage.vue'))

const {
  isAuthenticated,
  isAuthResolved,
  currentUser,
  fetchUserProfile,
  hardware,
  storageStatus,
  metaHealth,
  resilienceTelemetry,
  ringBufferTelemetry,
  checkMetaHealth,
  fetchPerceptionTelemetry,
  loadSystemAndData,
  timelineData,
  timelineMeta,
  events,
  selectedEvent,
  currentTimelineSec,
  activeView,
  isAnalyzing,
  saccadicTargets,
  activeSaccade,
  fetchSaccadicTargets,
  updateActiveSaccade,
  runMetaAnalysis,
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
} = useHiveStore()

const videoPlayer = ref(null)
let activeStopRecording = null

const isNearPeakAnomaly = computed(() => {
  if (activeView.value === 'sam') return true
  if (!selectedEvent.value || typeof currentTimelineSec.value !== 'number') return false
  const peakTime = Number(selectedEvent.value.timestampSec || 0)
  // Dynamic Saccadic Trigger: Active strictly during the V-JEPA peak anomaly window (+-1.2s)
  return Math.abs(currentTimelineSec.value - peakTime) <= 1.2
})

const saccadicReticleStyle = computed(() => {
  if (!selectedEvent.value?.hotspot) return null
  const normX = activeSaccade.value
    ? (activeSaccade.value.focusCenterX / 540)
    : Number(selectedEvent.value.hotspot.normCenterX || 0.456)
  // focusCenterY and normCenterY already account for the 35px top black header bar in the 1080x960 frame
  const normY = activeSaccade.value
    ? (activeSaccade.value.focusCenterY / 960)
    : Number(selectedEvent.value.hotspot.normCenterY || 0.436)

  // All 1080x960 event media (sequence, raw clip, peak frame) have the camera panel on the left half (0..50% width)
  const leftPct = normX * 50
  const topPct = Math.max(35 / 960, normY) * 100

  return {
    left: `${leftPct.toFixed(2)}%`,
    top: `${topPct.toFixed(2)}%`
  }
})

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
  stopAllAudio()

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

    // Real-time browser speech recognition into museQuestionInput
    let speechRecognitionInstance = null
    const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition
    if (SpeechRec) {
      try {
        speechRecognitionInstance = new SpeechRec()
        speechRecognitionInstance.continuous = true
        speechRecognitionInstance.interimResults = true
        speechRecognitionInstance.lang = navigator.language || 'de-DE'
        speechRecognitionInstance.onresult = (event) => {
          let str = ''
          for (let i = 0; i < event.results.length; ++i) {
            str += event.results[i][0].transcript
          }
          if (str.trim()) {
            museQuestionInput.value = str
            voiceTranscript.value = str
          }
        }
        speechRecognitionInstance.onerror = (e) => {
          console.debug('Speech recognition event:', e)
        }
        speechRecognitionInstance.start()
      } catch (e) {
        console.debug('Speech recognition init error:', e)
      }
    }

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
      if (speechRecognitionInstance) {
        try { speechRecognitionInstance.stop() } catch (e) {}
      }

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

      isProcessing.value = false

      let recognizedText = museQuestionInput.value.trim()

      // Transcribe voice input dynamically via Meta Audio API
      try {
        isTranscribing.value = true
        const transRes = await apiFetch('/api/v1/muse/transcribe', {
          method: 'POST',
          headers: { 'Content-Type': 'audio/wav' },
          body: wavBytes,
          cancelKey: 'transcribe',
          retries: 1,
          timeoutMs: 15000
        })
        if (transRes.ok && transRes.data?.transcript?.trim()) {
          recognizedText = transRes.data.transcript.trim()
          voiceTranscript.value = recognizedText
          museQuestionInput.value = recognizedText
        }
      } catch (err) {
        console.debug('Transcribe error:', err)
      } finally {
        isTranscribing.value = false
      }

      // Voice Input: Immediately execute production pipeline through Meta Muse
      if (recognizedText) {
        console.log('Voice input complete. Executing production pipeline:', recognizedText)
        await askMuse(recognizedText)
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
  beeModeActive.value = !beeModeActive.value
}

function toggleVoiceInput() {
  toggleMicrophoneListening()
}

function setPresetQuestion(q) {
  museQuestionInput.value = q
}

function formatMarkdownReport(md) {
  if (!md) return ''
  let text = md.trim()

  // Escape HTML characters to prevent injection
  text = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // Headers: ###, ##, #
  text = text.replace(/^###\s*(.*)$/gm, '<h4 class="text-xs font-bold text-honey-400 mt-2 mb-0.5">$1</h4>')
  text = text.replace(/^##\s*(.*)$/gm, '<h3 class="text-xs font-bold text-honey-300 mt-2.5 mb-1">$1</h3>')
  text = text.replace(/^#\s*(.*)$/gm, '<h2 class="text-sm font-bold text-honey-300 mt-3 mb-1">$1</h2>')

  // Bold & Italic
  text = text.replace(/\*\*(.*?)\*\*/g, '<strong class="font-semibold text-ink-100">$1</strong>')
  text = text.replace(/\*(.*?)\*/g, '<em class="italic text-ink-300">$1</em>')

  // Convert list items
  const lines = text.split('\n')
  let inList = false
  const result = []

  for (const line of lines) {
    const listMatch = line.match(/^\s*[-*]\s+(.*)$/)
    if (listMatch) {
      if (!inList) {
        result.push('<ul class="list-disc pl-4 space-y-0.5 my-1 text-ink-300 text-xs">')
        inList = true
      }
      result.push(`<li>${listMatch[1]}</li>`)
    } else {
      if (inList) {
        result.push('</ul>')
        inList = false
      }
      if (line.trim().length > 0 && !line.startsWith('<h')) {
        result.push(`<p class="my-1 text-ink-200 leading-relaxed text-xs">${line}</p>`)
      } else if (line.trim().length > 0) {
        result.push(line)
      }
    }
  }
  if (inList) {
    result.push('</ul>')
  }

  return result.join('')
}

onMounted(async () => {
  window.addEventListener('keydown', handleGlobalKeydown)
  const token = localStorage.getItem('beejepa_token')
  if (token) {
    await fetchUserProfile(token)
  }
  isAuthResolved.value = true
  await loadSystemAndData()
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  stopAllAudio()
})

function selectEvent(ev) {
  selectedEvent.value = ev
  updateActiveSaccade()
  if (ev && typeof ev.timestampSec === 'number') {
    const t = ev.timestampSec
    currentTimelineSec.value = t
    const targetSeqIdx = Math.min(9, Math.max(0, Math.floor(t / 30.0)))
    const localSec = Math.max(0, t - (targetSeqIdx * 30.0))

    if (activeView.value === 'comparison' || activeView.value === 'sequence') {
      if (targetSeqIdx !== currentSequenceIndex.value) {
        currentSequenceIndex.value = targetSeqIdx
        pendingSeekSec = localSec
      } else if (videoPlayer.value) {
        try {
          videoPlayer.value.currentTime = localSec
        } catch (e) {
          console.debug('Seek error:', e)
        }
      }
    }
  }
}

function selectEventById(id) {
  const found = events.value.find(e => e.eventId === id)
  if (found) {
    selectEvent(found)
  }
}

// Sequence and Event Video Media URLs
const currentSequenceIndex = ref(0)
let pendingSeekSec = null

const currentSequence = computed(() => {
  if (timelineMeta.value?.sequences && timelineMeta.value.sequences.length > 0) {
    return timelineMeta.value.sequences[currentSequenceIndex.value] || timelineMeta.value.sequences[0]
  }
  const idx = currentSequenceIndex.value
  const pad = String(idx).padStart(2, '0')
  return {
    index: idx,
    filename: `clip_${pad}_rec2125_seq00${idx}_${pad}_00_to_${pad}_30.mp4`
  }
})

const sequenceClipUrl = computed(() => {
  if (!currentSequence.value) return ''
  return `/api/v1/sequences/${currentSequence.value.filename}`
})

const rawClipUrl = computed(() => {
  if (!selectedEvent.value) return ''
  return `/api/v1/events/${selectedEvent.value.eventId}/media/clip_raw.mp4`
})

const comparisonClipUrl = computed(() => {
  return sequenceClipUrl.value
})

const peakFrameUrl = computed(() => {
  if (!selectedEvent.value) return ''
  return `/api/v1/events/${selectedEvent.value.eventId}/media/peak_frame.jpg`
})

// Current video source based on view toggle
const activeMediaSource = computed(() => {
  if (activeView.value === 'comparison' || activeView.value === 'sequence') {
    return sequenceClipUrl.value
  }
  if (activeView.value === 'raw' || activeView.value === 'event') {
    return rawClipUrl.value
  }
  return ''
})

// Hotspot Bounding Box style in percentage of the 1080x960 (9:8) frame
const hotspotBoxStyle = computed(() => {
  if (!selectedEvent.value?.hotspot) return {}
  const hs = selectedEvent.value.hotspot
  const box = hs.boundingBoxPx || hs.boxPx
  if (Array.isArray(box) && box.length === 4) {
    const [y1, x1, y2, x2] = box.map(Number)
    return {
      left: `${((x1 / 1080) * 100).toFixed(2)}%`,
      top: `${((Math.max(35, y1) / 960) * 100).toFixed(2)}%`,
      width: `${(((x2 - x1) / 1080) * 100).toFixed(2)}%`,
      height: `${(((y2 - Math.max(35, y1)) / 960) * 100).toFixed(2)}%`
    }
  }
  const cx = Number(hs.normCenterX || 0.456) * 50
  const cy = Number(hs.normCenterY || 0.436) * 100
  const w = (160 / 1080) * 100
  const h = (108 / 960) * 100
  return {
    left: `${Math.max(0, Math.min(50 - w, cx - w / 2)).toFixed(2)}%`,
    top: `${Math.max((35 / 960) * 100, Math.min(100 - h, cy - h / 2)).toFixed(2)}%`,
    width: `${w.toFixed(2)}%`,
    height: `${h.toFixed(2)}%`
  }
})

const maxDuration = computed(() => timelineMeta.value?.totalDurationSec || 300)
const minScore = computed(() => Number(timelineMeta.value?.minSurprise ?? 9.5))
const maxScore = computed(() => Number(timelineMeta.value?.maxSurprise ?? 16.5))
const svgWidth = 1000
const svgHeight = 100

const tauY = computed(() => {
  const range = Math.max(1.0, maxScore.value - minScore.value)
  const tau = Number(timelineMeta.value?.decisionThreshold || 10.2)
  const clamped = Math.max(minScore.value, Math.min(tau, maxScore.value))
  return (svgHeight - 8 - ((clamped - minScore.value) / range) * (svgHeight - 16)).toFixed(1)
})

function getTimelineCoordinates(timestampSec, score) {
  const range = Math.max(1.0, maxScore.value - minScore.value)
  const clampedScore = Math.max(minScore.value, Math.min(score, maxScore.value))
  const normX = Math.max(0, Math.min(1, timestampSec / maxDuration.value))
  const normScore = (clampedScore - minScore.value) / range
  const svgY = svgHeight - 8 - normScore * (svgHeight - 16)
  const normY = svgY / svgHeight
  return {
    svgX: normX * svgWidth,
    svgY,
    normX,
    normY
  }
}

// Area path for gradient fill under the curve
const svgAreaPath = computed(() => {
  if (!timelineData.value || timelineData.value.length === 0) return ''
  const points = timelineData.value.map(pt => {
    const coords = getTimelineCoordinates(pt.timestampSec, pt.surpriseScoreL2)
    return `${coords.svgX.toFixed(1)},${coords.svgY.toFixed(1)}`
  })

  return `M 0,${svgHeight} L ${points.join(' L ')} L ${svgWidth},${svgHeight} Z`
})

// Stroke path for the fever curve
const svgCurvePath = computed(() => {
  if (!timelineData.value || timelineData.value.length === 0) return ''
  const points = timelineData.value.map(pt => {
    const coords = getTimelineCoordinates(pt.timestampSec, pt.surpriseScoreL2)
    return `${coords.svgX.toFixed(1)},${coords.svgY.toFixed(1)}`
  })

  return `M ${points.join(' L ')}`
})

// Spikes with exact HTML coordinates (guaranteed 100% mathematical lock with curve vertices)
const timelineSpikes = computed(() => {
  if (!timelineData.value) return []
  return timelineData.value
    .filter(pt => pt.eventId)
    .map(pt => {
      const coords = getTimelineCoordinates(pt.timestampSec, pt.surpriseScoreL2)
      return {
        ...pt,
        normX: coords.normX,
        normY: coords.normY
      }
    })
})

function formatTime(sec) {
  if (sec == null || isNaN(sec)) return '0:00'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${m}:${s.toString().padStart(2, '0')}`
}

const sortedEvents = computed(() => {
  return [...events.value].sort((a, b) => (a.timestampSec || 0) - (b.timestampSec || 0))
})

const currentAnomalyIndex = computed(() => {
  if (!selectedEvent.value) return -1
  return sortedEvents.value.findIndex(e => e.eventId === selectedEvent.value.eventId)
})

const isFirstAnomaly = computed(() => currentAnomalyIndex.value <= 0)
const isLastAnomaly = computed(() => currentAnomalyIndex.value === -1 || currentAnomalyIndex.value >= sortedEvents.value.length - 1)

function findClosestEvent(sec) {
  if (!events.value || events.value.length === 0) return null
  let closest = events.value[0]
  let minDiff = Math.abs((closest.timestampSec || 0) - sec)
  for (let i = 1; i < events.value.length; i++) {
    const diff = Math.abs((events.value[i].timestampSec || 0) - sec)
    if (diff < minDiff) {
      minDiff = diff
      closest = events.value[i]
    }
  }
  return closest
}

let isUserScrubbing = false

function syncTimelineToVideo(seek = true) {
  const t = currentTimelineSec.value
  const targetSeqIdx = Math.min(9, Math.max(0, Math.floor(t / 30.0)))
  const localSec = Math.max(0, t - (targetSeqIdx * 30.0))

  const closest = findClosestEvent(t)
  if (closest && closest.eventId !== selectedEvent.value?.eventId) {
    selectedEvent.value = closest
  }

  if (seek && (activeView.value === 'comparison' || activeView.value === 'sequence')) {
    if (targetSeqIdx !== currentSequenceIndex.value) {
      currentSequenceIndex.value = targetSeqIdx
      pendingSeekSec = localSec
    } else if (videoPlayer.value) {
      try {
        if (Math.abs(videoPlayer.value.currentTime - localSec) > 0.3) {
          videoPlayer.value.currentTime = localSec
        }
      } catch (e) {
        console.debug('Video seek error:', e)
      }
    }
  }
}

function onTimelineSliderInput() {
  isUserScrubbing = true
  syncTimelineToVideo(true)
}

function onTimelineSliderChange() {
  syncTimelineToVideo(true)
  setTimeout(() => {
    isUserScrubbing = false
  }, 150)
}

function onTimelineContainerClick(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  if (!rect.width) return
  const clickX = e.clientX - rect.left
  const pct = Math.max(0, Math.min(1, clickX / rect.width))
  currentTimelineSec.value = Math.round(pct * maxDuration.value * 10) / 10
  syncTimelineToVideo(true)
}

function onVideoLoaded() {
  if (pendingSeekSec !== null && videoPlayer.value && !isNaN(videoPlayer.value.duration)) {
    try {
      videoPlayer.value.currentTime = Math.min(pendingSeekSec, videoPlayer.value.duration)
    } catch (e) {
      console.debug('onVideoLoaded seek error:', e)
    }
    pendingSeekSec = null
  }
}

function onVideoTimeUpdate() {
  if (isUserScrubbing) return
  if (!videoPlayer.value) return
  if (activeView.value === 'comparison' || activeView.value === 'sequence') {
    const globalSec = (currentSequenceIndex.value * 30.0) + (videoPlayer.value.currentTime || 0)
    currentTimelineSec.value = Math.min(maxDuration.value, Math.round(globalSec * 10) / 10)
    const closest = findClosestEvent(currentTimelineSec.value)
    if (closest && closest.eventId !== selectedEvent.value?.eventId) {
      selectedEvent.value = closest
    }
  }
}

function onVideoEnded() {
  if (activeView.value === 'comparison' || activeView.value === 'sequence') {
    if (currentSequenceIndex.value < 9) {
      currentSequenceIndex.value++
      pendingSeekSec = 0
      currentTimelineSec.value = currentSequenceIndex.value * 30.0
      nextTick(() => {
        if (videoPlayer.value) {
          videoPlayer.value.play().catch(() => {})
        }
      })
    }
  }
}

function toggleVideoPlayPause() {
  if (!videoPlayer.value) return
  if (videoPlayer.value.paused) {
    videoPlayer.value.play().catch(() => {})
  } else {
    videoPlayer.value.pause()
  }
}

function handleGlobalKeydown(e) {
  if (e.code === 'Space' || e.key === ' ') {
    const activeTag = document.activeElement?.tagName?.toLowerCase()
    if (activeTag === 'input' || activeTag === 'textarea' || document.activeElement?.isContentEditable) {
      return
    }
    e.preventDefault()
    toggleVideoPlayPause()
  }
}

function goToPrevAnomaly() {
  if (currentAnomalyIndex.value > 0) {
    selectEvent(sortedEvents.value[currentAnomalyIndex.value - 1])
  }
}

function goToNextAnomaly() {
  if (currentAnomalyIndex.value >= 0 && currentAnomalyIndex.value < sortedEvents.value.length - 1) {
    selectEvent(sortedEvents.value[currentAnomalyIndex.value + 1])
  } else if (sortedEvents.value.length > 0 && currentAnomalyIndex.value === -1) {
    selectEvent(sortedEvents.value[0])
  }
}

async function handleAuthenticated({ token, username, role }) {
  localStorage.setItem('beejepa_token', token)
  currentUser.value = { username, role }
  isAuthenticated.value = true
  window.scrollTo(0, 0)
  await loadSystemAndData()
}

async function handleLogout() {
  stopAllAudio()
  const token = localStorage.getItem('beejepa_token')
  if (token) {
    try {
      await apiFetch('/api/v1/auth/logout', {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
        cancelKey: 'authLogout',
        retries: 0,
        timeoutMs: 4000
      })
    } catch (e) {}
  }
  localStorage.removeItem('beejepa_token')
  isAuthenticated.value = false
  currentUser.value = null
  window.scrollTo(0, 0)
}
</script>

<template>
  <div class="min-h-screen bg-ink-950 text-ink-100 font-sans flex flex-col selection:bg-honey-500 selection:text-black">
    <!-- ================================================================= -->
    <!-- VIEW 1: LANDING PAGE WITH BEEKEEPER LOGIN                         -->
    <!-- ================================================================= -->
    <div v-if="!isAuthResolved" class="flex-1 bg-ink-950" aria-busy="true"></div>
    <LandingPage v-else-if="!isAuthenticated" @authenticated="handleAuthenticated" />

    <!-- ================================================================= -->
    <!-- VIEW 2: AUTHENTICATED HACKATHON DASHBOARD                         -->
    <!-- ================================================================= -->
    <div v-else class="flex-1 flex flex-col min-h-screen lg:h-screen lg:overflow-hidden bg-ink-950">
      <!-- Responsive Header -->
      <header class="border-b border-ink-800/80 bg-ink-900 px-4 sm:px-6 py-2.5 sm:py-3 flex flex-wrap items-center justify-between gap-3 shrink-0">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 rounded-lg bg-honey-500/15 border border-honey-500/30 p-1 flex items-center justify-center shrink-0">
            <img :src="beeLogo" alt="BeeJEPA Logo" class="w-full h-full object-contain drop-shadow" />
          </div>
          <div class="flex items-center gap-2">
            <h1 class="text-sm font-semibold text-white tracking-tight">BeeJEPA</h1>
            <span class="text-[10px] font-mono uppercase bg-ink-800 text-ink-300 px-2 py-0.5 rounded border border-ink-700 whitespace-nowrap">
              <span class="hidden sm:inline">Raspberry </span>Pi 4
            </span>
          </div>
        </div>

        <!-- Telemetry Badges (Responsive row) -->
        <div class="flex items-center gap-2 sm:gap-3 text-xs flex-wrap sm:flex-nowrap">
          <!-- SSD Storage Status -->
          <div class="flex items-center gap-1.5 sm:gap-2 px-2.5 py-1 rounded-md bg-ink-900 border border-ink-800">
            <span class="w-2 h-2 rounded-full" :class="storageStatus.isMounted ? 'bg-clover-400' : 'bg-ink-600'"></span>
            <span class="text-ink-400">SSD:</span>
            <span class="font-mono text-ink-200 text-[11px]">
              {{ storageStatus.isMounted ? storageStatus.freeSpaceFormatted + ' free' : 'Not Connected' }}
            </span>
          </div>

          <!-- Meta Model APIs Status Badge -->
          <button
            @click="checkMetaHealth"
            :title="metaHealth.message + ' (Klicken zum erneuten Testen)'"
            class="hidden md:flex items-center gap-2 px-2.5 py-1 rounded-md bg-ink-900 border border-ink-800 hover:border-ink-700 transition-colors cursor-pointer text-left"
          >
            <span
              class="w-2 h-2 rounded-full"
              :class="metaHealth.liveConnection ? 'bg-clover-400 shadow-sm shadow-clover-400/50' : (metaHealth.configured ? 'bg-honey-400 animate-pulse' : 'bg-ink-500')"
            ></span>
            <span class="text-ink-400">Baseten Muse:</span>
            <span class="font-mono text-ink-300 text-[11px]">
              {{ metaHealth.liveConnection ? 'Live (' + metaHealth.latencyMs + 'ms)' : (metaHealth.configured ? metaHealth.status : 'Mock (0 Credits)') }}
            </span>
          </button>

          <!-- FFM Off-Heap Ring Buffer Telemetry Badge -->
          <div
            @click="fetchPerceptionTelemetry"
            class="hidden lg:flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-ink-900 border border-ink-800 hover:border-ink-700 transition-colors cursor-pointer"
            title="Java 25 FFM API Zero-GC Off-Heap Ring Buffer"
          >
            <span class="w-2 h-2 rounded-full bg-electric-400 shadow-sm shadow-electric-400/50"></span>
            <span class="text-ink-400">FFM Buffer:</span>
            <span class="font-mono text-electric-300 text-[11px]">
              {{ ringBufferTelemetry.activeSlotsCount }}/{{ ringBufferTelemetry.capacity }} slots (0 GC)
            </span>
          </div>

          <!-- Voice & Calibration Settings Button Group -->
          <div class="flex items-center gap-1 bg-ink-900/90 border border-ink-800 rounded-lg p-0.5">
            <!-- Settings Gear Button: Calibration & Staging Inspector -->
            <button
              @click="stagedMuseModalOpen = true"
              title="Einstellungen & Kalibrierung (Meta API Staging)"
              class="p-1.5 text-ink-400 hover:text-honey-400 hover:bg-ink-800 rounded-md transition-colors"
            >
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
            </button>

            <!-- Bee Mode Button: 🐝 -->
            <button
              @click="toggleBeeMode"
              :title="beeModeActive ? 'Bee Mode: ON (Responses synthesized as bioacoustic bee buzzing)' : 'Bee Mode: OFF (Click to enable bioacoustic buzzing)'"
              :class="beeModeActive ? 'bg-honey-500/25 text-honey-300 border border-honey-500/50 shadow-sm shadow-honey-500/20' : 'text-ink-400 hover:text-honey-400 hover:bg-ink-800 border border-transparent'"
              class="flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs transition-all active:scale-95"
            >
              <span class="text-sm select-none" :class="{ 'animate-bounce': isBeeModePlaying }">🐝</span>
              <span class="font-mono text-[11px] font-medium">
                {{ isBeeModePlaying ? 'Buzzing...' : (beeModeActive ? 'Bee Mode: ON' : 'Bee Mode: OFF') }}
              </span>
              <span v-if="isBeeModePlaying" class="flex gap-0.5 items-end h-2.5 ml-0.5">
                <span class="w-0.5 bg-honey-400 h-2.5 animate-pulse"></span>
                <span class="w-0.5 bg-honey-400 h-1.5 animate-pulse delay-75"></span>
                <span class="w-0.5 bg-honey-400 h-3 animate-pulse delay-150"></span>
              </span>
            </button>
          </div>

          <!-- Beekeeper Profile & Logout -->
          <div class="flex items-center gap-2 sm:gap-3 pl-2 sm:pl-3 border-l border-ink-800">
            <span class="text-xs font-medium text-honey-400">{{ currentUser?.username || 'beekeeper' }}</span>
            <button
              @click="handleLogout"
              class="px-2 py-0.5 text-xs text-ink-400 hover:text-white bg-ink-800 hover:bg-ink-700 rounded transition-colors"
            >
              Logout
            </button>
          </div>
        </div>
      </header>

      <!-- Main Layout: Video Player + Meta AI Causal Analysis -->
      <main class="flex-1 p-3 sm:p-5 flex flex-col lg:grid lg:grid-cols-12 gap-4 lg:gap-5 overflow-y-auto lg:overflow-hidden">
        <!-- Left Column: Video & Event Viewer (7 Cols) -->
        <div class="lg:col-span-7 flex flex-col bg-ink-900 rounded-xl border border-ink-800 overflow-hidden shadow-xl shrink-0 lg:shrink">
          <!-- View Toggle Header (Responsive) -->
          <div class="px-3 sm:px-4 py-2.5 border-b border-ink-800/80 bg-ink-900/80 flex flex-col sm:flex-row sm:items-center justify-between gap-2 shrink-0">
            <!-- Tabs with Horizontal Scroll on Mobile -->
            <div class="flex items-center gap-1.5 sm:gap-2 overflow-x-auto no-scrollbar py-0.5">
              <button
                @click="activeView = 'comparison'"
                :class="activeView === 'comparison' ? 'bg-honey-500 text-ink-950 font-semibold' : 'text-ink-400 hover:text-white bg-ink-800/60'"
                class="px-2.5 sm:px-3 py-1.5 text-xs rounded transition-colors whitespace-nowrap shrink-0 min-h-[36px] flex items-center"
              >
                Sequence Stream (5m)
              </button>
              <button
                @click="activeView = 'raw'"
                :class="activeView === 'raw' ? 'bg-honey-500 text-ink-950 font-semibold' : 'text-ink-400 hover:text-white bg-ink-800/60'"
                class="px-2.5 sm:px-3 py-1.5 text-xs rounded transition-colors whitespace-nowrap shrink-0 min-h-[36px] flex items-center"
              >
                Event Clip
              </button>
              <button
                @click="activeView = 'sam'"
                :class="activeView === 'sam' ? 'bg-honey-500 text-ink-950 font-semibold' : 'text-ink-400 hover:text-white bg-ink-800/60'"
                class="px-2.5 sm:px-3 py-1.5 text-xs rounded transition-colors whitespace-nowrap shrink-0 min-h-[36px] flex items-center"
              >
                SAM 3.1 Peak Frame
              </button>
            </div>

            <!-- Sequence & Time Info -->
            <div v-if="selectedEvent" class="flex sm:block items-center justify-between text-right text-[11px] font-mono text-ink-400 shrink-0">
              <span class="text-honey-400 font-semibold">{{ selectedEvent.eventId }}</span>
              <span class="mx-1.5">•</span>
              <span>{{ formatTime(selectedEvent.timestampSec) }} (f{{ selectedEvent.frameIndex }})</span>
            </div>
          </div>

          <!-- Video / Frame Canvas -->
          <div class="relative w-full aspect-[9/8] sm:aspect-video lg:aspect-auto lg:flex-1 bg-black flex items-center justify-center overflow-hidden min-h-[240px] sm:min-h-[300px]">
            <!-- Aspect-locked 1080x960 (9:8) stage so Fovea & SAM overlays align pixel-accurately with the video/frame -->
            <div v-if="(activeView !== 'sam' && activeMediaSource) || (activeView === 'sam' && peakFrameUrl)" class="relative aspect-[9/8] max-h-full max-w-full flex items-center justify-center">
              <!-- Video Mode: Sequence Stream or Event Clip -->
              <video
                v-if="activeView !== 'sam' && activeMediaSource"
                ref="videoPlayer"
                :src="activeMediaSource"
                @loadedmetadata="onVideoLoaded"
                @timeupdate="onVideoTimeUpdate"
                @ended="onVideoEnded"
                @click="toggleVideoPlayPause"
                autoplay
                :loop="activeView === 'raw'"
                muted
                playsinline
                controls
                class="w-full h-full block cursor-pointer"
              ></video>

              <!-- SAM 3.1 Peak Frame Mode -->
              <div v-else-if="activeView === 'sam' && peakFrameUrl" class="relative w-full h-full flex items-center justify-center">
                <img :src="peakFrameUrl" alt="SAM Peak Frame" class="w-full h-full block" />
                <!-- Hotspot Bounding Box Overlay -->
                <div
                  class="absolute border-2 border-honey-400 bg-honey-400/20 rounded shadow-lg pointer-events-none animate-pulse"
                  :style="hotspotBoxStyle"
                >
                  <div class="absolute -top-5 left-0 bg-honey-500 text-ink-950 font-mono text-[9px] font-bold px-1.5 py-0.5 rounded-t whitespace-nowrap">
                    SAM 3.1 Box Prompt
                  </div>
                </div>
              </div>

              <!-- Saccadic Reticle HUD (Visual Raptor Crosshairs & Foveal Focus - Only at Peak) -->
              <div
                v-if="selectedEvent && saccadicReticleStyle && isNearPeakAnomaly"
                class="absolute pointer-events-none transition-all duration-300 ease-out z-20"
                :style="saccadicReticleStyle"
              >
                <!-- Outer Foveal Focus Ring -->
                <div class="relative -translate-x-1/2 -translate-y-1/2">
                  <div class="w-16 h-16 sm:w-20 sm:h-20 rounded-full border border-electric-400/60 bg-electric-500/10 shadow-[0_0_15px_rgba(119,183,254,0.28)] flex items-center justify-center animate-pulse">
                    <!-- Inner Precision Crosshairs -->
                    <div class="w-2.5 h-2.5 rounded-full border border-electric-300 bg-electric-400/40"></div>
                    <div class="absolute w-full h-[1px] bg-electric-400/40"></div>
                    <div class="absolute h-full w-[1px] bg-electric-400/40"></div>
                  </div>
                  <!-- Saccade Telemetry Badge -->
                  <div class="absolute top-full left-1/2 -translate-x-1/2 mt-1 px-1.5 py-0.5 bg-ink-950/85 backdrop-blur border border-electric-500/40 rounded text-[9px] font-mono text-electric-300 whitespace-nowrap shadow-md">
                    FOVEA [{{ activeSaccade ? activeSaccade.focusCenterX : Math.round((selectedEvent?.hotspot?.normCenterX ?? 0.456) * 540) }}, {{ activeSaccade ? activeSaccade.focusCenterY : Math.round((selectedEvent?.hotspot?.normCenterY ?? 0.436) * 960) }}] · GRID [{{ selectedEvent?.hotspot?.tubeletGridX ?? 2 }}, {{ selectedEvent?.hotspot?.tubeletGridY ?? 3 }}]
                  </div>
                </div>
              </div>
            </div>

            <!-- Empty State -->
            <div v-else class="text-center p-6 text-ink-500">
              <svg class="w-10 h-10 mx-auto mb-2 text-ink-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
              <p class="text-xs font-mono">No event media available.</p>
            </div>
          </div>
        </div>

        <!-- Right Column: Meta AI Causal Analysis & Metrics (5 Cols) -->
        <div class="lg:col-span-5 flex flex-col bg-ink-900 rounded-xl border border-ink-800 p-4 sm:p-5 overflow-y-auto shadow-xl">
          <div class="flex items-center justify-between pb-3 border-b border-ink-800 shrink-0">
            <h2 class="text-xs font-semibold text-ink-200 uppercase tracking-wider">Meta AI Causal Analysis</h2>
            <span
              v-if="selectedEvent?.agentResult"
              class="text-[10px] font-mono px-2 py-0.5 rounded bg-clover-500/10 text-clover-400 border border-clover-500/30"
            >
              SSD CACHED (0 CREDITS)
            </span>
            <span
              v-else
              class="text-[10px] font-mono px-2 py-0.5 rounded bg-honey-500/10 text-honey-400 border border-honey-500/30"
            >
              AWAITING INFERENCE
            </span>
          </div>

          <div v-if="selectedEvent" class="mt-4 space-y-4">
            <!-- V-JEPA Latent Metrics Grid -->
            <div class="grid grid-cols-2 gap-3">
              <div class="p-3 bg-ink-900/80 rounded-lg border border-ink-800">
                <div class="text-[10px] font-mono text-ink-400 uppercase">V-JEPA Surprise Score</div>
                <div class="text-xl font-bold font-mono text-ember-400 mt-1">
                  <span class="math">{{ Number(selectedEvent.surpriseScoreL2).toFixed(2) }}𝜎</span>
                </div>
                <div class="text-[10px] text-ink-500 mt-0.5">Latent error <span class="math">‖𝑧̂ − 𝑧‖<sub>2</sub></span></div>
              </div>

              <div class="p-3 bg-ink-900/80 rounded-lg border border-ink-800">
                <div class="text-[10px] font-mono text-ink-400 uppercase">Pixel-Diff Baseline</div>
                <div class="text-xl font-bold font-mono text-ink-300 mt-1">
                  {{ Number(selectedEvent.pixelDiffBaseline).toFixed(4) }}
                </div>
                <div class="text-[10px] text-ink-500 mt-0.5"><span class="math">|𝐼(𝑡 + Δ𝑡) − 𝐼(𝑡)|</span></div>
              </div>
            </div>

            <!-- Tubelet Hotspot Location -->
            <div class="p-3 bg-ink-900/80 rounded-lg border border-ink-800 text-xs">
              <div class="flex items-center justify-between text-ink-400 mb-1">
                <span>Tubelet Hotspot Grid:</span>
                <span class="font-mono text-honey-400 font-semibold">
                  ({{ selectedEvent?.hotspot?.tubeletGridX ?? 2 }}, {{ selectedEvent?.hotspot?.tubeletGridY ?? 3 }})
                </span>
              </div>
              <div class="flex items-center justify-between text-ink-400">
                <span>Normalized Center:</span>
                <span class="font-mono text-ink-200">
                  ({{ Number(selectedEvent?.hotspot?.normCenterX ?? 0.456).toFixed(3) }}, {{ Number(selectedEvent?.hotspot?.normCenterY ?? 0.436).toFixed(3) }})
                </span>
              </div>
            </div>

            <!-- Active Perception & Saccadic Fovea Telemetry -->
            <div v-if="activeSaccade" class="p-3 bg-electric-950/20 rounded-lg border border-electric-800/40 text-xs space-y-1.5">
              <div class="flex items-center justify-between text-electric-300 font-semibold">
                <span class="flex items-center gap-1.5">
                  <span class="w-1.5 h-1.5 rounded-full bg-electric-400 animate-pulse"></span>
                  Active Perception Saccade
                </span>
                <span class="font-mono text-[10px] text-electric-400 bg-electric-900/50 px-1.5 py-0.5 rounded border border-electric-700/50">
                  {{ activeSaccade.classification }}
                </span>
              </div>
              <div class="grid grid-cols-2 gap-2 pt-1 font-mono text-[11px] text-ink-300">
                <div class="flex justify-between bg-ink-900/60 px-2 py-1 rounded">
                  <span class="text-ink-400">Fixation (X,Y):</span>
                  <span class="text-electric-300 font-bold">[{{ activeSaccade.focusCenterX }}, {{ activeSaccade.focusCenterY }}]</span>
                </div>
                <div class="flex justify-between bg-ink-900/60 px-2 py-1 rounded">
                  <span class="text-ink-400">Saccadic Jump:</span>
                  <span class="text-electric-300 font-bold">{{ activeSaccade.saccadicJumpDistancePx }} px</span>
                </div>
                <div class="flex justify-between bg-ink-900/60 px-2 py-1 rounded">
                  <span class="text-ink-400">Hotspot Bees:</span>
                  <span class="text-honey-400 font-bold">{{ activeSaccade.hotspotBeesCount }} contained</span>
                </div>
                <div class="flex justify-between bg-ink-900/60 px-2 py-1 rounded">
                  <span class="text-ink-400">Token Density:</span>
                  <span class="text-electric-300 font-bold">+8.3x Fovea</span>
                </div>
              </div>
            </div>

            <!-- Meta AI & SAM 3.1 Prompt & Voice Console (Direct Main Screen Input) -->
            <div class="p-3 bg-ink-900/90 rounded-xl border border-ink-800 space-y-2.5 shadow-sm">
              <div class="flex items-center justify-between">
                <label class="text-[11px] font-semibold text-ink-200 flex items-center gap-1.5">
                  <span class="text-honey-400">🐝</span>
                  <span>Meta AI & SAM 3.1 Prompt:</span>
                </label>
                <div class="flex items-center gap-1">
                  <span v-if="isTranscribing" class="text-[10px] text-honey-300 animate-pulse font-mono flex items-center gap-1">
                    <span class="w-1.5 h-1.5 rounded-full bg-honey-400 animate-ping"></span> Transcribing...
                  </span>
                  <button
                    @click="toggleVoiceInput"
                    :class="isRecording ? 'bg-ember-500/20 text-ember-300 border-ember-500/40 animate-pulse' : 'bg-ink-800 text-ink-300 hover:text-white border-ink-700'"
                    class="px-2 py-0.5 rounded text-[10px] font-mono border flex items-center gap-1 transition-colors"
                    title="Record voice query and run production pipeline through Meta Muse"
                  >
                    <span>{{ isRecording ? 'Listening...' : 'Voice Input' }}</span>
                  </button>
                </div>
              </div>

              <!-- Interactive Dynamic Textarea -->
              <div class="relative">
                <textarea
                  v-model="museQuestionInput"
                  rows="2"
                  class="w-full px-3 py-2 bg-ink-950 border border-ink-800 rounded-lg text-ink-200 text-xs focus:outline-none focus:border-honey-500/50 resize-none font-sans leading-relaxed"
                  placeholder="Enter your prompt here or use Voice Input (e.g., 'What foreign object is at the flight entrance?')..."
                ></textarea>
              </div>

              <!-- Quick Presets -->
              <div class="flex flex-wrap items-center gap-1 pt-0.5">
                <button
                  @click="setPresetQuestion('Explain physical collision cause: What occurred at this tubelet?')"
                  class="px-2 py-0.5 rounded bg-ink-800/80 hover:bg-ink-700 text-ink-300 text-[10px] transition-colors"
                >
                  🐝 Collision
                </button>
                <button
                  @click="setPresetQuestion('Assess wasp/hornet predation threat based on spatial flight velocity')"
                  class="px-2 py-0.5 rounded bg-ink-800/80 hover:bg-ink-700 text-ink-300 text-[10px] transition-colors"
                >
                  🐝 Hornet Threat
                </button>
                <button
                  @click="setPresetQuestion('Isolate anomalous bee on landing board and extract spatial contour')"
                  class="px-2 py-0.5 rounded bg-ink-800/80 hover:bg-ink-700 text-ink-300 text-[10px] transition-colors"
                >
                  🐝 SAM Contour
                </button>
              </div>
            </div>

            <!-- Action Buttons: SAM 3.1 & Ask Muse (Mobile-friendly touch targets) -->
            <div class="flex items-center gap-2">
              <button
                @click="askMuse()"
                :disabled="isAskingMuse"
                class="flex-1 py-2.5 sm:py-3 px-4 min-h-[44px] bg-honey-500 hover:bg-honey-400 disabled:opacity-50 text-ink-950 font-semibold text-xs rounded-lg transition-all shadow-lg shadow-honey-500/10 flex items-center justify-center gap-1.5 active:scale-[0.98] touch-manipulation"
              >
                <span v-if="isAskingMuse" class="w-3.5 h-3.5 border-2 border-ink-950 border-t-transparent rounded-full animate-spin"></span>
                <span>{{ isAskingMuse ? 'Analyzing Hive...' : '🐝 Ask Muse Spark' }}</span>
              </button>

              <button
                @click="prepareSamPayload(selectedEvent)"
                :disabled="isPreparingMuse || isAskingMuse"
                class="hidden sm:inline-flex py-2.5 sm:py-3 px-3 min-h-[44px] bg-ink-800/80 hover:bg-ink-700 disabled:opacity-50 text-ink-400 hover:text-honey-300 font-medium text-xs rounded-lg transition-all border border-ink-700/60 items-center justify-center gap-1.5 shadow-sm active:scale-[0.98] touch-manipulation"
                title="Inspect SAM 3.1 wire payload format"
              >
                <span>SAM 3.1 Wire</span>
              </button>
            </div>

            <!-- Muse Spark Live / Cached Storyboard Diagnosis Card -->
            <div v-if="museDiagnosis" class="p-3.5 bg-ink-900/95 rounded-lg border border-honey-500/40 space-y-3 shadow-lg">
              <!-- Compact Header & Audio Action Bar -->
              <div class="flex flex-wrap items-center justify-between gap-2 pb-2.5 border-b border-ink-800">
                <div class="flex items-center gap-2">
                  <span class="text-xs font-semibold text-honey-400 flex items-center gap-1.5">
                    <span>🐝</span>
                    <span>Hive Briefing</span>
                  </span>
                  <span
                    :class="museDiagnosis.degradedFallback
                      ? 'bg-honey-500/20 text-honey-300 border-honey-500/40'
                      : (museDiagnosis.offlineLocked || museDiagnosis.sourceMode === 'DISK_CACHE'
                          ? 'bg-electric-500/20 text-electric-300 border-electric-500/40'
                          : 'bg-clover-500/20 text-clover-300 border-clover-500/40')"
                    class="px-2 py-0.5 rounded border text-[10px] font-mono"
                  >
                    {{ museDiagnosis.degradedFallback ? 'Degraded Fallback' : (museDiagnosis.offlineLocked || museDiagnosis.sourceMode === 'DISK_CACHE' ? 'Offline Cache' : 'Live') }}
                  </span>
                  <span class="text-ink-400 text-[10px] font-mono hidden sm:inline">{{ museDiagnosis.telemetry?.latencyMs || 0 }}ms</span>
                </div>

                <!-- Single-Line Audio Action Pills -->
                <div class="flex items-center gap-1.5 flex-wrap">
                  <button
                    @click="isSpeakingAloud ? stopReadAloud() : readAloud()"
                    class="px-3 py-1.5 min-h-[40px] bg-honey-500/20 hover:bg-honey-500/30 text-honey-300 border border-honey-500/40 rounded-full flex items-center gap-1.5 transition-colors text-xs font-medium active:scale-95 touch-manipulation"
                    :title="isSpeakingAloud ? 'Stop audio' : 'Read briefing aloud in natural English'"
                  >
                    <svg v-if="isSpeakingAloud" class="w-3.5 h-3.5 fill-current" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1"/></svg>
                    <svg v-else class="w-3.5 h-3.5 fill-current" viewBox="0 0 24 24"><path d="M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77zm-2.5-2.23l-5 4H2v12h4.5l5 4V1zm4.5 11c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>
                    <span>{{ isSpeakingAloud ? 'Stop' : 'Read' }}</span>
                  </button>
                  <button
                    @click="playBeeBuzz()"
                    :disabled="isPlayingBeeBuzz"
                    class="px-2.5 py-1.5 min-h-[40px] bg-ink-800 hover:bg-ink-700 text-ink-300 border border-ink-700 rounded-full flex items-center gap-1 transition-colors text-xs active:scale-95 touch-manipulation"
                    title="Bioacoustic bee buzz synthesis"
                  >
                    <span>{{ isPlayingBeeBuzz ? 'Buzzing...' : '🐝 FFT-Buzz' }}</span>
                  </button>
                  <label class="flex items-center gap-1.5 text-ink-400 hover:text-ink-300 cursor-pointer text-xs select-none pl-1">
                    <input type="checkbox" v-model="autoReadAloud" class="rounded accent-honey-500 w-3.5 h-3.5" />
                    <span class="text-[11px]">Auto</span>
                  </label>
                </div>
              </div>

              <!-- Clean Formatted HTML Report Content Directly Above Crops -->
              <div
                v-html="formatMarkdownReport(museDiagnosis.report || museDiagnosis.diagnosis)"
                class="p-3 bg-ink-950/90 rounded-lg border border-ink-800 text-xs text-ink-200 leading-relaxed font-sans max-h-60 overflow-y-auto space-y-1"
              ></div>

              <!-- Multi-Event Storyboard Focus (Desktop 3-Grid / Mobile Touch Carousel) -->
              <div v-if="museDiagnosis.events && museDiagnosis.events.length" class="space-y-1.5 pt-1">
                <div class="flex items-center justify-between text-[10px] uppercase font-mono text-ink-400 tracking-wider">
                  <span>KEYFRAME PEAK CROPS</span>
                  <span class="text-[9px] text-ink-500 md:hidden">Swipe ↔</span>
                </div>
                <div class="flex overflow-x-auto snap-x snap-mandatory gap-3 pb-2 scrollbar-none md:grid md:grid-cols-3 md:gap-2 md:overflow-visible">
                  <div
                    v-for="evt in museDiagnosis.events"
                    :key="evt.eventId"
                    class="flex-none w-[78%] sm:w-[65%] md:w-auto snap-center p-1.5 bg-ink-950 rounded-lg border border-ink-800 space-y-1.5 text-center"
                  >
                    <img
                      v-if="evt.cropBase64"
                      :src="'data:image/jpeg;base64,' + evt.cropBase64"
                      :alt="evt.eventId"
                      class="w-full aspect-square rounded object-cover border border-ink-700/80"
                    />
                    <div class="flex items-center justify-between text-[10px] font-mono bg-ink-900/90 px-2 py-1 rounded border border-ink-800">
                      <span class="text-ink-300">{{ evt.timestamp }}</span>
                      <span class="math text-ember-400">{{ evt.surpriseScoreSigma }}𝜎</span>
                      <span class="text-honey-400 font-semibold">{{ evt.beesInsideHotspotCount !== undefined ? evt.beesInsideHotspotCount : evt.landingBoardBeeCount }} bees</span>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Raw API Payload Toggle -->
              <div class="pt-1 flex items-center justify-between text-[10px] text-ink-400 border-t border-ink-800">
                <button
                  @click="showRawWirePayload = !showRawWirePayload"
                  class="text-ink-400 hover:text-ink-200 flex items-center gap-1"
                >
                  <span>{{ showRawWirePayload ? '▼ Hide' : '▶ Show' }} Raw Wire Payload</span>
                </button>
                <button
                  @click="prepareMusePayload(selectedEvent)"
                  class="text-honey-400 hover:text-honey-300 underline"
                >
                  View Staging & cURL
                </button>
              </div>
              <pre v-if="showRawWirePayload" class="p-2 bg-ink-950 rounded text-[9px] font-mono text-honey-300/80 overflow-x-auto max-h-40 border border-ink-800">
{{ JSON.stringify(museDiagnosis, null, 2) }}
              </pre>
            </div>

            <!-- Staged Muse Query Status Banner (Prepared, Not Sent) -->
            <div v-if="stagedMuseQuery" class="p-3 bg-honey-500/10 rounded-lg border border-honey-500/30 space-y-2">
              <div class="flex items-center justify-between">
                <span class="text-[11px] font-semibold text-honey-400 flex items-center gap-1.5">
                  <span>🐝</span>
                  <span>Muse Spark Open Staged</span>
                </span>
                <span class="text-[9px] font-mono px-1.5 py-0.5 rounded bg-honey-500/20 text-honey-300 border border-honey-500/40">
                  Staged (0 Credits)
                </span>
              </div>
              <p class="text-[11px] text-ink-300">
                Grounded: V-JEPA Event <span class="font-mono text-honey-300">{{ stagedMuseQuery.eventId || 'Active' }}</span> ({{ stagedMuseQuery.estimatedTokens }} Tokens)
              </p>
              <div class="flex items-center justify-between pt-1 text-[11px]">
                <span class="text-ink-400 font-mono text-[10px]">{{ stagedMuseQuery.httpMethod || 'POST' }} {{ stagedMuseQuery.model || 'meta-models/muse-spark-open' }}</span>
                <button
                  @click="stagedMuseModalOpen = true"
                  class="font-medium text-honey-400 hover:text-honey-300 underline underline-offset-2"
                >
                  Payload & Prompt ansehen
                </button>
              </div>
            </div>

            <!-- SAM 3.1 Segmentation Isolation Card (Only shown when real output exists) -->
            <div v-if="selectedEvent.agentResult?.sam && selectedEvent.agentResult.sam.status === 'SUCCESS' && selectedEvent.agentResult.sam.segmentedObject" class="p-3.5 bg-ink-900/90 rounded-lg border border-ink-800 space-y-1.5">
              <div class="flex items-center justify-between">
                <span class="text-[11px] font-semibold text-honey-400">SAM 3.1 Instance Segmentation</span>
                <span class="text-[10px] font-mono text-ink-400">{{ selectedEvent.agentResult.sam.model }}</span>
              </div>
              <p class="text-xs text-ink-300">
                Isolated Object: <span class="font-semibold text-white">{{ selectedEvent.agentResult.sam.segmentedObject }}</span>
              </p>
              <p v-if="selectedEvent.agentResult.sam.instanceCount" class="text-[11px] text-ink-400 font-mono">
                Instances: {{ selectedEvent.agentResult.sam.instanceCount }} • Box Prompt Applied
              </p>
            </div>

            <!-- Muse Spark Causal Scene Diagnosis Card (Only shown when real diagnosis exists) -->
            <div v-if="selectedEvent.agentResult?.muse && selectedEvent.agentResult.muse.status === 'SUCCESS' && selectedEvent.agentResult.muse.diagnosis" class="p-3.5 bg-ink-900/90 rounded-lg border border-honey-500/30 space-y-2">
              <div class="flex items-center justify-between">
                <span class="text-[11px] font-semibold text-honey-400">Muse Spark Causal Diagnosis</span>
                <span v-if="selectedEvent.agentResult.muse.confidence" class="text-[10px] font-mono text-ink-400">Confidence: {{ Math.round(selectedEvent.agentResult.muse.confidence * 100) }}%</span>
              </div>
              <p class="text-xs text-ink-200 leading-relaxed italic bg-ink-950 p-3 rounded border border-ink-800">
                "{{ selectedEvent.agentResult.muse.diagnosis }}"
              </p>
            </div>
          </div>

          <!-- Empty State when no event is selected / present -->
          <div v-else class="flex-1 flex flex-col items-center justify-center text-center p-6 text-ink-500 my-auto">
            <div class="w-12 h-12 rounded-xl border border-ink-800 bg-ink-900/60 flex items-center justify-center text-ink-600 mb-3">
              <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </div>
            <p class="text-[11px] text-ink-500 max-w-xs mt-1">
              Awaiting anomaly events on SSD storage.
            </p>
          </div>
        </div>
      </main>

      <!-- Bottom: Latent Surprise Timeline & Interactive Scrubber Slider -->
      <footer class="border-t border-ink-800/80 bg-ink-900 px-4 sm:px-6 py-3 shrink-0">
        <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-1 mb-2 text-xs">
          <div class="flex items-center gap-2.5">
            <span class="font-semibold text-ink-200">
              {{ timelineData && timelineData.length > 0
                ? `${Math.round((timelineMeta?.totalDurationSec || 300) / 60 * 10) / 10}-Minute Latent Surprise Timeline (${timelineMeta?.recordingId || 'rec2125'})`
                : 'Latent Surprise Timeline' }}
            </span>
            <span
              v-if="timelineData && timelineData.length > 0"
              class="text-[10px] font-mono text-ember-400 bg-ember-500/10 border border-ember-500/20 px-1.5 py-0.5 rounded"
            >
              Threshold <span class="math">𝜏 = {{ timelineMeta?.decisionThreshold || timelineMeta?.thresholdSigma || '10.2' }}𝜎</span>
            </span>
            <span
              v-else
              class="text-[10px] font-mono text-ink-400 bg-ink-800/80 border border-ink-700 px-1.5 py-0.5 rounded"
            >
              Awaiting Data
            </span>
          </div>
          <div v-if="timelineData && timelineData.length > 0" class="text-[11px] text-ink-400 font-mono hidden sm:flex items-center gap-3">
            <span>Scrubber: <strong class="text-honey-400 font-semibold">{{ formatTime(currentTimelineSec) }}</strong> / {{ formatTime(maxDuration) }}</span>
            <span class="text-ink-600">|</span>
            <span>Anomaly {{ currentAnomalyIndex >= 0 ? currentAnomalyIndex + 1 : '-' }} of {{ sortedEvents.length }}</span>
          </div>
        </div>

        <!-- SVG Timeline Curve Container with Beautiful Gradient & Breathing Room -->
        <div class="relative w-full bg-ink-950 rounded-lg border border-ink-800 px-3 py-2 shadow-inner select-none">
          <div v-if="!timelineData || timelineData.length === 0" class="h-24 sm:h-28 flex flex-col items-center justify-center text-ink-500 text-xs font-mono gap-1">
            <span>No timeline artifacts found on storage.</span>
            <span class="text-[11px] text-ink-600">Connect SSD or populate ./data with sequence events.</span>
          </div>

          <!-- Interactive Track: SVG curve, event spikes, and needle all share this EXACT geometry -->
          <div
            v-else
            class="relative w-full h-24 sm:h-28 cursor-pointer select-none"
            @click="onTimelineContainerClick"
            title="Click to seek on timeline"
          >
            <svg viewBox="0 0 1000 100" preserveAspectRatio="none" class="w-full h-full block pointer-events-none overflow-visible">
              <defs>
                <!-- Gradient under the fever curve -->
                <linearGradient id="curveGradient" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="#EAA23A" stop-opacity="0.3" />
                  <stop offset="60%" stop-color="#EAA23A" stop-opacity="0.08" />
                  <stop offset="100%" stop-color="#EAA23A" stop-opacity="0.0" />
                </linearGradient>
              </defs>

              <!-- Grid Lines -->
              <line x1="0" y1="92" x2="1000" y2="92" stroke="#202224" stroke-width="1" />
              <line x1="0" :y1="tauY" x2="1000" :y2="tauY" stroke="#F36C47" stroke-dasharray="4 4" stroke-width="1.2" opacity="0.6" />

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
                stroke="#EAA23A"
                stroke-width="2"
                vector-effect="non-scaling-stroke"
              />
            </svg>

            <!-- Hotspot Interactive HTML Pins (100% Perfectly Round, Exact Alignment) -->
            <div
              v-for="spike in timelineSpikes"
              :key="spike.eventId"
              class="absolute -translate-x-1/2 -translate-y-1/2 cursor-pointer group z-20"
              :style="{ left: `${spike.normX * 100}%`, top: `${spike.normY * 100}%` }"
              @click.stop="selectEventById(spike.eventId)"
            >
              <!-- Pin Dot -->
              <div class="relative flex items-center justify-center p-2">
                <span v-if="selectedEvent?.eventId === spike.eventId" class="absolute w-6 h-6 rounded-full bg-ember-500/40 animate-ping"></span>
                <span
                  class="w-3.5 h-3.5 rounded-full border-2 transition-transform group-hover:scale-125"
                  :class="selectedEvent?.eventId === spike.eventId ? 'bg-ember-500 border-white ring-2 ring-ember-400' : 'bg-honey-400 border-ink-900 shadow-md'"
                ></span>
              </div>

              <!-- Tooltip Hover Badge -->
              <div class="absolute -top-7 left-1/2 -translate-x-1/2 opacity-0 group-hover:opacity-100 transition-opacity bg-ink-900 border border-ink-700 px-2 py-0.5 rounded text-[10px] font-mono text-honey-300 whitespace-nowrap shadow-xl pointer-events-none z-30">
                <span class="math">{{ spike.surpriseScoreL2 }}𝜎</span> ({{ formatTime(spike.timestampSec) }})
              </div>
            </div>

            <!-- Vertical Scrubber Needle Line on Timeline -->
            <div
              class="absolute top-0 bottom-0 pointer-events-none z-30 -translate-x-1/2 transition-[left] duration-75 ease-out"
              :style="{ left: `${(currentTimelineSec / maxDuration) * 100}%` }"
            >
              <div class="w-0.5 h-full bg-honey-400/90 shadow-[0_0_8px_rgba(234,162,58,0.8)]"></div>
              <div class="absolute -top-1 left-1/2 -translate-x-1/2 bg-honey-500 text-ink-950 font-mono text-[9px] font-bold px-1.5 py-0.5 rounded shadow whitespace-nowrap">
                {{ formatTime(currentTimelineSec) }}
              </div>
            </div>
          </div>
        </div>

        <!-- Timeline Dynamic Time Markers -->
        <div v-if="timelineData && timelineData.length > 0" class="flex justify-between text-[10px] font-mono text-ink-500 px-3 pt-1">
          <span>0:00 (Start)</span>
          <span class="hidden sm:inline">{{ formatTime(maxDuration * 0.25) }}</span>
          <span>{{ formatTime(maxDuration * 0.5) }}</span>
          <span class="hidden sm:inline">{{ formatTime(maxDuration * 0.75) }}</span>
          <span>{{ formatTime(maxDuration) }} (End)</span>
        </div>

        <!-- Dedicated Timeline Slider (Scrubber Bar Control) -->
        <div v-if="timelineData && timelineData.length > 0" class="mt-2 flex items-center gap-3">
          <!-- Current Scrubber Time Badge -->
          <div class="flex items-center gap-1.5 shrink-0 bg-ink-900 border border-ink-800 px-2.5 py-1 rounded-md text-[11px] font-mono shadow-sm">
            <span class="w-2 h-2 rounded-full bg-honey-400 animate-pulse"></span>
            <span class="text-honey-300 font-semibold">{{ formatTime(currentTimelineSec) }}</span>
            <span class="text-ink-600">/</span>
            <span class="text-ink-400">{{ formatTime(maxDuration) }}</span>
          </div>

          <!-- Slider Range Input (Mobile-touch friendly with min-h-[44px] container) -->
          <div class="relative flex-1 flex items-center min-h-[44px]">
            <input
              type="range"
              min="0"
              :max="maxDuration"
              step="0.5"
              v-model.number="currentTimelineSec"
              @input="onTimelineSliderInput"
              @change="onTimelineSliderChange"
              class="timeline-slider w-full h-2 bg-ink-800 rounded-lg appearance-none cursor-pointer accent-honey-500 focus:outline-none focus:ring-1 focus:ring-honey-500/50"
              aria-label="Timeline Scrubber Slider"
            />
          </div>

          <!-- Anomaly Stepper Controls (Snap to Previous / Next Anomaly) -->
          <div class="flex items-center gap-1 shrink-0">
            <button
              @click="goToPrevAnomaly"
              :disabled="isFirstAnomaly"
              class="min-h-[38px] px-2.5 py-1 text-[11px] font-mono rounded bg-ink-800/90 hover:bg-ink-700 text-ink-300 border border-ink-700/60 disabled:opacity-30 disabled:cursor-not-allowed transition flex items-center gap-1"
              title="Previous Anomaly"
            >
              <span>◀</span>
              <span class="hidden sm:inline">Anomaly</span>
            </button>
            <button
              @click="goToNextAnomaly"
              :disabled="isLastAnomaly"
              class="min-h-[38px] px-2.5 py-1 text-[11px] font-mono rounded bg-ink-800/90 hover:bg-ink-700 text-ink-300 border border-ink-700/60 disabled:opacity-30 disabled:cursor-not-allowed transition flex items-center gap-1"
              title="Next Anomaly"
            >
              <span class="hidden sm:inline">Anomaly</span>
              <span>▶</span>
            </button>
          </div>
        </div>
      </footer>
    </div>

    <!-- Staged Meta Muse Request Modal (Cleaned & Streamlined) -->
    <div
      v-if="stagedMuseModalOpen"
      class="fixed inset-0 bg-ink-950/80 backdrop-blur-sm z-50 flex items-center justify-center p-3 sm:p-6"
      @click.self="stagedMuseModalOpen = false"
    >
      <div class="bg-ink-900 border border-ink-800 rounded-2xl w-full max-w-xl max-h-[85vh] flex flex-col shadow-2xl overflow-hidden animate-in fade-in zoom-in-95 duration-150">
        <!-- Header -->
        <div class="p-4 border-b border-ink-800 flex items-center justify-between bg-ink-900/80">
          <div class="flex items-center gap-2">
            <span class="text-base select-none">🐝</span>
            <div>
              <h3 class="text-sm font-semibold text-white">Meta Muse Staging Inspector</h3>
              <p class="text-[11px] text-ink-400">Grounded Video Telemetry & SAM 3.1 Prompt Preview</p>
            </div>
          </div>
          <button
            @click="stagedMuseModalOpen = false"
            class="text-ink-400 hover:text-white p-1 rounded-lg hover:bg-ink-800 transition-colors"
          >
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Body -->
        <div class="p-4 space-y-3.5 overflow-y-auto flex-1 text-xs">
          <!-- Compact Status & Event Context -->
          <div class="p-2.5 bg-ink-950/70 rounded-xl border border-ink-800 flex items-center justify-between font-mono text-[11px]">
            <div class="flex items-center gap-2">
              <span class="w-2 h-2 rounded-full bg-honey-400 animate-pulse"></span>
              <span class="text-ink-300 font-semibold">{{ stagedMuseQuery?.eventId || stagedSamQuery?.eventId || selectedEvent?.eventId || 'Event Telemetry' }}</span>
            </div>
            <div class="flex items-center gap-3 text-ink-400">
              <span class="math text-ember-400">{{ stagedMuseQuery?.eventData?.surpriseScoreL2 ? Number(stagedMuseQuery.eventData.surpriseScoreL2).toFixed(2) + '𝜎' : (selectedEvent?.surpriseScoreL2 ? Number(selectedEvent.surpriseScoreL2).toFixed(2) + '𝜎' : '4.82𝜎') }}</span>
              <span class="text-honey-400">Hotspot ({{ stagedMuseQuery?.eventData?.hotspot?.tubeletGridX ?? 2 }}, {{ stagedMuseQuery?.eventData?.hotspot?.tubeletGridY ?? 3 }})</span>
              <span class="text-clover-400 text-[10px] px-1.5 py-0.5 rounded bg-clover-500/10 border border-clover-500/20">0 Credits</span>
            </div>
          </div>

          <!-- Question Input & Presets -->
          <div class="space-y-1.5">
            <div class="flex items-center justify-between">
              <label class="text-[11px] font-semibold text-ink-200">Beekeeper Query to Meta Muse:</label>
              <span class="text-[10px] text-ink-400">Text or Voice</span>
            </div>
            <textarea
              v-model="museQuestionInput"
              rows="2"
              class="w-full px-3 py-2 bg-ink-950 border border-ink-800 rounded-xl text-ink-200 font-mono text-xs focus:outline-none focus:border-honey-500/50 resize-none"
              placeholder="Query for Meta Muse..."
            ></textarea>
            <div class="flex flex-wrap items-center gap-1.5">
              <button
                @click="setPresetQuestion('Explain physical collision cause: What occurred at this tubelet?')"
                class="px-2 py-0.5 rounded bg-ink-800 hover:bg-ink-700 text-ink-300 text-[10px] transition-colors"
              >
                🐝 Collision
              </button>
              <button
                @click="setPresetQuestion('Assess wasp/hornet predation threat based on spatial flight velocity')"
                class="px-2 py-0.5 rounded bg-ink-800 hover:bg-ink-700 text-ink-300 text-[10px] transition-colors"
              >
                🐝 Hornet Threat
              </button>
              <button
                @click="setPresetQuestion('Analyze swarm preparation cues from cluster vibration and entrance traffic')"
                class="px-2 py-0.5 rounded bg-ink-800 hover:bg-ink-700 text-ink-300 text-[10px] transition-colors"
              >
                🐝 Swarm Dynamics
              </button>
            </div>
          </div>

          <!-- Staged Inspector Tabs: Just 2 clean tabs! -->
          <div class="border-t border-ink-800 pt-2 space-y-2">
            <div class="flex items-center gap-3 border-b border-ink-800 pb-1.5">
              <button
                @click="stagedTab = 'prompt'"
                :class="stagedTab === 'prompt' ? 'text-honey-400 border-b-2 border-honey-400 font-medium' : 'text-ink-400 hover:text-ink-300'"
                class="px-2 py-1 text-xs transition-colors shrink-0"
              >
                Grounded Prompt
              </button>
              <button
                @click="stagedTab = 'payload'"
                :class="stagedTab === 'payload' ? 'text-honey-400 border-b-2 border-honey-400 font-medium' : 'text-ink-400 hover:text-ink-300'"
                class="px-2 py-1 text-xs transition-colors shrink-0"
              >
                Wire Payload & cURL
              </button>
            </div>

            <!-- Tab 1: Formatted Grounded Prompt -->
            <div v-if="stagedTab === 'prompt'" class="p-3 bg-ink-950 rounded-xl border border-ink-800 font-mono text-[11px] text-ink-300 whitespace-pre-wrap leading-relaxed max-h-52 overflow-y-auto">
              {{ stagedMuseQuery?.prompt || stagedSamQuery?.prompt || 'No prompt generated' }}
            </div>

            <!-- Tab 2: Wire Payload & cURL -->
            <div v-if="stagedTab === 'payload'" class="space-y-2">
              <div v-if="stagedSamQuery?.curlCommand || stagedMuseQuery?.curlCommand" class="p-2.5 bg-ink-950 rounded-xl border border-ink-800 space-y-1">
                <div class="text-[10px] font-mono text-honey-400 uppercase font-semibold">cURL:</div>
                <pre class="text-ink-300 whitespace-pre-wrap font-mono text-[10px] leading-tight select-all max-h-28 overflow-y-auto">{{ stagedSamQuery?.curlCommand || stagedMuseQuery?.curlCommand }}</pre>
              </div>
              <div class="p-2.5 bg-ink-950 rounded-xl border border-ink-800 font-mono text-[10px] text-ink-200 whitespace-pre-wrap max-h-40 overflow-y-auto">
                {{ JSON.stringify(stagedMuseQuery?.payload || stagedSamQuery?.imagePayload || { model: 'muse-spark-open', question: museQuestionInput }, null, 2) }}
              </div>
            </div>
          </div>
        </div>

        <!-- Footer with Direct Action -->
        <div class="p-3.5 border-t border-ink-800 bg-ink-900/90 flex items-center justify-between">
          <button
            @click="stagedMuseModalOpen = false"
            class="px-3 py-1.5 bg-ink-800 hover:bg-ink-700 text-ink-300 rounded-lg text-xs transition-colors"
          >
            Close
          </button>
          <button
            @click="stagedMuseModalOpen = false; askMuse()"
            :disabled="isAskingMuse"
            class="px-3.5 py-1.5 bg-honey-500 hover:bg-honey-400 text-ink-950 font-semibold rounded-lg text-xs transition-colors flex items-center gap-1.5 shadow-md shadow-honey-500/10 active:scale-95"
          >
            <span>🐝 Submit to Muse Spark</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
