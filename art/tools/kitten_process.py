#!/usr/bin/env python3
"""Turn raw Gemini kitten images into game sprites.

Usage (from the pack root):
    python3 tools/process.py                 # every image in raw/
    python3 tools/process.py pounce jump     # only these pose ids
    python3 tools/process.py --full          # also keep full-resolution PNGs

For each raw/<pose_id>.(png|jpg|jpeg|webp) it:
  1. removes the white background (flood fill from the corners, so white fur
     enclosed by the outline is kept),
  2. tints the cream fur into every colour in palette.json, leaving outline,
     eyes, inner ears, nose, collar and bell untouched,
  3. scales to the pose's target height from poses.json,
  4. writes sprites/<pose_id>/kitten_<colour>_<pose_id>.png, plus a mirrored
     _right copy for poses whose "faces" is "left",
  5. writes sprites/<pose_id>/_sheet.png for a quick visual check.

Requires: pip install pillow numpy
"""
import colorsys
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parent.parent
FUR_BASE = np.array([247, 234, 217]) / 255
FUR_SHADOW = np.array([215, 165, 138]) / 255
SHEET_BG = (247, 213, 165, 255)


def cutout(path):
    im = Image.open(path).convert('RGBA')
    bg = Image.new('RGBA', im.size, (255, 255, 255, 255))
    bg.alpha_composite(im)
    im = bg.convert('RGB')
    r, g, b = im.split()
    mn = ImageChops.darker(ImageChops.darker(r, g), b)
    mx = ImageChops.lighter(ImageChops.lighter(r, g), b)
    light = Image.eval(mn, lambda v: 255 if v > 232 else 0)
    neutral = Image.eval(ImageChops.subtract(mx, mn), lambda v: 255 if v < 14 else 0)
    m = ImageChops.multiply(light, neutral)
    w, h = m.size
    for pt in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        if m.getpixel(pt) == 255:
            ImageDraw.floodfill(m, pt, 128)
    alpha = Image.eval(m, lambda v: 0 if v == 128 else 255).filter(ImageFilter.GaussianBlur(1.5))
    alpha = alpha.point(lambda v: 0 if v < 60 else (255 if v > 200 else int((v - 60) * 255 / 140)))
    out = im.convert('RGBA')
    out.putalpha(alpha)
    return out.crop(out.getbbox())


def fur_weights(img):
    a = np.array(img).astype(np.float32)
    rgb = a[..., :3] / 255
    mx, mn = rgb.max(-1), rgb.min(-1)
    v = mx
    s = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1e-6), 0)
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    d = np.maximum(mx - mn, 1e-6)
    h = np.where(mx == r, ((g - b) / d) % 6, np.where(mx == g, (b - r) / d + 2, (r - g) / d + 4)) * 60
    fur = ((h >= 10) & (h <= 42) & (s < 0.42)).astype(np.float32)
    # The bell's highlights fall inside the fur hue range; mask the whole bell
    # by closing over its saturated gold pixels.
    gold = ((h >= 28) & (h <= 55) & (s > 0.42) & (v > 0.5)).astype(np.uint8) * 255
    k = max(5, (min(img.size) // 60) | 1)
    bell = Image.fromarray(gold, 'L').filter(ImageFilter.MaxFilter(k)).filter(ImageFilter.MinFilter(k)).filter(ImageFilter.MaxFilter(5))
    w = fur * (1 - np.array(bell).astype(np.float32) / 255) * np.clip((v - 0.62) / 0.16, 0, 1)
    lum = 0.299 * r + 0.587 * g + 0.114 * b
    return a, rgb, w, lum


def tint(prepped, target):
    a, rgb, w, lum = prepped
    a = a.copy()
    L = lambda c: 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
    t = np.clip((L(FUR_BASE) - lum) / (L(FUR_BASE) - L(FUR_SHADOW)), 0, 1.6)[..., None]
    T = np.array(target) / 255
    hh, ss, vv = colorsys.rgb_to_hsv(*T)
    D = np.array(colorsys.hsv_to_rgb(hh, min(1, ss * 1.35 + 0.08), vv * 0.80))
    out = T + (D - T) * t
    a[..., :3] = np.clip((rgb * (1 - w[..., None]) + out * w[..., None]) * 255, 0, 255)
    return Image.fromarray(a.astype(np.uint8), 'RGBA')


def scaled(im, height):
    s = height / im.height
    return im.resize((max(1, round(im.width * s)), height), Image.LANCZOS)


def sheet(images, path):
    cw = max(i.width for i in images)
    ch = max(i.height for i in images)
    cols = 6
    rows = (len(images) + cols - 1) // cols
    gap = 16
    out = Image.new('RGBA', (gap + cols * (cw + gap), gap + rows * (ch + gap)), SHEET_BG)
    for n, im in enumerate(images):
        x = gap + (n % cols) * (cw + gap) + (cw - im.width) // 2
        y = gap + (n // cols) * (ch + gap) + ch - im.height
        out.alpha_composite(im, (x, y))
    out.convert('RGB').save(path)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    keep_full = '--full' in sys.argv
    poses = {p['id']: p for p in json.loads((ROOT / 'poses.json').read_text())['poses']}
    palette = json.loads((ROOT / 'palette.json').read_text())
    raws = sorted(p for p in (ROOT / 'raw').iterdir() if p.suffix.lower() in ('.png', '.jpg', '.jpeg', '.webp'))
    if args:
        raws = [p for p in raws if p.stem in args]
    if not raws:
        sys.exit('No raw images found. Save Gemini outputs as raw/<pose_id>.png')
    for raw in raws:
        pose = poses.get(raw.stem, {'id': raw.stem, 'faces': 'none', 'height': 256})
        if raw.stem not in poses:
            print(f'warning: {raw.name} is not in poses.json; using height 256, no mirroring')
        out_dir = ROOT / 'sprites' / raw.stem
        out_dir.mkdir(parents=True, exist_ok=True)
        base = cutout(raw)
        prepped = fur_weights(base)
        variants = {'cream': base, **{name: tint(prepped, rgb) for name, rgb in palette.items()}}
        preview = []
        for colour, full in variants.items():
            small = scaled(full, pose['height'])
            names = [(f'kitten_{colour}_{raw.stem}', small, full)]
            if pose.get('faces') == 'left':
                names = [(f'kitten_{colour}_{raw.stem}_left', small, full),
                         (f'kitten_{colour}_{raw.stem}_right', ImageOps.mirror(small), ImageOps.mirror(full))]
            for name, sm, fl in names:
                sm.save(out_dir / f'{name}.png')
                if keep_full:
                    (out_dir / 'full').mkdir(exist_ok=True)
                    fl.save(out_dir / 'full' / f'{name}.png')
            preview.append(small)
        sheet(preview, out_dir / '_sheet.png')
        print(f'{raw.name}: {len(variants)} colours -> {out_dir.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
