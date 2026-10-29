# Rebuild preview (fitness5, tier floors, belt dial, deciding-batch repair)

Constructions competing in one pool: `off,w3:d10` (`off` = default, else a `ReadableDef` spec at the ring and belt of each slot); readability weight 0.0 (ranking after the floors: dist5(fitness5) - weight x readable-fork share, solver line). Old = `app/src/main/assets/levels.json`.

Replacements for 20 official slots, same recipe / tier / ring schedule and director seeds as `OfficialPack` (seed 7, 400 tries, x2 on tier >= 2), selected by fitness5 with the tier floors (`Fitness5Config`): stage 0 = every try evaluated as OfficialPack does (fitness4 at 40 seats); stage 1 = the best 16 by fitness4 at the slot's ring, short chain (x1) on the intended and solver line, with and without the queue repair (`--repair both`); stage 2 = the best 4 per variant re-measured with the long chain (x16). Belt (`queuePreview`): 10 on tier 0 and in cycles 1-2 (12 / 16 on tiers 2 / 3 there), else 14 / 16 / 20 on tiers 1 / 2 / 3. Commit 8ce5435-dirty, wall time 5085 s on 6 threads.

Old = the shipped level (`app/src/main/assets/levels.json`, belt 10), new = the selected replacement. Both measured the same way: long chain (burn-in 256, every 64), K = 16, H = 40, order dependence over 64 draws at 200000 nodes (undecided draws count as not winnable). Per-level table: the solver line (the fairness report's view); the floors table: the mean over the intended and the solver line (what selection checks).

## Per level, solver line (old → new)

| slot | recipe | tier | ring | belt | moves | unfair | traps | fair share | blind bits | winnable uniform start / 1/3 | winnable 1/3, H=40 | w>=2 share, mean w | hidden bits | fair hidden bits | solve nodes | readable forks (lag mean) | old @ new belt: unfair / traps / blind |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | juggle + just-enough cascade | 0 | 40 → 40 | 10 → 10 | 35 → 35 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 0.0 | 100% / 100% → 100% / 100% | 100% → 100% | 94%, 4.85 → 94%, 4.85 | 2.0 → 2.0 | 2.0 → 2.0 | 399 → 399 | 0% (29.0) → 0% (29.0) | same belt |
| L10 | hard: juggle, scarce key -> heralded chain climax | 3 | 40 → 40 | 16 → 16 | 64 → 64 | 0 → 0 | 0 → 0 | 100% → 100% | 2.8 → 2.8 | 0% / 0% → 0% / 0% | 0% → 0% | 46%, 1.71 → 46%, 1.71 | 39.9 → 39.9 | 39.9 → 39.9 | 1801 → 1801 | 32% (23.2) → 32% (23.2) rep 60 | same belt |
| L17 | juggle + steal + keystone | 1 | 40 → 40 | 10 → 10 | 48 → 48 | 1 → 2 | 0 → 0 | 95% → 91% | 3.9 → 14.8 | 0% / 67% → 0% / 98% | 56% → 97% | 79%, 2.98 → 55%, 2.36 | 21.6 → 32.9 | 20.2 → 29.2 | 5960 → 4855 | 21% (33.5) → 18% (27.4) rep 50 | same belt |
| L19 | juggle, mirror spine + steal | 2 | 40 → 40 | 12 → 12 | 51 → 51 | 0 → 0 | 0 → 0 | 100% → 100% | 6.7 → 6.2 | 27% / 88% → 31% / 100% | 77% → 100% | 84%, 4.34 → 64%, 2.40 | 21.1 → 27.9 | 21.1 → 27.9 | 18668 → 43113 | 20% (24.6) → 35% (16.0) | same belt |
| L25 | juggle + steal + keystone | 1 | 38 → 38 | 14 → 14 | 48 → 48 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 0.0 | 17% / 100% → 17% / 100% | 100% → 100% | 85%, 3.43 → 85%, 3.43 | 8.1 → 8.1 | 8.1 → 8.1 | 1348 → 1348 | 50% (12.0) → 50% (12.0) rep 60 | same belt |
| L28 | juggle, gutter halves + heralded climax | 2 | 36 → 36 | 16 → 16 | 51 → 51 | 0 → 0 | 0 → 0 | 100% → 100% | 2.1 → 2.1 | 0% / 100% → 0% / 100% | 89% → 89% | 88%, 3.69 → 88%, 3.69 | 17.2 → 17.2 | 17.2 → 17.2 | 1608 → 1608 | 44% (17.0) → 44% (17.0) | same belt |
| L33 | juggle + interrupted run + cascade | 1 | 36 → 36 | 14 → 14 | 41 → 41 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 0.0 | 77% / 98% → 77% / 98% | 98% → 98% | 90%, 4.83 → 90%, 4.83 | 8.8 → 8.8 | 8.8 → 8.8 | 2671 → 2671 | 40% (10.7) → 40% (10.7) | same belt |
| L37 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 51 → 51 | 0 → 0 | 0 → 0 | 100% → 100% | 3.0 → 3.0 | 100% / 28% → 100% / 28% | 39% → 39% | 78%, 4.80 → 78%, 4.80 | 25.3 → 25.3 | 25.3 → 25.3 | 2416 → 2416 | 57% (29.3) → 57% (29.3) | same belt |
| L41 | breather juggle | 0 | 40 → 40 | 10 → 10 | 24 → 24 | 0 → 0 | 0 → 0 | 100% → 100% | 3.2 → 1.0 | 86% / 100% → 8% / 100% | 100% → 100% | 68%, 1.95 → 39%, 1.44 | 7.8 → 9.2 | 7.8 → 9.2 | 974 → 217 | 60% (10.3) → 50% (15.4) | same belt |
| L43 | juggle + steal + keystone | 1 | 34 → 34 | 14 → 14 | 48 → 48 | 0 → 0 | 0 → 0 | 100% → 100% | 1.5 → 2.9 | 0% / 22% → 3% / 97% | 56% → 41% | 85%, 2.64 → 83%, 2.67 | 13.6 → 21.9 | 13.6 → 21.9 | 2303 → 1465 | 36% (14.9) → 50% (19.9) | same belt |
| L54 | juggle, mirror spine + steal | 2 | 40 → 36 | 16 → 16 | 51 → 51 | 1 → 0 | 0 → 0 | 95% → 100% | 0.9 → 0.3 | 16% / 3% → 6% / 97% | 16% → 100% | 66%, 2.64 → 76%, 3.62 | 27.3 → 22.5 | 26.9 → 22.5 | 5112 → 9981 | 48% (24.9) → 43% (11.5) rep 60 | same belt |
| L60 | dense pack, gutter halves, heralded | 3 | 36 → 36 | 20 → 20 | 68 → 68 | 1 → 1 | 0 → 0 | 97% → 97% | 4.8 → 4.8 | 0% / 0% → 0% / 0% | 3% → 3% | 67%, 2.68 → 67%, 2.68 | 42.4 → 42.4 | 40.4 → 40.4 | 26372 → 26372 | 53% (13.6) → 53% (13.6) rep 60 | same belt |
| L62 | juggle + rolling + heralded climax, mirror | 1 | 30 → 30 | 14 → 14 | 48 → 48 | 0 → 0 | 0 → 0 | 100% → 100% | 4.1 → 4.1 | 23% / 100% → 23% / 100% | 95% → 95% | 72%, 2.81 → 72%, 2.81 | 26.4 → 26.4 | 26.4 → 26.4 | 2085 → 2085 | 26% (17.7) → 26% (17.7) | same belt |
| L67 | juggle + chained runs | 2 | 36 → 36 | 16 → 16 | 63 → 63 | 1 → 1 | 0 → 0 | 97% → 97% | 4.8 → 4.8 | 0% / 0% → 0% / 0% | 0% → 0% | 65%, 2.60 → 65%, 2.60 | 28.4 → 28.4 | 27.1 → 27.1 | 3395 → 3395 | 19% (18.0) → 19% (18.0) | same belt |
| L72 | juggle, mirror spine + steal | 2 | 34 → 34 | 16 → 16 | 51 → 51 | 0 → 0 | 0 → 0 | 100% → 100% | 5.2 → 5.2 | 8% / 45% → 8% / 45% | 72% → 72% | 90%, 3.54 → 90%, 3.54 | 20.0 → 20.0 | 20.0 → 20.0 | 1311 → 1311 | 35% (17.1) → 35% (17.1) rep 60 | same belt |
| L80 | dense pack, mirror spine | 3 | 40 → 40 | 20 → 20 | 70 → 70 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 0.0 | 0% / 5% → 0% / 5% | 42% → 42% | 69%, 4.35 → 69%, 4.35 | 28.9 → 28.9 | 28.9 → 28.9 | 2506 → 2506 | 54% (10.0) → 54% (10.0) rep 60 | same belt |
| L85 | juggle, squatter dig + steal | 2 | 38 → 34 | 16 → 16 | 62 → 62 | 0 → 0 | 0 → 0 | 100% → 100% | 4.2 → 7.2 | 0% / 0% → 0% / 16% | 16% → 30% | 75%, 3.28 → 71%, 2.90 | 22.9 → 30.0 | 22.9 → 30.0 | 24542 → 40428 | 41% (22.7) → 30% (17.7) | same belt |
| L89 | juggle, rolling + squatter | 2 | 40 → 32 | 16 → 16 | 65 → 65 | 0 → 1 | 0 → 0 | 100% → 97% | 4.4 → 8.5 | 0% / 2% → 0% / 16% | 6% → 88% | 63%, 2.13 → 85%, 4.10 | 29.5 → 38.7 | 29.5 → 36.5 | 19229 → 7599 | 35% (17.6) → 29% (23.8) | same belt |
| L93 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 51 → 51 | 1 → 1 | 0 → 0 | 92% → 92% | 6.0 → 6.0 | 0% / 6% → 0% / 6% | 67% → 67% | 76%, 3.64 → 76%, 3.64 | 23.6 → 23.6 | 18.5 → 18.5 | 1315627 → 1315627 | 26% (25.7) → 26% (25.7) rep 60 | same belt |
| L99 | juggle, rolling + squatter | 2 | 40 → 28 | 16 → 16 | 65 → 65 | 0 → 0 | 0 → 0 | 100% → 100% | 0.4 → 0.4 | 0% / 0% → 0% / 0% | 0% → 0% | 75%, 2.97 → 78%, 3.58 | 19.9 → 19.7 | 19.9 → 19.7 | 1995 → 2809 | 28% (18.1) → 62% (28.4) | same belt |

## Selection and floors (mean over intended + solver line)

| slot | tier | fitness5 old → new | fitness4 (new, 40 seats) | floors new | floors old | pool (ok) | prefiltered / finals | repaired | best without repair: unfair / f5 / floors | best with repair: unfair / f5 / floors | time stage 0 / 1 / 2 (s) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | 0 | (bonus 7.1) → 16.1 | 10.7 | pass | pass | 180 (26) | 55 / 16 | 23 | 0.0 / 16.1 / pass | 0.0 / 19.3 / pass | 41 / 21 / 19 |
| L10 | 3 | (bonus 22.4) → 72.1 | 74.2 | order 0.00 | order 0.00 | 394 (163) | 64 / 16 | 32 | 0.0 / 71.3 / order 0.00 | 0.0 / 72.1 / order 0.00 | 356 / 365 / 834 |
| L17 | 1 | (bonus 7.5) → 26.0 | 30.5 | unfair 1.5 | unfair 1.5 | 180 (91) | 64 / 16 | 32 | 0.0 / 24.9 / blindratio 0.40 | 1.5 / 26.0 / unfair 1.5 | 154 / 163 / 159 |
| L19 | 2 | (bonus 12.9) → 33.8 | 31.3 | pass | pass | 157 (35) | 64 / 16 | 32 | 0.0 / 33.8 / pass | 0.0 / 31.4 / blindratio 0.41 | 127 / 194 / 387 |
| L25 | 1 | (bonus 11.7) → 29.1 | 23.0 | pass | pass | 227 (74) | 64 / 16 | 32 | 0.0 / 29.8 / pass | 0.0 / 29.1 / pass | 150 / 185 / 231 |
| L28 | 2 | (bonus 15.2) → 33.1 | 24.3 | pass | pass | 301 (23) | 64 / 16 | 32 | 0.0 / 33.1 / pass | 0.0 / 30.7 / pass | 285 / 273 / 391 |
| L33 | 1 | (bonus 11.0) → 25.0 | 17.2 | pass | pass | 160 (32) | 64 / 16 | 32 | 0.0 / 25.0 / pass | 0.0 / 25.0 / pass | 84 / 115 / 55 |
| L37 | 2 | (bonus 17.4) → 33.1 | 22.5 | pass | pass | 292 (23) | 64 / 16 | 32 | 0.0 / 33.1 / pass | 0.0 / 30.5 / pass | 279 / 214 / 239 |
| L41 | 0 | (bonus 7.3) → 15.5 | 10.9 | pass | pass | 250 (43) | 43 / 14 | 11 | 0.0 / 15.5 / pass | 0.0 / 19.2 / blindratio 0.43 | 16 / 2 / 3 |
| L43 | 1 | (bonus 12.8) → 34.2 | 26.6 | pass | pass | 184 (79) | 64 / 16 | 32 | 0.0 / 34.2 / pass | 0.0 / 37.0 / pass | 132 / 194 / 376 |
| L54 | 2 | (bonus 12.1) → 31.4 | 22.0 | pass | pass | 141 (36) | 63 / 16 | 31 | 0.0 / 35.2 / pass | 0.0 / 31.4 / pass | 123 / 301 / 356 |
| L60 | 3 | (bonus 21.9) → 60.4 | 55.2 | pass | pass | 34 (4) | 42 / 16 | 20 | 1.0 / 57.8 / pass | 1.0 / 60.4 / pass | 100 / 376 / 1185 |
| L62 | 1 | (bonus 19.6) → 38.7 | 31.2 | pass | pass | 86 (32) | 64 / 16 | 32 | 0.0 / 38.7 / pass | 0.0 / 39.2 / pass | 75 / 214 / 267 |
| L67 | 2 | (bonus 9.6) → 37.4 | 39.5 | order 0.00 | order 0.00 | 142 (20) | 64 / 16 | 32 | 1.0 / 37.4 / order 0.00 | 0.0 / 33.2 / order 0.00 | 181 / 396 / 930 |
| L72 | 2 | (bonus 11.2) → 31.7 | 28.7 | pass | pass | 135 (25) | 64 / 16 | 32 | 0.0 / 32.9 / pass | 0.5 / 31.7 / pass | 126 / 263 / 460 |
| L80 | 3 | (bonus 21.1) → 63.3 | 59.1 | pass | pass | 191 (45) | 63 / 16 | 31 | 0.0 / 50.8 / pass | 0.0 / 63.3 / pass | 269 / 494 / 1356 |
| L85 | 2 | (bonus 15.0) → 34.7 | 26.1 | pass | pass | 401 (161) | 63 / 16 | 31 | 0.0 / 34.7 / pass | 0.0 / 40.7 / pass | 536 / 423 / 775 |
| L89 | 2 | (bonus 8.4) → 39.5 | 34.1 | pass | order 0.06 | 361 (176) | 64 / 16 | 32 | 1.0 / 39.5 / pass | 0.0 / 39.7 / order 0.00 | 610 / 608 / 1177 |
| L93 | 2 | (bonus 11.4) → 35.4 | 37.8 | pass | pass | 288 (16) | 64 / 16 | 32 | 0.0 / 37.2 / pass | 1.0 / 35.4 / pass | 286 / 258 / 400 |
| L99 | 2 | (bonus 10.6) → 40.0 | 36.9 | order 0.00 | order 0.02 | 359 (203) | 64 / 16 | 32 | 0.0 / 40.0 / order 0.00 | 0.0 / 40.0 / order 0.01 | 607 / 488 / 1093 |

## Pack summary (20 levels, solver line)

| metric | old | new |
|---|---|---|
| levels with >= 1 unfair decision | 5 | 5 |
| unfair decisions (sum) | 5 | 6 |
| traps (sum) | 0 | 0 |
| fair share (mean) | 99% | 99% |
| blind bits (mean) | 2.9 | 3.7 |
| hidden bits (mean) | 21.7 | 23.7 |
| fair hidden bits (mean) | 21.2 | 23.0 |
| blind / hidden (mean) | 0.13 | 0.13 |
| winnable from start, uniform (mean) | 23% | 19% |
| winnable at 1/3, uniform (mean) | 43% | 55% |
| winnable at 1/3, H=40 (mean) | 52% | 63% |
| choices with w >= 2 (mean) | 76% | 74% |
| mean w (mean) | 3.29 | 3.30 |
| levels at <= 5% winnable from start (uniform) | 11 | 11 |
| readable forks, deciding batch on show (mean share) | 36% | 38% |
| floors passed (mean over lines) | 15 | 16 |
| construction chosen | | default 12, w3:d10 8 |

## Best finalist per construction (solver line; slot: construction unfair / readable / f5 / floors)

- L4: default 0 / 0% / 16.1 / pass; w3:d10 0 / 80% / 15.5 / pass -> default
- L10: default 0 / 32% / 72.1 / order 0.00; w3:d10 0 / 24% / 58.6 / order 0.00 -> default
- L17: default 1 / 21% / 27.4 / unfair 1.5; w3:d10 2 / 18% / 26.0 / unfair 1.5 -> w3:d10
- L19: default 0 / 20% / 39.6 / pass; w3:d10 0 / 35% / 33.8 / pass -> w3:d10
- L25: default 0 / 50% / 29.1 / pass; w3:d10 0 / 38% / 34.5 / pass -> default
- L28: default 0 / 44% / 33.1 / pass; w3:d10 1 / 36% / 32.9 / pass -> default
- L33: default 0 / 40% / 25.0 / pass; w3:d10 0 / 57% / 34.3 / pass -> default
- L37: default 0 / 57% / 33.1 / pass; w3:d10 1 / 22% / 30.9 / blindratio 0.30 -> default
- L41: default 0 / 20% / 16.6 / pass; w3:d10 0 / 50% / 15.5 / pass -> w3:d10
- L43: default 0 / 36% / 34.2 / pass; w3:d10 0 / 50% / 34.2 / pass -> w3:d10
- L54: default 1 / 48% / 34.1 / pass; w3:d10 0 / 43% / 31.4 / pass -> w3:d10
- L60: default 1 / 53% / 60.4 / pass; w3:d10 0 / 81% / 43.7 / opening,fairhidden 18.2,order 0.00 -> default
- L62: default 0 / 26% / 38.7 / pass; w3:d10 0 / 62% / 32.4 / order 0.04 -> default
- L67: default 1 / 19% / 37.4 / order 0.00; w3:d10 0 / 48% / 36.5 / openbits 0.03 -> default
- L72: default 0 / 35% / 31.7 / pass; w3:d10 0 / 59% / 38.0 / order 0.00 -> default
- L80: default 0 / 54% / 63.3 / pass; w3:d10 0 / 71% / 42.2 / pass -> default
- L85: default 0 / 41% / 40.7 / pass; w3:d10 0 / 30% / 34.7 / pass -> w3:d10
- L89: default 0 / 35% / 40.0 / order 0.06; w3:d10 1 / 29% / 39.5 / pass -> w3:d10
- L93: default 1 / 26% / 35.4 / pass; w3:d10 0 / 47% / 42.3 / pass -> default
- L99: default 0 / 28% / 40.4 / order 0.02; w3:d10 0 / 62% / 40.0 / order 0.00 -> w3:d10

## Effect of the deciding-batch repair (same pools, best finalist of each variant, solver line, 20 slots)

| metric | without repair | with repair |
|---|---|---|
| unfair decisions (mean) | 0.15 | 0.20 |
| traps (mean) | 0.00 | 0.00 |
| blind bits (mean) | 3.5 | 3.4 |
| fair hidden bits (mean) | 24.4 | 21.7 |
| winnable at 1/3, H=40 (mean) | 54% | 45% |
| readable forks, solver line (mean share) | 35% | 40% |
| fitness5 (mean) | 36.0 | 36.8 |
| floors passed | 16 | 13 |
| chosen as the slot's level | 12 | 8 |

## Floors failed by finalists (count over all slots)

order 112, blindratio 71, unfair 62, deeptraps 56, fairhidden 48, multi 2
