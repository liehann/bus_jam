# Readable stalls: forks whose deciding batch is on show, by construction

Goal: forks that can be read from what is on show. For each fork, the colour batch that decides it (what the winning line boards during the fork's loss window that sat beyond carousel + belt when the choice was made, `Readability.kt`) should be inside carousel + belt. The tier difficulty (fair hidden bits), multiple winning options (w >= 2) and order robustness (winnable at 1/3) should hold. The solver and state model are unchanged. The new construction is a dial, `StallScript.readable: ReadableDef?`, and it is off by default.

**Verdict.** Readable construction raises the readable-fork share from 34% to 42-44% on the 20 rebuild slots. Unfair decisions fall from 5 to 2, traps stay at 0, fair hidden bits go up slightly (21.2 to 23.2) and winnable at 1/3 goes up (H = 40: 52% to 62%). It costs blind bits (2.8 to 4.5), winnable-from-start (23% to 19%), w >= 2 (76% to 72%) and floors passed (15 to 11). It does not clearly beat fitness5 + belt: it is better on readability, unfairness and order robustness at 1/3, and worse on blind bits, winnability from the start, options and floors. Most forks still fail the readability test, and the reason is structural (section 1): a hold or stall completion is boarded straight off the queue, and in an 8-move loss window the queue moves about 45 elements, more than any belt shows. The early-emission stall (section 3, `runBudget`) made forks less readable.

## 1. Where the deciding batch comes from (diagnosis on the 20 rebuild-preview levels)

`ReadableDiag.kt` rebuilds each rebuild-preview level from its provenance (same recipe, director seed, try), keeps each container's construction role (new diagnostic `StallLevel.roles`), and breaks down every fork of the intended line at the slot's ring and belt.

Forks by the role of the move the line makes there:

| phase (move at the fork) | forks | readable | mean window (moves) | mean hidden elements |
|---|---|---|---|---|
| juggle hand-over | 229 | 22% | 6.4 | 13.1 |
| stall dummy docking | 82 | 76% | 4.3 | 4.2 |
| filler | 62 | 21% | 6.6 | 8.4 |
| stall run | 14 | 14% | 7.1 | 11.5 |
| cascade / island / target / valve | 18 | 39% | 5.8 | 6.5 |

Forks by loss window (the deepest loser's horizon, capped at 8):

| window | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|
| readable / forks | 35/35 | 44/51 | 28/40 | 12/31 | 9/27 | 3/24 | 4/197 |

Hidden elements (elements in the deciding batch) by how they are boarded: juggle hand-over completions 1501, juggle preloads 1143 (staged just before docking), completions on filler taps 435, run taps 250 + 172, stall completions 128 + 88, cascades 93.

What this shows:
- **The stall is not the main problem.** Stall-dummy forks are already 76% readable. 57% of all forks are in the juggle opening, and only 22% of those are readable.
- **The window decides.** Forks with a 2-3 move window are 92% readable, and forks with an 8-move window are 2% readable, whatever the phase. Half of all forks have an 8-move window.
- **Arithmetic.** The queue moves on by one element per boarding, about 5.5 per move on these levels. Over an 8-move window it advances about 45 elements, and the belt shows 10-20. A docked container that waits (juggle hold, stall dummy) can never share the carousel with its own colour, because it would board those elements at once. So it is always completed by elements revealed during the tap that completes it, straight off the queue. Such an element is on show at a fork t only if fewer than `belt` elements are boarded between t and that tap. In a deep window that is true only in roughly the first 2-3 moves. Any hold that resolves later in the window puts the fork's deciding batch beyond the belt, whatever the run length or emission order.
- **What stretches the windows.** Of the deepest losers at unreadable forks (probe on 4 slots, default construction), most are reachable containers whose colour is nowhere on show, or not enough of it: an early stall dummy (156 forks), a decoy exit filler (87), or a juggle container (42). Docking one holds a slot past anything visible and loses 8+ moves later. Every fork where such a container is reachable gets an 8-move window.

## 2. Design (`ReadableDef`, core `generator/reverse/StallDirector.kt`)

The parts that are used:
1. **Exact deciding-batch scoring in candidate selection.** When the director scores a candidate tap, the forward state before the tap and every later tap are already final. So the construction line's deciding batch for that fork can be computed exactly: `Readability.hiddenBoarded`, a replay of at most 8 moves from the construction state via the new `Readability.boardedFrom`. The edge is the shipped ring (`ReadableDef.ring`) plus the belt. A readable fork scores `+weight`. An unreadable one scores `-weight - perElement * min(n, 20)`. With `deepOnlyReadable` (the default), an unreadable fork gets none of the existing horizon reward (`2 + hMax`): deep stakes only count when what decides them is on show.
2. **Hide dead containers (`coverDead`).** At placement, each reachable container whose colour is not in carousel + belt (`w[0 until ring + belt]` of the reverse state) and whose exit ray the new container would block adds `coverDead` to the placement score. This is the same mechanism as `coverFillable` / `coverStaged`. The new container is released before every container it blocks, so the line is never affected. Those containers are hidden until about when their colour shows up, which cuts the deep losers that stretch the windows.
3. **Early emission (`runBudget`, tested, not used).** The stall's dummy completions move from the run's last tap to the first run tap by which the target and run have boarded about `runBudget` elements (target 10, run container ~7). The critical batch is emitted early and the rest of the run follows on the freed dock. This was the suggested stall change, and it made readability worse (section 3).

`readable = null` (the default) leaves every code path, and the rng stream, exactly as before. `ReadableDef.candidates` / `openCandidates` can raise candidates per tap.

Tried and dropped:
- **Aged juggle preloads.** Staging the preload long before docking instead of just before built 0 of 160 tries (4 slots x 40): the hand-over then finds no colour absent from the carousel.
- **`coverShort`.** Also hiding containers whose colour is on show but short of capacity gave readable 46% at 14.1 hidden bits vs 49% at 13.7 for `coverDead` alone, so no gain. Raising it to 10 lowered readability to 43%.

## 3. Construction probe (8 slots: 4, 10, 19, 25, 33, 60, 72, 89; 40 tries each; intended line at the slot's ring, every built try)

`ReadableProbe.kt` (spec letters: w weight, e perElement, d coverDead, r runBudget, c / o candidates).

| construction | readable forks | forks / level | hidden bits | built | generation time (s) |
|---|---|---|---|---|---|
| default | 40% | 15.6 | 16.9 | 73 | 266-306 |
| scoring only (w3, d0; 4 slots) | 44% (default 43%) | 12.4 | 14.8 | 41 | - |
| coverDead only (w0 x0 d10) | 46% | 12.5 | 14.2 | 65 | 271 |
| **w3:d10** | **49%** | 12.0 | 13.7 | 66 | 242 |
| w3:d20 | 47% | 12.1 | 14.0 | 65 | 224 |
| w3:d10:c5:o8 | 47% | 13.4 | 15.8 | 58 | 312 |
| **w4:d10:e0.2** | **55%** | 11.1 | 11.5 | 64 | 181 |
| w6:d10:e0.3 | 55% | 11.1 | 11.8 | 65 | 211 |
| w3:d10:r16 (early emission) | 38% | 10.0 | 10.3 | 67 | 188 |
| w3:d10:r16:c5 | 43% | 8.5 | 9.6 | 66 | 246 |

Scoring on its own barely moves readability (43% to 44%). A fork's deciding batch is fixed by the later taps, which the current candidate cannot change, so scoring mostly removes forks. Hiding dead containers moves it. Every gain trades against hidden bits: readable share and hidden bits move in opposite directions along one frontier. Early emission is worse on both axes. Completing the dummies at the target leaves the rest of the run on a free dock, which gives more choices with deep losers and fewer stall decisions. Generation time is not higher: the readability replay is at most 8 moves per scored candidate, and the variants build a little faster because hidden containers mean fewer reachable moves to solve.

## 4. Full evaluation: same 20 slots, same fitness5 selection

`RebuildPreview --readable <spec> --repair off`: OfficialPack's recipes, seeds and tries (seed 7, 400 tries, x2 on tier >= 2), the belt dial, fitness5 with the tier floors, and the long chain on the finalists. "Old" is the rebuild-preview level of each slot (`--old levelgen/out/rebuild-preview-levels.json --old-meta levelgen/out/rebuild-preview-meta.json`), re-measured the same way: its numbers reproduce rebuild-preview.md exactly. Generated tables: `readable-stalls-w3d10.md` and `readable-stalls-w4d10.md`. "fitness5 + belt alone" is the rebuild-preview's best finalist without the queue repair, from its repair-effect table (that table has no traps sum, uniform-order winnability or w >= 2, shown as -).

Pack summary, solver line, 20 levels:

| metric | rebuild-preview (fitness5 + belt + repair) | fitness5 + belt alone | readable w3:d10 | readable w4:d10:e0.2 |
|---|---|---|---|---|
| readable forks (mean share) | 34% | 31% | **42%** | **44%** |
| unfair decisions (sum) | 5 | 4 (0.20 mean) | **2** | 4 |
| levels with >= 1 unfair | 5 | - | 2 | 4 |
| traps (sum) | 0 | 0 | 0 | 0 |
| blind bits (mean) | 2.8 | 2.9 | 4.5 | 3.4 |
| blind / hidden (mean) | 0.13 | - | 0.20 | 0.15 |
| hidden bits (mean) | 21.7 | - | 23.4 | 22.2 |
| fair hidden bits (mean) | 21.2 | 21.9 | **23.2** | 21.9 |
| winnable at 1/3, H = 40 (mean) | 52% | 48% | **62%** | 55% |
| winnable at 1/3, uniform (mean) | 43% | - | 51% | 44% |
| winnable from start, uniform (mean) | 23% | - | 19% | 16% |
| levels <= 5% winnable from start (uniform) | 11 | - | 13 | 14 |
| choices with w >= 2 (mean) | 76% | - | 72% | 72% |
| mean w | 3.30 | - | 3.05 | 3.05 |
| floors passed (of 20) | 15 | 15 | 11 | 10 |
| finalist floor failures | order 51, unfair 30, deeptraps 29, blindratio 25, fairhidden 14 (repair both: 2x finalists) | | order 31, blindratio 20, fairhidden 16, unfair 13, deeptraps 8 | order 35, blindratio 21, fairhidden 17, unfair 11, deeptraps 9 |

Per slot, solver line, readable share (old → w3:d10 / w4:d10:e0.2) and fair hidden bits: with w3:d10, 11 slots read better and 9 worse (L10, L17, L25 at 0%, L28, L37, L54, L72, L85, L89); with w4:d10:e0.2, 12 better and 8 worse. 14 slots pick the same level in both variants.

| slot | readable old → w3 / w4 | fair hidden old → w3 / w4 | floors w3 |
|---|---|---|---|
| L4 | 0% → 80% / 80% | 2.0 → 7.0 / 7.0 | pass |
| L10 | 32% → 26% / 15% | 39.9 → 35.0 / 23.8 | order |
| L17 | 21% → 20% / 23% | 20.2 → 13.9 / 12.7 | blindratio |
| L19 | 20% → 35% / 35% | 21.1 → 27.9 / 27.9 | pass |
| L25 | 50% → 0% / 42% | 8.1 → 27.8 / 15.7 | blindratio |
| L28 | 44% → 36% / 36% | 17.2 → 16.5 / 16.5 | pass |
| L33 | 40% → 57% / 57% | 8.8 → 12.5 / 12.5 | pass |
| L37 | 57% → 31% / 31% | 25.3 → 20.3 / 20.3 | blindratio |
| L41 | 20% → 50% / 50% | 6.2 → 9.2 / 9.2 | pass |
| L43 | 36% → 50% / 50% | 13.6 → 21.9 / 21.9 | pass |
| L54 | 48% → 29% / 23% | 26.9 → 27.0 / 16.8 | pass |
| L60 | 53% → 81% / 81% | 40.4 → 19.4 / 19.4 | opening, fairhidden, order |
| L62 | 26% → 43% / 43% | 26.4 → 31.2 / 31.2 | blindratio |
| L67 | 19% → 48% / 48% | 27.1 → 30.9 / 30.9 | openbits |
| L72 | 35% → 10% / 39% | 20.0 → 17.6 / 30.5 | opening, blindratio |
| L80 | 54% → 71% / 71% | 28.9 → 33.9 / 33.9 | pass |
| L85 | 41% → 30% / 26% | 22.9 → 30.0 / 25.9 | pass |
| L89 | 35% → 29% / 29% | 29.5 → 36.5 / 36.5 | pass |
| L93 | 26% → 47% / 47% | 18.5 → 25.2 / 25.2 | pass |
| L99 | 28% → 62% / 62% | 19.9 → 19.7 / 19.7 | order |

Notes:
- **L60** (dense pack, tier 3): the readable construction builds only 6 of 800 tries (0 pass the base gates), against 28 for the default. Its level fails three floors. Dense boards leave little room to hide containers, and the scoring changes which taps build.
- **Generation time.** Both runs ran side by side on 4 threads each, on a machine at load 140-380 (other agents' jobs). Wall time was 6250 s and 5925 s, against 2184 s for the rebuild preview on 8 threads on a quieter machine (with `--repair both`, which doubles stages 1-2). Summed per-slot times: stage 0 8699 / 8845 s vs 3373 s, stages 1+2 12436 / 11577 s vs 10059 s. These are dominated by the load and are not comparable. The probe (section 3, all variants in one run, same load) is the controlled comparison: construction takes the same time or less (default 266-306 s; w3:d10 242 s; w4:d10:e0.2 181 s).

## 5. Reproducibility and tests

- **Defaults are byte-identical.** With defaults, `OfficialPack --only 4,41` patched into a copy of `app/src/main/assets/levels.json` gives a byte-identical file (`cmp`), and `official-lines.json` is unchanged. `ReadableStallsTest.theDialIsOffByDefault` checks that an explicit `readable = null` builds exactly the default level. `DeterminismTest` passes.
- **New tests** (core, `ReadableStallsTest.kt`):
  - `hiddenBoarded` from the initial state equals `Readability.arrivals` on step-0 forks;
  - readable construction replays to WON and validates, leaves fewer reachable containers whose colour is not on show, and does not lower the readable share on the construction line;
  - `runBudget` = 10 completes the stall's dummies during the target's tap.
- **`./gradlew test` in the copy**: BUILD SUCCESSFUL. core: 179 tests, 0 failed (3 skipped, as before). levelgen: 7, 0 failed. app unit tests pass.

## 6. Code changes

- **core `generator/reverse/StallDirector.kt`:**
  - `ReadableDef` (new, documented);
  - `StallScript.readable` (default null);
  - candidate scoring of the deciding batch (`evaluate`, `readLine`);
  - `coverDead` placement term (`place`);
  - early emission (`completionTap`, `runBudget`);
  - candidate overrides;
  - `StallLevel.roles` (diagnostic construction role per container, default empty).
- **core `generator/reverse/Readability.kt`:** `boardedFrom` (boarded positions from a construction state) and `hiddenBoarded` (one fork's deciding batch). `boarded` delegates to `boardedFrom` and its behaviour is unchanged.
- **levelgen `reverse/RebuildPreview.kt`:**
  - `--readable <spec>`;
  - `--old` may be a preview pack (slot taken from the "Slot: L<n>" tag);
  - `--old-meta` (the old levels' lines from a provenance file);
  - meta records the spec.
- **levelgen `reverse/ReadableProbe.kt`** (new): the construction probe of section 3, and `readableDef(spec, ring, belt)`.
- **levelgen `reverse/ReadableDiag.kt`** (new): the diagnosis of section 1.
- **core test `generator/reverse/ReadableStallsTest.kt`** (new).

Outputs: `readable-stalls-levels.json` and `readable-stalls-meta.json` (the w3:d10 pack, 20 levels, provenance keyed by content hash, with `oldHash` = the rebuild-preview level), plus `readable-stalls-w3d10*` and `readable-stalls-w4d10*` (both variants' packs, meta and generated tables).

Run: `./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.RebuildPreviewKt --args="--readable w3:d10 --repair off --old levelgen/out/rebuild-preview-levels.json --old-meta levelgen/out/rebuild-preview-meta.json --out levelgen/out/readable-stalls-w3d10"`

## 7. What would move it further

- **Readability needs short windows near holds.** The deciding batch of an 8-move window is beyond the belt as soon as a hold resolves after the first ~belt boardings. Readable deep forks therefore need either (a) loss windows of at most about 3 moves where the line holds containers, or (b) a stretch with no hold completions in the later part of every deep window. The juggle opening is built from back-to-back hand-overs, so it cannot meet (b). A readable opening would be a different mechanic: dig-order choices whose batches already sit on the carousel (principle 12 mismatch windows), not dock holds.
- **Selection.** Fitness5 has no readability term. It picked levels that are worse on readability in 8-9 slots, because hidden bits and the floors reward the deep, hold-heavy tries. Adding a readable-share axis or floor to fitness5, or letting default and readable tries compete in one pool (as `--repair both` does), is the cheaper next step. The floor losses come mostly from order robustness and the blind ratio.
