# Combined selection: default + clued-squeeze tries in one pool, clued term in the ranking

*Terms.* A **squeeze** is a point where the dock is filling and the player must choose carefully (a stall in the generator's code). It is **clued** when the plates that decide the right choice are already visible on the carousel or the belt at decision time (a clued fork: the fork's deciding batch is empty). Earlier reports called these readable stalls / readable forks.

Run: `RebuildPreview --threads 6 --clued off,w3:d10 --clued-weight 0,5,15 --out levelgen/out/combined-select` (seed 7, 400 tries, x2 on tier >= 2, `--repair both`), slots 4, 10, 17, 19, 25, 28, 33, 37, 41, 43, 54, 60, 62, 67, 72, 80, 85, 89, 93, 99. Each slot's stage-0 pool holds the default construction's tries and the clued-squeeze construction's tries (`CluedDef` spec `w3:d10`, same director seeds); each construction gets its own prefilter (best 16 by fitness4) and its own finalists per repair variant (best 4, long chain), and all finalists compete for the slot under the selection order: accepted first, then fewest failed floors, then `dist5(fitness5) - weight x clued-fork share` (clued fork = deciding batch within carousel + belt, solver line). Weight 0 = plain fitness5. The three weights share one run (measurements cached per slot; only the ranking differs).

Old = the levels shipped now in `app/src/main/assets/levels.json` for those slots (the fitness5 campaign rebuild, default construction; L41 kept its pre-rebuild level), lines from `levelgen/out/official-lines.json`. Both measured the same way (long chain, K = 16, H = 40, order dependence 64 draws at 200k nodes), solver line.

Wall time 5085 s on 6 threads for all three weights (the machine was shared; load 7-14). For scale: the default-only rebuild preview took 2184 s on 8 threads, the clued-only preview 6250 s on 4 threads. Weights 5 and 15 add only stage-2 re-measurements of newly ranked finalists (0-80 s per slot).

Full per-weight reports (per-level tables, floors, best finalist per construction): `combined-select-rw0.md`, `combined-select-rw5.md`, `combined-select-rw15.md` (and their `-levels.json` / `-meta.json`).

## Pack summary (20 levels, solver line)

| metric | shipped now | combined, w 0 | combined, w 5 | combined, w 15 |
|---|---|---|---|---|
| levels with >= 1 unfair decision | 5 | 5 | 4 | 3 |
| unfair decisions (sum) | 5 | 6 | 4 | 3 |
| traps (sum) | 0 | 0 | 0 | 0 |
| fair share (mean) | 99% | 99% | 99% | 99% |
| blind bits (mean) | 2.9 | 3.7 | 3.0 | 2.8 |
| hidden bits (mean) | 21.7 | 23.7 | 23.8 | 23.3 |
| fair hidden bits (mean) | 21.2 | 23.0 | 23.3 | 22.8 |
| blind / hidden (mean) | 0.13 | 0.13 | 0.13 | 0.10 |
| winnable from start, uniform (mean) | 23% | 19% | 25% | 24% |
| winnable at 1/3, uniform (mean) | 43% | 55% | 55% | 50% |
| winnable at 1/3, H=40 (mean) | 52% | 63% | 63% | 59% |
| choices with w >= 2 (mean) | 76% | 74% | 74% | 76% |
| mean w (mean) | 3.29 | 3.30 | 3.30 | 3.45 |
| levels at <= 5% winnable from start (uniform) | 11 | 11 | 10 | 11 |
| clued forks, deciding batch on show (mean share) | 36% | 38% | 49% | 52% |
| floors passed (mean over lines) | 15 | 16 | 16 | 16 |

## Construction chosen per slot

| slot | w 0 | w 5 | w 15 |
|---|---|---|---|
| L4 | default | w3:d10 | default |
| L10 | default | default | default |
| L17 | w3:d10 | default | w3:d10 |
| L19 | w3:d10 | w3:d10 | w3:d10 |
| L25 | default | default | default |
| L28 | default | default | default |
| L33 | default | default | default |
| L37 | default | default | default |
| L41 | w3:d10 | w3:d10 | w3:d10 |
| L43 | w3:d10 | w3:d10 | w3:d10 |
| L54 | w3:d10 | w3:d10 | w3:d10 |
| L60 | default | default | default |
| L62 | default | default | default |
| L67 | default | w3:d10 | w3:d10 |
| L72 | default | default | default |
| L80 | default | default | default |
| L85 | w3:d10 | w3:d10 | w3:d10 |
| L89 | w3:d10 | w3:d10 | w3:d10 |
| L93 | default | default | default |
| L99 | w3:d10 | w3:d10 | w3:d10 |

Levels identical to the shipped ones: w 0: 12 slots (every slot where the default construction won: L4, 10, 25, 28, 33, 37, 60, 62, 67, 72, 80, 93), so the combined pool with weight 0 reproduces the shipped selection wherever the default tries win; w 15: 8 slots (L10, 25, 37, 60, 62, 72, 80, 93). At w 5 and 15 the weight also reorders the default construction's own finalists (L4, L28, L33, L43 pick a more clued default level).

## Findings

- Pooling the two constructions without a clued term (w 0) barely moves readability (36% -> 38%): fitness5 picks clued-squeeze levels in 8 slots for other reasons (fitness5, floors), and the pack gets more blind bits (2.9 -> 3.7) and one more unfair decision. It does raise winnable at 1/3 (52% -> 63% at H = 40) and fair hidden bits (21.2 -> 23.0).
- The clued term is what lifts the clued-fork share: 49% at w 5, 52% at w 15, with unfair decisions down to 4 / 3 (from 5), blind bits back to 2.9 / 2.8, blind/hidden 0.10 at w 15, floors passed 16 (shipped 15), traps 0 throughout.
- Costs at w 15: winnable at 1/3 falls back a little from the w 0 / 5 level (59% H = 40, 50% uniform; still above shipped 52% / 43%), fair hidden bits 22.8 (shipped 21.2). Winnable from start stays at 23-25%; levels at <= 5% winnable from start unchanged (10-11).
- The term ranks only after the floors (accepted, then fewest fails), so it never trades a floor for readability; within the same floor outcome a weight of 15 is worth up to 15 points of fitness5 distance (to the recipe's target, or of fitness5 on MAX4 recipes) for a fully clued line.

## Recommendation

Use the combined pool with weight 15 (or 5 if keeping closer to fitness5 matters more): it improves every fairness measure on these 20 slots against the shipped levels (unfair 5 -> 3, clued forks 36% -> 52%, blind 2.9 -> 2.8, floors 15 -> 16) without losing winnability. To ship it, patch the slots through `OfficialPackF5 --clued off,w3:d10 --clued-weight 15 --only ...` (options added, defaults unchanged); its keep-unless-better rule (`decide`) does not yet look at readability, so it would keep most shipped levels unless readability is added there too. Cost: roughly 2x stage 0 time per slot (two constructions).
