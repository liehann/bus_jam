# Campaign rebuild favouring clued squeezes

A **squeeze** is a point where the dock is filling and the player must choose carefully (a stall in the generator's
code). It is **clued** when the plates that decide the right choice are already on the carousel or the belt at decision
time; the measure is the clued-fork share on the solver line.

Run: `OfficialPackF5 --threads 6 --reuse none --clued off,w3:d10 --clued-weight 15 --out levelgen/out/clued-rebuild`
(seed 7, 400 tries, x2 on tier >= 2). Every slot except the tutorial got a new candidate from one pool holding the default
construction's tries and the clued-squeeze construction's (`w3:d10`), ranked by accepted, fewest failed floors, then
`dist5(fitness5) - 15 x clued-fork share`. The keep-or-replace rule (`decide`) uses the same order and weight, with the
churn margin of 2.0 on that score. Wall time 24,843 s (6.9 h) on 6 threads on a shared machine (load 40-100); CPU time
over the slots 41 h.

**51 slots replaced, 49 kept** (34 replacements built with the clued-squeeze construction, 17 with the default one).

## Campaign totals (100 levels, solver line)

| measure | shipped before | rebuilt (installed) |
|---|---|---|
| clued-fork share (mean) | 39% | 56% |
| unfair decisions (sum) | 18 | 16 |
| levels with >= 1 unfair decision | 17 | 14 |
| traps (sum) | 2 | 2 |
| deep traps (sum) | 1 | 2 |
| blind bits (mean) | 3.1 | 2.6 |
| fair hidden bits (mean) | 19.5 | 19.1 |
| hidden bits (mean) | 19.7 | 19.5 |
| winnable at 1/3, uniform (mean) | 51% | 51% |
| winnable at 1/3, H = 40 (mean) | 58% | 59% |
| choices with w >= 2 (mean) | 76% | 76% |
| levels passing gates + floors | 81 | 90 |
| fitness5 (mean) | 34.8 | 33.9 |

**Verdict: installed.** Clued share, unfair decisions, blind bits, winnable at 1/3 and floors passed improve; traps and
w >= 2 hold. Two measures slip slightly: fair hidden bits (19.5 to 19.1, the difficulty carrier, -2%) and deep traps
(1 to 2, with total traps unchanged). The extra deep trap and two unfair decisions come from L70, which was replaced
because the shipped level fails its order floor (winnable from 1/3 = 0) and the new one passes all floors. That is the
floors-first rule, which already applied before the clued weight was added; L57 lost its trap.

Determinism: `OfficialReplayKt` rebuilt all 100 shipped levels from `levelgen/out/official-meta.json` and each is
identical to the installed one (34 of them from the clued-squeeze construction, recorded under `clued`).

The provenance records of the new levels carry commit `303d070-dirty`: the tree had an unrelated edit to
`tools/prototype_pack.py` while the run was going. Generator code was at 303d070.

The full generated report follows (per-slot table with old → new for every measure, the clued-fork share and the
reason for each decision; totals with the all-new column; by tier; floors; difficulty curve).

---

## Official campaign under fitness5 (generated report)

Every slot of the official plan (`OfficialPack`: same recipes, tiers, cycles, planned rings, no mechanics, seed 7, 400 tries, x2 on tier >= 2, same director seeds) got a new candidate selected by fitness5 with the tier floors (`Fitness5Config`, id c80a0f57; `select5v`, the queue repair competing with the unrepaired level) at the belt of `beltFor` (10 on tier 0 and cycles 1-2 except 12 / 16 on tiers 2 / 3 there; else 14 / 16 / 20 on tiers 1 / 2 / 3). The 0 slots of the rebuild preview were not regenerated: their levels come from `rebuild-preview-levels.json` and were re-measured here.

**Keep or replace.** The shipped level and the candidate are measured the same way (long chain x16, order dependence over 64 draws at 200000 nodes, intended + solver line, the solver line at 3M nodes). The shipped level stays unless the candidate is strictly better in the selection's order: passes the base gates and floors, then fewer failed floors, then fitness5 closer to the recipe target (higher on max recipes) less 15 x the clued-fork share (solver line: forks whose deciding plates are on the carousel or belt at decision time), as in the selection. When both pass, the candidate must get at least 2.0 closer to the target (fewer needless replacements); with the same number of failed floors, only the same floors, failed by less or with fitness5 at least 2.0 closer. A candidate must also be solved by `Solver.solve(level, 2M)`. Ties keep the shipped level.

Commit 303d070-dirty. CPU time over the slots 148281 s (41.2 h); runs: 2026-10-29T14:07:05.287415Z slots 100 threads 6 wall 24843 s commit 303d070-dirty.

**51 slots replaced, 49 kept.** Replacements by construction: default 17, w3:d10 34.

## Per slot (old → new; solver line unless noted)

Floors and fitness5 on the mean over the lines (what selection checks). Winnable at 1/3: uniform / with H = 40 re-drawn. Decision in the last column; `*` = from the rebuild preview.

| slot | recipe | tier | ring | belt | unfair | traps | blind bits | fair hidden bits | hidden bits | winnable at 1/3 | w>=2, mean w | clued forks | fitness5 | floors old | floors new | decision |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L1 | tutorial | 0 | 40 | 10 | 0 | 0 | 0.0 | 0.0 | 0.0 | 100% / 100% | 100%, 2.00 | 100% | 7.0 | fairhidden 0.0 | | kept (tutorial (not rebuilt)) |
| L2 | juggle intro | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 2.2 → 0.0 | 9.5 → 3.0 | 9.5 → 3.0 | 100% / 100% → 100% / 100% | 89%, 3.41 → 78%, 2.52 | 44% → 100% (w3:d10) | 20.4 → 20.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L3 | juggle + mismatch window | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 2.0 → 1.5 | 6.8 → 9.3 | 6.8 → 9.3 | 98% / 86% → 100% / 100% | 72%, 2.64 → 83%, 3.57 | 60% → 83% | 20.0 → 22.1 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's); old try re-run differs |
| L4 | juggle + just-enough cascade | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.0 → 0.0 | 2.0 → 4.6 | 2.0 → 4.6 | 100% / 100% → 100% / 100% | 94%, 4.85 → 94%, 5.62 | 0% → 100% | 16.1 → 19.7 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L5 | juggle + left-slot steal | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.3 → 0.0 | 8.3 → 7.0 | 8.3 → 7.0 | 58% / 61% → 95% / 63% | 82%, 3.06 → 82%, 2.76 | 40% → 100% (w3:d10) | 25.2 → 24.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L6 | juggle + herald (the climax colour waits early) | 1 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 2.2 → 2.2 | 10.4 → 10.4 | 10.4 → 10.4 | 100% / 100% → 100% / 100% | 91%, 6.70 → 91%, 6.70 | 83% → 83% | 29.2 → 29.2 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L7 | juggle + interrupted run + cascade | 1 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 3.9 → 3.9 | 21.5 → 21.5 | 21.5 → 21.5 | 98% / 100% → 98% / 100% | 75%, 3.20 → 75%, 3.20 | 25% → 25% | 30.7 → 30.7 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L8 | juggle + steal + keystone | 1 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.0 → 2.9 | 7.0 → 11.6 | 7.0 → 11.6 | 100% / 97% → 39% / 38% | 89%, 6.52 → 89%, 3.48 | 20% → 20% (w3:d10) | 34.2 → 28.6 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L9 | juggle + rolling + heralded climax, mirror | 1 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.0 → 1.0 | 6.5 → 5.3 | 6.5 → 5.3 | 36% / 42% → 83% / 88% | 94%, 3.26 → 85%, 4.13 | 0% → 100% (w3:d10) | 34.8 → 28.8 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L10 | hard: juggle, scarce key -> heralded chain climax | 3 | 40 → 40 | 16 → 16 | 0 → 0 | 0 → 0 | 2.8 → 2.8 | 39.9 → 39.9 | 39.9 → 39.9 | 0% / 0% → 0% / 0% | 46%, 1.71 → 46%, 1.71 | 32% → 32% | 72.1 → 72.1 | order 0.00 | order 0.00 | **kept** (new level is not better); old try re-run differs |
| L11 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.0 → 0.0 | 4.6 → 2.0 | 4.6 → 2.0 | 100% / 100% → 100% / 100% | 87%, 2.87 → 87%, 2.52 | 67% → 100% (w3:d10) | 19.0 → 17.3 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L12 | juggle + mismatch window | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 4.0 → 0.0 | 11.7 → 4.8 | 11.7 → 4.8 | 25% / 11% → 100% / 100% | 80%, 2.90 → 73%, 2.20 | 27% → 100% | 18.2 → 22.6 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L13 | juggle + just-enough cascade | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 3.2 → 2.2 | 6.3 → 9.8 | 6.3 → 9.8 | 81% / 84% → 100% / 100% | 76%, 2.29 → 91%, 2.24 | 20% → 50% (w3:d10) | 16.3 → 17.4 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L14 | juggle + left-slot steal | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 2.0 → 0.0 | 10.1 → 3.0 | 10.1 → 3.0 | 98% / 100% → 100% / 100% | 85%, 3.21 → 94%, 3.64 | 78% → 100% | 23.2 → 23.8 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L15 | juggle + herald (the climax colour waits early) | 1 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 4.8 → 0.0 | 21.0 → 9.5 | 21.0 → 9.5 | 30% / 70% → 97% / 100% | 59%, 2.27 → 89%, 5.05 | 12% → 50% | 28.5 → 30.1 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L16 | juggle + interrupted run + cascade | 1 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.0 → 3.4 | 8.6 → 17.2 | 8.6 → 17.2 | 100% / 100% → 44% / 38% | 87%, 3.87 → 79%, 3.21 | 0% → 33% | 23.6 → 27.9 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's); old try re-run differs |
| L17 | juggle + steal + keystone | 1 | 40 → 40 | 10 → 10 | 1 → 0 | 0 → 0 | 3.9 → 0.0 | 20.2 → 10.0 | 21.6 → 10.0 | 67% / 56% → 16% / 19% | 79%, 2.98 → 79%, 2.96 | 21% → 75% (w3:d10) | 27.4 → 30.2 | unfair 1.5 | unfair 0.5 | **replaced** (fails the same floors by less); old try re-run differs |
| L18 | juggle + rolling + heralded climax, mirror | 1 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 3.5 → 4.0 | 15.4 → 16.3 | 15.4 → 16.3 | 100% / 100% → 80% / 20% | 86%, 4.82 → 77%, 2.73 | 13% → 54% (w3:d10) | 27.3 → 21.3 | blindratio 0.39 | pass | **replaced** (new level passes the floors, the shipped one fails) |
| L19 | juggle, mirror spine + steal | 2 | 40 → 40 | 12 → 12 | 0 → 0 | 0 → 0 | 6.7 → 6.2 | 21.1 → 27.9 | 21.1 → 27.9 | 88% / 77% → 100% / 100% | 84%, 4.34 → 64%, 2.40 | 20% → 35% (w3:d10) | 39.6 → 33.8 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L20 | hard: juggle, scarce key -> heralded chain climax | 3 | 40 → 40 | 16 → 16 | 0 → 0 | 0 → 0 | 3.7 → 3.7 | 29.6 → 29.6 | 29.6 → 29.6 | 8% / 11% → 8% / 11% | 83%, 4.46 → 83%, 4.46 | 30% → 30% | 54.4 → 54.4 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L21 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 4.0 → 3.8 | 9.0 → 8.9 | 9.0 → 8.9 | 100% / 100% → 100% / 100% | 57%, 1.91 → 61%, 2.13 | 33% → 67% | 13.8 → 14.6 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L22 | juggle + just-enough cascade | 0 | 38 → 38 | 10 → 10 | 0 → 0 | 0 → 0 | 3.0 → 0.0 | 10.8 → 4.6 | 10.8 → 4.6 | 100% / 95% → 70% / 64% | 88%, 3.00 → 74%, 2.32 | 0% → 100% (w3:d10) | 17.0 → 27.9 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L23 | juggle + left-slot steal | 0 | 38 → 38 | 10 → 10 | 0 → 0 | 0 → 0 | 1.0 → 1.0 | 6.0 → 5.0 | 6.0 → 5.0 | 58% / 34% → 80% / 100% | 58%, 1.73 → 75%, 2.21 | 33% → 80% (w3:d10) | 20.8 → 26.1 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L24 | juggle + interrupted run + cascade | 1 | 38 → 38 | 14 → 14 | 0 → 0 | 0 → 0 | 1.0 → 1.0 | 4.7 → 8.2 | 4.7 → 8.2 | 34% / 56% → 100% / 92% | 85%, 3.55 → 75%, 2.53 | 50% → 100% (w3:d10) | 24.8 → 26.2 | fairhidden 2.4 | pass | **replaced** (new level passes the floors, the shipped one fails); old try re-run differs |
| L25 | juggle + steal + keystone | 1 | 38 → 38 | 14 → 14 | 0 → 0 | 0 → 0 | 0.0 → 0.0 | 8.1 → 8.1 | 8.1 → 8.1 | 100% / 100% → 100% / 100% | 85%, 3.43 → 85%, 3.43 | 50% → 50% | 29.1 → 29.1 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L26 | juggle + rolling + heralded climax, mirror | 1 | 38 → 38 | 14 → 14 | 0 → 0 | 0 → 0 | 1.6 → 1.6 | 12.9 → 12.9 | 12.9 → 12.9 | 91% / 88% → 91% / 88% | 67%, 2.63 → 67%, 2.63 | 45% → 45% | 28.7 → 28.7 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L27 | juggle, mirror spine + steal | 2 | 36 → 36 | 16 → 16 | 1 → 1 | 0 → 0 | 0.6 → 0.6 | 22.3 → 22.3 | 22.8 → 22.8 | 20% / 19% → 20% / 19% | 64%, 2.08 → 64%, 2.08 | 67% → 67% | 32.4 → 32.4 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L28 | juggle, gutter halves + heralded climax | 2 | 36 → 36 | 16 → 16 | 0 → 0 | 0 → 0 | 2.1 → 0.6 | 17.2 → 28.0 | 17.2 → 28.0 | 100% / 89% → 83% / 52% | 88%, 3.69 → 70%, 3.18 | 44% → 71% | 33.1 → 33.6 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L29 | juggle, domino stacks + interrupted run | 2 | 40 → 40 | 10 → 16 | 0 → 0 | 0 → 0 | 6.8 → 1.6 | 19.0 → 27.3 | 19.0 → 27.3 | 91% / 98% → 19% / 20% | 82%, 4.72 → 66%, 2.69 | 33% → 77% (w3:d10) | 35.8 → 37.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L30 | juggle, everything, scarce key -> heralded long chain | 3 | 36 → 36 | 20 → 20 | 0 → 0 | 0 → 0 | 0.6 → 0.3 | 35.4 → 34.6 | 35.4 → 34.6 | 0% / 11% → 0% / 36% | 63%, 3.44 → 59%, 3.50 | 59% → 69% | 61.6 → 61.4 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L31 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.0 → 0.0 | 5.3 → 7.0 | 5.3 → 7.0 | 100% / 100% → 100% / 100% | 68%, 1.86 → 83%, 3.65 | 100% → 100% (w3:d10) | 17.2 → 16.5 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L32 | juggle + herald (the climax colour waits early) | 1 | 36 → 36 | 14 → 14 | 0 → 0 | 0 → 0 | 2.8 → 1.4 | 13.4 → 10.1 | 13.4 → 10.1 | 100% / 100% → 100% / 100% | 82%, 3.52 → 88%, 3.83 | 25% → 63% | 26.4 → 28.3 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L33 | juggle + interrupted run + cascade | 1 | 36 → 36 | 14 → 14 | 0 → 0 | 0 → 0 | 0.0 → 1.2 | 8.8 → 12.3 | 8.8 → 12.3 | 98% / 98% → 100% / 94% | 90%, 4.83 → 85%, 4.45 | 40% → 100% | 25.0 → 25.0 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L34 | juggle + steal + keystone | 1 | 36 → 36 | 14 → 14 | 0 → 0 | 0 → 0 | 6.3 → 4.0 | 27.3 → 16.5 | 27.3 → 16.5 | 84% / 86% → 100% / 98% | 79%, 3.23 → 74%, 3.83 | 18% → 46% | 28.6 → 28.7 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L35 | juggle + rolling + heralded climax, mirror | 1 | 36 → 36 | 14 → 14 | 0 → 0 | 0 → 0 | 1.0 → 1.0 | 12.5 → 12.5 | 12.5 → 12.5 | 47% / 34% → 47% / 34% | 79%, 3.38 → 79%, 3.38 | 50% → 50% | 32.3 → 32.3 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L36 | juggle, mirror spine + steal | 2 | 36 → 36 | 16 → 16 | 0 → 0 | 0 → 0 | 3.7 → 3.7 | 18.6 → 18.6 | 18.6 → 18.6 | 100% / 100% → 100% / 100% | 80%, 4.24 → 80%, 4.24 | 63% → 63% | 33.6 → 33.6 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L37 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 3.0 → 3.0 | 25.3 → 25.3 | 25.3 → 25.3 | 28% / 39% → 28% / 39% | 78%, 4.80 → 78%, 4.80 | 57% → 57% | 33.1 → 33.1 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L38 | juggle, domino stacks + interrupted run | 2 | 38 → 38 | 16 → 16 | 0 → 0 | 0 → 0 | 1.6 → 1.6 | 15.0 → 15.0 | 15.0 → 15.0 | 98% / 100% → 98% / 100% | 86%, 3.93 → 86%, 3.93 | 80% → 80% | 37.8 → 37.8 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L39 | juggle, squatter dig + steal | 2 | 38 → 38 | 16 → 16 | 0 → 0 | 0 → 0 | 6.2 → 6.2 | 25.5 → 25.5 | 25.5 → 25.5 | 0% / 20% → 0% / 20% | 77%, 4.26 → 77%, 4.26 | 36% → 36% | 41.9 → 41.9 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L40 | superhard: dense pack, juggle, scarce key, heralded climax | 3 | 36 → 36 | 20 → 20 | 0 → 0 | 0 → 0 | 2.2 → 2.2 | 36.5 → 36.5 | 36.5 → 36.5 | 0% / 97% → 0% / 97% | 77%, 3.57 → 77%, 3.57 | 23% → 23% | 60.2 → 60.2 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L41 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 3.2 → 0.4 | 7.8 → 4.7 | 7.8 → 4.7 | 100% / 100% → 100% / 100% | 68%, 1.95 → 83%, 3.83 | 60% → 67% (w3:d10) | 17.6 → 17.0 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L42 | juggle + interrupted run + cascade | 1 | 34 → 34 | 14 → 14 | 0 → 0 | 0 → 0 | 0.7 → 0.7 | 9.2 → 9.2 | 9.2 → 9.2 | 100% / 100% → 100% / 100% | 88%, 3.43 → 88%, 3.43 | 82% → 82% | 25.6 → 25.6 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L43 | juggle + steal + keystone | 1 | 34 → 34 | 14 → 14 | 0 → 0 | 0 → 0 | 1.5 → 2.9 | 13.6 → 21.9 | 13.6 → 21.9 | 22% / 56% → 97% / 41% | 85%, 2.64 → 83%, 2.67 | 36% → 50% (w3:d10) | 34.2 → 34.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L44 | juggle + rolling + heralded climax, mirror | 1 | 34 → 34 | 14 → 14 | 0 → 0 | 0 → 0 | 1.8 → 0.4 | 18.7 → 13.5 | 18.7 → 13.5 | 100% / 100% → 39% / 39% | 79%, 3.23 → 83%, 3.81 | 35% → 71% (w3:d10) | 36.7 → 27.3 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L45 | juggle, mirror spine + steal | 2 | 38 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 1.7 → 1.8 | 22.4 → 19.6 | 22.4 → 19.6 | 80% / 11% → 73% / 41% | 66%, 2.44 → 72%, 2.62 | 39% → 67% (w3:d10) | 31.7 → 32.6 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L46 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 1 → 0 | 0 → 0 | 6.3 → 10.2 | 26.0 → 26.8 | 28.9 → 26.8 | 88% / 95% → 88% / 100% | 69%, 3.35 → 74%, 3.94 | 18% → 53% (w3:d10) | 38.9 → 40.7 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L47 | juggle, domino stacks + interrupted run | 2 | 40 → 40 | 16 → 16 | 0 → 0 | 0 → 0 | 0.0 → 0.0 | 19.6 → 19.6 | 19.6 → 19.6 | 22% / 13% → 22% / 13% | 59%, 2.30 → 59%, 2.30 | 73% → 73% | 36.2 → 36.2 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L48 | juggle, squatter dig + steal | 2 | 40 → 40 | 10 → 16 | 0 → 0 | 0 → 0 | 11.3 → 1.0 | 24.7 → 18.2 | 24.7 → 18.2 | 0% / 2% → 0% / 0% | 82%, 2.77 → 68%, 2.75 | 9% → 59% (w3:d10) | 35.2 → 37.2 | order 0.02 | order 0.00 | **replaced** (fails the same floors, score (fitness5 with clued weight 15) better by >= 2.0) |
| L49 | juggle, keystone + cascades, heralded climax | 2 | 36 → 32 | 16 → 16 | 0 → 1 | 0 → 0 | 8.0 → 2.7 | 30.1 → 28.4 | 30.5 → 29.4 | 8% / 25% → 28% / 92% | 87%, 4.06 → 78%, 3.91 | 4% → 46% | 40.8 → 49.4 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L50 | dense pack + interrupted run | 3 | 40 → 40 | 20 → 20 | 2 → 2 | 1 → 1 | 4.4 → 7.3 | 25.3 → 28.3 | 27.2 → 30.4 | 0% / 25% → 0% / 9% | 70%, 3.14 → 81%, 2.48 | 45% → 58% | 48.3 → 47.5 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L51 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 3.2 → 0.0 | 6.3 → 2.0 | 6.3 → 2.0 | 100% / 100% → 100% / 100% | 78%, 2.65 → 83%, 2.43 | 50% → 100% (w3:d10) | 17.9 → 16.0 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L52 | juggle + steal + keystone | 1 | 40 → 40 | 14 → 14 | 0 → 0 | 0 → 0 | 4.2 → 2.0 | 24.2 → 23.1 | 24.2 → 23.1 | 100% / 98% → 100% / 92% | 68%, 2.96 → 66%, 2.98 | 16% → 30% | 29.0 → 29.6 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L53 | juggle + rolling + heralded climax, mirror | 1 | 32 → 32 | 14 → 14 | 0 → 0 | 0 → 0 | 1.1 → 1.1 | 9.0 → 9.0 | 9.0 → 9.0 | 94% / 97% → 94% / 97% | 91%, 4.49 → 91%, 4.49 | 100% → 100% | 30.3 → 30.3 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L54 | juggle, mirror spine + steal | 2 | 40 → 36 | 16 → 16 | 1 → 0 | 0 → 0 | 0.9 → 0.3 | 26.9 → 22.5 | 27.3 → 22.5 | 3% / 16% → 97% / 100% | 66%, 2.64 → 76%, 3.62 | 48% → 43% (w3:d10) | 34.1 → 31.4 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's); old try re-run differs |
| L55 | juggle, gutter halves + heralded climax | 2 | 32 → 32 | 10 → 16 | 0 → 0 | 0 → 0 | 1.3 → 3.0 | 23.5 → 24.9 | 23.5 → 27.5 | 0% / 3% → 0% / 0% | 76%, 4.20 → 64%, 3.20 | 43% → 58% (w3:d10) | 41.2 → 37.8 | order 0.03 | order 0.07 | **replaced** (fails the same floors by less) |
| L56 | juggle, squatter dig + steal | 2 | 36 → 32 | 10 → 16 | 0 → 0 | 0 → 0 | 1.0 → 1.4 | 18.6 → 27.9 | 18.6 → 27.9 | 0% / 0% → 0% / 28% | 61%, 3.52 → 73%, 2.95 | 31% → 32% (w3:d10) | 40.5 → 42.9 | order 0.00 | pass | **replaced** (new level passes the floors, the shipped one fails) |
| L57 | juggle + chained runs | 2 | 30 → 34 | 16 → 16 | 1 → 0 | 1 → 0 | 4.0 → 2.6 | 37.8 → 33.8 | 39.4 → 33.8 | 0% / 2% → 2% / 3% | 62%, 2.28 → 60%, 1.97 | 39% → 42% (w3:d10) | 41.7 → 34.0 | order 0.01 | order 0.03 | **replaced** (fails the same floors by less); old try re-run differs |
| L58 | juggle, keystone + cascades, heralded climax | 2 | 34 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 0.3 → 0.3 | 19.5 → 19.5 | 19.5 → 19.5 | 8% / 56% → 2% / 58% | 82%, 4.38 → 82%, 4.38 | 67% → 75% | 38.6 → 38.6 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L59 | juggle, rolling + squatter | 2 | 38 → 38 | 16 → 16 | 0 → 0 | 0 → 0 | 5.6 → 3.5 | 27.3 → 26.7 | 27.3 → 26.7 | 0% / 42% → 0% / 39% | 78%, 4.38 → 77%, 4.00 | 32% → 39% | 42.9 → 43.6 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's); old try re-run differs |
| L60 | dense pack, gutter halves, heralded | 3 | 36 → 36 | 20 → 20 | 1 → 1 | 0 → 0 | 4.8 → 4.8 | 40.4 → 40.4 | 42.4 → 42.4 | 0% / 3% → 0% / 3% | 67%, 2.68 → 67%, 2.68 | 53% → 53% | 60.4 → 60.4 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L61 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 1.0 → 0.6 | 7.0 → 3.9 | 7.0 → 3.9 | 100% / 100% → 100% / 100% | 62%, 1.67 → 91%, 2.70 | 71% → 100% | 17.3 → 20.1 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L62 | juggle + rolling + heralded climax, mirror | 1 | 30 → 30 | 14 → 14 | 0 → 0 | 0 → 0 | 4.1 → 4.1 | 26.4 → 26.4 | 26.4 → 26.4 | 100% / 95% → 100% / 95% | 72%, 2.81 → 72%, 2.81 | 26% → 26% | 38.7 → 38.7 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L63 | juggle, mirror spine + steal | 2 | 30 → 30 | 10 → 16 | 0 → 0 | 0 → 0 | 5.3 → 1.8 | 21.3 → 18.0 | 21.3 → 18.0 | 45% / 42% → 100% / 100% | 77%, 4.29 → 80%, 4.28 | 0% → 91% | 35.3 → 36.3 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L64 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 1.0 → 1.0 | 20.2 → 20.2 | 20.2 → 20.2 | 64% / 72% → 64% / 72% | 78%, 4.00 → 78%, 4.00 | 27% → 27% | 29.9 → 29.9 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L65 | juggle, domino stacks + interrupted run | 2 | 30 → 30 | 16 → 16 | 0 → 0 | 0 → 0 | 4.9 → 3.3 | 24.5 → 22.3 | 24.5 → 22.3 | 63% / 47% → 27% / 34% | 76%, 4.07 → 80%, 4.25 | 33% → 52% | 34.5 → 32.9 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L66 | juggle, squatter dig + steal | 2 | 38 → 30 | 16 → 16 | 0 → 1 | 0 → 0 | 6.0 → 5.5 | 28.3 → 25.9 | 28.3 → 26.9 | 0% / 17% → 0% / 14% | 78%, 3.58 → 75%, 3.25 | 38% → 28% (w3:d10) | 42.9 → 31.2 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L67 | juggle + chained runs | 2 | 36 → 28 | 16 → 16 | 1 → 0 | 0 → 0 | 4.8 → 2.4 | 27.1 → 30.9 | 28.4 → 30.9 | 0% / 0% → 0% / 39% | 65%, 2.60 → 69%, 2.79 | 19% → 48% (w3:d10) | 37.4 → 36.5 | order 0.00 | openbits 0.03 | **kept** (fails other floors than the shipped level (openbits 0.03 vs order 0.00)) |
| L68 | juggle, keystone + cascades, heralded climax | 2 | 40 → 32 | 16 → 16 | 1 → 0 | 0 → 0 | 3.0 → 1.4 | 25.8 → 33.2 | 26.8 → 33.2 | 0% / 34% → 75% / 89% | 61%, 2.37 → 80%, 3.52 | 38% → 65% (w3:d10) | 44.0 → 34.5 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L69 | juggle, rolling + squatter | 2 | 36 → 28 | 16 → 16 | 0 → 0 | 0 → 0 | 4.1 → 6.3 | 32.0 → 25.1 | 32.0 → 25.1 | 0% / 2% → 0% / 98% | 69%, 2.24 → 75%, 4.23 | 13% → 47% (w3:d10) | 40.0 → 35.7 | order 0.02 | pass | **replaced** (new level passes the floors, the shipped one fails); old try re-run differs |
| L70 | dense pack, squatter dig | 3 | 40 → 36 | 20 → 20 | 0 → 2 | 0 → 1 | 2.6 → 8.3 | 32.8 → 28.8 | 32.8 → 37.3 | 0% / 0% → 0% / 23% | 59%, 2.55 → 63%, 3.38 | 57% → 37% (w3:d10) | 65.4 → 32.4 | order 0.00 | pass | **replaced** (new level passes the floors, the shipped one fails); old try re-run differs |
| L71 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 3.0 → 0.0 | 7.2 → 3.2 | 7.2 → 3.2 | 100% / 100% → 100% / 100% | 70%, 2.43 → 86%, 2.95 | 33% → 100% | 17.8 → 16.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L72 | juggle, mirror spine + steal | 2 | 34 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 5.2 → 5.2 | 20.0 → 20.0 | 20.0 → 20.0 | 45% / 72% → 45% / 72% | 90%, 3.54 → 90%, 3.54 | 35% → 35% | 31.7 → 31.7 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L73 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 1 → 1 | 0 → 0 | 3.9 → 3.9 | 17.8 → 17.8 | 18.5 → 18.5 | 69% / 59% → 69% / 59% | 82%, 4.04 → 82%, 4.04 | 20% → 20% | 33.6 → 33.6 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L74 | juggle, domino stacks + interrupted run | 2 | 30 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 7.0 → 10.0 | 27.7 → 35.1 | 27.7 → 35.1 | 53% / 53% → 72% / 97% | 81%, 3.09 → 72%, 3.69 | 42% → 68% (w3:d10) | 34.1 → 36.5 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's) |
| L75 | juggle, squatter dig + steal | 2 | 30 → 38 | 16 → 16 | 0 → 1 | 0 → 0 | 0.5 → 5.0 | 18.1 → 22.2 | 18.1 → 22.9 | 27% / 67% → 2% / 44% | 75%, 4.85 → 79%, 2.74 | 46% → 43% (w3:d10) | 45.9 → 37.9 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L76 | juggle, squatter dig + steal | 2 | 38 → 38 | 16 → 16 | 1 → 1 | 0 → 0 | 3.4 → 3.9 | 22.7 → 33.5 | 25.0 → 40.4 | 0% / 0% → 0% / 16% | 58%, 2.77 → 85%, 3.54 | 52% → 19% (w3:d10) | 37.7 → 48.1 | order 0.00 | pass | **replaced** (new level passes the floors, the shipped one fails); old try re-run differs |
| L77 | juggle + chained runs | 2 | 36 → 36 | 16 → 16 | 1 → 1 | 0 → 0 | 6.9 → 6.9 | 25.9 → 25.9 | 28.4 → 28.4 | 16% / 11% → 16% / 11% | 63%, 2.29 → 63%, 2.29 | 25% → 25% | 38.0 → 38.0 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L78 | juggle, keystone + cascades, heralded climax | 2 | 40 → 36 | 10 → 16 | 1 → 0 | 0 → 0 | 0.6 → 0.7 | 12.4 → 17.7 | 12.8 → 17.7 | 0% / 0% → 30% / 59% | 88%, 3.34 → 92%, 3.21 | 50% → 25% (w3:d10) | 39.2 → 31.9 | order 0.00 | pass | **replaced** (new level passes the floors, the shipped one fails) |
| L79 | juggle, rolling + squatter | 2 | 28 → 36 | 16 → 16 | 0 → 0 | 0 → 0 | 6.9 → 1.3 | 37.7 → 30.3 | 37.7 → 30.3 | 0% / 52% → 0% / 36% | 70%, 3.63 → 78%, 3.70 | 33% → 52% (w3:d10) | 50.9 → 42.8 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L80 | dense pack, mirror spine | 3 | 40 → 40 | 20 → 20 | 0 → 0 | 0 → 0 | 0.0 → 0.0 | 28.9 → 28.9 | 28.9 → 28.9 | 5% / 42% → 5% / 42% | 69%, 4.35 → 69%, 4.35 | 54% → 54% | 63.3 → 63.3 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L81 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 0.6 → 0.6 | 5.5 → 5.5 | 5.5 → 5.5 | 100% / 100% → 100% / 100% | 87%, 2.65 → 87%, 2.65 | 83% → 83% | 16.0 → 16.0 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L82 | juggle, mirror spine + steal | 2 | 30 → 30 | 16 → 16 | 1 → 0 | 0 → 0 | 5.2 → 0.0 | 16.8 → 23.6 | 17.4 → 23.6 | 86% / 94% → 58% / 97% | 71%, 2.16 → 64%, 2.72 | 15% → 56% | 32.3 → 29.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L83 | juggle, gutter halves + heralded climax | 2 | 30 → 38 | 16 → 16 | 0 → 0 | 0 → 0 | 2.9 → 1.0 | 24.0 → 20.3 | 24.0 → 20.3 | 44% / 39% → 97% / 81% | 88%, 3.94 → 79%, 3.94 | 27% → 47% | 36.1 → 36.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L84 | juggle, domino stacks + interrupted run | 2 | 30 → 30 | 16 → 16 | 0 → 0 | 0 → 0 | 2.2 → 3.9 | 7.5 → 25.4 | 7.5 → 25.4 | 98% / 100% → 5% / 64% | 89%, 4.13 → 78%, 2.57 | 86% → 20% (w3:d10) | 33.8 → 30.6 | fairhidden 7.5 | pass | **replaced** (new level passes the floors, the shipped one fails) |
| L85 | juggle, squatter dig + steal | 2 | 38 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 4.2 → 7.2 | 22.9 → 30.0 | 22.9 → 30.0 | 0% / 16% → 16% / 30% | 75%, 3.28 → 71%, 2.90 | 41% → 30% (w3:d10) | 40.7 → 34.7 | pass | pass | **kept** (both pass, new score (fitness5 with clued weight 15) within 2.0 of the shipped one's); old try re-run differs |
| L86 | juggle, squatter dig + steal | 2 | 34 → 34 | 16 → 16 | 0 → 0 | 0 → 0 | 2.0 → 2.0 | 26.8 → 26.8 | 26.8 → 26.8 | 0% / 31% → 0% / 31% | 75%, 3.48 → 75%, 3.48 | 33% → 33% | 53.1 → 53.1 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L87 | juggle + chained runs | 2 | 40 → 40 | 16 → 16 | 0 → 0 | 0 → 0 | 0.0 → 0.0 | 17.2 → 17.2 | 17.2 → 17.2 | 50% / 30% → 50% / 30% | 82%, 3.23 → 82%, 3.23 | 33% → 33% | 39.8 → 39.8 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L88 | juggle, keystone + cascades, heralded climax | 2 | 36 → 36 | 10 → 16 | 1 → 0 | 0 → 0 | 4.6 → 2.6 | 28.3 → 24.8 | 28.9 → 24.8 | 3% / 13% → 3% / 17% | 61%, 2.06 → 69%, 2.31 | 19% → 55% | 37.2 → 39.1 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L89 | juggle, rolling + squatter | 2 | 40 → 32 | 16 → 16 | 0 → 1 | 0 → 0 | 4.4 → 8.5 | 29.5 → 36.5 | 29.5 → 38.7 | 2% / 6% → 16% / 88% | 63%, 2.13 → 85%, 4.10 | 35% → 29% (w3:d10) | 40.0 → 39.5 | order 0.06 | pass | **replaced** (new level passes the floors, the shipped one fails) |
| L90 | juggle, everything, scarce key -> heralded long chain | 3 | 40 → 40 | 20 → 20 | 0 → 0 | 0 → 0 | 2.2 → 2.2 | 32.1 → 32.1 | 32.1 → 32.1 | 0% / 23% → 0% / 23% | 50%, 1.76 → 50%, 1.76 | 52% → 52% | 53.4 → 53.4 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L91 | breather juggle | 0 | 40 → 40 | 10 → 10 | 0 → 0 | 0 → 0 | 3.0 → 5.0 | 6.2 → 10.0 | 6.2 → 10.0 | 100% / 100% → 100% / 100% | 70%, 2.57 → 43%, 1.78 | 20% → 60% (w3:d10) | 15.2 → 12.5 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L92 | juggle, mirror spine + steal | 2 | 30 → 38 | 16 → 16 | 0 → 1 | 0 → 0 | 8.5 → 4.6 | 29.9 → 18.4 | 29.9 → 20.0 | 100% / 100% → 41% / 44% | 74%, 3.68 → 72%, 2.22 | 16% → 59% | 33.6 → 34.5 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L93 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 1 → 1 | 0 → 0 | 6.0 → 6.0 | 18.5 → 18.5 | 23.6 → 23.6 | 6% / 67% → 6% / 67% | 76%, 3.64 → 76%, 3.64 | 26% → 26% | 35.4 → 35.4 | pass | pass | **kept** (shipped level passes and scores at least as well); old try re-run differs |
| L94 | juggle, domino stacks + interrupted run | 2 | 34 → 38 | 16 → 16 | 0 → 0 | 0 → 0 | 10.2 → 2.8 | 41.3 → 17.8 | 41.3 → 17.8 | 78% / 100% → 42% / 64% | 67%, 2.54 → 74%, 3.30 | 9% → 47% (w3:d10) | 36.4 → 31.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0) |
| L95 | juggle, squatter dig + steal | 2 | 38 → 38 | 16 → 16 | 0 → 0 | 0 → 0 | 2.2 → 2.2 | 23.8 → 23.8 | 23.8 → 23.8 | 45% / 89% → 45% / 89% | 85%, 4.71 → 85%, 4.71 | 52% → 52% | 43.0 → 43.0 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L96 | juggle, squatter dig + steal | 2 | 40 → 38 | 16 → 16 | 0 → 0 | 0 → 0 | 8.0 → 4.4 | 24.2 → 25.2 | 24.2 → 25.2 | 70% / 83% → 2% / 16% | 81%, 3.34 → 86%, 5.02 | 17% → 31% (w3:d10) | 41.6 → 39.2 | pass | pass | **replaced** (both pass, new score (fitness5 with clued weight 15) better by >= 2.0); old try re-run differs |
| L97 | juggle + chained runs | 2 | 32 → 28 | 16 → 16 | 0 → 0 | 0 → 0 | 1.2 → 0.8 | 21.9 → 17.2 | 21.9 → 17.2 | 0% / 0% → 0% / 3% | 69%, 3.11 → 73%, 3.52 | 58% → 100% | 35.7 → 29.6 | order 0.00 | order 0.03 | **replaced** (fails the same floors by less); old try re-run differs |
| L98 | juggle, keystone + cascades, heralded climax | 2 | 40 → 40 | 16 → 16 | 0 → 0 | 0 → 0 | 3.7 → 3.7 | 22.8 → 22.8 | 22.8 → 22.8 | 19% / 56% → 19% / 56% | 65%, 2.92 → 65%, 2.92 | 48% → 48% | 47.0 → 47.0 | pass | pass | **kept** (shipped level passes and scores at least as well) |
| L99 | juggle, rolling + squatter | 2 | 40 → 28 | 16 → 16 | 0 → 0 | 0 → 0 | 0.4 → 0.4 | 19.9 → 19.7 | 19.9 → 19.7 | 0% / 0% → 0% / 0% | 75%, 2.97 → 78%, 3.58 | 28% → 62% (w3:d10) | 40.4 → 40.0 | order 0.02 | order 0.00 | **replaced** (fails the same floors, score (fitness5 with clued weight 15) better by >= 2.0) |
| L100 | hard: juggle, scarce key -> heralded chain climax | 3 | 40 → 40 | 20 → 20 | 1 → 1 | 0 → 0 | 8.9 → 8.9 | 39.0 → 39.0 | 41.0 → 41.0 | 0% / 0% → 0% / 0% | 59%, 1.81 → 59%, 1.81 | 18% → 18% | 68.0 → 68.0 | order 0.00 | order 0.00 | **kept** (new level is not better) |

## Totals (100 levels, solver line)

Old = the shipped pack before this run; all new = every slot's candidate (what a full regeneration would ship); final = the pack now shipped.

| metric | old | all new | final |
|---|---|---|---|
| clued forks (mean share) | 39% | 58% | 56% |
| levels with >= 1 unfair decision | 17 | 14 | 14 |
| unfair decisions (sum) | 18 | 16 | 16 |
| traps (sum) | 2 | 2 | 2 |
| deep traps (sum) | 1 | 2 | 2 |
| fair share (mean) | 99% | 99% | 99% |
| blind bits (mean) | 3.1 | 2.5 | 2.6 |
| hidden bits (mean) | 19.7 | 19.6 | 19.5 |
| fair hidden bits (mean) | 19.5 | 19.3 | 19.1 |
| blind / hidden (mean) | 0.16 | 0.12 | 0.12 |
| winnable from start, uniform (mean) | 26% | 25% | 25% |
| winnable at 1/3, uniform (mean) | 51% | 52% | 51% |
| winnable at 1/3, H=40 (mean) | 58% | 62% | 59% |
| levels at <= 5% winnable from start (uniform) | 57 | 61 | 61 |
| choices with w >= 2 (mean) | 76% | 77% | 76% |
| mean w (mean) | 3.27 | 3.30 | 3.24 |
| fitness5 (mean) | 34.8 | 33.9 | 33.9 |
| levels passing gates + floors | 81 | 90 | 90 |
| belt 10 / 12 / 14 / 16 / 20 | 34 / 1 / 13 / 44 / 8 | 27 / 1 / 13 / 51 / 8 | 27 / 1 / 13 / 51 / 8 |

## By tier (final pack, solver line means)

| tier | levels | replaced | unfair (sum) | traps (sum) | blind bits | fair hidden bits | hidden bits | winnable at 1/3, H=40 | w>=2 | floors pass |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 19 | 12 | 0 | 0 | 1.0 | 5.3 | 5.3 | 92% | 77% | 18 |
| 1 | 21 | 11 | 0 | 0 | 1.8 | 13.2 | 13.2 | 78% | 81% | 20 |
| 2 | 50 | 27 | 10 | 0 | 3.3 | 23.9 | 24.4 | 46% | 75% | 44 |
| 3 | 10 | 1 | 6 | 2 | 3.8 | 33.6 | 35.0 | 24% | 64% | 8 |

## Levels of the final pack that fail their floors

| slot | tier | decision | fails (final) | candidate fails |
|---|---|---|---|---|
| L1 | 0 | kept | fairhidden 0.0 | - |
| L10 | 3 | kept | order 0.00 | order 0.00 |
| L17 | 1 | replaced | unfair 0.5 | unfair 0.5 |
| L48 | 2 | replaced | order 0.00 | order 0.00 |
| L55 | 2 | replaced | order 0.07 | order 0.07 |
| L57 | 2 | replaced | order 0.03 | order 0.03 |
| L67 | 2 | kept | order 0.00 | openbits 0.03 |
| L97 | 2 | replaced | order 0.03 | order 0.03 |
| L99 | 2 | replaced | order 0.00 | order 0.00 |
| L100 | 3 | kept | order 0.00 | order 0.00 |

By floor: order 8, unfair 1, fairhidden 1

## Difficulty curve by cycle

Difficulty under fitness5 is carried by the fair hidden bits (hidden bits at decisions that can be read; mean over the lines). Rows: cycles; columns: the slot in the cycle (1 = breather, 10 = closer). `*` = replaced.

**Fair hidden bits, final pack**

| cycle | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0.0 | 3.2* | 3.4 | 5.3* | 6.9* | 9.4 | 13.9 | 8.8* | 8.8* | 40.8 |
| 2 | 3.0* | 4.2* | 4.9* | 4.1* | 8.8* | 8.2 | 8.9* | 9.1* | 19.4* | 27.6 |
| 3 | 4.5* | 3.9* | 6.0 | 8.2* | 8.1 | 12.9 | 22.3 | 28.0* | 19.7* | 35.4 |
| 4 | 3.2 | 8.1* | 12.2* | 16.5* | 12.5 | 18.8 | 25.3 | 15.0 | 25.5 | 32.3 |
| 5 | 3.9 | 9.4 | 21.9* | 13.5* | 19.6* | 20.0* | 16.0 | 17.6* | 30.1 | 26.9 |
| 6 | 3.0* | 17.8 | 9.0 | 17.7 | 21.3* | 27.9* | 33.8* | 19.5 | 25.8 | 39.3 |
| 7 | 3.5 | 26.4 | 16.2* | 18.7 | 18.4* | 28.3 | 27.1 | 33.2* | 25.1* | 28.8* |
| 8 | 3.5* | 18.3 | 16.9 | 27.7 | 22.2* | 31.9* | 24.9 | 17.7* | 30.3* | 29.1 |
| 9 | 4.0 | 15.1* | 22.7* | 25.4* | 22.7 | 26.8 | 16.5 | 21.4* | 36.5* | 28.7 |
| 10 | 5.0* | 16.6* | 18.9 | 15.6* | 23.8 | 23.9* | 16.6* | 22.5 | 19.7* | 39.1 |

**Fair hidden bits, old pack**

| cycle | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0.0 | 7.8 | 3.4 | 3.7 | 6.6 | 9.4 | 13.9 | 10.5 | 8.8 | 40.8 |
| 2 | 3.5 | 5.9 | 3.4 | 7.6 | 12.7 | 8.2 | 16.5 | 14.8 | 20.4 | 27.6 |
| 3 | 4.5 | 5.4 | 6.0 | 2.4 | 8.1 | 12.9 | 22.3 | 17.2 | 18.6 | 35.4 |
| 4 | 3.2 | 13.4 | 8.8 | 28.6 | 12.5 | 18.8 | 25.3 | 15.0 | 25.5 | 32.3 |
| 5 | 3.9 | 9.4 | 13.6 | 15.3 | 22.1 | 26.0 | 16.0 | 19.0 | 30.1 | 26.9 |
| 6 | 3.2 | 17.8 | 9.0 | 17.7 | 23.5 | 18.6 | 31.3 | 19.5 | 25.8 | 39.3 |
| 7 | 3.5 | 26.4 | 21.3 | 18.7 | 24.5 | 28.3 | 27.1 | 23.3 | 32.0 | 36.7 |
| 8 | 5.4 | 18.3 | 16.9 | 27.7 | 18.1 | 18.8 | 24.9 | 15.4 | 37.7 | 29.1 |
| 9 | 4.0 | 16.8 | 24.0 | 7.5 | 22.7 | 26.8 | 16.5 | 28.3 | 24.2 | 28.7 |
| 10 | 3.1 | 29.9 | 18.9 | 41.3 | 23.8 | 15.8 | 16.6 | 22.5 | 20.8 | 39.1 |

**Hidden bits, final pack (old in brackets)**

| cycle | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 0.0 (0.0) | 3.2 (7.8) | 3.4 (3.4) | 5.3 (3.7) | 6.9 (6.6) | 9.4 (9.4) | 13.9 (13.9) | 8.8 (10.5) | 8.8 (8.8) | 40.8 (40.8) |
| 2 | 3.0 (3.5) | 4.2 (5.9) | 4.9 (3.4) | 4.1 (7.6) | 8.8 (12.7) | 8.2 (8.2) | 9.2 (17.5) | 9.1 (14.8) | 19.4 (20.4) | 27.8 (27.8) |
| 3 | 4.5 (4.5) | 3.9 (5.4) | 6.0 (6.0) | 8.2 (2.4) | 8.1 (8.1) | 12.9 (12.9) | 22.8 (22.8) | 28.0 (17.2) | 19.7 (18.6) | 35.4 (35.4) |
| 4 | 3.2 (3.2) | 8.1 (13.4) | 12.2 (8.8) | 16.5 (28.6) | 12.5 (12.5) | 18.8 (18.8) | 25.3 (25.3) | 15.0 (15.0) | 25.5 (25.5) | 32.3 (32.3) |
| 5 | 3.9 (3.9) | 9.4 (9.4) | 21.9 (13.6) | 13.5 (15.3) | 19.6 (22.1) | 20.0 (28.9) | 16.0 (16.0) | 17.6 (19.0) | 30.5 (30.5) | 28.1 (28.1) |
| 6 | 3.0 (3.2) | 17.8 (17.8) | 9.0 (9.0) | 17.8 (17.8) | 23.8 (23.5) | 27.9 (18.6) | 33.8 (32.1) | 19.5 (19.5) | 25.8 (25.8) | 41.3 (41.3) |
| 7 | 3.5 (3.5) | 26.4 (26.4) | 16.2 (21.3) | 18.9 (18.9) | 18.4 (24.5) | 28.3 (28.3) | 28.4 (28.4) | 33.2 (23.8) | 25.1 (32.0) | 37.3 (37.2) |
| 8 | 3.5 (5.4) | 18.7 (18.7) | 18.8 (18.8) | 27.7 (27.7) | 22.9 (18.1) | 36.2 (20.0) | 27.1 (27.1) | 17.7 (15.6) | 30.3 (37.7) | 29.1 (29.1) |
| 9 | 4.0 (4.0) | 15.1 (17.4) | 22.7 (24.0) | 25.4 (7.5) | 22.7 (22.7) | 26.8 (26.8) | 16.5 (16.5) | 21.7 (28.9) | 38.7 (24.4) | 28.7 (28.7) |
| 10 | 5.0 (3.1) | 17.6 (29.9) | 23.8 (23.8) | 15.6 (41.3) | 23.8 (23.8) | 24.2 (15.8) | 16.6 (16.6) | 23.0 (23.0) | 19.7 (20.8) | 40.1 (40.1) |

**Winnable at 1/3 with H = 40, final pack (old in brackets)**

| cycle | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 100% (100%) | 100% (100%) | 93% (93%) | 50% (53%) | 59% (50%) | 98% (98%) | 98% (98%) | 33% (63%) | 66% (61%) | 0% (0%) |
| 2 | 100% (100%) | 100% (55%) | 58% (82%) | 97% (88%) | 98% (66%) | 100% (100%) | 30% (36%) | 20% (100%) | 70% (50%) | 5% (5%) |
| 3 | 100% (100%) | 58% (89%) | 34% (34%) | 92% (78%) | 100% (100%) | 88% (88%) | 19% (19%) | 52% (89%) | 10% (99%) | 11% (11%) |
| 4 | 100% (100%) | 100% (100%) | 97% (98%) | 98% (43%) | 34% (34%) | 100% (100%) | 39% (39%) | 100% (100%) | 20% (20%) | 97% (97%) |
| 5 | 100% (100%) | 100% (100%) | 41% (56%) | 39% (100%) | 41% (11%) | 100% (95%) | 13% (13%) | 0% (2%) | 25% (25%) | 16% (16%) |
| 6 | 100% (100%) | 67% (67%) | 97% (97%) | 45% (45%) | 7% (3%) | 28% (0%) | 3% (1%) | 56% (56%) | 42% (42%) | 11% (11%) |
| 7 | 100% (100%) | 95% (95%) | 100% (42%) | 45% (45%) | 65% (47%) | 17% (17%) | 0% (0%) | 89% (17%) | 98% (2%) | 23% (0%) |
| 8 | 100% (100%) | 72% (72%) | 30% (30%) | 53% (53%) | 44% (67%) | 17% (0%) | 11% (11%) | 59% (0%) | 36% (52%) | 52% (52%) |
| 9 | 100% (100%) | 97% (94%) | 45% (39%) | 64% (100%) | 16% (16%) | 31% (31%) | 25% (25%) | 17% (13%) | 88% (6%) | 55% (55%) |
| 10 | 100% (100%) | 72% (100%) | 67% (67%) | 64% (100%) | 89% (89%) | 16% (53%) | 3% (0%) | 28% (28%) | 0% (2%) | 0% (0%) |

**Rise within each cycle** (fair hidden bits, mean of the slots in each band):

| cycle | breather old → final | 2-5 old → final | 6-9 old → final | closer old → final | rises (final) |
|---|---|---|---|---|---|
| 1 | 0.0 → 0.0 | 5.4 → 4.7 | 10.7 → 10.2 | 40.8 → 40.8 | yes |
| 2 | 3.5 → 3.0 | 7.4 → 5.5 | 15.0 → 11.4 | 27.6 → 27.6 | yes |
| 3 | 4.5 → 4.5 | 5.5 → 6.5 | 17.8 → 20.7 | 35.4 → 35.4 | yes |
| 4 | 3.2 → 3.2 | 15.8 → 12.3 | 21.1 → 21.1 | 32.3 → 32.3 | yes |
| 5 | 3.9 → 3.9 | 15.1 → 16.1 | 22.8 → 20.9 | 26.9 → 26.9 | yes |
| 6 | 3.2 → 3.0 | 17.0 → 16.4 | 23.8 → 26.8 | 39.3 → 39.3 | yes |
| 7 | 3.5 → 3.5 | 22.7 → 19.9 | 27.7 → 28.4 | 36.7 → 28.8 | yes |
| 8 | 5.4 → 3.5 | 20.2 → 21.2 | 24.2 → 26.2 | 29.1 → 29.1 | yes |
| 9 | 4.0 → 4.0 | 17.8 → 21.5 | 23.9 → 25.3 | 28.7 → 28.7 | yes |
| 10 | 3.1 → 5.0 | 28.5 → 18.7 | 18.9 → 20.7 | 39.1 → 39.1 | yes |

**Slots much easier than their tier** (final fair hidden bits below half the tier median, or a replacement that lost over 40% of the hidden bits and some fair hidden bits): 

| slot | tier | tier median fair hidden | fair hidden old → final | hidden old → final | decision |
|---|---|---|---|---|---|
| L2 | 0 | 4.0 | 7.8 → 3.2 | 7.8 → 3.2 | replaced |
| L14 | 0 | 4.0 | 7.6 → 4.1 | 7.6 → 4.1 | replaced |
| L17 | 1 | 9.4 | 16.5 → 8.9 | 17.5 → 9.2 | replaced |
| L34 | 1 | 9.4 | 28.6 → 16.5 | 28.6 → 16.5 | replaced |
| L92 | 2 | 22.2 | 29.9 → 16.6 | 29.9 → 17.6 | replaced |
| L94 | 2 | 22.2 | 41.3 → 15.6 | 41.3 → 15.6 | replaced |
