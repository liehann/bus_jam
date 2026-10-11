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

Packages: `:core` = `com.phloots.jam.core.*`, `:levelgen` = `com.phloots.jam.levelgen`. `:app` keeps namespace,
packages and applicationId `com.phloots.busjam` until the re-skin.

```
core/src/main/kotlin/com/phloots/jam/core/
  model/Model.kt                 Limits, Dir, ContainerSize, Cell, Container, Level, DockedContainer, Status,
                                 Bits128, GameState, Move, IgnoreReason, Event, Transition (frozen, architect)
  engine/Engine.kt               Engine.apply / legalMoves / isTappable / computeStatus  (gamestate)
  engine/LevelData.kt            internal precompute + packed 4-Long state (v2)          (gamestate)
  solver/Solver.kt               Solver.solve / hint / analyze / analyzeBounded, SolveResult,
                                 SolveStats, FullAnalysis                                (gamestate)
  solver/StateMap.kt             internal 4-Long-key open-addressing memo                (gamestate)
  scoring/DifficultyConfig.kt    weights + thresholds, the single tuning file            (gamestate)
  scoring/Scorer.kt              Scorer.score, DifficultyReport                          (gamestate)
  generator/Generator.kt         Generator(seed).generate(GenParams), GenParams          (generator)
  generator/Motifs.kt            MotifGenerator(seed).construct(MotifParams), RunGroup,
                                 MotifKind, MotifDecision, MotifLevel, VaultInfo          (generator)
  generator/MotifCheck.kt        MotifCheck (decision / core / wall-certificate checks),
                                 MotifFilter, MotifResult, MotifGenerator.generate        (generator)
  io/LevelPackCodec.kt           LevelPack, PackedLevel, LevelPackCodec                  (frozen, architect)
levelgen/src/main/kotlin/com/phloots/jam/levelgen/*.kt                                   (generator)
app/src/main/kotlin/com/phloots/busjam/...                                               (UI)
```

## File ownership

| Agent | Owns |
|---|---|
| gamestate | engine, solver, scoring: `core/.../{engine,solver,scoring}/**` and their tests `core/src/test/.../{engine,solver,scoring}/**` (incl. `solver/TestLevels.kt`) |
| generator | generator, levelgen, pack: `core/.../generator/**`, `:levelgen/**`, `app/src/main/assets/levels.json` (regenerated output), `core/src/test/.../{generator,campaign}/**` |
| UI | `:app/**` except `assets/levels.json` |
| architect | `core/.../model/**`, `core/.../io/**`, `core/src/test/.../ModelAndCodecTest.kt`, Gradle files, this document |

Do not edit another agent's files; ask, or record a minimal change in the change log below.

## Frozen API v2 (summary)

Rules: `docs/DESIGN.md` "v2 changes". Shape only is frozen here; v2 behaviour bodies that are not implemented yet
throw `TODO("v2")` and the tests that depend on them are `@Disabled("v2 pending")` (JUnit 5, `:core`) or
`@Ignore("v2 pending")` (JUnit 4, `:app`). Whoever implements a body re-enables the matching tests.

Invariants:

- **Coordinates**: `(row, col)`, row 0 = top, col 0 = left. `UP = row-1`, `DOWN = row+1`, `LEFT = col-1`, `RIGHT = col+1` (`Dir.dRow/dCol`).
- **Limits**: width 10..12 (`MIN_WIDTH`/`MAX_WIDTH`), height 12..14 (`MIN_HEIGHT`/`MAX_HEIGHT`), `MAX_CONTAINERS = 128`,
  `DOCK_SLOTS = 4`, `STAGING_SPOTS = 40`, `MAX_COLORS = 10`.
- **ContainerSize**: `S(length 1, capacity 4)`, `M(2, 6)`, `L(3, 10)`. A length-1 container still faces a direction.
- **Container**: `(row, col)` is the **front** cell; the body extends behind it (`dir.opposite`) for `size.length`
  cells. `cells()` returns front first. The exit ray is the cells from front+dir to the board edge.
- **Level**: `containers[i].id == i`, `n <= 128`, size within the limits, in bounds, no overlap, queue colours in
  range, per-colour elements == capacity. `Level.validate()` checks these structurally (not solvability). `queue` is
  front-first. Engine/Solver do **not** call `validate()`, so tests may use small hand-built boards.
- **Bits128**: immutable 128-bit id set (`lo` = ids 0..63, `hi` = 64..127); `get`, `with`, `without`, `and`, `or`,
  `andNot`, `count`, `isEmpty`, `ids`, `Bits128.firstN(n)`, `EMPTY`. Hot paths may use `lo`/`hi` directly.
- **GameState** `(level, onBoard: Bits128, dock: List<DockedContainer?>, staging: List<Int>, queueIndex, status)`:
  - `dock` has exactly 4 positional entries (index 0 = leftmost; null = free). Release takes the leftmost free slot;
    dispatches leave a hole (no compaction). Boarding scans slots left to right.
  - `DockedContainer(containerId, color, spaceLeft)` — no arrival stamp any more (priority is positional).
  - `staging` = colours of the staged elements in age order (oldest first, list index = staging spot), size <= `STAGING_SPOTS` (40),
    compacted (after a boarding the later elements shift one spot left). Full unless the queue is exhausted.
  - `queueIndex` = next queue element **not yet staged** (`== queue.size` once everything is staged).
  - `stagedElementIndices()` = queue indices parallel to `staging` (derived; boarding always takes the oldest staged
    element of a colour). `queueRemaining` = unstaged count. `dockUsed`.
  - `GameState.initial(level)` = all on board, empty dock, staging filled with the first min(STAGING_SPOTS, queue.size) elements,
    `queueIndex` = that count, PLAYING. (Implemented.)
  - `status` is set by the Engine (WON > DEADLOCK > STUCK > PLAYING, see `Engine` KDoc).
- **Events** (chronological, for animation):
  `Released(containerId, slot)`,
  `Blocked(containerId, stopRow, stopCol, blockerId)` (stop = front-cell position at the block, i.e. the cell just
  before the blocker; no state change),
  `Staged(elementIndex, stagingSpot)` (queue -> staging; spot = list index it takes),
  `Boarded(elementIndex, fromStagingSpot, slot, containerId)` (spot index at that moment, before compaction),
  `Dispatched(slot, containerId)`,
  `Ignored(containerId, IgnoreReason)` with `IgnoreReason { NOT_ON_BOARD, DOCK_FULL, GAME_OVER, INVALID_ID }`.
  A successful tap emits `Released`, then the boarding loop's `Boarded` / `Dispatched` / `Staged` events in the order
  they happen. `GameState.initial` emits nothing (the UI shows the initial staging statically).

Signatures (unchanged from v1 except where noted):

```kotlin
object Engine {
    fun apply(state: GameState, move: Move): Transition      // pure                         TODO("v2")
    fun legalMoves(state: GameState): List<Move>             // ascending id; empty unless PLAYING  TODO("v2")
    fun isTappable(state: GameState, containerId: Int): Boolean                            // TODO("v2")
    fun computeStatus(state: GameState): Status                                             // TODO("v2")
}
object Solver {
    const val DEFAULT_MAX_STATES: Int
    fun solve(state: GameState): SolveResult                 // SolveResult(solvable, solution, stats)  TODO("v2")
    fun solve(level: Level): SolveResult
    fun hint(state: GameState): Move?
    fun analyze(level: Level): FullAnalysis
    fun analyze(level: Level, maxStates: Int): FullAnalysis                                // TODO("v2")
}
object Scorer { fun score(level, analysis, config = DifficultyConfig.DEFAULT): DifficultyReport }  // formula v1; P/S v2 pending
class Generator(seed: Long) {
    fun generate(params: GenParams): Level?
    fun generateScored(params: GenParams, config: DifficultyConfig = DifficultyConfig.DEFAULT): GeneratedLevel?  // TODO("v2")
    fun construct(params: GenParams): Level?
    fun constructWithOrder(params: GenParams): Pair<Level, List<Int>>?                     // TODO("v2")
}
object LevelPackCodec {
    const val CURRENT_VERSION = 2
    fun encode(pack: LevelPack, prettyPrint: Boolean = false): String
    fun decode(json: String): LevelPack                      // accepts versions 1..2; IllegalArgumentException otherwise
}
@Serializable data class LevelPack(val version: Int = LevelPackCodec.CURRENT_VERSION, val levels: List<PackedLevel>)
@Serializable data class PackedLevel(val level: Level, val score: Double = 0.0, val metrics: DifficultyReport? = null)
```

Solver memo key (v2, DESIGN.md): onBoard (2 Longs) + dock slots positional (container id + spaceLeft per slot) +
staging as per-colour counts + queueIndex, packed compactly (e.g. 4 Longs). Internal to the gamestate agent.

`levels.json` format (campaign order == list order, `levels[0]` is level 1):

```json
{"version":2,"levels":[{"level":{"id":1,"width":10,"height":12,
  "containers":[{"id":0,"size":"S","color":0,"row":1,"col":1,"dir":"UP"}],
  "queue":[0,0,0,0]},"score":0.0}]}
```

Pack versions: **1** = v1 rules (current shipped file, keys already renamed to `containers`/`size`; it decodes, but
its levels do not meet v2 limits and the sizes now mean different lengths). **2** = v2 rules. Until the generator
ships a version-2 pack: `CampaignPackTest` skips itself (JUnit assumption on `version >= 2`), `ModelAndCodecTest`
validates the asset only when `version >= 2`, and the app still loads the v1 file (the board renders; taps hit
`TODO("v2")` until the engine lands).

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
  required by current AndroidX). Additions: `Limits`, `Cell`, `Dir.dRow/dCol/opposite`, `Container.tail()`,
  `Level.inBounds/colors/validate`, `GameState.isOnBoard/frontElement/queueRemaining/dockUsed`, `IgnoreReason`.
- 2026-10-10 (generator): additive only, all in `core/.../generator/`. `GenParams` gained defaulted fields
  (`maxIntendedSlots`, `interleave`, `decoyFraction`, `maxTrapRatio`, `rejectSizeDominated`, `minContainers`,
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
  before boarding (so 1..4); min-pressure ties broken by lowest container id; trap depth = longest play *after* the
  mistake until DEADLOCK/STUCK (0 = the mistake ends the game); `reachableStates` = memoized states (lost states
  proven by the front-colour lookahead are scored in closed form and not counted).
  Memo key deviation: DESIGN.md's sorted (colour, spaceLeft) multiset is not lossless when two untouched
  same-colour containers of different sizes are both docked (arrival order decides which fills first), so the key
  sorts slots by (colour, arrival) and keeps (colour, spaceLeft) in that order. Still compact: two Longs.
- 2026-10-10 (architect): **v2 rename** (behaviour-preserving, commit "Rename game model to neutral
  container/element terms"). Vehicle->Container, VehicleType CAR/VAN/BUS->ContainerSize S/M/L (field `type`->`size`),
  seats/seatsLeft->capacity/spaceLeft, Passenger->Element (`passengerIndex`->`elementIndex`, `frontPassenger`->
  `frontElement`), ParkedVehicle->DockedContainer, `GameState.slots`->`dock`, `SLOT_COUNT`->`DOCK_SLOTS`,
  `SLOTS_FULL`->`DOCK_FULL`, `slotsUsed`->`dockUsed`, Exited->Released, Departed->Dispatched, Bumped->Blocked.
  JSON keys `vehicles`->`containers`, `type`->`size`, values CAR/VAN/BUS->S/M/L. Packages `com.phloots.busjam.core`
  -> `com.phloots.jam.core`, `com.phloots.busjam.levelgen` -> `com.phloots.jam.levelgen` (`:app` unchanged).
- 2026-10-10 (architect): **v2 API freeze** (staging area, S/M/L sizes, 128 containers). Breaking:
  `ContainerSize` lengths S1/M2/L3 (capacities 4/6/10); `Limits` gained `MIN_WIDTH` 10, `MIN_HEIGHT` 12,
  `STAGING_SPOTS` 7, `MAX_WIDTH` 12, `MAX_HEIGHT` 14, `MAX_CONTAINERS` 128; `Level.validate` enforces them plus queue
  colour range. New `Bits128`; `GameState.onBoard` is `Bits128`; `GameState` gained `staging: List<Int>` and
  `stagedElementIndices()`, lost `arrivalCounter` and `frontElement`; `queueIndex` now means "next element not yet
  staged"; `GameState.initial` fills staging. `DockedContainer` lost `arrival`. Events: new `Staged(elementIndex,
  stagingSpot)`; `Boarded` gained `fromStagingSpot` (now `Boarded(elementIndex, fromStagingSpot, slot,
  containerId)`). `LevelPackCodec.CURRENT_VERSION` = 2 (decode accepts 1..2). Engine/Solver/Generator signatures
  unchanged; bodies `TODO("v2")`: `Engine.*`, `Solver.solve(state)`, `Solver.analyze(level, maxStates)`,
  `Generator.generateScored`, `Generator.constructWithOrder`, internal `LevelData.toPacked`. `Scorer` and
  `DifficultyConfig` unchanged (v2 P and S normalisation pending, gamestate). Disabled pending v2: `EngineTest`,
  `SolverTest`, `GeneratorTest`, 3 `ScorerTest` methods, `CampaignPackTest` (assumption), app `GameViewModelTest`,
  `PlayLevelOneTest`, `ScreenshotTest.animationFramesAndWinDialog`. Minimal edits in other agents' files to keep
  the build compiling: v1 tests (`EngineTest`, `TestLevels`) adapted to the new constructors; `AnimationPlan` treats
  `Event.Staged` as a no-op; `ScreenshotTest` builds a v2 `GameState`.
- 2026-10-10 (gamestate): **v2 engine/solver/scorer implemented.** All additive / source-compatible unless noted.
  - Constants (on lead request, in the architect's Model.kt): `Limits.STAGING_SPOTS` 7 -> **40**, `Limits.MAX_COLORS`
    8 -> **10**. Minimal edit to `ModelAndCodecTest` (it hard-coded 7 staging spots and colour 9 as out of range).
  - `Solver`: new `solve(state, maxNodes: Long): SolveResult?` and `solve(level, maxNodes)` (null = budget exceeded,
    otherwise exact); `HINT_MAX_NODES = 500_000`, and `hint()` is bounded by it (null on budget exhaustion as well as
    on lost states); new `analyzeBounded(level, config = DifficultyConfig.DEFAULT): FullAnalysis` (budgeted,
    deterministic: solve line, each path child classified WIN/LOSS/UNKNOWN by a budgeted solve, UNKNOWN excluded
    from T/sharp denominators and O, depth-limited trap depth). The exhaustive `analyze` is kept for small levels.
  - `FullAnalysis` gained trailing defaulted fields: `meanDockUsed`, `unknownChildren`, `bounded`, `digDepthMax`,
    `digDepthMean`, `peakDeadWeight`, `meanDeadWeight` (KDoc defines them). `analyze` now picks the
    min-occupancy line (min summed dockUsed over path states) instead of min-peak; `peakSlots` is still reported.
  - `Scorer.score(level, analysis, config, rolloutLossRate: Double? = null)`; new `Scorer.isTooEasy(level, report,
    config)`. `DifficultyReport` gained defaulted `rollout`, `chain`, `deadWeight`. Formula (DifficultyConfig KDoc):
    100 × (0.45 R + 0.20 C + 0.10 W + 0.10 S + 0.05 T + 0.04 D + 0.03 O + 0.03 P); P = meanDockUsed / 4;
    S colours 4→10, containers 30→110, cells 120→168.
  - `DifficultyConfig`: new weights `wRollout`, `wChain`, `wDeadWeight`, `digMeanNorm`, `digMaxNorm`; floor
    `floorMinContainers`, `floorMinScore` (0), `floorMinSharpDecisions` (0), `floorMinColors`; budgets
    `rootSolveNodeBudget`, `childSolveNodeBudget`, `trapDepthNodeBudget`, `maxUnknownChildren`; naive-bot
    toggles `botOneOfEach`, `botGreedyMatch`, `botFirstFree`; rollout block `rolloutCount`, `rolloutTemperatures`,
    `rolloutEpsilon`, `rolloutCarelessEvery`, `rolloutCarelessEpsilon`, `rolloutStarvePenalty`,
    `floorMinRolloutLoss` (fields requested by the generator agent).
  - Internal: memo key = 4 Longs (onBoard lo/hi canonicalised over interchangeable free leaves; a = 4 dock slots ×
    (spaceLeft 4b, colour 4b) + queueIndex 12b; b = 10 staging counts × 6b). The dock stores colour, not container
    id (lossless once docked, so a coarser key than DESIGN's id + spaceLeft). Exact prunings: release lookahead
    (a staged-colour container plus its blockers must fit the free slots), a relaxed game with blocking ignored
    (memoised; a relaxed loss proves a real loss), last-slot non-matching skip, free-leaf symmetry. `LongPairMap`
    was replaced by `StateMap`. `LevelData.toPacked` now writes (a, b) into an out array.

- 2026-10-10 (generator): **v2 generator / levelgen / pack.** `GenParams` reworked for v2 (generator-owned, defaulted
  fields): removed the v1 queue knobs (`maxIntendedSlots`, `interleave`, `skipAhead`, `crunches`, `crunchLength`);
  added `maxContainers`, `blockBias`, `runs`, `runLength`, `runMaxBlockers`, `buriedPreference`, `chainLength`,
  `hardLinkFraction`, `window`, `rejectBots`, `bounded`, `maxUnknownChildren`, `minRolloutLoss`; `maxSolveNodes` now
  feeds `Solver.solve(state, maxNodes)`. `GeneratedLevel` gained `solveStats` and `rollout`. Construction: dense
  inside-out placement, base topological order, 1–2 long single-colour runs whose targets are dug out through blocking
  chains (hard links = late colours that wait in the dock), phase-separated colours, keyed queue; `LineSim` follows v2
  rules (40 staging, left-to-right dock priority). New `core/.../generator/Bots.kt` (`Bot` ONE_OF_EACH / GREEDY_MATCH /
  FIRST_FREE, `Bot.winners(level)`) and `core/.../scoring/RolloutDifficulty.kt` (`lossRate(level, config)`,
  `evaluate(level, config)`; seeded from level content, settings in DifficultyConfig's rollout block; its value is
  passed to `Scorer.score(..., rolloutLossRate)`). levelgen selects on solver node counts (no wall-clock),
  `--preview <n>` writes a preview pack, curve CSV gained rollout/chain/deadWeight. Pack: version 2.
- 2026-10-10 (gamestate, reviewer fixes): `Solver.hint(state, isCancelled: () -> Boolean = { false })` and
  `Solver.solve(state, maxNodes, isCancelled = { false })`: the callback is polled every
  `Solver.CANCEL_CHECK_NODES` (1024) search nodes; a cancelled search returns null (hint) / null (budgeted solve).
  Source-compatible (defaulted). UI: pass `{ !coroutineContext.isActive }`. `RolloutDifficulty` softmax uses
  `StrictMath.exp` (bit-identical across JVMs/CPUs; stored rollout scores may shift by an ulp-driven amount, so
  regenerate packs). Invariant docs above updated to `STAGING_SPOTS = 40`, `MAX_COLORS = 10`.
- 2026-10-10 (generator): **motif-based construction** (DESIGN.md v2 "Level-design principles"). Additive only.
  - New `core/.../generator/Motifs.kt` / `MotifCheck.kt`: `MotifGenerator(seed).construct(MotifParams): MotifLevel?`
    and the extension `MotifGenerator.generate(params, config, MotifFilter): MotifResult?` (construct + v2 floor: bots
    lose, rollout loss, root solve budget + per-decision verification + `analyzeBounded` + `Scorer.score`). Motifs
    (`MotifKind`): Squeeze, StackToUnlock, CapacityChoice, BigUnblocks, DenseCore; each run group is a "vault" stamp
    (wall links on an edge lane, run target T, shadow that exits only through T). `MotifCheck.checkDecision`: budgeted
    `Solver.solve` of the wrong branch (LOSS / NOT_FORCING); if the budget runs out, `MotifCheck.wallCertificate`
    (sound slot-count proof at the run's staging stall, cross-checked against the solver in tests) gives
    CERTIFIED_LOSS; rollouts from both children are the last fallback (WORSE).
  - `PackedLevel` (architect's io file, minimal): trailing defaulted `motifs: List<String> = emptyList()` — motif tags
    of a motif-built level for the debug UI, e.g. `"CapacityChoice M6|L10 @12 LOSS"` (first word = `MotifKind` name,
    `@n` = intended-line step of the decision, last word = verification outcome; `"DenseCore 0.95 sharp 5/14"`,
    `"Squeeze 22-30"` = run span). Old packs decode unchanged; packs now encode `"motifs":[]` for other levels.
  - `RolloutDifficulty.evaluate(start: GameState, config, salt)` overload (rollouts from a mid-game state); the
    level overload delegates with salt 0 (identical results).
  - Determinism: `Generator` placement uses `StrictMath.pow`; motif code uses only kotlin.random + StrictMath.
    New `levelgen/src/test/.../DeterminismTest.kt` (small campaign run and motif preview, twice with different thread
    counts, byte-compared). Shipped pack regenerated with the default `./gradlew :levelgen:run`.
  - `:levelgen`: `--motif-preview <n>` (default out `levelgen/out/preview-levels.json`, `--seeds-per-slot k`) and
    `--motif-explore` (construction/verification funnel diagnostics; `MOTIFS=S3,CC,...` env selects recipes).
