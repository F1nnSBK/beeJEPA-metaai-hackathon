# ==============================================================================
# Stage 1: Raspberry Pi ARM64 / Linux Runtime Base (Java 25 + FFM + SIMD + FFmpeg)
# ==============================================================================
FROM eclipse-temurin:25-jre AS runtime-base
WORKDIR /opt/beejepa

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl \
        ffmpeg \
        python3 \
        python3-venv \
        python3-pip \
    && python3 -m venv /opt/beejepa/.venv \
    && /opt/beejepa/.venv/bin/pip install --no-cache-dir edge-tts \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/beejepa/data /opt/beejepa/sqlite /opt/beejepa/public

ENV PORT=8080 \
    BEEJEPA_DATA_PATH=/opt/beejepa/data \
    BEEJEPA_SQLITE_PATH=/opt/beejepa/sqlite/state.db \
    BEEJEPA_REQUIRE_SYMLINK=false \
    BEEJEPA_RING_BUFFER_CAPACITY=64 \
    BEEJEPA_RING_BUFFER_MAX_PAYLOAD_BYTES=393216 \
    BASETEN_ALLOW_INFERENCE=true \
    REDIS_HOST=redis \
    REDIS_PORT=6379 \
    REDIS_ENABLED=true \
    CACHE_TTL_SECONDS=604800

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/ping || exit 1

ENTRYPOINT ["java", \
    "--enable-preview", \
    "--add-modules=jdk.incubator.vector", \
    "-Xms128m", \
    "-Xmx512m", \
    "-XX:MaxMetaspaceSize=128m", \
    "-XX:MaxDirectMemorySize=128m", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=50", \
    "-XX:+UseStringDeduplication", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.awt.headless=true", \
    "-jar", "/opt/beejepa/beeJEPA-all.jar"]

# ==============================================================================
# Stage 2: Prebuilt Runtime Target (Fast Raspberry Pi Deploy with Prebuilt JAR)
# ==============================================================================
FROM runtime-base AS prebuilt-runtime
COPY beeJEPA-all.jar /opt/beejepa/beeJEPA-all.jar
COPY public/ /opt/beejepa/public/

# ==============================================================================
# Stage 3: Build Vue 3 SPA Frontend from Source
# ==============================================================================
FROM node:22-alpine AS frontend-builder
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ==============================================================================
# Stage 4: Build Micronaut 4.7 / Java 25 Executable Shadow JAR from Source
# ==============================================================================
FROM eclipse-temurin:25-jdk AS backend-builder
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ ./gradle/
RUN chmod +x gradlew
COPY src/ ./src/
COPY --from=frontend-builder /workspace/frontend/dist/ ./src/main/resources/public/
RUN ./gradlew shadowJar -x test --no-daemon

# ==============================================================================
# Stage 5: Full Source-Built Runtime Target (Default)
# ==============================================================================
FROM runtime-base AS full-runtime
COPY --from=backend-builder /workspace/build/libs/*-all.jar /opt/beejepa/beeJEPA-all.jar
COPY --from=frontend-builder /workspace/frontend/dist/ /opt/beejepa/public/
