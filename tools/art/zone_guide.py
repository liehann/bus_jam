#!/usr/bin/env python3
"""Render a flat colour-zone guide image from a layout file (art/layouts/<id>.json).

The guide is attached to an image-generation prompt so the painted scene puts each element where the game
expects it. Usage: python3 tools/art/zone_guide.py [layout-id]   (default: default)
Writes art/guides/<id>_zones.png (full Pixel 9 Pro size) and <id>_zones_small.png (a quarter size, for upload).
"""
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]

# One flat, saturated colour per zone. The same table is used by the prompts and by the fitting step.
COLOURS = {
    "scenery": (128, 128, 0),        # olive: free area for theme decorations (grey read as "leave empty" in v1/v2)
    "top_bar": (255, 255, 255),      # white: plain area, the app draws the UI here
    "kitchen": (255, 140, 0),        # orange
    "belt": (255, 0, 255),           # magenta: both the incoming belt and the loop band
    "loop_inside": (0, 200, 255),    # cyan
    "counter": (255, 215, 0),        # yellow
    "bench": (224, 0, 42),           # red
    "holder": (128, 0, 255),         # purple
    "board": (0, 176, 80),           # green
}


def render(layout):
    w_dp, h_dp = layout["screen_dp"]
    w_px, h_px = layout["screen_px"]
    s = w_px / w_dp
    z = layout["zones"]
    img = Image.new("RGB", (w_px, h_px), COLOURS["scenery"])
    d = ImageDraw.Draw(img)

    def box(r):
        return [round(v * s) for v in r]

    d.rectangle(box(z["top_bar"]["rect"]), fill=COLOURS["top_bar"])
    if "status_bar" in z:
        d.rectangle(box(z["status_bar"]["rect"]), fill=COLOURS["top_bar"])
    d.rectangle(box(z["counter"]["rect"]), fill=COLOURS["counter"])
    for r in z["benches"]["rects"]:
        d.rounded_rectangle(box(r), radius=round(6 * s), fill=COLOURS["bench"])
    for r in z["holders"]["rects"]:
        d.rounded_rectangle(box(r), radius=round(3 * s), fill=COLOURS["holder"])

    # Loop: outer stadium filled with belt colour, inner stadium with loop_inside.
    x0, y0, x1, y1 = z["loop"]["stadium"]
    band = z["loop"]["band"]
    d.rounded_rectangle(box([x0, y0, x1, y1]), radius=round((y1 - y0) / 2 * s), fill=COLOURS["belt"])
    d.rounded_rectangle(box([x0 + band, y0 + band, x1 - band, y1 - band]),
                        radius=round((y1 - y0 - 2 * band) / 2 * s), fill=COLOURS["loop_inside"])

    # Incoming belt: a thick polyline with round joints.
    qb = z["queue_belt"]
    pts = [(round(x * s), round(y * s)) for x, y in qb["path"]]
    width = round(qb["width"] * s)
    d.line(pts, fill=COLOURS["belt"], width=width, joint="curve")
    for p in pts[1:-1]:
        r = width // 2
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=COLOURS["belt"])

    d.rounded_rectangle(box(z["kitchen"]["rect"]), radius=round(4 * s), fill=COLOURS["kitchen"])

    # The board itself is drawn by the app; the painting only supplies a calm floor here (no frame colour,
    # which v1 left behind as a dark green border).
    d.rectangle(box(z["board"]["rect"]), fill=COLOURS["board"])
    return img


def main(argv):
    lid = argv[1] if len(argv) > 1 else "default"
    layout = json.loads((ROOT / "art" / "layouts" / f"{lid}.json").read_text(encoding="utf-8"))
    img = render(layout)
    out = ROOT / "art" / "guides"
    out.mkdir(parents=True, exist_ok=True)
    img.save(out / f"{lid}_zones.png")
    img.resize((img.width // 2, img.height // 2), Image.NEAREST).save(out / f"{lid}_zones_small.png")
    # The painted part only: everything above the board. The board, the floor around it and the bottom margin
    # are assembled by the app from tiled textures (v1-v3 kept inventing a second room in that big empty area).
    if "paint_region" in layout:
        s = layout["screen_px"][0] / layout["screen_dp"][0]
        x0, y0, x1, y1 = [round(v * s) for v in layout["paint_region"]]
        top = img.crop((x0, y0, x1, y1))
        top.save(out / f"{lid}_zones_top.png")
    print(out / f"{lid}_zones.png")


if __name__ == "__main__":
    main(sys.argv)
