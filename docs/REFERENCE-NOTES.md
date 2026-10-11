# Reference-game observations (structural facts only, from screenshots of normal play)

Used to calibrate our generator and scorer. Never copy layouts into shipped levels.

## gridplus.busjam.carpuzzle ("Bus Fever"-style), installed on BusJam_Phone

### Level 2 (2026-10-11)
- Parking: 4 normal slots + 1 VIP slot + 3 locked "+" slots (likely purchasable/ad-unlocked).
- Waiting area: single long line circulating on a loop track (no grid); any matching passenger boards a docked bus.
- Queue: long single-colour blocks of roughly 15–25 (purple, orange, pink, orange, purple, pink, orange, green, …);
  the whole queue is visible from the start.
- Board: no visible grid; 12 buses, apparently all the same length (~2 units), in two mirrored 2×3 clusters.
- Colours: 4. Direction shown by a large roof arrow.
- Difficulty: tutorial-level, shallow blocking.
- Unknown: bus capacity, later size variety, VIP slot rule.

### Level 3 (2026-10-11)
- 22 vehicles, 3 colours (blue, yellow, red). Three visual types: sedan car (short), tall-roof van (similar length),
  long bus (~1.5× a car by pixels, so lengths roughly 2:3, not 1:2:3).
- Queue: blue/yellow blocks with short red islands (2–4) inside them — interrupted runs already at level 3.
- Board: mirror-symmetric (left/right); difficulty concentrated in a vertical central spine (bus ↓, bus, car ↓,
  car ↓); sides are easy pairs. Roughly grid-aligned with some half-cell row offsets.
- A worried-face emoji appears over a waiting passenger (likely a waiting/crowding warning).
- Patterns worth reusing (generic, not layouts): symmetry + central spine for early readability; short islands
  early to teach interrupted runs; a pressure warning indicator.

### Level 4 (2026-10-11)
- 5 colours (cyan, pink, green, yellow, purple); ~30 vehicles (cars + buses).
- Board: mirror-symmetric again — two U-shaped pockets with an open centre; inner vehicles face down and are walled
  in by a bottom row that must be removed in order.
- Late colour as bait: purple not in the visible queue yet, but 3–4 purple cars sit free on the outer corners —
  tapping them early parks dead weight ("plausible but poorly timed option").
- VIP booster introduced (count 1): airlifts any bus to the VIP slot, bypassing blocking. Consumable, not a level
  element.
- Mood emoji on passengers (sleepy).
- Idea for us (later): VIP as a 5th one-off dock slot, either as a booster or as a per-level planned mechanic.

### Level 5 (2026-10-11)
- Angled vehicles: most at ~45°, a straight vertical spine through the middle — effectively 8+ directions.
- Radial "sunburst" layout with 180° rotational symmetry (not grid-based); two small cars at the centre.
- Blocking = whether a diagonal exit path crosses another vehicle's body — harder to read, difficulty without more
  vehicles.
- 5 colours, ~32 vehicles; queue: yellow-heavy front, pink/orange middle, purple late.
- For us (later): cheap step = 8 grid directions (diagonal lanes, same cell-based blocking); full step =
  continuous geometry (swept-rectangle exit paths, free placement). Engine dock/staging/solver logic unchanged.

### Level 6 (2026-10-11; user reported "level 8", screen showed Level 6)
- Themed silhouette board: a heart, vehicles at free angles throughout.
- 7 colours (red, green, purple, blue, yellow, cyan, brown); ~40 vehicles (cars, vans, buses).
- Squatter pattern: a large brown block near the front of the queue while most brown vehicles are packed in the
  centre of the heart — brown fills the waiting area while the centre is dug out.
- Mood emoji escalates (angry) — likely tied to waiting-area fill / difficulty.
- For us: shaped boards (heart/star/letters; for Kitten Jumble paw prints, fish, cat heads) via a per-level cell
  mask on our grid — visual variety without angles or engine changes.

### Level 7 (2026-10-11; user reported "level 9" — in-game numbers run ~2 lower than the user's count)
- Dominant colour: brown ≈ half the queue, ~a third of the vehicles; short islands of other colours (orange ~4,
  purple/blue small, cyan larger) scattered through the brown — repeated interrupted runs.
- 7 colours (brown, blue, cyan, purple, orange, yellow, green); ~45 vehicles.
- Diagonal "domino stacks": 4–5 parallel vehicles on one diagonal, nose to tail; only the end one can leave →
  visible fixed-order chain with a designed colour order inside.
- Loose mirror layout: chevron of stacks at top, central spine (brown car on yellow bus), rows of upright vehicles
  at the bottom.
- For us: deliberate visible stacks (same lane, same facing) whose internal colour order fights the queue.

### Level 7 mid-level + user observations (2026-10-11)
- Mid-level: one blue bus docked with a "24" badge (likely seats remaining — would imply large bus capacity; to
  confirm). Board cleared mostly from the right; diagonal stacks untouched with brown at their bottoms; brown still
  dominates the queue front with green/purple islands. Dock nearly empty — pressure here is route planning.
- **Boarding takes time** (passengers circulate around the island): impatient players dock a second same-colour bus
  before the first fills, which can wreck the level — a real-time "impatience" trap. Ours is turn-based (≤300 ms
  animations, queued taps). Our left-slot-steal rule already makes the double-dock costly. Decision for now: stay
  turn-based; later options: slower visible boarding as a feel setting, or a gentle warning when docking a second
  container of a colour that's still filling.
- **Any-angle vehicles** make "is this free?" harder to read. User: keep 4 directions for now; note angles for later
  (8 grid directions first).
- UI idea: a number badge of remaining capacity under each docked container (clearer than our seat grid).

### Level 8 (2026-10-11)
- Back to 4 directions, axis-aligned (slight perspective only); dense asymmetric cluster, ~30 vehicles.
- 7 colours (yellow, cyan, orange, blue, red, pink, green); queue more mixed, shorter blocks, a large red block.
- New booster "Arrange" (count 1) — likely reorders/sorts the waiting passengers. Boosters so far: VIP, Arrange.

### Level 9 — first level labelled "hard" by the game (2026-10-11)
- 9 colours (cyan, orange, red, green, blue, pink, brown, yellow, purple), up from 7.
- ~40 vehicles, mostly long buses — big capacities sit longer in the dock → pressure from the vehicle mix.
- Queue: very mixed, short blocks of many colours, no long single-colour runs → docked buses fill gradually.
- Dense central core: three upright columns of 3 stacked vehicles all facing up; horizontal clusters above on both
  sides; a U-shaped wall of horizontal buses at the bottom sealing a downward-facing bus in the centre.
- Mirror symmetry; angled cars in side pockets.
- Calibration point: "hard" here = more colours + short mixed queue blocks + mostly large vehicles + dense core —
  not special mechanics.

### Level 9 mid-level — "the next move isn't obvious" (2026-10-11) — reference decision moment
- Dock: one pink bus, badge "8" = seats remaining (badge counts seats left; buses hold more than our 10 — the
  earlier "24" suggests large capacities).
- Waiting ring dominated by yellow (+ some cyan/red/blue/green); pink far back in the line → pink bus is dead weight
  for a long time.
- Yellow options: a small angled yellow car in the right pocket (cheap, absorbs few) vs two big yellow buses buried in
  the central core (absorb many, but require docking off-colour dummies to dig out). Most reachable vehicles are
  off-colour (orange/red/blue buses at the top) = more dead weight beside the pink.
- Decision structure = big-vs-small + stall (how many dummies can you afford with one slot already dead) + a
  squatter-like yellow surge. Target feel for our hybrid generator.

### Level 9 after the choke point (2026-10-11)
- Dock: pink bus in slot 1 (badge 8 → 4), a second pink (van, from the top-right cluster) in slot 3 with badge "24";
  slots 2 and 4 free. A large pink block is approaching on both arms of the line — the player pre-docked a second
  pink to absorb the run (left slot fills first, matching our left→right priority).
- Yellow still dominates the ring with both big yellow buses buried in the core: the player got through by riding
  the pink run, not by digging for yellow. Top clusters mostly cleared → route into the core opening.
- Badge semantics unclear: the van shows 24 while the bus showed 8 — maybe not "seats left in this vehicle"
  (to confirm with the user).
- (Level 9 outcome: "it was easy after that" — one choke point, then straightforward.)

### Level 10 — new mechanic: hidden colours (2026-10-11)
- ~14 vehicles are dark grey with "?"; colour revealed only once the vehicle is unblocked (exit path clear). They form a
  diagonal band through the centre, coloured vehicles around/between them.
- Played as "quite easy" (user). Only 4 colours (pink, yellow, blue, purple) and a simple pink-heavy queue, right after the first hard level (9
  colours) → sawtooth: hard level, then breather + new mechanic.
- For us: engine unchanged (knows colours); UI hides colour until the container's exit lane is clear; solver checks
  solvability with full information; bots/difficulty scoring must be information-limited (can't see hidden colours);
  hints must not leak hidden colours. Generator can hide stall dummies or a chain link so the trap reveals late.

### Level 11 — new world (2026-10-11)
- New theme (cherry blossom: pink ground, stone lantern, bamboo fountain) — worlds appear to change every 10 levels.
- Hidden colours again, now with 7 visible colours (pink, yellow, purple, green, blue, cyan, brown): ~13 hidden
  vehicles in a radial ring inside an outer ring of coloured ones; ~40 vehicles; mirror-symmetric radial layout with
  a short central spine (brown → blue, facing up) and an upright row at the bottom.
- Queue: pink-heavy front, then cyan, green, purple, blue.

### Level 12 (2026-10-11) — closest analogue to our format
- Strict dense rectangular grid, all axis-aligned, 4 directions, packed nearly wall to wall. By pixels a car ≈ 2
  cells, a long bus ≈ 3 cells, grid ≈ 12–13 cells wide — very close to our M/L sizes and board widths.
- Two dense halves separated by a one-column central gutter; vehicles facing across the gutter are blocked by the
  other half.
- ~13 hidden ("?") vehicles throughout, including outer-edge ones (first moves are reveals).
- 5 visible colours (blue, red, pink, yellow, brown); blue dominant on board and at queue front; ~40 vehicles, many
  blue buses.
- Use as a calibration target for our hybrid generator (same ingredients).
- Level 12 played: "a bit more challenging — it's not always obvious what the right move is" (user).

### Level 13 — Jumble (2026-10-11; no screenshot captured)
- Introduces "Jumble": changes the colours of the vehicles (user saw it as a mechanic; at level 14 it appears as a
  third booster with count 1, so it's most likely a consumable that re-colours/shuffles vehicles).

### Level 14 (2026-10-11)
- Boosters now VIP, Arrange, Jumble (each ×1).
- Layout: a loose ring/swirl of vehicles mostly facing outward around a central "T" of two orange buses plus two
  yellow buses; inner arc of downward-facing vehicles above a bottom row.
- 8 colours (blue, purple, pink, green, cyan, orange, brown, yellow); ~45 vehicles; queue in big blocks (cyan, pink,
  purple, blue, orange).

### Level 14 mid-level — squatter in action (2026-10-11, user-highlighted)
- Blue elements fill a large share of the loop (~20+ spots near the gate) → usable waiting area roughly halved.
- Blue vehicles: two big buses buried in the lower arc (one inner facing down, one in the bottom row facing left),
  boxed in by cyan/yellow/pink. Other colours (cyan, green, pink, yellow) are released to dig them out; cyan van
  docked with 4 remaining.
- Tension: each digger needs its colour to come round through the few free spots; digging slowly overflows; a digger
  whose colour isn't coming parks as dead weight.
- Variant of principle 11: the squatter's own containers are BIG and buried; relief comes from digging with other
  colours rather than small same-colour valves.
- Readability note: their blue vs cyan are close in hue.

### Level 15 — first "super hard" (2026-10-11)
- ~70 vehicles packed into a diamond silhouette at ~100% density — almost no empty space; only the outer rim is free.
- A 4-direction grid rotated ~30° (two diagonal axes), not free angles — same rules, harder to trace visually.
- 9 colours (orange, yellow, green, red, blue, pink, brown, purple, light blue); very mixed queue of short blocks, no
  dominant colour, no long runs. No hidden vehicles or special mechanics.
- Super-hard recipe: max density + many colours + short mixed queue + large board.
- For us: a "dense pack" recipe — 12×14 at ~95% density, 9 colours, mixed queue, ~70 containers (engine supports 128).
- The game announces difficulty before the level ("Hard" at L9, "Super hard" at L15) — sets expectations, makes
  losses feel fair. For us: "Hard"/"Super hard" badge on level tiles + a pre-level banner, label derived from the
  difficulty score (bosses / top percentile), so it's consistent across the campaign.

### Level 15 mid-level — dummy stall at super-hard density (2026-10-11, user-highlighted)
- User: "I needed to park two dummy pink buses, so I only have two docks."
- Dock: pink bus slot 1 badge 40, slot 2 empty, pink bus slot 3 badge 8, red van slot 4 badge 8. No pink on the ring
  → both pinks are pure dummies; effectively 2 slots (one taken by a red van that red on the ring will fill).
- Left-slot steal (catalogue e) live: when pink arrives, slot 1 (needs 40) fills first; the slot-3 pink (needs 8)
  can't complete until slot 1 is full → both pinks hold their slots until ~48 pinks pass.
- Badge semantics still unclear (40 vs 8 on two same-size pink buses).
- Structure = principle 9 dummy stall + principle 8(e) on a ~70-vehicle, 9-colour board: the super-hard reference.

### Level 15 — loss (2026-10-11)
- Final dock: pink 40 | red 8 | pink 8 | blue 16 — all 4 full. Ring full of green/brown/yellow/purple/orange, none
  matching. A large red block waits in the line just outside the ring and can't step on (ring full, no one can board).
- Fatal move: the last free slot spent on a blue bus with no blue on the ring. Keeping it free would have let an
  on-ring colour board, freeing seats for the reds to enter and finish the red van.
- Near-miss loss with the rescue visible just outside the ring, most of the board left — "fair but painful".
- Generator lessons: fork whose wrong answer is "dock a colour nobody on the ring is waiting for" when the line beyond
  the ring holds the needed colour (lookahead depth = reading the line, not just the ring); prefer near-miss losses.

### Level 15 — second attempt, mid-level (2026-10-11)
- Only one pink dummy, the one needing 8, in slot 1; slots 2–4 free; big right-hand cluster untouched; still no pink
  on the ring.
- Versus attempt 1 (pink 40 in slot 1 + pink 8 in slot 3): same decision point, but now the cheap dummy is leftmost
  so it completes first when pink arrives. Attempt 1's choice lost much later → a left-slot-steal fork (catalogue e)
  with a deep horizon, observed in a real "super hard" level.
