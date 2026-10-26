# beeJEPA Edge Server & Console

High-performance, lightweight edge server and embedded web console for **beeJEPA**, optimized for deployment on a **Raspberry Pi 4 Model B** (ARM64) using **GraalVM Native Image (AOT)**, **Java 25 Virtual Threads**, and an embedded **Vue 3 + Tailwind CSS** frontend.

Built for the **Meta AI Hackathon** in conjunction with self-supervised **V-JEPA** video anomaly detection.

---

## Architecture & Features

### 1. Edge & Core Runtime
- **Hardware Target:** Raspberry Pi 4 Model B (Broadcom BCM2711, Quad-core Cortex-A72, ARM64).
- **Backend Framework:** Micronaut 4.7 with Netty and Ahead-of-Time (AOT) optimizations (`io.micronaut.aot`).
- **Concurrency:** Java 25 Virtual Threads (Project Loom, `micronaut.server.thread-selection: AUTO`) for non-blocking, high-concurrency edge I/O.
- **Embedded State Storage:** SQLite engine (`state.db`) operating in Write-Ahead Logging (`WAL`) mode directly on the external SSD for run tracking, audit logs, and authentication.
- **Secure Authentication:** Beekeeper credentials protected via `PBKDF2WithHmacSHA256` with 16-byte cryptographic random salts and constant-time verification.
- **Single-Binary Delivery:** The Vue 3 SPA is compiled into `src/main/resources/public/` and bundled directly into the standalone GraalVM native binary.

### 2. V-JEPA Artifacts & Smart SSD Caching
- **Artifact Ingestion:** Detects V-JEPA sequence directories on the external SSD (`data/events/<eventId>/meta.json`), parsing metadata into Java 25 Records (`AnomalyEvent`).
- **Media Streaming:** Serves high-speed raw clips (`clip_raw.mp4`), side-by-side latent comparison clips (`clip_comparison.mp4`), and peak hotspot frames (`peak_frame.jpg`).
- **Smart SSD Caching:** When an anomaly is analyzed by Meta AI models, the output is saved as `agent_result.json` on the SSD. Subsequent requests load instantly with **0 API credits consumed**.
- **External SSD Symlink:** Automated symlink validation (`data/ -> /mnt/ssd/beejepa_data`), mount checking, and free disk space telemetry.

### 3. Meta AI Multimodal Pipeline
- **SAM 3.1 Instance Segmentation:** Applies Meta Segment Anything Model 3.1 with a bounding box prompt centered on the V-JEPA spatial tubelet hotspot grid coordinates `(tubeletGridX, tubeletGridY)`.
- **Muse Spark Causal Explanation:** Invokes Meta Muse (`meta-models/muse-spark-open-1026`) to synthesize natural-language causal reasoning explaining *why* the latent prediction error spiked (e.g. predatory wasp intrusion, erratic queen flight, or camera occlusion).

### 4. Deterministic Bee Voice Acoustic Synthesizer (Java 25)
- **Bioacoustic Grounding:** Converts text responses from Meta Muse into authentic honeybee communication signals based on scientific bioacoustic literature (210–460 Hz fundamental frequencies, 26–30 Hz wingbeat amplitude modulation, harmonic overtone series, and comb resonance).
- **Deterministic Word-to-Acoustic Mapping:** Every specific word always generates the exact same bee phonetic pitch, duration, and timbre.
- **Sentence Prosody:** Interrogative pitch lifts (`?`), declarative cadences (`.`), and alarm piping flourishes (`!`).
- **Sub-3ms Performance:** In-memory PCM 16-bit 22.05 kHz WAV generation with zero disk I/O and zero external audio libraries.
- **Live Device Microphone:** In-browser Web Speech API and `getUserMedia` integration allowing live voice queries (*"Hey Muse, how are my bees doing?"*) in safe, zero-credit test mode.

### 5. Embedded Vue 3 Frontend
- **37.5-Minute Latent Surprise Timeline:** Interactive area curve with threshold line ($\tau = 3.0\sigma$) and HTML pin markers with hover tooltips and ping animation.
- **Multi-View Video Inspector:** Instant toggle between *Side-by-Side (Pixel vs. V-JEPA)*, *Raw 60fps Clip*, and *SAM 3.1 Peak Frame* with animated hotspot bounding box overlay.
- **Dynamic 3D Bee Swarm Background:** Canvas-based low-temperature random walk simulation with stratified grid spawn and Boids mutual separation force to eliminate clumping.

---

## Repository Structure

```
beeJEPA/
├── build.gradle               # Gradle build configuration (Micronaut, Groovy DSL, GraalVM Native)
├── settings.gradle            # Project settings
├── gradlew                    # Gradle wrapper script
├── build_ui.sh                # Builds Vue frontend and syncs dist into Micronaut resources
├── .env.example               # Template for environment variables and secrets
├── .gitignore                 # Excludes .env, SSD data directory, and build artifacts
├── scripts/
│   ├── setup_ssd_symlink.sh   # Mounts/symlinks external SSD to data/
│   └── install_nginx.sh       # Installs production Nginx configuration on Raspberry Pi
├── nginx/
│   └── beejepa.conf           # Reverse proxy configuration with WebSocket support
├── frontend/                  # Vue 3 + Vite + Tailwind CSS SPA
│   ├── package.json
│   ├── vite.config.js
│   ├── tailwind.config.js
│   ├── index.html
│   └── src/
│       ├── App.vue            # Console dashboard, video player, timeline & bee swarm
│       ├── main.js
│       └── style.css
└── src/
    ├── main/
    │   ├── java/com/beejepa/
    │   │   ├── Application.java               # Main entry point with automatic .env loader
    │   │   ├── auth/                          # PBKDF2 authentication service & tokens
    │   │   ├── config/                        # Micronaut configuration properties
    │   │   ├── controller/                    # REST endpoints
    │   │   │   ├── AuthController.java        # Login, logout & session profile
    │   │   │   ├── EventController.java       # Timeline, events, media & SAM/Muse triggers
    │   │   │   ├── MuseController.java        # Muse status, test voice query & WAV stream
    │   │   │   ├── StateController.java       # SQLite state key-value endpoints
    │   │   │   └── SystemInfoController.java  # Healthcheck & ping
    │   │   ├── event/                         # AnomalyEvent record & EventService
    │   │   ├── muse/                          # Muse client, service, DTOs & BeeAcousticSynthesizer
    │   │   ├── state/                         # SQLite StateRepository & schema migrations
    │   │   └── storage/                       # StorageService & SSD symlink verification
    │   └── resources/
    │       ├── application.yml                # Server, Virtual Threads & static-resource router
    │       ├── logback.xml                    # Structured thread-aware logging
    │       └── public/                        # Built frontend assets (synced by build_ui.sh)
    └── test/                                  # Integration & unit test suite
```

---

## Prerequisites

- **Java Development Kit (JDK):** JDK 21+ (Tested on Java 25 with Virtual Threads).
- **Node.js:** Node.js 18+ and npm (for compiling the Vue frontend).
- **External SSD:** Fast USB 3.0 / USB-C SSD (ext4 for Raspberry Pi, APFS/exFAT for macOS).

---

## Development Workflow

### 1. Environment Setup

```bash
cp .env.example .env
```

Edit `.env` as needed:
```env
META_MUSE_API_KEY=your_key_here
META_MUSE_BASE_URL=https://api.muse.meta.com
BASETEN_API_KEY=your_baseten_key_here
PORT=8080
BEEJEPA_DATA_PATH=./data
BEEJEPA_REQUIRE_SYMLINK=false
```

### 2. External SSD Setup

Symlink your external SSD partition to `data/`:

```bash
# On Raspberry Pi:
./scripts/setup_ssd_symlink.sh /mnt/ssd/beejepa_data

# On macOS:
./scripts/setup_ssd_symlink.sh /Volumes/BeeSSD/beejepa_data
```

### 3. Run Backend Server (Port 8080)

```bash
./gradlew run
```

### 4. Run Frontend in Development Mode (Port 5173)

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173/` in your browser. API requests proxy to `http://localhost:8080`.

---

## Production Build & Raspberry Pi Deployment

### 1. Compile and Bundle the Frontend

```bash
./build_ui.sh
```

This compiles optimized client bundles via Vite into `frontend/dist/` and syncs them into `src/main/resources/public/`.

### 2. Compile Standalone GraalVM Native Image (ARM64)

```bash
./gradlew nativeCompile
```

Run the standalone executable:
```bash
./build/native/nativeCompile/beejepa
```

The server starts in **< 40 ms**, consumes minimal memory, and serves both the REST API and the embedded Vue 3 SPA on `http://<pi-ip>:8080/`.

---

## REST API Reference

### System & Authentication
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | Embedded Vue 3 Dashboard Console |
| `POST` | `/api/v1/auth/login` | Authenticate beekeeper credentials (PBKDF2) |
| `GET` | `/api/v1/auth/me` | Current authenticated user profile |
| `GET` | `/api/v1/ping` | Healthcheck, JVM version, and Virtual Thread confirmation |
| `GET` | `/api/v1/storage` | SSD mount status, symlink target path, and free disk space |

### V-JEPA Events & Media
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/timeline` | 37.5-minute anomaly fever curve data & sequence metadata |
| `GET` | `/api/v1/events` | List all detected anomaly events on the SSD |
| `GET` | `/api/v1/events/{id}` | Detailed metadata for a specific anomaly event |
| `GET` | `/api/v1/events/{id}/media/{file}` | Stream media (`clip_raw.mp4`, `clip_comparison.mp4`, `peak_frame.jpg`) |
| `POST` | `/api/v1/events/{id}/analyze` | Trigger SAM 3.1 & Muse Spark causal inference (cached on SSD) |

### Meta Muse & Acoustic Voice
| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/v1/muse/status` | Connectivity and standby status for Meta Muse |
| `POST` | `/api/v1/muse/query-voice` | Zero-credit test query for voice interactions |
| `GET` | `/api/v1/muse/voice?text=...` | Stream dynamically synthesized 22.05 kHz WAV bee voice audio |
| `POST` | `/api/v1/muse/generate` | Dispatch inference request to Meta Muse |
| `POST` | `/api/v1/muse/embeddings` | Compute embeddings via Meta Muse |

---

## License

Created for the **Meta AI Hackathon 2026** and university coursework in self-supervised video representations. Distributed under the MIT License.
