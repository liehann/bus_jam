# Bus Jam — agreed design

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

Solver key: `onBoard` + sorted (colour, seatsLeft) of slots + `queueIndex`. Sorting is lossless because per colour
only the earliest-arrived vehicle can be partly filled.

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
