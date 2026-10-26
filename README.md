# beeJEPA Edge Server

High-performance, lightweight edge server framework for **beeJEPA**, optimized for deployment on a **Raspberry Pi** (ARM64) using **GraalVM Native Image (AOT)** and **Java Virtual Threads**.

---

## Architecture & Features

- **Runtime & Framework:** Micronaut 4.7 with Netty and Ahead-of-Time (AOT) optimizations (`io.micronaut.aot`).
- **Concurrency:** Java Virtual Threads (Project Loom, `micronaut.server.thread-selection: AUTO`) for non-blocking, high-concurrency I/O.
- **AOT Native Compilation:** Ahead-of-Time compilation via GraalVM Native Image produces a lean, standalone binary with near-instant startup and minimal memory footprint on Raspberry Pi 4 / 5.
- **Meta Muse API Integration:** Declarative HTTP client (`@Client`) for Meta Muse endpoints, supporting `.env` secret management (`META_MUSE_API_KEY`) and automated mock/fallback execution for local offline testing.
- **External SSD Repository:** Symlinked data hierarchy (`data/ -> /mnt/ssd/...` or `/Volumes/...`) with automated symlink detection, mount validation, and free disk space monitoring.
- **Edge State Storage:** Lightweight, embedded SQLite engine (`state.db`) operating in Write-Ahead Logging (`WAL`) mode directly on the SSD symlink for transactional state and run tracking across virtual threads.

---

## Repository Structure

```
beeJEPA/
├── build.gradle               # Gradle build configuration (Micronaut, AOT, GraalVM Native)
├── settings.gradle            # Project settings
├── gradlew                    # Gradle wrapper script
├── .env.example               # Template for environment variables and secrets
├── .gitignore                 # Excludes .env, SSD data directory, SQLite DBs, and build artifacts
├── scripts/
│   └── setup_ssd_symlink.sh   # Utility script to mount/symlink the external SSD
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
    │       ├── application.yml                # Server configuration and Virtual Thread settings
    │       └── logback.xml                    # Structured logging with thread identifiers
    └── test/                                  # Integration & unit test suite
```

---

## Prerequisites

- **Java Development Kit (JDK):** Java 21+ (e.g. OpenJDK 21 LTS or 25; Virtual Threads and Micronaut AOT require JDK 21+).
- **External SSD:** Formatted SSD (e.g., ext4 for Raspberry Pi Linux or APFS/exFAT for macOS).

---

## Getting Started

### 1. Configure Environment Variables

Copy `.env.example` to `.env` and configure your API credentials:

```bash
cp .env.example .env
```

Contents of `.env`:
```env
# Meta Muse API Key
META_MUSE_API_KEY=your_meta_muse_api_key_here

# Meta Muse Endpoint
META_MUSE_BASE_URL=https://api.muse.meta.com

# Server Port
PORT=8080

# Local path or SSD symlink mount
BEEJEPA_DATA_PATH=./data

# Require data to be a symlink to an SSD (true/false)
BEEJEPA_REQUIRE_SYMLINK=false
```

### 2. Configure SSD Symlink

Once your external SSD is connected and mounted, run the setup script:

```bash
# On Raspberry Pi (example mount point):
./scripts/setup_ssd_symlink.sh /mnt/ssd/beejepa_data

# On macOS (example mount point):
./scripts/setup_ssd_symlink.sh /Volumes/BeeSSD/beejepa_data
```

This creates the symlink `data -> <TARGET_SSD_PATH>` and initializes standard directories:
- `data/models/`
- `data/embeddings/`
- `data/captures/`
- `data/runs/`
- `data/state.db` (SQLite state storage)

### 3. Run Development Server

```bash
./gradlew run
```

### 4. Run Test Suite

```bash
./gradlew test
```

---

## Ahead-of-Time (AOT) & GraalVM Compilation

### Native Compilation on Raspberry Pi (ARM64)

Ensure GraalVM JDK 21+ is installed on the Raspberry Pi:

```bash
./gradlew nativeCompile
```

The compiled standalone binary is placed in:
```bash
./build/native/nativeCompile/beejepa
```

Execute the binary directly with zero JVM overhead:
```bash
./build/native/nativeCompile/beejepa
```

### Cross-Compilation via Docker (Multi-Arch)

To compile an ARM64 native image using Docker Buildx:

```bash
./gradlew dockerBuildNative
```

---

## REST API Reference

| Method | Endpoint | Description |
|---|---|---|
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
