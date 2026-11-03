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
  selectedCounterfactual,
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
const showAblationMatrix = ref(false)
const mobilePanel = ref('causal')
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

const activeCounterfactualObj = computed(() => {
  const list = ablationPyramid.value?.counterfactuals
  if (!Array.isArray(list) || !list.length) return null
  return list.find(c => c.id === selectedCounterfactual.value) || list[list.length - 1]
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
    if (activeStopRecording) {
      activeStopRecording()
    }
    return
  }

  stopAllAudio()

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

      const wavBytes = encodeWavBuffer(allSamples, sampleRate)

      isProcessing.value = false

      let recognizedText = museQuestionInput.value.trim()

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

      if (recognizedText) {
        console.log('Voice input complete. Executing production pipeline:', recognizedText)
        await askMuse(recognizedText)
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
  let text = md.trim()

  text = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  text = text.replace(/^###\s*(.*)$/gm, '<h4 class="text-xs font-semibold text-honey-400 mt-2 mb-0.5">$1</h4>')
  text = text.replace(/^##\s*(.*)$/gm, '<h3 class="text-xs font-semibold text-honey-300 mt-2.5 mb-1">$1</h3>')
  text = text.replace(/^#\s*(.*)$/gm, '<h2 class="text-sm font-semibold text-honey-300 mt-3 mb-1">$1</h2>')

  text = text.replace(/\*\*(.*?)\*\*/g, '<strong class="font-semibold text-ink-100">$1</strong>')
  text = text.replace(/\*(.*?)\*/g, '<em class="italic text-ink-300">$1</em>')

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
  const pad = String(idx).padStart(2, '0') // taste-skill-ignore
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

// V-JEPA Hotspot rectangle in 1080x960 frame coordinates (left panel: 0..540)
const vjepaHotspotRect = computed(() => {
  if (!selectedEvent.value?.hotspot) {
    return { x: 235, y: 372, width: 160, height: 108 }
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
  const cx = Math.round(Number(hs.normCenterX || 0.456) * 540)
  const cy = Math.round(Number(hs.normCenterY || 0.436) * 960)
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
      <!-- Top Command Bar (Sticky on Mobile) -->
      <header class="sticky top-0 z-30 lg:static border-b border-ink-800 bg-ink-900 px-2.5 sm:px-5 pt-[calc(env(safe-area-inset-top)+0.4rem)] pb-1.5 sm:py-2 flex items-center justify-between gap-1.5 sm:gap-2 shrink-0">
        <!-- Brand & Hardware Node -->
        <div class="flex items-center gap-1.5 sm:gap-2 shrink-0">
          <div class="w-7 h-7 sm:w-8 sm:h-8 rounded-md bg-ink-950 border border-ink-800 p-1 flex items-center justify-center shrink-0">
            <img :src="beeLogo" alt="beeJEPA" class="w-full h-full object-contain" />
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <h1 class="text-sm font-semibold text-ink-100 tracking-tight whitespace-nowrap">BeeJEPA</h1>
          </div>
        </div>

        <!-- Right Telemetry & Operator Controls -->
        <div class="flex items-center gap-1 sm:gap-2 text-xs shrink-0">
          <!-- Storage Status -->
          <div class="hidden sm:flex items-center gap-1.5 px-2.5 min-h-[34px] rounded-md bg-ink-950 border border-ink-800 tabular-nums">
            <span class="w-2 h-2 rounded-full shrink-0" :class="storageStatus.isMounted ? 'bg-clover-400' : 'bg-ink-600'"></span>
            <span class="text-ink-400">Storage</span>
            <span class="font-mono text-ink-200 text-[11px]">
              {{ storageStatus.isMounted ? storageStatus.freeSpaceFormatted + ' free' : 'Offline' }}
            </span>
          </div>

          <!-- Baseten Muse API Health -->
          <button
            type="button"
            @click="checkMetaHealth"
            :title="(metaHealth.message || 'Meta API status') + ' (Click to re-verify)'"
            class="hidden md:flex items-center gap-1.5 px-2.5 min-h-[34px] rounded-md bg-ink-950 border border-ink-800 hover:border-ink-700 transition-colors cursor-pointer text-left tabular-nums"
          >
            <span
              class="w-2 h-2 rounded-full shrink-0"
              :class="metaHealth.liveConnection ? 'bg-clover-400' : (metaHealth.configured ? 'bg-honey-400' : 'bg-ink-500')"
            ></span>
            <span class="text-ink-400">Baseten Muse</span>
            <span class="font-mono text-ink-200 text-[11px]">
              {{ metaHealth.liveConnection ? 'Live (' + metaHealth.latencyMs + 'ms)' : (metaHealth.configured ? metaHealth.status : 'Cached') }}
            </span>
          </button>

          <!-- Java 25 FFM Ring Buffer Telemetry -->
          <button
            type="button"
            @click="fetchPerceptionTelemetry"
            class="hidden xl:flex items-center gap-1.5 px-2.5 min-h-[34px] rounded-md bg-ink-950 border border-ink-800 hover:border-ink-700 transition-colors cursor-pointer tabular-nums"
            title="Java 25 FFM Off-Heap Ring Buffer"
          >
            <span class="w-2 h-2 rounded-full bg-electric-400 shrink-0"></span>
            <span class="text-ink-400">FFM Buffer</span>
            <span class="font-mono text-electric-300 text-[11px]">
              {{ ringBufferTelemetry.activeSlotsCount }}/{{ ringBufferTelemetry.capacity }} slots
            </span>
          </button>

          <!-- Calibration & Bioacoustic Mode Group -->
          <div class="flex items-center gap-0.5 sm:gap-1 bg-ink-950 border border-ink-800 rounded-md p-0.5">
            <button
              type="button"
              @click="stagedMuseModalOpen = true"
              title="API Staging & Calibration Inspector"
              aria-label="Open Meta Muse Staging Inspector"
              class="min-h-[38px] min-w-[38px] sm:min-h-[32px] sm:min-w-0 px-2 text-ink-300 hover:text-honey-400 hover:bg-ink-900 rounded flex items-center justify-center gap-1.5 transition-colors touch-manipulation"
            >
              <svg class="w-4 h-4 sm:w-3.5 sm:h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
                <path stroke-linecap="round" stroke-linejoin="round" d="M4 7h16M4 12h10M4 17h7" />
              </svg>
              <span class="hidden sm:inline font-mono text-[11px]">Staging</span>
            </button>

            <button
              type="button"
              @click="toggleBeeMode"
              :title="beeModeActive ? 'Bee Mode: ON (Responses synthesized as bioacoustic bee buzzing)' : 'Bee Mode: OFF (Click to enable bioacoustic buzzing)'"
              :class="beeModeActive ? 'bg-honey-500/20 text-honey-300 border border-honey-500/40' : 'text-ink-400 hover:text-ink-200 hover:bg-ink-900 border border-transparent'"
              class="min-h-[38px] sm:min-h-[32px] flex items-center gap-1.5 px-2 sm:px-2.5 rounded text-xs transition-colors touch-manipulation"
            >
              <svg class="w-3.5 h-3.5 shrink-0 text-honey-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
                <path stroke-linecap="round" stroke-linejoin="round" d="M3 12h3l2-5 4 10 3-7 2 2h4" />
              </svg>
              <span class="font-mono text-[11px] font-medium whitespace-nowrap">
                <span class="sm:hidden">{{ isBeeModePlaying ? 'Buzz' : (beeModeActive ? 'Bee ON' : 'Bee OFF') }}</span>
                <span class="hidden sm:inline">{{ isBeeModePlaying ? 'Buzzing...' : (beeModeActive ? 'Bee Mode: ON' : 'Bee Mode: OFF') }}</span>
              </span>
            </button>
          </div>

          <!-- Operator Session & Sign Out -->
          <div class="flex items-center gap-1.5 sm:gap-2 pl-1 sm:pl-2 border-l border-ink-800">
            <span class="hidden sm:inline text-xs font-mono text-honey-400">{{ currentUser?.username || 'beekeeper' }}</span>
            <button
              type="button"
              @click="handleLogout"
              class="min-h-[38px] sm:min-h-[34px] px-2 sm:px-2.5 text-xs text-ink-300 hover:text-ink-100 bg-ink-950 hover:bg-ink-800 border border-ink-800 rounded-md transition-colors touch-manipulation"
            >
              Logout
            </button>
          </div>
        </div>
      </header>

      <!-- Mobile Compact Telemetry Bar -->
      <div class="sm:hidden border-b border-ink-800 bg-ink-950 px-3 min-h-[38px] flex items-center justify-between gap-2 text-[11px] font-mono text-ink-400 tabular-nums shrink-0">
        <div class="flex items-center gap-1.5 truncate">
          <span class="w-1.5 h-1.5 rounded-full shrink-0" :class="storageStatus.isMounted ? 'bg-clover-400' : 'bg-ink-600'"></span>
          <span class="truncate">Storage {{ storageStatus.isMounted ? storageStatus.freeSpaceFormatted : 'Offline' }}</span>
        </div>
        <button type="button" @click="checkMetaHealth" class="min-h-[38px] px-1 flex items-center gap-1.5 text-ink-300 shrink-0 touch-manipulation">
          <span class="w-1.5 h-1.5 rounded-full" :class="metaHealth.liveConnection ? 'bg-clover-400' : 'bg-honey-400'"></span>
          <span>Muse {{ metaHealth.liveConnection ? metaHealth.latencyMs + 'ms' : 'Cached' }}</span>
        </button>
        <button type="button" @click="fetchPerceptionTelemetry" class="min-h-[38px] px-1 flex items-center gap-1 text-electric-300 shrink-0 touch-manipulation">
          <span>FFM {{ ringBufferTelemetry.activeSlotsCount }}/{{ ringBufferTelemetry.capacity }}</span>
        </button>
      </div>

      <!-- Main Operational Workspace: 12-Col Grid on Desktop, Mobile-First Coupled Stage on Phone -->
      <main class="flex-1 p-2 sm:p-4 pb-[calc(env(safe-area-inset-bottom)+0.75rem)] sm:pb-4 flex flex-col lg:grid lg:grid-cols-12 gap-3 sm:gap-4 lg:overflow-hidden">
        <!-- =============================================================== -->
        <!-- LEFT PANE (7 Cols): VISUAL STAGE + COUPLED SURPRISE TIMELINE     -->
        <!-- =============================================================== -->
        <section class="lg:col-span-7 flex flex-col bg-ink-900 rounded-lg border border-ink-800 overflow-hidden shrink-0 lg:shrink lg:min-h-0">
          <!-- View Mode Bar (3-Col Equal Touch Tabs on Mobile, Flex Row on Desktop) -->
          <div class="px-1 sm:px-4 border-b border-ink-800 bg-ink-900 flex items-center justify-between gap-2 shrink-0">
            <div class="grid grid-cols-3 w-full sm:w-auto sm:flex sm:items-center sm:gap-1" role="tablist" aria-label="Stage view mode">
              <button
                type="button"
                role="tab"
                :aria-selected="activeView === 'comparison'"
                @click="activeView = 'comparison'"
                :class="activeView === 'comparison'
                  ? 'border-b-2 border-honey-400 text-ink-100 font-semibold'
                  : 'border-b-2 border-transparent text-ink-400 hover:text-ink-200'"
                class="px-1.5 sm:px-3 min-h-[44px] text-xs transition-colors whitespace-nowrap flex items-center justify-center touch-manipulation"
              >
                <span class="sm:hidden">Sequence (5m)</span>
                <span class="hidden sm:inline">Sequence Stream (5m)</span>
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="activeView === 'raw'"
                @click="activeView = 'raw'"
                :class="activeView === 'raw'
                  ? 'border-b-2 border-honey-400 text-ink-100 font-semibold'
                  : 'border-b-2 border-transparent text-ink-400 hover:text-ink-200'"
                class="px-1.5 sm:px-3 min-h-[44px] text-xs transition-colors whitespace-nowrap flex items-center justify-center touch-manipulation"
              >
                Event Clip
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="activeView === 'sam'"
                @click="activeView = 'sam'"
                :class="activeView === 'sam'
                  ? 'border-b-2 border-honey-400 text-ink-100 font-semibold'
                  : 'border-b-2 border-transparent text-ink-400 hover:text-ink-200'"
                class="px-1.5 sm:px-3 min-h-[44px] text-xs transition-colors whitespace-nowrap flex items-center justify-center touch-manipulation"
              >
                <span class="sm:hidden">{{ selectedEvent?.hasSamSpatial ? 'SAM 3.1' : 'Peak Frame' }}</span>
                <span class="hidden sm:inline">{{ selectedEvent?.hasSamSpatial ? 'SAM 3.1 Segmentation' : 'V-JEPA Peak Frame' }}</span>
              </button>
            </div>

            <!-- Active Event Coordinate Readout (Desktop/Tablet right side) -->
            <div v-if="selectedEvent" class="hidden sm:flex items-center gap-1.5 text-[11px] font-mono text-ink-400 tabular-nums py-1.5 shrink-0">
              <span class="text-honey-400 font-semibold">{{ selectedEvent.eventId }}</span>
              <span>·</span>
              <span class="text-ink-200">{{ formatTime(selectedEvent.timestampSec) }}</span>
              <span class="text-ink-500">(f{{ selectedEvent.frameIndex }})</span>
            </div>
          </div>

          <!-- Aspect-Locked 1080x960 (9:8) Video & Peak Frame Stage -->
          <div class="relative w-full h-[210px] sm:h-auto sm:aspect-video lg:aspect-auto lg:flex-1 bg-ink-950 flex items-center justify-center overflow-hidden sm:min-h-[260px] lg:min-h-0">
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
                  <!-- Real SAM 3.1 Board Bees (only when sam_spatial_*.json exists for this event) -->
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
                </svg>
                <div class="absolute bottom-2 left-2 right-2 flex flex-wrap items-center justify-between gap-1.5 pointer-events-none">
                  <span
                    v-if="selectedEvent?.hasSamSpatial && selectedEvent?.samSpatial"
                    class="bg-ink-950/90 border border-clover-500/40 text-clover-300 font-mono text-[10px] px-2 py-0.5 rounded tabular-nums"
                  >
                    SAM 3.1: {{ selectedEvent.samSpatial.landingBoardBeeCount }} board bees · {{ selectedEvent.samSpatial.beesInsideHotspotCount }} in V-JEPA hotspot
                  </span>
                  <span
                    v-else
                    class="bg-ink-950/90 border border-ink-700 text-ink-300 font-mono text-[10px] px-2 py-0.5 rounded tabular-nums"
                  >
                    V-JEPA 2.1 Hotspot · No SAM 3.1 mask cached for this event
                  </span>
                  <span class="bg-ink-950/90 border border-electric-500/40 text-electric-300 font-mono text-[10px] px-2 py-0.5 rounded tabular-nums">
                    GRID [{{ selectedEvent?.hotspot?.tubeletGridX ?? 2 }}, {{ selectedEvent?.hotspot?.tubeletGridY ?? 3 }}]
                  </span>
                </div>
              </div>

              <!-- Saccadic Reticle HUD (Foveal Focus Crosshair at Peak Anomaly in Video Modes) -->
              <div
                v-if="selectedEvent && saccadicReticleStyle && isNearPeakAnomaly && activeView !== 'sam'"
                class="absolute pointer-events-none transition-transform duration-300 ease-out z-20"
                :style="saccadicReticleStyle"
              >
                <div class="relative -translate-x-1/2 -translate-y-1/2">
                  <div class="w-16 h-16 sm:w-20 sm:h-20 rounded-full border border-electric-400/80 bg-electric-500/10 flex items-center justify-center">
                    <div class="w-2.5 h-2.5 rounded-full border border-electric-300 bg-electric-400/40"></div>
                    <div class="absolute w-full h-[1px] bg-electric-400/40"></div>
                    <div class="absolute h-full w-[1px] bg-electric-400/40"></div>
                  </div>
                  <div class="absolute top-full left-1/2 -translate-x-1/2 mt-1 px-1.5 py-0.5 bg-ink-950/95 border border-electric-500/50 rounded text-[10px] font-mono text-electric-300 whitespace-nowrap tabular-nums">
                    FOVEA [{{ activeSaccade ? activeSaccade.focusCenterX : Math.round((selectedEvent?.hotspot?.normCenterX ?? 0.456) * 540) }}, {{ activeSaccade ? activeSaccade.focusCenterY : Math.round((selectedEvent?.hotspot?.normCenterY ?? 0.436) * 960) }}] · GRID [{{ selectedEvent?.hotspot?.tubeletGridX ?? 2 }}, {{ selectedEvent?.hotspot?.tubeletGridY ?? 3 }}]
                  </div>
                </div>
              </div>
            </div>

            <!-- Empty Media State -->
            <div v-else class="text-center p-6 text-ink-400">
              <svg class="w-8 h-8 mx-auto mb-2 text-ink-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
              <p class="text-xs font-mono">No event media loaded.</p>
            </div>
          </div>

          <!-- Coupled Latent Surprise Timeline & Scrubber (Mobile-First: Directly Below Video Stage) -->
          <div class="border-t border-ink-800 bg-ink-900 px-3 sm:px-4 py-2 sm:py-2.5 shrink-0">
            <div class="flex items-center justify-between gap-2 mb-1.5 sm:mb-2 text-xs">
              <div class="flex items-center gap-1.5 sm:gap-2 min-w-0">
                <span class="font-medium text-ink-100 truncate">
                  <span class="sm:hidden">Surprise Timeline</span>
                  <span class="hidden sm:inline">
                    {{ timelineData && timelineData.length > 0
                      ? `${Math.round((timelineMeta?.totalDurationSec || 300) / 60 * 10) / 10}-Minute Latent Surprise Timeline (${timelineMeta?.recordingId || 'rec2125'})`
                      : 'Latent Surprise Timeline' }}
                  </span>
                </span>
                <span
                  v-if="timelineData && timelineData.length > 0"
                  class="text-[11px] font-mono text-ember-300 bg-ember-500/10 border border-ember-500/30 px-1.5 py-0.5 rounded tabular-nums shrink-0"
                >
                  <span class="hidden sm:inline">Threshold </span><span class="math">𝜏 = {{ timelineMeta?.decisionThreshold || timelineMeta?.thresholdSigma || '10.2' }}𝜎</span>
                </span>
                <span
                  v-else
                  class="text-[11px] font-mono text-ink-400 bg-ink-950 border border-ink-800 px-1.5 py-0.5 rounded shrink-0"
                >
                  Awaiting data
                </span>
              </div>

              <div v-if="timelineData && timelineData.length > 0" class="text-[11px] text-ink-400 font-mono flex items-center gap-1.5 sm:gap-2 tabular-nums shrink-0">
                <span class="sm:hidden text-honey-400 font-semibold">{{ selectedEvent?.eventId || 'evt_01' }}</span>
                <span class="sm:hidden text-ink-700">·</span>
                <span class="sm:hidden">{{ currentAnomalyIndex >= 0 ? currentAnomalyIndex + 1 : '-' }}/{{ sortedEvents.length }}</span>
                <span class="hidden sm:inline">Scrubber: <strong class="text-honey-400 font-semibold">{{ formatTime(currentTimelineSec) }}</strong> / {{ formatTime(maxDuration) }}</span>
                <span class="hidden sm:inline text-ink-700">·</span>
                <span class="hidden sm:inline">Anomaly {{ currentAnomalyIndex >= 0 ? currentAnomalyIndex + 1 : '-' }} of {{ sortedEvents.length }}</span>
              </div>
            </div>

            <!-- Interactive SVG Fever Curve Track -->
            <div class="relative w-full bg-ink-950 rounded-md border border-ink-800 px-2.5 py-1.5 select-none">
              <div v-if="!timelineData || timelineData.length === 0" class="h-16 sm:h-20 flex flex-col items-center justify-center text-ink-400 text-xs font-mono gap-1">
                <span>No timeline artifacts found on storage.</span>
                <span class="text-[11px] text-ink-500">Connect SSD or populate ./data with sequence events.</span>
              </div>

              <div
                v-else
                class="relative w-full h-16 sm:h-20 cursor-pointer select-none"
                @click="onTimelineContainerClick"
                title="Click to seek on timeline"
              >
                <svg viewBox="0 0 1000 100" preserveAspectRatio="none" class="w-full h-full block pointer-events-none overflow-visible">
                  <defs>
                    <linearGradient id="curveGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stop-color="#EAA23A" stop-opacity="0.26" />
                      <stop offset="65%" stop-color="#EAA23A" stop-opacity="0.06" />
                      <stop offset="100%" stop-color="#EAA23A" stop-opacity="0.0" />
                    </linearGradient>
                  </defs>

                  <line x1="0" y1="92" x2="1000" y2="92" stroke="#202224" stroke-width="1" />
                  <line x1="0" :y1="tauY" x2="1000" :y2="tauY" stroke="#F36C47" stroke-dasharray="4 4" stroke-width="1.2" opacity="0.65" />

                  <path
                    v-if="svgAreaPath"
                    :d="svgAreaPath"
                    fill="url(#curveGradient)"
                  />

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
                  <div class="relative flex items-center justify-center p-2.5">
                    <span
                      class="w-3 h-3 rounded-full border-2 transition-transform group-hover:scale-125"
                      :class="selectedEvent?.eventId === spike.eventId ? 'bg-ember-500 border-ink-100 ring-2 ring-ember-400/60 scale-110' : 'bg-honey-400 border-ink-950'"
                    ></span>
                  </div>

                  <div class="absolute -top-7 left-1/2 -translate-x-1/2 opacity-0 group-hover:opacity-100 transition-opacity bg-ink-900 border border-ink-700 px-2 py-0.5 rounded text-[10px] font-mono text-honey-300 whitespace-nowrap pointer-events-none z-30 tabular-nums">
                    <span class="math">{{ spike.surpriseScoreL2 }}𝜎</span> ({{ formatTime(spike.timestampSec) }})
                  </div>
                </div>

                <!-- Vertical Scrubber Playhead Needle -->
                <div
                  class="absolute top-0 bottom-0 pointer-events-none z-30 -translate-x-1/2"
                  :style="{ left: `${(currentTimelineSec / maxDuration) * 100}%` }"
                >
                  <div class="w-0.5 h-full bg-honey-400"></div>
                  <div class="absolute -top-1 left-1/2 -translate-x-1/2 bg-honey-500 text-ink-950 font-mono text-[9px] font-semibold px-1.5 py-0.5 rounded-sm whitespace-nowrap tabular-nums">
                    {{ formatTime(currentTimelineSec) }}
                  </div>
                </div>
              </div>
            </div>

            <!-- Time Axis Labels -->
            <div v-if="timelineData && timelineData.length > 0" class="flex justify-between text-[10px] font-mono text-ink-400 px-1 pt-1 tabular-nums">
              <span>0:00</span>
              <span class="hidden sm:inline">{{ formatTime(maxDuration * 0.25) }}</span>
              <span>{{ formatTime(maxDuration * 0.5) }}</span>
              <span class="hidden sm:inline">{{ formatTime(maxDuration * 0.75) }}</span>
              <span>{{ formatTime(maxDuration) }}</span>
            </div>

            <!-- Scrubber Range Control + Prev/Next Anomaly Stepper -->
            <div v-if="timelineData && timelineData.length > 0" class="mt-1.5 flex items-center gap-2 sm:gap-3">
              <div class="flex items-center gap-1.5 shrink-0 bg-ink-950 border border-ink-800 px-2.5 min-h-[42px] sm:min-h-[38px] rounded-md text-[11px] font-mono tabular-nums">
                <span class="w-1.5 h-1.5 rounded-full bg-honey-400"></span>
                <span class="text-honey-400 font-semibold">{{ formatTime(currentTimelineSec) }}</span>
                <span class="text-ink-600">/</span>
                <span class="text-ink-400">{{ formatTime(maxDuration) }}</span>
              </div>

              <div class="relative flex-1 flex items-center min-h-[42px]">
                <input
                  type="range"
                  min="0"
                  :max="maxDuration"
                  step="0.5"
                  v-model.number="currentTimelineSec"
                  @input="onTimelineSliderInput"
                  @change="onTimelineSliderChange"
                  class="timeline-slider w-full h-2 bg-ink-800 rounded-lg appearance-none cursor-pointer accent-honey-500 focus:outline-none"
                  aria-label="Timeline scrubber slider"
                />
              </div>

              <div class="flex items-center gap-1.5 sm:gap-1 shrink-0">
                <button
                  type="button"
                  @click="goToPrevAnomaly"
                  :disabled="isFirstAnomaly"
                  class="min-h-[42px] min-w-[42px] sm:min-h-[38px] sm:min-w-0 px-2.5 text-[11px] font-mono rounded-md bg-ink-950 hover:bg-ink-800 text-ink-200 border border-ink-800 disabled:opacity-35 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-1 touch-manipulation"
                  title="Previous anomaly"
                  aria-label="Previous anomaly"
                >
                  <svg class="w-4 h-4 sm:w-3.5 sm:h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M15 19l-7-7 7-7" />
                  </svg>
                  <span class="hidden sm:inline">Prev</span>
                </button>
                <button
                  type="button"
                  @click="goToNextAnomaly"
                  :disabled="isLastAnomaly"
                  class="min-h-[42px] min-w-[42px] sm:min-h-[38px] sm:min-w-0 px-2.5 text-[11px] font-mono rounded-md bg-ink-950 hover:bg-ink-800 text-ink-200 border border-ink-800 disabled:opacity-35 disabled:cursor-not-allowed transition-colors flex items-center justify-center gap-1 touch-manipulation"
                  title="Next anomaly"
                  aria-label="Next anomaly"
                >
                  <span class="hidden sm:inline">Next</span>
                  <svg class="w-4 h-4 sm:w-3.5 sm:h-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </section>

        <!-- =============================================================== -->
        <!-- RIGHT PANE (5 Cols): TELEMETRY, MUSE SPARK & ABLATION PYRAMID   -->
        <!-- =============================================================== -->
        <section class="lg:col-span-5 flex flex-col bg-ink-900 rounded-lg border border-ink-800 p-3.5 sm:p-4 lg:overflow-y-auto lg:min-h-0">
          <!-- Panel Header -->
          <div class="flex items-center justify-between pb-3 border-b border-ink-800 shrink-0">
            <h2 class="text-sm font-semibold text-ink-100">Meta AI Causal Analysis</h2>
            <span
              v-if="selectedEvent?.hasSamSpatial"
              class="text-[11px] font-mono px-2 py-0.5 rounded bg-clover-500/10 text-clover-400 border border-clover-500/30 tabular-nums"
            >
              SAM 3.1 + Muse cached
            </span>
            <span
              v-else
              class="text-[11px] font-mono px-2 py-0.5 rounded bg-ink-950 text-ink-300 border border-ink-800"
            >
              V-JEPA 2.1 peak
            </span>
          </div>

          <div v-if="selectedEvent" class="mt-3.5 space-y-3.5">
            <!-- 1px Hairline-Divided 4-Cell KPI Strip (2x2 on mobile, 4-up on wide) -->
            <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-2 xl:grid-cols-4 gap-px bg-ink-800 border border-ink-800 rounded-md overflow-hidden">
              <!-- Primary Metric: V-JEPA Surprise Score -->
              <div class="p-3 bg-ink-950 flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">V-JEPA Surprise</div>
                <div class="text-2xl font-semibold font-mono text-ember-400 mt-1 tabular-nums leading-tight">
                  <span class="math">{{ Number(selectedEvent.surpriseScoreL2).toFixed(2) }}𝜎</span>
                </div>
                <div class="text-[11px] text-ink-500 mt-1">Latent <span class="math">‖𝑧̂ − 𝑧‖<sub>2</sub></span></div>
              </div>

              <!-- Sibling Metric 2: Landing Board Bee Count (Real SAM 3.1 or V-JEPA Detector Count) -->
              <div class="p-3 bg-ink-950 flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">Landing Board</div>
                <div class="text-lg font-semibold font-mono text-ink-100 mt-1 tabular-nums leading-tight">
                  {{ selectedEvent.boardBees ?? '—' }} bees
                </div>
                <div class="text-[11px] text-ink-500 mt-1 font-mono">
                  {{ selectedEvent.hasSamSpatial ? 'SAM 3.1 count' : 'V-JEPA 2.1 count' }}
                </div>
              </div>

              <!-- Sibling Metric 3: Tubelet Hotspot Grid -->
              <div class="p-3 bg-ink-950 flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">Tubelet Hotspot</div>
                <div class="text-lg font-semibold font-mono text-honey-400 mt-1 tabular-nums leading-tight">
                  ({{ selectedEvent?.hotspot?.tubeletGridX ?? 2 }}, {{ selectedEvent?.hotspot?.tubeletGridY ?? 3 }})
                </div>
                <div class="text-[11px] text-ink-500 mt-1 font-mono tabular-nums">
                  ({{ Number(selectedEvent?.hotspot?.normCenterX ?? 0.456).toFixed(3) }}, {{ Number(selectedEvent?.hotspot?.normCenterY ?? 0.436).toFixed(3) }})
                </div>
              </div>

              <!-- Sibling Metric 4: Hotspot Bees (when SAM-segmented) or Active V-JEPA Grid Cells -->
              <div class="p-3 bg-ink-950 flex flex-col justify-between">
                <div class="text-[11px] text-ink-400">
                  {{ selectedEvent.hasSamSpatial ? 'Hotspot Cluster' : 'Active Grid Cells' }}
                </div>
                <div class="text-lg font-semibold font-mono text-electric-300 mt-1 tabular-nums leading-tight">
                  {{ selectedEvent.hasSamSpatial ? (selectedEvent.hotspotBeesCount + ' bees') : ((selectedEvent.activeCells ?? '—') + ' cells') }}
                </div>
                <div class="text-[11px] text-ink-500 mt-1 font-mono tabular-nums">
                  {{ selectedEvent.hasSamSpatial ? 'IoA ≥ 0.25 · SAM 3.1' : '43×43 V-JEPA grid' }}
                </div>
              </div>
            </div>

            <!-- Active Perception Saccade Classification Strip -->
            <div v-if="activeSaccade" class="px-3 py-2 bg-ink-950 rounded-md border border-ink-800 flex flex-wrap items-center justify-between gap-2 text-xs">
              <div class="flex items-center gap-2 text-electric-300 font-medium">
                <span class="w-2 h-2 rounded-full bg-electric-400 shrink-0"></span>
                <span>Active Perception Saccade</span>
              </div>
              <div class="flex items-center gap-2 font-mono text-[11px] tabular-nums">
                <span class="text-ink-400">Jump {{ activeSaccade.saccadicJumpDistancePx }} px</span>
                <span class="text-ink-700">·</span>
                <span class="text-electric-300 bg-electric-950/60 px-1.5 py-0.5 rounded border border-electric-800/60">
                  {{ activeSaccade.classification }}
                </span>
              </div>
            </div>

            <!-- Mobile Section Switcher (< lg): Prevents long vertical scroll on 390px screens -->
            <div class="flex lg:hidden border-b border-ink-800" role="tablist" aria-label="Analysis section">
              <button
                type="button"
                role="tab"
                :aria-selected="mobilePanel === 'causal'"
                @click="mobilePanel = 'causal'"
                :class="mobilePanel === 'causal'
                  ? 'border-b-2 border-honey-400 text-ink-100 font-semibold'
                  : 'border-b-2 border-transparent text-ink-400 hover:text-ink-200'"
                class="flex-1 py-2.5 text-xs text-center transition-colors min-h-[42px]"
              >
                Causal Console &amp; Briefing
              </button>
              <button
                type="button"
                role="tab"
                :aria-selected="mobilePanel === 'ablation'"
                @click="mobilePanel = 'ablation'"
                :class="mobilePanel === 'ablation'
                  ? 'border-b-2 border-honey-400 text-ink-100 font-semibold'
                  : 'border-b-2 border-transparent text-ink-400 hover:text-ink-200'"
                class="flex-1 py-2.5 text-xs text-center transition-colors min-h-[42px]"
              >
                5-Tier Ablation Pyramid
              </button>
            </div>

            <!-- =========================================================== -->
            <!-- SUB-PANEL 1: CAUSAL PROMPT, BRIEFING & SEGMENTATION         -->
            <!-- =========================================================== -->
            <div :class="mobilePanel === 'causal' ? 'block' : 'hidden lg:block'" class="space-y-3.5">
              <!-- Meta AI & SAM 3.1 Prompt & Voice Console -->
              <div class="p-3 bg-ink-950 rounded-md border border-ink-800 space-y-2.5">
                <div class="flex items-center justify-between gap-2">
                  <label for="muse-prompt-input" class="text-xs font-medium text-ink-200">
                    Meta AI &amp; SAM 3.1 Prompt
                  </label>
                  <div class="flex items-center gap-1.5">
                    <span v-if="isTranscribing" class="text-[11px] text-honey-300 font-mono">
                      Transcribing...
                    </span>
                    <button
                      type="button"
                      @click="toggleVoiceInput"
                      :class="isRecording ? 'bg-ember-500/20 text-ember-300 border-ember-500/50' : 'bg-ink-900 text-ink-300 hover:text-ink-100 border-ink-800'"
                      class="min-h-[38px] sm:min-h-[32px] px-3 sm:px-2.5 rounded text-[11px] font-mono border flex items-center gap-1.5 transition-colors touch-manipulation"
                      title="Record voice query and run production pipeline through Meta Muse"
                    >
                      <svg class="w-3.5 h-3.5 sm:w-3 sm:h-3 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M12 2a3 3 0 00-3 3v7a3 3 0 006 0V5a3 3 0 00-3-3z" />
                        <path stroke-linecap="round" stroke-linejoin="round" d="M19 10v2a7 7 0 01-14 0v-2M12 19v3m-4 0h8" />
                      </svg>
                      <span>{{ isRecording ? 'Listening...' : 'Voice Input' }}</span>
                    </button>
                  </div>
                </div>

                <!-- Interactive Dynamic Textarea (16px on mobile prevents iOS Safari zoom) -->
                <div>
                  <textarea
                    id="muse-prompt-input"
                    v-model="museQuestionInput"
                    rows="2"
                    class="w-full px-3 py-2 bg-ink-900 border border-ink-800 rounded-md text-ink-100 text-base sm:text-xs focus:outline-none focus:border-honey-400 resize-none font-sans leading-relaxed"
                    placeholder="Ask about this anomaly or tap Voice Input..."
                  ></textarea>
                </div>

                <!-- Quick Presets -->
                <div class="flex flex-wrap items-center gap-1.5">
                  <button
                    type="button"
                    @click="setPresetQuestion('Explain physical collision cause: What occurred at this tubelet?')"
                    class="min-h-[38px] sm:min-h-[32px] px-2.5 py-1 rounded bg-ink-900 hover:bg-ink-800 border border-ink-800 text-ink-300 hover:text-ink-100 text-[11px] transition-colors flex-1 sm:flex-initial inline-flex items-center justify-center touch-manipulation"
                  >
                    Collision cause
                  </button>
                  <button
                    type="button"
                    @click="setPresetQuestion('Assess wasp/hornet predation threat based on spatial flight velocity')"
                    class="min-h-[38px] sm:min-h-[32px] px-2.5 py-1 rounded bg-ink-900 hover:bg-ink-800 border border-ink-800 text-ink-300 hover:text-ink-100 text-[11px] transition-colors flex-1 sm:flex-initial inline-flex items-center justify-center touch-manipulation"
                  >
                    Hornet threat
                  </button>
                  <button
                    type="button"
                    @click="setPresetQuestion('Isolate anomalous bee on landing board and extract spatial contour')"
                    class="min-h-[38px] sm:min-h-[32px] px-2.5 py-1 rounded bg-ink-900 hover:bg-ink-800 border border-ink-800 text-ink-300 hover:text-ink-100 text-[11px] transition-colors flex-1 sm:flex-initial inline-flex items-center justify-center touch-manipulation"
                  >
                    SAM contour
                  </button>
                </div>
              </div>

              <!-- Action Buttons: Single Solid Primary CTA + Ghost Secondary -->
              <div class="flex items-center gap-2">
                <button
                  type="button"
                  @click="askMuse()"
                  :disabled="isAskingMuse"
                  class="flex-1 min-h-[44px] py-2.5 px-4 bg-honey-400 hover:bg-honey-300 disabled:opacity-50 text-ink-950 font-semibold text-xs rounded-md transition-colors flex items-center justify-center gap-2 touch-manipulation"
                >
                  <span v-if="isAskingMuse" class="w-3.5 h-3.5 border-2 border-ink-950 border-t-transparent rounded-full animate-spin"></span>
                  <span>{{ isAskingMuse ? 'Analyzing Hive...' : 'Ask Muse Spark' }}</span>
                </button>

                <button
                  type="button"
                  @click="prepareSamPayload(selectedEvent)"
                  :disabled="isPreparingMuse || isAskingMuse"
                  class="min-h-[44px] py-2.5 px-3 bg-ink-950 hover:bg-ink-800 disabled:opacity-50 text-ink-300 hover:text-honey-300 font-medium text-xs rounded-md transition-colors border border-ink-800 inline-flex items-center justify-center gap-1.5 touch-manipulation shrink-0"
                  title="Inspect SAM 3.1 wire payload format"
                >
                  <span>SAM 3.1 Wire</span>
                </button>
              </div>

              <!-- Muse Spark Live / Cached Storyboard Diagnosis Card -->
              <div v-if="museDiagnosis" class="p-3.5 bg-ink-950 rounded-md border border-honey-500/40 space-y-3">
                <div class="flex flex-wrap items-center justify-between gap-2 pb-2.5 border-b border-ink-800">
                  <div class="flex items-center gap-2">
                    <span class="text-xs font-semibold text-honey-400">Hive Briefing</span>
                    <span
                      :class="museDiagnosis.degradedFallback
                        ? 'bg-honey-500/15 text-honey-300 border-honey-500/40'
                        : (museDiagnosis.offlineLocked || museDiagnosis.sourceMode === 'DISK_CACHE'
                            ? 'bg-electric-500/15 text-electric-300 border-electric-500/40'
                            : 'bg-clover-500/15 text-clover-300 border-clover-500/40')"
                      class="px-2 py-0.5 rounded border text-[10px] font-mono"
                    >
                      {{ museDiagnosis.degradedFallback ? 'Degraded Fallback' : (museDiagnosis.offlineLocked || museDiagnosis.sourceMode === 'DISK_CACHE' ? 'Offline Cache' : 'Live') }}
                    </span>
                    <span class="text-ink-400 text-[11px] font-mono tabular-nums">{{ museDiagnosis.telemetry?.latencyMs || 0 }}ms</span>
                  </div>

                  <!-- Audio Action Controls -->
                  <div class="flex items-center gap-1.5 flex-wrap">
                    <button
                      type="button"
                      @click="isSpeakingAloud ? stopReadAloud() : readAloud()"
                      class="px-2.5 min-h-[36px] bg-ink-900 hover:bg-ink-800 text-honey-300 border border-honey-500/40 rounded-md flex items-center gap-1.5 transition-colors text-xs font-medium touch-manipulation"
                      :title="isSpeakingAloud ? 'Stop audio' : 'Read briefing aloud in natural English'"
                    >
                      <svg v-if="isSpeakingAloud" class="w-3.5 h-3.5 fill-current" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1"/></svg>
                      <svg v-else class="w-3.5 h-3.5 fill-current" viewBox="0 0 24 24"><path d="M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77zm-2.5-2.23l-5 4H2v12h4.5l5 4V1zm4.5 11c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>
                      <span>{{ isSpeakingAloud ? 'Stop' : 'Read' }}</span>
                    </button>
                    <button
                      type="button"
                      @click="playBeeBuzz()"
                      :disabled="isPlayingBeeBuzz"
                      class="px-2.5 min-h-[36px] bg-ink-900 hover:bg-ink-800 text-ink-200 border border-ink-800 rounded-md flex items-center gap-1.5 transition-colors text-xs touch-manipulation"
                      title="Bioacoustic bee buzz synthesis"
                    >
                      <svg class="w-3.5 h-3.5 text-honey-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.75">
                        <path stroke-linecap="round" stroke-linejoin="round" d="M3 12h3l2-5 4 10 3-7 2 2h4" />
                      </svg>
                      <span>{{ isPlayingBeeBuzz ? 'Buzzing...' : 'FFT-Buzz' }}</span>
                    </button>
                    <label class="flex items-center gap-1.5 text-ink-400 hover:text-ink-200 cursor-pointer text-xs select-none pl-1 min-h-[36px]">
                      <input type="checkbox" v-model="autoReadAloud" class="rounded accent-honey-500 w-3.5 h-3.5" />
                      <span class="text-[11px]">Auto</span>
                    </label>
                  </div>
                </div>

                <!-- Formatted Markdown Diagnosis -->
                <div
                  v-html="formatMarkdownReport(museDiagnosis.report || museDiagnosis.diagnosis)"
                  class="p-3 bg-ink-900 rounded-md border border-ink-800 text-xs text-ink-200 leading-relaxed font-sans max-h-60 overflow-y-auto space-y-1"
                ></div>

                <!-- Multi-Event Storyboard Peak Crops -->
                <div v-if="museDiagnosis.events && museDiagnosis.events.length" class="space-y-1.5 pt-1">
                  <div class="flex items-center justify-between text-[11px] font-medium text-ink-400">
                    <span>Keyframe peak crops</span>
                    <span class="text-[10px] font-mono text-ink-500 md:hidden">Swipe horizontally</span>
                  </div>
                  <div class="flex overflow-x-auto snap-x snap-mandatory gap-2.5 pb-1 scrollbar-none md:grid md:grid-cols-3 md:gap-2 md:overflow-visible">
                    <div
                      v-for="evt in museDiagnosis.events"
                      :key="evt.eventId"
                      class="flex-none w-[74%] sm:w-[58%] md:w-auto snap-center p-1.5 bg-ink-900 rounded-md border border-ink-800 space-y-1.5 text-center"
                    >
                      <img
                        v-if="evt.cropBase64"
                        :src="'data:image/jpeg;base64,' + evt.cropBase64"
                        :alt="evt.eventId"
                        class="w-full aspect-square rounded-sm object-cover border border-ink-800"
                      />
                      <div class="flex items-center justify-between text-[10px] font-mono bg-ink-950 px-2 py-1 rounded-sm border border-ink-800 tabular-nums">
                        <span class="text-ink-300">{{ evt.timestamp }}</span>
                        <span class="math text-ember-400">{{ evt.surpriseScoreSigma }}𝜎</span>
                        <span class="text-honey-400 font-semibold">{{ evt.beesInsideHotspotCount !== undefined ? evt.beesInsideHotspotCount : evt.landingBoardBeeCount }} bees</span>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- Raw API Payload Toggle -->
                <div class="pt-2 flex items-center justify-between text-[11px] text-ink-400 border-t border-ink-800">
                  <button
                    type="button"
                    @click="showRawWirePayload = !showRawWirePayload"
                    class="text-ink-400 hover:text-ink-200 flex items-center gap-1.5 min-h-[32px]"
                  >
                    <span>{{ showRawWirePayload ? 'Hide raw wire payload' : 'Show raw wire payload' }}</span>
                  </button>
                  <button
                    type="button"
                    @click="prepareMusePayload(selectedEvent)"
                    class="text-honey-400 hover:text-honey-300 underline underline-offset-2 min-h-[32px]"
                  >
                    View staging &amp; cURL
                  </button>
                </div>
                <pre v-if="showRawWirePayload" class="p-2.5 bg-ink-900 rounded-md text-[10px] font-mono text-honey-300 overflow-x-auto max-h-40 border border-ink-800">{{ JSON.stringify(museDiagnosis, null, 2) }}</pre>
              </div>

              <!-- Staged Muse Query Status Banner -->
              <div v-if="stagedMuseQuery" class="p-3 bg-honey-500/10 rounded-md border border-honey-500/30 space-y-2">
                <div class="flex items-center justify-between">
                  <span class="text-xs font-semibold text-honey-400">Muse Spark Open Staged</span>
                  <span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-honey-500/20 text-honey-300 border border-honey-500/40 tabular-nums">
                    Dry run
                  </span>
                </div>
                <p class="text-[11px] text-ink-300 tabular-nums">
                  Grounded: V-JEPA Event <span class="font-mono text-honey-300">{{ stagedMuseQuery.eventId || 'Active' }}</span> ({{ stagedMuseQuery.estimatedTokens }} tokens)
                </p>
                <div class="flex items-center justify-between pt-1 text-[11px]">
                  <span class="text-ink-400 font-mono text-[10px]">{{ stagedMuseQuery.httpMethod || 'POST' }} {{ stagedMuseQuery.model || 'meta-models/muse-spark-open' }}</span>
                  <button
                    type="button"
                    @click="stagedMuseModalOpen = true"
                    class="font-medium text-honey-400 hover:text-honey-300 underline underline-offset-2"
                  >
                    Inspect payload &amp; prompt
                  </button>
                </div>
              </div>

              <!-- SAM 3.1 Segmentation Isolation Card -->
              <div v-if="selectedEvent.hasSamSpatial && selectedEvent.agentResult?.sam && selectedEvent.agentResult.sam.status === 'SUCCESS' && selectedEvent.agentResult.sam.segmentedObject" class="p-3 bg-ink-950 rounded-md border border-ink-800 space-y-1.5">
                <div class="flex items-center justify-between">
                  <span class="text-xs font-semibold text-honey-400">SAM 3.1 Instance Segmentation</span>
                  <span class="text-[10px] font-mono text-ink-400">{{ selectedEvent.agentResult.sam.model }}</span>
                </div>
                <p class="text-xs text-ink-300">
                  Isolated object: <span class="font-semibold text-ink-100">{{ selectedEvent.agentResult.sam.segmentedObject }}</span>
                </p>
                <p v-if="selectedEvent.agentResult.sam.instanceCount" class="text-[11px] text-ink-400 font-mono tabular-nums">
                  Board instances: {{ selectedEvent.agentResult.sam.instanceCount }} · Hotspot instances: {{ selectedEvent.agentResult.sam.hotspotBeesCount }}
                </p>
              </div>

              <!-- Muse Spark Causal Scene Diagnosis Card -->
              <div v-if="selectedEvent.hasSamSpatial && selectedEvent.agentResult?.muse && selectedEvent.agentResult.muse.status === 'SUCCESS' && selectedEvent.agentResult.muse.diagnosis" class="p-3 bg-ink-950 rounded-md border border-honey-500/30 space-y-2">
                <div class="flex items-center justify-between">
                  <span class="text-xs font-semibold text-honey-400">Muse Spark Event Summary</span>
                  <span class="text-[10px] font-mono text-ink-400">{{ selectedEvent.agentResult.muse.model }}</span>
                </div>
                <p class="text-xs text-ink-200 leading-relaxed italic bg-ink-900 p-2.5 rounded border border-ink-800">
                  "{{ selectedEvent.agentResult.muse.diagnosis }}"
                </p>
              </div>
            </div>

            <!-- =========================================================== -->
            <!-- SUB-PANEL 2: 5-TIER ABLATION PYRAMID & EMPIRICAL MATRIX     -->
            <!-- =========================================================== -->
            <div v-if="ablationPyramid" :class="mobilePanel === 'ablation' ? 'block' : 'hidden lg:block'">
              <div class="p-3.5 bg-ink-950 rounded-md border border-ink-800 space-y-3">
                <div class="flex flex-wrap items-center justify-between gap-2 pb-2 border-b border-ink-800">
                  <div>
                    <h3 class="text-xs font-semibold text-honey-400">5-Tier Ablation Pyramid</h3>
                    <p class="text-[11px] text-ink-400">Select a tier from Base (V-JEPA 2.1) to Apex (Muse Spark) to inspect its role</p>
                  </div>
                  <span class="text-[10px] font-mono px-2 py-0.5 rounded bg-ink-900 text-ink-300 border border-ink-800 tabular-nums">
                    -{{ ablationPyramid.funnelMetrics?.compressionRatioPct || 93.6 }}% frames · +{{ ablationPyramid.funnelMetrics?.tokenDensityBoost || 8.3 }}x ({{ ablationPyramid.funnelMetrics?.attentionFlopRatio || 68.6 }}x FLOPs)
                  </span>
                </div>

                <!-- Visual Centered Pyramid Stack (Apex Tier 4 -> Base Tier 0) -->
                <div class="flex flex-col items-center gap-1.5 py-1">
                  <button
                    v-for="tier in orderedPyramidTiersApexFirst"
                    :key="tier.id"
                    type="button"
                    @click="selectedPyramidTier = tier.id"
                    :class="[
                      pyramidTierWidthClass(tier.tierIndex),
                      selectedPyramidTier === tier.id
                        ? 'bg-honey-500/20 border-honey-400 text-honey-200'
                        : 'bg-ink-900 border-ink-800 text-ink-300 hover:border-ink-700 hover:text-ink-100'
                    ]"
                    class="min-h-[42px] sm:min-h-[36px] px-2.5 py-1.5 rounded border text-[11px] font-mono flex items-center justify-between transition-colors cursor-pointer tabular-nums touch-manipulation"
                  >
                    <span class="font-semibold truncate">T{{ tier.tierIndex }} · {{ tier.shortTitle }}</span>
                    <span class="text-[10px] text-ink-400 ml-2 shrink-0 hidden sm:inline">{{ tier.quantitativeMetric.split('·')[0] }}</span>
                  </button>
                </div>

                <!-- Selected Tier Role & Counterfactual Breakdown -->
                <div v-if="activePyramidTierObj" class="p-2.5 bg-ink-900 rounded-md border border-ink-800 space-y-1.5 text-[11px]">
                  <div class="flex items-center justify-between gap-2">
                    <span class="font-semibold text-honey-300">{{ activePyramidTierObj.name }}</span>
                    <span class="font-mono text-[10px] text-electric-300">{{ activePyramidTierObj.modelOrComponent }}</span>
                  </div>
                  <p class="text-ink-300 italic text-[11px]">"{{ activePyramidTierObj.roleQuestion }}"</p>
                  <div class="grid grid-cols-1 gap-1 pt-1 font-mono text-[10px]">
                    <div class="bg-ink-950 px-2 py-1.5 rounded border border-ink-800">
                      <span class="text-ink-400">Input: </span>
                      <span class="text-ink-200">{{ activePyramidTierObj.inputSignal }}</span>
                    </div>
                    <div class="bg-ink-950 px-2 py-1.5 rounded border border-ink-800">
                      <span class="text-ink-400">Output: </span>
                      <span class="text-clover-300">{{ activePyramidTierObj.outputSignal }}</span>
                    </div>
                    <div class="bg-ember-500/10 px-2 py-1.5 rounded border border-ember-500/30">
                      <span class="text-ember-300 font-semibold">Without this tier: </span>
                      <span class="text-ink-200">{{ activePyramidTierObj.whatBreaksWithoutIt }}</span>
                    </div>
                  </div>
                </div>

                <!-- Counterfactual Ablation Matrix Toggle -->
                <div class="pt-1.5 border-t border-ink-800 space-y-2">
                  <button
                    type="button"
                    @click="showAblationMatrix = !showAblationMatrix"
                    class="w-full min-h-[42px] sm:min-h-[36px] flex items-center justify-between text-[11px] font-mono text-ink-300 hover:text-honey-300 transition-colors tabular-nums touch-manipulation"
                  >
                    <span>{{ showAblationMatrix ? 'Hide' : 'Show' }} Empirical Factorial &amp; Baseline Matrix (N=20)</span>
                    <span class="text-honey-400 shrink-0 ml-2">
                      Hit {{ activeCounterfactualObj?.pointingHitRatePct ?? 100 }}% · IoU {{ activeCounterfactualObj?.meanHotspotIou ?? 1.0 }}
                    </span>
                  </button>

                  <div v-if="showAblationMatrix" class="space-y-2.5 pt-1">
                    <div class="grid grid-cols-1 gap-1">
                      <button
                        v-for="cf in ablationPyramid.counterfactuals"
                        :key="cf.id"
                        type="button"
                        @click="selectedCounterfactual = cf.id"
                        :class="selectedCounterfactual === cf.id ? 'bg-ink-800 border-honey-500/50 text-ink-100' : 'bg-ink-900 border-ink-800 text-ink-400 hover:text-ink-200'"
                        class="min-h-[42px] sm:min-h-[36px] px-2.5 py-1.5 rounded border text-left text-[10px] font-mono flex items-center justify-between transition-colors tabular-nums touch-manipulation"
                      >
                        <span class="truncate">{{ cf.label }}</span>
                        <span class="text-honey-400 shrink-0 ml-2">
                          Hit {{ cf.pointingHitRatePct ?? (cf.capabilitiesScore * 20) }}% · MAE {{ cf.hotspotCountMae ?? '—' }}
                        </span>
                      </button>
                    </div>

                    <div v-if="activeCounterfactualObj" class="p-2.5 bg-ink-900 rounded border border-ink-800 text-[10px] space-y-1 font-mono tabular-nums">
                      <div class="flex flex-wrap items-center justify-between gap-2 text-[10px] text-ink-400 pb-1 border-b border-ink-800">
                        <span>Code: <strong class="text-honey-300">{{ activeCounterfactualObj.factorialCode || activeCounterfactualObj.id }}</strong></span>
                        <span>Pointing Hit: <strong class="text-clover-300">{{ activeCounterfactualObj.pointingHitRatePct ?? 100 }}%</strong></span>
                        <span>Mean IoU: <strong class="text-electric-300">{{ activeCounterfactualObj.meanHotspotIou ?? 1.0 }}</strong></span>
                        <span>Count MAE: <strong class="text-ink-200">{{ activeCounterfactualObj.hotspotCountMae ?? 0.0 }}</strong></span>
                      </div>
                      <div><span class="text-ink-400">Active:</span> <span class="text-ink-200">{{ activeCounterfactualObj.activeComponents }}</span></div>
                      <div><span class="text-ink-400">Ablated:</span> <span class="text-ember-300">{{ activeCounterfactualObj.missingComponent }}</span></div>
                      <div class="text-ink-300 font-sans pt-0.5 leading-relaxed">{{ activeCounterfactualObj.observedOutcome }}</div>
                    </div>

                    <div class="p-2 bg-white rounded-md border border-ink-700 overflow-x-auto">
                      <img
                        :src="ablationPyramid.figureSvgPath || '/landing/ablation_pyramid_roles.svg'"
                        alt="BeeJEPA 5-Tier Ablation Pyramid Tufte SVG"
                        class="w-full h-auto block"
                      />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Empty State when no event is selected / present -->
          <div v-else class="flex-1 flex flex-col items-center justify-center text-center p-6 text-ink-400 my-auto">
            <div class="w-10 h-10 rounded-md border border-ink-800 bg-ink-950 flex items-center justify-center text-ink-500 mb-2.5">
              <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
            </div>
            <p class="text-xs text-ink-400 max-w-xs">
              Awaiting anomaly events on SSD storage.
            </p>
          </div>
        </section>
      </main>
    </div>

    <!-- ================================================================= -->
    <!-- STAGED META MUSE REQUEST INSPECTOR MODAL (Mobile Bottom Sheet)    -->
    <!-- ================================================================= -->
    <div
      v-if="stagedMuseModalOpen"
      class="fixed inset-0 bg-ink-950/85 z-50 flex items-end sm:items-center justify-center p-0 sm:p-6"
      role="dialog"
      aria-modal="true"
      aria-labelledby="staging-modal-title"
      @click.self="stagedMuseModalOpen = false"
    >
      <div class="bg-ink-900 border-t sm:border border-ink-800 rounded-t-xl sm:rounded-lg w-full max-w-xl max-h-[88svh] flex flex-col overflow-hidden">
        <!-- Modal Header -->
        <div class="px-4 py-3.5 border-b border-ink-800 flex items-center justify-between bg-ink-900">
          <div>
            <h3 id="staging-modal-title" class="text-sm font-semibold text-ink-100">Meta Muse Staging Inspector</h3>
            <p class="text-[11px] text-ink-400">Grounded Video Telemetry &amp; SAM 3.1 Prompt Preview</p>
          </div>
          <button
            type="button"
            @click="stagedMuseModalOpen = false"
            aria-label="Close staging inspector"
            class="min-h-[42px] min-w-[42px] sm:min-h-[36px] sm:min-w-[36px] flex items-center justify-center text-ink-400 hover:text-ink-100 rounded-md hover:bg-ink-800 transition-colors touch-manipulation"
          >
            <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Modal Body -->
        <div class="p-4 space-y-3.5 overflow-y-auto flex-1 text-xs">
          <!-- Compact Status & Event Context -->
          <div class="p-2.5 bg-ink-950 rounded-md border border-ink-800 flex flex-wrap items-center justify-between gap-2 font-mono text-[11px] tabular-nums">
            <div class="flex items-center gap-2">
              <span class="w-2 h-2 rounded-full bg-honey-400"></span>
              <span class="text-ink-200 font-semibold">{{ stagedMuseQuery?.eventId || stagedSamQuery?.eventId || selectedEvent?.eventId || 'Event Telemetry' }}</span>
            </div>
            <div class="flex items-center gap-2.5 text-ink-400">
              <span class="math text-ember-400">{{ stagedMuseQuery?.eventData?.surpriseScoreL2 ? Number(stagedMuseQuery.eventData.surpriseScoreL2).toFixed(2) + '𝜎' : (selectedEvent?.surpriseScoreL2 ? Number(selectedEvent.surpriseScoreL2).toFixed(2) + '𝜎' : '—') }}</span>
              <span class="text-honey-400">Hotspot ({{ stagedMuseQuery?.eventData?.hotspot?.tubeletGridX ?? 2 }}, {{ stagedMuseQuery?.eventData?.hotspot?.tubeletGridY ?? 3 }})</span>
              <span class="text-ink-300 text-[10px] px-1.5 py-0.5 rounded bg-ink-900 border border-ink-800">Dry run</span>
            </div>
          </div>

          <!-- Question Input & Presets -->
          <div class="space-y-1.5">
            <div class="flex items-center justify-between">
              <label for="modal-muse-query" class="text-xs font-medium text-ink-200">Beekeeper query to Meta Muse</label>
              <span class="text-[11px] text-ink-400">Text or voice</span>
            </div>
            <textarea
              id="modal-muse-query"
              v-model="museQuestionInput"
              rows="2"
              class="w-full px-3 py-2 bg-ink-950 border border-ink-800 rounded-md text-ink-100 font-mono text-base sm:text-xs focus:outline-none focus:border-honey-400 resize-none"
              placeholder="Query for Meta Muse..."
            ></textarea>
            <div class="flex flex-wrap items-center gap-1.5">
              <button
                type="button"
                @click="setPresetQuestion('Explain physical collision cause: What occurred at this tubelet?')"
                class="min-h-[38px] sm:min-h-[32px] px-2.5 py-1 rounded bg-ink-950 hover:bg-ink-800 border border-ink-800 text-ink-300 text-[11px] transition-colors flex-1 sm:flex-initial inline-flex items-center justify-center touch-manipulation"
              >
                Collision cause
              </button>
              <button
                type="button"
                @click="setPresetQuestion('Assess wasp/hornet predation threat based on spatial flight velocity')"
                class="min-h-[38px] sm:min-h-[32px] px-2.5 py-1 rounded bg-ink-950 hover:bg-ink-800 border border-ink-800 text-ink-300 text-[11px] transition-colors flex-1 sm:flex-initial inline-flex items-center justify-center touch-manipulation"
              >
                Hornet threat
              </button>
              <button
                type="button"
                @click="setPresetQuestion('Analyze swarm preparation cues from cluster vibration and entrance traffic')"
                class="min-h-[38px] sm:min-h-[32px] px-2.5 py-1 rounded bg-ink-950 hover:bg-ink-800 border border-ink-800 text-ink-300 text-[11px] transition-colors flex-1 sm:flex-initial inline-flex items-center justify-center touch-manipulation"
              >
                Swarm dynamics
              </button>
            </div>
          </div>

          <!-- Staged Inspector Tabs -->
          <div class="border-t border-ink-800 pt-2 space-y-2.5">
            <div class="flex items-center gap-3 border-b border-ink-800">
              <button
                type="button"
                @click="stagedTab = 'prompt'"
                :class="stagedTab === 'prompt' ? 'text-honey-400 border-b-2 border-honey-400 font-medium' : 'text-ink-400 hover:text-ink-200 border-b-2 border-transparent'"
                class="min-h-[40px] px-2 py-2 text-xs transition-colors shrink-0 touch-manipulation"
              >
                Grounded Prompt
              </button>
              <button
                type="button"
                @click="stagedTab = 'payload'"
                :class="stagedTab === 'payload' ? 'text-honey-400 border-b-2 border-honey-400 font-medium' : 'text-ink-400 hover:text-ink-200 border-b-2 border-transparent'"
                class="min-h-[40px] px-2 py-2 text-xs transition-colors shrink-0 touch-manipulation"
              >
                Wire Payload &amp; cURL
              </button>
            </div>

            <!-- Tab 1: Formatted Grounded Prompt -->
            <div v-if="stagedTab === 'prompt'" class="p-3 bg-ink-950 rounded-md border border-ink-800 font-mono text-[11px] text-ink-300 whitespace-pre-wrap leading-relaxed max-h-52 overflow-y-auto">
              {{ stagedMuseQuery?.prompt || stagedSamQuery?.prompt || 'No prompt generated' }}
            </div>

            <!-- Tab 2: Wire Payload & cURL -->
            <div v-if="stagedTab === 'payload'" class="space-y-2">
              <div v-if="stagedSamQuery?.curlCommand || stagedMuseQuery?.curlCommand" class="p-2.5 bg-ink-950 rounded-md border border-ink-800 space-y-1">
                <div class="text-[10px] font-mono text-honey-400 font-semibold">cURL command</div>
                <pre class="text-ink-300 whitespace-pre-wrap font-mono text-[10px] leading-tight select-all max-h-28 overflow-y-auto">{{ stagedSamQuery?.curlCommand || stagedMuseQuery?.curlCommand }}</pre>
              </div>
              <pre class="p-2.5 bg-ink-950 rounded-md border border-ink-800 font-mono text-[10px] text-ink-200 whitespace-pre-wrap max-h-40 overflow-y-auto">{{ JSON.stringify(stagedMuseQuery?.payload || stagedSamQuery?.imagePayload || { model: 'muse-spark-open', question: museQuestionInput }, null, 2) }}</pre>
            </div>
          </div>
        </div>

        <!-- Modal Footer -->
        <div class="px-4 py-3 border-t border-ink-800 bg-ink-900 flex items-center justify-between gap-2 pb-[calc(env(safe-area-inset-bottom)+0.75rem)] sm:pb-3">
          <button
            type="button"
            @click="stagedMuseModalOpen = false"
            class="min-h-[42px] px-4 bg-ink-950 hover:bg-ink-800 text-ink-300 border border-ink-800 rounded-md text-xs transition-colors touch-manipulation"
          >
            Close
          </button>
          <button
            type="button"
            @click="stagedMuseModalOpen = false; askMuse()"
            :disabled="isAskingMuse"
            class="min-h-[42px] px-4 bg-honey-400 hover:bg-honey-300 text-ink-950 font-semibold rounded-md text-xs transition-colors flex items-center gap-1.5 touch-manipulation"
          >
            <span>Run on Muse Spark</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
