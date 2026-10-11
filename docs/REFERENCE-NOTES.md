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
