#!/usr/bin/env bash
set -euo pipefail

RASPI_HOST="${RASPI_HOST:-raspi}"
REMOTE_DIR="/home/finn/beejepa-raspi"
SSD_DATA_DIR="/mnt/onlypits/beeJEPA"
EXT4_STATE_DIR="/mnt/onlypits-ext4/beejepa"

echo "=== [1/4] Building Vue 3 SPA & Micronaut Java 25 Shadow JAR ==="
./build_ui.sh
./gradlew shadowJar -x test

echo "=== [2/4] Syncing Artifacts & Manifest to Raspberry Pi (${RASPI_HOST}) ==="
ssh "${RASPI_HOST}" "chmod 755 /home/finn && mkdir -p ${REMOTE_DIR}/public && sudo mkdir -p ${EXT4_STATE_DIR}/sqlite ${EXT4_STATE_DIR}/redis && sudo chown -R finn:finn ${EXT4_STATE_DIR} && ln -sfn ${SSD_DATA_DIR} ${REMOTE_DIR}/data"
scp build/libs/*-all.jar "${RASPI_HOST}:${REMOTE_DIR}/beeJEPA-all.jar"
scp Dockerfile docker-compose.yml "${RASPI_HOST}:${REMOTE_DIR}/"
scp -r src/main/resources/public/* "${RASPI_HOST}:${REMOTE_DIR}/public/"
scp deploy/manifest.json "${RASPI_HOST}:${SSD_DATA_DIR}/hackathon_sample/manifest.json"
scp deploy/beejepa-raspi-nginx.conf "${RASPI_HOST}:${REMOTE_DIR}/beejepa-raspi-nginx.conf"
if [ -f .env ]; then
  scp .env "${RASPI_HOST}:${REMOTE_DIR}/.env"
fi

echo "=== [3/4] Updating Nginx & Starting Redis + beeJEPA on Raspberry Pi ==="
ssh "${RASPI_HOST}" "cd ${REMOTE_DIR} && BEEJEPA_DOCKER_TARGET=prebuilt-runtime BEEJEPA_HOST_DATA_DIR=${SSD_DATA_DIR} BEEJEPA_HOST_SQLITE_DIR=${EXT4_STATE_DIR}/sqlite docker compose up -d --build --remove-orphans"
ssh "${RASPI_HOST}" "sudo cp ${REMOTE_DIR}/beejepa-raspi-nginx.conf /etc/nginx/sites-available/beejepa && sudo ln -sfn /etc/nginx/sites-available/beejepa /etc/nginx/sites-enabled/beejepa && sudo rm -f /etc/nginx/sites-enabled/onlypits /etc/nginx/sites-enabled/default /etc/nginx/sites-available/onlypits && sudo nginx -t && sudo systemctl reload nginx"

echo "=== [4/4] Verifying Live Raspberry Pi Health ==="
ssh "${RASPI_HOST}" "for i in \$(seq 1 30); do curl -fsS http://127.0.0.1:8080/api/v1/ping && break || sleep 1; done && echo '' && curl -fsS http://127.0.0.1:8080/api/v1/muse/status && echo '' && curl -fsS http://127.0.0.1/api/v1/ping"
echo ""
echo "=== Deployment to Raspberry Pi (${RASPI_HOST}) Complete! ==="
