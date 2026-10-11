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
