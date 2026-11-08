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

## Level themes (theme kits)

A theme is a set of separate Gemini pieces, not one full-screen painting: a single painting never lines up with the
layout (the sushi backdrop reached a mean zone IoU of 0.47), while pieces placed by code always do. The rule for
splitting a theme: **anything whose shape follows the level or the gameplay is drawn by code, anything decorative
that sits at a fixed place or tiles is generated.** Code-drawn parts still take their look from generated textures.

| Piece | Generated | Drawn by the app (`CafeSkin` with a `ThemeKit`) |
|---|---|---|
| `backdrop` | edge decoration around a plain middle, phone aspect | fills the screen behind the field; its middle's colour fills the status bar |
| `belt` | one straight, tileable strip | bent along the belt and loop paths (`drawStripAlong`, one bitmap mesh per repeat), the loop's moving with the plates |
| `loop` | the loop interior as a wide picture (3:1) | cropped to the loop's proportions, clipped to its shape |
| `kitchen`, `bench` | sprites | placed from `RingLayout` (kitchen rect, each table) |
| `counter` | tileable strip | repeated across the width behind the tables |
| `mat`, `frame` | seamless mat tile, tileable frame piece | mat tiled in the board, the frame bent along its four edges |
| `props` | 4 x 2 sheet: card stand, frame corner joint (an L, corner at the top left), the theme's 3 obstacles, 3 decor items | the corner joint at each board corner; the rest is cut out for later (obstacles have no game rule yet) |
| Cards, plates, pills, window, critters | | shared by every theme |

Steps for a theme (`<id>` from `art/themes/worlds.json`):
1. Level mock: run `art/prompts/generated/<id>.txt` in Gemini, save it as `art/raw/<id>/mock.jpeg`.
2. Pieces: `python3 tools/art/build_prompt.py --kit <id>` renders `art/prompts/generated/<id>_kit.md` from
   `art/prompts/kit.template.txt`, the theme's slots and its `kit` object. Run each prompt in a new chat with the
   mock attached as image 1; save each result as `art/raw/<id>/<piece>.<ext>`.
3. `python3 art/tools/theme_kit.py <id>` (or `--all`) writes `drawable-nodpi/kit_<id>_*.webp` (cutouts key out all
   light neutral pixels; strips get their lighting evened out and their ends cross-faded so they tile; the mat is
   made seamless and flattened) and a preview at `app/build/kit_<id>.png`. Missing pieces are skipped, and the app
   draws its vector version for them.
4. The app picks the theme up by itself (`CafeThemes.available`: any world theme with a `kit_<id>_backdrop`). The
   debug build's in-game Settings card has a theme switch; `ScreenshotTest.pixel9ProLuauNight` renders luau night.

## Generating locally

`art/tools/gen_local.py <theme> --model klein` generates kit pieces on bazzite (FLUX.2 [klein] 9B, 15-25 s a piece,
reference images and exact sizes). Without `--model` it uses FLUX.1 Kontext on goblin (~25 min a piece). The
comparison with Gemini, the style block and the prompt rules for critters are in `docs/BAZZITE-ART.md`.

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
- **Downloading:** use the image's "Download full size image" button (the preview is low resolution). The button
  only works while the pointer is over the image: hover the image, then click the button's coordinates (clicking the
  element reference found while the pointer is elsewhere does nothing). After loading a new chat, the first click
  into the prompt box can be lost; check the prompt holds the image and the text before sending. Then
  `art/tools/grab.sh art/raw/<species>/<name>.png`; run `grab.sh --reset` first so only downloads made after that
  move. Never move other `Gemini_Generated_Image_*` files: older ones in `~/Downloads` belong to the user.
- **Don't run the level generator at the same time.** It pins every core and Chrome stops responding to automation.

## Art TODO

- **Plates must read as holding food.** A playtester couldn't tell that the plates on the belt carry food. Evaluate:
  bolder, higher-contrast food art (a larger food shape filling more of the plate, a darker outline, less plate
  rim), or larger plates. Space is limited, since the belt is narrow and the loop must hold 20-40 plates, so try
  the art change first and measure what size the layout can afford.
- Theme foods on the plates (each theme draws the same plates today).
- The luau counter strip reads thin; try a taller counter or a darker front edge.
- Unused props: each kit's props sheet is cut into sprites, but only the corner joint is placed in the scene.
- Luau lantern and torch props keep a pale glow halo from the white background; key them out or regenerate them.
