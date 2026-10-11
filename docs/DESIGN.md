# Jam puzzle — agreed design (v2)

> v2 (2026-10-10) supersedes v1 wherever they conflict. The game will be skinned as "Kitten Jumble" later; the code
> stays theme-neutral. The v1 text below is kept for history; **read the v2 section first**.

## v2 changes

**Neutral terminology** (code, tests, docs, JSON — UI strings may keep placeholder art for now):

| v1 | v2 |
|---|---|
| Vehicle | Container |
| VehicleType CAR/VAN/BUS | ContainerSize S/M/L |
| seats / seatsLeft | capacity / spaceLeft |
| Passenger | Element |
| Parking slot(s) | Dock / DockSlot (4 slots) |
| (new) | Staging area (20–40 spots, per level) |
| Queue | Queue |
| Exit / park | Release (leaves board, docks) |
| Depart | Dispatch (full container leaves dock) |
| Bump | Blocked |

Packages: `:core` is `com.phloots.jam.core.*` and `:levelgen` is `com.phloots.jam.levelgen`. The `:app` namespace, its packages and the applicationId stay `com.phloots.busjam` until the re-skin.

**Sizes:** S = length 1 / capacity 4, M = length 2 / capacity 6, L = length 3 / capacity 10. No length-4 containers.
A length-1 container still faces a direction.

**Board:** rectangle, minimum 10 wide × 12 tall, maximum 12 wide × 14 tall. Up to 128 containers per level
(onBoard bitmask = two Longs). Any edge is an exit.

**Staging area (replaces "front of queue only"):**
- **20–40** staging spots, **per level** (`Level.stagingSpots`, JSON field `stagingSpots`, optional, default 40;
  validated to `Limits.MIN_STAGING_SPOTS`..`Limits.MAX_STAGING_SPOTS`). History: 7, then 40 (playtest comparison
  with Bus Fever Party — ~40 waiting, ~50 vehicles), then per level 20–40 (user decision 2026-10-11; the generators
  still produce 40 until the size becomes a level-design dial). Whenever a spot is free and the queue is non-empty, the next queue element moves into staging
  (auto-refill, in queue order). Staging is therefore always full unless the queue is exhausted.
- Boarding loop, run after every state change until nothing changes:
  1. Scan dock slots **left to right** (positional, slot 0 first). For the leftmost docked container that has free
     space and for which at least one staged element has its colour, the **oldest** such staged element boards it.
  2. A container that becomes full dispatches immediately, freeing its slot.
  3. Refill staging from the queue. Repeat.
  Consequence: with two same-colour containers docked, the left one fills completely before the right one gets
  anything — even if the right one needs only one more. Docking a second same-colour container is punished.
- Release takes the **leftmost free** dock slot (unchanged), so a later same-colour container can end up left of an
  earlier partially-filled one and then takes priority.
- Win: board, dock, staging and queue all empty.
- Lose (DEADLOCK): all 4 dock slots occupied and no staged element matches any docked container.
- STUCK: no tappable container and the boarding loop can't progress (and not won).
- Tap when all 4 dock slots are occupied: ignored (after the loop this implies DEADLOCK, so it's GAME_OVER).
- Balance: per colour, total elements == total capacity of that colour's containers.

**Solver key (v2):** onBoard (2 Longs) + dock slots **positional** (container id + spaceLeft per slot — position
matters now) + staging as per-colour counts + queueIndex. Pack compactly (e.g. 4 Longs). Element identity within a
colour does not matter, so staging is a count vector.

**Difficulty (v2):**
- Levels must have many containers from the start. Only **level 1** is a tutorial (10×12, sparse, no traps).
  From level 2 on, levels must clear a "too easy" floor (minimum containers, minimum score, minimum sharp
  decisions); the generator discards anything below it.
- **Rollout difficulty (dominant score term):** K≈64 deterministic seeded stochastic heuristic players (softmax
  over greedy-match scores at several temperatures + epsilon-random) play the level through Engine; loss rate is
  the main difficulty signal ("how easy is it to blunder into a jam"). One-deviation trap metrics read ~0 at 40
  staging, so T/D/O get low weight. Floor from level 2: naive bots lose and rollout loss rate ≥ X.
- Staging size is a per-level property, 20–40 (user decision 2026-10-11; replaces "staging stays at 40"). The rules,
  solver, scoring, metrics and real-time carousel all read `level.stagingSpots`; the generators keep producing 40
  for now.
- Replace P (it saturated at 1.0 in v1): P = mean over solution-path states of occupied dock slots / 4.
- S normalisation: colours 4→8, containers 30→110, cells 120→168.
- Campaign: fix the v1 boss bug — bosses must score meaningfully above level 9 of their cycle (target ≈ +6–10
  points), levels 1→9 must span a visible range, and level 1 of the next cycle drops back. Tests assert these.
- **Queue structure:** every level (from 2) mixes short interleaved stretches with 1–2 long single-colour runs
  that need several same-colour containers released in succession — the level's key challenge. Typical sizes
  ~50 containers.
- **Blocking chains (key difficulty lever, playtest finding):** dense boards where a needed container is buried
  behind a chain of 1–10 containers (each blocking the next's exit path, up to an edge container). Intermediate
  chain containers should often be "hard to fill" — colours whose elements sit beyond the current staging window
  (late in the queue) — so digging a chain clogs the dock. Chain length and the number of hard-to-fill links ramp
  with difficulty. Scorer reports chain metrics (max/mean dig depth of the containers the solution needs next;
  count of hard-to-fill links docked at once).
- Colours start at **5** (level 2) and ramp to **10** (Limits.MAX_COLORS = 10; Bus Fever Party shows up to 10) — with colours ≤ dock slots, "keep one of each colour docked"
  always works (playtest finding).
- **Heuristic-bot rejection:** from level 2, simulate deterministic naive players (one-of-each, greedy-match,
  first-free) via Engine; if any wins, discard the level.

**Level-design principles (playtest, 2026-10-10 — level 20 of the preview was the first "good" level):**
1. **Which container of this colour?** The core decision. Several same-colour containers of different sizes
   (S4 / M6 / L10) in different positions. The player picks one *knowing how many of that colour are staged/coming*:
   e.g. 6 staged reds → an M fills exactly and dispatches; an L sits with 4 empty spaces; an S leaves 2 waiting.
   Deliberately set up counts that make one choice work and a tempting one fail.
2. **Big vs small trade-off.** A big container holds a dock slot longer; a small one clears fast. Puzzles should
   sometimes make the *big* one the right pick because releasing it unblocks a critical container.
3. **Stacking to unlock.** Sometimes you must dock several same-colour containers at once (e.g. 3 purple) to free
   enough of another colour (e.g. blues) to clear a long run — that's what made preview level 20 interesting.
4. **Dense core, built outward.** Construct from the centre out: the centre is ~fully packed with only a handful
   of unpacking orders that don't jam; the periphery is looser and gives early freedom.
6. **Sustained dock pressure (playtest of motif preview level 2: "every vehicle filled up immediately").**
   Containers must sit in the dock partially filled for several moves, and the dock must regularly fill to 3–4,
   so the player repeatedly faces "which container next?" with only 1–2 non-losing options. Achieve this with a
   well-mixed queue (8–10 colours spread out so the 40 staged hold only a few of each colour, below L/M capacity);
   long single-colour runs are occasional events, not the backbone. Measure it directly along the winning line:
   fraction of states with dock ≥ 3, mean moves a container stays docked, fraction of decision states with only
   1–2 safe moves — this becomes a primary score term and a floor.
7. **Reverse ("director") generation (user idea, prototyped in parallel with the motif generator).** Play the game
   backwards from the empty end state; each reverse step undoes one tap plus its whole automatic cascade, so the
   intended line is valid by construction: un-dispatch (full container back into a free dock slot), un-board
   (elements from a docked container back into staging, respecting oldest-first and left→right priority),
   un-refill (staged elements back to the queue front), un-release (an *empty* docked container whose left
   neighbours are all occupied leaves the dock and slides onto the board from an edge along its exit lane until it
   hits something — this builds blocking chains naturally; heuristics avoid gaps). Key invariant: between taps no
   staged element matches a docked container with space — so a partially-filled docked container means its colour
   is absent from all 40 staged. A director script controls pressure directly (e.g. hold 3 slots with partial
   containers while chain A is built, then switch slot and chain). Forward checks (bots lose, tight decisions)
   still decide whether alternatives fail; the director can plant decoys deliberately.
8. **Decisions = forks; difficulty = few good options × deep horizon (user, 2026-10-10).** At a decision state:
   b = legal moves, w = winning ("good") moves, h = horizon = smallest search depth that proves a bad move lost
   (h=1 obvious, h≥4 needs planning). A good level has a handful of decision states with small w (1–2) out of b ≥ 3
   and h ≥ 2; never many states with lots of good options. Difficulty score is driven by Σ over decision states of
   (b−w)/b weighted by h (and penalised when w is large); floor: ≥ k decision states with w ≤ 2, b ≥ 3, h ≥ 2.
   Generators *plant forks*: at a chosen point, the correct container C plus a reachable decoy D whose failure
   mechanism and horizon are designed, then verified forward (D proven lost, C wins). Fork catalogue:
   (a) big-now-not-small: big waits but exactly enough is coming; small leaves leftovers that force the big one in
       later when it no longer fits;
   (b) exact fit vs overfill: M fills and leaves; L sits through a run and blocks the run's slot;
   (c) chain order: digging chain A first leaves a hard link docked when B's hard link arrives;
   (d) deliberate dead weight: the right move docks a slow-filling container because it's the only way to reach
       something needed in time (counter-intuitive move);
   (e) left-slot steal: a same-colour container released into a free slot left of a partial one steals its
       elements; the fork is which slot will be free (controlled by dispatch order);
   (f) timing window: right container now vs later; too early sits empty, too late misses its colour's batch;
   (g) exact colour budget: colour batches 4 then 10 require S4 then L10; M6 first orphans 4.
9. **Rolling dummies (user, after playtest 2026-10-11).** Calibration: Motif preview 2 L18 felt hard and good
   (restarted twice) — "play 3 other colours into docks to unblock pink/green, and later brown"; Reverse preview
   1 L19/L20 felt "better but still kinda easy" despite much higher pressure metrics. So the liked structure is the
   deliberate *dummy* stall (dock off-colour containers to dig out a buried run container), not raw dock occupancy.
   Variation to build: dummies are often **partially filled** (some of their colour staged, not enough to fill),
   and dummies **roll**: mid-stall one dummy gets completed by a third-colour arrival pattern and dispatches, freeing a
   slot exactly when a second dummy of another colour must be docked to reach the next blocker — overlapping stalls
   instead of stall-then-relief. Decisions: which dummy you can afford now, which will be completed soon enough,
   whether one more dummy strands you when the next run starts.
10. **Interrupted runs / multi-dummy unlock — the waiting area is a resource (user, 2026-10-11).** A long run of
    colour A (e.g. brown) has an island of colour B (e.g. red) in the middle; A's container is buried. Correct
    plan: dummy to unblock B's container first → two dummies working towards A (not finishing) → play other colours
    so the queue advances and A accumulates in staging (the 40 spots absorb the A run) until B arrives and clears →
    final dummy unblocks A → A boards from staging. Failure modes: digging towards A too early leaves no slot for B
    (fail the red); waiting too long overflows staging with A before B arrives (stuck). Staging capacity (40) and
    dock slots (4) are both resources. Levels should interleave several such chains ("absorb run 1 with other
    colours to reach a break that unblocks colour 2, which ultimately unblocks colour 1").
11. **Squatter colour + relief valves (user, 2026-10-11).** A colour whose elements arrive early but whose
    containers are all deeply buried until the endgame: its elements occupy staging, shrinking the effective waiting
    area for that level (e.g. 25 squatters → ~15 usable spots). Relief valves: a few small (S/M) containers of that
    colour, moderately buried, which can be dug out at a cost to free some spots "just enough to get through".
    Decision: when to spend moves/dock slots on a valve. Effective staging size becomes a per-level difficulty dial.
    Variant seen in the reference game (L14): no small valves — the squatter's containers are big and buried and the
    relief is digging them out with other colours whose elements must squeeze through the few free spots.
12. **Waiting ≠ reachable (mismatch windows) — user's "great example of the puzzle aspect" (ref L15, 2026-10-11).**
    At a state, W = colours on the carousel/staging, R = colours of containers releasable right now. The puzzle is
    strongest when |W ∩ R| is 0–1 for a stretch of several moves while the containers for W are 1–3 blockers deep:
    every move is then a dummy (release something in R \ W) or a dig towards W, and the decision is which dummies you
    can afford before the ring jams. Metric: along the winning line and bot lines, track |W ∩ R| per state; target
    mismatch windows of length k (ramping with difficulty), and reject levels where the overlap is usually large
    (they play themselves). The reverse director controls both W (staged colours) and R (exposed containers).
13. **Just-enough cascades (ref L15, 2026-10-11: "I got just enough space from red to fill enough green to also get a
    blue, and keep the level going").** A fill frees k carousel seats → exactly enough of the next colour steps on
    from the line to fill a docked container → that dispatch frees seats/a slot → the next colour gets in. Designed
    tight margins (k just sufficient) give a visible "save". The reverse director controls the line just beyond the
    ring, so it can plant such chains deliberately; the wrong ordering (e.g. docking an off-colour first) should
    break the chain → a fork with deep horizon. Same mechanism as the "rescue stuck outside a full ring" loss.
14. **Juggle from the first moves, then climax (playtest of Hybrid preview 2 L19, 2026-10-11: "easy until it
    wasn't"; the opening was "find anything that works", the long red chain was the good part, but a mistake made
    in the easy opening had already left too few free docks to reach red).** Wanted from the opening on: several
    partially filled docks plus passengers on the carousel, so that picking the next move carries pressure. Shape
    for hard levels (user choice): steady juggling from about move 3, i.e. 2–3 docked containers partly filled and
    several colours waiting, held through the middle and building to one long-chain climax. A wide, pressure-free
    opening whose choices only bite ~8 moves later is the failure to avoid: the stakes must be felt when the move
    is made. Metric: per state on the winning and bot lines, count partly filled docked containers and staged
    elements; reject levels whose first ~third has long stretches below the target.
15. **Scarce key colour, spent precisely (Hybrid preview 2 L19, 2026-10-11: stumped the user twice, "this is good,
    it's difficult").** One colour (light blue in L19) is the scarce resource, but some of it must be spent as dummies
    to dig towards a long mid-game chain of another colour (red). The strategy is to work out exactly which
    containers of the scarce colour to dock: too few and the chain stays buried, the wrong ones (or too many) and
    the colour runs out later. Generator: pick a scarce colour, make several of its containers block the climax
    chain, and size its supply so that only a specific subset of those spends wins (a fork over subsets, not over
    a single move). This is what made L19 work despite its weak opening (principle 14).
16. **Quality = hidden decision bits, spread from the opening (docs/LEVEL-QUALITY.md, 2026-10-11).** A state with b
    legal moves and w winning ones carries log2(b/w) bits; count only losers whose loss is not visible at once
    (horizon >= 2): hidden bits = Σ log2((w + losers with h >= 2) / w) along a winning line. Liked levels 23.7-59.6,
    disliked 4.2-13.6 (the clearest separation of all metrics tried; mean dock occupancy does not separate). Hidden
    bits need slack in the middle: per state they peak at 2 free docks (1.33 bits, 74% of states) and vanish at 1
    (losses immediate) and 4 (anything works). A first third with no hidden bits is the "find anything that works"
    opening. Generator: floors on hidden bits by tier and on the opening share, measured on the intended and the
    solver line.
17. **Herald the climax (Hybrid preview 3 L10, 2026-10-11: "I figured out the crazy chain, but it was too late").**
    Deep decisions (horizon > 3) can only be planned from what is on show (board, carousel, the next 20 of the line).
    L10's chain came on show 4 moves before it flooded, after 79% of its digging: the digging was blind. Put part of
    the climax colour on the carousel early (~10 waiting from about move 8, its containers being the buried climax
    run) and dig out one small container of it mid-level as a foreshadowing instance, so the chain can be planned
    for. This is not a colour tally: the player still infers the size and timing of the run.
5. There is an art to this — prefer hand-tunable construction "recipes"/motifs (each encoding one of the above
   decision patterns) composed into a level, over pure randomness. Verify each motif with the solver + bots.

**Real-time carousel (v3, user decision 2026-10-11).** The waiting area is a rotating ring of N = `level.stagingSpots`
seats (20–40; ~0.2 s per seat, so ~8 s per rotation at 40 and ~4 s at 20). The entry point defaults to 60% of the way
round the ring from the middle of the gate window (`RtConfig.entryPosition` = `ENTRY_DEFAULT`: position 28 at 40
seats, 16 at 20; user decision 2026-10-11, was N/2), on the loop's top towards its right end, so the belt drops plates
near the serving side and a new element reaches the window after ~40% of a lap; the 8-seat window and the entry fit
every size. The boarding gate is a window of `RtConfig.gateWidth` (default 8) consecutive
seats next to the dock: matching elements inside it board one after another, the most downstream first, at most one
start per `boardStaggerTicks` (default 75 ms), so a burst rolls down the window as a wave; elements entering the
window later board as they arrive (leftmost same-colour container with space first). The vacated seat travels on; an
element from the queue line can only step on when an empty seat passes the entry point. A level starts with every
element in the queue line and an empty ring, which fills through the entry (one seat step per element). The player may tap at any time,
including during the fill and while boarding/refill is still in progress (impatience can lose a level).
- **Patient play is the reference:** if the carousel runs undisturbed after a tap, it must converge to exactly
  `Engine.apply`'s settled state (same-colour elements are interchangeable; everything boardable boards; leftmost
  same-colour container first). Solver, generator and scoring keep operating on settled states; "solvable" means
  solvable by patient play. The initial fill ends in Engine's initial state (first N staged); a single tap during
  the fill also converges to `Engine.apply` from that state.
- Undo = rewind to just before the last tap, including carousel phase. Deterministic: real-time state is advanced
  by integer ticks; the UI maps wall-clock time to ticks.

**UI (v2):** board, then a staging area of the level's 20–40 spots (compact multi-row grid) between the dock and the two-lane queue preview
(left lane = next ~12 after staging, right lane = ~12 from the halfway point). Keep current car art for now.

**Reviewer fixes to include:** record a win only when the WON animation finishes (undo mid-animation must not keep
a recorded win); a hint that returns null must not cost stars and should say "no winning move — try undo";
filter level selection on solver node counts, not wall-clock time; tests for ProgressStore; off-path
solver/hint brute-force tests (from mid-game states after mistakes).

---

# v1 (history)

Native Android puzzle game ("Bus Jam" / "Parking Jam" style). Kotlin, Jetpack Compose, single activity,
minSdk 26, targetSdk = latest stable. No ads, analytics or network. Package / applicationId: `com.phloots.busjam`.
Portrait only.

## Rules (implement exactly)

- Board: rectangle, up to 10 wide × 12 tall. Any edge is an exit.
- Vehicles occupy a straight line of cells and face one of 4 directions (UP/DOWN/LEFT/RIGHT).
  Types: CAR length 2 / 4 seats, VAN length 3 / 6 seats, BUS length 4 / 10 seats. Each has a colour.
- Tap a vehicle → it drives forward. If every cell between its front and the board edge is empty it exits the
  board and parks. Otherwise it drives up to the blocker, bumps, returns (no state change).
- Parking: 4 positional slots. An exited vehicle takes the **leftmost free** slot. If all 4 slots are full, board
  vehicles cannot be tapped (tap is a no-op; UI may show a small "full" shake).
- One logical passenger queue. Boarding loop after every state change: while the front passenger's colour matches
  a parked vehicle with free seats, it boards the matching vehicle that **arrived first**. A full vehicle departs,
  freeing its slot. (Loop continues after departures.)
- Win: board, slots and queue all empty.
- Lose (DEADLOCK): all 4 slots occupied and front passenger matches none of them.
- STUCK: no tappable vehicle exists and the queue can't advance (and not won).
- Per colour, total passengers == total seats of that colour's vehicles.
- Colours: up to 8 (Okabe-Ito-based palette), each with a distinct shape icon (circle, square, triangle, diamond,
  star, heart, hexagon, cross) on vehicles and passengers.

## Modules

- `:core` — pure Kotlin/JVM (kotlinx.serialization only). Model, engine, solver, generator, scorer. Deterministic, seedable.
- `:levelgen` — JVM CLI (`application` plugin) using `:core` → `app/src/main/assets/levels.json` + curve table (stdout + CSV).
- `:app` — Compose UI depending on `:core`. No game logic in UI.

Package root `com.phloots.busjam.core` with subpackages `model`, `engine`, `solver`, `generator`, `scoring`, `io`.

## Core data model

```kotlin
enum class Dir { UP, DOWN, LEFT, RIGHT }
enum class VehicleType(val length: Int, val seats: Int) { CAR(2, 4), VAN(3, 6), BUS(4, 10) }

data class Vehicle(val id: Int, val type: VehicleType, val color: Int,
                   val row: Int, val col: Int, val dir: Dir)   // (row,col) = FRONT cell; body extends behind it

data class Level(val id: Int, val width: Int, val height: Int,
                 val vehicles: List<Vehicle>,   // <= 64, ids 0..n-1 = index
                 val queue: List<Int>)          // passenger colours, front first

data class ParkedVehicle(val vehicleId: Int, val color: Int, val seatsLeft: Int, val arrival: Int)

data class GameState(level, onBoard: Long, slots: List<ParkedVehicle?> /* size 4 */, queueIndex: Int, arrivalCounter: Int)
   status: PLAYING | WON | DEADLOCK | STUCK

data class Move(val vehicleId: Int)
sealed interface Event: Exited(vehicleId, slot), Bumped(vehicleId, stopRow, stopCol, blockerId),
                        Boarded(passengerIndex, slot, vehicleId), Departed(slot, vehicleId), Ignored(vehicleId, reason)

Engine.apply(state, move): Transition(state, events)   // pure
```
Row 0 is the top. UP = row-1.

Solver key: `onBoard` + slots sorted by (colour, arrival order) with seatsLeft + `queueIndex`, packed into two Longs.
(Sorting by (colour, seatsLeft) alone is lossy: two untouched same-colour vehicles of different types can't be told
apart by arrival.)

## Solver

DFS + memoization. `solve(level|state)` → solvable, one solution `List<Move>`, stats for scorer.
Must solve any shipped level in < 50 ms on JVM. `hint(state): Move?` → next move of a winning line (null if lost).
A separate full-search entry point (offline, may be slower) computes the scoring metrics.

## Generator

Reverse-extraction construction: place vehicles in reverse exit order; each newly placed vehicle's exit ray must avoid
all already-placed vehicles → blocking graph acyclic by construction, and the placement order reversed is a valid
extraction order. Then build the queue from that order, interleaving colours so slot usage stays ≤ 4 along the
intended line. Then solve + score; reject unsolvable / off-band. Density (coverage of the rectangle) is a difficulty parameter.

## Difficulty score (weights in `core/.../scoring/DifficultyConfig.kt`, the single config file)

- T trap ratio: mean over solution-path states of (losing legal moves / legal moves)
- D trap depth: mean over trap moves of the longest play from the mistake to a visible deadlock, ÷ 8, cap 1
- O ordering: fraction of decision states (≥2 legal moves) on the path with exactly 1 winning move
- P slot pressure: (peak slots used on the min-pressure winning line − 1) / 3
- S size: mean of normalised colours (3→8), vehicles (4→40), cells (25→120)

score = 100 × (0.30T + 0.20D + 0.20O + 0.15P + 0.15S)
Reject if 0.15S > 0.40 × score/100 (size dominates). Reject if fewer than k sharp decisions (states with ≤50% winning
moves), k per band (0 in tutorial).

## Campaign

1000 levels = 100 cycles of 10. Cycle c has a target band [b(c), b(c)+w]; b rises smoothly from ~0 to ~85th
percentile of the pool. Levels 1–9 of a cycle climb across the band; level 10 is a boss at ~b(c+3). Next cycle's
level 1 drops back. Cycle 1: no traps, 3 colours for levels 1–3, 4 after. Colours ramp 4 → 8 (bosses may be +1).
De-duplicate including symmetry (flips/rotations where the board shape allows).
Test: load levels.json, re-solve all, assert per-cycle median of the 9 normal levels is non-decreasing and each boss
≥ the top of its own cycle.

## App / UX

- Home screen with **Play** (opens the first unbeaten level) and Levels. Level select: lazy grid of 1000, locked/unlocked, stars.
- Game screen: board, 4 parking slots, queue drawn as **two lanes**: left lane = next ~12 from the front, right lane =
  ~12 starting at the queue's halfway point (preview). Buttons: restart, undo (unlimited), hint.
- Win / lose dialogs. Lose dialog offers Undo or Restart.
- Animations ≤ 300 ms: drive, bump, passengers walking/boarding, departing. Taps during animation are queued.
- Flat 2D top-down Canvas art (no bitmaps). Windshield indicates direction; shape icon per colour.
- Haptics on bump, board, win.
- DataStore: unlocked level, stars per level.
- Stars: 3 = no undo/hint, 2 = used undo, 1 = used hint. Restart clears penalties.
- Compose UI test (Robolectric, runs in `./gradlew test`) plays level 1 to a win via the solver's move list.

## Process

`./gradlew test assembleDebug` must pass. Git: origin github.com/liehann/bus_jam, push to main after each phase.
