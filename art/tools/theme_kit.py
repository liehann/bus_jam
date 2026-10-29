#!/usr/bin/env python3
"""Critter Café theme kit: turns the Gemini pieces in art/raw/<theme>/ into the game's drawables.

The pieces come from the prompts in art/prompts/generated/<theme>_kit.md (python3 tools/art/build_prompt.py --kit
<theme>); what the app does with each is in docs/ART-PIPELINE.md, "Level themes". This script:
- backdrop, loop: resized (the backdrop to the phone's width);
- kitchen, bench: cut out of their white background;
- belt, counter, frame: the strip is cropped out of the white margin (and off its end caps when it stops short of
  the image edges), its lighting evened out along its length, and
  its ends cross-faded so it tiles (the app bends belt and frame strips along a path, and repeats the counter);
- mat: made seamless in both directions and flattened towards its mean colour so the cards stand out;
- props: the sheet is cut into one sprite per grid cell, named prop_<name>: by default a 4 x 2 grid in PROPS order
  (the kit prompt's order); art/raw/<theme>/props.json ({"grid": [cols, rows], "names": [...]}, null to skip a
  cell) describes a sheet laid out differently.
A missing piece is skipped with a message (the app draws its vector version instead).

Writes app/src/main/res/drawable-nodpi/kit_<theme>_<piece>.webp (replacing the theme's earlier outputs) and a
preview at app/build/kit_<theme>.png. Run from the repo root:
    python3 art/tools/theme_kit.py luau_night [more themes]
    python3 art/tools/theme_kit.py --all        # every art/raw/<theme> with a theme file and at least one piece
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'app/src/main/res/drawable-nodpi'
PIECES = ['backdrop', 'kitchen', 'belt', 'loop', 'counter', 'bench', 'mat', 'frame', 'props']
PROPS = ['stand', 'corner', 'obstacle_1', 'obstacle_2', 'obstacle_3', 'decor_1', 'decor_2', 'decor_3']
BACKDROP_W = 1080
STRIP_H = {'belt': 96, 'counter': 160, 'frame': 64}
MAT_SIZE = 512
MAT_FLATTEN = 0.45  # how far the mat moves towards its mean colour
ENCLOSED_WHITE = 245  # an enclosed light patch this white is background, not a pale part of the sprite
END_TRIM = 0.06  # of a strip's length, cut from each end when it doesn't reach the image edges


def cutout(path):
    """Keys out the white background: the light, neutral pixels reachable from the image border, plus enclosed
    patches of near-pure white (background showing through a gap, such as under a bench seat or between a cart
    wheel's spokes). Pale parts of the sprite (steam, cloth, white slats) are kept: an enclosed patch is keyed only
    when its mean darkest channel is at least ENCLOSED_WHITE."""
    im = Image.open(path).convert('RGB')
    a = np.asarray(im).astype(np.int16)
    mn, mx = a.min(-1), a.max(-1)
    key = (mn > 232) & (mx - mn < 14)
    lab, n = ndimage.label(key)
    sizes = ndimage.sum(key, lab, np.arange(n + 1))
    whiteness = ndimage.mean(mn, lab, np.arange(n + 1))
    border = np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]]))
    drop = whiteness >= ENCLOSED_WHITE
    drop[border] = True
    drop[0] = False
    drop &= sizes > 0
    gone = drop[lab]
    alpha = Image.fromarray(np.where(gone, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))
    alpha = alpha.point(lambda v: 0 if v < 60 else (255 if v > 200 else int((v - 60) * 255 / 140)))
    out = im.convert('RGBA')
    out.putalpha(alpha)
    return out.crop(out.getbbox())


def raw(theme, piece):
    """The raw piece's path, or None when it has not been generated yet."""
    found = [p for p in sorted((ROOT / 'art/raw' / theme).glob(f'{piece}.*')) if p.suffix in ('.jpg', '.jpeg', '.png', '.webp')]
    return found[0] if found else None


def save(im, theme, piece):
    path = OUT / f'kit_{theme}_{piece}.webp'
    lossless = im.mode == 'RGBA'
    im.save(path, 'WEBP', quality=88, method=6, lossless=False, exact=lossless)
    return im


def fit_width(im, w):
    return im.resize((w, round(im.height * w / im.width)), Image.LANCZOS)


def fit_height(im, h):
    return im.resize((round(im.width * h / im.height), h), Image.LANCZOS)


def seam_x(a, frac=0.06):
    """Cross-fades the right end into the left so the columns tile: returns width W - k."""
    w = a.shape[1]
    k = max(8, int(w * frac))
    out = a[:, :w - k].copy()
    t = np.linspace(0, 1, k, dtype=np.float32)[None, :, None]
    out[:, :k] = a[:, w - k:] * (1 - t) + a[:, :k] * t
    return out


def even_light(a):
    """Divides out the slow brightness change along the strip (Gemini lights one end warmer)."""
    rgb = a[..., :3]
    lum = rgb.mean(axis=(0, 2))
    w = len(lum)
    k = max(3, w // 6) | 1
    pad = np.pad(lum, k // 2, mode='edge')
    smooth = np.convolve(pad, np.ones(k) / k, mode='valid')
    gain = (lum.mean() / np.maximum(smooth, 1))[None, :, None]
    a = a.copy()
    a[..., :3] = np.clip(rgb * gain, 0, 255)
    return a


def strip(theme, piece):
    im = Image.open(raw(theme, piece)).convert('RGB')
    rgb = np.asarray(im).astype(np.float32)
    if piece == 'frame':
        # the pole's ends of row are ragged: keep alpha, crop to the rows that have any of it
        cut = cutout(raw(theme, piece))
        a = np.asarray(cut).astype(np.float32)
        rows = np.where((a[..., 3] > 0).mean(1) > 0.3)[0]
        a = a[rows.min():rows.max() + 1]
        mode = 'RGBA'
    else:
        # the strip's horizontal extent, less its ends (the local model draws a belt as an object with end caps),
        # then the rows it covers fully within that
        ink = rgb.min(-1) < 225
        cols = np.where(ink.mean(0) > 0.15)[0]
        c0, c1 = cols.min(), cols.max()
        if c1 - c0 < rgb.shape[1] * 0.97:
            trim = int((c1 - c0) * END_TRIM)
            c0, c1 = c0 + trim, c1 - trim
        rows = np.where(ink[:, c0:c1 + 1].mean(1) > 0.98)[0]
        a = rgb[rows.min():rows.max() + 1, c0:c1 + 1]
        mode = 'RGB'
    a = seam_x(even_light(a))
    out = Image.fromarray(a.round().astype(np.uint8), mode)
    return save(fit_height(out, STRIP_H[piece]), theme, piece)


def mat(theme):
    a = np.asarray(Image.open(raw(theme, 'mat')).convert('RGB')).astype(np.float32)
    a = seam_x(a)
    a = seam_x(a.transpose(1, 0, 2)).transpose(1, 0, 2)
    mean = a.mean(axis=(0, 1), keepdims=True)
    a = a + (mean - a) * MAT_FLATTEN
    im = Image.fromarray(a.round().astype(np.uint8), 'RGB').resize((MAT_SIZE, MAT_SIZE), Image.LANCZOS)
    return save(im, theme, 'mat')


def props(theme):
    layout = ROOT / 'art/raw' / theme / 'props.json'
    spec = json.loads(layout.read_text()) if layout.exists() else {'grid': [4, 2], 'names': PROPS}
    cols, rows = spec['grid']
    sheet = cutout(raw(theme, 'props'))  # cropped to the items' bbox
    w, h = sheet.size
    out = []
    for i, name in enumerate(spec['names']):
        if name is None:
            continue
        r, c = divmod(i, cols)
        cell = sheet.crop((c * w // cols, r * h // rows, (c + 1) * w // cols, (r + 1) * h // rows))
        box = cell.getbbox()
        if box is None:
            print(f'  {theme}: props cell {i} ({name}) is empty, skipped')
            continue
        cell = cell.crop(box)
        if max(cell.size) > 256:
            cell.thumbnail((256, 256), Image.LANCZOS)
        out.append(save(cell, theme, f'prop_{name}'))
    return out


def sprite(theme, piece, size):
    im = cutout(raw(theme, piece))
    im.thumbnail((size, size), Image.LANCZOS)
    return save(im, theme, piece)


MAKERS = {
    'backdrop': lambda t: [save(fit_width(Image.open(raw(t, 'backdrop')).convert('RGB'), BACKDROP_W), t, 'backdrop')],
    'loop': lambda t: [save(fit_width(Image.open(raw(t, 'loop')).convert('RGB'), 1200), t, 'loop')],
    'kitchen': lambda t: [sprite(t, 'kitchen', 320)],
    'bench': lambda t: [sprite(t, 'bench', 400)],
    'belt': lambda t: [strip(t, 'belt')],
    'counter': lambda t: [strip(t, 'counter')],
    'frame': lambda t: [strip(t, 'frame')],
    'mat': lambda t: [mat(t)],
    'props': props,
}


def kit(theme):
    """Processes one theme; returns the number of drawables written."""
    for old in OUT.glob(f'kit_{theme}_*.webp'):
        old.unlink()
    made = []
    for piece in PIECES:
        if raw(theme, piece) is None:
            print(f'  {theme}: no {piece} yet, skipped')
            continue
        made += MAKERS[piece](theme)
    if not made:
        return 0
    # preview: everything on a neutral blue-grey, one piece under the other
    pw = 1400
    tiles = []
    for im in made:
        im = im.convert('RGBA')
        if im.width > pw:
            im = fit_width(im, pw)
        tiles.append(im)
    hgt = sum(t.height for t in tiles) + 8 * len(tiles)
    prev = Image.new('RGBA', (pw, hgt), (90, 96, 120, 255))
    y = 0
    for t in tiles:
        prev.alpha_composite(t, (0, y))
        y += t.height + 8
    (ROOT / 'app/build').mkdir(exist_ok=True)
    prev.convert('RGB').save(ROOT / f'app/build/kit_{theme}.png')
    print(f'{theme}: {len(made)} drawables -> {OUT.relative_to(ROOT)}/kit_{theme}_*.webp')
    return len(made)


def all_themes():
    themes = {p.stem for p in (ROOT / 'art/themes').glob('*.json')}
    return sorted(d.name for d in (ROOT / 'art/raw').iterdir()
                  if d.is_dir() and d.name in themes and any(raw(d.name, p) for p in PIECES))


def main(argv):
    themes = all_themes() if argv == ['--all'] else argv
    if not themes:
        print('usage: theme_kit.py <theme> [...] | --all', file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    for theme in themes:
        kit(theme)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
