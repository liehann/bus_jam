#!/usr/bin/env python3
"""Critter Café kitten sprites for the app, from the chibi kitten pack (Downloads/kitten_jumble_pack.zip).

Cuts the cream kitten out of art/raw/kitten/kitten_{front,left}.jpg, tints it into the game palette (the colours
in CritterPalette.kt, lightened) and writes app/src/main/res/drawable-nodpi/critter_kitten_<colour>_{front,right}.png
(the right view is the left reference mirrored). Run from the repo root: python3 art/tools/critter_sprites.py
"""
import re
import sys
from pathlib import Path

from PIL import ImageOps

sys.path.insert(0, str(Path(__file__).resolve().parent))
from kitten_process import cutout, fur_weights, scaled, sheet, tint  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / 'art/raw/kitten'
OUT = ROOT / 'app/src/main/res/drawable-nodpi'
PALETTE_KT = ROOT / 'app/src/main/kotlin/com/phloots/busjam/ui/skin/critter/CritterPalette.kt'
HEIGHT = 192
LIGHTEN = 0.3  # fur is the ticket colour lifted toward white, so faces stay readable on the dark colours


def palette():
    src = PALETTE_KT.read_text()
    block = src[src.index('val colors = listOf('):src.index('val names')]
    found = re.findall(r'Color\(0xFF([0-9A-Fa-f]{6})\), // (\w+)', block)
    return {name: tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for h, name in found}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    pal = palette()
    preview = []
    for view, mirror in (('front', False), ('right', True)):
        base = cutout(RAW / f'kitten_{"left" if mirror else view}.jpg')
        prepped = fur_weights(base)
        for name, rgb in pal.items():
            fur = [round(c + (255 - c) * LIGHTEN) for c in rgb]
            im = scaled(tint(prepped, fur), HEIGHT)
            if mirror:
                im = ImageOps.mirror(im)
            im.save(OUT / f'critter_kitten_{name}_{view}.png', optimize=True)
            preview.append(im)
    sheet(preview, ROOT / 'app/build/critter_kitten_sheet.png')
    print(f'{len(preview)} sprites -> {OUT.relative_to(ROOT)}')


if __name__ == '__main__':
    main()
