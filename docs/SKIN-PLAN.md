# Kitten Jumble skin plan

Status: plan only, no app code changed. Revised 2026-10-11 against the locked-in sushi-restaurant direction and the
mocks `docs/skin/mocks/restaurant_level.jpeg` and `docs/skin/mocks/bamboo_spa_level.jpeg`. Rules, solver and level
packs are untouched; the code keeps its neutral terms (Container / Element / Dock / Staging).

Mapping:

| Code term | Kitten Jumble | Notes |
|---|---|---|
| Container (S/M/L = 1/2/3 cells, capacity 4/6/10) | **meal ticket**: pastel strip in the colour, 1/2/3 cells, exit arrow at the front end, one animal-head icon | the icon has no gameplay meaning; it picks the animal species at the table |
| Dock slot (4) | **table / bench at the counter**: ticket holder, the ticket's animals, plate stack, number under the table | |
| Element | **plate of sushi**, rim colour = element colour | |
| Staging ring (20-40 seats, per level) | **sushi conveyor loop**; shape and surroundings per theme | ring size per level is being implemented by another agent |
| Queue | **winding belt** from the kitchen (noren curtain) to the loop's entry point | |
| Boarding window (6 seats at the gate) | the stretch of conveyor right above the counter | |

All sizes are from the current Compose layout (`RingLayout` in `ui/game/RingField.kt`, `DockBoardArt.kt`) on a
411 x 914 dp phone (field width 395 dp after 8 dp side padding; screenshot `app/build/screenshots/v2_rt_2_boarding.png`),
and the compact layout on a 360 x 640 dp phone (`v2_rt_small_phone.png`).

## 1. Layout and mapping per screen

### Current real-time layout (regular)

| Band | Today | Size |
|---|---|---|
| Top bar + debug lines | "< Levels", "Level N", pause, stars, pack/score lines | ~85 dp to the ring top |
| Ring | stadium, queue (2 rows, up to 20 tokens + "+N") **inside** the oval | `ringH` = 0.32 x width, clamped 100-130 (126 dp); perimeter 857 dp; band `seatR*2.5` ~27 dp |
| Seats | `pitch` = perimeter / N; `seatR` = min(pitch*0.46, 11 dp); token = min(pitch*0.82, seatR*1.8) | N=40: pitch 21.4, token 17.5 dp; N<=26: token capped at 19.8 dp |
| Dock | 4 slots, 8 dp gaps | 92.8 x 41.4 dp each + 9 dp badge room |
| Board | 12x14 | cell 32.9 dp, 395 x 461 dp, top at y = 200 dp of the field |
| Slack | | ~110 dp unused between board and buttons on 914 dp |

Compact (360 x 640): ring 84 dp, slots 80 x 31 dp, board cell 22.5 dp, no slack.

### Target layout (regular, 411 x 914)

| Band | Content | Height |
|---|---|---|
| Top bar | "Level 12" pill, pause, hint bulb (mock); stars move to the win dialog | 48 dp |
| Queue belt | kitchen noren top-left (44 x 44 dp), belt runs right, U-turns, runs left, drops into the loop's entry at top centre; plates 20 dp, pitch 22 dp, lane width 26 dp | 64 dp |
| Conveyor loop | stadium (both mock themes), band 28 dp; inside = theme art (empty floor / hot spring) | 130 dp |
| Counter | ticket holders (22 dp), animals seated at the tables (~40 dp), table legs (10 dp), number pill (14 dp) | 88 dp |
| Board | 12x14 at cell 32.9 dp (width-limited) | 461 dp |
| Bottom bar | undo, restart (mock); hint is at the top | 56 dp |

Sum with 4 x 4 dp gaps: 863 dp, inside the ~870 dp available under the status bar. The 110 dp slack plus dropping
the debug lines pays for the belt and the taller counter. On 10x12 boards the cell is still width-limited (39.5 dp
at 10 wide would be 474 dp tall; clamp the cell to 34 dp, leaving room).

Compact (360 x 640): no room for the belt. The queue goes back **inside** the loop (as today: two rows of 14 dp plates,
"+N"); the loop's inner art is then just the belt. Counter shrinks to 56 dp (animals 28 dp, no legs, number pill
overlapping the table front). Board cell drops from 22.5 to ~21.4 dp. Rule: belt above the loop only when the board
still gets cell >= 28 dp; otherwise the inside-the-loop queue.

### Conveyor path per theme (variable ring size)

Replace the hard-coded stadium in `RingLayout.trackPoint()` with a path spec:

```kotlin
class TrackSpec(
    val loop: (Rect) -> Path,                  // closed centre-line, built inside the ring rect
    val gateFraction: Float,                   // arc-length fraction of the window centre (bottom middle)
    val entryFraction: Float = gateFraction + 0.5f,
    val belt: ((Rect, entry: Offset) -> Path)?, // open queue path, kitchen -> entry; null = queue inside the loop
    val bandDp: Float = 28f,
)
```

- At layout time, sample the loop with `PathMeasure` into a 512-point arc-length LUT (positions + tangents).
  `trackPoint(d)` becomes a LUT lookup; `seatPoint(pos, gate)` is unchanged (seat `k` at `L * (pos - gate) / N`).
- Plate size: `min(pitch * 0.88, band * 0.82)`. N=40 on the 857 dp stadium: 18.8 dp; N=30: 23 dp; N=20: 23 dp with
  belt showing between plates (fine on a conveyor).
- Constraints for any loop shape: the stretch under the gate must be a horizontal straight at least
  `gateWidth * pitch` long (128 dp at N=40, 257 dp at N=20) centred over the counter; the entry is at the top,
  where the belt joins.
- Queue belt: plates at fixed pitch along the belt path; visible count = belt length / pitch (cap at `QUEUE_MAX`,
  today 20; see open questions).
- `FlatSkin` uses a stadium spec identical to today's geometry, so screenshots stay unchanged.
- Both mocks are stadiums (restaurant: empty floor inside; spa: hot spring inside). Later themes can use a rounded
  rectangle or a kidney shape with the same spec.

### Game screen elements

| Concept | Today | Kitten Jumble | Size |
|---|---|---|---|
| Ring band | grey stroke | wood rim + cream belt, faint chevrons in the direction of travel | 28 dp band |
| Seat | white circle | nothing (plates sit on the belt); empty seats show belt | |
| Element on ring | `drawElement` shape token | plate: coloured rim disc, white well, sushi topping | 18.8-23 dp |
| Boarding window | gold band + ticks + triangle | warm glow strip on the belt above the counter, small serving hatch arrow | `gateWidth * pitch` |
| Entry | dashed ring | belt joins the loop (merge chevrons) | |
| Queue | 20 tokens inside the oval + "+N" | plates on the winding belt from the noren; "+N" tag on the noren | 20 dp plates |
| Walker | token hop arc, 17.5 -> 8.8 dp | plate lifts off the belt and arcs to the table in front of its animal | 19 -> 14 dp |
| Dock slot | grey rect | table/bench + ticket holder | 92.8 dp wide, 88 dp band |
| Docked container | bus | ticket clipped in the holder (mini ticket, colour + icon) and 1-3 animals tinted in the ticket colour | ticket 14 x 22 dp |
| Seated elements | seat dots | single empty-plate stack beside the animals (2.2 dp per plate) | up to 22 dp tall |
| Remaining badge | ink pill under the slot | cream number pill under the table: plates still needed | 14 dp, Fredoka 11 sp |
| Dispatch | bus lifts away | animals hop happy, ticket unclips, animals walk out sideways, stack clears | 400 ms |
| Board | asphalt + cells | per theme: wood tray (restaurant), tatami in a bamboo frame (spa) | as today |
| Container on board | bus + `boardIcon` | ticket: cells divided by dashed lines, arrow chevron at the front, icon disc on the middle/back cell | body 27 dp thick |
| Blocked bump, hint | nudge, gold halo | same motion, butter halo | |

### Other screens

| Screen | Kitten Jumble |
|---|---|
| Home | logo (Fredoka, multicolour outline), hero: animals at a counter with a conveyor (raster), Play (mint pill), Levels (pink pill); debug pack list, real-time switch and a **Skin** switch |
| Level select | round cream nodes, current = butter, locked = beige with padlock; optional theme banner per chapter (restaurant, spa) |
| Win dialog | "Delicious!" / "Purrfect!" card, 3 stars, Next; the level's animals happy with plate stacks |
| Lose dialog | "Kitchen jammed!" (dock full) / "All stuck!" (no moves), a hungry animal with an empty plate, Undo + Restart |

## 2. Asset inventory

### Vector (Compose `DrawScope` code in `ui/skin/kitten/`)

| Asset | Variants | Notes |
|---|---|---|
| Meal ticket | 3 sizes x 10 colours x 4 directions | rounded rect, darker outline, dashed cell dividers, small perforation notches at the back end, chevron arrow at the front, white icon disc (raster head inside). Arbitrary length so animations can squash. |
| Plate | 10 rim colours | outer disc in the colour (rim = 30 % of radius, lighter top highlight), white well, dark outline 1 dp; topping blitted from the raster atlas |
| Plate stack | 1-10 plates | ellipses offset 2.2 dp, rim colour of the ticket; split into two stacks above 6 if the L table is crowded |
| Conveyor band, belt, chevrons, merge, glow window | per theme colours | follows the `TrackSpec` LUT; cached layer |
| Table / bench, ticket holder, counter top | restaurant wood, spa bamboo | slot-sized (92.8 dp wide) |
| Number pill, buttons, Level pill, pause, bulb, undo, restart, stars, dialog cards | | port from the old pack's `ui/assets.html` |
| Hot-spring water, board trays/frames | per theme | flat fills; props on top are raster |

### Raster (Gemini, then `tools/process.py` cutout/tint)

| Asset | Count | Form |
|---|---|---|
| Animals, untinted cream fur | 6 species x 6 frames: `seated`, `eat_a`, `eat_b`, `happy`, `walk_a`, `walk_b` (side view) = 36 | two layers per frame: fur mask (tinted at runtime) + ink/details (untinted); ~120 px tall at 3x (40 dp) |
| Animal head icons | 6 | natural colours (the mocks do not tint the icons), 36 px (12 dp at 3x) |
| Theme accessory overlays | spa towel per species (6); restaurant none or a bib | anchored to a head point in a JSON table |
| Sushi toppings, top view | 10 types | 64 px each, one atlas; drawn at ~50 % of plate size (9-12 dp) |
| Kitchen noren portal | 2 (restaurant, spa) | ~44 dp |
| Background props | restaurant: lantern, menu board; spa: bamboo stalks, stone lantern, rocks, steam puffs | placed by the theme in margins only (see section 3) |
| Backgrounds | restaurant wall, spa shoji wall | 1080 x 1100 px, top part of the screen behind the belt and loop; loop interior is vector |
| Home hero, app icon, win/lose animals | 4 | |

Species: kitten, bunny, hamster (all three seated in the mocks), red panda, grey tabby, fox (icons in the restaurant
mock). Kitten is the default and most frequent.

Animals per table = ticket cells: S = 1, M = 2, L = 3 (matches the mocks: 2 bunnies, 2 kittens, 1 hamster). Plates
go to the animal with the fewest; the stack is shared. On a 92.8 dp table, 3 animals at ~26 dp plus the stack fits
with slight overlap.

Tinting: ship one untinted 2-layer atlas; when a ticket docks, bake its (species, colour) frames into a small cache
(at most 4 tables x 6 frames). No 360-frame pre-tinted atlas.

### Gemini prompts

Attach both mocks as the style reference to every prompt. Shared preamble:

> Cozy kawaii mobile puzzle game art matching the attached reference images exactly: flat pastel fills, thick
> dark-brown (#5B3A29) outlines of even weight, soft cel shading in one darker tone, no gradients, no text, no drop
> shadow, centred on a plain pure-white background with generous margin.

Animals (one prompt per species and pose; generate cream fur so the app can tint it):

> [preamble] A chubby [kitten | bunny | hamster | red panda | grey tabby cat | fox] with cream-white fur (#FFF6E8)
> and pale-pink inner ears, seen from the front, [sitting on its bottom, paws resting on its belly, content smile |
> holding a piece of salmon nigiri to its open mouth with both paws | chewing with cheeks puffed and eyes closed
> happily | arms raised in joy, eyes as happy arcs, tiny hearts around the head | walking to the right seen from the
> side, mid-step with the left foot forward | walking to the right seen from the side, mid-step with the right foot
> forward]. Full body, 1024 x 1024.

(Red panda and fox: "keep the face markings dark brown and white, everything else cream" so the fur mask still tints.)

Head icons:

> [preamble] Head only of the same [species] in its natural colours, front view, simple, readable at 12 px, 256 x 256.

Spa towel overlay:

> [preamble] A small folded white towel resting on top of the head of the same [species], drawn alone without the
> animal, positioned as it would sit on the head, 1024 x 1024.

Sushi toppings (one sheet, then cut):

> [preamble] Ten sushi pieces seen exactly from above, each on its own with space around it, arranged in a 5 x 2
> grid, no plates: salmon nigiri, tuna nigiri, tamago nigiri with a nori band, shrimp nigiri with tail, cucumber maki
> (round, nori ring), salmon maki, ikura gunkan, california roll with sesame, inari pouch, onigiri triangle with nori.
> Strong distinct silhouettes, readable at 32 px. 2048 x 820.

Kitchen portal:

> [preamble] A small dark kitchen doorway with a white three-panel noren curtain hanging from a [wooden | bamboo]
> rod, seen from the front, a short stub of conveyor belt coming out at the bottom, 512 x 512.

Backgrounds:

> [preamble, but "full-bleed, no white background, no characters, no conveyor"] Portrait game background 1080 x 1100
> for the top half of the screen: [a cozy sushi restaurant wall in warm cream and light wood, a wooden counter edge
> along the bottom | a Japanese bamboo spa: shoji paper walls on the left, bamboo stalks at both edges, paper
> lanterns, grey river stones on a pale stone floor]. Keep the centre area plain and low-contrast for game pieces.

Props (one sheet per theme):

> [preamble] Separate small props in a 4 x 2 grid with space around each: [paper lantern, menu stand, soy sauce
> bottle, chopsticks cup, teapot, small plant | stone lantern, bamboo stalk with leaves, two grey river stones,
> wooden bath bucket, rolled towel, steam puff].

Home hero:

> [preamble] Wide banner 1600 x 600: a kitten, a bunny and a hamster in pastel pink, mint and sky colours sitting
> at a wooden sushi counter, eating from colourful sushi plates on a small conveyor belt, stacks of empty plates.

App icon foreground (adaptive icon, 432 x 432, safe zone 264):

> [preamble] Head of a cream kitten peeking over a pink-rimmed sushi plate holding salmon nigiri, centred, occupying
> the middle 60 % of the canvas. (Background layer: flat `#F7D5A5`.)

## 3. Problems in the mocks

1. **Decorations in grid cells read as blockers.** Restaurant: soy sauce bottle and chopstick cup in empty cells.
   Spa: stones, two buckets, rolled towel. The rules have no obstacles. Keep props off the board: only in margins,
   on the board frame, or in the loop interior. Game boards are 85-95 % full anyway, so cell decoration would rarely
   show.
2. **No boarding window.** Nothing shows where plates can leave the belt. Add a glow strip on the 6 seats above the
   counter centre; at N=20 it spans 257 dp, at N=40 128 dp.
3. **Number under the table is ambiguous** (2 / 5 / 1 next to stacks of 0 / ~5 / 3 plates). Proposal: plates still
   needed (= today's remaining-capacity badge), decremented when a plate leaves the belt (space is reserved at the
   gate). Blank when the table is free.
4. **Fourth table** has a charcoal ticket in its holder but no animals and no number; and the restaurant counter
   has 8 holders for 4 tables (small tan menu stands between them). One holder per table; empty table = empty holder.
5. **Grid sizes.** Restaurant board is about 14 x 9, spa about 12 x 8, both wider than tall and ~55 % filled. Game
   boards are 10x12..12x14 (taller than wide, ~90 % filled). At 395 dp width a 12x14 board is 461 dp tall vs the
   mock's ~250 dp; the mock's tall belt area (~45 % of the screen) does not fit with a game board. Section 1 budgets
   64 + 130 + 88 dp instead of the mock's ~165 + 125 + 100 dp.
6. **Counter height on small phones.** The mock counter is ~100 dp; on 360 x 640 there is no room. Compact counter
   56 dp and queue inside the loop (section 1).
7. **Plate colour at 17-20 dp.** The mock rims are thin (~10 % of radius) with a large white well; at 18 dp that is
   a ~1.8 dp ring and pastel rims blur together. Widen the rim to ~30 % of the radius and keep the topping small.
8. **Colours.** Restaurant uses the 10 pack colours (two pinks, two purples, charcoal). The spa mock adds taupe and
   teal tickets that are not in the palette, and its pinks vary. Use one 10-colour palette for all themes (tweaked
   palette below).
9. **Sushi type as a second cue.** The mocks pick toppings at random. Tying type to colour (one silhouette per
   colour) gives colour-blind players a cue on the plates, but the ticket would then need the same cue (a small
   topping glyph on the ticket or the animal holding it), and the animal icon is already there. Open question.
10. **Ticket details vary.** Some L tickets have no icon, some show the icon on the front cell, the spa purple
    ticket has a double chevron and a green one an "8"-like glyph. Fix one rule: icon on the back cell, arrow on the
    front cell, nothing else.
11. **Animals tinted vs icons untinted.** The seated animals take the ticket colour (good cue at the table), the
    board icons do not (fine, they carry no meaning). Keep it that way so the icon never competes with the colour.
12. **"15/20" counter** at the bottom has no meaning in the game today (tickets served / total? plates?). Levels
    have up to ~128 tickets. Open question; it may also reveal information the user wants hidden.
13. **Queue length.** The mock shows ~27 upcoming plates and no "+N"; today the game shows 20 and "+N".

## 4. Animation hooks (real-time model)

Timing at 0.1 s per seat (`RtConfig`: 4 ticks per seat, tick 25 ms): release 12 ticks = 300 ms, board 8 = 200 ms,
stagger 3 = 75 ms, dispatch 16 = 400 ms. Inputs: `RtSlotView(containerId, color, capacity, seated, releasedAt,
dockedAt, dispatchAt, freeAt)`, `RtWalkerView(color, slot, startTick, arriveTick, fromPosition)`, `RtEvent`.

| Moment | Model | Visual |
|---|---|---|
| Ticket released | `releasedAt..dockedAt` | ticket slides off the board (existing drive), shrinks to the mini ticket and clips into the holder; animals hop onto the bench during the last 150 ms so they are seated at `dockedAt` (plates can arrive that tick) |
| Plate leaves the belt | walker `startTick`, `BoardingStarted` | plate lifts off its seat (seat empties), number pill decrements, haptic tick |
| Plate travels | `startTick..arriveTick` (200 ms) | arc from `seatPoint(fromPosition)` to the spot in front of the target animal, 19 -> 14 dp; no walk cycle needed (resolves the old "walk is too short" question) |
| Eat | walker arrival | target animal plays `eat_a`/`eat_b` (120 ms each) while it has plates in front; topping vanishes, empty plate drops onto the stack. Waves arrive every 75 ms, so eating is a loop, not one animation per plate |
| Complete | `dispatchAt..freeAt` (400 ms) | 0-200 ms: `happy` + hearts, ticket unclips and floats up; 200-400 ms: animals turn and walk out sideways (`walk_a/b`), stack fades |
| Slot free | `freeAt` | leaving animals may keep walking off-screen after `freeAt` as decoration under the next arrivals; no gameplay effect |
| Blocked / ignored | `Blocked`, `Ignored` | ticket bump; the animal icon wobbles |
| Lose / win | `status` | all seated animals switch to a sad or happy frame behind the dialog |

Species per ticket: `theme.speciesPool[hash(containerId) % pool.size]`, deterministic, until the icon gets a
gameplay meaning.

## 5. App integration

### Skin layer
- Package `app/.../ui/skin/`:
  ```kotlin
  interface Skin {
      val colors: SkinColors                       // background, board, ink, per-index colour + name
      fun theme(level: Level): ThemeSpec           // TrackSpec, backgrounds, props, table style, species pool
      fun DrawScope.background(lay: RingLayout, theme: ThemeSpec)
      fun DrawScope.board(geo: FieldGeometry, level: Level)
      fun DrawScope.container(center: Offset, width: Float, length: Float, degrees: Float, color: Int,
                              icon: Int, alpha: Float = 1f, highlight: Float = 0f)        // ticket
      fun DrawScope.dockSlot(geo: FieldGeometry, slot: Int, view: RtSlotView?, reserved: Int, tick: Float)
      fun DrawScope.track(lay: RingLayout, view: RtView)                                  // loop + belt + window
      fun DrawScope.element(color: Int, center: Offset, size: Float, alpha: Float = 1f)   // plate
  }
  ```
- `FlatSkin` wraps today's functions (`drawContainer`, `drawElement`, `drawDockSlot`, asphalt board, stadium
  `TrackSpec`) with no visual change, screenshot-compared. `KittenSkin` is the new one. Provide via `LocalSkin`.
- Call sites: `RingField.kt` walkers (~271), queue (~329), seats (~345), track drawing; `DockBoardArt.kt` board,
  containers, dock; `HomeScreen.kt` hero. `Palette.name()` in content descriptions -> `skin.colors.name()`
  ("pink ticket facing up", "pink plate").
- Debug toggle: "Kitten Jumble skin" switch on the home screen next to the real-time switch, stored like
  `mode.realTime` in `AppViewModel`; debug-only until complete.
- Scope: real-time screen only.

### Atlas drawing and caching
- No composable per plate or animal; everything stays in the one `Canvas`.
- Toppings, heads, animals, props: `drawImage(atlas, srcOffset, srcSize, dstOffset, dstSize, filterQuality = Low)`
  from `res/drawable-nodpi/` atlases + a generated Kotlin rect table. 40 seats + ~28 queue + ~3 walkers + 12 animals
  = ~85 blits per frame.
- Plate rims are vector; bake 10 rim discs per plate size into one runtime atlas when the layout changes.
- Cached layers via `GraphicsLayer` / `drawWithCache`: (a) background + loop band + belt + tables (invalidated on
  layout/theme change); (b) board + on-board tickets (on `onBoard`, hint, layout change). Bumping, hinted and flying
  tickets draw live on top.
- Docked mini tickets and tinted animal frames: per (colour, species) cache, baked on dock.

### Font
- `fredoka-latin.woff2` from the old pack does not load on Android. Use the variable `Fredoka[wdth,wght].ttf` (Google
  Fonts, OFL) in `res/font/` + `OFL.txt` in `assets/licenses/`; theme `Typography` and canvas `TextMeasurer` styles
  (number pills, "+N", "Level 12").

### Palette (carried over from the old plan; applies to ticket colour, plate rim and animal tint)

CIEDE2000 between the pack colours, CVD = Machado 2009 severity 1.0. Worst pairs: lavender/plum 13.5; deutan
sky/lavender 3.5, pink/mint 5.8; protan blueberry/plum 5.5; tritan mint/sky 4.4. Most pack colours sit at L 76-90,
so separation relies on hue. Proposed palette spreads lightness from 48 to 90:

| Colour | Pack | Proposed | L* |
|---|---|---|---|
| pink | `#F9B8C9` | `#F4A6BE` | 76 |
| mint | `#B0E5C1` | `#A0E2B2` | 85 |
| sky | `#B0D7F2` | `#A4E0F2` | 86 |
| butter | `#F9E096` | `#F9E096` | 90 |
| lavender | `#CDBDF2` | `#B8A4EC` | 71 |
| tangerine | `#F8AA70` | `#F8A360` | 74 |
| cherry | `#EA6E78` | `#E25864` | 56 |
| blueberry | `#7A92E2` | `#6884DE` | 57 |
| plum | `#D892D6` | `#A054A4` | 48 |
| charcoal | `#8C8898` | `#787484` | 50 |

Result: closest normal-vision pair 17.2 (was 13.5); deutan minimum 9.5 (was 3.5); protan minimum 8.0 (was 5.5).
Tritan mint/sky stays ~4.6; a second cue (sushi type per colour, problem 9) would cover it. The mocks' pastel rims
are lighter than this; plates need the darker values for the rim to read at 18 dp.

## 6. Milestones

1. **M1: skin switch + tickets + plates.** `Skin`, `FlatSkin` (identical, screenshot-compared), `KittenSkin` with the
   palette; vector tickets S/M/L with arrow and a placeholder icon disc; vector plates (rim + placeholder topping
   dot); restaurant colours for board and background. Debug toggle. Board-layer cache from day one.
2. **M2: `TrackSpec` + conveyor.** LUT-based track replacing `trackPoint()` (stadium spec = today), variable N plate
   sizing, themed band, boarding-window glow, queue belt above the loop with the kitchen stub, compact fallback
   inside the loop.
3. **M3: counter.** Vector tables, holders, mini ticket clip-in, plate stacks, number pill; dispatch clears the
   table. Placeholder animals (vector blobs in the colour, 1-3 per table).
4. **M4: raster art.** User runs the Gemini prompts; cutout + fur-mask split in `tools/process.py`; atlases for
   animals, heads, toppings, noren; eat / happy / walk hooks.
5. **M5: themes.** `ThemeSpec` per level (restaurant, bamboo spa: hot-spring interior, bamboo benches, towel overlay,
   props in margins); backgrounds.
6. **M6: chrome + release.** Fredoka, buttons, top/bottom bars, home hero, level nodes, win/lose dialogs, app icon,
   app name/id rename, default skin flip.

## Decisions (user, 2026-10-11)

- **Scope first:** ship one polished theme (sushi bar) before any other theme or new mechanic. The art pipeline comes
  first; the world list and new mechanics in `art/themes/worlds.json` are proposals only.
- **Target device:** Pixel 9 Pro. If it looks good there, it's good; the compact 360 x 640 layout is not a target.
- **Number under the table:** plates (elements) still needed; it counts down to zero.
- **Colour lives on the plate rim.** Sushi type is not tied to colour.
- **Species to start:** kitten, red panda, fox, wolf.
- **UI:** top bar has the level label, pause and settings. Restart, undo and hint move into the pause menu. The
  mock's "15/20" was meant to be the level number; show the level somewhere, not necessarily there.
- **Theme selection:** per chapter (a run of levels shares one look), not per level.
- **Palette:** the pastel palette, tweaked where needed for readability.

## Open questions

1. Animals per table = ticket cells (S 1, M 2, L 3)?
2. Queue: how many upcoming plates on the belt (today 20 + "+N"; the mock shows ~27 and no "+N")? Keep "+N"?
3. Rename app id/package (`com.phloots.busjam`) and make Kitten Jumble the default: when?
