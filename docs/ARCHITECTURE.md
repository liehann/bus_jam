# Bus Jam — architecture

`docs/DESIGN.md` is the agreed spec. This file describes how the code is laid out, who owns what, and the frozen
shared API. **API changes must be minimal and recorded in the "API change log" at the bottom of this file**
(date, author agent, what changed, why).

## Toolchain

| Item | Version | Notes |
|---|---|---|
| Gradle (wrapper) | 9.8.1 | `services.gradle.org/versions/current` |
| AGP | 9.4.1 | built-in Kotlin: **do not** apply `org.jetbrains.kotlin.android` |
| Kotlin | 2.4.21 | also `plugin.compose`, `plugin.serialization` |
| Compose BOM | 2026.09.00 | Compose 1.12.x |
| compileSdk | 37 (minor 2, `android-37.2`) | AndroidX core 1.19 / Compose 1.12 / lifecycle 2.11 require compileSdk >= 37 |
| targetSdk / minSdk | 37 / 26 | |
| JDK | 17 | `jvmToolchain(17)` everywhere |
| Robolectric | 4.17, pinned to `sdk=34` | `app/src/test/resources/robolectric.properties`; Robolectric SDK 35+ needs JDK 21 |

All versions live in `gradle/libs.versions.toml`. `local.properties` (gitignored) holds `sdk.dir`.

## Modules

```
:core      pure Kotlin/JVM + kotlinx.serialization. Model, engine, solver, generator, scorer, io.
:levelgen  JVM CLI (application plugin) -> app/src/main/assets/levels.json + curve CSV. Depends on :core.
:app       Android, Compose, single MainActivity (portrait). Depends on :core. No game logic.
```

Package root `com.phloots.busjam.core`:

```
core/src/main/kotlin/com/phloots/busjam/core/
  model/Model.kt                 Limits, Dir, VehicleType, Cell, Vehicle, Level, ParkedVehicle, Status,
                                 GameState, Move, IgnoreReason, Event, Transition        (frozen, architect)
  engine/Engine.kt               Engine.apply / legalMoves / isTappable                  (gamestate)
  solver/Solver.kt               Solver.solve / hint / analyze, SolveResult, SolveStats,
                                 FullAnalysis                                            (gamestate)
  scoring/DifficultyConfig.kt    weights + thresholds, the single tuning file            (gamestate)
  scoring/Scorer.kt              Scorer.score, DifficultyReport                          (gamestate)
  generator/Generator.kt         Generator(seed).generate(GenParams), GenParams          (generator)
  io/LevelPackCodec.kt           LevelPack, PackedLevel, LevelPackCodec                  (frozen, implemented)
levelgen/src/main/kotlin/com/phloots/busjam/levelgen/Main.kt                             (generator)
app/src/main/kotlin/com/phloots/busjam/...                                               (UI)
```

## File ownership

| Agent | Owns |
|---|---|
| gamestate/solver | `core/.../engine/**`, `core/.../solver/**`, `core/.../scoring/**`, their tests under `core/src/test/.../{engine,solver,scoring}` |
| generator/levelgen | `core/.../generator/**`, `:levelgen/**`, `app/src/main/assets/levels.json` (regenerated output), campaign tests |
| UI | `:app/**` except `assets/levels.json` |
| architect | `core/.../model/**`, `core/.../io/**`, Gradle files, this document |

Do not edit another agent's files; ask, or record a minimal change in the change log below.

## Frozen API (summary)

Invariants:

- **Coordinates**: `(row, col)`, row 0 = top, col 0 = left. `UP = row-1`, `DOWN = row+1`, `LEFT = col-1`, `RIGHT = col+1` (`Dir.dRow/dCol`).
- **Vehicle**: `(row, col)` is the **front** cell; the body extends behind it (`dir.opposite`) for `type.length` cells.
  `cells()` returns front first. The exit ray is the cells from front+dir to the board edge.
- **Level**: `vehicles[i].id == i`, `n <= 64`, width <= 10, height <= 12, in bounds, no overlap, per-colour
  passengers == seats. `Level.validate()` checks these structurally (not solvability). `queue` is front-first.
- **GameState**: `onBoard` bit `i` = vehicle `i` on board (64-bit, hence <= 64 vehicles). `slots` has exactly 4
  positional entries (index 0 = leftmost; null = free; exits take the leftmost free slot; departures leave a hole,
  no compaction). `queueIndex` = front passenger index (== `queue.size` when empty). `arrivalCounter` = next arrival
  stamp; boarding goes to the matching parked vehicle with the lowest `arrival`. `status` is set by the Engine.
  `GameState.initial(level)` = all on board, empty slots, PLAYING.
- **Events** (chronological, for animation): `Exited(vehicleId, slot)`, `Bumped(vehicleId, stopRow, stopCol, blockerId)`
  (stop = front-cell position at the bump, i.e. the cell just before the blocker), `Boarded(passengerIndex, slot, vehicleId)`,
  `Departed(slot, vehicleId)`, `Ignored(vehicleId, IgnoreReason)`.

Signatures:

```kotlin
object Engine {
    fun apply(state: GameState, move: Move): Transition      // pure
    fun legalMoves(state: GameState): List<Move>             // ascending id; empty unless PLAYING
    fun isTappable(state: GameState, vehicleId: Int): Boolean
}
object Solver {
    fun solve(state: GameState): SolveResult                 // SolveResult(solvable, solution: List<Move>, stats)
    fun solve(level: Level): SolveResult
    fun hint(state: GameState): Move?
    fun analyze(level: Level): FullAnalysis                  // raw T, D(moves), O, peakSlots, sharpDecisions, ...
}
object Scorer { fun score(level, analysis, config = DifficultyConfig.DEFAULT): DifficultyReport }
class Generator(seed: Long) { fun generate(params: GenParams): Level? }
object LevelPackCodec {
    fun encode(pack: LevelPack, prettyPrint: Boolean = false): String
    fun decode(json: String): LevelPack                      // IllegalArgumentException on bad input/version
}
@Serializable data class LevelPack(val version: Int = 1, val levels: List<PackedLevel>)
@Serializable data class PackedLevel(val level: Level, val score: Double = 0.0, val metrics: DifficultyReport? = null)
```

`levels.json` format (campaign order == list order, `levels[0]` is level 1):

```json
{"version":1,"levels":[{"level":{"id":1,"width":5,"height":5,
  "vehicles":[{"id":0,"type":"CAR","color":0,"row":1,"col":1,"dir":"UP"}],
  "queue":[0,0,0,0]},"score":0.0}]}
```

`app/src/main/assets/levels.json` currently holds 3 hand-written trivial levels so the UI can be built before the
generator exists; the generator agent overwrites it.

## Build & test

```
./gradlew test assembleDebug                 # must pass before every push
./gradlew :core:test                         # JUnit 5 (Jupiter) + kotlin.test
./gradlew :app:testDebugUnitTest             # Robolectric + compose ui-test-junit4 (JUnit 4)
./gradlew :levelgen:run --args="..."         # working dir = repo root
./gradlew :app:installDebug
```

Gotchas:

- AGP 9 built-in Kotlin: `:app` applies only `com.android.application` + `org.jetbrains.kotlin.plugin.compose`.
  Configure Kotlin with the top-level `kotlin { }` block (no `kotlinOptions`).
- compileSdk with a minor level uses the new DSL: `compileSdk { version = release(37) { minorApiLevel = 2 } }`.
- Compose tests: use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule` (v1 is deprecated; v2 uses
  `StandardTestDispatcher`, so synchronise with `waitForIdle()` / `mainClock`).
- Robolectric is pinned to SDK 34 because SDK 35+ android-all jars need JDK 21.
- `core` tests read `../app/src/main/assets/levels.json` relative to the `core` project dir.

## API change log

- 2026-10-10 (architect): initial freeze. Deviations from DESIGN.md: compileSdk/targetSdk 37 (latest stable,
  required by current AndroidX). Additions: `Limits`, `Cell`, `Dir.dRow/dCol/opposite`, `Vehicle.tail()`,
  `Level.inBounds/colors/validate`, `GameState.isOnBoard/frontPassenger/queueRemaining/slotsUsed`, `IgnoreReason`.
- 2026-10-10 (generator): additive only, all in `core/.../generator/`. `GenParams` gained defaulted fields
  (`maxIntendedSlots`, `interleave`, `decoyFraction`, `maxTrapRatio`, `rejectSizeDominated`, `minVehicles`,
  `analyzeMaxStates`, `skipAhead`, `crunches`, `crunchLength`) — queue/trap-shaping knobs and band filters.
  `Generator` gained `generateScored(params, config): GeneratedLevel?` (level + `FullAnalysis` + `DifficultyReport`,
  used by `:levelgen`), `construct(params): Level?` and `constructWithOrder(params): Pair<Level, List<Int>>?`
  (solver-free construction + intended extraction order, for tests). `generate()` is unchanged in signature.
  Internal `LineSim` (independent replay of an intended line). Campaign test: `core/src/test/.../campaign/CampaignPackTest.kt`.
  Note: `:core:test` does not declare `levels.json` as an input, so after regenerating the pack run
  `./gradlew :core:test --rerun` (or add `inputs.file("../app/src/main/assets/levels.json")` to core's test task).
- 2026-10-10 (gamestate): additive only. `FullAnalysis` gained a trailing defaulted field `capped: Boolean = false`;
  `Solver.analyze(level, maxStates: Int)` overload plus `Solver.DEFAULT_MAX_STATES` (2,000,000; the 1-arg
  `analyze(level)` uses it). On exceeding the cap analyze returns `solvable = false, capped = true,
  reachableStates = maxStates` (other metrics 0) so callers can reject. `Engine.computeStatus(state)` is public
  (status as a pure function of the fields). Internal: `engine/LevelData.kt` (per-level precompute cached per thread
  by `Level` identity, packed state, bitmask ray tests), `solver/LongPairMap.kt`.
  Metric definitions pinned down where DESIGN.md is loose (KDoc on `FullAnalysis`): path states = states before
  each move of the min-pressure line (final WON state excluded); `peakSlots` counts slots right after each exit,
  before boarding (so 1..4); min-pressure ties broken by lowest vehicle id; trap depth = longest play *after* the
  mistake until DEADLOCK/STUCK (0 = the mistake ends the game); `reachableStates` = memoized states (lost states
  proven by the front-colour lookahead are scored in closed form and not counted).
  Memo key deviation: DESIGN.md's sorted (colour, seatsLeft) multiset is not lossless when two untouched
  same-colour vehicles of different types are both parked (arrival order decides which fills first), so the key
  sorts slots by (colour, arrival) and keeps (colour, seatsLeft) in that order. Still compact: two Longs.
