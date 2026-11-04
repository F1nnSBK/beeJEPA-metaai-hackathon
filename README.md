# beeJEPA

Hive entrance monitoring system combining **V-JEPA 2.1** prediction error tracking, **Meta SAM 3.1** instance segmentation, and **Meta Muse Spark Open** multimodal analysis on **Java 25** and **GraalVM Native Image**.

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

Continuous 60 fps video from a beehive entrance produces too much footage for manual review or constant cloud VLM calls. To provide clear transparency for the hackathon evaluation, the overview below distinguishes between the pre-existing university coursework foundation and the system built during the **Meta Global AI Developer Hackathon 2026**:

- **Pre-Existing University Coursework Foundation — System 1:**
  Self-supervised feature extraction from 60 fps `1080x960` hive camera footage using **V-JEPA 2.1** ViT-H/16 over an overlapping `9x9` patch grid. It computes the continuous prediction error `S(t) = ||z_pred - z_target||_2` and exports the anomaly timeline and event clips to the Hugging Face dataset. At this stage, outputs are numerical surprise scores and spatial grid coordinates without instance masks, language output, or a web interface.

- **Built During the Meta AI Hackathon — System 2:**
  - **Hotspot Cropping:** When `S(t)` crosses the threshold `τ`, `VisualCropRenderer` extracts a `688x688` overview of the flight board and a `384x384` crop around the V-JEPA attention peak.
  - **Off-Heap Frame Buffer and Vector API:** `OffHeapFrameRingBuffer` stores keyframes outside the JVM heap via Java 25 `Arena` and `MemorySegment`, while `SimdLatentEvaluator` computes vector distances and cosine similarity with `FloatVector`.
  - **SAM 3.1 Segmentation and Geometric Filtering:** `Sam31Client` segments bees on the landing board via **Meta SAM 3.1**, and `SamGeometry` measures how much of each detected bee lies inside the V-JEPA hotspot using an Intersection-over-Area threshold of `0.25` and a `22%` minimum hotspot coverage check.
  - **Muse Spark Open Report Generation:** `MetaMuseService` sends the event crops and segmentation metrics to `meta-models/muse-spark-open-1026` on Baseten. The model can call `QuerySam31Concept` and `QueryFovealHotspot` as tools and returns both a `<REPORT>` block in Markdown and a `<SPOKEN>` block formatted for text-to-speech.
  - **Caching and Fault Tolerance:** `ResilientApiExecutor` handles retries, circuit breaking, and API key failover. Results are cached across an in-memory map, Redis, and a local SQLite WAL database.
  - **Web Interface and Audio:** A Three.js scroll introduction leads into a Vue 3 console with video playback, SAM overlays, English speech output via `edge-tts`, and synthesized bee audio from `BeeAcousticSynthesizer`.

```
┌──────────────────────────────────────────────────────────────┐
│  PRE-EXISTING UNIVERSITY COURSEWORK (System 1)               │
│  - Raw 60 fps 1080x960 hive entrance video                   │
│  - Self-supervised V-JEPA 2.1 ViT-H/16 feature extraction    │
│  - Prediction error trajectory S(t) = ||z_pred - z_target||_2│
│  - Exported event clips & timeline dataset on Hugging Face   │
└──────────────────────────────┬───────────────────────────────┘
                               │  [HACKATHON CUT-OFF LINE]
═══════════════════════════════╪════════════════════════════════
┌──────────────────────────────▼───────────────────────────────┐
│  BUILT DURING META AI HACKATHON (System 2)                   │
│  1. Java 25 Off-Heap Frame Buffer & Vector API Evaluator     │
│  2. Meta SAM 3.1 Instance Segmentation on Landing Board      │
│  3. Geometric Hotspot Containment Filter with IoA >= 0.25    │
│  4. Meta Muse Spark Open Report & Spoken Summary on Baseten  │
│  5. Resilient Executor + Memory, Redis & SQLite WAL Cache    │
│  6. Three.js 3D Scroll Intro, Vue 3 Console & Audio Pipeline │
└──────────────────────────────────────────────────────────────┘
```

### Coursework vs. Hackathon Scope Matrix

| Area | Pre-Existing University Coursework (System 1) | Built During Meta AI Hackathon (System 2) |
| :--- | :--- | :--- |
| **Vision Models** | Pre-computed V-JEPA 2.1 encoder | **Meta SAM 3.1** instance segmentation via `/v1/responses` on the landing board ROI |
| **Spatial Grounding** | Numerical surprise heatmap and hotspot coordinates | **Geometric Containment (`SamGeometry`)** intersecting SAM 3.1 boxes with the V-JEPA hotspot via `IoA >= 0.25` and `< 22%` coverage check |
| **Multimodal Reasoning** | None — numerical surprise scores `S(t)` only | **Meta Muse Spark Open** (`meta-models/muse-spark-open-1026`) on Baseten with `688x688` + `384x384` visual inputs, tool calling, and `<REPORT>` + `<SPOKEN>` output |
| **Backend Runtime** | Offline Python evaluation scripts | **Java 25 & Micronaut 4.7 GraalVM Native Server** with Virtual Threads, FFM off-heap buffer, Vector API, Redis RESP2 client, SQLite WAL cache, and PBKDF2 auth |
| **Frontend & Audio** | Static plots and exported MP4 clips | **Three.js 3D Scroll Story + Vue 3 Console** with interactive timeline, `edge-tts` speech synthesis, `BeeAcousticSynthesizer`, and FFT acoustic mirror |

---

## Pipeline Stages

### 1. Camera Stream and V-JEPA 2.1 Attention
![Side-by-side V-JEPA detection](figures/side_by_side_vjepa_detection.gif)

- **Left:** Raw 60 fps flight board recording.
- **Right:** V-JEPA 2.1 prediction error heatmap highlighting sudden motion changes at the hive entrance.

### 2. SAM 3.1 Segmentation and Hotspot Containment
![Spatial isolation](figures/channel1_and_2_spatial_isolation.svg)

- **Overview Panel:** SAM 3.1 segments individual bees on the landing board while the V-JEPA hotspot marks the region of highest prediction error.
- **Hotspot Crop:** `SamGeometry` isolates the segmented bees inside the hotspot in bright green and forwards the `384x384` crop to Muse Spark Open.

### 3. Multi-Event Timeline Tracking
![Multi-event timeline storyboard](figures/multi_event_timeline_storyboard.svg)

Instead of evaluating single frames in isolation, `MetaMuseService` processes consecutive peak events in parallel on Java 25 Virtual Threads so the model can compare activity across the recording.

### 4. Event-Triggered Cropping vs. Uniform Polling
![Comparison without vs with V-JEPA](figures/ablation_vjepa_vs_no_vjepa.svg)

| Aspect | Uniform Polling without V-JEPA | Event-Triggered Pipeline with V-JEPA |
| :--- | :--- | :--- |
| **Frame Selection** | Fixed-interval sampling across the full video | Triggered only at prediction error peaks |
| **Visual Input** | Full camera frame only | Full overview plus `384x384` crop around the hotspot |
| **Spatial Filtering** | Unfiltered detections across the board | Hotspot containment with `IoA >= 0.25` and coverage check |
| **Model Output** | Single-frame description | Multi-event Markdown report and spoken summary |

### 5. Five-Tier Pipeline Breakdown
![Five-tier pipeline roles](figures/ablation_pyramid_roles.svg)

| Tier | Component | Role in the Pipeline | Effect if Removed |
| :--- | :--- | :--- | :--- |
| **Tier 4** | `MetaMuseService` | Combines multi-event crops and geometry into `<REPORT>` and `<SPOKEN>` outputs | Output stops at raw JSON metrics without a summary or audio briefing |
| **Tier 3** | `SamGeometry` | Intersects V-JEPA hotspots with SAM 3.1 boxes using `IoA >= 0.25` | Cannot tell whether a hotspot contains clustered bees or an unsegmented object |
| **Tier 2** | `Sam31Client` | Segments bees within the landing board region of interest | V-JEPA 2.1 provides heatmaps without object boundaries or counts |
| **Tier 1** | `V-JEPA 2.1` & `ActivePerceptionService` | Detects temporal peaks and locates the spatial hotspot on the board | Requires uniform polling or a fixed crop that misses activity away from the center |
| **Tier 0** | `EventService` | Serves 60 fps clips and extracts keyframes via `ffmpeg` | Raw pixel-difference triggers react to lighting changes and moving grass |

---

## Dataset and Media Delivery

The dataset and event clips are hosted on Hugging Face at [**F1nnSBK/bee-jepa-entrance-anomalies**](https://huggingface.co/datasets/F1nnSBK/bee-jepa-entrance-anomalies).

- **Recordings:** `rec2123` and `rec2125` from `mrkschtr/thehiveisreal`, recorded at `1080x960` and 60 fps with side-by-side camera and V-JEPA 2.1 heatmap views.
- **Media Delivery:** When a keyframe is requested and not yet on disk, `EventService` extracts it on demand from the `.mp4` clip at `00:00:01.5` using `ffmpeg` and stores it in Redis and SQLite. When a video file is missing locally, `EventController` responds with a `307` redirect to the Hugging Face dataset file.

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

## Backend Structure

1. **Off-Heap Buffer and Vector Evaluation — `com.beejepa.perception`**
   - `OffHeapFrameRingBuffer` allocates off-heap memory slots via Java 25 `Arena.ofShared()`, storing a 32-byte header and raw JPEG bytes per slot.
   - `SimdLatentEvaluator` computes Euclidean distance, L2 norm, and cosine similarity with `FloatVector.SPECIES_PREFERRED`.
   - `ActivePerceptionService` loads keyframes into the ring buffer, builds the saccadic trajectory, and assembles the ablation pyramid data.

2. **Segmentation, Multimodal Client, Caching and Audio — `com.beejepa.muse`**
   - `Sam31Client` and `SamGeometry` call `/v1/responses` with `model: "sam-3.1"`, filter detections to the landing board area `x <= 535` and `372 <= y <= 518`, and evaluate hotspot containment.
   - `VisualCropRenderer` draws bounding boxes onto the `688x688` frame and produces the `384x384` bicubic crop.
   - `MetaMuseService` and `TolerantPayloadParser` run inference against `meta-models/muse-spark-open-1026`, execute tool calls, and split the response into Markdown and spoken text.
   - `ResilientApiExecutor`, `MuseDiskCache`, and `RedisCacheClient` manage circuit breaking, backoff retries, API key failover, and caching across memory, Redis, and SQLite.
   - `BeeAcousticSynthesizer` and `AudioSpectralAnalyzer` generate 22.05 kHz WAV audio with 210–460 Hz carriers and analyze microphone input with a 1024-point FFT.

3. **Persistence, Authentication and Telemetry — `com.beejepa.state`, `com.beejepa.auth`, `com.beejepa.telemetry`**
   - Runs on **Micronaut 4.7** and **Netty** with **SQLite WAL** in `state.db` for caching, latency tracking, and session authentication using `PBKDF2WithHmacSHA256`.

---

## Quickstart

### Prerequisites
- **JDK 25** with `--enable-preview` and `--add-modules jdk.incubator.vector` configured in `build.gradle`
- **Node.js 18+** and `npm`
- Optional: **Redis** on `127.0.0.1:6379` and **ffmpeg** for on-demand frame extraction

### 1. Environment Configuration
```bash
cp .env.example .env
```
Main settings in `.env`:
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
Open `http://localhost:8080`. During frontend development, run `cd frontend && npm run dev` to proxy API calls to port `8080`.

### 3. Run Tests
```bash
./gradlew test
```

### 4. Build GraalVM Native Binary
```bash
./gradlew nativeCompile
./build/native/nativeCompile/beejepa
```

---

## REST API

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/v1/auth/login` | Authenticate user and issue a 12-hour `bee_` bearer token |
| `GET` | `/api/v1/auth/me` | Validate current bearer token and return user profile |
| `GET` | `/api/v1/timeline` | Return surprise trajectory `S(t)` and sequence metadata |
| `GET` | `/api/v1/events` | List peak anomaly events with cached SAM 3.1 and Muse data |
| `GET` | `/api/v1/events/{id}/media/{file}` | Serve event keyframes or `.mp4` clips from local storage or Hugging Face |
| `GET` | `/api/v1/sequences/{filename}` | Stream 30-second sequence videos from local storage or Hugging Face |
| `POST` | `/api/v1/events/{id}/analyze` | Run or refresh SAM 3.1 and Muse Spark analysis for an event |
| `GET` | `/api/v1/muse/status` | Check upstream API status, active model, and circuit breaker state |
| `POST` | `/api/v1/muse/ask` | Run multi-event visual analysis and return report, spoken summary, and crops |
| `GET/POST` | `/api/v1/muse/events/{id}/sam` | Load or run SAM 3.1 segmentation and hotspot containment for an event |
| `GET` | `/api/v1/muse/tts?text=...` | Stream English TTS audio generated via `edge-tts` |
| `GET` | `/api/v1/muse/voice?text=...` | Stream 22.05 kHz WAV audio from `BeeAcousticSynthesizer` |
| `POST` | `/api/v1/muse/acoustic-mirror` | Analyze microphone audio via FFT and return a synthesized bee reply |
| `GET` | `/api/v1/perception/ring-buffer` | Read off-heap frame buffer metrics |
| `GET` | `/api/v1/perception/saccades` | List saccadic targets and vector cosine similarity values |
| `GET` | `/api/v1/perception/ablation-pyramid` | Return 5-tier pipeline metrics and tier descriptions |
| `POST` | `/api/v1/perception/simd-eval` | Compute Euclidean distance and cosine similarity via the Vector API |
| `GET` | `/api/v1/telemetry/stats` | Read request latency percentiles and `/prometheus` metrics |
| `GET` | `/api/v1/overview` | Read system state, storage status, and virtual thread health |

---

## License

MIT License.
