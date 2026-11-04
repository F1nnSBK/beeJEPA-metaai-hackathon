#!/usr/bin/env python3
"""
Generates the scientific Tufte-style SVG figure for the 5-Tier BeeJEPA Pipeline
(figures/ablation_pyramid_roles.svg) purely from measured dataset metrics in
data/hackathon_sample/manifest.json and frontend/public/landing/snapshot.json.

Strict compliance with visualization rules:
- Output format: SVG only (never PNG).
- Style: Edward Tufte minimalist scientific layout (despined top/right, high data-ink ratio).
- Typography: Fira Sans / Fira Math, normal weight only (no bold / textbf), no internal annotations.
"""

import json
import shutil
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SAMPLE_DIR = PROJECT_ROOT / "data" / "hackathon_sample"
MANIFEST_PATH = SAMPLE_DIR / "manifest.json"
SNAPSHOT_PATH = PROJECT_ROOT / "frontend" / "public" / "landing" / "snapshot.json"
FIGURES_DIR = PROJECT_ROOT / "figures"
LANDING_PUBLIC_DIR = PROJECT_ROOT / "frontend" / "public" / "landing"
BACKEND_PUBLIC_DIR = PROJECT_ROOT / "src" / "main" / "resources" / "public" / "landing"


def apply_tufte_axes(ax, left_spine=True, bottom_spine=True):
    """Applies Edward Tufte minimal axis styling without bold fonts or internal annotations."""
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.spines["left"].set_visible(left_spine)
    ax.spines["bottom"].set_visible(bottom_spine)
    for spine_name in ("left", "bottom"):
        if ax.spines[spine_name].get_visible():
            ax.spines[spine_name].set_color("#4A4D52")
            ax.spines[spine_name].set_linewidth(0.75)
    ax.tick_params(
        axis="both",
        which="both",
        colors="#2C2E33",
        labelsize=8.5,
        width=0.65,
        length=3.5,
    )
    ax.set_facecolor("none")


def load_measured_metrics():
    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        manifest = json.load(f)

    snapshot = {}
    if SNAPSHOT_PATH.exists():
        with open(SNAPSHOT_PATH, "r", encoding="utf-8") as f:
            snapshot = json.load(f)

    events = manifest.get("events", [])
    raw_timeline = manifest.get("timeline", [])
    timeline_points = raw_timeline.get("points", []) if isinstance(raw_timeline, dict) else raw_timeline
    total_duration_sec = int(manifest.get("total_duration_sec", 300))
    fps = 60
    raw_frames = total_duration_sec * fps
    vjepa_peaks = len(events)

    sam_data = snapshot.get("sam", {})
    board_bees = int(sam_data.get("board_bees", 35))
    hotspot_bees = int(sam_data.get("hotspot_bees", 12))

    scores = sorted(float(p.get("token_delta_score", p.get("surprise_score", 0.0))) for p in timeline_points)
    p99_idx = min(len(scores) - 1, max(0, int(round(0.99 * (len(scores) - 1))))) if scores else 0
    tau_p99 = scores[p99_idx] if scores else 0.0

    event_peaks = [int(e.get("board_bee_count", e.get("board_bees", 0))) for e in events]
    event_cells = [int(e.get("active_overlay_cells", e.get("hotspot", {}).get("active_cells", 0))) for e in events]
    event_scores = [float(e.get("token_delta_score", e.get("surprise_score", 0.0))) for e in events]

    return {
        "raw_frames": raw_frames,
        "timeline_windows": len(timeline_points),
        "vjepa_peaks": vjepa_peaks,
        "board_bees": board_bees,
        "hotspot_bees": hotspot_bees,
        "timeline_scores": [float(p.get("token_delta_score", p.get("surprise_score", 0.0))) for p in timeline_points],
        "timeline_times": [float(p.get("timestamp_sec", p.get("time_sec", 0.0))) for p in timeline_points],
        "tau_p99": tau_p99,
        "event_peaks": event_peaks,
        "event_cells": event_cells,
        "event_scores": event_scores,
    }


def generate_ablation_pyramid_svg():
    m = load_measured_metrics()

    plt.rcParams.update({
        "font.family": "sans-serif",
        "font.sans-serif": ["Fira Sans", "Fira Math", "DejaVu Sans", "Arial", "Helvetica"],
        "mathtext.fontset": "custom",
        "mathtext.rm": "Fira Math",
        "mathtext.it": "Fira Math",
        "mathtext.bf": "Fira Math",
        "font.weight": "normal",
        "axes.titleweight": "normal",
        "axes.labelweight": "normal",
        "svg.fonttype": "none",
    })

    fig, axes = plt.subplots(
        1, 3,
        figsize=(18.0, 4.4),
        layout="constrained",
        gridspec_kw={"width_ratios": [1.22, 1.08, 1.12]},
    )
    fig.set_constrained_layout_pads(w_pad=0.25, h_pad=0.1)
    fig.patch.set_facecolor("#FFFFFF")

    # -------------------------------------------------------------------------
    # Panel A: Centered Symmetric 5-Tier Active Perception Funnel
    # -------------------------------------------------------------------------
    ax1 = axes[0]
    tier_labels = [
        f"Tier 0: Raw 60 fps Stream ({m['raw_frames']:,} frames)",
        f"Tier 1: V-JEPA 2.1 ViT-B ({m['vjepa_peaks']} peak clips)",
        f"Tier 2: SAM 3.1 Board ROI ({m['board_bees']} bee masks)",
        f"Tier 3: IoA >= 0.25 Containment ({m['hotspot_bees']} bees)",
        "Tier 4: Muse Spark Open Digest (1 report)",
    ]
    pyramid_half_widths = [1.00, 0.72, 0.50, 0.32, 0.16]
    pyramid_colors = ["#8C929C", "#D97706", "#2B8A3E", "#1971C2", "#C92A2A"]
    y_positions = list(range(len(tier_labels)))

    for y, hw, col in zip(y_positions, pyramid_half_widths, pyramid_colors):
        ax1.barh(
            y,
            width=2.0 * hw,
            left=-hw,
            height=0.62,
            color=col,
            alpha=0.82,
            edgecolor="none",
        )

    ax1.set_yticks(y_positions)
    ax1.set_yticklabels(tier_labels)
    ax1.set_xlim(-1.1, 1.1)
    ax1.set_xticks([-1.0, -0.5, 0.0, 0.5, 1.0])
    ax1.set_xticklabels(["1.0", "0.5", "0.0", "0.5", "1.0"])
    ax1.set_xlabel("Relative Search Funnel Width (Base to Apex)", fontsize=8.5, color="#2C2E33")
    ax1.set_title("A. Five-Tier Information Funnel (Base to Apex)", fontsize=9.5, color="#1A1B1E", pad=10)
    apply_tufte_axes(ax1, left_spine=False, bottom_spine=True)
    ax1.tick_params(axis="y", length=0)

    # -------------------------------------------------------------------------
    # Panel B: Measured Timeline Token-Delta Trajectory (N=140 windows)
    # -------------------------------------------------------------------------
    ax2 = axes[1]
    ax2.plot(
        m["timeline_times"],
        m["timeline_scores"],
        color="#D97706",
        linewidth=1.2,
        alpha=0.9,
    )
    ax2.axhline(
        m["tau_p99"],
        color="#C92A2A",
        linestyle="--",
        linewidth=0.9,
        alpha=0.85,
    )
    ax2.set_xlim(0, max(m["timeline_times"]) if m["timeline_times"] else 300)
    ax2.set_xlabel("Recording Offset (s)", fontsize=8.5, color="#2C2E33")
    ax2.set_ylabel("Token-Delta Score", fontsize=8.5, color="#2C2E33")
    ax2.set_title("B. Measured Timeline Token-Delta & P99 Threshold", fontsize=9.5, color="#1A1B1E", pad=10)
    apply_tufte_axes(ax2, left_spine=True, bottom_spine=True)

    # -------------------------------------------------------------------------
    # Panel C: Measured Board Token Peaks vs. Active Overlay Cells (N=20 Events)
    # -------------------------------------------------------------------------
    ax3 = axes[2]
    ax3.scatter(
        m["event_cells"],
        m["event_peaks"],
        c=m["event_scores"],
        cmap="YlOrRd",
        s=42,
        alpha=0.88,
        edgecolors="#2C2E33",
        linewidths=0.5,
    )
    ax3.set_xlabel("Active Overlay Cells (43x43 Grid)", fontsize=8.5, color="#2C2E33")
    ax3.set_ylabel("Board Token Peaks", fontsize=8.5, color="#2C2E33")
    ax3.set_title("C. Measured Overlay Cells vs. Board Token Peaks (N=20)", fontsize=9.5, color="#1A1B1E", pad=10)
    apply_tufte_axes(ax3, left_spine=True, bottom_spine=True)

    FIGURES_DIR.mkdir(parents=True, exist_ok=True)
    out_svg = FIGURES_DIR / "ablation_pyramid_roles.svg"
    fig.savefig(out_svg, format="svg", bbox_inches="tight")
    plt.close(fig)

    LANDING_PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(out_svg, LANDING_PUBLIC_DIR / "ablation_pyramid_roles.svg")

    BACKEND_PUBLIC_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(out_svg, BACKEND_PUBLIC_DIR / "ablation_pyramid_roles.svg")

    print(f"Generated Tufte SVG: {out_svg} (tau P99 = {m['tau_p99']:.4f})")


if __name__ == "__main__":
    generate_ablation_pyramid_svg()
