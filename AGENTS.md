# AGENTS.md

## Commands

- Backend build & test: `./gradlew test`
- Backend run: `./gradlew run`
- Native image: `./gradlew nativeCompile`
- Frontend dev: `cd frontend && npm run dev`
- Frontend build: `cd frontend && npm run build`

## Code Map

- `frontend/` — application source
- `src/` — application source
- `.github/` — project configuration

## Conventions

- Use `import`/`export` syntax in `.mjs` files.
- Use `package` and `import` declarations in `.java` files.
- Use `import` and `from` imports in `.py` files.
- Name tests `*Test.java`.

## Ground Truth & Data Conventions

- Dataset (`data/manifest.json`, `data/events.json`): Backbone is `ViT-B` (not `ViT-H`).
- Anomaly metric in `events.json` is a raw **token-delta** score (not $\sigma$), and board counts represent **token peaks** (not individual bees). Label them honestly in DTOs, prompts, and UI.
- Cache lookups in `MuseDiskCache` (L0 Map -> L1 Redis -> L2 SQLite WAL) must match the exact deterministic key `(eventId, normalizedPrompt, modelVersion)`.
- Health endpoint is `GET /api/v1/muse/status` (not `/health`).
- Off-heap ring buffer (`OffHeapFrameRingBuffer`) active capacity is 64 slots (`393,216` bytes max payload); 64-float latents live in `ActivePerceptionService.latentStore`.

## Do NOT

- Never return a cached response belonging to a different `eventId` or question when Baseten (`sam-3.1` or `muse-spark-open-1026`) is unreachable.
- Never return hardcoded domain claims (e.g., "no hornets or wasps detected", static `"14:20"` timestamps, or fixed transcription strings) as API fallbacks.
- Never classify an event as `potential_non_bee_anomaly` or intruder when a SAM 3.1 call fails—treat API failures as error states, not biological anomalies.
- Never report board token peaks as SAM-verified bee detections when SAM returns 0 boxes.
- Never hardcode evaluation tables or tautological 100% hit rates in `ActivePerceptionService`—only serve measured data or verified evaluation artifacts.
- Never redirect client requests via HTTP 307 to external CDNs (`huggingface.co`) without explicit proxy/consent handling (GDPR/IP leak).
- Never pass a single `eventId` from the frontend when the user asks a global cohort/day question.
