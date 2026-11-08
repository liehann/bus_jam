# Fairness of the official campaign

Playtest feedback (2026-10): levels are too hard because what's on show isn't enough to choose the right move, and
there is effectively one correct sequence: a wrong move is found out ten moves later. The current fitness
(`HybridGenerator.fitness4`) rewards *hidden bits*: decisions whose losing alternatives don't lose at once. This report
measures how many of those decisions can be made from what the player sees. Code: `core/.../generator/reverse/Fairness.kt`
(metrics, tests in `FairnessTest.kt`) and `levelgen/.../reverse/FairnessReport.kt` (this report's tables; `QualityReport
--fair 10` adds the main columns to the quality TSV). Raw rows: `levelgen/out/fairness.tsv`; generated tables (per
level, by cycle / slot / tier): `levelgen/out/fairness-tables.md`, repeated in the appendix.

All numbers are for the current `app/src/main/assets/levels.json` (100 levels), along each level's solver line
(`Solver.solve`, 5M nodes, as in `QualityReport`). `levelgen/out/official-lines.json` is not in the repo, so the
intended lines weren't used.

## 1. What the player sees, and what's hidden

The player sees the board, the dock, the carousel and the next **10** elements of the line. The belt shows at most
`RingField.QUEUE_MAX = Limits.DEFAULT_QUEUE_PREVIEW = 10` plates. Levels can now set their own belt
(`Level.queuePreview`, default 10), and `FairnessConfig.shown` defaults to it (null = the level's belt; set it to
override). The per-colour totals of everything not yet seen follow from the balance rule, so the only hidden
information is the **order** of the queue beyond the belt: the *hidden tail*. Hidden-colour containers (mechanic
`hidden`) also hide their colour until their cover leaves. `Fairness` doesn't model that yet (it treats the board as
fully visible), so on such levels the fair share is an upper bound. The campaign has none.

**Belt length in `Quality` (was `Quality.SHOWN_QUEUE = 20`).** `Quality.measure` / `Quality.of` used a fixed belt of
20 for the chain-visibility metrics. It was set at 08:27 on 2026-10-11, while the app still showed 20; the belt went
to 10 at 08:53 that day. They now take `shown`, which defaults to `level.queuePreview` (10 for every official level).
`Quality.SHOWN_QUEUE_CALIBRATION = 20` reproduces the old numbers. The constant affects only `chainVisibleAt` and
what follows from it: `chainLead`, `digShareBeforeVisible`, `blindDeep` (blind share), `climaxHidden`, the
*readable* axis, and through that `fitness4` and the herald floors (`minLead`, `maxBlindShare`). It doesn't affect
`hidden`, `deep` or `bits`, or any fairness number in this report. The tables below were measured with `Quality.of`
at 20, and none of their columns change at 10.
Effect on the official pack (100 levels, solver lines, belt 20 vs 10):

- `chainVisibleAt` / `chainLead` change on 17 levels; mean lead goes from 16.0 to 15.0.
- Blind share, readable, `digShareBeforeVisible` and `climaxHidden` change on 12 levels. Mean readable goes from
  0.58 to 0.56. The mean quality bonus of `fitness4` goes from 12.09 to 12.01, with a largest drop of 2.6.
- Herald floor (lead >= 8 and blind share <= 0.6): 33 of 42 herald levels pass at 20 and 32 at 10. The one that
  flips is L90 (lead 21 -> 3, blind share 0.32 -> 0.90). At belt 10 its chain is on show only 3 moves before it
  floods.

The generator's quality section (`HybridFitnessConfig.minLead = 8`, `leadFull = 12`, `maxBlindShare = 0.6`) was set
from playtest labels measured at belt 20. From now on, OfficialPack / HybridPreview4 runs (including `--only`
rebuilds) select with belt-10 numbers, which are slightly stricter on herald levels. The shipped pack is unchanged.

## 2. Definitions

**Decision.** A state on the line with at least two legal moves and a losing move that doesn't lose at once
(horizon h >= 2). These are exactly the states that carry hidden bits today: 1,929 of them over the pack.

**Plausible hidden orders.** The player knows the level can be won and, on the winning line, hasn't lost yet. So the
orders that count at a state are re-orderings of the hidden tail under which the state is still winnable:

- **Horizon H = 40.** The next 40 hidden elements are drawn uniformly, without replacement, from the whole hidden
  multiset; the rest keep their real relative order. H = whole tail gives a uniform permutation.
- **Random draws.** Up to 48 independent draws; an order is kept when the bounded solver (50k nodes) proves the state
  winnable.
- **Swap chain.** Random draws are almost never winnable here (section 3), so the rest of the K = 16 samples come
  from a Metropolis chain started at the real order. A proposal swaps a hidden element within the horizon with any
  other hidden element of another colour, and is kept when the state stays provably winnable. Report default:
  burn-in 256 proposals, then a sample every 64 ("x16"). `FairnessConfig`'s own default is the cheap x1 chain
  (16 / 4).

**Win probability p(m).** The share of the kept orders, plus the real one, under which move m wins. Each move is
solved under the same orders, with a 20k-node budget; an exhausted budget counts as a win, as in `Quality`.

**Fair.** The robust set is R = {m : p(m) >= 0.9}. A decision is fair when R is not empty and every move in R really
wins. It is unfair in two ways:

- **No robust move.** Nothing on show points to a good move.
- **Trap.** A move that looks safe from everything on show (p >= 0.9) loses with the real order.

Decisions with fewer than 4 winnable random draws and fewer than 4 accepted chain swaps are **undetermined**: the
hidden order is rigid there. They are counted apart (26 in the pack) and not judged.

**Options.** For each choice (b >= 2): w is the number of winning moves and wR the number of robust winning moves.
Reported as the share of choices with w >= 2 and the mean w (also wR).

**Loss horizon, blind vs visible.** For each losing alternative, h is the moves until every continuation has ended
(`ReverseAnalysis.lossHorizon`, cap 8). A loser is **visible** when it loses under >= 90% of the plausible orders
(its loss follows from what's on show, however deep), and **blind** otherwise. Deep means h > 3.

- **Deep blind loser:** the bad case.
- **Deep visible loser:** hard but fair.
- **Blind bits** = Σ log2((w + blind losers) / w): the hidden bits left for a player who reads everything on show
  perfectly.

**Order dependence.** The share of fully uniform, unconditioned orders of the hidden tail (64 draws, 200k nodes)
under which the state is winnable, at the start and at 1/3 and 2/3 of the line. Also reported with H = 40. Undecided
draws (4.6%) count as not winnable, so these are lower bounds.

**Release windows (exploratory).** 24 winning lines sampled by a randomised walk (random legal order, take the first
move the bounded solver doesn't prove lost). For each container: the earliest and latest move index at which it was
released.

- **Window width:** latest minus earliest.
- **Narrow:** width <= 2.
- **Narrow and blind:** narrow, and on every sampled line released at least 5 moves before an element of its colour
  is on show.
- **Concurrency:** the mean over move indices of the number of containers whose window contains it. This is a proxy
  for the antichain width.

## 3. The campaign at belt 10

- **62 of 100 levels have at least one unfair decision.** 173 of 1,929 decisions (9%) are unfair: 69 with no robust
  move and 104 traps. Mean fair share is 90%. Distribution of unfair decisions per level: 0 in 38 levels, 1 in 18,
  2 in 14, 3 in 12, 4-8 in 18. Worst: L72 (8), L25 (7), L43, L67 and L80 (6 each).
- **In 27 levels the worst decision has a losing move that looks better than every winning one.**
  - L10, step 11 (the clearest case): the two real winners have p = 0.12 and 0.06.
  - Container 46 has p = 0.94 (it wins under 16 of 16 plausible orders) but loses 6 moves later with the real one.
  - That is the "wrong move discovered much later" of the feedback.
- **These are lower bounds** (section 5): longer chains find more unfair decisions.
- **31% of hidden bits can't be read from what's on show.** Mean hidden bits 23.0 per level against 6.9 blind bits;
  20.2 hidden bits sit at fair decisions.
  - Deep losers: 1,206 blind against 1,821 visible.
  - Blind bits track unfair decisions (Spearman 0.61 over the pack); raw hidden bits track them less (0.44).
- **Options are common, not scarce.** 73% of choices have w >= 2 (66% with wR >= 2); mean w 3.0, mean wR 2.8. The
  closers are lowest (58% with w >= 2, mean w 2.4).
- **The levels only work with the designed order.** This is the "one correct sequence" complaint in another form.
  Under a uniformly random hidden order, the level is winnable from the start in a mean 22% of draws (median 0%).
  - 63 levels are at <= 5% and 55 at 0%. At 1/3 of the line: mean 40% (median 14%), 44 levels at <= 5%. At 2/3:
    mean 78%.
  - It falls with the cycle, from 47% in cycle 1 to 6% in cycle 9 and 4% in cycle 10. By slot: breathers 65%,
    ladder 2-5 25%, ladder 6-9 13%, closers 0% (9% at 1/3, 33% at 2/3).
  - Re-drawing only the next 40 hidden elements gives nearly the same picture (start mean 28%, 53 levels at
    <= 5%). The near future beyond the belt decides it, not the far tail.
  - Spearman with hidden bits: -0.69.
- **Release windows.** Mean width 10.3 moves (22% of the line), 11.6 narrow containers per level, mean concurrency
  11.3.
  - Narrow-and-blind releases (pinned in time and released well before their colour shows) are rare: 40 over the
    pack, in 28 levels. The closers average 1.0 and have the narrowest windows (width 7.6, 24.6 narrow per level).
  - The window metrics hardly correlate with unfair decisions (narrow count 0.13), so they describe how forced the
    sequence is, not whether it's readable. Keep them exploratory.

By tier the unfair decisions are not concentrated on the hard levels: tier 1 (ladder) levels have the most per level
(2.4, fair share 81%). Tier 0 has the fewest (0.4).

## 4. Belt length (the 20 levels with the most hidden bits)

Same lines and settings, x16 chain, belt 10 vs 20 vs 30 (per-level table in the appendix):

| belt | unfair decisions | traps | fair share | levels without unfair decisions | deep blind losers | blind bits (mean) |
|---|---|---|---|---|---|---|
| 10 (app) | 51 | 31 | 91% | 5/20 | 361 | 10.5 |
| 20 | 42 | 29 | 92% | 5/20 | 312 | 9.1 |
| 30 | 32 | 20 | 94% | 5/20 | 251 | 7.2 |

- **Each extra 10 plates removes about a fifth of the unfair decisions** (-18% at 20, -37% at 30) and of the deep
  blind losers (-14%, -30%). Blind bits fall from 10.5 to 7.2.
- **A longer belt doesn't clear these levels.** None more is free of unfair decisions at 30. Their unfairness sits
  beyond 30 elements of look-ahead: the traps are about elements arriving 5-10 moves (30-60 elements) later.
- **The x1 chain and random-only sampling show the same trend.** x1: 21 / 15 / 8 unfair, 10 / 11 / 14 clean levels.
  Random-only, judged decisions: 18 / 12 / 11.
- A belt of 20 is a cheap partial fix. A belt of 40 would need a layout change and is untested.

## 5. Sampling bias (the swap chain starts at the real order)

The chain starts at the real order, so short chains give orders close to it. Under those orders the real line's
moves keep winning and p(m) is inflated for them. The variant study on the same 20 levels quantifies this:

| sampling | unfair | traps | levels without unfair decisions | undetermined | random-sample share |
|---|---|---|---|---|---|
| random draws only (up to 96), no chain | 18 | 14 | 10/20 | **404** | 100% |
| hybrid, chain x1 (burn-in 16, every 4) | 21 | 15 | 10/20 | 23 | 30% |
| chain only x1 | 23 | 20 | 10/20 | 24 | 0% |
| chain only x4 (64 / 16) | 40 | 31 | 6/20 | 22 | 0% |
| hybrid, chain x16 (256 / 64), **report default** | 51 | 31 | 5/20 | 22 | 30% |
| chain only x16 | 61 | 41 | 3/20 | 22 | 0% |
| chain only x64 (1024 / 256) | 77 | 46 | 3/20 | 22 | 0% |

- **Random draws alone can't judge these levels.** 404 of their 653 decisions get fewer than 4 winnable orders out
  of 96, and on the decisions they can judge (mostly late, short-tail ones) they agree with the hybrid.
- **The chain has not converged at x64.** Unfair decisions keep rising (x1 21 → x4 40 → x16 61 → x64 77,
  chain-only), but the gain per 4x halves. Its limit is the uniform distribution over winnable orders reachable by
  swaps, the exact conditional of section 2.
- **So every unfair count in this report is a lower bound,** probably by a factor up to about 1.5 at x16.
- **Per state, how many samples were random draws** is in the per-level table ("random samples") and in the per-state
  dump (`FairnessReport` writes `fairness-states.tsv`; not kept in `out/`, 2.8 MB). Across the pack, 30% of kept
  orders were independent random draws and 70% came from the chain. Early in a level it's almost all chain; late,
  with a short tail, almost all random draws.
- **Horizon sensitivity (x1):** H = 20 gives 17 unfair decisions, H = 40 gives 21, H = 80 gives 29. A longer
  re-drawn near future finds more blind decisions.

## 6. Calibration against the playtest labels (x16, belt 10)

The two liked levels still in the repo, and the disliked ones:

| level | label | hidden | blind bits | fair | unfair | w>=2 | mean w | winnable, uniform: start / 1/3 / 2/3 |
|---|---|---|---|---|---|---|---|---|
| Hybrid preview 2 L19 | + stumped twice | 23.7 | 4.7 | 89% | 2 | 79% | 6.8 | 0% (64 undecided) / 8% / 100% |
| Hybrid preview 3 L10 | + "figured out the chain too late" | 59.6 | **24.7** | 93% | 3 | 50% | 1.6 | 0% / 0% / 19% |
| Reverse preview 1 L19 | - easy | 4.2 | 0.7 | 100% | 0 | 85% | 4.7 | 42% / 58% / 98% |
| Reverse preview 1 L20 | - easy | 12.2 | 8.4 | 73% | 3 | 85% | 5.7 | 97% / 100% / 100% |
| Motif preview 1 L2 | - filled up immediately | 11.8 | 0.4 | 80% | 1 | 96% | 13.5 | 100% / 100% / 100% |
| Motif preview 1 L3 | - (same family) | 13.6 | 1.1 | 100% | 0 | 97% | 13.5 | 100% / 100% / 100% |

(Motif preview 2 L18 and the ring-size test are no longer in the repo; HP3 L10 is from the untracked
`levelgen/out/hybrid-preview-3-levels.json` of the main checkout.)

- **"Figured out the chain too late" is the level with the most blind bits (24.7 of 59.6).** It matches the new
  complaint.
- **The "easy" levels sit at the other extreme:** order-independent (97-100% winnable under any order) and full of
  options (mean w 13.5, 96% w >= 2). Options and order robustness need a band, not a maximum: too many winning moves
  is the "filled up immediately" failure.
- **Unfair counts don't separate liked from disliked** (2-3 vs 0-3); one or two traps were tolerated.
- Six labels give gaps, not thresholds; the floors below are judgement within those gaps.

## 7. Proposal: fitness5 and floors

Implemented (2026-10-17) as `Fitness5` / `Fitness5Config` (core `generator/reverse/Fitness5.kt`), the deciding-batch metric and queue repair as `Readability.kt`, and the selection in `levelgen/.../reverse/Fitness5Select.kt`; first results in `levelgen/out/rebuild-preview.md`.

Keep difficulty, but take it from decisions that can be read. Per line (averaged over the intended and solver lines
as now), computed with `Fairness.measure` (belt 10, H = 40, x16 chain):

```
info5   = min(1, fairHidden / hiddenCap)              // hidden bits at fair decisions only (hiddenCap 40)
blind   = min(1, blindBits / 10)
options = clamp((multiRobustShare - 0.4) / 0.3, 0, 1) // full at >= 70% of choices with 2+ robust winners
          - clamp((meanW - 6) / 4, 0, 1)              // ...but many-winner levels are the easy ones
order   = clamp(winnable(1/3, H=40) / 0.3, 0, 1)      // the line survives a different near future

fitness5 = fit3
         + 12·info5 + info5·(5·opening + 6·readable + 3·slack)   // fitness4's axes, on fair bits
         + 4·options + 3·order
         - 8·blind - 2·unfairDecisions - 2·traps
```

Floors (acceptance, by tier 0/1/2/3):

| floor | tier 0 | tier 1 | tier 2 | tier 3 | campaign now |
|---|---|---|---|---|---|
| unfair decisions | 0 | 0 | <= 1 | <= 2 | 62 levels have >= 1, tier 1 mean 2.4 |
| traps with h > 3 | 0 | 0 | 0 | <= 1 | |
| blind bits / hidden bits | <= 0.25 | <= 0.25 | <= 0.3 | <= 0.35 | 0.30 pack mean |
| fair hidden bits (replaces minHidden) | >= 3 | >= 8 | >= 15 | >= 24 | 20.2 mean |
| share of choices with w >= 2 | >= 0.5 | >= 0.5 | >= 0.5 | >= 0.45 | 0.73 (closers 0.58) |
| mean w | <= 6 | <= 6 | <= 6 | <= 6 | 3.0 |
| winnable at 1/3, H = 40 | >= 0.3 | >= 0.2 | >= 0.1 | >= 0.05 | 44 levels at <= 5% |

Where the difficulty comes from instead:

- **Hard-but-visible decisions.** Deep visible losers (h > 3, losing under every plausible order) are the decisions
  the player can solve by reading the board and carousel. Reward them through `info5`. 1,821 of them exist in the
  pack already, 60% of the deep losers.
- **Small ring.** Ring size is an existing lever (LEVEL-QUALITY section 8). It raises pressure on what's on show and
  doesn't depend on the hidden order.
- **Longer belt.** A belt of 20 removes about 18% of the unfair decisions on the hardest levels at no design cost
  (app layout permitting). Design to belt 20 and measure with `shown = 20`.
- **Generator side.**
  - **Heralds** (the climax colour visible early) already help readability; extend the idea to every planted fork.
  - **Arrival window.** The colour batch that decides a fork should arrive within carousel + belt of the fork. The
    trap cases are forks whose deciding batch is 30-60 elements beyond the belt.

Cost: `Fairness.measure` takes about 1 s per level with the x1 chain and about 4-5 s with x16 (8 threads: 100 s and
470 s for the pack). The generator tries 400-800 candidates per slot, so:

- Use the x1 chain, or the x1 unfair/blind counts, as a cheap prefilter on the best tries.
- Run x16 only on the finalists: for example the top 10 by fitness4, before acceptance.
- Order dependence (64 solves of the start state per line) is cheap and can run on every try.

## 8. Limits

- **Every count is along one line,** the solver's. A player on another winning line meets other decisions.
- **Every unfair count is a lower bound** (section 5). The x16 chain undercounts by maybe a third relative to x64,
  which hasn't converged either.
- **The sampler stands in for the player.** p(m) is the win rate of a perfect player over winnable hidden orders near
  the real one. A person also misses visible information, so this measures what's possible to read, not what's read.
- **Budgets.** Exhausted child solves count as wins (inflating p for every move alike). Exhausted parent solves are
  dropped (4.6% of the uniform order-dependence draws).
- **Tests.** The unit tests use small hand-built levels:
  - a blind trap that scores unfair (and fair when the belt shows the whole queue);
  - interchangeable moves with w >= 2;
  - sampler invariants, order dependence and release windows.

## Runtimes (8 threads)

| run | wall time |
|---|---|
| main pass, x1 chain, 100 levels (incl. release windows) | 100 s |
| main pass, x16 chain + order dependence at 200k, 100 levels | 471 s |
| variants x1 (11 variants x 20 levels) | 423 s |
| variants x16 (belt 20 / 30, random-only belts, chain x64) on 20 levels | 2,224 s (x64 alone 9,661 CPU-s) |

Regenerate: `./gradlew :levelgen:reversePreview -PreverseMain=com.phloots.crittercafe.levelgen.reverse.FairnessReportKt`
(add `--hardest 20` for the variant study; `--variants belt_20,belt_30,chain_x64` to choose variants).

# Appendix

## A1. Generated tables, belt 10, x16 chain (FairnessReport, 100 levels)

Pack `app/src/main/assets/levels.json`, solver lines, belt 10, horizon H = 40, K = 16 winnable orderings (random draws: at most 48; chain: burn-in 256, every 64), robust p >= 0.9, budgets 20000/50000 nodes, 24 sampled lines per level, order dependence over 64 draws (budget 200000, undecided draws counted as not winnable and shown as (n?)). Main pass 471 s, variants 0 s, total 472 s on 8 threads.

#### Per level (belt 10)

| L | slot | tier | ring | moves | hidden | blind bits | dec | fair | unfair (no robust / trap) | undet | random samples | worst (step: best winner p vs best loser p) | w>=2 | mean w | wR>=2 | mean wR | deep losers blind / visible | shallow blind | winnable, uniform: start / 1/3 / 2/3 | winnable, H=40 | window width | narrow / blind | conc | s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | breather | 0 | 40 | 8 | 0.0 | 0.0 | 0 | 100% | 0 (0/0) | 0 | 100% | - | 100% | 2.00 | 100% | 2.00 | 0 / 0 | 0 | 100% / 100% / 100% | 100% / 100% / 100% | 4.0 | 0 / 0 | 5.0 | 0+0 |
| 2 | ladder 2-5 | 0 | 40 | 28 | 3.3 | 3.0 | 2 | 100% | 0 (0/0) | 0 | 100% | @6 1.00 vs 0.88 (w 2/4) | 93% | 3.59 | 93% | 3.59 | 5 / 0 | 0 | 100% / 100% / 100% | 100% / 100% / 100% | 12.9 | 0 / 0 | 13.9 | 0+0 |
| 3 | ladder 2-5 | 0 | 40 | 31 | 12.0 | 5.2 | 7 | 86% | 1 (0/1) | 0 | 100% | @7 0.94 vs 0.94 (w 2/5) | 80% | 2.80 | 77% | 2.77 | 10 / 8 | 0 | 6% / 100% / 100% | 22%(2?) / 100% / 100% | 8.5 | 2 / 0 | 9.5 | 1+4 |
| 4 | ladder 2-5 | 0 | 40 | 35 | 9.0 | 4.2 | 5 | 80% | 1 (0/1) | 0 | 100% | @10 1.00 vs 0.94 (w 2/5) | 73% | 3.36 | 70% | 3.33 | 8 / 1 | 0 | 100% / 91% / 100% | 100% / 94% / 100% | 16.9 | 0 / 0 | 17.9 | 0+0 |
| 5 | ladder 2-5 | 0 | 40 | 37 | 8.3 | 0.3 | 5 | 100% | 0 (0/0) | 0 | 78% | @5 1.00 vs 0.12 (w 4/5) | 82% | 3.06 | 79% | 2.97 | 1 / 5 | 0 | 13% / 58% / 100% | 5% / 61% / 100% | 13.8 | 0 / 0 | 14.8 | 1+0 |
| 6 | ladder 6-9 | 1 | 40 | 45 | 16.2 | 10.2 | 14 | 86% | 2 (0/2) | 0 | 98% | @14 1.00 vs 0.94 (w 1/3) | 73% | 2.98 | 73% | 2.86 | 16 / 5 | 0 | 6% / 98% / 100% | 20%(1?) / 78% / 100% | 10.0 | 2 / 1 | 11.0 | 2+3 |
| 7 | ladder 6-9 | 1 | 40 | 41 | 11.4 | 4.9 | 14 | 93% | 1 (1/0) | 0 | 99% | @5 0.41 vs 0.88 (w 1/3) | 83% | 3.18 | 78% | 3.10 | 7 / 7 | 0 | 63% / 100% / 100% | 55% / 100% / 100% | 10.7 | 0 / 0 | 11.7 | 1+0 |
| 8 | ladder 6-9 | 1 | 40 | 48 | 13.1 | 5.6 | 8 | 50% | 4 (3/1) | 0 | 5% | @16 0.06 vs 0.94 (w 1/4) | 60% | 2.48 | 57% | 2.33 | 4 / 11 | 2 | 0% / 2% / 100% | 5% / 0% / 100% | 14.6 | 1 / 0 | 15.6 | 9+1 |
| 9 | ladder 6-9 | 1 | 40 | 48 | 9.9 | 8.3 | 8 | 100% | 0 (0/0) | 0 | 100% | @21 0.94 vs 0.88 (w 1/6) | 91% | 5.91 | 85% | 5.64 | 18 / 0 | 0 | 83%(11?) / 98%(1?) / 100% | 84%(10?) / 100% / 100% | 22.6 | 0 / 0 | 23.6 | 1+1 |
| 10 | closer | 3 | 40 | 64 | 41.9 | 14.4 | 32 | 84% | 5 (2/3) | 0 | 22% | @11 0.12 vs 0.94 (w 2/5) | 55% | 2.47 | 43% | 2.17 | 22 / 32 | 1 | 0% / 0% / 72% | 0% / 0% / 23% | 8.4 | 16 / 0 | 9.4 | 59+2 |
| 11 | breather | 0 | 40 | 24 | 8.2 | 5.6 | 9 | 100% | 0 (0/0) | 0 | 100% | @3 1.00 vs 0.82 (w 1/2) | 65% | 1.70 | 65% | 1.70 | 6 / 1 | 0 | 6% / 56% / 100% | 28% / 48% / 100% | 3.5 | 11 / 0 | 4.5 | 0+0 |
| 12 | ladder 2-5 | 0 | 40 | 31 | 7.4 | 0.0 | 6 | 100% | 0 (0/0) | 0 | 100% | @5 1.00 vs 0.00 (w 3/4) | 80% | 2.53 | 77% | 2.47 | 0 / 7 | 0 | 59% / 100% / 100% | 67% / 100% / 100% | 8.3 | 7 / 1 | 9.3 | 0+0 |
| 13 | ladder 2-5 | 0 | 40 | 35 | 11.4 | 8.8 | 12 | 92% | 1 (0/1) | 0 | 96% | @9 1.00 vs 0.94 (w 3/5) | 94% | 3.50 | 94% | 3.41 | 21 / 0 | 1 | 89%(2?) / 98% / 100% | 100% / 88% / 100% | 15.0 | 0 / 0 | 16.0 | 1+2 |
| 14 | ladder 2-5 | 0 | 40 | 37 | 5.5 | 0.0 | 6 | 100% | 0 (0/0) | 0 | 83% | @4 1.00 vs 0.00 (w 2/3) | 77% | 2.57 | 67% | 2.47 | 0 / 5 | 0 | 100% / 94% / 100% | 100% / 84% / 100% | 9.9 | 0 / 0 | 10.9 | 0+0 |
| 15 | ladder 2-5 | 1 | 40 | 45 | 18.4 | 7.4 | 14 | 79% | 3 (0/3) | 0 | 88% | @17 1.00 vs 0.94 (w 4/7) | 73% | 3.84 | 68% | 3.73 | 17 / 9 | 0 | 8%(33?) / 50% / 100% | 94%(3?) / 61% / 100% | 15.9 | 0 / 0 | 16.9 | 22+2 |
| 16 | ladder 6-9 | 1 | 40 | 41 | 17.2 | 3.4 | 15 | 100% | 0 (0/0) | 0 | 85% | @2 1.00 vs 0.88 (w 3/5) | 79% | 3.21 | 64% | 2.85 | 9 / 20 | 0 | 3% / 44% / 100% | 8%(1?) / 38% / 100% | 9.7 | 5 / 0 | 10.7 | 12+1 |
| 17 | ladder 6-9 | 1 | 40 | 48 | 15.6 | 2.8 | 15 | 100% | 0 (0/0) | 0 | 70% | @27 1.00 vs 0.59 (w 2/3) | 82% | 4.09 | 70% | 3.82 | 4 / 6 | 1 | 5%(13?) / 55% / 2% | 34%(11?) / 53% / 5% | 17.1 | 0 / 0 | 18.1 | 1+5 |
| 18 | ladder 6-9 | 1 | 40 | 48 | 1.3 | 0.0 | 1 | 100% | 0 (0/0) | 0 | 100% | @24 1.00 vs 0.00 (w 2/5) | 98% | 5.09 | 98% | 5.09 | 0 / 0 | 0 | 73%(12?) / 100% / 100% | 100% / 100% / 100% | 17.3 | 0 / 0 | 18.3 | 0+0 |
| 19 | ladder 6-9 | 2 | 40 | 51 | 21.0 | 2.0 | 19 | 100% | 0 (0/0) | 0 | 49% | @7 1.00 vs 0.29 (w 1/4) | 67% | 2.08 | 61% | 2.00 | 3 / 14 | 0 | 0% / 3% / 100% | 0% / 0% / 100% | 6.8 | 11 / 1 | 7.8 | 9+0 |
| 20 | closer | 3 | 40 | 64 | 52.6 | 3.0 | 41 | 100% | 0 (0/0) | 0 | 15% | @36 1.00 vs 0.35 (w 1/3) | 34% | 1.51 | 31% | 1.48 | 4 / 52 | 0 | 0% / 0% / 72% | 0% / 0% / 34% | 2.8 | 41 / 3 | 3.8 | 20+0 |
| 21 | breather | 0 | 40 | 24 | 7.0 | 0.0 | 7 | 100% | 0 (0/0) | 0 | 90% | @4 1.00 vs 0.00 (w 1/2) | 61% | 1.78 | 61% | 1.78 | 0 / 4 | 0 | 33% / 100% / 100% | 17% / 100% / 100% | 3.6 | 7 / 0 | 4.6 | 0+0 |
| 22 | ladder 2-5 | 0 | 38 | 35 | 20.5 | 11.4 | 12 | 75% | 3 (0/3) | 0 | 88% | @19 0.65 vs 0.94 (w 1/4) | 65% | 2.59 | 62% | 2.53 | 15 / 1 | 3 | 100% / 100% / 100% | 100% / 81% / 100% | 16.1 | 0 / 0 | 17.1 | 1+0 |
| 23 | ladder 2-5 | 0 | 38 | 37 | 22.8 | 3.9 | 15 | 93% | 1 (1/0) | 0 | 30% | @12 1.00 vs 0.82 (w 2/5) | 69% | 2.53 | 64% | 2.39 | 8 / 21 | 0 | 0% / 0% / 100% | 0% / 0% / 100% | 7.4 | 8 / 0 | 8.4 | 3+0 |
| 24 | ladder 2-5 | 1 | 38 | 41 | 0.2 | 0.0 | 1 | 100% | 0 (0/0) | 0 | 100% | @3 1.00 vs 0.00 (w 6/7) | 100% | 5.21 | 100% | 5.18 | 0 / 0 | 0 | 98% / 100% / 100% | 100% / 100% / 100% | 23.1 | 0 / 0 | 24.1 | 0+0 |
| 25 | ladder 2-5 | 1 | 38 | 48 | 26.7 | 18.4 | 19 | 63% | 7 (3/4) | 0 | 44% | @24 0.65 vs 0.76 (w 2/6) | 74% | 3.80 | 67% | 3.59 | 29 / 10 | 1 | 34%(42?) / 95% / 28% | 22%(50?) / 25% / 41% | 19.4 | 0 / 0 | 20.4 | 41+7 |
| 26 | ladder 6-9 | 1 | 38 | 48 | 21.3 | 12.7 | 14 | 64% | 5 (1/4) | 0 | 69% | @13 0.12 vs 0.94 (w 3/8) | 71% | 3.47 | 64% | 3.13 | 23 / 7 | 2 | 98%(1?) / 34% / 100% | 98%(1?) / 17% / 100% | 20.0 | 0 / 0 | 21.0 | 4+0 |
| 27 | ladder 6-9 | 2 | 36 | 51 | 42.4 | 9.1 | 23 | 91% | 2 (1/1) | 0 | 68% | @3 0.18 vs 0.82 (w 1/6) | 62% | 2.66 | 54% | 2.46 | 13 / 44 | 0 | 0%(1?) / 67% / 100% | 8% / 55% / 100% | 19.9 | 0 / 0 | 20.9 | 21+4 |
| 28 | ladder 6-9 | 2 | 36 | 51 | 22.9 | 10.0 | 15 | 67% | 5 (1/4) | 0 | 73% | @16 1.00 vs 0.94 (w 1/5) | 69% | 3.12 | 61% | 2.90 | 13 / 18 | 1 | 0% / 14% / 100% | 20%(13?) / 55% / 100% | 8.8 | 8 / 0 | 9.8 | 23+4 |
| 29 | ladder 6-9 | 2 | 40 | 62 | 19.0 | 6.8 | 21 | 100% | 0 (0/0) | 0 | 96% | @25 1.00 vs 0.88 (w 2/6) | 82% | 4.72 | 80% | 4.23 | 13 / 12 | 4 | 45%(34?) / 91%(6?) / 53% | 63%(23?) / 98%(1?) / 55% | 24.7 | 0 / 0 | 25.7 | 13+11 |
| 30 | closer | 3 | 40 | 66 | 50.9 | 5.5 | 49 | 98% | 1 (0/1) | 0 | 22% | @33 1.00 vs 0.94 (w 3/5) | 59% | 2.42 | 50% | 1.80 | 9 / 64 | 0 | 0%(3?) / 0%(7?) / 2% | 0% / 0% / 5% | 4.2 | 31 / 2 | 5.2 | 95+1 |
| 31 | breather | 0 | 40 | 24 | 7.0 | 4.0 | 7 | 100% | 0 (0/0) | 0 | 100% | @5 1.00 vs 0.71 (w 1/2) | 59% | 1.68 | 59% | 1.68 | 4 / 3 | 0 | 44% / 100% / 100% | 30% / 100% / 100% | 3.2 | 12 / 1 | 4.2 | 0+0 |
| 32 | ladder 2-5 | 1 | 36 | 45 | 21.3 | 13.5 | 16 | 75% | 4 (3/1) | 0 | 73% | @16 1.00 vs 0.94 (w 2/5) | 61% | 3.48 | 52% | 3.07 | 20 / 0 | 1 | 5%(37?) / 9% / 100% | 97% / 55% / 100% | 14.7 | 0 / 0 | 15.7 | 9+0 |
| 33 | ladder 2-5 | 1 | 36 | 41 | 11.3 | 3.5 | 11 | 90% | 1 (1/0) | 1 | 37% | @13 0.41 vs 0.71 (w 1/3) | 79% | 4.05 | 77% | 3.97 | 6 / 8 | 0 | 64%(18?) / 2% / 100% | 14%(55?) / 3% / 100% | 6.9 | 6 / 1 | 7.9 | 26+4 |
| 34 | ladder 2-5 | 1 | 40 | 48 | 8.8 | 0.0 | 13 | 100% | 0 (0/0) | 0 | 15% | @9 1.00 vs 0.06 (w 2/3) | 62% | 2.47 | 49% | 2.07 | 0 / 10 | 0 | 0% / 0% / 100% | 0% / 0% / 100% | 5.1 | 20 / 0 | 6.1 | 27+0 |
| 35 | ladder 2-5 | 1 | 36 | 48 | 18.2 | 2.0 | 15 | 93% | 1 (1/0) | 0 | 96% | @12 0.94 vs 0.88 (w 3/7) | 84% | 4.11 | 67% | 3.44 | 3 / 24 | 2 | 50%(1?) / 86% / 100% | 89%(3?) / 81% / 100% | 16.4 | 3 / 0 | 17.4 | 2+2 |
| 36 | ladder 6-9 | 2 | 36 | 50 | 21.2 | 6.8 | 14 | 86% | 2 (1/1) | 0 | 92% | @20 1.00 vs 0.94 (w 3/8) | 78% | 4.88 | 76% | 4.53 | 19 / 14 | 0 | 80%(13?) / 63% / 100% | 97%(2?) / 34% / 100% | 20.4 | 0 / 0 | 21.4 | 3+1 |
| 37 | ladder 6-9 | 2 | 34 | 51 | 31.9 | 12.6 | 21 | 89% | 2 (0/2) | 3 | 24% | @20 1.00 vs 0.94 (w 1/6) | 68% | 2.96 | 62% | 2.72 | 20 / 28 | 0 | 8%(52?) / 14%(3?) / 100% | 3%(59?) / 0% / 100% | 13.3 | 8 / 1 | 14.3 | 109+5 |
| 38 | ladder 6-9 | 2 | 34 | 62 | 16.4 | 9.3 | 11 | 91% | 1 (0/1) | 0 | 100% | @39 1.00 vs 0.94 (w 1/3) | 82% | 4.17 | 82% | 4.13 | 14 / 0 | 0 | 27%(27?) / 97%(2?) / 100% | 86% / 81%(12?) / 100% | 19.8 | 2 / 0 | 20.8 | 1+1 |
| 39 | ladder 6-9 | 2 | 38 | 62 | 36.9 | 9.9 | 31 | 94% | 2 (0/2) | 0 | 19% | @34 1.00 vs 0.94 (w 1/2) | 57% | 2.11 | 44% | 1.59 | 14 / 24 | 0 | 0% / 0% / 3% | 2%(1?) / 0% / 5% | 5.8 | 16 / 1 | 6.8 | 106+1 |
| 40 | closer | 3 | 40 | 70 | 44.7 | 11.6 | 41 | 100% | 0 (0/0) | 0 | 38% | @14 1.00 vs 0.82 (w 3/5) | 65% | 2.51 | 55% | 2.09 | 22 / 38 | 0 | 0% / 0% / 23% | 0%(1?) / 0% / 41% | 10.2 | 3 / 0 | 11.2 | 126+2 |
| 41 | breather | 0 | 40 | 24 | 7.8 | 3.2 | 10 | 100% | 0 (0/0) | 0 | 100% | @4 1.00 vs 0.35 (w 2/3) | 68% | 1.95 | 63% | 1.89 | 5 / 3 | 0 | 86% / 100% / 100% | 88% / 100% / 100% | 5.3 | 4 / 0 | 6.3 | 0+0 |
| 42 | ladder 2-5 | 1 | 34 | 41 | 12.1 | 2.7 | 9 | 78% | 2 (0/2) | 0 | 100% | @8 1.00 vs 0.94 (w 3/5) | 90% | 4.13 | 90% | 4.10 | 8 / 14 | 0 | 42%(37?) / 100% / 100% | 80%(13?) / 100% / 100% | 16.1 | 0 / 0 | 17.1 | 1+2 |
| 43 | ladder 2-5 | 1 | 34 | 48 | 21.4 | 10.1 | 15 | 60% | 6 (4/2) | 0 | 54% | @30 0.59 vs 0.59 (w 1/4) | 66% | 2.72 | 53% | 2.45 | 15 / 14 | 1 | 27%(4?) / 56% / 0% | 48%(5?) / 28% / 0% | 14.3 | 5 / 0 | 15.3 | 17+2 |
| 44 | ladder 2-5 | 1 | 40 | 48 | 16.7 | 7.7 | 21 | 76% | 5 (0/5) | 0 | 90% | @5 1.00 vs 0.94 (w 1/3) | 79% | 2.45 | 68% | 2.26 | 15 / 9 | 0 | 39% / 75% / 100% | 77% / 63% / 100% | 7.9 | 2 / 0 | 8.9 | 1+1 |
| 45 | ladder 2-5 | 2 | 38 | 51 | 25.2 | 7.0 | 16 | 81% | 3 (1/2) | 0 | 46% | @23 0.41 vs 0.76 (w 1/9) | 84% | 4.82 | 82% | 4.38 | 12 / 39 | 1 | 2%(23?) / 83% / 100% | 11%(21?) / 70%(1?) / 100% | 7.6 | 9 / 1 | 8.6 | 24+5 |
| 46 | ladder 6-9 | 2 | 38 | 51 | 14.3 | 5.0 | 13 | 100% | 0 (0/0) | 0 | 91% | @13 1.00 vs 0.71 (w 4/6) | 88% | 3.30 | 84% | 2.96 | 8 / 17 | 1 | 0% / 36% / 100% | 0% / 34% / 100% | 9.4 | 1 / 0 | 10.4 | 10+4 |
| 47 | ladder 6-9 | 2 | 32 | 62 | 33.1 | 8.7 | 26 | 88% | 3 (3/0) | 0 | 63% | @29 0.47 vs 0.65 (w 1/7) | 76% | 3.63 | 64% | 2.93 | 24 / 32 | 0 | 41%(27?) / 39%(18?) / 13% | 72%(1?) / 98%(1?) / 50% | 13.8 | 4 / 0 | 14.8 | 32+0 |
| 48 | ladder 6-9 | 2 | 40 | 62 | 24.7 | 11.3 | 22 | 100% | 0 (0/0) | 0 | 34% | @23 1.00 vs 0.88 (w 2/5) | 82% | 2.77 | 66% | 2.56 | 17 / 15 | 0 | 0% / 0% / 100% | 0% / 2% / 100% | 6.0 | 17 / 1 | 7.0 | 37+0 |
| 49 | ladder 6-9 | 2 | 40 | 65 | 27.7 | 3.2 | 27 | 100% | 0 (0/0) | 0 | 43% | @43 1.00 vs 0.53 (w 2/4) | 67% | 2.22 | 63% | 2.17 | 5 / 20 | 0 | 0% / 0% / 100% | 0% / 0% / 100% | 5.3 | 22 / 3 | 6.3 | 14+0 |
| 50 | closer | 3 | 40 | 70 | 30.4 | 8.0 | 31 | 94% | 2 (1/1) | 0 | 20% | @32 1.00 vs 0.94 (w 2/4) | 81% | 2.48 | 71% | 2.16 | 17 / 25 | 0 | 0% / 0% / 53% | 0% / 2% / 30% | 5.3 | 20 / 2 | 6.3 | 41+0 |
| 51 | breather | 0 | 40 | 24 | 6.3 | 3.2 | 4 | 100% | 0 (0/0) | 0 | 100% | @8 1.00 vs 0.65 (w 1/3) | 78% | 2.65 | 78% | 2.65 | 4 / 1 | 0 | 94% / 100% / 100% | 91% / 100% / 100% | 8.8 | 1 / 0 | 9.8 | 0+0 |
| 52 | ladder 2-5 | 1 | 40 | 48 | 9.8 | 4.4 | 7 | 57% | 3 (1/2) | 0 | 2% | @12 1.00 vs 0.94 (w 3/4) | 91% | 3.47 | 85% | 3.34 | 8 / 5 | 0 | 2%(22?) / 0% / 100% | 6%(14?) / 0% / 100% | 10.5 | 7 / 0 | 11.5 | 27+3 |
| 53 | ladder 2-5 | 1 | 32 | 48 | 23.0 | 9.6 | 12 | 58% | 5 (1/4) | 0 | 33% | @13 0.18 vs 0.94 (w 2/5) | 74% | 2.98 | 70% | 2.89 | 14 / 15 | 1 | 0%(6?) / 6% / 100% | 0%(8?) / 9% / 100% | 2.4 | 36 / 3 | 3.4 | 27+5 |
| 54 | ladder 2-5 | 2 | 32 | 51 | 31.0 | 15.1 | 19 | 79% | 4 (0/4) | 0 | 82% | @17 1.00 vs 0.94 (w 3/6) | 80% | 3.49 | 76% | 3.24 | 33 / 16 | 1 | 58%(14?) / 83%(7?) / 100% | 44%(30?) / 45%(27?) / 100% | 7.5 | 26 / 2 | 8.5 | 31+6 |
| 55 | ladder 2-5 | 2 | 32 | 51 | 23.5 | 1.3 | 14 | 100% | 0 (0/0) | 0 | 19% | @16 1.00 vs 0.65 (w 3/6) | 76% | 4.20 | 74% | 4.08 | 3 / 34 | 0 | 0%(30?) / 0%(16?) / 100% | 0%(50?) / 3%(5?) / 100% | 6.4 | 13 / 1 | 7.4 | 44+5 |
| 56 | ladder 6-9 | 2 | 36 | 62 | 18.6 | 1.0 | 16 | 100% | 0 (0/0) | 0 | 27% | @23 1.00 vs 0.88 (w 5/6) | 61% | 3.52 | 54% | 3.03 | 1 / 17 | 0 | 0%(11?) / 0%(14?) / 47% | 0%(27?) / 0%(9?) / 45% | 15.4 | 8 / 0 | 16.4 | 64+2 |
| 57 | ladder 6-9 | 2 | 38 | 63 | 33.6 | 16.2 | 28 | 86% | 4 (0/4) | 0 | 32% | @13 0.06 vs 0.94 (w 1/5) | 77% | 2.53 | 63% | 2.19 | 29 / 15 | 1 | 0% / 0% / 100% | 0% / 0% / 100% | 14.1 | 1 / 0 | 15.1 | 35+0 |
| 58 | ladder 6-9 | 2 | 30 | 65 | 36.2 | 14.0 | 36 | 94% | 2 (0/2) | 0 | 27% | @31 1.00 vs 0.94 (w 3/5) | 77% | 3.75 | 69% | 2.77 | 28 / 40 | 1 | 0%(4?) / 0%(1?) / 100% | 2% / 6%(1?) / 100% | 3.4 | 34 / 1 | 4.4 | 203+9 |
| 59 | ladder 6-9 | 2 | 40 | 65 | 23.8 | 10.9 | 33 | 97% | 1 (1/0) | 0 | 16% | @13 0.71 vs 0.71 (w 2/3) | 72% | 2.10 | 60% | 1.93 | 20 / 10 | 0 | 0% / 0% / 8% | 0% / 0% / 11% | 4.7 | 22 / 1 | 5.7 | 53+0 |
| 60 | closer | 3 | 40 | 68 | 26.0 | 3.9 | 27 | 85% | 4 (1/3) | 0 | 64% | @4 0.53 vs 0.53 (w 6/8) | 79% | 4.72 | 72% | 3.90 | 15 / 21 | 1 | 0%(3?) / 89%(6?) / 75% | 0%(1?) / 80%(13?) / 92% | 26.8 | 0 / 0 | 27.8 | 51+3 |
| 61 | breather | 0 | 40 | 24 | 7.8 | 3.7 | 9 | 100% | 0 (0/0) | 0 | 100% | @0 1.00 vs 0.88 (w 3/4) | 87% | 2.70 | 87% | 2.61 | 8 / 2 | 0 | 100% / 83% / 100% | 100% / 77% / 100% | 7.5 | 2 / 0 | 8.5 | 0+0 |
| 62 | ladder 2-5 | 1 | 30 | 48 | 18.8 | 6.1 | 18 | 89% | 2 (0/2) | 0 | 72% | @8 0.82 vs 0.94 (w 2/5) | 80% | 2.80 | 67% | 2.46 | 11 / 7 | 1 | 3%(6?) / 63%(3?) / 100% | 3%(2?) / 36% / 100% | 7.5 | 6 / 0 | 8.5 | 18+1 |
| 63 | ladder 2-5 | 2 | 30 | 51 | 21.3 | 5.3 | 18 | 100% | 0 (0/0) | 0 | 80% | @21 1.00 vs 0.88 (w 1/5) | 77% | 4.29 | 58% | 3.35 | 11 / 22 | 0 | 0%(7?) / 45%(1?) / 100% | 5% / 42% / 100% | 18.0 | 0 / 0 | 19.0 | 12+1 |
| 64 | ladder 2-5 | 2 | 30 | 51 | 19.9 | 16.2 | 15 | 80% | 3 (1/2) | 0 | 100% | @21 0.59 vs 0.82 (w 1/6) | 82% | 4.36 | 80% | 4.18 | 30 / 7 | 4 | 0%(13?) / 78%(9?) / 100% | 36%(12?) / 89% / 100% | 0.0 | 51 / 0 | 0.0 | 2+7 |
| 65 | ladder 2-5 | 2 | 38 | 62 | 29.8 | 14.7 | 32 | 100% | 0 (0/0) | 0 | 59% | @36 1.00 vs 0.88 (w 2/5) | 82% | 3.59 | 67% | 3.03 | 28 / 18 | 1 | 0% / 0% / 59% | 0% / 0% / 58% | 14.7 | 0 / 0 | 15.7 | 63+1 |
| 66 | ladder 6-9 | 2 | 38 | 62 | 30.8 | 7.2 | 26 | 96% | 1 (1/0) | 0 | 26% | @22 1.00 vs 0.88 (w 3/7) | 74% | 3.16 | 67% | 2.62 | 10 / 26 | 2 | 0% / 0% / 100% | 0% / 0% / 100% | 18.4 | 5 / 0 | 19.4 | 54+2 |
| 67 | ladder 6-9 | 2 | 32 | 63 | 32.9 | 15.8 | 25 | 76% | 6 (5/1) | 0 | 30% | @5 0.53 vs 0.76 (w 1/7) | 73% | 2.85 | 56% | 2.26 | 30 / 32 | 2 | 0%(40?) / 0%(23?) / 95% | 13%(23?) / 0% / 100% | 4.9 | 25 / 0 | 5.9 | 94+4 |
| 68 | ladder 6-9 | 2 | 36 | 65 | 33.5 | 5.8 | 30 | 100% | 0 (0/0) | 0 | 43% | @31 0.94 vs 0.53 (w 2/4) | 67% | 2.24 | 54% | 1.95 | 11 / 22 | 0 | 0% / 0% / 100% | 3% / 0% / 100% | 4.7 | 26 / 2 | 5.7 | 35+0 |
| 69 | ladder 6-9 | 2 | 40 | 65 | 26.8 | 11.3 | 25 | 80% | 5 (3/2) | 0 | 40% | @14 0.53 vs 0.94 (w 1/6) | 66% | 3.25 | 58% | 2.70 | 25 / 13 | 1 | 0% / 0% / 20% | 5% / 0% / 94% | 20.6 | 0 / 0 | 21.6 | 27+1 |
| 70 | closer | 3 | 40 | 70 | 54.8 | 13.1 | 44 | 93% | 3 (2/1) | 0 | 15% | @35 0.65 vs 0.88 (w 2/4) | 55% | 2.22 | 46% | 1.70 | 26 / 53 | 1 | 0% / 0% / 19% | 0% / 0% / 25% | 4.8 | 31 / 1 | 5.8 | 168+1 |
| 71 | breather | 0 | 40 | 24 | 7.2 | 3.0 | 6 | 100% | 0 (0/0) | 0 | 100% | @5 1.00 vs 0.88 (w 1/2) | 70% | 2.43 | 70% | 2.43 | 3 / 1 | 0 | 97% / 100% / 100% | 98% / 100% / 100% | 6.3 | 2 / 0 | 7.3 | 0+0 |
| 72 | ladder 2-5 | 2 | 30 | 51 | 41.0 | 17.6 | 25 | 68% | 8 (4/4) | 0 | 28% | @7 0.53 vs 0.88 (w 2/7) | 56% | 1.81 | 35% | 1.35 | 30 / 31 | 2 | 0%(3?) / 6% / 100% | 0% / 16% / 100% | 5.0 | 13 / 0 | 6.0 | 60+2 |
| 73 | ladder 2-5 | 2 | 38 | 51 | 15.1 | 6.9 | 20 | 100% | 0 (0/0) | 0 | 71% | @17 1.00 vs 0.82 (w 1/2) | 72% | 3.04 | 66% | 2.76 | 9 / 16 | 0 | 0% / 92% / 100% | 2% / 100% / 100% | 13.0 | 2 / 0 | 14.0 | 17+0 |
| 74 | ladder 2-5 | 2 | 30 | 62 | 21.9 | 10.3 | 29 | 100% | 0 (0/0) | 0 | 84% | @35 1.00 vs 0.82 (w 2/3) | 80% | 3.15 | 68% | 2.83 | 22 / 8 | 2 | 2%(7?) / 98%(1?) / 100% | 16%(3?) / 100% / 100% | 14.7 | 2 / 0 | 15.7 | 25+0 |
| 75 | ladder 2-5 | 2 | 40 | 62 | 16.9 | 2.0 | 22 | 100% | 0 (0/0) | 0 | 9% | @29 1.00 vs 0.47 (w 1/2) | 71% | 2.96 | 69% | 2.71 | 1 / 16 | 1 | 0% / 0% / 38% | 0% / 0% / 100% | 4.4 | 34 / 2 | 5.4 | 12+0 |
| 76 | ladder 6-9 | 2 | 34 | 62 | 46.7 | 22.3 | 34 | 88% | 4 (1/3) | 1 | 66% | @8 1.00 vs 0.94 (w 2/9) | 61% | 2.64 | 56% | 2.51 | 40 / 29 | 4 | 0%(4?) / 3% / 66% | 0% / 8%(1?) / 39% | 0.0 | 62 / 1 | 1.0 | 57+3 |
| 77 | ladder 6-9 | 2 | 40 | 63 | 21.9 | 9.9 | 22 | 82% | 4 (0/4) | 0 | 22% | @17 1.00 vs 0.94 (w 4/5) | 70% | 2.62 | 69% | 2.46 | 19 / 10 | 0 | 0%(2?) / 0%(10?) / 100% | 0% / 0% / 100% | 0.0 | 63 / 0 | 0.0 | 53+8 |
| 78 | ladder 6-9 | 2 | 40 | 65 | 12.8 | 0.6 | 18 | 94% | 1 (1/0) | 0 | 41% | @5 0.94 vs 0.18 (w 2/4) | 88% | 3.34 | 66% | 2.98 | 1 / 13 | 0 | 0% / 0% / 100% | 0% / 0% / 100% | 9.6 | 8 / 0 | 10.6 | 16+0 |
| 79 | ladder 6-9 | 2 | 40 | 65 | 45.3 | 1.4 | 32 | 100% | 0 (0/0) | 0 | 12% | @41 1.00 vs 0.41 (w 3/4) | 64% | 2.36 | 50% | 2.17 | 2 / 62 | 0 | 0%(1?) / 0% / 0% | 0%(2?) / 0% / 6% | 7.1 | 20 / 0 | 8.1 | 24+1 |
| 80 | closer | 3 | 28 | 70 | 49.8 | 10.3 | 35 | 83% | 6 (4/2) | 0 | 10% | @15 0.35 vs 0.65 (w 1/6) | 45% | 1.70 | 32% | 1.41 | 13 / 34 | 4 | 0%(8?) / 0%(5?) / 11% | 0% / 0% / 11% | 6.9 | 22 / 1 | 7.9 | 81+1 |
| 81 | breather | 0 | 40 | 24 | 6.6 | 1.0 | 4 | 100% | 0 (0/0) | 0 | 100% | @6 1.00 vs 0.12 (w 1/3) | 74% | 2.70 | 74% | 2.70 | 1 / 6 | 0 | 59% / 100% / 100% | 81% / 100% / 100% | 8.4 | 2 / 0 | 9.4 | 0+0 |
| 82 | ladder 2-5 | 2 | 38 | 51 | 23.0 | 6.2 | 15 | 100% | 0 (0/0) | 0 | 70% | @5 1.00 vs 0.88 (w 1/9) | 78% | 3.10 | 73% | 3.00 | 5 / 12 | 4 | 0% / 64% / 100% | 2% / 63% / 100% | 19.8 | 1 / 0 | 20.8 | 4+0 |
| 83 | ladder 2-5 | 2 | 30 | 51 | 29.7 | 6.3 | 17 | 88% | 2 (1/1) | 0 | 85% | @13 0.71 vs 0.82 (w 2/11) | 88% | 5.23 | 85% | 4.83 | 17 / 47 | 2 | 0%(20?) / 11%(12?) / 100% | 8%(29?) / 55% / 100% | 15.8 | 1 / 0 | 16.8 | 10+4 |
| 84 | ladder 2-5 | 2 | 38 | 62 | 25.7 | 2.6 | 31 | 90% | 3 (1/2) | 0 | 15% | @8 1.00 vs 0.94 (w 4/6) | 67% | 2.52 | 56% | 2.07 | 8 / 15 | 0 | 0% / 0% / 100% | 0% / 0% / 100% | 6.5 | 17 / 2 | 7.5 | 69+0 |
| 85 | ladder 2-5 | 2 | 38 | 62 | 44.6 | 5.2 | 34 | 91% | 3 (1/2) | 0 | 0% | @29 0.12 vs 0.94 (w 1/4) | 72% | 2.53 | 60% | 2.12 | 9 / 47 | 0 | 0% / 0% / 0% | 0% / 0% / 2% | 6.3 | 19 / 0 | 7.3 | 89+6 |
| 86 | ladder 6-9 | 2 | 30 | 62 | 39.2 | 4.7 | 31 | 84% | 3 (1/2) | 12 | 33% | @45 0.35 vs 0.94 (w 1/7) | 68% | 2.47 | 47% | 1.86 | 7 / 72 | 2 | 0%(12?) / 0%(3?) / 2% | 0% / 0% / 0% | 0.0 | 62 / 0 | 0.0 | 204+11 |
| 87 | ladder 6-9 | 2 | 40 | 63 | 34.0 | 10.2 | 28 | 89% | 3 (1/2) | 0 | 33% | @17 0.53 vs 0.59 (w 1/5) | 63% | 2.61 | 53% | 2.40 | 15 / 33 | 1 | 0%(9?) / 0% / 100% | 0%(11?) / 0% / 100% | 3.0 | 35 / 1 | 4.0 | 94+6 |
| 88 | ladder 6-9 | 2 | 36 | 65 | 28.9 | 4.6 | 26 | 96% | 1 (1/0) | 0 | 39% | @7 0.94 vs 0.71 (w 1/3) | 61% | 2.06 | 52% | 1.88 | 2 / 17 | 3 | 0% / 3% / 61% | 2% / 13% / 61% | 8.7 | 5 / 0 | 9.7 | 37+0 |
| 89 | ladder 6-9 | 2 | 40 | 65 | 28.4 | 3.6 | 23 | 91% | 2 (1/1) | 0 | 43% | @30 0.06 vs 0.94 (w 2/5) | 78% | 3.23 | 59% | 2.78 | 5 / 17 | 2 | 0%(4?) / 0%(6?) / 0% | 0%(1?) / 0% / 33% | 15.6 | 0 / 0 | 16.6 | 44+0 |
| 90 | closer | 3 | 32 | 66 | 40.2 | 9.5 | 23 | 100% | 0 (0/0) | 9 | 26% | @28 1.00 vs 0.88 (w 4/8) | 55% | 2.34 | 55% | 2.31 | 21 / 32 | 0 | 0%(22?) / 2%(16?) / 2% | 0% / 2%(9?) / 11% | 0.0 | 66 / 0 | 0.0 | 144+7 |
| 91 | breather | 0 | 40 | 24 | 9.6 | 7.6 | 10 | 90% | 1 (0/1) | 0 | 100% | @1 1.00 vs 0.94 (w 2/3) | 37% | 1.42 | 37% | 1.42 | 8 / 1 | 0 | 33% / 100% / 100% | 48% / 100% / 100% | 1.8 | 15 / 0 | 2.8 | 0+0 |
| 92 | ladder 2-5 | 2 | 38 | 51 | 27.0 | 4.8 | 21 | 90% | 2 (1/1) | 0 | 18% | @3 0.47 vs 0.94 (w 2/7) | 70% | 2.32 | 56% | 2.02 | 9 / 25 | 1 | 0% / 5% / 100% | 0% / 2% / 100% | 3.6 | 25 / 1 | 4.6 | 48+4 |
| 93 | ladder 2-5 | 2 | 34 | 51 | 27.9 | 0.7 | 23 | 96% | 1 (1/0) | 0 | 42% | @1 1.00 vs 0.65 (w 6/7) | 68% | 2.90 | 60% | 2.54 | 2 / 35 | 0 | 0%(4?) / 30%(2?) / 100% | 0% / 23% / 100% | 6.6 | 15 / 0 | 7.6 | 48+1 |
| 94 | ladder 2-5 | 2 | 38 | 62 | 29.7 | 7.2 | 29 | 97% | 1 (1/0) | 0 | 52% | @18 1.00 vs 0.41 (w 4/6) | 69% | 2.70 | 62% | 2.41 | 10 / 30 | 1 | 5%(22?) / 0% / 100% | 33%(20?) / 0% / 100% | 12.0 | 11 / 0 | 13.0 | 45+1 |
| 95 | ladder 2-5 | 2 | 38 | 62 | 24.6 | 7.3 | 18 | 94% | 1 (1/0) | 0 | 7% | @33 0.18 vs 0.88 (w 2/6) | 81% | 3.73 | 66% | 3.25 | 11 / 21 | 2 | 0%(2?) / 0%(1?) / 9% | 0%(2?) / 0% / 28% | 19.0 | 0 / 0 | 20.0 | 86+1 |
| 96 | ladder 6-9 | 2 | 38 | 62 | 29.0 | 6.3 | 29 | 100% | 0 (0/0) | 0 | 10% | @1 1.00 vs 0.76 (w 2/3) | 57% | 1.76 | 53% | 1.72 | 8 / 17 | 0 | 0% / 0% / 13% | 0% / 0% / 8% | 3.9 | 24 / 1 | 4.9 | 27+0 |
| 97 | ladder 6-9 | 2 | 32 | 63 | 33.5 | 15.5 | 25 | 88% | 3 (1/2) | 0 | 69% | @29 1.00 vs 0.94 (w 3/7) | 80% | 3.72 | 72% | 3.17 | 33 / 27 | 0 | 0%(22?) / 11%(24?) / 78% | 0% / 45%(14?) / 100% | 21.4 | 1 / 0 | 22.4 | 51+3 |
| 98 | ladder 6-9 | 2 | 40 | 65 | 22.0 | 3.2 | 26 | 92% | 2 (1/1) | 0 | 33% | @29 1.00 vs 0.94 (w 2/4) | 82% | 2.42 | 61% | 2.06 | 4 / 21 | 1 | 0% / 0% / 100% | 0% / 0% / 100% | 4.7 | 21 / 0 | 5.7 | 45+0 |
| 99 | ladder 6-9 | 2 | 40 | 65 | 19.5 | 2.9 | 25 | 96% | 1 (0/1) | 0 | 48% | @11 1.00 vs 0.94 (w 4/6) | 94% | 4.17 | 80% | 3.56 | 7 / 16 | 1 | 0% / 13% / 100% | 0% / 13% / 100% | 17.0 | 0 / 0 | 18.0 | 34+0 |
| 100 | closer | 3 | 40 | 64 | 40.1 | 11.3 | 31 | 94% | 2 (2/0) | 0 | 16% | @11 0.29 vs 0.82 (w 1/4) | 47% | 1.95 | 40% | 1.84 | 13 / 33 | 0 | 0%(2?) / 0% / 2% | 0% / 0% / 5% | 6.2 | 16 / 1 | 7.2 | 27+4 |

#### By cycle (belt 10)

| cycle | levels | with unfair decisions | unfair decisions (mean) | traps (mean) | fair share (mean) | hidden bits (mean) | blind bits (mean) | deep blind / visible losers (mean) | w>=2 share (mean) | mean w | winnable from start, uniform (mean) | winnable from start, H=40 (mean) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 01 | 10 | 6 | 1.4 | 0.8 | 88% | 12.5 | 5.6 | 9.1 / 6.9 | 79% | 3.18 | 47% | 49% |
| 02 | 10 | 2 | 0.4 | 0.4 | 97% | 15.9 | 3.3 | 6.4 / 11.4 | 75% | 3.01 | 34% | 53% |
| 03 | 10 | 7 | 2.4 | 1.7 | 85% | 23.4 | 7.8 | 12.3 / 18.1 | 71% | 3.23 | 41% | 43% |
| 04 | 10 | 7 | 1.3 | 0.7 | 92% | 21.8 | 7.3 | 12.2 / 14.9 | 70% | 3.24 | 28% | 42% |
| 05 | 10 | 6 | 2.1 | 1.2 | 88% | 21.3 | 6.7 | 12.6 / 18.8 | 78% | 3.05 | 24% | 38% |
| 06 | 10 | 7 | 2.3 | 1.9 | 86% | 23.2 | 8.0 | 15.5 / 17.4 | 77% | 3.34 | 15% | 14% |
| 07 | 10 | 6 | 2.0 | 0.8 | 91% | 27.6 | 9.9 | 19.0 / 20.2 | 74% | 3.15 | 10% | 16% |
| 08 | 10 | 5 | 2.3 | 1.3 | 91% | 27.9 | 8.4 | 14.0 / 22.0 | 68% | 2.61 | 10% | 12% |
| 09 | 10 | 7 | 1.7 | 1.0 | 93% | 30.0 | 5.4 | 9.0 / 29.8 | 70% | 2.88 | 6% | 9% |
| 10 | 10 | 9 | 1.4 | 0.6 | 94% | 26.3 | 6.7 | 10.5 / 22.6 | 68% | 2.71 | 4% | 8% |

#### By slot (belt 10)

| slot | levels | with unfair decisions | unfair decisions (mean) | traps (mean) | fair share (mean) | hidden bits (mean) | blind bits (mean) | deep blind / visible losers (mean) | w>=2 share (mean) | mean w | winnable from start, uniform (mean) | winnable from start, H=40 (mean) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| breather | 10 | 1 | 0.1 | 0.1 | 99% | 6.7 | 3.1 | 3.9 / 2.2 | 70% | 2.10 | 65% | 68% |
| closer | 10 | 7 | 2.3 | 1.1 | 93% | 43.1 | 9.1 | 16.2 / 38.4 | 58% | 2.43 | 0% | 0% |
| ladder 2-5 | 40 | 27 | 1.9 | 1.2 | 87% | 19.6 | 6.5 | 11.6 / 15.3 | 77% | 3.32 | 25% | 34% |
| ladder 6-9 | 40 | 27 | 1.8 | 1.1 | 91% | 25.4 | 7.8 | 13.5 / 20.1 | 74% | 3.15 | 13% | 19% |

#### By tier (belt 10)

| tier | levels | with unfair decisions | unfair decisions (mean) | traps (mean) | fair share (mean) | hidden bits (mean) | blind bits (mean) | deep blind / visible losers (mean) | w>=2 share (mean) | mean w | winnable from start, uniform (mean) | winnable from start, H=40 (mean) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 19 | 6 | 0.4 | 0.4 | 96% | 8.8 | 3.6 | 5.6 / 3.7 | 74% | 2.50 | 64% | 67% |
| 1 | 21 | 15 | 2.4 | 1.5 | 81% | 14.9 | 6.4 | 10.8 / 8.6 | 79% | 3.61 | 33% | 49% |
| 2 | 50 | 34 | 1.8 | 1.1 | 92% | 27.7 | 8.0 | 14.2 / 23.7 | 73% | 3.12 | 5% | 11% |
| 3 | 10 | 7 | 2.3 | 1.1 | 93% | 43.1 | 9.1 | 16.2 / 38.4 | 58% | 2.43 | 0% | 0% |

## A2. Belt and chain variants, x16 base (hardest 20)

#### Variants on the 20 levels with the most hidden bits

Each cell: unfair / undetermined / fair share / traps / random-sample share.

| L | hidden | belt 10 (main) | belt 20 | belt 30 | belt 20 random only | belt 30 random only | chain x64 (burn-in 1024, every 256) |
|---|---|---|---|---|---|---|---|
| 70 | 54.8 | 3 / 0 / 93% / 1 / 15% | 6 / 0 / 86% / 4 / 12% | 6 / 0 / 86% / 4 / 23% | 0 / 35 / 100% / 0 / 100% | 0 / 32 / 100% / 0 / 100% | 6 / 0 / 86% / 2 / 0% |
| 20 | 52.6 | 0 / 0 / 100% / 0 / 15% | 0 / 0 / 100% / 0 / 19% | 0 / 0 / 100% / 0 / 14% | 0 / 31 / 100% / 0 / 100% | 0 / 30 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 0% |
| 30 | 50.9 | 1 / 0 / 98% / 1 / 22% | 1 / 0 / 98% / 1 / 23% | 1 / 0 / 98% / 1 / 26% | 3 / 33 / 81% / 3 / 100% | 2 / 32 / 88% / 2 / 100% | 1 / 0 / 98% / 1 / 0% |
| 80 | 49.8 | 6 / 0 / 83% / 2 / 10% | 0 / 0 / 100% / 0 / 16% | 1 / 0 / 97% / 1 / 21% | 0 / 26 / 100% / 0 / 100% | 0 / 24 / 100% / 0 / 100% | 3 / 0 / 91% / 2 / 0% |
| 76 | 46.7 | 4 / 1 / 88% / 3 / 66% | 1 / 1 / 97% / 1 / 67% | 1 / 1 / 97% / 1 / 70% | 0 / 8 / 100% / 0 / 100% | 1 / 4 / 97% / 1 / 100% | 15 / 1 / 55% / 12 / 0% |
| 79 | 45.3 | 0 / 0 / 100% / 0 / 12% | 0 / 0 / 100% / 0 / 16% | 0 / 0 / 100% / 0 / 17% | 0 / 22 / 100% / 0 / 100% | 0 / 20 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 0% |
| 40 | 44.7 | 0 / 0 / 100% / 0 / 38% | 0 / 0 / 100% / 0 / 37% | 0 / 0 / 100% / 0 / 38% | 0 / 21 / 100% / 0 / 100% | 0 / 23 / 100% / 0 / 100% | 4 / 0 / 90% / 0 / 0% |
| 85 | 44.6 | 3 / 0 / 91% / 2 / 0% | 1 / 0 / 97% / 1 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 34 / 100% / 0 / 100% | 0 / 34 / 100% / 0 / 100% | 5 / 0 / 85% / 1 / 0% |
| 27 | 42.4 | 2 / 0 / 91% / 1 / 68% | 1 / 0 / 96% / 0 / 70% | 1 / 0 / 96% / 0 / 78% | 0 / 3 / 100% / 0 / 100% | 0 / 3 / 100% / 0 / 100% | 2 / 0 / 91% / 2 / 0% |
| 10 | 41.9 | 5 / 0 / 84% / 3 / 22% | 3 / 0 / 91% / 2 / 23% | 3 / 0 / 91% / 3 / 24% | 0 / 22 / 100% / 0 / 100% | 0 / 22 / 100% / 0 / 100% | 6 / 0 / 81% / 3 / 0% |
| 72 | 41.0 | 8 / 0 / 68% / 4 / 28% | 5 / 0 / 80% / 4 / 32% | 4 / 0 / 84% / 2 / 42% | 1 / 12 / 92% / 1 / 100% | 4 / 10 / 73% / 2 / 100% | 9 / 0 / 64% / 6 / 0% |
| 90 | 40.2 | 0 / 9 / 100% / 0 / 26% | 1 / 9 / 93% / 0 / 32% | 2 / 9 / 86% / 1 / 26% | 0 / 13 / 100% / 0 / 100% | 0 / 17 / 100% / 0 / 100% | 5 / 9 / 64% / 4 / 0% |
| 100 | 40.1 | 2 / 0 / 94% / 0 / 16% | 4 / 0 / 87% / 2 / 19% | 2 / 0 / 94% / 0 / 23% | 0 / 22 / 100% / 0 / 100% | 0 / 19 / 100% / 0 / 100% | 3 / 0 / 90% / 0 / 0% |
| 86 | 39.2 | 3 / 12 / 84% / 2 / 33% | 5 / 12 / 74% / 2 / 31% | 1 / 14 / 94% / 0 / 44% | 1 / 19 / 92% / 1 / 100% | 0 / 19 / 100% / 0 / 100% | 4 / 12 / 79% / 2 / 0% |
| 39 | 36.9 | 2 / 0 / 94% / 2 / 19% | 1 / 0 / 97% / 1 / 23% | 2 / 0 / 94% / 2 / 32% | 1 / 23 / 88% / 1 / 100% | 1 / 20 / 91% / 1 / 100% | 4 / 0 / 87% / 4 / 0% |
| 58 | 36.2 | 2 / 0 / 94% / 2 / 27% | 2 / 0 / 94% / 2 / 34% | 1 / 0 / 97% / 1 / 39% | 2 / 17 / 89% / 2 / 100% | 1 / 15 / 95% / 1 / 100% | 3 / 0 / 92% / 3 / 0% |
| 87 | 34.0 | 3 / 0 / 89% / 2 / 33% | 2 / 0 / 93% / 1 / 42% | 2 / 0 / 93% / 0 / 47% | 0 / 12 / 100% / 0 / 100% | 0 / 11 / 100% / 0 / 100% | 2 / 0 / 93% / 1 / 0% |
| 57 | 33.6 | 4 / 0 / 86% / 4 / 32% | 4 / 0 / 86% / 4 / 33% | 2 / 0 / 93% / 2 / 45% | 0 / 15 / 100% / 0 / 100% | 0 / 14 / 100% / 0 / 100% | 3 / 0 / 89% / 2 / 0% |
| 97 | 33.5 | 3 / 0 / 88% / 2 / 69% | 5 / 0 / 80% / 4 / 76% | 3 / 0 / 88% / 2 / 79% | 4 / 5 / 80% / 4 / 100% | 2 / 4 / 90% / 2 / 100% | 2 / 0 / 92% / 1 / 0% |
| 68 | 33.5 | 0 / 0 / 100% / 0 / 43% | 0 / 0 / 100% / 0 / 45% | 0 / 0 / 100% / 0 / 65% | 0 / 10 / 100% / 0 / 100% | 0 / 7 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 0% |

| variant | unfair decisions (sum) | undetermined (sum) | traps (sum) | fair share (mean) | levels with no unfair decision | deep blind losers (sum) | blind bits (mean) | random-sample share (mean) | seconds (sum) |
|---|---|---|---|---|---|---|---|---|---|
| belt 10 (main) | 51 | 22 | 31 | 91% | 5/20 | 361 | 10.5 | 30% | 2303 |
| belt 20 | 42 | 22 | 29 | 92% | 5/20 | 312 | 9.1 | 33% | 2479 |
| belt 30 | 32 | 24 | 20 | 94% | 5/20 | 251 | 7.2 | 38% | 2716 |
| belt 20 random only | 12 | 383 | 12 | 96% | 14/20 | 167 | 4.5 | 100% | 105 |
| belt 30 random only | 11 | 360 | 9 | 97% | 14/20 | 138 | 3.8 | 100% | 154 |
| chain x64 (burn-in 1024, every 256) | 77 | 22 | 46 | 86% | 3/20 | 425 | 12.7 | 0% | 9661 |

## A3. All variants, x1 base (hardest 20; chain x4 / x16 rows are chain-only)

#### Variants on the 20 levels with the most hidden bits

Each cell: unfair / undetermined / fair share / traps / random-sample share.

| L | hidden | belt 10 (main) | belt 20 | belt 30 | random only | chain only | chain x4 (burn-in 64, every 16) | chain x16 (burn-in 256, every 64) | belt 20 random only | belt 30 random only | H 20 | H 80 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 70 | 54.8 | 0 / 0 / 100% / 0 / 15% | 0 / 0 / 100% / 0 / 12% | 0 / 0 / 100% / 0 / 23% | 0 / 36 / 100% / 0 / 100% | 1 / 0 / 98% / 1 / 0% | 3 / 0 / 93% / 3 / 0% | 3 / 0 / 93% / 2 / 0% | 0 / 35 / 100% / 0 / 100% | 0 / 32 / 100% / 0 / 100% | 2 / 0 / 95% / 1 / 8% | 0 / 0 / 100% / 0 / 15% |
| 20 | 52.6 | 0 / 0 / 100% / 0 / 15% | 0 / 0 / 100% / 0 / 19% | 0 / 0 / 100% / 0 / 14% | 0 / 32 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 31 / 100% / 0 / 100% | 0 / 30 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 22% | 0 / 0 / 100% / 0 / 19% |
| 30 | 50.9 | 0 / 0 / 100% / 0 / 22% | 0 / 0 / 100% / 0 / 23% | 1 / 0 / 98% / 1 / 26% | 1 / 33 / 94% / 1 / 100% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 3 / 33 / 81% / 3 / 100% | 2 / 32 / 88% / 2 / 100% | 0 / 0 / 100% / 0 / 11% | 0 / 0 / 100% / 0 / 23% |
| 80 | 49.8 | 2 / 0 / 94% / 0 / 10% | 0 / 0 / 100% / 0 / 16% | 0 / 0 / 100% / 0 / 21% | 1 / 30 / 80% / 0 / 100% | 1 / 0 / 97% / 1 / 0% | 1 / 0 / 97% / 0 / 0% | 3 / 0 / 91% / 1 / 0% | 0 / 26 / 100% / 0 / 100% | 0 / 24 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 23% | 0 / 0 / 100% / 0 / 14% |
| 76 | 46.7 | 0 / 1 / 100% / 0 / 66% | 0 / 1 / 100% / 0 / 67% | 0 / 1 / 100% / 0 / 70% | 0 / 9 / 100% / 0 / 100% | 0 / 1 / 100% / 0 / 0% | 7 / 1 / 79% / 6 / 0% | 13 / 1 / 61% / 10 / 0% | 0 / 8 / 100% / 0 / 100% | 1 / 4 / 97% / 1 / 100% | 0 / 0 / 100% / 0 / 63% | 8 / 1 / 76% / 8 / 69% |
| 79 | 45.3 | 0 / 0 / 100% / 0 / 12% | 0 / 0 / 100% / 0 / 16% | 0 / 0 / 100% / 0 / 17% | 0 / 24 / 100% / 0 / 100% | 0 / 1 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 22 / 100% / 0 / 100% | 0 / 20 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 46% | 0 / 0 / 100% / 0 / 0% |
| 40 | 44.7 | 1 / 0 / 98% / 1 / 38% | 0 / 0 / 100% / 0 / 37% | 0 / 0 / 100% / 0 / 38% | 0 / 21 / 100% / 0 / 100% | 2 / 0 / 95% / 2 / 0% | 1 / 0 / 98% / 1 / 0% | 1 / 0 / 98% / 1 / 0% | 0 / 21 / 100% / 0 / 100% | 0 / 23 / 100% / 0 / 100% | 1 / 0 / 98% / 1 / 43% | 1 / 0 / 98% / 1 / 41% |
| 85 | 44.6 | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 0 / 34 / 100% / 0 / 100% | 1 / 0 / 97% / 0 / 0% | 1 / 0 / 97% / 0 / 0% | 2 / 0 / 94% / 1 / 0% | 0 / 34 / 100% / 0 / 100% | 0 / 34 / 100% / 0 / 100% | 0 / 1 / 100% / 0 / 3% | 1 / 0 / 97% / 0 / 3% |
| 27 | 42.4 | 1 / 0 / 96% / 1 / 68% | 1 / 0 / 96% / 0 / 70% | 0 / 0 / 100% / 0 / 78% | 1 / 5 / 94% / 1 / 100% | 3 / 0 / 87% / 3 / 0% | 2 / 0 / 91% / 2 / 0% | 2 / 0 / 91% / 1 / 0% | 0 / 3 / 100% / 0 / 100% | 0 / 3 / 100% / 0 / 100% | 1 / 0 / 96% / 1 / 55% | 1 / 0 / 96% / 1 / 69% |
| 10 | 41.9 | 5 / 0 / 84% / 4 / 22% | 3 / 0 / 91% / 2 / 23% | 0 / 1 / 100% / 0 / 25% | 0 / 23 / 100% / 0 / 100% | 5 / 0 / 84% / 4 / 0% | 5 / 0 / 84% / 3 / 0% | 4 / 0 / 88% / 2 / 0% | 0 / 22 / 100% / 0 / 100% | 0 / 22 / 100% / 0 / 100% | 4 / 0 / 88% / 4 / 26% | 5 / 0 / 84% / 3 / 26% |
| 72 | 41.0 | 2 / 0 / 92% / 2 / 28% | 1 / 0 / 96% / 1 / 32% | 1 / 0 / 96% / 1 / 42% | 3 / 13 / 75% / 2 / 100% | 0 / 0 / 100% / 0 / 0% | 4 / 0 / 84% / 3 / 0% | 6 / 0 / 76% / 2 / 0% | 1 / 12 / 92% / 1 / 100% | 4 / 10 / 73% / 2 / 100% | 0 / 0 / 100% / 0 / 19% | 1 / 0 / 96% / 0 / 28% |
| 90 | 40.2 | 0 / 9 / 100% / 0 / 26% | 0 / 9 / 100% / 0 / 32% | 0 / 9 / 100% / 0 / 26% | 1 / 14 / 89% / 0 / 100% | 0 / 9 / 100% / 0 / 0% | 0 / 9 / 100% / 0 / 0% | 2 / 9 / 86% / 1 / 0% | 0 / 13 / 100% / 0 / 100% | 0 / 17 / 100% / 0 / 100% | 0 / 9 / 100% / 0 / 25% | 0 / 9 / 100% / 0 / 26% |
| 100 | 40.1 | 0 / 0 / 100% / 0 / 16% | 1 / 0 / 97% / 0 / 19% | 0 / 0 / 100% / 0 / 23% | 0 / 23 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 0% | 2 / 0 / 94% / 0 / 0% | 4 / 0 / 87% / 2 / 0% | 0 / 22 / 100% / 0 / 100% | 0 / 19 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 29% | 0 / 0 / 100% / 0 / 20% |
| 86 | 39.2 | 1 / 13 / 94% / 0 / 34% | 1 / 14 / 94% / 1 / 35% | 0 / 15 / 100% / 0 / 47% | 2 / 19 / 83% / 2 / 100% | 1 / 13 / 94% / 1 / 0% | 2 / 12 / 89% / 1 / 0% | 3 / 12 / 84% / 2 / 0% | 1 / 19 / 92% / 1 / 100% | 0 / 19 / 100% / 0 / 100% | 3 / 14 / 82% / 3 / 74% | 3 / 12 / 84% / 2 / 29% |
| 39 | 36.9 | 2 / 0 / 94% / 2 / 19% | 1 / 0 / 97% / 1 / 23% | 1 / 0 / 97% / 1 / 32% | 2 / 25 / 67% / 2 / 100% | 3 / 0 / 90% / 3 / 0% | 3 / 0 / 90% / 3 / 0% | 4 / 0 / 87% / 4 / 0% | 1 / 23 / 88% / 1 / 100% | 1 / 20 / 91% / 1 / 100% | 1 / 0 / 97% / 1 / 7% | 3 / 0 / 90% / 3 / 20% |
| 58 | 36.2 | 0 / 0 / 100% / 0 / 27% | 0 / 0 / 100% / 0 / 34% | 1 / 0 / 97% / 1 / 39% | 3 / 19 / 82% / 3 / 100% | 0 / 0 / 100% / 0 / 0% | 4 / 0 / 89% / 4 / 0% | 4 / 0 / 89% / 4 / 0% | 2 / 17 / 89% / 2 / 100% | 1 / 15 / 95% / 1 / 100% | 1 / 0 / 97% / 1 / 44% | 1 / 0 / 97% / 1 / 16% |
| 87 | 34.0 | 1 / 0 / 96% / 0 / 33% | 0 / 0 / 100% / 0 / 42% | 0 / 0 / 100% / 0 / 47% | 0 / 13 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 1 / 0 / 96% / 1 / 0% | 0 / 12 / 100% / 0 / 100% | 0 / 11 / 100% / 0 / 100% | 2 / 0 / 93% / 2 / 23% | 1 / 0 / 96% / 1 / 33% |
| 57 | 33.6 | 4 / 0 / 86% / 3 / 32% | 1 / 0 / 96% / 0 / 33% | 2 / 0 / 93% / 1 / 45% | 1 / 15 / 92% / 1 / 100% | 3 / 0 / 89% / 2 / 0% | 4 / 0 / 86% / 4 / 0% | 6 / 0 / 79% / 5 / 0% | 0 / 15 / 100% / 0 / 100% | 0 / 14 / 100% / 0 / 100% | 1 / 0 / 96% / 0 / 33% | 2 / 0 / 93% / 1 / 33% |
| 97 | 33.5 | 2 / 0 / 92% / 2 / 69% | 5 / 0 / 80% / 4 / 76% | 2 / 0 / 92% / 2 / 79% | 3 / 5 / 85% / 2 / 100% | 3 / 0 / 88% / 3 / 0% | 1 / 0 / 96% / 1 / 0% | 2 / 0 / 92% / 2 / 0% | 4 / 5 / 80% / 4 / 100% | 2 / 4 / 90% / 2 / 100% | 1 / 0 / 96% / 1 / 74% | 2 / 0 / 92% / 2 / 58% |
| 68 | 33.5 | 0 / 0 / 100% / 0 / 43% | 1 / 0 / 97% / 1 / 45% | 0 / 0 / 100% / 0 / 65% | 0 / 11 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 0% | 0 / 0 / 100% / 0 / 0% | 1 / 0 / 97% / 0 / 0% | 0 / 10 / 100% / 0 / 100% | 0 / 7 / 100% / 0 / 100% | 0 / 0 / 100% / 0 / 47% | 0 / 0 / 100% / 0 / 34% |

| variant | unfair decisions (sum) | undetermined (sum) | traps (sum) | fair share (mean) | levels with no unfair decision | deep blind losers (sum) | blind bits (mean) | random-sample share (mean) | seconds (sum) |
|---|---|---|---|---|---|---|---|---|---|
| belt 10 (main) | 21 | 23 | 15 | 96% | 10/20 | 245 | 6.6 | 30% | 185 |
| belt 20 | 15 | 24 | 10 | 97% | 11/20 | 222 | 6.1 | 33% | 161 |
| belt 30 | 8 | 26 | 7 | 99% | 14/20 | 162 | 4.2 | 38% | 179 |
| random only | 18 | 404 | 14 | 92% | 10/20 | 195 | 5.4 | 100% | 54 |
| chain only | 23 | 24 | 20 | 96% | 10/20 | 190 | 5.3 | 0% | 129 |
| chain x4 (burn-in 64, every 16) | 40 | 22 | 31 | 93% | 6/20 | 282 | 7.9 | 0% | 412 |
| chain x16 (burn-in 256, every 64) | 61 | 22 | 41 | 89% | 3/20 | 366 | 10.5 | 0% | 1515 |
| belt 20 random only | 12 | 383 | 12 | 96% | 14/20 | 167 | 4.5 | 100% | 75 |
| belt 30 random only | 11 | 360 | 9 | 97% | 14/20 | 138 | 3.8 | 100% | 98 |
| H 20 | 17 | 24 | 15 | 97% | 10/20 | 244 | 6.8 | 34% | 138 |
| H 80 | 29 | 22 | 23 | 95% | 8/20 | 261 | 7.1 | 29% | 150 |
