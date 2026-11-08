#!/usr/bin/env python3
"""Labelled comparison sheets: every tile carries a coloured banner naming where it came from.

usage: sheet.py SPEC.json OUT.jpg
SPEC = {"title": str, "subtitle": str, "cols": int, "tile": [w, h],
        "rows": [{"label": str, "tiles": [{"src": path, "kind": "gemini|bazzite|goblin", "caption": str}]}]}
Needs ImageMagick (convert) and the DejaVu fonts.
"""
import json, subprocess, sys, tempfile, os

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
KIND = {  # banner colour, banner text
    "gemini": ("#c8742c", "GEMINI"),
    "bazzite": ("#2a7f8f", "BAZZITE  ·  FLUX.2 klein"),
    "zimage": ("#5a6fa8", "BAZZITE  ·  Z-Image"),
    "goblin": ("#6b6b6b", "OLD LOCAL  ·  Kontext"),
}


def sh(*a):
    subprocess.run(a, check=True)


def tile(t, w, h, out):
    colour, banner = KIND[t["kind"]]
    sh("convert", t["src"], "-resize", f"{w - 16}x{h - 16}", "-background", "white", "-gravity", "center",
       "-extent", f"{w - 16}x{h - 16}", "-bordercolor", colour, "-border", "8",
       "(", "-size", f"{w}x40", f"xc:{colour}", "-font", BOLD, "-pointsize", "20", "-fill", "white",
       "-gravity", "center", "-annotate", "+0+0", banner, ")", "+swap", "-append",
       "(", "-size", f"{w}x56", "xc:#f6f1e8", "-font", FONT, "-pointsize", "18", "-fill", "#3a2a20",
       "-gravity", "center", "-annotate", "+0+0", t.get("caption", ""), ")", "-append", out)


def main():
    spec = json.load(open(sys.argv[1]))
    w, h = spec.get("tile", [360, 360])
    cols = spec.get("cols", 4)
    tmp = tempfile.mkdtemp()
    rows = []
    for i, r in enumerate(spec["rows"]):
        files = []
        for j, t in enumerate(r["tiles"]):
            f = os.path.join(tmp, f"t{i}_{j}.png")
            tile(t, w, h, f)
            files.append(f)
        rf = os.path.join(tmp, f"r{i}.png")
        sh("montage", *files, "-tile", f"{cols}x", "-geometry", "+8+8", "-background", "#f6f1e8", rf)
        if r.get("label"):
            lf = os.path.join(tmp, f"l{i}.png")
            sh("convert", "-background", "#f6f1e8", "-fill", "#3a2a20", "-font", BOLD, "-pointsize", "26",
               "label:  " + r["label"], "-bordercolor", "#f6f1e8", "-border", "0x10", lf)
            sh("convert", lf, rf, "-background", "#f6f1e8", "-gravity", "west", "-append", rf)
        rows.append(rf)
    body = os.path.join(tmp, "body.png")
    sh("convert", *rows, "-background", "#f6f1e8", "-gravity", "west", "-append", body)
    bw = int(subprocess.run(["identify", "-format", "%w", body], capture_output=True, text=True).stdout)
    legend = "   ".join(f"■ {v[1]}" for k, v in KIND.items() if any(t["kind"] == k for r in spec["rows"] for t in r["tiles"]))
    sh("convert", "(", "-size", f"{bw}x70", "xc:#3a2a20", "-font", BOLD, "-pointsize", "34", "-fill", "white",
       "-gravity", "west", "-annotate", "+20+0", spec["title"], ")",
       "(", "-size", f"{bw}x44", "xc:#efe4d2", "-font", FONT, "-pointsize", "20", "-fill", "#3a2a20",
       "-gravity", "west", "-annotate", "+20+0", spec.get("subtitle", ""), ")",
       body, "-append", "-quality", "82", sys.argv[2])
    print(sys.argv[2])


if __name__ == "__main__":
    main()
