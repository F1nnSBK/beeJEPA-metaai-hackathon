# beeJEPA Edge Server & Console

High-performance, lightweight edge server and embedded web console for **beeJEPA**, optimized for deployment on a **Raspberry Pi** (ARM64) using **GraalVM Native Image (AOT)**, **Java Virtual Threads**, and an embedded **Vue 3 + Tailwind CSS** frontend.

---

## Architecture & Features

- **Backend Runtime:** Micronaut 4.7 with Netty and Ahead-of-Time (AOT) optimizations (`io.micronaut.aot`).
- **Concurrency:** Java Virtual Threads (Project Loom, `micronaut.server.thread-selection: AUTO`) for non-blocking, high-concurrency edge I/O.
- **Embedded Frontend:** Vue 3 Single Page Application (SPA) built with Vite and Tailwind CSS.
  - 3-column video layout placeholder (Raw Hive Optical Stream, V-JEPA Latent Forecast, Surprise Anomaly Detector).
  - Interactive bottom timeline component with anomaly spike indicators and scrubber controls.
- **Single-Binary Delivery:** The compiled Vue 3 frontend is bundled into `src/main/resources/public/` and served directly by Micronaut on `/**`. During GraalVM native image compilation, the entire frontend and backend are baked into a single standalone binary.
- **Meta Muse API Integration:** Declarative HTTP client (`@Client`) for Meta Muse endpoints, supporting `.env` secret management (`META_MUSE_API_KEY`) with automated mock/fallback execution for local offline testing.
- **External SSD Repository:** Symlinked data hierarchy (`data/ -> /mnt/ssd/...` or `/Volumes/...`) with automated symlink detection, mount validation, and free disk space monitoring.
- **Edge State Storage:** Embedded SQLite engine (`state.db`) operating in Write-Ahead Logging (`WAL`) mode directly on the SSD symlink for persistent state and run tracking across virtual threads.

---

## Repository Structure

```
beeJEPA/
├── build.gradle               # Gradle build configuration (Micronaut, AOT, GraalVM Native)
├── settings.gradle            # Project settings
├── gradlew                    # Gradle wrapper script
├── build_ui.sh                # Builds Vue frontend and syncs dist into Micronaut resources
├── .env.example               # Template for environment variables and secrets
├── .gitignore                 # Excludes .env, SSD data directory, SQLite DBs, and build artifacts
├── scripts/
│   └── setup_ssd_symlink.sh   # Utility script to mount/symlink the external SSD
├── frontend/                  # Vue 3 + Vite + Tailwind CSS SPA
│   ├── package.json
│   ├── vite.config.js
│   ├── tailwind.config.js
│   ├── postcss.config.js
│   ├── index.html
│   └── src/
│       ├── App.vue            # 3-column video grid and timeline UI
│       ├── main.js
│       └── style.css          # Tailwind CSS entry
└── src/
    ├── main/
    │   ├── java/com/beejepa/
    │   │   ├── Application.java               # Main entry point with automatic .env loader
    │   │   ├── config/                        # Configuration classes (MetaMuseConfig, StorageConfig)
    │   │   ├── controller/                    # REST endpoints (StateController, MuseController, SystemInfoController)
    │   │   ├── muse/                          # Meta Muse API Client, Service & DTOs
    │   │   ├── state/                         # SQLite State Repository & Service
    │   │   └── storage/                       # Storage Service & SSD symlink verification
    │   └── resources/
    │       ├── application.yml                # Server, Virtual Threads, and static-resources router
    │       ├── logback.xml                    # Structured logging with thread identifiers
    │       └── public/                        # Built frontend assets (populated by build_ui.sh)
    └── test/                                  # Integration & unit test suite
```

---

## Prerequisites

- **Java Development Kit (JDK):** Java 21+ (e.g., OpenJDK 21 LTS or 25; Virtual Threads and Micronaut AOT require JDK 21+).
- **Node.js:** Node 18+ and npm (for compiling the Vue frontend).
- **External SSD:** Formatted SSD (e.g., ext4 for Raspberry Pi Linux or APFS/exFAT for macOS).

---

## Development Workflow

During active development, run the backend and frontend development servers concurrently:

### 1. Configure Environment Variables

```bash
cp .env.example .env
```

Set your configuration in `.env`:
```env
META_MUSE_API_KEY=your_meta_muse_api_key_here
META_MUSE_BASE_URL=https://api.muse.meta.com
PORT=8080
BEEJEPA_DATA_PATH=./data
BEEJEPA_REQUIRE_SYMLINK=false
```

### 2. Configure SSD Symlink

When the external SSD is plugged in:

```bash
# On Raspberry Pi (example mount point):
./scripts/setup_ssd_symlink.sh /mnt/ssd/beejepa_data

# On macOS (example mount point):
./scripts/setup_ssd_symlink.sh /Volumes/BeeSSD/beejepa_data
```

### 3. Start Backend (Port 8080)

```bash
./gradlew run
```

### 4. Start Frontend Dev Server (Port 5173)

In a separate terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173/` in your browser. API requests are directed to the backend at `http://localhost:8080/api/...`.

---

## Production Build & Raspberry Pi Deployment

### 1. Build and Bake the Frontend into Micronaut

Run the integration script:

```bash
./build_ui.sh
```

This script:
1. Runs `npm run build` inside `frontend/` to generate optimized production assets in `frontend/dist/`.
2. Cleans `src/main/resources/public/`.
3. Copies the built assets into `src/main/resources/public/`.

Micronaut's router is configured in `application.yml` to serve static files from `classpath:public` on `/**`:

```yaml
micronaut:
  router:
    static-resources:
      frontend:
        paths: classpath:public
        mapping: /**
```

### 2. AOT Native Compilation (Raspberry Pi ARM64)

Compile the standalone native binary with GraalVM:

```bash
./gradlew nativeCompile
```

The resulting executable contains the entire web server, virtual thread runtime, SQLite driver, and the Vue 3 SPA:

```bash
./build/native/nativeCompile/beejepa
```

Access the complete UI and API at `http://<raspberry-pi-ip>:8080/`.

---

## Nginx & Cloudflare Tunnel (beejepa.finn-hertsch.de)

The repository provides a production Nginx configuration tailored for the Raspberry Pi with WebSocket support and direct SSD file delivery.

### 1. Install & Activate Nginx Config

Run the installer script with sudo on the Raspberry Pi:

```bash
sudo ./scripts/install_nginx.sh
```

Or manually link the file:
```bash
sudo cp nginx/beejepa.conf /etc/nginx/sites-available/beejepa.conf
sudo ln -sf /etc/nginx/sites-available/beejepa.conf /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

### 2. Cloudflare Tunnel Configuration

Point your Cloudflare Tunnel (`cloudflared`) to Nginx:
- **Public Hostname:** `beejepa.finn-hertsch.de`
- **Service:** `HTTP` -> `localhost:80` (or `http://127.0.0.1:8080` directly)
- **Additional Headers:** Nginx is pre-configured with `$http_cf_connecting_ip` and full WebSocket upgrade support (`/ws`).

---

## REST API Reference

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | Serves the embedded Vue 3 SPA Console |
| `GET` | `/api/v1/ping` | Healthcheck, JVM version, and Virtual Thread confirmation |
| `GET` | `/api/v1/storage` | SSD symlink status, real target path, and usable disk space |
| `GET` | `/api/v1/overview` | Full system overview (storage, state entries, recent runs) |
| `GET` | `/api/v1/state` | Retrieve all key-value state pairs from SQLite |
| `GET` | `/api/v1/state/{key}` | Retrieve a specific state value |
| `PUT` | `/api/v1/state/{key}` | Store or update a key-value pair in SQLite (`state.db`) |
| `GET` | `/api/v1/runs` | List recent processing runs |
| `POST` | `/api/v1/runs` | Log and record a new task run |
| `GET` | `/api/v1/muse/status` | Configuration and connectivity status for Meta Muse API |
| `POST` | `/api/v1/muse/generate` | Dispatch inference request to Meta Muse (mock fallback when unconfigured) |
| `POST` | `/api/v1/muse/embeddings` | Compute embeddings via Meta Muse |
