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
  ablationPyramid,
  selectedPyramidTier,
  fetchSaccadicTargets,
  fetchAblationPyramid,
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
  museError,
  isAskingMuse,
  showRawWirePayload,
  askMuse,
  warmMusePipeline,
  prepareMusePayload,
  prepareSamPayload,
  beeModeActive,
  autoReadAloud,
  isBeeModePlaying,
  isPlayingBeeBuzz,
  isLoadingTts,
  isSpeakingAloud,
  isRecording,
  isProcessing,
  stopAllAudio,
  readAloud,
  stopReadAloud,
  playBeeBuzz
} = useHiveStore()

const videoPlayer = ref(null)
const rightTab = ref('briefing')
let activeStopRecording = null

const orderedPyramidTiersApexFirst = computed(() => {
  const list = ablationPyramid.value?.tiers
  if (!Array.isArray(list)) return []
  return [...list].sort((a, b) => Number(b.tierIndex ?? 0) - Number(a.tierIndex ?? 0))
})

const activePyramidTierObj = computed(() => {
  const list = ablationPyramid.value?.tiers
  if (!Array.isArray(list) || !list.length) return null
  return list.find(t => t.id === selectedPyramidTier.value) || list[1] || list[0]
})

function pyramidTierWidthClass(tierIndex) {
  switch (Number(tierIndex)) {
    case 4: return 'w-[76%] sm:w-[64%]'
    case 3: return 'w-[82%] sm:w-[73%]'
    case 2: return 'w-[88%] sm:w-[82%]'
    case 1: return 'w-[94%] sm:w-[91%]'
    default: return 'w-full'
  }
}

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
    : Number(selectedEvent.value.hotspot.normCenterX)
  // focusCenterY and normCenterY already account for the 35px top black header bar in the 1080x960 frame
  const normY = activeSaccade.value
    ? (activeSaccade.value.focusCenterY / 960)
    : Number(selectedEvent.value.hotspot.normCenterY)
  if (!Number.isFinite(normX) || !Number.isFinite(normY)) return null

  // All 1080x960 event media (sequence, raw clip, peak frame) have the camera panel on the left half (0..50% width)
  const leftPct = normX * 50
  const topPct = Math.max(35 / 960, normY) * 100

  return {
    left: `${leftPct.toFixed(2)}%`,
    top: `${topPct.toFixed(2)}%`
  }
})

function downsampleBuffer(samples, inputRate, targetRate = 16000) {
  if (!samples || samples.length === 0 || inputRate === targetRate) {
    return samples
  }
  const ratio = inputRate / targetRate
  const newLength = Math.max(1, Math.round(samples.length / ratio))
  const result = new Float32Array(newLength)
  for (let i = 0; i < newLength; i++) {
    const start = Math.floor(i * ratio)
    const end = Math.min(samples.length, Math.floor((i + 1) * ratio))
    let sum = 0
    let count = 0
    for (let j = start; j < end; j++) {
      sum += samples[j]
      count++
    }
    result[i] = count > 0 ? sum / count : samples[Math.min(samples.length - 1, start)]
  }
  return result
}

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
    if (activeStopRecording) {
      activeStopRecording()
    }
    return
  }

  stopAllAudio()
  warmMusePipeline(selectedEvent.value?.eventId)

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
        speechRecognitionInstance.lang = 'en-US'
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
        isProcessing.value = false
        return
      }

      const allSamples = new Float32Array(totalLen)
      let off = 0
      for (const c of floatChunks) {
        allSamples.set(c, off)
        off += c.length
      }

      const targetRate = 16000
      const resampled = downsampleBuffer(allSamples, sampleRate, targetRate)
      const wavBytes = encodeWavBuffer(resampled, targetRate)

      isTranscribing.value = true
      isProcessing.value = false
      museError.value = null

      let recognizedText = ''

      try {
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
        } else {
          museError.value = transRes.data?.error || 'Voice transcription returned no speech.'
        }
      } catch (err) {
        museError.value = 'Voice transcription failed.'
        console.debug('Transcribe error:', err)
      }

      if (recognizedText) {
        isAskingMuse.value = true
        isTranscribing.value = false
        await askMuse(recognizedText)
      } else {
        isTranscribing.value = false
      }
    }

    activeStopRecording = finishAndSend
    const startTimestamp = Date.now()

    silenceCheckInterval = setInterval(() => {
      if (stopped) return
      const now = Date.now()

      if (hasSpoken && (now - lastSpeechTime) >= 1000) {
        finishAndSend()
        return
      }

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
  let text = md
    .replace(/<\/?(?:report|spoken|spoken_summary)>/gi, '')
    .replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE00}-\u{FE0F}]/gu, '')
    .replace(/^\s*---+\s*$/gm, '')
    .trim()

  text = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  text = text.replace(/^###\s*(.*)$/gm, '<h4 class="text-xs font-semibold text-honey-400 mt-2 mb-0.5">$1</h4>')
  text = text.replace(/^##\s*(.*)$/gm, '<h3 class="text-xs font-semibold text-honey-300 mt-2.5 mb-1">$1</h3>')
  text = text.replace(/^#\s*(.*)$/gm, '<h2 class="text-sm font-semibold text-honey-300 mt-3 mb-1">$1</h2>')

  text = text.replace(/`([^`]+)`/g, '<code class="font-mono text-[11px] text-honey-300 bg-ink-950 px-1 py-0.5 rounded">$1</code>')
  text = text.replace(/\*\*(.*?)\*\*/g, '<strong class="font-semibold text-ink-100">$1</strong>')
  text = text.replace(/\*(.*?)\*/g, '<em class="italic text-ink-300">$1</em>')

  const lines = text.split('\n')
  let inList = false
  const result = []

  for (const line of lines) {
    const trimmedLine = line.trim()
    if (!trimmedLine || /^\|\s*[-:]+/.test(trimmedLine)) {
      continue
    }
    const listMatch = line.match(/^\s*(?:[-*]|\d+\.)\s+(.*)$/)
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
      if (!trimmedLine.startsWith('<h')) {
        result.push(`<p class="my-1 text-ink-200 leading-relaxed text-xs">${trimmedLine}</p>`)
      } else {
        result.push(trimmedLine)
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

function getMaxSequenceIndex() {
  const count = timelineMeta.value?.sequences?.length || 10
  return Math.max(0, count - 1)
}

function selectEvent(ev) {
  selectedEvent.value = ev
  updateActiveSaccade()
  if (ev?.eventId) {
    warmMusePipeline(ev.eventId)
  }
  if (ev && typeof ev.timestampSec === 'number') {
    const t = ev.timestampSec
    currentTimelineSec.value = t
    const targetSeqIdx = typeof ev.sequenceIndex === 'number'
      ? Math.min(getMaxSequenceIndex(), Math.max(0, ev.sequenceIndex))
      : Math.min(getMaxSequenceIndex(), Math.max(0, Math.floor(t / 30.0)))
    const localSec = typeof ev.timeSecLocal === 'number'
      ? Math.max(0, ev.timeSecLocal)
      : Math.max(0, t - (targetSeqIdx * 30.0))

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
  return null
})

const sequenceClipUrl = computed(() => {
  if (!currentSequence.value?.filename) return ''
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

// V-JEPA Hotspot rectangle in 1080x960 frame coordinates (left panel: 0..540)
const vjepaHotspotRect = computed(() => {
  if (!selectedEvent.value?.hotspot) {
    return null
  }
  const hs = selectedEvent.value.hotspot
  const box = hs.boundingBoxPx || hs.boxPx
  if (Array.isArray(box) && box.length === 4) {
    const [y1, x1, y2, x2] = box.map(Number)
    const cleanY1 = Math.max(35, y1)
    return {
      x: x1,
      y: cleanY1,
      width: Math.max(10, x2 - x1),
      height: Math.max(10, y2 - cleanY1)
    }
  }
  if (!Number.isFinite(Number(hs.normCenterX)) || !Number.isFinite(Number(hs.normCenterY))) {
    return null
  }
  const cx = Math.round(Number(hs.normCenterX) * 540)
  const cy = Math.round(Number(hs.normCenterY) * 960)
  return {
    x: Math.max(0, cx - 68),
    y: Math.max(35, cy - 68),
    width: 136,
    height: 136
  }
})

// Real SAM 3.1 bounding boxes from data/hackathon_sample/cache/sam_spatial_*.json
const samContainedBoxes = computed(() => {
  const contained = selectedEvent.value?.samSpatial?.containedBees
  if (!Array.isArray(contained)) return []
  return contained
    .map(c => (Array.isArray(c?.box) && c.box.length === 4 ? c.box.map(Number) : null))
    .filter(Boolean)
})

const samBoardBoxes = computed(() => {
  const board = selectedEvent.value?.samSpatial?.boardBees
  if (!Array.isArray(board)) return []
  const containedKeys = new Set(samContainedBoxes.value.map(b => b.join(',')))
  return board
    .filter(b => Array.isArray(b) && b.length === 4)
    .map(b => b.map(Number))
    .filter(b => !containedKeys.has(b.join(',')))
})

const maxDuration = computed(() => Math.max(1, Number(timelineMeta.value?.totalDurationSec || 0)))
const minScore = computed(() => Number(timelineMeta.value?.minSurprise ?? 0))
const maxScore = computed(() => Number(timelineMeta.value?.maxSurprise ?? 1))
const svgWidth = 1000
const svgHeight = 100

const tauY = computed(() => {
  const tau = Number(timelineMeta.value?.decisionThreshold)
  if (!Number.isFinite(tau)) return null
  const range = Math.max(1.0, maxScore.value - minScore.value)
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
  return `${m}:${s.toString().padStart(2, '0')}` // taste-skill-ignore
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
  const targetSeqIdx = Math.min(getMaxSequenceIndex(), Math.max(0, Math.floor(t / 30.0)))
  const localSec = Math.max(0, t - (targetSeqIdx * 30.0))

  const closest = findClosestEvent(t)
  if (closest && closest.eventId !== selectedEvent.value?.eventId) {
    selectedEvent.value = closest
    updateActiveSaccade()
    warmMusePipeline(closest.eventId)
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
    if (currentSequenceIndex.value < getMaxSequenceIndex()) {
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
  if (e.key === 'Escape' && stagedMuseModalOpen.value) {
    stagedMuseModalOpen.value = false
    return
  }
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
  <div class="min-h-svh bg-ink-950 text-ink-100 font-sans flex flex-col selection:bg-honey-500 selection:text-ink-950">
    <!-- ================================================================= -->
    <!-- VIEW 1: LANDING PAGE WITH BEEKEEPER LOGIN                         -->
    <!-- ================================================================= -->
    <div v-if="!isAuthResolved" class="flex-1 bg-ink-950" aria-busy="true"></div>
    <LandingPage v-else-if="!isAuthenticated" @authenticated="handleAuthenticated" />

    <!-- ================================================================= -->
    <!-- VIEW 2: AUTHENTICATED BEEJEPA OPERATIONAL CONSOLE                 -->
    <!-- ================================================================= -->
    <div v-else class="flex-1 flex flex-col min-h-svh lg:h-dvh lg:overflow-hidden bg-ink-950">
      <!-- Quiet Top Bar -->
      <header class="sticky top-0 z-30 lg:static bg-ink-900 px-3 sm:px-5 pt-[calc(env(safe-area-inset-top)+0.4rem)] pb-2 sm:py-2.5 flex items-center justify-between gap-2 shrink-0">
        <!-- Brand & Live Status -->
        <div class="flex items-center gap-2.5 shrink-0">
          <div class="w-7 h-7 rounded-md bg-ink-950 p-1 flex items-center justify-center shrink-0">
            <img :src="beeLogo" alt="BeeJEPA" class="w-full h-full object-contain" />
          </div>
          <h1 class="text-sm font-semibold text-ink-100 tracking-tight">BeeJEPA</h1>
          <button
            type="button"
            @click="checkMetaHealth"
            :title="metaHealth.message || 'Check connection status'"
            class="flex items-center gap-1.5 text-xs text-ink-400 hover:text-ink-200 transition-colors pl-1 tabular-nums"
          >
            <span
              class="w-1.5 h-1.5 rounded-full shrink-0"
              :class="metaHealth.liveConnection ? 'bg-clover-400' : 'bg-honey-400'"
            ></span>
            <span>{{ metaHealth.liveConnection ? 'Live' : 'Cached' }}</span>
          </button>
        </div>

        <!-- Quiet Operator Controls -->
        <div class="flex items-center gap-1.5 text-xs shrink-0">
          <button
            type="button"
            @click="toggleBeeMode"
            :title="beeModeActive ? 'Bee Mode: ON' : 'Bee Mode: OFF'"
            :class="beeModeActive
              ? 'bg-honey-500/20 text-honey-300'
              : 'text-ink-400 hover:text-ink-200 hover:bg-ink-800/60'"
            class="min-h-[36px] sm:min-h-[32px] px-2.5 rounded-md flex items-center gap-1.5 text-xs font-medium transition-colors touch-manipulation"
          >
            <svg class="w-3.5 h-3.5 shrink-0 text-honey-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
              <path stroke-linecap="round" stroke-linejoin="round" d="M3 12h3l2-5 4 10 3-7 2 2h4" />
            </svg>
            <span>{{ isBeeModePlaying ? 'Buzzing' : 'Bee Mode' }}</span>
          </button>

          <button
            type="button"
            @click="prepareMusePayload(selectedEvent)"
            title="Inspect API payloads & system status"
            class="min-h-[36px] sm:min-h-[32px] px-2.5 text-ink-400 hover:text-ink-100 hover:bg-ink-800/60 rounded-md flex items-center gap-1.5 transition-colors touch-manipulation text-xs"
          >
            <svg class="w-3.5 h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4 7h16M4 12h10M4 17h7" />
            </svg>
            <span class="hidden sm:inline">Inspector</span>
          </button>

          <button
            type="button"
            @click="handleLogout"
            class="min-h-[36px] sm:min-h-[32px] px-2.5 text-xs text-ink-400 hover:text-ink-100 hover:bg-ink-800/60 rounded-md transition-colors touch-manipulation"
          >
            Logout
          </button>
        </div>
      </header>

      <!-- Main Operational Workspace -->
      <main class="flex-1 p-2.5 sm:p-4 pb-[calc(env(safe-area-inset-bottom)+0.75rem)] sm:pb-4 flex flex-col lg:grid lg:grid-cols-12 gap-3 sm:gap-4 lg:overflow-hidden">
        <!-- =============================================================== -->
        <!-- LEFT PANE (7 Cols): VISUAL STAGE + COUPLED SURPRISE TIMELINE     -->
        <!-- =============================================================== -->
        <section class="lg:col-span-7 flex flex-col bg-ink-900 rounded-lg overflow-hidden shrink-0 lg:shrink lg:min-h-0">
          <!-- View Mode Bar -->
          <div class="px-2.5 sm:px-4 py-1.5 bg-ink-900 flex items-center justify-between gap-2 shrink-0">
            <div class="grid grid-cols-3 w-full sm:w-auto sm:flex sm:items-center sm:gap-1 bg-ink-950 p-1 rounded-md" role="tablist" aria-label="Stage view mode">
              <button
                type="button"
                role="tab"
                :aria-selected="activeView === 'comparison'"
                @click="activeView = 'comparison'"
                :class="activeView === 'comparison'
                  ? 'bg-ink-800 text-ink-100 font-semibold'
                  : 'text-ink-400 hover:text-ink-200'"
                class="px-2.5 sm:px-3 min-h-[36px] sm:min-h-[30px] rounded text-xs transition-colors whitespace-nowrap flex items-center justify-center touch-manipulation"
              >
                Sequence
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="activeView === 'raw'"
                @click="activeView = 'raw'"
                :class="activeView === 'raw'
                  ? 'bg-ink-800 text-ink-100 font-semibold'
                  : 'text-ink-400 hover:text-ink-200'"
                class="px-2.5 sm:px-3 min-h-[36px] sm:min-h-[30px] rounded text-xs transition-colors whitespace-nowrap flex items-center justify-center touch-manipulation"
              >
                Event Clip
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="activeView === 'sam'"
                @click="activeView = 'sam'"
                :class="activeView === 'sam'
                  ? 'bg-ink-800 text-ink-100 font-semibold'
                  : 'text-ink-400 hover:text-ink-200'"
                class="px-2.5 sm:px-3 min-h-[36px] sm:min-h-[30px] rounded text-xs transition-colors whitespace-nowrap flex items-center justify-center touch-manipulation"
              >
                {{ selectedEvent?.hasSamSpatial ? 'SAM 3.1' : 'Peak Frame' }}
              </button>
            </div>

            <!-- Active Event Timestamp Readout -->
            <div v-if="selectedEvent" class="hidden sm:flex items-center gap-1.5 text-xs text-ink-400 tabular-nums shrink-0">
              <span v-if="selectedEvent.recordingId" class="text-honey-400 font-medium">{{ selectedEvent.recordingId }}</span>
              <span v-if="selectedEvent.recordingId" class="text-ink-600">·</span>
              <span class="text-ink-200 font-medium">{{ selectedEvent.formattedTime || formatTime(selectedEvent.timestampSec) }}</span>
              <span class="text-ink-600">·</span>
              <span>Frame {{ selectedEvent.frameIndex }}</span>
            </div>
          </div>

          <!-- Aspect-Locked 1080x960 (9:8) Video & Peak Frame Stage -->
          <div class="relative w-full h-[220px] sm:h-auto sm:aspect-video lg:aspect-auto lg:flex-1 bg-ink-950 flex items-center justify-center overflow-hidden sm:min-h-[260px] lg:min-h-0">
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

              <!-- Peak Frame & Real SAM 3.1 Segmentation Overlay Mode -->
              <div v-else-if="activeView === 'sam' && peakFrameUrl" class="relative w-full h-full flex items-center justify-center">
                <img :src="peakFrameUrl" :alt="selectedEvent?.eventId || 'Peak Frame'" class="w-full h-full block" />
                <svg viewBox="0 0 1080 960" class="absolute inset-0 w-full h-full pointer-events-none">
                  <!-- Real SAM 3.1 Board Bees -->
                  <template v-if="selectedEvent?.hasSamSpatial">
                    <rect
                      v-for="(b, idx) in samBoardBoxes"
                      :key="'board-' + idx"
                      :x="b[0]"
                      :y="b[1]"
                      :width="Math.max(2, b[2] - b[0])"
                      :height="Math.max(2, b[3] - b[1])"
                      fill="none"
                      stroke="#2EA043"
                      stroke-width="2.5"
                    />
                    <rect
                      v-for="(b, idx) in samContainedBoxes"
                      :key="'contained-' + idx"
                      :x="b[0]"
                      :y="b[1]"
                      :width="Math.max(2, b[2] - b[0])"
                      :height="Math.max(2, b[3] - b[1])"
                      fill="rgba(74, 222, 128, 0.14)"
                      stroke="#4ADE80"
                      stroke-width="3.2"
                    />
                  </template>
                  <!-- V-JEPA 2.1 Hotspot Box (camera panel + heatmap panel) -->
                  <template v-if="vjepaHotspotRect">
                    <rect
                      :x="vjepaHotspotRect.x"
                      :y="vjepaHotspotRect.y"
                      :width="vjepaHotspotRect.width"
                      :height="vjepaHotspotRect.height"
                      fill="none"
                      stroke="#F36C47"
                      stroke-width="3"
                      stroke-dasharray="7 5"
                    />
                    <rect
                      :x="vjepaHotspotRect.x + 540"
                      :y="vjepaHotspotRect.y"
                      :width="vjepaHotspotRect.width"
                      :height="vjepaHotspotRect.height"
                      fill="none"
                      stroke="#EAA23A"
                      stroke-width="2.5"
                      stroke-dasharray="6 5"
                    />
                  </template>
                </svg>
                <div class="absolute bottom-2 left-2 right-2 flex items-center justify-between gap-1.5 pointer-events-none">
                  <span
                    v-if="selectedEvent?.hasSamSpatial && selectedEvent?.samSpatial"
                    class="bg-ink-950/90 text-clover-300 text-[11px] font-medium px-2.5 py-1 rounded tabular-nums"
                  >
                    SAM 3.1 · {{ selectedEvent.samSpatial.landingBoardBeeCount >= 0 ? `${selectedEvent.samSpatial.landingBoardBeeCount} board` : 'board unknown' }} · {{ selectedEvent.samSpatial.beesInsideHotspotCount >= 0 ? `${selectedEvent.samSpatial.beesInsideHotspotCount} in hotspot` : 'hotspot unknown' }}
                  </span>
                  <span
                    v-else
                    class="bg-ink-950/90 text-ink-300 text-[11px] font-medium px-2.5 py-1 rounded tabular-nums"
                  >
                    {{ vjepaHotspotRect ? 'V-JEPA 2.1 measured hotspot' : `Hotspot: ${selectedEvent?.hotspotStatus || 'PENDING'}` }}
                  </span>
                </div>
              </div>

              <!-- Saccadic Reticle HUD (Video Modes) -->
              <div
                v-if="selectedEvent && saccadicReticleStyle && isNearPeakAnomaly && activeView !== 'sam'"
                class="absolute pointer-events-none transition-transform duration-300 ease-out z-20"
                :style="saccadicReticleStyle"
              >
                <div class="relative -translate-x-1/2 -translate-y-1/2">
                  <div class="w-16 h-16 sm:w-20 sm:h-20 rounded-full border border-electric-400/80 bg-electric-500/10 flex items-center justify-center"> <!-- taste-skill-ignore -->
                    <div class="w-2.5 h-2.5 rounded-full bg-electric-400/60"></div>
                  </div>
                </div>
              </div>
            </div>

            <!-- Empty Media State -->
            <div v-else class="text-center p-6 text-ink-400">
              <p class="text-xs">No event media loaded.</p>
            </div>
          </div>

          <!-- Coupled Token-Delta Timeline & Stepper -->
          <div class="bg-ink-900 px-3 sm:px-4 py-2.5 sm:py-3 shrink-0">
            <div class="flex items-center justify-between gap-2 mb-2 text-xs">
              <div class="flex items-center gap-2 min-w-0">
                <span class="font-medium text-ink-200 truncate">Timeline</span>
                <span
                  v-if="timelineData && timelineData.length > 0 && timelineMeta?.decisionThreshold != null"
                  class="hidden sm:inline text-[11px] text-ink-400 tabular-nums shrink-0"
                >
                  <span class="math">𝜏 = {{ Number(timelineMeta.decisionThreshold).toFixed(2) }}</span>
                </span>
              </div>

              <!-- Compact Anomaly Stepper -->
              <div v-if="timelineData && timelineData.length > 0" class="flex items-center gap-1.5 text-[11px] tabular-nums shrink-0">
                <button
                  type="button"
                  @click="goToPrevAnomaly"
                  :disabled="isFirstAnomaly"
                  class="min-h-[34px] sm:min-h-[28px] px-2.5 rounded bg-ink-950 hover:bg-ink-800 text-ink-200 disabled:opacity-35 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-1 touch-manipulation"
                  title="Previous anomaly"
                  aria-label="Previous anomaly"
                >
                  <svg class="w-3.5 h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M15 19l-7-7 7-7" />
                  </svg>
                  <span class="hidden sm:inline">Prev</span>
                </button>
                <span class="text-ink-400 px-1">
                  <strong class="text-ink-100 font-semibold">{{ currentAnomalyIndex >= 0 ? currentAnomalyIndex + 1 : '-' }}</strong> / {{ sortedEvents.length }}
                </span>
                <button
                  type="button"
                  @click="goToNextAnomaly"
                  :disabled="isLastAnomaly"
                  class="min-h-[34px] sm:min-h-[28px] px-2.5 rounded bg-ink-950 hover:bg-ink-800 text-ink-200 disabled:opacity-35 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-1 touch-manipulation"
                  title="Next anomaly"
                  aria-label="Next anomaly"
                >
                  <span class="hidden sm:inline">Next</span>
                  <svg class="w-3.5 h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
                  </svg>
                </button>
              </div>
            </div>

            <!-- Interactive SVG Fever Curve Track -->
            <div class="relative w-full bg-ink-950 rounded-md px-2.5 py-1.5 select-none">
              <div v-if="!timelineData || timelineData.length === 0" class="h-14 sm:h-16 flex items-center justify-center text-ink-400 text-xs">
                <span>No timeline data found.</span>
              </div>

              <div
                v-else
                class="relative w-full h-14 sm:h-16 cursor-pointer select-none"
                @click="onTimelineContainerClick"
                title="Click to seek on timeline"
              >
                <svg viewBox="0 0 1000 100" preserveAspectRatio="none" class="w-full h-full block pointer-events-none overflow-visible">
                  <defs>
                    <linearGradient id="curveGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stop-color="#EAA23A" stop-opacity="0.24" />
                      <stop offset="70%" stop-color="#EAA23A" stop-opacity="0.05" />
                      <stop offset="100%" stop-color="#EAA23A" stop-opacity="0.0" />
                    </linearGradient>
                  </defs>

                  <line x1="0" y1="92" x2="1000" y2="92" stroke="#202224" stroke-width="1" />
                  <line v-if="tauY != null" x1="0" :y1="tauY" x2="1000" :y2="tauY" stroke="#F36C47" stroke-dasharray="4 4" stroke-width="1.2" opacity="0.6" />

                  <path v-if="svgAreaPath" :d="svgAreaPath" fill="url(#curveGradient)" />
                  <path
                    v-if="svgCurvePath"
                    :d="svgCurvePath"
                    fill="none"
                    stroke="#EAA23A"
                    stroke-width="2"
                    vector-effect="non-scaling-stroke"
                  />
                </svg>

                <!-- Interactive Spike Pins -->
                <div
                  v-for="spike in timelineSpikes"
                  :key="spike.eventId"
                  class="absolute -translate-x-1/2 -translate-y-1/2 cursor-pointer group z-20"
                  :style="{ left: `${spike.normX * 100}%`, top: `${spike.normY * 100}%` }"
                  @click.stop="selectEventById(spike.eventId)"
                >
                  <div class="relative flex items-center justify-center p-2">
                    <span
                      class="w-2.5 h-2.5 rounded-full transition-transform group-hover:scale-125"
                      :class="selectedEvent?.eventId === spike.eventId ? 'bg-ember-500 ring-2 ring-ember-400/50 scale-110' : 'bg-honey-400'"
                    ></span>
                  </div>

                  <div class="absolute -top-7 left-1/2 -translate-x-1/2 opacity-0 group-hover:opacity-100 transition-opacity bg-ink-900 px-2 py-0.5 rounded text-[10px] text-honey-300 whitespace-nowrap pointer-events-none z-30 tabular-nums">
                    <span class="math">{{ Number(spike.tokenDeltaScore ?? spike.surpriseScoreL2).toFixed(2) }}</span> ({{ formatTime(spike.timestampSec) }})
                  </div>
                </div>

                <!-- Vertical Scrubber Playhead Needle -->
                <div
                  class="absolute top-0 bottom-0 pointer-events-none z-30 -translate-x-1/2"
                  :style="{ left: `${(currentTimelineSec / maxDuration) * 100}%` }"
                >
                  <div class="w-0.5 h-full bg-honey-400"></div>
                  <div class="absolute -top-1 left-1/2 -translate-x-1/2 bg-honey-500 text-ink-950 text-[10px] font-semibold px-1.5 py-0.5 rounded-sm whitespace-nowrap tabular-nums">
                    {{ formatTime(currentTimelineSec) }}
                  </div>
                </div>
              </div>
            </div>

            <!-- Compact Scrubber Slider + Time Readout -->
            <div v-if="timelineData && timelineData.length > 0" class="mt-2 flex items-center gap-2.5 text-[11px] text-ink-400 tabular-nums">
              <span class="text-honey-400 font-semibold w-8">{{ formatTime(currentTimelineSec) }}</span>
              <input
                type="range"
                min="0"
                :max="maxDuration"
                step="0.5"
                v-model.number="currentTimelineSec"
                @input="onTimelineSliderInput"
                @change="onTimelineSliderChange"
                class="timeline-slider flex-1 h-1.5 bg-ink-800 rounded-lg appearance-none cursor-pointer accent-honey-500 focus:outline-none"
                aria-label="Timeline scrubber slider"
              />
              <span class="w-8 text-right">{{ formatTime(maxDuration) }}</span>
            </div>
          </div>
        </section>

        <!-- =============================================================== -->
        <!-- RIGHT PANE (5 Cols): DISTILLED BRIEFING & ABLATION WORKSPACE    -->
        <!-- =============================================================== -->
        <section class="lg:col-span-5 flex flex-col bg-ink-900 rounded-lg overflow-hidden lg:min-h-0">
          <!-- Mode Tabs (Briefing | Ablation) -->
          <div class="px-3.5 sm:px-4 pt-2.5 pb-1 flex items-center justify-between gap-2 shrink-0">
            <div class="flex items-center gap-1 bg-ink-950 p-1 rounded-md" role="tablist" aria-label="Inspector mode">
              <button
                type="button"
                role="tab"
                :aria-selected="rightTab === 'briefing'"
                @click="rightTab = 'briefing'"
                :class="rightTab === 'briefing'
                  ? 'bg-ink-800 text-ink-100 font-semibold'
                  : 'text-ink-400 hover:text-ink-200'"
                class="min-h-[34px] sm:min-h-[28px] px-3 rounded text-xs transition-colors touch-manipulation"
              >
                Briefing
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="rightTab === 'ablation'"
                @click="rightTab = 'ablation'"
                :class="rightTab === 'ablation'
                  ? 'bg-ink-800 text-ink-100 font-semibold'
                  : 'text-ink-400 hover:text-ink-200'"
                class="min-h-[34px] sm:min-h-[28px] px-3 rounded text-xs transition-colors touch-manipulation"
              >
                Ablation
              </button>
            </div>
          </div>

          <div v-if="selectedEvent" class="flex-1 flex flex-col p-3 sm:p-4 lg:overflow-y-auto lg:min-h-0">
            <!-- 4-Cell Metric Strip (One Figure, One Place) -->
            <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-2 xl:grid-cols-4 gap-1.5 shrink-0">
              <div class="p-2 sm:p-2.5 bg-ink-950 rounded-md flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">Score</div>
                <div class="text-lg sm:text-xl font-semibold text-ember-400 mt-0.5 tabular-nums leading-tight">
                  <span class="math">{{ Number(selectedEvent.tokenDeltaScore ?? selectedEvent.surpriseScoreL2).toFixed(2) }}</span>
                </div>
              </div>

              <div class="p-2 sm:p-2.5 bg-ink-950 rounded-md flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">Landing Board</div>
                <div class="text-base sm:text-lg font-semibold text-ink-100 mt-0.5 tabular-nums leading-tight">
                  {{ selectedEvent.hasSamSpatial && selectedEvent.boardBees >= 0 ? `${selectedEvent.boardBees} bees` : `${selectedEvent.boardPeaks ?? '—'} peaks` }}
                </div>
              </div>

              <div class="p-2 sm:p-2.5 bg-ink-950 rounded-md flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">
                  {{ selectedEvent.hasSamSpatial && selectedEvent.hotspotBeesCount >= 0 ? 'Hotspot' : 'Active Cells' }}
                </div>
                <div class="text-base sm:text-lg font-semibold text-electric-300 mt-0.5 tabular-nums leading-tight">
                  {{ selectedEvent.hasSamSpatial && selectedEvent.hotspotBeesCount >= 0 ? `${selectedEvent.hotspotBeesCount} bees` : `${selectedEvent.activeCells ?? '—'} cells` }}
                </div>
              </div>

              <div class="p-2 sm:p-2.5 bg-ink-950 rounded-md flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">Fixation</div>
                <div class="text-base sm:text-lg font-semibold text-honey-400 mt-0.5 tabular-nums leading-tight">
                  {{ selectedEvent?.hotspot ? `(${selectedEvent.hotspot.tubeletGridX}, ${selectedEvent.hotspot.tubeletGridY})` : (selectedEvent?.hotspotStatus || 'PENDING') }}
                </div>
              </div>
            </div>

            <!-- =========================================================== -->
            <!-- TAB 1: HIVE BRIEFING & COMPACT PROMPT COMPOSER              -->
            <!-- =========================================================== -->
            <div v-if="rightTab === 'briefing'" class="flex-1 flex flex-col justify-between mt-4 space-y-4">
              <!-- Briefing Content Area (Flat, Unnested) -->
              <div class="space-y-3">
                <div class="flex items-center justify-between gap-2">
                  <div class="flex items-center gap-2">
                    <h3 class="text-xs font-semibold text-ink-100">Hive Briefing</h3>
                    <span
                      v-if="museDiagnosis"
                      class="text-[11px] text-ink-400 tabular-nums"
                    >
                      · {{ museDiagnosis.offlineLocked || museDiagnosis.sourceMode === 'DISK_CACHE' ? 'Cached' : `Live (${museDiagnosis.telemetry?.latencyMs || 0}ms)` }}
                    </span>
                  </div>

                  <!-- Quiet Audio Controls -->
                  <div v-if="museDiagnosis" class="flex items-center gap-1.5">
                    <button
                      type="button"
                      @click="(isSpeakingAloud || isLoadingTts) ? stopReadAloud() : readAloud()"
                      class="px-2.5 min-h-[32px] bg-ink-950 hover:bg-ink-800 text-honey-300 rounded flex items-center gap-1.5 transition-colors text-[11px] font-medium touch-manipulation"
                      :title="isLoadingTts ? 'Synthesizing audio...' : (isSpeakingAloud ? 'Stop audio' : 'Read briefing aloud')"
                    >
                      <span v-if="isLoadingTts" class="w-3 h-3 border-2 border-honey-300 border-t-transparent rounded-full animate-spin"></span> <!-- taste-skill-ignore -->
                      <svg v-else-if="isSpeakingAloud" class="w-3 h-3 fill-current" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1"/></svg>
                      <svg v-else class="w-3 h-3 fill-current" viewBox="0 0 24 24"><path d="M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77zm-2.5-2.23l-5 4H2v12h4.5l5 4V1zm4.5 11c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>
                      <span>{{ isLoadingTts ? 'Loading...' : (isSpeakingAloud ? 'Stop' : 'Read') }}</span>
                    </button>
                    <button
                      type="button"
                      @click="playBeeBuzz()"
                      :disabled="isPlayingBeeBuzz"
                      class="px-2.5 min-h-[32px] bg-ink-950 hover:bg-ink-800 text-ink-300 rounded flex items-center gap-1.5 transition-colors text-[11px] touch-manipulation"
                      title="Bioacoustic bee buzz synthesis"
                    >
                      <span>{{ isPlayingBeeBuzz ? 'Buzzing...' : 'Buzz' }}</span>
                    </button>
                  </div>
                </div>

                <!-- Prose Report (Directly on surface, no nested card-in-card) -->
                <div
                  v-if="museError"
                  class="p-2.5 rounded bg-ember-500/15 text-ember-300 text-xs leading-relaxed"
                >
                  {{ museError }}
                </div>
                <div
                  v-else-if="museDiagnosis"
                  v-html="formatMarkdownReport(museDiagnosis.report || museDiagnosis.diagnosis)"
                  class="text-xs text-ink-200 leading-relaxed font-sans space-y-1.5"
                ></div>
                <p
                  v-else-if="selectedEvent.agentResult?.muse?.diagnosis"
                  class="text-xs text-ink-300 leading-relaxed"
                >
                  {{ selectedEvent.agentResult.muse.diagnosis }}
                </p>
                <p v-else class="text-xs text-ink-400">
                  Ask a question below to synthesize a grounded briefing for this event.
                </p>

                <!-- Keyframe Peak Crops (Clean 3-up strip without nested box borders) -->
                <div v-if="museDiagnosis?.events && museDiagnosis.events.length" class="pt-1 space-y-2">
                  <div class="text-[11px] text-ink-400">Peak hotspot crops</div>
                  <div class="grid grid-cols-3 gap-2">
                    <div
                      v-for="evt in museDiagnosis.events"
                      :key="evt.eventId"
                      class="space-y-1"
                    >
                      <img
                        v-if="evt.cropBase64"
                        :src="'data:image/jpeg;base64,' + evt.cropBase64"
                        :alt="evt.eventId"
                        class="w-full aspect-square rounded object-cover bg-ink-950"
                      />
                      <div class="flex items-center justify-between text-[10px] text-ink-400 tabular-nums px-0.5">
                        <span>{{ evt.timestamp }}</span>
                        <span class="text-honey-400 font-medium">
                          {{ evt.beesInsideHotspotCount >= 0 ? `${evt.beesInsideHotspotCount} bees` : (evt.landingBoardBeeCount >= 0 ? `${evt.landingBoardBeeCount} board` : 'unsegmented') }}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- Compact Prompt Composer (Bottom of Briefing Tab) -->
              <div class="pt-2 space-y-2 shrink-0">
                <!-- Quiet Quick Presets -->
                <div class="flex flex-wrap items-center gap-1.5">
                  <button
                    type="button"
                    @click="setPresetQuestion('Explain physical collision cause: What occurred at this tubelet?')"
                    class="px-2.5 py-1 rounded bg-ink-950 hover:bg-ink-800 text-ink-400 hover:text-ink-200 text-[11px] transition-colors touch-manipulation"
                  >
                    Collision cause
                  </button>
                  <button
                    type="button"
                    @click="setPresetQuestion('Assess wasp/hornet predation threat based on spatial flight velocity')"
                    class="px-2.5 py-1 rounded bg-ink-950 hover:bg-ink-800 text-ink-400 hover:text-ink-200 text-[11px] transition-colors touch-manipulation"
                  >
                    Hornet threat
                  </button>
                  <button
                    type="button"
                    @click="setPresetQuestion('Isolate anomalous bee on landing board and extract spatial contour')"
                    class="px-2.5 py-1 rounded bg-ink-950 hover:bg-ink-800 text-ink-400 hover:text-ink-200 text-[11px] transition-colors touch-manipulation"
                  >
                    Board activity
                  </button>
                </div>

                <!-- Single-Row Composer Bar -->
                <form
                  @submit.prevent="askMuse()"
                  @mouseenter="warmMusePipeline()"
                  class="flex items-center gap-1.5 bg-ink-950 rounded-md p-1 transition-colors"
                >
                  <input
                    id="muse-prompt-input"
                    type="text"
                    v-model="museQuestionInput"
                    @focus="warmMusePipeline()"
                    class="flex-1 min-w-0 px-2.5 py-1.5 bg-transparent text-ink-100 text-base sm:text-xs focus:outline-none font-sans placeholder:text-ink-500"
                    :placeholder="isRecording ? 'Listening...' : ((isTranscribing || isProcessing) ? 'Transcribing voice...' : 'Ask a question...')"
                  />

                  <button
                    type="button"
                    @click="toggleVoiceInput"
                    :disabled="isTranscribing || isProcessing"
                    :class="isRecording ? 'bg-ember-500/20 text-ember-300' : 'text-ink-400 hover:text-ink-100 hover:bg-ink-900 disabled:opacity-40'"
                    class="min-h-[36px] min-w-[36px] sm:min-h-[32px] sm:min-w-[32px] rounded flex items-center justify-center transition-colors shrink-0 touch-manipulation"
                    :title="isRecording ? 'Listening...' : 'Voice input'"
                    aria-label="Voice input"
                  >
                    <svg class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M12 2a3 3 0 00-3 3v7a3 3 0 006 0V5a3 3 0 00-3-3z" />
                      <path stroke-linecap="round" stroke-linejoin="round" d="M19 10v2a7 7 0 01-14 0v-2M12 19v3m-4 0h8" />
                    </svg>
                  </button>

                  <button
                    type="submit"
                    :disabled="isAskingMuse || isTranscribing || isProcessing"
                    class="min-h-[36px] sm:min-h-[32px] px-3.5 bg-honey-400 hover:bg-honey-300 disabled:opacity-50 text-ink-950 font-semibold text-xs rounded transition-colors flex items-center justify-center gap-1.5 shrink-0 touch-manipulation"
                  >
                    <span v-if="isAskingMuse || isTranscribing || isProcessing" class="w-3 h-3 border-2 border-ink-950 border-t-transparent rounded-full animate-spin"></span> <!-- taste-skill-ignore -->
                    <span>{{ (isAskingMuse || isTranscribing || isProcessing) ? 'Asking...' : 'Ask' }}</span>
                  </button>
                </form>
              </div>
            </div>

            <!-- =========================================================== -->
            <!-- TAB 2: 5-TIER PIPELINE ROLES & MEASURED TELEMETRY           -->
            <!-- =========================================================== -->
            <div v-else-if="rightTab === 'ablation'" class="mt-4 space-y-4">
              <template v-if="ablationPyramid">
                <div class="flex items-center justify-between gap-2">
                  <span class="text-xs font-semibold text-ink-100">5-Tier Pipeline Roles</span>
                  <span v-if="ablationPyramid.measured" class="text-[11px] text-ink-400 tabular-nums">
                    {{ ablationPyramid.measured.eventsWithMeasuredHotspot ?? 0 }}/{{ ablationPyramid.measured.eventsTotal ?? 0 }} measured hotspots
                  </span>
                </div>

                <!-- Visual Centered Pyramid Stack (Apex Tier 4 -> Base Tier 0) -->
                <div class="flex flex-col items-center gap-1.5">
                  <button
                    v-for="tier in orderedPyramidTiersApexFirst"
                    :key="tier.id"
                    type="button"
                    @click="selectedPyramidTier = tier.id"
                    :class="[
                      pyramidTierWidthClass(tier.tierIndex),
                      selectedPyramidTier === tier.id
                        ? 'bg-honey-500/20 text-honey-200'
                        : 'bg-ink-950 text-ink-300 hover:bg-ink-800/80 hover:text-ink-100'
                    ]"
                    class="min-h-[38px] sm:min-h-[34px] px-3 py-1.5 rounded text-xs flex items-center justify-between transition-colors cursor-pointer tabular-nums touch-manipulation"
                  >
                    <span class="font-semibold truncate">T{{ tier.tierIndex }} · {{ tier.shortTitle }}</span>
                    <span class="text-[11px] text-ink-400 ml-2 shrink-0 hidden sm:inline">{{ tier.modelOrComponent }}</span>
                  </button>
                </div>

                <!-- Flat Selected Tier Specification -->
                <div v-if="activePyramidTierObj" class="p-3 bg-ink-950 rounded-md space-y-2 text-xs">
                  <div class="flex items-center justify-between gap-2">
                    <span class="font-semibold text-honey-300">{{ activePyramidTierObj.name }}</span>
                    <span class="text-[11px] text-ink-400">{{ activePyramidTierObj.modelOrComponent }}</span>
                  </div>
                  <p class="text-ink-300 text-[11px]">{{ activePyramidTierObj.roleQuestion }}</p>
                  <div class="space-y-1.5 pt-1 text-[11px]">
                    <div class="flex justify-between gap-3">
                      <span class="text-ink-400 shrink-0">Input</span>
                      <span class="text-ink-200 text-right">{{ activePyramidTierObj.inputSignal }}</span>
                    </div>
                    <div class="flex justify-between gap-3">
                      <span class="text-ink-400 shrink-0">Output</span>
                      <span class="text-clover-300 text-right">{{ activePyramidTierObj.outputSignal }}</span>
                    </div>
                    <div class="flex justify-between gap-3">
                      <span class="text-ember-300 shrink-0">Without tier</span>
                      <span class="text-ink-200 text-right">{{ activePyramidTierObj.whatBreaksWithoutIt }}</span>
                    </div>
                  </div>
                </div>

                <!-- Live Measured Event & Catalog Telemetry -->
                <div v-if="ablationPyramid.measured" class="p-3 bg-ink-950 rounded-md space-y-1.5 text-[11px] tabular-nums">
                  <div class="text-xs font-semibold text-ink-100 mb-1">Measured Telemetry</div>
                  <div class="flex justify-between gap-3">
                    <span class="text-ink-400">Catalog source</span>
                    <span class="text-ink-200">{{ ablationPyramid.measured.datasetSource }}</span>
                  </div>
                  <div class="flex justify-between gap-3">
                    <span class="text-ink-400">Timeline windows / Events</span>
                    <span class="text-ink-200">{{ ablationPyramid.measured.timelineWindows }} / {{ ablationPyramid.measured.eventsTotal }} ({{ ablationPyramid.measured.eventsAboveTau }} ≥ 𝜏)</span>
                  </div>
                  <div class="flex justify-between gap-3">
                    <span class="text-ink-400">Threshold 𝜏 (P99)</span>
                    <span class="text-honey-300">{{ ablationPyramid.measured.decisionThresholdTau }}</span>
                  </div>
                  <div class="flex justify-between gap-3">
                    <span class="text-ink-400">Hotspot status</span>
                    <span class="text-ink-200">{{ ablationPyramid.measured.hotspotStatus }} ({{ ablationPyramid.measured.activeBoardCells ?? '—' }} cells)</span>
                  </div>
                  <div class="flex justify-between gap-3">
                    <span class="text-ink-400">SAM 3.1 status</span>
                    <span class="text-clover-300">{{ ablationPyramid.measured.samStatus }}</span>
                  </div>
                </div>
              </template>
              <p v-else class="text-xs text-ink-400">No ablation telemetry loaded.</p>
            </div>
          </div>

          <!-- Empty State when no event is selected -->
          <div v-else class="flex-1 flex flex-col items-center justify-center text-center p-6 text-ink-400 my-auto">
            <p class="text-xs text-ink-400 max-w-xs">Awaiting anomaly events on storage.</p>
          </div>

          <footer class="px-4 py-1.5 flex items-center justify-end gap-2 text-[10px] text-ink-500/50 shrink-0">
            <a href="https://www.finn-hertsch.de/impressum" target="_blank" rel="noopener noreferrer" class="hover:text-ink-400 transition-colors">Impressum</a>
            <span aria-hidden="true">·</span>
            <a href="https://www.finn-hertsch.de/datenschutz" target="_blank" rel="noopener noreferrer" class="hover:text-ink-400 transition-colors">Datenschutz</a>
          </footer>
        </section>
      </main>
    </div>

    <!-- ================================================================= -->
    <!-- INSPECTOR MODAL (Payloads, cURL & System Telemetry)               -->
    <!-- ================================================================= -->
    <div
      v-if="stagedMuseModalOpen"
      class="fixed inset-0 bg-ink-950/85 z-50 flex items-end sm:items-center justify-center p-0 sm:p-6"
      role="dialog"
      aria-modal="true"
      aria-labelledby="staging-modal-title"
      @click.self="stagedMuseModalOpen = false"
    >
      <div class="bg-ink-900 rounded-t-xl sm:rounded-lg w-full max-w-xl max-h-[88svh] flex flex-col overflow-hidden">
        <!-- Modal Header -->
        <div class="px-4 py-3 flex items-center justify-between bg-ink-900">
          <div>
            <h3 id="staging-modal-title" class="text-sm font-semibold text-ink-100">System &amp; Payload Inspector</h3>
            <p class="text-[11px] text-ink-400">Runtime telemetry, grounded prompts &amp; wire payloads</p>
          </div>
          <button
            type="button"
            @click="stagedMuseModalOpen = false"
            aria-label="Close inspector"
            class="min-h-[38px] min-w-[38px] flex items-center justify-center text-ink-400 hover:text-ink-100 rounded-md hover:bg-ink-800 transition-colors touch-manipulation"
          >
            <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Modal Body -->
        <div class="p-4 space-y-3.5 overflow-y-auto flex-1 text-xs">
          <!-- System Telemetry Strip -->
          <div class="grid grid-cols-3 gap-1.5 text-[11px] tabular-nums">
            <div class="p-2.5 bg-ink-950 rounded-md">
              <div class="text-ink-400 text-[10px]">Storage</div>
              <div class="text-ink-200 font-medium mt-0.5 truncate">{{ storageStatus.isMounted ? storageStatus.freeSpaceFormatted + ' free' : 'Offline' }}</div>
            </div>
            <div class="p-2.5 bg-ink-950 rounded-md">
              <div class="text-ink-400 text-[10px]">Baseten Muse</div>
              <div class="text-ink-200 font-medium mt-0.5">{{ metaHealth.liveConnection ? `Live (${metaHealth.latencyMs}ms)` : 'Cached' }}</div>
            </div>
            <div class="p-2.5 bg-ink-950 rounded-md">
              <div class="text-ink-400 text-[10px]">FFM Ring Buffer</div>
              <div class="text-electric-300 font-medium mt-0.5">{{ ringBufferTelemetry.activeSlotsCount }}/{{ ringBufferTelemetry.capacity }} slots</div>
            </div>
          </div>

          <!-- Target Switcher: Muse Spark vs SAM 3.1 Wire -->
          <div class="flex items-center justify-between gap-2">
            <div class="flex items-center gap-1.5">
              <button
                type="button"
                @click="prepareMusePayload(selectedEvent)"
                class="px-2.5 py-1.5 rounded bg-ink-950 hover:bg-ink-800 text-ink-200 text-xs transition-colors"
              >
                Muse Spark Payload
              </button>
              <button
                type="button"
                @click="prepareSamPayload(selectedEvent)"
                class="px-2.5 py-1.5 rounded bg-ink-950 hover:bg-ink-800 text-ink-200 text-xs transition-colors"
              >
                SAM 3.1 Wire
              </button>
            </div>
            <span class="text-[11px] text-ink-400 tabular-nums">
              {{ selectedEvent?.eventId || '' }}
            </span>
          </div>

          <!-- Staged Inspector Tabs -->
          <div class="space-y-2.5">
            <div class="flex items-center gap-1 bg-ink-950 p-1 rounded-md w-fit">
              <button
                type="button"
                @click="stagedTab = 'prompt'"
                :class="stagedTab === 'prompt' ? 'bg-ink-800 text-honey-400 font-medium' : 'text-ink-400 hover:text-ink-200'"
                class="min-h-[32px] px-3 rounded text-xs transition-colors shrink-0 touch-manipulation"
              >
                Grounded Prompt
              </button>
              <button
                type="button"
                @click="stagedTab = 'payload'"
                :class="stagedTab === 'payload' ? 'bg-ink-800 text-honey-400 font-medium' : 'text-ink-400 hover:text-ink-200'"
                class="min-h-[32px] px-3 rounded text-xs transition-colors shrink-0 touch-manipulation"
              >
                Wire Payload &amp; cURL
              </button>
            </div>

            <!-- Tab 1: Formatted Grounded Prompt -->
            <pre v-if="stagedTab === 'prompt'" class="p-3 bg-ink-950 rounded-md font-mono text-[11px] text-ink-300 whitespace-pre-wrap leading-relaxed max-h-56 overflow-y-auto">{{ stagedMuseQuery?.prompt || stagedSamQuery?.prompt || 'No prompt staged yet.' }}</pre> <!-- taste-skill-ignore -->

            <!-- Tab 2: Wire Payload & cURL -->
            <div v-if="stagedTab === 'payload'" class="space-y-2">
              <div v-if="stagedSamQuery?.curlCommand || stagedMuseQuery?.curlCommand" class="p-2.5 bg-ink-950 rounded-md space-y-1">
                <div class="text-[10px] text-honey-400 font-semibold">cURL command</div>
                <pre class="text-ink-300 whitespace-pre-wrap font-mono text-[10px] leading-tight select-all max-h-28 overflow-y-auto">{{ stagedSamQuery?.curlCommand || stagedMuseQuery?.curlCommand }}</pre> <!-- taste-skill-ignore -->
              </div>
              <pre class="p-2.5 bg-ink-950 rounded-md font-mono text-[10px] text-ink-200 whitespace-pre-wrap max-h-44 overflow-y-auto">{{ JSON.stringify(stagedMuseQuery?.payload || stagedSamQuery?.imagePayload || museDiagnosis || { model: 'muse-spark-open' }, null, 2) }}</pre> <!-- taste-skill-ignore -->
            </div>
          </div>
        </div>

        <!-- Modal Footer -->
        <div class="px-4 py-3 bg-ink-900 flex items-center justify-end gap-2 pb-[calc(env(safe-area-inset-bottom)+0.75rem)] sm:pb-3">
          <button
            type="button"
            @click="stagedMuseModalOpen = false"
            class="min-h-[38px] px-4 bg-ink-950 hover:bg-ink-800 text-ink-300 rounded-md text-xs transition-colors touch-manipulation"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
