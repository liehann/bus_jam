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

## Qwen-Image on bazzite (sheets 08_qwen_*)

Installed 2026-11-07 in `~/ai/models/comfyui/`: `diffusion_models/qwen_image_2512_fp8_e4m3fn.safetensors`,
`diffusion_models/qwen_image_edit_2511_fp8mixed.safetensors`, `unet/qwen-image-2512-Q4_K_M.gguf` (+ `Q4_K_S`).
`comfy_gen.py` models: `qwen` (fp8, 40 steps, cfg 4; with refs = Edit 2511), `qwengguf`, `qwenfast` (fp8 +
Lightning LoRA, 4-8 steps, cfg 1).

| Variant | Time (896x1200) | Notes |
|---|---|---|
| fp8, 40 steps | 3.2-7.5 min | 20 GB model streamed from RAM (16 GB card); richest detail |
| Q4_K_M GGUF, 40 steps | 5.3 min | fully on the GPU but slower: GGUF dequantises every step on ROCm; looks the same as fp8 |
| fp8 + Lightning, 8 steps | ~30 s | close to the 40-step look, slightly shinier, sometimes a grey background |
| Edit 2511 + kitten refs | ~11 min | stays too close to the reference (the fox reads as a cat); not better than klein |

Qwen is more detailed than klein (fur, eye shine, richer props) but warmer, more saturated and heavier-outlined than
the Gemini look, and it drew a phone frame around the backdrop. klein + Gemini reference stays the default for kits.

## Toward the Gemini look (sheet 09_qwen_gemini_4x4)

The kids prefer Gemini. Four animals (kitten, fox, red panda, wolf) x four Qwen prompts, plus klein, all seed 7
(`jobs_batch8_qwen_gemini.py`):
- P1-P3, Qwen text only (detailed / flat clip-art / one sentence): flat and consistent within a row, but rounder,
  generic kawaii with blush cheeks. Prompting alone doesn't reach the Gemini look.
- P4, Qwen-Edit 2511 with a different Gemini animal as the reference (~6.5 min): closest to Gemini (slim build,
  small eyes, no blush), but the species bleeds from the reference (the kitten given the fox reads as a fox).
- K, klein with the kitten + another Gemini animal as references (~20 s): close second; adds blush and has the same
  species bleed.
Every row is consistent across animals; matching Gemini needs Gemini images as input. Next step: train a style LoRA
on the approved Gemini art.

## Bigger models

| Model | Why | Fit on 16 GB / 32 GB RAM | Refs |
|---|---|---|---|
| **Qwen-Image-Edit-2511**, Q4_K_M GGUF (~13 GB) | strongest local editor for "keep this character, change X" | fits; text encoder, VAE and a Lightning LoRA are already on bazzite | up to 3 |
| **FLUX.2 [dev]** 32B, Q4 GGUF (~19 GB) + offload | bigger sibling of klein, same `ReferenceLatent` workflow, more detail | tight, minutes per image; needs a quantised Mistral text encoder | up to 6-10 |
| Krea 2 (12.9B) | strong aesthetics, style LoRAs | FP8 fits | none (text only); license wording unclear |

Both GGUF options need the ComfyUI-GGUF custom node, which the container doesn't have yet. Suggested next test:
Qwen-Image-Edit-2511 on the critter prompts (identity consistency), FLUX.2 dev on the backdrop and kit.

## Batch 9: new critters and story scenes (2026-11-11, sheets 10-11)

`jobs_batch9_critters_story.py`: 42 images, about 20 min in total.

- **Six new cream critters (sheet 10)**, using the batch-3 recipe (klein, kitten front and side as references, COLOUR
  RULE), at about 23 s each. The hamster, koala and panda are on-model in both views and both seeds. The fawn has its
  dapple spots only in seed 4. The hedgehog's spines come out grey-tan, not cream, so the tint pipeline would leave
  them untinted; tell it "cream spines" if that matters. The otter is the weakest: it reads somewhat like a ferret.
- **Story scenes (sheet 11)**: six beats of "how the café began" (dawn opening, first customer, lunch rush, colour
  sorting, closing time, the journey to new cafés), 1344x896. klein with `home/hero.jpeg` as the style reference
  (15-24 s) matches the Gemini hero closely, keeps the cast (pink kitten, red panda, yellow fox, blue wolf), and stays
  pale and soft. Qwen Lightning, text only (8 steps, about 35 s), is richer and more painterly but drifts off-cast: the
  fox comes out orange, it adds a second red panda, and the kitten gets tabby stripes. klein s4 has no café behind
  the stools, and klein s1 of s6 writes "CAFF" on the treehouse. Best picks: s1 klein s1, s2 klein s1, s3 klein s2,
  s4 Qwen, s5 klein s1, s6 klein s2.

## MiniMax H3 on bazzite (sheet 12, clips in `docs/bazzite-art/h3/`)

MiniMax H3 (open weights since 2026-08-03) is a **33B omni video model**: text, first and last frame, or references
to 24 fps video with stereo audio. It is not an image model. ComfyUI 0.43.2 has the native nodes
(`MiniMaxH3ImageToVideo`, `EmptyMiniMaxH3LatentAV`, ...). Files from `Comfy-Org/MiniMax-H3`, 44 GB in total:

| File | Size | Note |
|---|---|---|
| `diffusion_models/minimax_h3_fl2va_pruned_fp8_scaled` | 21 GB | fp8 runs natively on gfx1200. The log says this ROCm build also has native `int8_tensorwise` / `w6a8_int8`, so `pruned_int8_convrot` (the template's default) may work too; untested |
| `text_encoders/qwen3vl_32b_minimax_h3_nvfp4_awq` | 15.7 GB | nvfp4 is emulated on ROCm but encodes in about 5 s |
| `vae/minimax_h3_video_vae_fp16`, `vae/minimax_h3_audio_vae_fp32` | 5.2 + 0.6 GB | |
| `loras/minimax_h3_fl2v_turbo_8step_v1.0_comfyui_bf16` | 2 GB | 8 steps, `res_multistep` / `simple`, BasicGuider (no CFG) |

Driver: `art/tools/bazzite/h3_gen.py` (the template's image-to-video subgraph, flattened). Results, RX 9060 XT 16 GB,
32 GB RAM (it uses about 28 GB of RAM while sampling):

| Job | Size | Time | Result |
|---|---|---|---|
| Still: 5 frames, keep frame 0 (cold start) | 768x768 | 69 s | on-prompt and clean, but softer than klein; fake text on signs; the kitten became a striped tabby. Not worth using for stills |
| Story s5 animated (first frame) | 768x512, 2.3 s | 90 s | keeps the picture-book style; the kitten yawns and leans over, the tail swings, the lanterns glow; soft ambient audio |
| Story s1 animated (first frame) | 768x512, 4.5 s | 159 s | the door swings open, the sun rises, the critters hop and wag; style stays stable |
| Kitten idle loop (first = last = `kitten_front`) | 768x1024, 2.3 s | 222 s | it blinks and swishes its tail, the body stays put on white; the first and last frames differ by under 1/255 on average, so the loop has no visible seam |

**Verdict:** it works, and at 768p it is fast enough (1.5-4 min per clip) to use. It is no replacement for klein
for stills, but it is good for **animating finished art**: story cut-scenes with sound, and idle loops for critter
sprites. A critter idle loop needs the white background removed and the frames downsampled (for example 12 fps) into
a sprite sheet before the game can use it; that has not been tried. Untested: longer clips at the full 1344x768 (RAM
is the limit), the `ref2va` reference model for multi-critter scenes, and the int8_convrot weights.

## Running it

```
python3 art/tools/bazzite/jobs_batch3.py        # writes batch3.json (run from a dir holding refsm/ refs)
COMFY=http://192.168.1.164:8188 python3 art/tools/bazzite/comfy_gen.py batch3.json out/
python3 art/tools/bazzite/gallery.py out/ review.html
# MiniMax H3 clips (jobs: name, prompt, w, h, seconds, seed, first_frame?, last_frame?, still?)
COMFY=http://192.168.1.164:8188 python3 art/tools/bazzite/h3_gen.py h3jobs.json out/
```

ComfyUI must be up (`ssh bazzite ai-mode on image`, or `story` mode, which bazzite's story illustrator also uses).
The driver uses only the stdlib. The outputs feed `critter_sprites.py` the same way as the Gemini downloads
(plain white background, cream fur).
