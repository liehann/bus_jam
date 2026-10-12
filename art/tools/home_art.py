#!/usr/bin/env python3
"""Critter Café home art for the app, from the Gemini originals in art/raw/home/.

hero.jpeg -> app/src/main/res/drawable-nodpi/critter_home_hero.webp: trimmed, 1080 px wide, edges faded to
transparent so the scene blends into the home screen.
logo.jpeg -> critter_logo.png: white background removed (flood fill from the corners), trimmed, 1000 px wide.
Run from the repo root: python3 art/tools/home_art.py
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / 'art/raw/home'
OUT = ROOT / 'app/src/main/res/drawable-nodpi'


def hero():
    im = Image.open(RAW / 'hero.jpeg').convert('RGB')
    w0, h0 = im.size
    im = im.crop((0, round(h0 * 0.12), w0, round(h0 * 0.93)))  # the empty wall above the lantern, the floor below
    im = im.resize((1080, round(im.height * 1080 / im.width)), Image.LANCZOS)
    h, w = im.height, im.width
    y, x = np.mgrid[0:h, 0:w]
    # oval vignette: opaque inside, fading to transparent at the edges (a squared superellipse keeps the corners soft)
    nx = (x - w / 2) / (w / 2)
    ny = (y - h / 2) / (h / 2)
    r = (nx ** 4 + ny ** 4) ** 0.25
    k = np.clip((1.0 - r) / 0.22, 0, 1)
    k = k * k * (3 - 2 * k)
    out = im.convert('RGBA')
    out.putalpha(Image.fromarray((k * 255).astype(np.uint8)))
    out.save(OUT / 'critter_home_hero.webp', quality=88, method=6)


def logo():
    src = RAW / 'logo.jpeg'
    if not src.exists():
        return
    im = Image.open(src).convert('RGB')
    r, g, b = im.split()
    mn = ImageChops.darker(ImageChops.darker(r, g), b)
    mask = Image.eval(mn, lambda v: 255 if v > 236 else 0)
    w, h = mask.size
    for pt in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]:
        if mask.getpixel(pt) == 255:
            ImageDraw.floodfill(mask, pt, 128)
    alpha = Image.eval(mask, lambda v: 0 if v == 128 else 255).filter(ImageFilter.GaussianBlur(1.2))
    out = im.convert('RGBA')
    out.putalpha(alpha)
    out = out.crop(out.getbbox())
    out = out.resize((1000, round(out.height * 1000 / out.width)), Image.LANCZOS)
    out.save(OUT / 'critter_logo.png', optimize=True)


if __name__ == '__main__':
    hero()
    logo()
    print('home art written', file=sys.stderr)
