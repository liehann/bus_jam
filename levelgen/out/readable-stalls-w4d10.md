# Rebuild preview (fitness5, tier floors, belt dial, deciding-batch repair)

Construction: readable stalls (`ReadableDef` spec `w4:d10:e0.2`, ring and belt of each slot). Old = `levelgen/out/rebuild-preview-levels.json`.

Replacements for 20 official slots, same recipe / tier / ring schedule and director seeds as `OfficialPack` (seed 7, 400 tries, x2 on tier >= 2), selected by fitness5 with the tier floors (`Fitness5Config`): stage 0 = every try evaluated as OfficialPack does (fitness4 at 40 seats); stage 1 = the best 16 by fitness4 at the slot's ring, short chain (x1) on the intended and solver line, with and without the queue repair (`--repair off`); stage 2 = the best 4 per variant re-measured with the long chain (x16). Belt (`queuePreview`): 10 on tier 0 and in cycles 1-2 (12 / 16 on tiers 2 / 3 there), else 14 / 16 / 20 on tiers 1 / 2 / 3. Commit 8ce5435-dirty, wall time 5925 s on 4 threads.

Old = the shipped level (`app/src/main/assets/levels.json`, belt 10), new = the selected replacement. Both measured the same way: long chain (burn-in 256, every 64), K = 16, H = 40, order dependence over 64 draws at 200000 nodes (undecided draws count as not winnable). Per-level table: the solver line (the fairness report's view); the floors table: the mean over the intended and the solver line (what selection checks).

## Per level, solver line (old → new)

| slot | recipe | tier | ring | belt | moves | unfair | traps | fair share | blind bits | winnable uniform start / 1/3 | winnable 1/3, H=40 | w>=2 share, mean w | hidden bits | fair hidden bits | solve nodes | readable forks (lag mean) | old @ new belt: unfair / traps / blind |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | juggle + just-enough cascade | 0 | 40 → 40 | 10 → 10 | 35 → 35 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 1.6 | 100% / 100% → 100% / 100% | 100% → 100% | 94%, 4.85 → 91%, 4.03 | 2.0 → 7.0 | 2.0 → 7.0 | 399 → 454 | 0% (29.0) → 80% (4.0) | same belt |
| L10 | hard: juggle, scarce key -> heralded chain climax | 3 | 40 → 40 | 16 → 16 | 64 → 64 | 0 → 0 | 0 → 0 | 100% → 100% | 2.8 → 3.0 | 0% / 0% → 0% / 0% | 0% → 0% | 46%, 1.71 → 76%, 2.20 | 39.9 → 23.8 | 39.9 → 23.8 | 1801 → 3459 | 32% (23.2) → 15% (22.7) | same belt |
| L17 | juggle + steal + keystone | 1 | 40 → 40 | 10 → 10 | 48 → 48 | 1 → 0 | 0 → 0 | 95% → 100% | 3.9 → 1.8 | 0% / 67% → 5% / 2% | 56% → 6% | 79%, 2.98 → 85%, 3.87 | 21.6 → 12.7 | 20.2 → 12.7 | 5960 → 2470 | 21% (33.5) → 23% (23.0) | same belt |
| L19 | juggle, mirror spine + steal | 2 | 40 → 40 | 12 → 12 | 51 → 51 | 0 → 0 | 0 → 0 | 100% → 100% | 6.7 → 6.2 | 27% / 88% → 31% / 100% | 77% → 100% | 84%, 4.34 → 64%, 2.40 | 21.1 → 27.9 | 21.1 → 27.9 | 18668 → 43113 | 20% (24.6) → 35% (16.0) | same belt |
| L25 | juggle + steal + keystone | 1 | 38 → 38 | 14 → 14 | 48 → 48 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 4.0 | 17% / 100% → 19% / 98% | 100% → 97% | 85%, 3.43 → 89%, 4.38 | 8.1 → 15.7 | 8.1 → 15.7 | 1348 → 1638 | 50% (12.0) → 42% (26.1) | same belt |
| L28 | juggle, gutter halves + heralded climax | 2 | 36 → 36 | 16 → 16 | 51 → 51 | 0 → 1 | 0 → 0 | 100% → 93% | 2.1 → 4.0 | 0% / 100% → 0% / 6% | 89% → 88% | 88%, 3.69 → 74%, 3.12 | 17.2 → 17.4 | 17.2 → 16.5 | 1608 → 4215 | 44% (17.0) → 36% (21.2) | same belt |
| L33 | juggle + interrupted run + cascade | 1 | 36 → 36 | 14 → 14 | 41 → 41 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 0.0 | 77% / 98% → 0% / 100% | 98% → 100% | 90%, 4.83 → 75%, 3.08 | 8.8 → 12.5 | 8.8 → 12.5 | 2671 → 9873 | 40% (10.7) → 57% (38.3) | same belt |
| L37 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 51 → 51 | 0 → 0 | 0 → 0 | 100% → 100% | 3.0 → 9.8 | 100% / 28% → 0% / 31% | 39% → 19% | 78%, 4.80 → 66%, 2.58 | 25.3 → 21.9 | 25.3 → 20.3 | 2416 → 217721 | 57% (29.3) → 31% (25.3) | same belt |
| L41 | breather juggle | 0 | 40 → 40 | 10 → 10 | 24 → 24 | 0 → 0 | 0 → 0 | 100% → 100% | 1.9 → 1.0 | 100% / 100% → 8% / 100% | 100% → 100% | 78%, 2.04 → 39%, 1.44 | 6.2 → 9.2 | 6.2 → 9.2 | 10067 → 217 | 20% (33.6) → 50% (15.4) | same belt |
| L43 | juggle + steal + keystone | 1 | 34 → 34 | 14 → 14 | 48 → 48 | 0 → 0 | 0 → 0 | 100% → 100% | 1.5 → 2.9 | 0% / 22% → 3% / 97% | 56% → 41% | 85%, 2.64 → 83%, 2.67 | 13.6 → 21.9 | 13.6 → 21.9 | 2303 → 1465 | 36% (14.9) → 50% (19.9) | same belt |
| L54 | juggle, mirror spine + steal | 2 | 40 → 32 | 16 → 16 | 51 → 51 | 1 → 0 | 0 → 0 | 95% → 100% | 0.9 → 1.7 | 16% / 3% → 94% / 94% | 16% → 95% | 66%, 2.64 → 83%, 3.51 | 27.3 → 16.8 | 26.9 → 16.8 | 5112 → 21008 | 48% (24.9) → 23% (15.0) | same belt |
| L60 | dense pack, gutter halves, heralded | 3 | 36 → 40 | 20 → 20 | 68 → 68 | 1 → 0 | 0 → 0 | 97% → 100% | 4.8 → 2.4 | 0% / 0% → 0% / 0% | 3% → 0% | 67%, 2.68 → 55%, 2.67 | 42.4 → 19.4 | 40.4 → 19.4 | 26372 → 1788 | 53% (13.6) → 81% (10.8) | same belt |
| L62 | juggle + rolling + heralded climax, mirror | 1 | 30 → 30 | 14 → 14 | 48 → 48 | 0 → 0 | 0 → 0 | 100% → 100% | 4.1 → 8.2 | 23% / 100% → 70% / 38% | 95% → 66% | 72%, 2.81 → 77%, 2.39 | 26.4 → 31.2 | 26.4 → 31.2 | 2085 → 960 | 26% (17.7) → 43% (16.8) | same belt |
| L67 | juggle + chained runs | 2 | 36 → 28 | 16 → 16 | 63 → 63 | 1 → 0 | 0 → 0 | 97% → 100% | 4.8 → 2.4 | 0% / 0% → 0% / 0% | 0% → 39% | 65%, 2.60 → 69%, 2.79 | 28.4 → 30.9 | 27.1 → 30.9 | 3395 → 72452 | 19% (18.0) → 48% (17.1) | same belt |
| L72 | juggle, mirror spine + steal | 2 | 34 → 34 | 16 → 16 | 51 → 51 | 0 → 1 | 0 → 0 | 100% → 94% | 5.2 → 1.0 | 8% / 45% → 0% / 2% | 72% → 0% | 90%, 3.54 → 50%, 2.10 | 20.0 → 31.5 | 20.0 → 30.5 | 1311 → 26660 | 35% (17.1) → 39% (21.5) | same belt |
| L80 | dense pack, mirror spine | 3 | 40 → 36 | 20 → 20 | 70 → 70 | 0 → 0 | 0 → 0 | 100% → 100% | 0.0 → 2.4 | 0% / 5% → 0% / 95% | 42% → 94% | 69%, 4.35 → 62%, 3.63 | 28.9 → 33.9 | 28.9 → 33.9 | 2506 → 50382 | 54% (10.0) → 71% (14.1) | same belt |
| L85 | juggle, squatter dig + steal | 2 | 38 → 38 | 16 → 16 | 62 → 62 | 0 → 1 | 0 → 0 | 100% → 96% | 4.2 → 6.3 | 0% / 0% → 0% / 0% | 16% → 0% | 75%, 3.28 → 78%, 3.22 | 22.9 → 26.2 | 22.9 → 25.9 | 24542 → 6058 | 41% (22.7) → 26% (20.8) | same belt |
| L89 | juggle, rolling + squatter | 2 | 40 → 32 | 16 → 16 | 65 → 65 | 0 → 1 | 0 → 0 | 100% → 97% | 4.4 → 8.5 | 0% / 2% → 0% / 16% | 6% → 88% | 63%, 2.13 → 85%, 4.10 | 29.5 → 38.7 | 29.5 → 36.5 | 19229 → 7599 | 35% (17.6) → 29% (23.8) | same belt |
| L93 | juggle, gutter halves + heralded climax | 2 | 34 → 34 | 16 → 16 | 51 → 51 | 1 → 0 | 0 → 0 | 92% → 100% | 6.0 → 0.6 | 0% / 6% → 0% / 8% | 67% → 69% | 76%, 3.64 → 68%, 3.22 | 23.6 → 25.2 | 18.5 → 25.2 | 1315627 → 3204 | 26% (25.7) → 47% (17.1) | same belt |
| L99 | juggle, rolling + squatter | 2 | 40 → 28 | 16 → 16 | 65 → 65 | 0 → 0 | 0 → 0 | 100% → 100% | 0.4 → 0.4 | 0% / 0% → 0% / 0% | 0% → 0% | 75%, 2.97 → 78%, 3.58 | 19.9 → 19.7 | 19.9 → 19.7 | 1995 → 2809 | 28% (18.1) → 62% (28.4) | same belt |

## Selection and floors (mean over intended + solver line)

| slot | tier | fitness5 old → new | fitness4 (new, 40 seats) | floors new | floors old | pool (ok) | prefiltered / finals | repaired | best without repair: unfair / f5 / floors | best with repair: unfair / f5 / floors | time stage 0 / 1 / 2 (s) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| L4 | 0 | (bonus 7.1) → 15.5 | 9.1 | pass | pass | 101 (8) | 16 / 4 | 0 | 0.0 / 15.5 / pass | - | 215 / 72 / 32 |
| L10 | 3 | (bonus 22.4) → 52.9 | 51.9 | order 0.00 | order 0.00 | 186 (42) | 16 / 4 | 0 | 0.0 / 52.9 / order 0.00 | - | 947 / 445 / 891 |
| L17 | 1 | (bonus 7.5) → 26.0 | 21.6 | order 0.20 | unfair 1.5 | 97 (26) | 16 / 4 | 0 | 0.0 / 26.0 / order 0.20 | - | 632 / 199 / 117 |
| L19 | 2 | (bonus 12.9) → 33.8 | 31.3 | pass | pass | 74 (11) | 16 / 4 | 0 | 0.0 / 33.8 / pass | - | 521 / 238 / 343 |
| L25 | 1 | (bonus 11.7) → 31.1 | 25.9 | blindratio 0.26 | pass | 119 (25) | 16 / 4 | 0 | 0.0 / 31.1 / blindratio 0.26 | - | 456 / 162 / 234 |
| L28 | 2 | (bonus 15.2) → 32.9 | 26.6 | pass | pass | 128 (8) | 16 / 4 | 0 | 1.0 / 32.9 / pass | - | 761 / 331 / 561 |
| L33 | 1 | (bonus 11.0) → 34.8 | 29.5 | pass | pass | 75 (10) | 16 / 4 | 0 | 0.0 / 34.8 / pass | - | 211 / 192 / 214 |
| L37 | 2 | (bonus 17.4) → 28.0 | 27.2 | blindratio 0.44 | pass | 106 (6) | 16 / 4 | 0 | 0.0 / 28.0 / blindratio 0.44 | - | 853 / 298 / 351 |
| L41 | 0 | (bonus 7.9) → 15.5 | 10.9 | pass | pass | 116 (24) | 16 / 4 | 0 | 0.0 / 15.5 / pass | - | 31 / 3 / 1 |
| L43 | 1 | (bonus 12.8) → 34.2 | 26.6 | pass | pass | 121 (22) | 16 / 4 | 0 | 0.0 / 34.2 / pass | - | 440 / 238 / 350 |
| L54 | 2 | (bonus 12.1) → 34.7 | 28.7 | pass | pass | 79 (13) | 16 / 4 | 0 | 0.0 / 34.7 / pass | - | 404 / 170 / 127 |
| L60 | 3 | (bonus 21.9) → 43.7 | 42.0 | opening,fairhidden 18.2,order 0.00 | pass | 5 (0) | 5 / 4 | 0 | 0.0 / 43.7 / opening,fairhidden 18.2,order 0.00 | - | 117 / 94 / 328 |
| L62 | 1 | (bonus 19.6) → 32.6 | 28.3 | blindratio 0.26 | pass | 39 (6) | 16 / 4 | 0 | 0.0 / 32.6 / blindratio 0.26 | - | 129 / 124 / 163 |
| L67 | 2 | (bonus 9.6) → 36.5 | 24.7 | openbits 0.03 | order 0.00 | 56 (3) | 16 / 4 | 0 | 0.0 / 36.5 / openbits 0.03 | - | 178 / 216 / 548 |
| L72 | 2 | (bonus 11.2) → 42.9 | 32.9 | order 0.00 | pass | 79 (6) | 16 / 4 | 0 | 1.0 / 42.9 / order 0.00 | - | 187 / 114 / 249 |
| L80 | 3 | (bonus 21.1) → 42.2 | 27.4 | pass | pass | 69 (9) | 16 / 4 | 0 | 0.0 / 42.2 / pass | - | 200 / 240 / 664 |
| L85 | 2 | (bonus 15.0) → 36.8 | 33.5 | order 0.00 | pass | 181 (29) | 16 / 4 | 0 | 1.0 / 36.8 / order 0.00 | - | 710 / 270 / 600 |
| L89 | 2 | (bonus 8.4) → 39.5 | 34.1 | pass | order 0.06 | 119 (24) | 16 / 4 | 0 | 1.0 / 39.5 / pass | - | 689 / 321 / 844 |
| L93 | 2 | (bonus 11.4) → 42.3 | 30.5 | pass | pass | 114 (8) | 16 / 4 | 0 | 0.0 / 42.3 / pass | - | 433 / 155 / 76 |
| L99 | 2 | (bonus 10.6) → 40.0 | 36.9 | order 0.00 | order 0.02 | 116 (42) | 16 / 4 | 0 | 0.0 / 40.0 / order 0.00 | - | 731 / 515 / 487 |

## Pack summary (20 levels, solver line)

| metric | old | new |
|---|---|---|
| levels with >= 1 unfair decision | 5 | 4 |
| unfair decisions (sum) | 5 | 4 |
| traps (sum) | 0 | 0 |
| fair share (mean) | 99% | 99% |
| blind bits (mean) | 2.8 | 3.4 |
| hidden bits (mean) | 21.7 | 22.2 |
| fair hidden bits (mean) | 21.2 | 21.9 |
| blind / hidden (mean) | 0.13 | 0.15 |
| winnable from start, uniform (mean) | 23% | 16% |
| winnable at 1/3, uniform (mean) | 43% | 44% |
| winnable at 1/3, H=40 (mean) | 52% | 55% |
| choices with w >= 2 (mean) | 76% | 72% |
| mean w (mean) | 3.30 | 3.05 |
| levels at <= 5% winnable from start (uniform) | 11 | 14 |
| readable forks, deciding batch on show (mean share) | 34% | 44% |
| floors passed (mean over lines) | 15 | 10 |

## Floors failed by finalists (count over all slots)

order 35, blindratio 21, fairhidden 17, unfair 11, deeptraps 9, meanw 1, multi 1
