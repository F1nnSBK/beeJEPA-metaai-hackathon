"""Build the landing page data snapshot from real hackathon_sample artifacts.

Every value written by this script is read or measured from files in
data/hackathon_sample. Nothing is generated, interpolated or invented:

- timeline and events      -> manifest.json
- keyframe (camera view)   -> event_frames/<focus_event>.jpg, left panel
- V-JEPA attention map     -> same keyframe, right panel minus left panel
- SAM 3.1 detections       -> cache/sam_spatial_<focus_event>.json
- Muse Spark statement     -> cache/muse_responses.json

Usage:
    .venv/bin/python scripts/build_landing_snapshot.py
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SAMPLE_DIR = PROJECT_ROOT / "data" / "hackathon_sample"
OUTPUT_DIR = PROJECT_ROOT / "frontend" / "public" / "landing"

FOCUS_EVENT_ID = "evt_rec2125_seq000_f1217"
HEADER_LUMINANCE_THRESHOLD = 40.0
HEADER_MIN_BRIGHT_RUN = 24
HEAT_NOISE_FLOOR = 36.0
HEAT_REFERENCE_PERCENTILE = 97.0
KEYFRAME_WEBP_QUALITY = 82


@dataclass(frozen=True)
class PanelGeometry:
    header_height: int
    panel_width: int
    panel_height: int


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def detect_header_height(frame: np.ndarray) -> int:
    """Return the height of the dark caption bar burned into the top of the frame.

    The caption text inside the bar produces a few bright rows, so the camera
    panel is defined as the first row that starts a sustained run of bright rows.
    """
    bright_rows = frame.mean(axis=(1, 2)) > HEADER_LUMINANCE_THRESHOLD
    for row_index in range(len(bright_rows) - HEADER_MIN_BRIGHT_RUN):
        if bright_rows[row_index : row_index + HEADER_MIN_BRIGHT_RUN].all():
            return row_index
    raise ValueError("Keyframe contains no sustained bright region; cannot locate camera panel.")


def split_panels(frame: np.ndarray) -> tuple[np.ndarray, np.ndarray, PanelGeometry]:
    header_height = detect_header_height(frame)
    panel_width = frame.shape[1] // 2
    camera_panel = frame[header_height:, :panel_width]
    attention_panel = frame[header_height:, panel_width : panel_width * 2]
    geometry = PanelGeometry(header_height, panel_width, camera_panel.shape[0])
    return camera_panel, attention_panel, geometry


def extract_attention_map(camera_panel: np.ndarray, attention_panel: np.ndarray) -> np.ndarray:
    """Isolate the V-JEPA overlay as the per-pixel difference between both panels."""
    difference = np.abs(attention_panel.astype(np.int16) - camera_panel.astype(np.int16)).sum(axis=2)
    heat = np.clip(difference.astype(np.float32) - HEAT_NOISE_FLOOR, 0.0, None)
    smoothed = Image.fromarray(np.clip(heat, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2))
    return np.asarray(smoothed, dtype=np.float32)


def normalize_for_display(heat: np.ndarray) -> np.ndarray:
    """Scale against a high percentile so weaker attention cells stay visible."""
    active = heat[heat > 0]
    if active.size == 0:
        return heat
    reference = float(np.percentile(active, HEAT_REFERENCE_PERCENTILE))
    return np.clip(heat / reference, 0.0, 1.0)


def describe_attention(heat: np.ndarray) -> dict:
    rows, cols = np.indices(heat.shape)
    total = float(heat.sum())
    centroid_x = float((cols * heat).sum() / total)
    centroid_y = float((rows * heat).sum() / total)
    peak_y, peak_x = np.unravel_index(int(np.argmax(heat)), heat.shape)
    return {
        "centroid": [round(centroid_x, 1), round(centroid_y, 1)],
        "peak": [int(peak_x), int(peak_y)],
        "activeFraction": round(float((normalize_for_display(heat) > 0.2).mean()), 4),
    }


def extract_muse_statement(muse_cache: dict) -> str:
    report = muse_cache["default"]
    takeaway = report.split("### Beekeeper Takeaway", maxsplit=1)[1].strip()
    sentences = re.split(r"(?<=\.)\s+", takeaway)
    selected = [s for s in sentences if s.startswith("Zero") or s.startswith("No beekeeper")]
    if not selected:
        raise ValueError("Muse cache does not contain the expected takeaway sentences.")
    return " ".join(selected)


def shift_boxes_into_panel(sam_summary: dict, geometry: PanelGeometry) -> list[list[int]]:
    boxes = []
    for bee in sam_summary["containedBees"]:
        x1, y1, x2, y2 = bee["box"]
        if x2 > geometry.panel_width:
            raise ValueError(f"SAM box {bee['box']} lies outside the camera panel.")
        boxes.append([x1, y1 - geometry.header_height, x2, y2 - geometry.header_height])
    return boxes


def shift_board_boxes_into_panel(sam_summary: dict, geometry: PanelGeometry) -> list[list[int]]:
    boxes = []
    for raw_box in sam_summary.get("boardBees", []):
        x1, y1, x2, y2 = raw_box
        if x2 <= geometry.panel_width:
            boxes.append([x1, y1 - geometry.header_height, x2, y2 - geometry.header_height])
    return boxes


def build_snapshot() -> dict:
    manifest = load_json(SAMPLE_DIR / "manifest.json")
    sam_summary = load_json(SAMPLE_DIR / "cache" / f"sam_spatial_{FOCUS_EVENT_ID}.json")
    muse_cache = load_json(SAMPLE_DIR / "cache" / "muse_responses.json")
    focus_event = next(e for e in manifest["events"] if e["event_id"] == FOCUS_EVENT_ID)

    frame = np.asarray(Image.open(SAMPLE_DIR / "event_frames" / f"{FOCUS_EVENT_ID}.jpg").convert("RGB"))
    camera_panel, attention_panel, geometry = split_panels(frame)
    heat = extract_attention_map(camera_panel, attention_panel)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    Image.fromarray(camera_panel).save(OUTPUT_DIR / "keyframe.webp", quality=KEYFRAME_WEBP_QUALITY, method=6)
    Image.fromarray((normalize_for_display(heat) * 255).astype(np.uint8), mode="L").save(
        OUTPUT_DIR / "attention.png", optimize=True
    )

    return {
        "source": {
            "dataset": manifest["dataset"],
            "fps": manifest["fps"],
            "durationSec": manifest["total_duration_sec"],
            "tau": manifest["decision_threshold_tau"],
            "sequenceCount": manifest["summary"]["sequences_count"],
            "eventCount": manifest["summary"]["events_count"],
            "maxSurprise": manifest["summary"]["max_surprise_overall"],
        },
        "timeline": [[p["time_sec"], p["surprise_score"]] for p in manifest["timeline"]],
        "events": [
            {"id": e["event_id"], "t": e["time_sec_global"], "s": e["surprise_score"], "severity": e["severity"]}
            for e in manifest["events"]
        ],
        "focus": {
            "eventId": FOCUS_EVENT_ID,
            "formattedTime": focus_event["formatted_time"],
            "timeSec": focus_event["time_sec_global"],
            "frameIdx": focus_event["frame_approx"],
            "surprise": focus_event["surprise_score"],
            "boardBeesVjepaDetector": focus_event["board_bees"],
            "boardBeesSam": sam_summary["landingBoardBeeCount"],
            "hotspotBeesSam": sam_summary["beesInsideHotspotCount"],
            "classification": sam_summary["geometricPreClassification"],
            "frame": {"width": geometry.panel_width, "height": geometry.panel_height},
            "samBoxes": shift_boxes_into_panel(sam_summary, geometry),
            "boardBoxes": shift_board_boxes_into_panel(sam_summary, geometry),
            "attention": describe_attention(heat),
        },
        "muse": {
            "model": "meta-models/muse-spark-open-1026",
            "statement": extract_muse_statement(muse_cache),
        },
    }


def main() -> None:
    snapshot = build_snapshot()
    target = OUTPUT_DIR / "snapshot.json"
    target.write_text(json.dumps(snapshot, separators=(",", ":")), encoding="utf-8")
    focus = snapshot["focus"]
    print(f"Wrote {target.relative_to(PROJECT_ROOT)}")
    print(f"  timeline points : {len(snapshot['timeline'])}")
    print(f"  events          : {len(snapshot['events'])}")
    print(f"  camera panel    : {focus['frame']['width']}x{focus['frame']['height']}")
    print(f"  SAM boxes       : {len(focus['samBoxes'])}")
    print(f"  attention       : {focus['attention']}")
    print(f"  muse statement  : {snapshot['muse']['statement']}")


if __name__ == "__main__":
    main()
