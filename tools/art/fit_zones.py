#!/usr/bin/env python3
"""Measure how well a painted backdrop follows the layout, using Gemini's traced zone map.

Usage: python3 tools/art/fit_zones.py <painting> <zone-map> [layout-id]
Prints per-zone IoU against the guide and each zone's measured bounding box in dp, and writes
<painting stem>_fit.png: the painting with the guide's zone outlines (thin) and the traced outlines (thick) on top.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

sys.path.insert(0, str(Path(__file__).resolve().parent))
import zone_guide  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
NAMES = list(zone_guide.COLOURS)
PALETTE = np.array([zone_guide.COLOURS[n] for n in NAMES], dtype=np.float32)


def classify(img):
    """Label every pixel with the nearest zone colour (also maps the old grey scenery label to scenery)."""
    a = np.asarray(img.convert("RGB"), dtype=np.float32)
    pal = np.vstack([PALETTE, [[200, 200, 200]]])
    d = ((a[:, :, None, :] - pal[None, None, :, :]) ** 2).sum(-1)
    lab = d.argmin(-1)
    lab[lab == len(NAMES)] = NAMES.index("scenery")
    # JPEG edges leave stray mislabelled pixels; a mode filter removes them.
    return np.asarray(Image.fromarray(lab.astype(np.uint8)).filter(ImageFilter.ModeFilter(9)))


def outline(mask):
    m = Image.fromarray((mask * 255).astype(np.uint8))
    return np.asarray(m.filter(ImageFilter.FIND_EDGES)) > 0


def main(argv):
    painting, zones = Path(argv[1]), Path(argv[2])
    lid = argv[3] if len(argv) > 3 else "default"
    layout = json.loads((ROOT / "art" / "layouts" / f"{lid}.json").read_text(encoding="utf-8"))
    pimg = Image.open(painting).convert("RGB")
    size = pimg.size
    traced = classify(Image.open(zones).resize(size, Image.NEAREST))
    guide = classify(zone_guide.render(layout).resize(size, Image.NEAREST))
    s = size[0] / layout["screen_dp"][0]
    print(f"{'zone':12} {'IoU':>5}   measured bbox (dp)          guide bbox (dp)")
    ious = []
    for k, n in enumerate(NAMES):
        if n == "scenery":
            continue
        t, g = traced == k, guide == k
        if not g.any():
            continue
        iou = (t & g).sum() / max(1, (t | g).sum())
        ious.append(iou)

        def bbox(m):
            ys, xs = np.nonzero(m)
            return "-" if len(xs) == 0 else f"[{xs.min()/s:5.0f},{ys.min()/s:5.0f},{xs.max()/s:5.0f},{ys.max()/s:5.0f}]"
        print(f"{n:12} {iou:5.2f}   {bbox(t):28} {bbox(g)}")
    print(f"mean IoU {np.mean(ious):.2f}")

    out = np.asarray(pimg).copy()
    for k, n in enumerate(NAMES):
        if n == "scenery":
            continue
        col = zone_guide.COLOURS[n]
        out[outline(guide == k)] = (0, 0, 0)
        t = outline(traced == k)
        t = np.asarray(Image.fromarray((t * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))) > 0
        out[t] = col
    dst = painting.with_name(painting.stem + "_fit.png")
    Image.fromarray(out).save(dst)
    print(dst)


if __name__ == "__main__":
    main(sys.argv)
