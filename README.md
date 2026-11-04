# beeJEPA

Hive entrance monitoring system combining **V-JEPA 2.1** (`ViT-B/16`) prediction-error tracking, **Meta SAM 3.1** instance segmentation, **Meta Muse Spark Open** multimodal reasoning, and **Meta Muse Voice Transcribe 1.0** speech recognition on **Java 25** and **GraalVM Native Image**.

- **Web Console:** [https://beejepa.finn-hertsch.de](https://beejepa.finn-hertsch.de)
- **Dataset:** [F1nnSBK/bee-jepa-entrance-anomalies](https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies)

[![Live Demo](https://img.shields.io/badge/Demo-beejepa.finn--hertsch.de-D97706.svg)](https://beejepa.finn-hertsch.de)
[![Hugging Face Dataset](https://img.shields.io/badge/Hugging%20Face-bee--jepa--entrance--anomalies-F59E0B.svg)](https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies)
[![Runtime](https://img.shields.io/badge/Runtime-Java%2025%20%7C%20GraalVM%20Native-EA580C.svg)](https://www.graalvm.org/)
[![Hardware](https://img.shields.io/badge/Target-Raspberry%20Pi%204%20%7C%20ARM64-C51A4A.svg)](https://www.raspberrypi.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-3B82F6.svg)](https://opensource.org/licenses/MIT)

---

## Overview & Hackathon Scope

![Figure 1: Pipeline overview](figures/figure1_head_concept.svg)

Continuous 60 fps footage from a beehive entrance produces far too many frames for manual inspection or continuous cloud VLM polling. To keep the evaluation boundary transparent, the architecture separates the pre-existing university coursework dataset from the system built during the **Meta Global AI Developer Hackathon 2026**:

- **Pre-Existing University Coursework Foundation (System 1):**
  Self-supervised feature extraction over 60 fps `1080x960` side-by-side hive entrance recordings (`rec2123` and `rec2125`) using a frozen **V-JEPA 2.1** (`ViT-B/16`, `vjepa2_1_vit_base_384`) encoder on a `43x43` spatial token grid (`16` frames per window, stride `2`). Across `96` 30-second sequences (`2,688` sliding windows), it records per-window `token_delta_score` trajectories, `board_peaks` (`board_peaks_9x9` and `board_peaks_16x16`), and a data-derived 99th-percentile threshold `τ (P99)`, isolating `184` peak anomaly windows exported to Hugging Face.

- **Built During the Meta AI Hackathon (System 2):**
  - **Hotspot Extraction & Foveal Cropping (`HeatmapHotspotExtractor`, `VideoFrameExtractor`, `VisualCropRenderer`):** Extracts keyframes at the exact `peak_frame / 59.94` timestamp via `ffmpeg`, isolates the `43x43` V-JEPA overlay (`|right_panel - left_panel|`), locates the highest-energy `5x3` token window in the landing-board band (`rows 15..22`) above the ambient noise floor (`rows 0..13`), and renders a `688x688` flight-board overview alongside a `384x384` bicubic hotspot crop.
  - **Off-Heap Frame Ring Buffer & SIMD Vector Evaluation (`OffHeapFrameRingBuffer`, `SimdLatentEvaluator`, `ActivePerceptionService`):** Stores up to `64` keyframes (`393,216` bytes max payload per slot) outside the JVM heap via the Java 25 Foreign Function & Memory (FFM) API (`Arena.ofShared()`, `MemorySegment`) and evaluates 64-float measured board-energy vectors (`latentStore`) using `jdk.incubator.vector.FloatVector`.
  - **Meta SAM 3.1 Segmentation & Geometric Containment (`Sam31Client`, `SamGeometry`):** Segments individual bees on the landing board (`x <= 535`, `372 <= y <= 518`) via **Meta SAM 3.1** (`POST /v1/responses`) and intersects detected bounding boxes with the measured V-JEPA hotspot using Intersection-over-Area (`IoA >= 0.25`), classifying events into `multi_bee_cluster_in_hotspot`, `single_bee_in_hotspot`, or `no_bee_in_hotspot` (or `sam_unavailable` / `no_measured_hotspot` when unsegmented).
  - **Multimodal Reasoning & Tool Calling (`MetaMuseService`, `TolerantPayloadParser`):** Sends multi-event digests, spatial containment metrics, and visual crops to **Meta Muse Spark Open** (`meta-models/muse-spark-open-1026`) on Baseten. The model can invoke `QuerySam31Concept` on clean keyframes during tool-call rounds and returns structured `<REPORT>` (Markdown) and `<SPOKEN>` (audio briefing) outputs.
  - **Voice Transcription (`MetaVoiceTranscribeClient`):** Transcribes 16 kHz mono 16-bit PCM WAV microphone audio via Meta Model API `muse-voice-transcribe-1.0` (`POST /api/v1/muse/transcribe`).
  - **Deterministic Multi-Tier Caching & Fail-Closed Resilience (`MuseDiskCache`, `RedisCacheClient`, `StateRepository`, `ResilientApiExecutor`):** Indexes responses by the exact deterministic key `(eventId, normalizedPrompt, modelVersion)` across L0 in-memory map, L1 Redis RESP2, and L2 SQLite WAL (`state.db`). Upstream API failures fail closed (`HTTP 502` / `503`) without returning cross-event cache hits or synthetic fallback claims.
  - **3D Scroll Intro, Inspection Console & Acoustic Mirror (`LandingPage.vue`, `HiveScene.js`, `App.vue`, `BeeAcousticSynthesizer`, `AudioSpectralAnalyzer`):** Three.js WebGL scroll story leading into a Vue 3 inspection console with synchronized video/heatmap playback, cohort-wide or event-scoped Muse queries, `edge-tts` speech synthesis, 22.05 kHz bee acoustic synthesis, and a 1024-point Radix-2 Cooley-Tukey FFT acoustic mirror.

```
┌──────────────────────────────────────────────────────────────┐
│  PRE-EXISTING UNIVERSITY COURSEWORK (System 1)               │
│  - 60 fps 1080x960 side-by-side hive entrance recordings     │
│  - Frozen V-JEPA 2.1 ViT-B/16 encoder (43x43 token grid)     │
│  - Token-delta trajectory, board peaks & P99 threshold τ     │
│  - Exported event metadata & sequence clips on Hugging Face  │
└──────────────────────────────┬───────────────────────────────┘
                               │  [HACKATHON CUT-OFF LINE]
═══════════════════════════════╪════════════════════════════════
┌──────────────────────────────▼───────────────────────────────┐
│  BUILT DURING META AI HACKATHON (System 2)                   │
│  1. Pixel-Measured 43x43 Hotspot Extractor & Foveal Cropper  │
│  2. Java 25 FFM 64-Slot Off-Heap Ring Buffer & Vector API    │
│  3. Meta SAM 3.1 Instance Segmentation & IoA >= 0.25 Filter  │
│  4. Meta Muse Spark Open Multimodal Reasoning & Tool Calling │
│  5. Meta Muse Voice Transcribe 1.0 (16 kHz Mono WAV ASR)     │
│  6. Deterministic L0/L1/L2 Cache & Fail-Closed API Executor  │
│  7. Three.js 3D Landing Page, Vue 3 Console & FFT Audio      │
└──────────────────────────────────────────────────────────────┘
```

### Coursework vs. Hackathon Scope Matrix

| Area | Pre-Existing University Coursework (System 1) | Built During Meta AI Hackathon (System 2) |
| :--- | :--- | :--- |
| **Vision Models** | Frozen V-JEPA 2.1 `ViT-B/16` (`vjepa2_1_vit_base_384`) | **Meta SAM 3.1** (`sam-3.1`) instance segmentation via `/v1/responses` on the landing-board ROI |
| **Spatial Grounding** | Side-by-side rendered `43x43` token-delta heatmap video | **`HeatmapHotspotExtractor` & `SamGeometry`** measuring the `5x3` token hotspot from keyframes and intersecting SAM 3.1 boxes via `IoA >= 0.25` |
| **Multimodal Reasoning & ASR** | None — numerical `token_delta_score` and `board_peaks` only | **Meta Muse Spark Open** (`meta-models/muse-spark-open-1026`) on Baseten + **Meta Muse Voice Transcribe 1.0** (`muse-voice-transcribe-1.0`) |
| **Backend Architecture** | Offline Python evaluation scripts | **Java 25 & Micronaut 4.7 GraalVM Native Server** with Virtual Threads, FFM off-heap ring buffer, Vector API, Redis RESP2, SQLite WAL, and PBKDF2 auth |
| **Frontend & Audio** | Static plots and exported MP4 clips | **Three.js 3D Scroll Story + Vue 3 Console** with interactive timeline, global/event Muse queries, `edge-tts`, `BeeAcousticSynthesizer`, and 1024-pt FFT |

---

## Pipeline Stages

### 1. Camera Stream and V-JEPA 2.1 Token-Delta Heatmap
![Side-by-side V-JEPA detection](figures/side_by_side_vjepa_detection.gif)

- **Left Panel (`x = 0..539`):** Raw 60 fps flight-board camera recording.
- **Right Panel (`x = 540..1079`):** V-JEPA 2.1 (`ViT-B/16`) prediction-error heatmap highlighting sudden motion changes across the `43x43` spatial grid.

### 2. SAM 3.1 Segmentation and Hotspot Containment (`IoA >= 0.25`)
![Spatial isolation](figures/channel1_and_2_spatial_isolation.svg)

- **Overview Panel (`688x688`):** `Sam31Client` segments individual bees on the landing board (`x <= 535`, `372 <= y <= 518`), while `HeatmapHotspotExtractor` bounds the `5x3` token window with the highest prediction error.
- **Hotspot Crop (`384x384`):** `SamGeometry` computes the Intersection-over-Area (`IoA = Area(B_bee ∩ B_hotspot) / Area(B_bee)`) for each detected bee, isolating bees with `IoA >= 0.25` inside the hotspot crop sent to Muse Spark Open.

### 3. Five-Tier Perception & Reasoning Breakdown

| Tier | Component | Role in the Pipeline | Measured Output |
| :--- | :--- | :--- | :--- |
| **Tier 4** | `MetaMuseService` | Synthesizes multi-event crops, SAM 3.1 geometry, and `QuerySam31Concept` tool results | Structured `<REPORT>` (Markdown) and `<SPOKEN>` (TTS briefing) |
| **Tier 3** | `SamGeometry` | Intersects measured V-JEPA hotspots with SAM 3.1 bee boxes using `IoA >= 0.25` | `beesInHotspot`, `meanIoA`, `peakIoA`, and `hotspotClass` |
| **Tier 2** | `Sam31Client` | Segments individual bees within the landing-board region of interest | Bounding boxes, confidence scores, and `landingBoardBeesCount` |
| **Tier 1** | `HeatmapHotspotExtractor` & `ActivePerceptionService` | Extracts the `43x43` board-energy hotspot and 64-float latent vector from keyframes | Measured `HotspotBox` (`x1, y1, x2, y2`), `gainRatio`, and cosine similarity |
| **Tier 0** | `CatalogLoader`, `CatalogStatistics` & `VideoFrameExtractor` | Loads sequence/event catalogs, computes linear-interpolated P99 `τ`, and extracts keyframes via `ffmpeg` | `2,688` timeline windows, `184` peak events, and HTTP Range media streams |

---

## Backend Architecture

1. **Catalog, Statistics, Frame Extraction & Hotspot Measurement — `com.beejepa.event`**
   - `CatalogLoader` reads `metadata/timeline_points.csv`, `metadata/anomaly_events.csv`, and `manifest.json` without mixing statistics or image processing into I/O.
   - `CatalogStatistics` computes linear-interpolated percentiles (`percentile(double[], 0.99)`) and rounding.
   - `VideoFrameExtractor` invokes `ffmpeg` to decode exact keyframes (`peak_frame / 59.94` s) from local `.mp4` sequences.
   - `HeatmapHotspotExtractor` measures the `43x43` V-JEPA overlay difference (`|right - left|`), locates the peak `5x3` landing-board window above the noise floor, and extracts 64-float normalized board-energy vectors.
   - `EventService` coordinates catalog state, hotspot persistence in SQLite, frame caching, and DTO mapping.

2. **Off-Heap Ring Buffer & SIMD Vector Evaluation — `com.beejepa.perception`**
   - `OffHeapFrameRingBuffer` allocates `64` off-heap slots (`393,216` bytes max payload + 32-byte header per slot) via Java 25 `Arena.ofShared()`.
   - `SimdLatentEvaluator` computes Euclidean distance, L2 norm, and cosine similarity over `float[]` vectors using `FloatVector.SPECIES_PREFERRED`.
   - `ActivePerceptionService` populates the ring buffer and `latentStore` from real keyframes, builds the saccadic trajectory, and computes empirical cohort statistics across the catalog and cached SAM 3.1 evaluations.

3. **Segmentation, Multimodal Reasoning, Voice ASR & Acoustic Mirror — `com.beejepa.muse`**
   - `Sam31Client` and `SamGeometry` query `sam-3.1` via `/v1/responses`, filter detections to the flight board, and compute `IoA >= 0.25` hotspot containment.
   - `VisualCropRenderer` renders the `688x688` annotated overview and `384x384` bicubic hotspot crop.
   - `MetaMuseService` and `TolerantPayloadParser` run multimodal inference against `meta-models/muse-spark-open-1026`, execute `QuerySam31Concept` tool calls on clean keyframes, and parse `<REPORT>` and `<SPOKEN>` blocks.
   - `MetaVoiceTranscribeClient` calls `https://api.meta.ai/v1/asr/transcribe` with `muse-voice-transcribe-1.0` using multipart `request` + `audio` (`WAV`) payloads.
   - `ResilientApiExecutor`, `MuseDiskCache`, and `RedisCacheClient` enforce circuit breaking, exponential backoff, API key failover, and deterministic `(eventId, normalizedPrompt, modelVersion)` caching across L0 Map, L1 Redis, and L2 SQLite WAL.
   - `BeeAcousticSynthesizer` and `AudioSpectralAnalyzer` synthesize 22.05 kHz WAV signals (210–460 Hz carriers) and analyze microphone audio with a 1024-point Radix-2 Cooley-Tukey FFT.

4. **Persistence, Authentication & Telemetry — `com.beejepa.state`, `com.beejepa.auth`, `com.beejepa.telemetry`**
   - Runs on **Micronaut 4.7** and **Netty** with **SQLite WAL** (`state.db`) for durable caching, request latency tracking, and session authentication (`PBKDF2WithHmacSHA256`).

---

## Dataset and Local Media Delivery

The dataset is published on Hugging Face at [**F1nnSBK/bee-jepa-entrance-anomalies**](https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies).

- **Recordings:** `rec2123` (`64` sequences) and `rec2125` (`32` sequences) from `mrkschtr/thehiveisreal`, recorded at `1080x960` and `59.94` fps with side-by-side camera and V-JEPA 2.1 (`ViT-B/16`) heatmap views (`96` 30-second sequences, `2,688` sliding windows, `184` peak events).
- **Privacy-Preserving Local Delivery:** All `.mp4` sequence videos and `.jpg` keyframes are served directly from local SSD storage (`BEEJEPA_DATA_PATH` / `BEEJEPA_SAMPLE_PATH`) with HTTP Range support (`206 Partial Content`). When a keyframe is not yet cached, `EventService` extracts it on demand from the local `.mp4` sequence via `ffmpeg`. Missing media files return `404 Not Found` directly—the server never redirects browser clients (`HTTP 307`) to external CDNs.

---

## Quickstart

### Prerequisites
- **JDK 25** (configured with `--enable-preview` and `--add-modules jdk.incubator.vector` in `build.gradle`)
- **Node.js 18+** and `npm`
- Optional: **Redis** on `127.0.0.1:6379` and **ffmpeg** for on-demand keyframe extraction

### 1. Environment Configuration
```bash
cp .env.example .env
```
Configure `.env`:
```env
BASETEN_API_KEY=your_baseten_api_key_here
BASETEN_BASE_URL=https://inference.baseten.co/v1
BASETEN_MUSE_MODEL=meta-models/muse-spark-open-1026
BASETEN_ALLOW_INFERENCE=true

META_MUSE_API_KEY=your_meta_muse_api_key_here
META_MUSE_BASE_URL=https://api.meta.ai

REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_ENABLED=true
CACHE_TTL_SECONDS=604800

PORT=8080
BEEJEPA_DATA_PATH=./data
BEEJEPA_RING_BUFFER_CAPACITY=64
BEEJEPA_RING_BUFFER_MAX_PAYLOAD_BYTES=393216
```

### 2. Build Frontend and Start Server
```bash
./build_ui.sh
./gradlew run
```
Open `http://localhost:8080`. For hot-reload frontend development, run `cd frontend && npm run dev` (proxies `/api` to port `8080`).

### 3. Run Test Suite
```bash
./gradlew test
```

### 4. Compile GraalVM Native Binary
```bash
./gradlew nativeCompile
./build/native/nativeCompile/beejepa
```

---

## REST API

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/auth/login` | Authenticate user and issue a 12-hour `bee_` bearer token |
| `GET` | `/api/v1/auth/me` | Validate bearer token and return active user profile |
| `POST` | `/api/v1/auth/logout` | Revoke active bearer token |
| `GET` | `/api/v1/timeline` | Return `token_delta_score` trajectory, P99 threshold `τ`, and sequence metadata |
| `GET` | `/api/v1/events` | List peak anomaly events with measured hotspots and cached SAM 3.1 / Muse data |
| `GET` | `/api/v1/events/{id}` | Return full event detail, hotspot coordinates, and media availability |
| `GET` | `/api/v1/events/{id}/media/{file}` | Serve event keyframes or `.mp4` clips from local storage (`404` if absent) |
| `GET` | `/api/v1/sequences/{filename}` | Stream 30-second `.mp4` sequence videos with HTTP Range (`206 Partial Content`) |
| `POST` | `/api/v1/events/{id}/analyze` | Run or refresh SAM 3.1 and Muse Spark Open analysis for a specific event |
| `GET` | `/api/v1/muse/status` | Report upstream API configuration, active model, and circuit breaker status |
| `POST` | `/api/v1/muse/ask` | Run single-event or global cohort visual analysis (`<REPORT>` + `<SPOKEN>`) |
| `POST` | `/api/v1/muse/transcribe` | Transcribe 16 kHz mono WAV audio via Meta `muse-voice-transcribe-1.0` |
| `GET/POST` | `/api/v1/muse/events/{id}/sam` | Retrieve or run SAM 3.1 segmentation and `IoA >= 0.25` hotspot containment |
| `GET` | `/api/v1/muse/tts?text=...` | Synthesize spoken English briefing audio via `edge-tts` |
| `GET` | `/api/v1/muse/voice?text=...` | Stream 22.05 kHz WAV bee acoustic signal from `BeeAcousticSynthesizer` |
| `POST` | `/api/v1/muse/acoustic-mirror` | Analyze microphone WAV via 1024-point FFT and synthesize acoustic response |
| `GET` | `/api/v1/perception/ring-buffer` | Read 64-slot off-heap FFM ring buffer telemetry |
| `GET` | `/api/v1/perception/saccades` | List saccadic targets and measured 64-float vector cosine similarities |
| `GET` | `/api/v1/perception/ablation-pyramid` | Return empirical cohort statistics and 5-tier pipeline breakdown |
| `POST` | `/api/v1/perception/simd-eval` | Compute Euclidean distance, L2 norm, and cosine similarity via Vector API |
| `GET` | `/api/v1/telemetry/stats` | Read request latency percentiles and `/prometheus` metrics |
| `GET` | `/api/v1/overview` | Read system health, local storage status, and virtual thread telemetry |

---

## License

MIT License.
