# Kitten Jumble skin plan

Status: plan only, no app code changed. Source pack: `kitten_jumble_pack/` (README, poses.json, palette.json,
`sprites/sitting/` 10 colours x 4 views at 256 px, `ui/assets.html` + `assets.png`, Fredoka woff2, `tools/process.py`).

Mapping: **Container = yarn skein** (S/M/L = 1/2/3 cells, capacity 4/6/10; the needle points the exit direction).
**Dock slot = cushion** (the skein rolls onto it). **Element = kitten** (head-only "bean" token on the ring and queue;
full-body sprite only when few are on screen). Rules, solver and level packs are untouched.

All sizes below are measured from the current Compose layout on a 411 dp-wide phone (field width 395 dp after the
8 dp side padding; 12x14 board, 40 seats, `RingLayout` regular mode).

## 1. Concept to asset mapping, per screen

### Game screen (real-time, `RingField.kt`)

| Concept | Current drawing | Kitten Jumble | On-screen size |
|---|---|---|---|
| Ring track | grey stroke path, `seatR*2.5` wide | themed track: a felt/wood oval "cat-tree carousel" band; each seat is a small round bed | oval 395 x 126 dp (`ringH` = 0.32 x width, clamped 100-130); band ~25 dp wide |
| Ring seat | white circle | round bed (vector) | 19.7 dp diameter, pitch 21.4 dp (perimeter 857 dp / 40) |
| Element on a seat | shape token `seatToken` | **bean head**, fur colour + collar charm | **17.5 dp** (not 22: `seatToken = min(pitch*0.82, seatR*1.8)`) |
| Boarding window (6 seats) | gold translucent band + end ticks + triangle | highlighted cushion band: warm pink felt under the 6 beds, paw-print arrow towards the dock | ~128 dp of track at the bottom centre |
| Entry point | dashed line + ring | small "door" arch on the track | 25 dp |
| Queue line (inside the oval) | up to 20 tokens in 2 rows, then "+N" | up to 20 bean heads at 14 dp, then "+462" pill in Fredoka | 14 dp tokens, pitch 15.4 dp |
| Walker (seat -> dock) | token shrinking 17.5 -> 8.8 dp, hop arc | full-body walk sprite (see note on walk time) shrinking into the skein | ~30 dp tall at start, ~18 dp at the end |
| Dock slot | grey rounded rect | cushion (vector, slot aspect) | 92.8 x 41.4 dp each, 8 dp gaps |
| Docked container | bus with 2-column seat grid | skein lying horizontally on the cushion, needle pointing right | 25.9 dp thick; length 48 / 61 / 85 dp for cap 4 / 6 / 10 |
| Seated elements | filled seat dots on the bus roof | paw dots under the cushion (filled = seated) + 1-2 bean heads peeking out of the skein for the latest arrivals | dots 7 dp (10 fit in 80 dp); peeking heads 12 dp |
| Remaining-capacity badge | ink pill "3" under the slot | cream count pill (pack style) | 11 sp text, 9 dp `badgeRoom` |
| Dispatch | bus lifts away, fades | skein rolls off up-right; its kittens do a pounce/jump burst | 400 ms (`dispatchTicks` 16) |
| Board | asphalt rect + cell squares | cream felt board with tan cell pockets and brown outline (pack board snippet) | 395 x 461 dp, cell 32.9 dp |
| Container on board | bus, icon `boardIcon` | skein: strands, cream band with charm symbol, floating needle | body 27 dp thick; length 28 (S) / 61 (M) / 94 (L) dp; symbol ~11 dp |
| S container (1 cell) | square bus | **yarn ball** (28 dp round) with a short needle across it; the pack has no 1x1 | 28 x 28 dp |
| Blocked bump, hint pulse | bus nudge, gold halo | same motion; halo in butter yellow | |
| Top bar | "< Levels", "Level N" 20 sp, pause 34 dp, stars 18 dp | pack buttons: "Level 12" pill, round pause, stars | bar 44 dp |
| Bottom buttons | Restart / Undo / Hint, dark | pack cream buttons with icons (redo arrow, undo arrow, bulb) | 3 x ~125 dp x 48 dp |

### Home (`HomeScreen.kt`)

| Concept | Kitten Jumble | Size |
|---|---|---|
| Hero canvas (3 buses) | 3 skeins + 2 sitting kittens (pack sprites) | 120 dp tall, full width |
| Title "Bus Jam" 48 sp | Kitten Jumble logo (pack SVG, multicolour Fredoka with outline + skein dot) | ~330 x 75 dp |
| Play button | big mint pill | full width x 72 dp |
| Levels button | pink pill | full width x 56 dp |
| Debug pack list, real-time switch | unchanged, plus a **Skin** switch (debug) | |

### Level select (`LevelSelectScreen.kt`)

| Concept | Kitten Jumble | Size |
|---|---|---|
| Level tile | round node (pack): cream, current = butter, locked = beige with padlock | tile 71 x 83 dp (5 columns); node 56 dp |
| Stars | pack gold stars | 12 dp |
| Boss tile (every 10th) | node with a cherry ring and a crown charm (no "Hard" label: deferred by user) | 56 dp |

### Dialogs (`GameScreen.kt` `ResultOverlay`)

| Concept | Kitten Jumble | Size |
|---|---|---|
| Win card | pack dialog "Purrfect!", 3 stars, Next (mint) | card ~347 dp wide, stars 44 dp; add a jumping kitten (pose `jump`) 72 dp above the title |
| Win text | "Level N complete" (the pack sample says "in 14 moves"; the game has no move count, drop it) | 16 sp |
| Lose card | same card, title "Tangled!" (dock full) / "All stuck!" (no moves), sleepy or sad kitten, Undo (cream) + Restart (pink) | as win |

## 2. Asset inventory

### Usable as-is
- `sprites/sitting/*` (10 colours x front/left/right/back, 256 px tall): home hero, win/lose dialog fallback.
- `ui/assets.html` SVG/CSS: buttons, icons, cushions, level nodes, skeins (1x2, 1x3), needle, band symbols, stars,
  board snippet, dialog. All of it is vector; port the shapes to Compose `DrawScope` code (no raster export).
- `tools/process.py` cutout + tint, reusable for every new pose.

### Needs adapting
| Asset | Change |
|---|---|
| Sitting sprites | Outline is 1-2 px after downscale below ~40 dp; thicken the outline before scaling (see section 3). |
| Fredoka | `fredoka-latin.woff2` does not load on Android. Use the variable `Fredoka[wdth,wght].ttf` from Google Fonts (OFL) in `res/font/`, plus `OFL.txt`. |
| Skeins | Pack defines yarn colours and symbols for 5 colours only (pink, mint, sky, butter, lavender), and the yarn hexes differ from `palette.json` (pink yarn `#F6A2BB` vs fur `#F9B8C9`). Derive yarn base/dark/light from the fur colour so there is one colour per index. |
| Skein geometry | Pack draws at 64 px cells; ours are 33 dp. Strand stroke 5/64 cell = 2.6 dp, band 26/64 = 13 dp, symbol 15/64 -> raise to ~11 dp for legibility. |
| Cushion | Pack cushion is ~1.5:1; the dock slot is 2.2:1 and the cap-10 skein is 85 dp long. Redraw the cushion path at slot aspect. |
| Collar | Sprites have a blue collar with a gold bell in every colour; the bell becomes the per-colour charm (drawn by the app on beans; on full sprites keep the bell). |

### Missing
| Asset | Form | Notes |
|---|---|---|
| Bean tokens x 10 colours | **vector** (Compose paths baked into an atlas at runtime) | 14 / 17.5 / 12 dp; raster crops from section 3 are the art reference and the >= 28 dp fallback |
| Charm symbols x 10 | vector | 5 exist (heart, flower, moon, star, paw); 5 new, see section 3 |
| Ring track, seat beds, boarding band, entry door | vector | path-based, must follow `trackPoint()` at any width |
| Board background + cell pockets | vector colours, optional raster felt texture tile | texture prompt below |
| Cushion at dock size | vector | |
| Skeins S/M/L, all 10 colours, 4 directions | vector | the 1x1 yarn ball is new |
| Paw dots, count pill | vector | from the pack |
| Walk frames | raster, poses.json `walk_side_a/b`, `walk_back` exist as prompts only (`raw/` is empty) | **`walk_front` is missing**: the walk goes from the ring's bottom edge down to the dock, i.e. towards the viewer |
| Celebrate | raster, `pounce` and `jump` prompts exist | |
| Lose kitten | raster, new pose | prompt below |
| Home hero, app icon | raster | prompts below |
| Lose dialog layout | vector | reuse the win card |

Gemini prompts (attach `reference/kitten_front.jpg` and `kitten_left.jpg` where a kitten appears; keep the pack's
`preamble` for poses):

New pose entries for `poses.json`:
```json
{"id": "walk_front", "use": "Walking from the ring down to a cushion", "prompt": "Walking directly towards the viewer, seen from the front, mid-step with one front paw lifted, tail up behind, happy face.", "faces": "none", "height": 240},
{"id": "sad", "use": "Lose dialog", "prompt": "Sitting facing the viewer, tangled in a loose strand of pink yarn wrapped around the body and one ear, ears drooping, big sad eyes, small frown.", "faces": "none", "height": 250}
```

Board felt texture (raster, tiled behind the vector cells):
> Seamless tileable texture, 512x512, soft cream felt fabric (#FBEFDC), very subtle warm fibre noise, no pattern, no
> objects, no shading gradient, flat even lighting, cozy mobile puzzle game background.

Home hero:
> Cozy mobile puzzle game illustration, wide banner 1600x500, transparent-friendly plain white background. Three
> cream kittens identical to the reference images (same thick dark-brown outline, pastel colours, soft tan shading,
> blue collar with gold bell), tinted pink, mint and sky, playing on a heap of pastel yarn skeins with knitting
> needles stuck in them. Thick dark-brown (#85583F) outlines, flat pastel fills, no text, no ground shadow.

App icon foreground (adaptive icon, 432x432 safe zone 264):
> Mobile game app icon foreground, plain white background. The head of the cream kitten from the reference images
> popping out of the top of a round pink yarn ball, a wooden knitting needle stuck through the ball diagonally. Thick
> dark-brown (#85583F) outline, pastel fills, centred, occupying the middle 60 % of the canvas, no text. (Background
> layer: flat `#F7D5A5`.)

## 3. Bean heads: process.py extension and colour check

Add `tools/beans.py` (imports `cutout`, `fur_weights`, `tint` from `process.py`):

1. Source: `reference/kitten_front.jpg` (1493x2000, untinted cream), not the 256 px sitting sprite (already tinted,
   and the head is only ~140 px).
2. `cutout()`, then find the collar: rows where blue pixels (`B > R + 20`) cover > 8 % of the width (rows 798-920 on
   the reference). Crop from the top to the collar's last row + 1 % height. Result: ~1000 x 937 px head + collar.
3. Replace the bell with a plain cream disc (`#FFF6E8`, outlined) at the collar centre; the app draws the charm symbol
   on top, so one raster serves all charms and stays crisp.
4. Thicken the outline: outline mask = luminance < 0.35 and alpha > 0.5; dilate with `MaxFilter(k)` where
   `k = src_px / target_px * 1.2`, so it lands at >= 1.2 dp after scaling. Composite the dilated outline under the
   original.
5. `tint()` per colour (with the tweaked palette below).
6. Pad to square, resize with LANCZOS to `size_dp x density` for size_dp in {14, 18, 28} and densities 1, 2, 3, 4,
   then `UnsharpMask(radius=1, percent=60)`.
7. Write one atlas per density: `out/beans/drawable-{mdpi,xhdpi,xxhdpi,xxxhdpi}/kj_beans.png` (10 columns = colour
   index order of the app, rows = sizes) plus `kj_beans.json` (cell rects), and `out/beans/_check.png`: the 18 dp row
   at 1x and 3x on the ring colour, plus the same row through deuteranopia, protanopia and tritanopia matrices
   (Machado 2009).

Prototype result (crop + tint run in the scratchpad, not in the repo): at 54 px (18 dp @ 3x) all 10 beans read as
kittens and colours separate; at 22 px (mdpi) eyes and ears blur into a blob and the outline vanishes. Hence vector
beans for the ring, raster beans for >= 28 dp.

### Palette check (CIEDE2000 between fur colours; CVD = Machado severity 1.0)

| Pair | Pack palette dE | Deutan | Protan | Tritan |
|---|---|---|---|---|
| lavender / plum | **13.5** (closest pair) | 6.4 | 9.1 | 20.4 |
| pink / plum | 15.8 | 20.3 | 17.0 | 8.6 |
| sky / lavender | 18.4 | **3.5** | 6.5 | 22.5 |
| pink / mint | 45.5 | **5.8** | 16.8 | 43.9 |
| blueberry / plum | 23.2 | 12.2 | **5.5** | 47.4 |
| mint / sky | 25.0 | 23.3 | 25.0 | **4.4** |
| pink / cherry | 18.9 | 18.0 | 21.3 | 18.9 |
| sky / blueberry | 19.0 | 19.8 | 19.5 | 16.8 |

Pink/cherry and sky/blueberry are fine (lightness gap ~20 L). The problems are lavender/plum (both mid-light
purples) and, for colour-blind players, sky/lavender and pink/mint. Most pack colours sit at L 76-90, so separation
relies on hue alone. Proposed palette (spreads lightness from 48 to 90):

| Colour | Pack | Proposed | L* |
|---|---|---|---|
| pink | `#F9B8C9` | `#F4A6BE` | 76 |
| mint | `#B0E5C1` | `#A0E2B2` | 85 |
| sky | `#B0D7F2` | `#A4E0F2` (towards aqua) | 86 |
| butter | `#F9E096` | `#F9E096` | 90 |
| lavender | `#CDBDF2` | `#B8A4EC` | 71 |
| tangerine | `#F8AA70` | `#F8A360` | 74 |
| cherry | `#EA6E78` | `#E25864` | 56 |
| blueberry | `#7A92E2` | `#6884DE` | 57 |
| plum | `#D892D6` | `#A054A4` (deep plum) | 48 |
| charcoal | `#8C8898` | `#787484` | 50 |

Result: closest normal-vision pair 17.2 (lavender/blueberry, was 13.5); deutan minimum 9.5 (was 3.5); protan minimum
8.0 (mint/butter, was 5.5). Tritan mint/sky stays ~4.6, which the charms cover.

### Charm symbols (collar charm on beans, band symbol on skeins, paw-dot tint stays colour)

Chosen for distinct silhouettes at ~7 dp: pink **heart**, mint **flower**, sky **moon**, butter **star**, lavender
**paw** (all from the pack), tangerine **fish**, cherry **bow**, blueberry **drop**, plum **crown**, charcoal
**diamond**. Charm disc on an 18 dp bean: 7 dp; on skeins: 11 dp on the band. The existing `IconShape` path code in
`Palette.kt` already has heart, star, crescent and diamond paths to reuse.

## 4. App integration

### Skin layer
- New package `app/.../ui/skin/`:
  ```kotlin
  interface Skin {
      val colors: SkinColors                   // background, board, slot, ink, gold, per-index colour + name
      fun DrawScope.board(geo: FieldGeometry, level: Level)
      fun DrawScope.container(center: Offset, width: Float, length: Float, degrees: Float, color: Int,
                              alpha: Float = 1f, fill: Fill? = null, highlight: Float = 0f, iconSize: Float)
      fun DrawScope.dockSlot(geo: FieldGeometry, slot: Int, view: SlotView?, filled: Int, offset: Offset, alpha: Float)
      fun DrawScope.track(lay: RingLayout, gateWidth: Int, entry: Offset)
      fun DrawScope.seat(center: Offset, radius: Float)
      fun DrawScope.element(color: Int, center: Offset, size: Float, lod: ElementLod, alpha: Float = 1f)
  }
  sealed interface ElementLod { data object Bean; data class Walk(val t: Float, val dx: Float, val dy: Float); data class Celebrate(val t: Float) }
  ```
- `FlatSkin` wraps today's functions (`drawContainer`, `drawElement`, `drawDockSlot`, asphalt board) with no visual
  change. `KittenSkin` is the new one. Provide via `LocalSkin` (CompositionLocal), read in `RingField`/`PlayField`
  composition and passed into the draw functions.
- Call sites to route through the skin: `RingField.kt` 271 (walkers), 329 (queue), 345 (seats), `drawCarousel` track;
  `DockBoardArt.kt` 76, 140, 163, 180; `PlayField.kt` 297, 348-373 (turn-based); `HomeScreen.kt` 63-65.
  `Palette.name()` in content descriptions moves to `skin.colors.name()` ("pink skein facing up").
- Debug toggle: a "Kitten Jumble skin" switch on the home screen next to the real-time switch, stored the same way as
  `mode.realTime` in `AppViewModel`; debug-only until the skin is complete, then flip the default.
- Scope: real-time screen first. Turn-based `PlayField` gets the skin only if that mode stays.

### Asset locations
- Vector everything except kittens: Kotlin `DrawScope` code in `ui/skin/kitten/` (no VectorDrawable XML; the skeins
  need per-colour strand colours and arbitrary lengths).
- Bean atlas: built at runtime once per density (vector paths drawn into one `ImageBitmap`, 10 colours x 3 sizes).
  If the raster beans win the comparison, ship them as `res/drawable-{mdpi,xhdpi,xxhdpi,xxxhdpi}/kj_beans.png`.
- Full-body sprites (sitting, walk, pounce, jump, sad): one atlas PNG per pose set in `res/drawable-nodpi/`
  (`kj_kittens_walk.png` etc.) + a generated Kotlin rect table, scaled at draw time. At 30 dp x 3.5 density the
  sprites need <= 105 px, so the 230-270 px pose heights are enough; ship one size.
- Font: `res/font/fredoka.ttf` (variable) + `OFL.txt` in `assets/licenses/`; `FontFamily(Font(R.font.fredoka, weight = ...))`
  in the theme `Typography`, and `TextMeasurer` styles in the canvas ("+462", count pills).

### Animation hooks
- Walkers: `RtWalkerView(startTick, arriveTick, fromPosition, slot)` already gives progress `p`; pass
  `ElementLod.Walk(p, dx, dy)`. The skin picks the frame from the direction (mostly `walk_front`; side frames for the
  outer slots) and alternates A/B frames every ~80 ms.
- **Walk duration is 200 ms** (`boardTicks` 8 x 25 ms), stagger 75 ms, so at most ~3 walkers are visible and a walk
  cycle shows ~2 frames. It reads as a hop. Either accept a hop (one pose, squash/stretch) or lengthen `boardTicks`
  (gameplay pacing, core change). Open question below.
- Dispatch (`dispatchAt..freeAt`, 400 ms): skein rolls away; spawn 2-3 `Celebrate` sprites (pounce/jump) from the
  cushion, purely visual.
- Seat to walker hand-off: the bean pops (scale 1 -> 1.2 -> 0) as the full body appears, so the LOD switch is hidden.

### Performance
- No composable per kitten. Everything stays in the one `Canvas` per field.
- Beans and sprites: `drawImage(atlas, srcOffset, srcSize, dstOffset, dstSize, filterQuality = Low)` from a single
  `ImageBitmap`; 40 seats + 20 queue + ~3 walkers = ~63 blits per frame.
- Skeins are expensive vector (≈ 15-25 clipped bezier strokes each, up to 128 on the board). Cache the static board
  layer (background + on-board skeins) into an `ImageBitmap` via `drawWithCache`/`GraphicsLayer`, invalidated only when
  `onBoard`, hint or layout changes. Draw the bumping, hinted and flying skeins live on top.
- Docked skeins: cache per (colour, capacity) bitmap.
- Ring track and beds: draw once into a cached layer; only the bean blits move.

## 5. Milestones

1. **M1: skin switch + beans + skeins.** `Skin` interface, `FlatSkin` (identical output, screenshot-compared),
   `KittenSkin` with the tweaked palette, vector bean tokens (ring, queue, walkers as beans), vector skeins S/M/L with
   needle and charm band, cream board. Debug toggle. Atlas + board-layer cache from day one.
2. **M2: dock + ring theme.** Cushions at slot size, docked skein, paw dots + count pill, peeking heads, skein roll-away
   on dispatch; themed track with bed seats, cushion-band boarding window, entry door, Fredoka "+N" pill.
3. **M3: chrome.** Fredoka font, pack buttons and icons, top bar, home logo + hero (sitting sprites), level nodes,
   win and lose dialogs.
4. **M4: animated kittens.** User runs the Gemini pose workflow (with `walk_front` and `sad` added); `beans.py` and
   sprite atlases; walk/hop and celebrate hooks; win/lose kittens.
5. **M5: release polish.** Felt texture, app icon, app name/applicationId rename to Kitten Jumble, default skin flip,
   remove `FlatSkin` or keep it as an accessibility option.

## Open questions
1. Adopt the tweaked palette (fixes lavender/plum and the CVD pairs), or keep the pack colours and rely on charms?
2. Charms for the 5 new colours: fish, bow, drop, crown, diamond?
3. Ring beans are 17.5 dp today (40 seats on an 857 dp perimeter, max ~20 dp without overlap). Accept that, let beans
   overlap slightly to reach ~22 dp, or raise the `ringH` clamp (costs board height)?
4. Walk is 200 ms: hop with one pose, or slow `boardTicks` (changes game pacing)?
5. Dock fill: paw dots, count pill, or both? Peeking heads in the skein?
6. Ring theme: cat-tree carousel (wood ring with beds) or conveyor of round beds?
7. Skin the turn-based mode too, or real-time only?
8. When to rename the app id/package (`com.phloots.busjam`) and make Kitten Jumble the default?
