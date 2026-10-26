#!/usr/bin/env bash
# ==============================================================================
# beeJEPA - Nginx Installation & Activation Script for Raspberry Pi
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONF_SRC="${PROJECT_ROOT}/nginx/beejepa.conf"
SITES_AVAILABLE="/etc/nginx/sites-available"
SITES_ENABLED="/etc/nginx/sites-enabled"

echo "=== Deploying beeJEPA Nginx Configuration ==="

if [ "$EUID" -ne 0 ]; then
    echo "Please run as root or with sudo:"
    echo "  sudo $0"
    exit 1
fi

if [ ! -d "${SITES_AVAILABLE}" ]; then
    echo "Directory ${SITES_AVAILABLE} not found. Creating..."
    mkdir -p "${SITES_AVAILABLE}"
    mkdir -p "${SITES_ENABLED}"
fi

echo "Copying config to ${SITES_AVAILABLE}/beejepa.conf..."
cp "${CONF_SRC}" "${SITES_AVAILABLE}/beejepa.conf"

echo "Enabling site in ${SITES_ENABLED}..."
rm -f "${SITES_ENABLED}/default"
ln -sf "${SITES_AVAILABLE}/beejepa.conf" "${SITES_ENABLED}/beejepa.conf"

echo "Testing Nginx syntax..."
nginx -t

echo "Reloading Nginx..."
systemctl reload nginx || systemctl restart nginx

echo ""
echo "=== Nginx deployed successfully! ==="
echo "Domain: beejepa.finn-hertsch.de"
echo "Upstream: http://127.0.0.1:8080"
