#!/usr/bin/env python3
"""Generates theme kit pieces with a self-hosted ComfyUI server (FLUX.1 Kontext [dev], reference-image edit).

For each piece it sends the theme's level mock (art/raw/<theme>/mock.*) as the reference image together with the
piece's prompt from art/prompts/generated/<theme>_kit.md, and saves the result as art/raw/<theme>/<piece>.png at a
fixed size per piece. Pieces that already exist are skipped unless --force; --suffix writes <piece><suffix>.png
instead (for trying the local model next to existing Gemini pieces).

art/raw/<theme>/crops.json ({"kitchen": [x0, y0, x1, y1], ...}, fractions of the mock) narrows the reference to the
part of the mock that shows the piece: Kontext copies a large, clear example much more faithfully than a detail of
the whole scene. --preview renders at about a quarter of the pixels with fewer steps (minutes instead of ~25 on the
GTX 1070), for trying prompts; --prompt replaces the kit prompt for a single piece; --no-crop ignores crops.json.

The server is the "ComfyUI" service in Coolify on goblin (GTX 1070, 8 GB), with the models named below in its
volume and the ComfyUI-GGUF custom node.

--model klein uses FLUX.2 [klein] 9B fp8 on bazzite instead (RX 9060 XT 16 GB, http://192.168.1.164:8188, 4 steps,
about 15-50 s a piece instead of ~25 min) and appends STYLE_BLOCK to the prompt; without it klein drifts to thin
outlines and flat, simple shapes. See docs/BAZZITE-ART.md.

usage: python3 art/tools/gen_local.py <theme> [piece ...] [--force] [--suffix _local] [--seed N] [--steps N]
                                      [--preview] [--prompt TEXT] [--server http://192.168.1.199:8188]
                                      [--model kontext|klein] [--no-style]
"""
import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import io
import uuid
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
PIECES = ['backdrop', 'kitchen', 'belt', 'loop', 'counter', 'bench', 'mat', 'frame', 'props']
# output size per piece (multiples of 64): the shape theme_kit.py expects; it crops and scales from here
SIZES = {
    'backdrop': (768, 1664), 'kitchen': (1024, 1024), 'belt': (1536, 384), 'loop': (1536, 512),
    'counter': (1536, 384), 'bench': (1024, 512), 'mat': (1024, 1024), 'frame': (2048, 256), 'props': (1536, 768),
}
UNET = 'flux1-kontext-dev-Q4_K_M.gguf'
T5 = 't5-v1_1-xxl-encoder-Q5_K_M.gguf'
CLIP_L = 'clip_l.safetensors'
VAE = 'ae.safetensors'
GUIDANCE = 2.5
KLEIN_UNET = 'flux-2-klein-9b-fp8.safetensors'
KLEIN_TE = 'qwen_3_8b_fp8mixed.safetensors'
KLEIN_VAE = 'flux2-vae.safetensors'
KLEIN_SERVER = 'http://192.168.1.164:8188'
STYLE_BLOCK = (
    'Use exactly the art style of the reference image: same outline thickness and colour, same cel shading, same '
    'pastel palette, same amount of detail. Art style: polished cozy casual mobile game illustration, flat 2D. Every '
    'shape has a thick, even, dark chocolate-brown outline (#4a2e22) about 1% of the image width, with slightly '
    'rounded line ends. Soft cel shading: one gentle darker shadow tone on the side away from the light and one small '
    'soft highlight, no hard black shadows, no gradients to black. Warm desaturated pastel palette. Subtle '
    'hand-painted texture, never noisy. Rounded chunky proportions, clean vector-like edges, moderate detail, '
    'consistent top-left lighting. Not photorealistic, not 3D, not pixel art, not watercolour, not sketchy.')
PREVIEW_SCALE = 0.5  # of each side
PREVIEW_STEPS = 12


def prompt(theme, piece):
    md = (ROOT / f'art/prompts/generated/{theme}_kit.md').read_text()
    m = re.search(rf'^## {piece}\n\n(.*?)\n\nSave as', md, re.S | re.M)
    if not m:
        sys.exit(f'no {piece} prompt in {theme}_kit.md')
    return m.group(1).strip()


# Kontext is an editing model: with a crop of the mock as the reference, "keep this object, remove the rest" copies
# the mock's design far more faithfully than the kit prompt's "draw one like it" (tested on the jungle kitchen and
# bench). {name} is the object as the kit prompt names it.
EXTRACT = {
    'kitchen': (r'draw (.*?) on its own',
                'Remove everything except {name}: keep the whole of it, with every detail of its roof, walls, posts, '
                'trim and decorations. Place it alone, centred, on a plain pure white background with a wide margin. '
                'Keep its exact design, colours, thick dark brown outlines and flat cartoon cel-shaded style. Remove '
                'the level sign, the belt, the plates, the ground, the plants around it and any shadow.'),
    'bench': (r'draw one single (.*?) like the benches',
              'Remove everything except one {name}: keep its exact shape, colours, wood lines, thick dark brown '
              'outlines and flat cartoon style, seen from the same slightly-above angle. Remove any animals, signs, '
              'numbers, plates and anything on it, so the bench is empty. Place it alone, centred, on a plain pure '
              'white background. Remove the ground, the counter and any shadow.'),
}


def extract_prompt(theme, piece):
    """The Kontext extract prompt for a cropped piece, or None to use the kit prompt."""
    if piece not in EXTRACT:
        return None
    pattern, template = EXTRACT[piece]
    m = re.search(pattern, prompt(theme, piece))
    return template.format(name=m.group(1)) if m else None


def has_crop(theme, piece):
    crops = ROOT / 'art/raw' / theme / 'crops.json'
    return crops.exists() and piece in json.loads(crops.read_text())


def reference(theme, piece, crop=True):
    """The mock, or its crop for [piece] from crops.json, as PNG bytes."""
    path = next(p for p in sorted((ROOT / 'art/raw' / theme).glob('mock.*')) if p.suffix in ('.jpg', '.jpeg', '.png'))
    im = Image.open(path).convert('RGB')
    crops = ROOT / 'art/raw' / theme / 'crops.json'
    box = json.loads(crops.read_text()).get(piece) if crop and crops.exists() else None
    if box:
        w, h = im.size
        im = im.crop((round(box[0] * w), round(box[1] * h), round(box[2] * w), round(box[3] * h)))
    buf = io.BytesIO()
    im.save(buf, 'PNG')
    return buf.getvalue()


def upload(server, name, data):
    """Uploads an image to the server's input folder; returns its name there."""
    boundary = uuid.uuid4().hex
    body = b''.join([
        f'--{boundary}\r\nContent-Disposition: form-data; name="overwrite"\r\n\r\ntrue\r\n'.encode(),
        f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="{name}"\r\n'
        'Content-Type: image/png\r\n\r\n'.encode(),
        data,
        f'\r\n--{boundary}--\r\n'.encode(),
    ])
    req = urllib.request.Request(f'{server}/upload/image', data=body,
                                 headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())['name']


def workflow(text, mock, w, h, seed, steps, ref_mp):
    """The Kontext edit graph in ComfyUI's API format: the mock is encoded as a reference latent beside the prompt,
    and a fresh w x h latent is sampled."""
    return {
        'unet': {'class_type': 'UnetLoaderGGUF', 'inputs': {'unet_name': UNET}},
        'clip': {'class_type': 'DualCLIPLoaderGGUF', 'inputs': {'clip_name1': T5, 'clip_name2': CLIP_L, 'type': 'flux'}},
        'vae': {'class_type': 'VAELoader', 'inputs': {'vae_name': VAE}},
        'mock': {'class_type': 'LoadImage', 'inputs': {'image': mock}},
        'scaled': {'class_type': 'ImageScaleToTotalPixels',
                   'inputs': {'image': ['mock', 0], 'upscale_method': 'lanczos', 'megapixels': ref_mp}},
        'ref': {'class_type': 'VAEEncode', 'inputs': {'pixels': ['scaled', 0], 'vae': ['vae', 0]}},
        'text': {'class_type': 'CLIPTextEncode', 'inputs': {'text': text, 'clip': ['clip', 0]}},
        'with_ref': {'class_type': 'ReferenceLatent', 'inputs': {'conditioning': ['text', 0], 'latent': ['ref', 0]}},
        'pos': {'class_type': 'FluxGuidance', 'inputs': {'conditioning': ['with_ref', 0], 'guidance': GUIDANCE}},
        'neg': {'class_type': 'ConditioningZeroOut', 'inputs': {'conditioning': ['text', 0]}},
        'latent': {'class_type': 'EmptySD3LatentImage', 'inputs': {'width': w, 'height': h, 'batch_size': 1}},
        'sample': {'class_type': 'KSampler', 'inputs': {
            'model': ['unet', 0], 'positive': ['pos', 0], 'negative': ['neg', 0], 'latent_image': ['latent', 0],
            'seed': seed, 'steps': steps, 'cfg': 1.0, 'sampler_name': 'euler', 'scheduler': 'simple', 'denoise': 1.0}},
        'decode': {'class_type': 'VAEDecode', 'inputs': {'samples': ['sample', 0], 'vae': ['vae', 0]}},
        'save': {'class_type': 'SaveImage', 'inputs': {'images': ['decode', 0], 'filename_prefix': 'kit'}},
    }


def workflow_klein(text, mock, w, h, seed, steps, ref_mp):
    """FLUX.2 [klein] 9B: the reference latent goes on both the prompt and the empty negative (cfg 1, distilled)."""
    return {
        'unet': {'class_type': 'UNETLoader', 'inputs': {'unet_name': KLEIN_UNET, 'weight_dtype': 'default'}},
        'clip': {'class_type': 'CLIPLoader', 'inputs': {'clip_name': KLEIN_TE, 'type': 'flux2'}},
        'vae': {'class_type': 'VAELoader', 'inputs': {'vae_name': KLEIN_VAE}},
        'mock': {'class_type': 'LoadImage', 'inputs': {'image': mock}},
        'scaled': {'class_type': 'ImageScaleToTotalPixels', 'inputs': {
            'image': ['mock', 0], 'upscale_method': 'lanczos', 'megapixels': ref_mp, 'resolution_steps': 16}},
        'ref': {'class_type': 'VAEEncode', 'inputs': {'pixels': ['scaled', 0], 'vae': ['vae', 0]}},
        'text': {'class_type': 'CLIPTextEncode', 'inputs': {'text': text, 'clip': ['clip', 0]}},
        'empty': {'class_type': 'CLIPTextEncode', 'inputs': {'text': '', 'clip': ['clip', 0]}},
        'pos': {'class_type': 'ReferenceLatent', 'inputs': {'conditioning': ['text', 0], 'latent': ['ref', 0]}},
        'neg': {'class_type': 'ReferenceLatent', 'inputs': {'conditioning': ['empty', 0], 'latent': ['ref', 0]}},
        'latent': {'class_type': 'EmptyFlux2LatentImage', 'inputs': {'width': w, 'height': h, 'batch_size': 1}},
        'noise': {'class_type': 'RandomNoise', 'inputs': {'noise_seed': seed}},
        'guider': {'class_type': 'CFGGuider', 'inputs': {
            'model': ['unet', 0], 'positive': ['pos', 0], 'negative': ['neg', 0], 'cfg': 1.0}},
        'sampler': {'class_type': 'KSamplerSelect', 'inputs': {'sampler_name': 'euler'}},
        'sigmas': {'class_type': 'Flux2Scheduler', 'inputs': {'steps': steps, 'width': w, 'height': h}},
        'sample': {'class_type': 'SamplerCustomAdvanced', 'inputs': {
            'noise': ['noise', 0], 'guider': ['guider', 0], 'sampler': ['sampler', 0], 'sigmas': ['sigmas', 0],
            'latent_image': ['latent', 0]}},
        'decode': {'class_type': 'VAEDecode', 'inputs': {'samples': ['sample', 0], 'vae': ['vae', 0]}},
        'save': {'class_type': 'SaveImage', 'inputs': {'images': ['decode', 0], 'filename_prefix': 'kit'}},
    }


def run(server, graph, free_first=True):
    """Queues the graph, waits for it and returns the saved image's bytes."""
    # Unload everything first: left resident, the text encoders keep the UNet to ~3.2 GB on the GPU and the steps
    # take twice as long (~120 s instead of ~64 s on the GTX 1070).
    # Not on bazzite (free_first=False): 16 GB holds klein comfortably, and other apps share that server.
    if free_first:
        free = urllib.request.Request(f'{server}/free', data=json.dumps({'unload_models': True, 'free_memory': True}).encode(),
                                      headers={'Content-Type': 'application/json'})
        urllib.request.urlopen(free, timeout=60).close()
    req = urllib.request.Request(f'{server}/prompt', data=json.dumps({'prompt': graph}).encode(),
                                 headers={'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            pid = json.loads(r.read())['prompt_id']
    except urllib.error.HTTPError as e:
        sys.exit(f'server refused the graph: {e.read().decode()[:2000]}')
    while True:
        time.sleep(5)
        with urllib.request.urlopen(f'{server}/history/{pid}', timeout=60) as r:
            hist = json.loads(r.read()).get(pid)
        if not hist:
            continue
        status = hist.get('status', {})
        if status.get('status_str') == 'error':
            sys.exit(f'generation failed: {json.dumps(status.get("messages"))[:2000]}')
        if status.get('completed'):
            img = hist['outputs']['save']['images'][0]
            q = urllib.parse.urlencode({'filename': img['filename'], 'subfolder': img['subfolder'], 'type': img['type']})
            with urllib.request.urlopen(f'{server}/view?{q}', timeout=120) as r:
                return r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('theme')
    ap.add_argument('pieces', nargs='*')
    ap.add_argument('--force', action='store_true')
    ap.add_argument('--suffix', default='')
    ap.add_argument('--model', choices=['kontext', 'klein'], default='kontext')
    ap.add_argument('--server', help='default: goblin for kontext, bazzite for klein')
    ap.add_argument('--seed', type=int, default=1234)
    ap.add_argument('--steps', type=int, help='default: 20 for kontext, 4 for klein')
    ap.add_argument('--no-style', action='store_true', help='klein: do not append STYLE_BLOCK')
    ap.add_argument('--preview', action='store_true')
    ap.add_argument('--prompt')
    ap.add_argument('--no-crop', action='store_true', help='use the whole mock even when crops.json has a box')
    a = ap.parse_args()
    klein = a.model == 'klein'
    a.server = a.server or (KLEIN_SERVER if klein else 'http://192.168.1.199:8188')
    a.steps = a.steps or (4 if klein else 20)
    pieces = a.pieces or PIECES
    if a.prompt and len(pieces) != 1:
        sys.exit('--prompt needs exactly one piece')
    for piece in pieces:
        dest = ROOT / 'art/raw' / a.theme / f'{piece}{a.suffix}.png'
        if dest.exists() and not a.force:
            print(f'skip {dest.relative_to(ROOT)} (exists)')
            continue
        t = time.time()
        w, h = SIZES[piece]
        steps, ref_mp = a.steps, 1.0
        if a.preview:
            w, h = (round(v * PREVIEW_SCALE / 64) * 64 for v in (w, h))
            steps, ref_mp = (steps if klein else PREVIEW_STEPS), 0.3
        mock = upload(a.server, f'{a.theme}_{piece}_ref.png', reference(a.theme, piece, not a.no_crop))
        cropped = has_crop(a.theme, piece) and not a.no_crop
        text = a.prompt or (cropped and extract_prompt(a.theme, piece)) or prompt(a.theme, piece)
        if klein:
            if not a.no_style:
                text = f'{text}\n\n{STYLE_BLOCK}'
            graph = workflow_klein(text, mock, w, h, a.seed, steps, ref_mp)
        else:
            graph = workflow(text, mock, w, h, a.seed, steps, ref_mp)
        dest.write_bytes(run(a.server, graph, free_first=not klein))
        print(f'{dest.relative_to(ROOT)} {w}x{h} in {time.time() - t:.0f} s', flush=True)


if __name__ == '__main__':
    main()
