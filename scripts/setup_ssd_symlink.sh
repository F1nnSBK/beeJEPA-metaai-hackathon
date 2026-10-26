#!/usr/bin/env bash
# ==============================================================================
# beeJEPA - SSD Symlink Setup Utility
# Symlinks the local `data` directory to an external SSD mount point.
# Compatible with macOS (/Volumes/...) and Linux/Raspberry Pi (/mnt/... or /media/...)
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
DATA_LINK="${PROJECT_ROOT}/data"

echo "=== beeJEPA External SSD Symlink Setup ==="
echo "Project Root: ${PROJECT_ROOT}"

if [ $# -eq 0 ]; then
    echo ""
    echo "Usage: $0 <TARGET_SSD_PATH>"
    echo ""
    echo "Examples:"
    echo "  macOS:        $0 /Volumes/BeeSSD/beejepa_data"
    echo "  Raspberry Pi: $0 /mnt/ssd/beejepa_data"
    echo "  Raspberry Pi: $0 /media/pi/BeeSSD/beejepa_data"
    echo ""
    exit 1
fi

TARGET_SSD="$1"

echo "Target SSD Path: ${TARGET_SSD}"

# Ensure parent directory of target exists
if [ ! -d "$(dirname "${TARGET_SSD}")" ]; then
    echo "WARNING: Parent directory $(dirname "${TARGET_SSD}") does not exist."
    echo "Please ensure your SSD is plugged in and properly mounted."
    read -p "Create directory anyway? (y/N): " -r CONFIRM
    if [[ ! "${CONFIRM}" =~ ^[Yy]$ ]]; then
        echo "Aborted."
        exit 1
    fi
fi

# Create target directory on SSD if it does not exist
mkdir -p "${TARGET_SSD}"
mkdir -p "${TARGET_SSD}/models"
mkdir -p "${TARGET_SSD}/embeddings"
mkdir -p "${TARGET_SSD}/captures"
mkdir -p "${TARGET_SSD}/runs"

# If data exists as a symlink
if [ -L "${DATA_LINK}" ]; then
    CURRENT_TARGET="$(readlink "${DATA_LINK}")"
    echo "Existing symlink found: ${DATA_LINK} -> ${CURRENT_TARGET}"
    if [ "${CURRENT_TARGET}" = "${TARGET_SSD}" ]; then
        echo "Symlink already points to ${TARGET_SSD}. Nothing to change."
        exit 0
    fi
    echo "Updating symlink to point to ${TARGET_SSD}..."
    rm "${DATA_LINK}"
# If data exists as a regular directory
elif [ -d "${DATA_LINK}" ]; then
    echo "Existing local directory '${DATA_LINK}' detected."
    echo "Migrating existing data (including SQLite state.db) to ${TARGET_SSD}..."
    cp -rn "${DATA_LINK}/"* "${TARGET_SSD}/" 2>/dev/null || true
    echo "Backing up local directory to '${DATA_LINK}.backup'..."
    mv "${DATA_LINK}" "${DATA_LINK}.backup"
fi

# Create symlink
ln -s "${TARGET_SSD}" "${DATA_LINK}"

echo ""
echo "SUCCESS: Symlink created!"
ls -ld "${DATA_LINK}"
echo "Target Real Path: $(cd -P "${DATA_LINK}" && pwd)"
echo ""
echo "Storage check:"
df -h "${DATA_LINK}"
echo ""
echo "beeJEPA will now store all frames, models, embeddings and state.db directly on the SSD."
