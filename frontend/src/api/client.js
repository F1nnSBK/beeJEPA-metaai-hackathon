const activeControllers = new Map()

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function createCombinedSignal(cancelKey, timeoutMs) {
  if (cancelKey && activeControllers.has(cancelKey)) {
    try {
      activeControllers.get(cancelKey).abort(new DOMException('Superseded by newer request', 'AbortError'))
    } catch (e) {}
  }

  const controller = new AbortController()
  if (cancelKey) {
    activeControllers.set(cancelKey, controller)
  }

  let timeoutId = null
  if (timeoutMs > 0) {
    timeoutId = setTimeout(() => {
      try {
        controller.abort(new DOMException(`Request timed out after ${timeoutMs}ms`, 'TimeoutError'))
      } catch (e) {}
    }, timeoutMs)
  }

  const cleanup = () => {
    if (timeoutId) clearTimeout(timeoutId)
    if (cancelKey && activeControllers.get(cancelKey) === controller) {
      activeControllers.delete(cancelKey)
    }
  }

  return { signal: controller.signal, cleanup }
}

/**
 * Resilient HTTP fetch wrapper with per-key request cancellation,
 * timeout protection, exponential backoff retry, and safe JSON parsing.
 */
export async function apiFetch(path, {
  method = 'GET',
  headers = {},
  body = undefined,
  timeoutMs = 15000,
  retries = 2,
  cancelKey = null,
  includeAuth = true
} = {}) {
  const token = includeAuth ? localStorage.getItem('beejepa_token') : null
  const mergedHeaders = { ...headers }
  if (token && !mergedHeaders.Authorization) {
    mergedHeaders.Authorization = `Bearer ${token}`
  }

  let lastError = null
  for (let attempt = 0; attempt <= retries; attempt++) {
    const { signal, cleanup } = createCombinedSignal(cancelKey, timeoutMs)
    try {
      const response = await fetch(path, {
        method,
        headers: mergedHeaders,
        body,
        signal
      })

      if (response.status === 429 || response.status === 502 || response.status === 503 || response.status === 504) {
        if (attempt < retries) {
          cleanup()
          const backoffMs = Math.min(2500, 300 * Math.pow(2, attempt) + Math.floor(Math.random() * 150))
          await sleep(backoffMs)
          continue
        }
      }

      const contentType = response.headers.get('content-type') || ''
      let data = null
      if (contentType.includes('application/json')) {
        const rawText = await response.text()
        if (rawText && rawText.trim().length > 0) {
          try {
            data = JSON.parse(rawText)
          } catch (parseErr) {
            console.warn(`Malformed JSON from ${path}:`, parseErr)
            data = null
          }
        }
      } else {
        const rawText = await response.text()
        if (rawText && rawText.trim().startsWith('{') || rawText.trim().startsWith('[')) {
          try {
            data = JSON.parse(rawText)
          } catch (e) {
            data = rawText
          }
        } else {
          data = rawText
        }
      }

      cleanup()
      return {
        ok: response.ok,
        status: response.status,
        data
      }
    } catch (err) {
      cleanup()
      lastError = err
      if (err?.name === 'AbortError') {
        return { ok: false, status: 0, aborted: true, data: null, error: err }
      }
      if (attempt < retries) {
        const backoffMs = Math.min(2500, 300 * Math.pow(2, attempt) + Math.floor(Math.random() * 150))
        await sleep(backoffMs)
      }
    }
  }

  return {
    ok: false,
    status: 0,
    aborted: false,
    data: null,
    error: lastError
  }
}

function toFiniteNumber(val, fallback) {
  const n = Number(val)
  return Number.isFinite(n) ? n : fallback
}

export function normalizeHotspot(raw) {
  if (!raw || typeof raw !== 'object') {
    return {
      tubeletGridX: 2,
      tubeletGridY: 3,
      normCenterX: 0.456,
      normCenterY: 0.436,
      boundingBoxPx: null
    }
  }
  const rawBox = raw.boundingBoxPx ?? raw.boxPx ?? raw.box_px ?? null
  let boundingBoxPx = null
  if (Array.isArray(rawBox) && rawBox.length === 4) {
    const nums = rawBox.map(v => toFiniteNumber(v, NaN))
    if (nums.every(Number.isFinite)) {
      boundingBoxPx = nums
    }
  }
  return {
    tubeletGridX: Math.round(toFiniteNumber(raw.tubeletGridX ?? raw.tubelet_grid_x ?? raw.gridX, 2)),
    tubeletGridY: Math.round(toFiniteNumber(raw.tubeletGridY ?? raw.tubelet_grid_y ?? raw.gridY, 3)),
    normCenterX: Math.max(0, Math.min(1, toFiniteNumber(raw.normCenterX ?? raw.norm_center_x ?? raw.centerX, 0.456))),
    normCenterY: Math.max(0, Math.min(1, toFiniteNumber(raw.normCenterY ?? raw.norm_center_y ?? raw.centerY, 0.436))),
    boundingBoxPx
  }
}

export function normalizeEvent(raw) {
  if (!raw || typeof raw !== 'object') return null
  const eventId = String(raw.eventId ?? raw.event_id ?? raw.id ?? '').trim()
  if (!eventId) return null

  return {
    ...raw,
    eventId,
    timestampSec: toFiniteNumber(raw.timestampSec ?? raw.timestamp_sec ?? raw.timeSec, 0),
    frameIndex: Math.round(toFiniteNumber(raw.frameIndex ?? raw.frame_index ?? raw.frame, 0)),
    surpriseScoreL2: toFiniteNumber(raw.surpriseScoreL2 ?? raw.surprise_score_l2 ?? raw.surpriseScoreSigma ?? raw.score, 0),
    pixelDiffBaseline: toFiniteNumber(raw.pixelDiffBaseline ?? raw.pixel_diff_baseline ?? 0, 0),
    severity: String(raw.severity ?? 'HIGH').toUpperCase(),
    hotspot: normalizeHotspot(raw.hotspot),
    agentResult: raw.agentResult ?? raw.agent_result ?? null,
    isCached: Boolean(raw.isCached ?? raw.is_cached ?? (raw.agentResult != null))
  }
}

export function normalizeTimeline(raw) {
  if (!raw || typeof raw !== 'object') {
    return {
      recordingId: 'rec2125',
      totalDurationSec: 300,
      minSurprise: 9.5,
      maxSurprise: 16.5,
      decisionThreshold: 10.2,
      sequences: [],
      timeline: []
    }
  }
  const rawPoints = Array.isArray(raw.timeline) ? raw.timeline : (Array.isArray(raw.points) ? raw.points : [])
  const timeline = rawPoints
    .filter(pt => pt && typeof pt === 'object')
    .map(pt => ({
      ...pt,
      timestampSec: toFiniteNumber(pt.timestampSec ?? pt.timestamp_sec ?? pt.t, 0),
      surpriseScoreL2: toFiniteNumber(pt.surpriseScoreL2 ?? pt.surprise_score_l2 ?? pt.score, 9.5),
      eventId: pt.eventId ?? pt.event_id ?? null
    }))

  return {
    ...raw,
    recordingId: String(raw.recordingId ?? raw.recording_id ?? 'rec2125'),
    totalDurationSec: Math.max(1, toFiniteNumber(raw.totalDurationSec ?? raw.total_duration_sec, 300)),
    minSurprise: toFiniteNumber(raw.minSurprise ?? raw.min_surprise, 9.5),
    maxSurprise: toFiniteNumber(raw.maxSurprise ?? raw.max_surprise, 16.5),
    decisionThreshold: toFiniteNumber(raw.decisionThreshold ?? raw.decision_threshold ?? raw.thresholdSigma, 10.2),
    sequences: Array.isArray(raw.sequences) ? raw.sequences : [],
    timeline
  }
}

export function normalizeSaccade(raw) {
  if (!raw || typeof raw !== 'object') return null
  const eventId = String(raw.eventId ?? raw.event_id ?? '').trim()
  if (!eventId) return null
  return {
    ...raw,
    eventId,
    focusCenterX: Math.round(toFiniteNumber(raw.focusCenterX ?? raw.focus_center_x, 246)),
    focusCenterY: Math.round(toFiniteNumber(raw.focusCenterY ?? raw.focus_center_y, 419)),
    saccadicJumpDistancePx: Math.round(toFiniteNumber(raw.saccadicJumpDistancePx ?? raw.saccadic_jump_distance_px, 0)),
    hotspotBeesCount: Math.round(toFiniteNumber(raw.hotspotBeesCount ?? raw.hotspot_bees_count ?? raw.beesInsideHotspotCount, 0)),
    classification: String(raw.classification ?? raw.geometricPreClassification ?? 'NOMINAL')
  }
}

export function normalizeMuseDiagnosis(raw) {
  if (!raw || typeof raw !== 'object') return null
  const report = String(raw.report ?? raw.diagnosis ?? raw.reportMarkdown ?? '').trim()
  const events = Array.isArray(raw.events)
    ? raw.events.filter(e => e && typeof e === 'object').map(e => ({
        ...e,
        eventId: String(e.eventId ?? e.event_id ?? ''),
        timestamp: String(e.timestamp ?? '00:00'),
        surpriseScoreSigma: toFiniteNumber(e.surpriseScoreSigma ?? e.surprise_score_sigma ?? e.surpriseScoreL2, 0),
        landingBoardBeeCount: Math.round(toFiniteNumber(e.landingBoardBeeCount ?? e.landing_board_bee_count, 0)),
        beesInsideHotspotCount: Math.round(toFiniteNumber(
          e.beesInsideHotspotCount ?? e.bees_inside_hotspot_count ?? e.landingBoardBeeCount,
          0
        )),
        cropBase64: typeof e.cropBase64 === 'string' ? e.cropBase64 : null
      }))
    : []

  const offlineLocked = Boolean(raw.offlineLocked ?? raw.offline_locked ?? false)
  const degradedFallback = Boolean(raw.degradedFallback ?? raw.degraded_fallback ?? false)
  const sourceMode = String(
    raw.sourceMode ?? raw.source_mode ?? (degradedFallback ? 'DETERMINISTIC_FALLBACK' : (offlineLocked ? 'DISK_CACHE' : 'LIVE_API'))
  )

  return {
    ...raw,
    report,
    diagnosis: String(raw.diagnosis ?? report),
    spokenSummary: String(raw.spokenSummary ?? raw.spoken_summary ?? ''),
    offlineLocked,
    degradedFallback,
    sourceMode,
    events,
    telemetry: {
      latencyMs: Math.round(toFiniteNumber(raw.telemetry?.latencyMs ?? raw.latencyMs, 0)),
      ...(raw.telemetry || {})
    }
  }
}

export function normalizeStorageStatus(raw) {
  if (!raw || typeof raw !== 'object') {
    return { isMounted: false, freeSpaceFormatted: 'Not Connected', isWritable: false }
  }
  return {
    ...raw,
    isMounted: Boolean(raw.isMounted ?? raw.mounted ?? false),
    freeSpaceFormatted: String(raw.freeSpaceFormatted ?? raw.free_space_formatted ?? 'Not Connected'),
    isWritable: Boolean(raw.isWritable ?? raw.writable ?? false)
  }
}

export function normalizeMetaHealth(raw) {
  if (!raw || typeof raw !== 'object') {
    return {
      configured: false,
      liveConnection: false,
      status: 'OFFLINE',
      statusCode: 0,
      latencyMs: 0,
      baseUrl: '',
      availableModels: [],
      message: 'Endpoint unreachable'
    }
  }
  return {
    ...raw,
    configured: Boolean(raw.configured ?? false),
    liveConnection: Boolean(raw.liveConnection ?? raw.live_connection ?? false),
    status: String(raw.status ?? 'UNKNOWN'),
    statusCode: Math.round(toFiniteNumber(raw.statusCode ?? raw.status_code, 200)),
    latencyMs: Math.round(toFiniteNumber(raw.latencyMs ?? raw.latency_ms, 0)),
    baseUrl: String(raw.baseUrl ?? raw.base_url ?? ''),
    availableModels: Array.isArray(raw.availableModels) ? raw.availableModels : [],
    message: String(raw.message ?? '')
  }
}

export function normalizeRingBuffer(raw) {
  if (!raw || typeof raw !== 'object') {
    return {
      capacity: 600,
      currentSequence: 0,
      activeSlotsCount: 0,
      totalAllocatedBytes: 0,
      memoryPerSlotBytes: 0,
      isOffHeap: true,
      droppedFrames: 0
    }
  }
  return {
    ...raw,
    capacity: Math.round(toFiniteNumber(raw.capacity, 600)),
    currentSequence: Math.round(toFiniteNumber(raw.currentSequence, 0)),
    activeSlotsCount: Math.round(toFiniteNumber(raw.activeSlotsCount, 0)),
    totalAllocatedBytes: Math.round(toFiniteNumber(raw.totalAllocatedBytes, 0)),
    memoryPerSlotBytes: Math.round(toFiniteNumber(raw.memoryPerSlotBytes, 0)),
    isOffHeap: Boolean(raw.isOffHeap ?? true),
    droppedFrames: Math.round(toFiniteNumber(raw.droppedFrames, 0))
  }
}

