# Rebuild preview (fitness5, tier floors, belt dial, deciding-batch repair)

Replacements for 20 official slots, same recipe / tier / ring schedule and director seeds as `OfficialPack` (seed 7, 400 tries, x2 on tier >= 2), selected by fitness5 with the tier floors (`Fitness5Config`): stage 0 = every try evaluated as OfficialPack does (fitness4 at 40 seats); stage 1 = the best 16 by fitness4 at the slot's ring, short chain (x1) on the intended and solver line, with and without the queue repair (`--repair both`); stage 2 = the best 4 per variant re-measured with the long chain (x16). Belt (`queuePreview`): 10 on tier 0 and in cycles 1-2 (12 / 16 on tiers 2 / 3 there), else 14 / 16 / 20 on tiers 1 / 2 / 3. Commit 8ce5435-dirty, wall time 2184 s on 8 threads.

Old = the shipped level (`app/src/main/assets/levels.json`, belt 10), new = the selected replacement. Both measured the same way: long chain (burn-in 256, every 64), K = 16, H = 40, order dependence over 64 draws at 200000 nodes (undecided draws count as not winnable). Per-level table: the solver line (the fairness report's view); the floors table: the mean over the intended and the solver line (what selection checks).

## Per level, solver line (old → new)

| slot | recipe | tier | ring | belt | moves | unfair | traps | fair share | blind bits | winnable uniform start / 1/3 | winnable 1/3, H=40 | w>=2 share, mean w | hidden bits | fair hidden bits | solve nodes | readable forks (lag mean) | old @ new belt: unfair / traps / blind |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | juggle + just-enough cascade | 0 | 40 → 40 | 10 → 10 | 35 → 35 | 1 → 0 | 1 → 0 | 80% → 100% | 4.2 → 0.0 | 100% / 91% → 100% / 100% | 94% → 100% | 73%, 3.36 → 94%, 4.85 | 9.0 → 2.0 | 7.6 → 2.0 | 775 → 399 | 20% (21.5) → 0% (29.0) | same belt |
| L10 | hard: juggle, scarce key -> heralded chain climax | 3 | 40 → 40 | 10 → 16 | 64 → 64 | 5 → 0 | 3 → 0 | 84% → 100% | 14.4 → 2.8 | 0% / 0% → 0% / 0% | 0% → 0% | 55%, 2.47 → 46%, 1.71 | 41.9 → 39.9 | 34.7 → 39.9 | 16803 → 1801 | 16% (30.6) → 32% (23.2) rep 60 | 4 / 4 / 10.7 |
| L17 | juggle + steal + keystone | 1 | 40 → 40 | 10 → 10 | 48 → 48 | 0 → 1 | 0 → 0 | 100% → 95% | 2.8 → 3.9 | 5% / 55% → 0% / 67% | 53% → 56% | 82%, 4.09 → 79%, 2.98 | 15.6 → 21.6 | 15.6 → 20.2 | 328323 → 5960 | 13% (31.5) → 21% (33.5) rep 60 | same belt |
| L19 | juggle, mirror spine + steal | 2 | 40 → 40 | 10 → 12 | 51 → 51 | 0 → 0 | 0 → 0 | 100% → 100% | 2.0 → 6.7 | 0% / 3% → 27% / 88% | 0% → 77% | 67%, 2.08 → 84%, 4.34 | 21.0 → 21.1 | 21.0 → 21.1 | 1085 → 18668 | 21% (25.9) → 20% (24.6) | 0 / 0 / 1.0 |
| L25 | juggle + steal + keystone | 1 | 38 → 38 | 10 → 14 | 48 → 48 | 7 → 0 | 4 → 0 | 63% → 100% | 18.4 → 0.0 | 34% / 95% → 17% / 100% | 25% → 100% | 74%, 3.80 → 85%, 3.43 | 26.7 → 8.1 | 13.4 → 8.1 | 18734 → 1348 | 11% (37.8) → 50% (12.0) rep 60 | 2 / 1 / 18.6 |
| L28 | juggle, gutter halves + heralded climax | 2 | 36 → 36 | 10 → 16 | 51 → 51 | 5 → 0 | 4 → 0 | 67% → 100% | 10.0 → 2.1 | 0% / 14% → 0% / 100% | 55% → 89% | 69%, 3.12 → 88%, 3.69 | 22.9 → 17.2 | 13.0 → 17.2 | 405595 → 1608 | 13% (16.8) → 44% (17.0) | 3 / 3 / 8.6 |
| L33 | juggle + interrupted run + cascade | 1 | 36 → 36 | 10 → 14 | 41 → 41 | 1 → 0 | 0 → 0 | 90% → 100% | 3.5 → 0.0 | 64% / 2% → 77% / 98% | 3% → 98% | 79%, 4.05 → 90%, 4.83 | 11.3 → 8.8 | 9.5 → 8.8 | 208376 → 2671 | 18% (11.3) → 40% (10.7) | 2 / 1 / 3.0 |
| L37 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 10 → 16 | 51 → 51 | 2 → 0 | 2 → 0 | 89% → 100% | 12.6 → 3.0 | 8% / 14% → 100% / 28% | 0% → 39% | 68%, 2.96 → 78%, 4.80 | 31.9 → 25.3 | 22.5 → 25.3 | 980154 → 2416 | 10% (31.4) → 57% (29.3) | 1 / 1 / 11.2 |
| L41 | breather juggle | 0 | 40 → 40 | 10 → 10 | 24 → 24 | 0 → 0 | 0 → 0 | 100% → 100% | 3.2 → 1.9 | 86% / 100% → 100% / 100% | 100% → 100% | 68%, 1.95 → 78%, 2.04 | 7.8 → 6.2 | 7.8 → 6.2 | 974 → 10067 | 60% (10.3) → 20% (33.6) | same belt |
| L43 | juggle + steal + keystone | 1 | 34 → 34 | 10 → 14 | 48 → 48 | 6 → 0 | 2 → 0 | 60% → 100% | 10.1 → 1.5 | 27% / 56% → 0% / 22% | 28% → 56% | 66%, 2.72 → 85%, 2.64 | 21.4 → 13.6 | 13.5 → 13.6 | 22221 → 2303 | 7% (23.1) → 36% (14.9) | 1 / 0 / 9.3 |
| L54 | juggle, mirror spine + steal | 2 | 32 → 40 | 10 → 16 | 51 → 51 | 4 → 1 | 4 → 0 | 79% → 95% | 15.1 → 0.9 | 58% / 83% → 16% / 3% | 45% → 16% | 80%, 3.49 → 66%, 2.64 | 31.0 → 27.3 | 24.6 → 26.9 | 58344 → 5112 | 16% (13.8) → 48% (24.9) rep 60 | 4 / 4 / 13.3 |
| L60 | dense pack, gutter halves, heralded | 3 | 40 → 36 | 10 → 20 | 68 → 68 | 4 → 1 | 3 → 0 | 85% → 97% | 3.9 → 4.8 | 0% / 89% → 0% / 0% | 80% → 3% | 79%, 4.72 → 67%, 2.68 | 26.0 → 42.4 | 24.0 → 40.4 | 159631 → 26372 | 22% (16.1) → 53% (13.6) rep 60 | 2 / 2 / 3.9 |
| L62 | juggle + rolling + heralded climax, mirror | 1 | 30 → 30 | 10 → 14 | 48 → 48 | 2 → 0 | 2 → 0 | 89% → 100% | 6.1 → 4.1 | 3% / 63% → 23% / 100% | 36% → 95% | 80%, 2.80 → 72%, 2.81 | 18.8 → 26.4 | 17.1 → 26.4 | 63967 → 2085 | 39% (20.5) → 26% (17.7) | 4 / 4 / 5.0 |
| L67 | juggle + chained runs | 2 | 32 → 36 | 10 → 16 | 63 → 63 | 6 → 1 | 1 → 0 | 76% → 97% | 15.8 → 4.8 | 0% / 0% → 0% / 0% | 0% → 0% | 73%, 2.85 → 65%, 2.60 | 32.9 → 28.4 | 24.8 → 27.1 | 128395 → 3395 | 4% (30.3) → 19% (18.0) | 5 / 1 / 12.0 |
| L72 | juggle, mirror spine + steal | 2 | 30 → 34 | 10 → 16 | 51 → 51 | 8 → 0 | 4 → 0 | 68% → 100% | 17.6 → 5.2 | 0% / 6% → 8% / 45% | 16% → 72% | 56%, 1.81 → 90%, 3.54 | 41.0 → 20.0 | 27.1 → 20.0 | 47532 → 1311 | 16% (30.8) → 35% (17.1) rep 60 | 7 / 4 / 16.0 |
| L80 | dense pack, mirror spine | 3 | 28 → 40 | 10 → 20 | 70 → 70 | 6 → 0 | 2 → 0 | 83% → 100% | 10.3 → 0.0 | 0% / 0% → 0% / 5% | 0% → 42% | 45%, 1.70 → 69%, 4.35 | 49.8 → 28.9 | 40.3 → 28.9 | 23222 → 2506 | 46% (17.4) → 54% (10.0) rep 60 | 0 / 0 / 6.1 |
| L85 | juggle, squatter dig + steal | 2 | 38 → 38 | 10 → 16 | 62 → 62 | 3 → 0 | 2 → 0 | 91% → 100% | 5.2 → 4.2 | 0% / 0% → 0% / 0% | 0% → 16% | 72%, 2.53 → 75%, 3.28 | 44.6 → 22.9 | 40.4 → 22.9 | 24156 → 24542 | 15% (24.7) → 41% (22.7) rep 60 | 1 / 0 / 3.2 |
| L89 | juggle, rolling + squatter | 2 | 40 → 40 | 10 → 16 | 65 → 65 | 2 → 0 | 1 → 0 | 91% → 100% | 3.6 → 4.4 | 0% / 0% → 0% / 2% | 0% → 6% | 78%, 3.23 → 63%, 2.13 | 28.4 → 29.5 | 24.8 → 29.5 | 1564 → 19229 | 30% (16.2) → 35% (17.6) | 2 / 0 / 1.4 |
| L93 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 10 → 16 | 51 → 51 | 1 → 1 | 0 → 0 | 96% → 92% | 0.7 → 6.0 | 0% / 30% → 0% / 6% | 23% → 67% | 68%, 2.90 → 76%, 3.64 | 27.9 → 23.6 | 27.2 → 18.5 | 12025 → 1315627 | 17% (21.3) → 26% (25.7) rep 60 | 0 / 0 / 0.0 |
| L99 | juggle, rolling + squatter | 2 | 40 → 40 | 10 → 16 | 65 → 65 | 1 → 0 | 1 → 0 | 96% → 100% | 2.9 → 0.4 | 0% / 13% → 0% / 0% | 13% → 0% | 94%, 4.17 → 75%, 2.97 | 19.5 → 19.9 | 18.9 → 19.9 | 2685 → 1995 | 20% (27.6) → 28% (18.1) | 0 / 0 / 2.3 |

## Selection and floors (mean over intended + solver line)

| slot | tier | fitness5 old → new | fitness4 (new, 40 seats) | floors new | floors old | pool (ok) | prefiltered / finals | repaired | best without repair: unfair / f5 / floors | best with repair: unfair / f5 / floors | time stage 0 / 1 / 2 (s) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | 0 | (bonus 5.4) → 16.1 | 10.7 | pass | unfair 0.5,deeptraps 0.5,blindratio 0.36 | 80 (18) | 28 / 8 | 12 | 0.0 / 16.1 / pass | 0.0 / 19.3 / pass | 28 / 4 / 2 |
| L10 | 3 | (bonus 9.4) → 72.1 | 74.2 | order 0.00 | unfair 3.0,deeptraps 1.5,order 0.00 | 212 (120) | 32 / 8 | 16 | 0.0 / 71.3 / order 0.00 | 0.0 / 72.1 / order 0.00 | 272 / 246 / 569 |
| L17 | 1 | (bonus 8.9) → 27.4 | 27.6 | unfair 1.5 | unfair 0.5,blindratio 0.25 | 87 (66) | 32 / 8 | 16 | 2.0 / 22.8 / unfair 2.0 | 1.5 / 27.4 / unfair 1.5 | 107 / 82 / 81 |
| L19 | 2 | (bonus 8.3) → 39.6 | 37.5 | pass | order 0.03 | 82 (24) | 32 / 8 | 16 | 0.0 / 39.6 / pass | 0.0 / 31.4 / blindratio 0.41 | 91 / 133 / 264 |
| L25 | 1 | (bonus -9.0) → 29.1 | 23.0 | pass | unfair 5.5,deeptraps 2.0,blindratio 0.52,order 0.20 | 106 (47) | 32 / 8 | 16 | 0.0 / 29.8 / pass | 0.0 / 29.1 / pass | 101 / 123 / 141 |
| L28 | 2 | (bonus -11.8) → 33.1 | 24.3 | pass | unfair 5.0,deeptraps 4.0,blindratio 0.43,fairhidden 13.0 | 178 (16) | 32 / 8 | 16 | 0.0 / 33.1 / pass | 0.0 / 30.7 / pass | 206 / 150 / 189 |
| L33 | 1 | (bonus 4.9) → 25.0 | 17.2 | pass | unfair 1.0,blindratio 0.31,order 0.03 | 87 (24) | 32 / 8 | 16 | 0.0 / 25.0 / pass | 0.0 / 25.0 / pass | 61 / 77 / 16 |
| L37 | 2 | (bonus 1.1) → 33.1 | 22.5 | pass | unfair 2.0,deeptraps 3.0,blindratio 0.39,order 0.00 | 188 (17) | 32 / 8 | 16 | 0.0 / 33.1 / pass | 0.0 / 30.5 / pass | 218 / 123 / 96 |
| L41 | 0 | (bonus 7.3) → 16.6 | 10.4 | pass | pass | 130 (18) | 25 / 8 | 9 | 0.0 / 16.6 / pass | 0.0 / 19.2 / blindratio 0.43 | 10 / 2 / 3 |
| L43 | 1 | (bonus -14.4) → 34.2 | 29.2 | pass | unfair 6.0,deeptraps 2.0,blindratio 0.47 | 86 (53) | 32 / 8 | 16 | 0.0 / 34.2 / pass | 0.0 / 27.9 / blindratio 0.42 | 92 / 92 / 153 |
| L54 | 2 | (bonus -6.5) → 34.1 | 28.7 | pass | unfair 4.0,deeptraps 4.0,blindratio 0.49 | 63 (25) | 32 / 8 | 16 | 0.5 / 39.9 / pass | 0.5 / 34.1 / pass | 72 / 207 / 269 |
| L60 | 3 | (bonus 6.1) → 60.4 | 55.2 | pass | unfair 4.5,deeptraps 1.5 | 28 (4) | 30 / 8 | 14 | 1.0 / 57.8 / pass | 1.0 / 60.4 / pass | 86 / 349 / 982 |
| L62 | 1 | (bonus 4.3) → 38.7 | 31.2 | pass | unfair 2.0,deeptraps 3.0,blindratio 0.33 | 47 (25) | 32 / 8 | 16 | 0.0 / 38.7 / pass | 0.0 / 39.2 / pass | 54 / 130 / 148 |
| L67 | 2 | (bonus -7.3) → 37.4 | 39.5 | order 0.00 | unfair 6.0,deeptraps 1.0,blindratio 0.48,order 0.00 | 85 (16) | 32 / 8 | 16 | 1.0 / 37.4 / order 0.00 | 0.0 / 33.2 / order 0.00 | 136 / 248 / 585 |
| L72 | 2 | (bonus -13.4) → 31.7 | 28.7 | pass | unfair 8.0,deeptraps 4.0,blindratio 0.43 | 61 (22) | 32 / 8 | 16 | 0.0 / 32.9 / pass | 0.5 / 31.7 / pass | 75 / 157 / 214 |
| L80 | 3 | (bonus -0.2) → 63.3 | 59.1 | pass | unfair 6.0,deeptraps 2.0,multi 0.45,order 0.00 | 123 (36) | 32 / 8 | 16 | 0.0 / 50.8 / pass | 0.0 / 63.3 / pass | 225 / 366 / 879 |
| L85 | 2 | (bonus 5.5) → 40.7 | 34.8 | pass | unfair 3.0,deeptraps 4.0,order 0.00 | 206 (135) | 32 / 8 | 16 | 0.0 / 44.7 / pass | 0.0 / 40.7 / pass | 378 / 337 / 509 |
| L89 | 2 | (bonus 6.6) → 40.0 | 42.0 | order 0.06 | unfair 1.5,order 0.00 | 239 (151) | 32 / 8 | 16 | 0.5 / 40.0 / order 0.06 | 0.0 / 39.7 / order 0.00 | 495 / 392 / 600 |
| L93 | 2 | (bonus 20.1) → 35.4 | 37.8 | pass | pass | 173 (10) | 32 / 8 | 16 | 0.0 / 37.2 / pass | 1.0 / 35.4 / pass | 204 / 176 / 298 |
| L99 | 2 | (bonus -9.9) → 40.4 | 38.7 | order 0.02 | unfair 4.5,deeptraps 3.0,order 0.08 | 244 (158) | 32 / 8 | 16 | 0.0 / 40.4 / order 0.02 | 0.5 / 39.2 / order 0.00 | 462 / 256 / 411 |

## Pack summary (20 levels, solver line)

| metric | old | new |
|---|---|---|
| levels with >= 1 unfair decision | 17 | 5 |
| unfair decisions (sum) | 64 | 5 |
| traps (sum) | 36 | 0 |
| fair share (mean) | 84% | 99% |
| blind bits (mean) | 8.1 | 2.8 |
| hidden bits (mean) | 26.5 | 21.7 |
| fair hidden bits (mean) | 21.4 | 21.2 |
| blind / hidden (mean) | 0.32 | 0.13 |
| winnable from start, uniform (mean) | 19% | 23% |
| winnable at 1/3, uniform (mean) | 36% | 43% |
| winnable at 1/3, H=40 (mean) | 29% | 52% |
| choices with w >= 2 (mean) | 71% | 76% |
| mean w (mean) | 3.04 | 3.30 |
| levels at <= 5% winnable from start (uniform) | 13 | 11 |
| readable forks, deciding batch on show (mean share) | 21% | 34% |
| floors passed (mean over lines) | 2 | 15 |

## Effect of the deciding-batch repair (same pools, best finalist of each variant, solver line, 20 slots)

| metric | without repair | with repair |
|---|---|---|
| unfair decisions (mean) | 0.20 | 0.20 |
| traps (mean) | 0.00 | 0.00 |
| blind bits (mean) | 2.9 | 3.3 |
| fair hidden bits (mean) | 21.9 | 21.6 |
| winnable at 1/3, H=40 (mean) | 48% | 39% |
| readable forks, solver line (mean share) | 31% | 39% |
| fitness5 (mean) | 37.1 | 36.5 |
| floors passed | 15 | 12 |
| chosen as the slot's level | 11 | 9 |

## Floors failed by finalists (count over all slots)

order 51, unfair 30, deeptraps 29, blindratio 25, fairhidden 14
