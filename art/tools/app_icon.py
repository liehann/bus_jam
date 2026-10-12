#!/usr/bin/env python3
"""Critter Café launcher icon (adaptive, API 26+), from the chibi kitten reference.

Foreground: the pink kitten's head and shoulders (cut out of art/raw/kitten/kitten_front.jpg, tinted like the game
sprites), 56 dp wide with the ear tips 24 dp from the top of the 108 dp layer; the cut through the body falls below
every launcher mask. Background: a colour (values/ic_launcher_background.xml).
Monochrome: the foreground's silhouette, for themed icons. Writes mipmap-<density>/ic_launcher_{foreground,monochrome}.png.
Run from the repo root: python3 art/tools/app_icon.py
"""
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kitten_process import cutout, fur_weights, tint  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
PINK = (254, 176, 197)
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def head():
    fur = [round(c + (255 - c) * 0.3) for c in PINK]
    kitten = tint(fur_weights(cutout(ROOT / 'art/raw/kitten/kitten_front.jpg')), fur)
    w, h = kitten.size
    part = kitten.crop((0, 0, w, round(h * 0.80)))
    return part.crop(part.getbbox())


def main():
    hd = head()
    big = 108 * 4  # xxxhdpi layer
    top = 24 * 4  # ear tips just inside a circle mask (72 dp across)
    width = 56 * 4
    s = width / hd.width
    hd = hd.resize((round(hd.width * s), round(hd.height * s)), Image.LANCZOS)
    layer = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    assert top + hd.height * 1.0 > 94 * 4, 'the cut must sit below every mask'
    layer.alpha_composite(hd, ((big - hd.width) // 2, top))
    mono = Image.new('RGBA', layer.size, (255, 255, 255, 0))
    mono.putalpha(layer.getchannel('A'))
    for name, k in DENSITIES.items():
        d = RES / f'mipmap-{name}'
        d.mkdir(parents=True, exist_ok=True)
        n = round(108 * k)
        layer.resize((n, n), Image.LANCZOS).save(d / 'ic_launcher_foreground.png', optimize=True)
        mono.resize((n, n), Image.LANCZOS).save(d / 'ic_launcher_monochrome.png', optimize=True)
    # preview: the layers under a circle and a rounded-square mask
    bg = Image.new('RGBA', layer.size, (0xC9, 0xEA, 0xD1, 255))
    bg.alpha_composite(layer)
    bg.save(ROOT / 'app/build/ic_launcher_preview.png')
    print('icon written')


if __name__ == '__main__':
    main()
