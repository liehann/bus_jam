# Critter Café art pipeline

How the critter, home and icon art was made, and how to make more. Sources live in `art/raw/`, scripts in
`art/tools/`, outputs in `app/src/main/res/`. Run every script from the repo root with `python3` (Pillow, numpy).

## Sources

| What | Source | Script | Output |
|---|---|---|---|
| Kitten | the chibi kitten pack (`~/Downloads/kitten_jumble_pack.zip`, from a claude.ai chat), copied to `art/raw/kitten/` | `critter_sprites.py` (uses `kitten_process.py` from the pack) | `drawable-nodpi/critter_<species>_<colour>_{front,right}.png` |
| Red panda, fox, wolf | Gemini, from the kitten's front and side views | `critter_sprites.py` | same |
| Home hero, logo | Gemini, `art/raw/home/{hero,logo}.jpeg` | `home_art.py` | `critter_home_hero.webp`, `critter_logo.png` |
| App icon | the pink kitten front view | `app_icon.py` | `mipmap-*/ic_launcher_{foreground,monochrome}.png` (+ `mipmap-anydpi-v26/` XML) |

### Sprite rules
- Critters are drawn in **cream fur** with white and dark-brown markings. `critter_sprites.py` tints only the cream
  (hue 10-42, low saturation) into each palette colour lightened 30% toward white, so markings, outlines and eyes
  survive the tint.
- The background must be plain white: the cutout is a flood fill from the corners (white fur inside the outline is kept).
- Each species needs a front view and a left view; the right view is the left one mirrored. Height 192 px.
- A new species: add it to `SPECIES` in `critter_sprites.py` and to `Species` in the app.

### App icon
The kitten head is 56 dp wide with the ear tips 24 dp from the top of the 108 dp layer, so no launcher mask clips
the ears and the cut through the body falls below every mask. Background `#C9EAD1`
(`values/ic_launcher_background.xml`); the launch splash uses the same icon on the café cream `#F6EBD9`.

## Generating with Gemini (Chrome)

Prompts that worked: paste the kitten front and side views, then ask for "the same chibi style, same proportions and
outline, a <species> with cream fur and white / dark-brown markings, sitting, front view (and side view facing left),
plain white background". Ask for a revision when the species reads as a kitten (the first fox looked like a kitten and
needed one revision).

- **Attaching reference images:** Gemini's file picker can't be driven by automation and it can't fetch from
  localhost. Put the image on the macOS clipboard and paste it into the prompt with cmd+V:
  `osascript -e 'set the clipboard to (read (POSIX file "/abs/path.png") as «class PNGf»)'`
- **Set the clipboard right before each paste and check the prompt box before sending.** Anything the user copied
  in between gets pasted instead (once, a private Sheets link landed in the prompt). Pasting replaces the user's
  clipboard; say so afterwards.
- **Downloading:** use the image's "Download full size image" button (the preview is low resolution). Then
  `art/tools/grab.sh art/raw/<species>/<name>.png`; run `grab.sh --reset` first so only downloads made after that
  move. Never move other `Gemini_Generated_Image_*` files: older ones in `~/Downloads` belong to the user.
- **Don't run the level generator at the same time.** It pins every core and Chrome stops responding to automation.
