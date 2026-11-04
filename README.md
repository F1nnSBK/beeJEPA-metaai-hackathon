# beeJEPA

Autonomous hive entrance monitoring and active perception system combining self-supervised **V-JEPA 2.1** latent surprise detection, **Meta SAM 3.1** spatial segmentation, and **Meta Muse Spark Open** multimodal reasoning on **Java 25** and **GraalVM Native Image**.

- **Web Console & Live Demo:** [https://beejepa.finn-hertsch.de](https://beejepa.finn-hertsch.de)
- **Dataset (Hugging Face):** [F1nnSBK/bee-jepa-entrance-anomalies](https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies)

[![Live Demo](https://img.shields.io/badge/Demo-beejepa.finn--hertsch.de-D97706.svg)](https://beejepa.finn-hertsch.de)
[![Hugging Face Dataset](https://img.shields.io/badge/Hugging%20Face-bee--jepa--entrance--anomalies-F59E0B.svg)](https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies)
[![Runtime](https://img.shields.io/badge/Runtime-Java%2025%20%7C%20GraalVM%20Native-EA580C.svg)](https://www.graalvm.org/)
[![Hardware](https://img.shields.io/badge/Target-Raspberry%20Pi%204%20%7C%20ARM64-C51A4A.svg)](https://www.raspberrypi.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-3B82F6.svg)](https://opensource.org/licenses/MIT)

---

## Overview & System Design

![Figure 1: Biological-to-Computational Paradigm in beeJEPA](figures/figure1_head_concept.svg)

*Figure 1: Biological-to-computational active perception loop in beeJEPA — mapping peripheral optic flow, saccadic fixation, foveal inspection, and colony signaling (top) to V-JEPA 2.1 peripheral prediction, latent surprise gating (`S(t) >= τ`), SAM 3.1 + `IoA >= 0.25` spatial grounding, and Muse Spark Open dual `<REPORT>` + `<SPOKEN>` synthesis (bottom).*

Continuous 60 fps video monitoring of a honeybee (*Apis mellifera*) flight board produces a high-volume stream where manual review or continuous cloud VLM polling is impractical. **beeJEPA** couples a self-supervised sensory pipeline (**System 1**) with an event-driven active perception, spatial grounding, and multimodal reasoning backend (**System 2**):

- **System 1 — Peripheral Sensory Stream (V-JEPA 2.1):**
  Extracts spatiotemporal representations from 60 fps `1080x960` hive entrance footage using **V-JEPA 2.1** (`ViT-H/16`) over an overlapping `9x9` Mean-Bee patch grid (`43x43` spatial tokens). Computes the continuous latent prediction error `S(t) = ||z_pred - z_target||_2` and gates downstream execution to peak anomaly windows (`S(t) >= τ`).

- **System 2 — Active Perception, Spatial Grounding & Reasoning:**
  - **Saccadic Foveal Cropping:** When `S(t)` crosses the decision threshold `τ`, the system localizes the spatial attention hotspot on the flight board and renders both a canonical `688x688` camera overview and a bicubic-upsampled `384x384` foveal crop (`VisualCropRenderer`).
  - **Java 25 FFM Off-Heap Ring Buffer & SIMD Vector API:** Buffers raw JPEG keyframes off-heap via `java.lang.foreign.Arena` / `MemorySegment` (`OffHeapFrameRingBuffer`) without garbage-collection pressure and evaluates compact frame signatures via `jdk.incubator.vector.FloatVector` (`SimdLatentEvaluator`).
  - **Meta SAM 3.1 Spatial Grounding & Containment (`IoA`):** Queries **Meta SAM 3.1** (`sam-3.1`) for promptable concept segmentation on the landing board ROI (`Sam31Client`) and intersects detected instances with the V-JEPA attention hotspot (`SamGeometry`, `IoA_bee >= 0.25` and `< 22%` hotspot coverage void guard) to separate normal honeybee clustering from potential foreign intruders.
  - **Meta Muse Spark Open Dual-Output Pipeline:** Synthesizes multi-event visual digests via `meta-models/muse-spark-open-1026` on Baseten (`MetaMuseService`) with autonomous tool-calling (`QuerySam31Concept`, `QueryFovealHotspot`), returning both a structured Markdown brief (`<REPORT>`) and a natural-language voice summary (`<SPOKEN>`) stripped of Markdown and raw timestamps (`TolerantPayloadParser`).
  - **Resilience & Multi-Tier Caching:** Protects upstream inference calls with a circuit breaker, exponential backoff with jitter, and automatic primary/secondary API key failover (`ResilientApiExecutor`), backed by a 3-tier cache (**L0 In-Memory -> L1 Redis RESP2 -> L2 SQLite WAL** in `state.db`).
  - **Interactive Three.js & Vue 3 Web Console:** Combines a 6-stage Three.js scroll introduction (`LandingPage.vue`) with a two-column operational console (`App.vue`) featuring synchronized video playback, saccadic HUD overlays, neural English TTS (`edge-tts`), and deterministic bioacoustic honeybee synthesis (`BeeAcousticSynthesizer`).

```
┌──────────────────────────────────────────────────────────────┐
│  SYSTEM 1: SENSORY FOUNDATION                                │
│  60 fps 1080x960 Hive Stream -> V-JEPA 2.1 (ViT-H/16)        │
│  Latent Surprise Trajectory S(t) = ||z_pred - z_target||_2   │
└──────────────────────────────┬───────────────────────────────┘
                               │  S(t) >= τ Saccadic Trigger
┌──────────────────────────────▼───────────────────────────────┐
│  SYSTEM 2: ACTIVE PERCEPTION & COGNITION                     │
│  1. Java 25 FFM Off-Heap Ring Buffer & SIMD Vector Evaluator │
│  2. Meta SAM 3.1 Concept Segmentation & Landing Board ROI    │
│  3. Scale-Invariant Containment (IoA_bee >= 0.25) & Guard    │
│  4. Meta Muse Spark Open Dual Output (<REPORT> + <SPOKEN>)   │
│  5. Resilient API Executor + L0/Redis L1/SQLite WAL L2 Cache │
│  6. Three.js 3D Scroll Story & Vue 3 Operational Console     │
└──────────────────────────────────────────────────────────────┘
```

---

## Pipeline Visuals & Functional Stages

### Side-by-Side Latent Representation (Camera Stream vs. V-JEPA Attention)
![Live Side-by-Side V-JEPA Anomaly Detection](figures/side_by_side_vjepa_detection.gif)

- **Left (Camera View):** Raw 60 fps flight board recording.
- **Right (V-JEPA 2.1 Latent Space):** Spatial surprise heatmap highlighting unpredictability in motion dynamics at the hive entrance.

### 1. Spatial Grounding & Hotspot Containment (SAM 3.1 + IoA)
![Single Frame Spatial Isolation](figures/channel1_and_2_spatial_isolation.svg)

- **Overview Panel (Left):** SAM 3.1 segments honeybees on the landing board ROI while the V-JEPA hotspot projects a focus window onto the region of highest latent surprise.
- **Hotspot Crop (Right):** Scale-invariant containment (`IoA_bee >= 0.25`) isolates the instances inside the hotspot (highlighted in bright green) and passes the `384x384` foveal crop to Muse Spark Open.

### 2. Multi-Event Chronological Tracking
![Multi-Event Timeline Storyboard](figures/multi_event_timeline_storyboard.svg)

Rather than inspecting isolated frames in a vacuum, `MetaMuseService` aggregates consecutive peak events along the timeline in parallel using Java 25 Virtual Threads so the model can distinguish transient landing bursts from sustained entrance anomalies.

### 3. Event-Driven Saccades vs. Uniform Polling
![Ablation Study: Without vs With V-JEPA](figures/ablation_vjepa_vs_no_vjepa.svg)

| Dimension | Uniform Baseline (Without V-JEPA) | Event-Driven Pipeline (With V-JEPA) |
| :--- | :--- | :--- |
| **Temporal Triggering** | Uniform frame polling across the continuous stream | **Surprise-gated peak triggers (`S(t) >= τ`)** |
| **Spatial Context** | Full-frame overview only | **Full-frame overview + `384x384` foveal hotspot crop** |
| **Geometric Filtering** | Unlocalized board detections | **Hotspot containment (`IoA_bee >= 0.25`) + `< 22%` void guard** |
| **Causal Synthesis** | Single-frame VLM prompt | **Chronological multi-event digest (`<REPORT>` + `<SPOKEN>`)** |

### 4. Five-Tier Architecture Funnel
![Five-Tier Ablation Pyramid and Role Analysis](figures/ablation_pyramid_roles.svg)

| Pyramid Tier | Subsystem | Functional Role in the Pipeline | What Breaks If Omitted |
| :--- | :--- | :--- | :--- |
| **Tier 4 (Apex)** | `Meta Muse Spark Open` (`<REPORT>` + `<SPOKEN>`) | Multi-event causal synthesis, tool calling & spoken briefing | Stops at per-frame geometric JSON; no temporal narrative or voice output |
| **Tier 3** | `SamGeometry` (`IoA_bee >= 0.25` + Void Guard) | Geometric intersection between V-JEPA hotspot and SAM 3.1 boxes | Cannot separate localized multi-bee crowding from an unsegmented foreign object in the hotspot |
| **Tier 2** | `Meta SAM 3.1` (`Sam31Client`) | Physical instance segmentation on the landing board ROI | V-JEPA 2.1 produces latent surprise maps without object boundaries or instance masks |
| **Tier 1** | `V-JEPA 2.1` + Java 25 FFM & SIMD | Spatiotemporal surprise gating and dynamic hotspot localization | Requires uniform polling or a static crop that misses anomalies drifting across the flight board |
| **Tier 0 (Base)** | Raw 60 fps Stream (`EventService`) | Optical capture and on-demand `ffmpeg` keyframe extraction | Raw pixel differences trigger on illumination changes and moving grass outside the flight board |

---

## Dataset: Hugging Face & Media Resolution

The trajectory dataset and event clips are hosted at [**F1nnSBK/bee-jepa-entrance-anomalies**](https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies).

- **Recordings:** `rec2123` and `rec2125` from `mrkschtr/thehiveisreal` (`1080x960` @ 60 fps side-by-side raw camera + V-JEPA 2.1 heatmap).
- **On-Demand Media Resolution:** If local `.mp4` clips or `peak_frame.jpg` keyframes are not present on disk, `EventService` extracts keyframes on the fly via `ffmpeg` (`image2pipe` at `00:00:01.5`) into the Redis/SQLite frame cache, and `EventController` redirects video stream requests (`307 Temporary Redirect`) to the Hugging Face CDN.

```python
import pandas as pd
from huggingface_hub import hf_hub_download

timeline_file = hf_hub_download(
    repo_id="F1nnSBK/bee-jepa-entrance-anomalies",
    filename="metadata/timeline_points.parquet",
    repo_type="dataset",
)
df = pd.read_parquet(timeline_file)
peaks = df[df["event_id"].notna()].sort_values("surprise_score_l2", ascending=False)
print(peaks[["sequence_id", "timestamp_sec", "surprise_score_l2", "event_id"]].head())
```

---

## System Architecture & Key Packages

1. **Active Perception & Off-Heap Memory (`com.beejepa.perception`):**
   - `OffHeapFrameRingBuffer`: Pre-allocates 64-byte aligned off-heap memory slots via Java 25 FFM (`Arena.ofShared()`) with a 32-byte binary header (`timestampNs`, `frameIndex`, `surpriseSigma`, `hotspotCx`, `hotspotCy`, `payloadSize`) and raw JPEG payload bytes.
   - `SimdLatentEvaluator`: Computes vectorized Euclidean distance, L2 norm, and cosine similarity using `FloatVector.SPECIES_PREFERRED`.
   - `ActivePerceptionService`: Coordinates frame ingestion into the off-heap ring buffer, builds the saccadic trajectory (`SaccadicTarget`), and serves the 5-tier ablation pyramid.

2. **Spatial Grounding, Multimodal Reasoning & Audio (`com.beejepa.muse`):**
   - `Sam31Client` & `SamGeometry`: Executes promptable concept segmentation against `/v1/responses` (`model: "sam-3.1"`), filters boxes to the landing board ROI (`x <= 535`, `372 <= y <= 518`), and computes scale-invariant containment (`IoA_bee >= 0.25`) and the hotspot coverage void guard (`< 22%`).
   - `VisualCropRenderer`: Renders canonical `688x688` annotated overviews and `384x384` bicubic foveal crops.
   - `MetaMuseService` & `TolerantPayloadParser`: Orchestrates multimodal inference with `meta-models/muse-spark-open-1026` on Baseten using Java 25 Virtual Threads, handles autonomous tool calls (`QuerySam31Concept`, `QueryFovealHotspot`), and parses `<REPORT>` and `<SPOKEN>` outputs.
   - `ResilientApiExecutor`, `MuseDiskCache` & `RedisCacheClient`: Provides circuit breaking, exponential backoff with jitter, API key failover (`BASETEN_API_KEY1` -> `BASETEN_API_KEY2`), and 3-tier caching across in-memory maps, Redis (custom zero-dependency RESP2 socket client), and SQLite WAL (`state.db`).
   - `BeeAcousticSynthesizer` & `AudioSpectralAnalyzer`: Synthesizes deterministic 22.05 kHz PCM WAV honeybee bioacoustic signals (210–460 Hz fundamental carriers with 26–31 Hz wingbeat amplitude modulation and 35 ms comb resonance) and analyzes microphone input via a 1024-point Radix-2 Cooley-Tukey FFT.

3. **Edge Runtime, Auth & Persistence (`com.beejepa.state`, `com.beejepa.auth`, `com.beejepa.event`):**
   - Built on **Micronaut 4.7** (Netty) with **SQLite WAL** (`state.db`) for response/spatial/audio/frame caching, request latency telemetry (`TelemetryService`), and session authentication (`AuthService` using `PBKDF2WithHmacSHA256` and 12-hour bearer tokens).

---

## Quickstart & Deployment

### Prerequisites
- **JDK 25** (configured with `--enable-preview` and `--add-modules jdk.incubator.vector` in `build.gradle`)
- **Node.js 18+** and `npm`
- Optional: **Redis** on `127.0.0.1:6379` (automatically falls back to in-memory L0 + SQLite WAL L2 if unavailable) and **ffmpeg** for on-the-fly keyframe extraction

### 1. Configure Environment
```bash
cp .env.example .env
```
Key settings in `.env`:
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

### 2. Build Frontend & Run Server
```bash
./build_ui.sh
./gradlew run
```
Open `http://localhost:8080` in your browser. For frontend hot-reload during development, run `cd frontend && npm run dev` (proxies API requests to port `8080`).

### 3. Run Verification Suite
```bash
./gradlew test
```

### 4. Compile Standalone GraalVM Native Image
```bash
./gradlew nativeCompile
./build/native/nativeCompile/beejepa
```

---

## REST API Overview

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/auth/login` | Authenticate operator and issue a 12-hour `bee_` bearer token (`GET /me`, `POST /logout`) |
| `GET` | `/api/v1/timeline` | Full surprise trajectory `S(t)` and sequence metadata |
| `GET` | `/api/v1/events` | Detected peak anomaly events with cached SAM 3.1 & Muse summaries |
| `GET` | `/api/v1/events/{id}/media/{file}` | Serve event keyframes (`peak_frame.jpg` via cache/`ffmpeg`) or `.mp4` clips (local or HF `307` redirect) |
| `GET` | `/api/v1/sequences/{filename}` | Stream 30-second stitched sequence videos (local or HF `307` redirect) |
| `POST` | `/api/v1/events/{id}/analyze` | Execute or refresh SAM 3.1 + Muse Spark analysis for an event |
| `GET` | `/api/v1/muse/status` | Upstream Baseten/Meta API connectivity, active model, and circuit breaker state |
| `POST` | `/api/v1/muse/ask` | Multi-event visual reasoning returning `diagnosis` (`<REPORT>`), `spokenSummary` (`<SPOKEN>`), and crops |
| `GET/POST` | `/api/v1/muse/events/{id}/sam` | Retrieve or execute SAM 3.1 concept segmentation and `IoA` containment for an event |
| `GET` | `/api/v1/muse/tts?text=...` | Neural English TTS audio stream (`edge-tts`, cached in Redis/SQLite) |
| `GET` | `/api/v1/muse/voice?text=...` | Deterministic 22.05 kHz WAV honeybee bioacoustic stream |
| `POST` | `/api/v1/muse/acoustic-mirror` | 1024-point FFT prosody analysis of microphone audio with bioacoustic bee reply |
| `GET` | `/api/v1/perception/ring-buffer` | Java 25 FFM off-heap circular frame buffer telemetry |
| `GET` | `/api/v1/perception/saccades` | Saccadic trajectory targets with SIMD cosine similarity metrics |
| `GET` | `/api/v1/perception/ablation-pyramid` | 5-tier architecture funnel and component role breakdown |
| `POST` | `/api/v1/perception/simd-eval` | Hardware SIMD (`FloatVector`) Euclidean distance & cosine evaluation |
| `GET` | `/api/v1/telemetry/stats` | Request latency percentiles (`p50`, `p95`, `p99`) and `/prometheus` metrics |
| `GET` | `/api/v1/overview` | Full system state, storage health (`/api/v1/storage`), and virtual thread status (`/api/v1/ping`) |

---

## License

Created for the **Meta Global AI Developer Hackathon 2026** and university research in self-supervised video representations. Distributed under the MIT License.
