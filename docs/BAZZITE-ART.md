# Local art generation on bazzite (2026-11-07)

Can the bazzite box (RX 9060 XT 16 GB, ComfyUI 0.43.2 on ROCm, repo `liehann/bazzite-ai`) replace Gemini for
Critter Café art? First round: 122 test images in three batches, plus the real jungle and spa theme kits through
`gen_local.py --model klein`, compared piece by piece with the Gemini art in `art/raw/` and, for jungle, with the
earlier local run (FLUX.1 Kontext [dev] Q4 GGUF on goblin's GTX 1070, ~25 min per piece). Contact sheets:
`docs/bazzite-art/*.jpg`. Tools: `art/tools/bazzite/` and `art/tools/gen_local.py --model klein`.

## Short answer

Yes for kit pieces, backdrops and critters, with **FLUX.2 [klein] 9B (fp8) plus the Gemini art as a style
reference**. It is already installed on bazzite, takes 15-50 s per image, renders at any explicit size (1024×2288,
2048×512, 896×1200 were all exact), and accepts several reference images in one prompt. A detailed style block in
the prompt is required. With a one-line style prompt it produces the thin-outline, flat, simpler look the laptop
gave.

## Real theme kits (`gen_local.py --model klein`, sheets 05-06)

Same kit prompts, mock crops and sizes as the goblin Kontext run, plus `STYLE_BLOCK`. 15-25 s per piece, so a
whole 9-piece kit takes about 3 minutes instead of about 4 hours.

- **Jungle (sheet 05):** the Klein kitchen is the mock's treehouse with its rope pulley, close to Gemini's. Goblin
  Kontext drew a generic leaf hut. Backdrop, counter and props are close to Gemini (sheet 05).
- **Spa (sheet 06):** kitchen, bench and props are good. Klein follows the mock more literally than Gemini did, which
  gives a grey stone backdrop centre and a bamboo mat instead of tatami. If Gemini's interpretation is preferred,
  say it in the kit prompt.
- **Recurring faults:** belt strips get rounded end caps (`theme_kit.py` cross-fades the ends, but the prompt should
  say "runs off both edges"). Mat seed 2 added leaves to what should be a plain tile. The jungle bench is drawn
  front-on and bigger than Gemini's three-quarter view.
- Outputs aren't committed (about 40 MB). Regenerate with
  `python3 art/tools/gen_local.py jungle --model klein --suffix _bazzite_s1 --seed 1`.

## What was compared

| Variant | Model | Prompt |
|---|---|---|
| Klein+ref | FLUX.2 klein 9B fp8, 4 steps | style block + Gemini image(s) via `ReferenceLatent` |
| Klein detailed | same | style block, no reference |
| Klein SHORT | same | "Cute flat 2D mobile game art, pastel colours, outlines." (laptop-style) |
| Z-Image | Z-Image Turbo bf16, 8 steps | style block (no reference support) |

## Findings

- **Style match:** Klein+ref is the closest to Gemini, with the same outline weight and colour, palette, cel shading
  and faint wood grain (sheets 01, 02, 03). The backdrop with `backdrop_v2` as reference reproduces the layout at an
  exact 9:20 phone size and does not add Gemini's stray second room (sheet 02).
- **Prompt detail matters most:** the SHORT prompt gives thin pinkish outlines and flat shapes (sheet 01, column 5).
  This is the laptop problem, and the style block below fixes it.
- **Z-Image Turbo** has bold, clean outlines but ignores instructions: it added backrests to "no backrest" benches and
  a roof to the hatch, and it can't take references. Not worth pursuing.
- **Critters (the pipeline recipe: kitten front + side as refs, cream fur, sheet 03/04):** on-model and consistent
  between front and left views. Fox, bear and raccoon worked first try. Failure modes and their fixes:
  - species pulled toward the reference (bunny came out with a cat body, wolf like a raccoon): say "use the
    references ONLY for style; anatomy follows the description" and list the anatomy explicitly (fixed in round 3);
  - red panda came out orange, which the tint pipeline can't use: add the COLOUR RULE (cream `#F2E3C8`, "absolutely no
    orange…") (fixed in round 3, seed 3);
  - natural-colour critters with fox + red panda as refs copied the fox instead of drawing the new animal.
    Use the kitten pair, not a finished species, as the reference.
- **Kit prompts that still need work:** the ticket stand comes out as a box with a slot (round 3 is closer but still
  not a place-card holder). The belt tiles once you say "runs off both edges, no end caps" (round 3 seed 3), but the
  slats need "all slats the same pale wood" to stop the rainbow.
- Seeds vary a lot, so generate 4 per piece and pick one. At about 20 s each, a full themed kit with 4 seeds per piece
  is about 15 min.

## Style block (use verbatim)

> Art style: polished cozy casual mobile game illustration, flat 2D. Every shape has a thick, even, dark
> chocolate-brown outline (#4a2e22) about 1% of the image width, with slightly rounded line ends. Soft cel shading:
> one gentle darker shadow tone on the side away from the light and one small soft highlight, no hard black
> shadows, no gradients to black. Warm desaturated pastel palette: cream, ivory, honey and blonde wood, soft peach,
> muted coral, sage. Subtle hand-painted texture: faint wood grain lines in a slightly darker tone, never noisy.
> Rounded chunky proportions, clean vector-like edges, moderate detail (small bolts, plank seams, fabric folds),
> consistent top-left lighting. Not photorealistic, not 3D, not pixel art, not watercolour, not sketchy.

Kit pieces add: "Use exactly the art style of the reference image … Draw a new object, do not copy the reference's
content." with `art/raw/sushi/backdrop_v2.jpeg` (or `kit_decor.jpeg`) as the reference. Critter prompts are in
`art/tools/bazzite/jobs_batch3.py`.

## Bigger models (not installed; need a shell on bazzite)

| Model | Why | Fit on 16 GB / 32 GB RAM | Refs |
|---|---|---|---|
| **Qwen-Image-Edit-2511**, Q4_K_M GGUF (~13 GB) | strongest local editor for "keep this character, change X" | fits; text encoder, VAE and a Lightning LoRA are already on bazzite | up to 3 |
| **FLUX.2 [dev]** 32B, Q4 GGUF (~19 GB) + offload | bigger sibling of klein, same `ReferenceLatent` workflow, more detail | tight, minutes per image; needs a quantised Mistral text encoder | up to 6-10 |
| Krea 2 (12.9B) | strong aesthetics, style LoRAs | FP8 fits | none (text only); license wording unclear |

Both GGUF options need the ComfyUI-GGUF custom node, which the container doesn't have yet. Suggested next test:
Qwen-Image-Edit-2511 on the critter prompts (identity consistency), FLUX.2 dev on the backdrop and kit.

## Running it

```
python3 art/tools/bazzite/jobs_batch3.py        # writes batch3.json (run from a dir holding refsm/ refs)
COMFY=http://192.168.1.164:8188 python3 art/tools/bazzite/comfy_gen.py batch3.json out/
python3 art/tools/bazzite/gallery.py out/ review.html
```

ComfyUI must be up (`ssh bazzite ai-mode on image`, or `story` mode, which bazzite's story illustrator also uses).
The driver uses only the stdlib. The outputs feed `critter_sprites.py` the same way as the Gemini downloads
(plain white background, cream fur).
