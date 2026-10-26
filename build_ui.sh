#!/usr/bin/env bash
# ==============================================================================
# beeJEPA - Frontend Build & Micronaut Integration Script
# Builds the Vue 3 SPA and syncs dist assets to Micronaut's static resources.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="${SCRIPT_DIR}/frontend"
PUBLIC_DIR="${SCRIPT_DIR}/src/main/resources/public"

echo "=== Building beeJEPA Vue 3 Frontend ==="
echo "Frontend directory: ${FRONTEND_DIR}"
echo "Target static resources: ${PUBLIC_DIR}"

if [ ! -d "${FRONTEND_DIR}" ]; then
    echo "ERROR: Frontend directory '${FRONTEND_DIR}' does not exist!"
    exit 1
fi

# Step 1: Run Vite production build in frontend directory
echo ""
echo "-> Running Vite build..."
cd "${FRONTEND_DIR}"
npm run build
cd "${SCRIPT_DIR}"

# Step 2: Clean existing static resources in src/main/resources/public
echo ""
echo "-> Refreshing ${PUBLIC_DIR}..."
rm -rf "${PUBLIC_DIR}"
mkdir -p "${PUBLIC_DIR}"

# Step 3: Copy Vite dist assets into src/main/resources/public
echo "-> Copying dist assets to Micronaut public resources..."
cp -R "${FRONTEND_DIR}/dist/"* "${PUBLIC_DIR}/"

echo ""
echo "=== Frontend Build & Sync Complete! ==="
echo "Assets deployed to ${PUBLIC_DIR}:"
ls -la "${PUBLIC_DIR}"
echo ""
echo "The Vue 3 frontend will now be baked directly into the Micronaut GraalVM native image."
