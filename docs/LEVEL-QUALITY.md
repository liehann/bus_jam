# Level quality: why the liked levels work

A short argument from the game's own mathematics, checked against every playtest verdict so far, and how it now
drives the generator (Hybrid preview 4). Numbers come from `levelgen/.../reverse/QualityReport.kt` (solver lines,
bounded solves) unless marked "intended line". Code: `core/.../generator/reverse/Quality.kt`,
`HybridGenerator.fitness4`, weights and floors in `HybridFitnessConfig.kt`.

## The labels, and what they allow

| level | verdict |
|---|---|
| Motif preview 2 L18 | liked: hard, restarted twice |
| Hybrid preview 2 L19 | liked: stumped twice; opening "find anything that works" |
| Hybrid preview 3 L10 | liked: failed once; "figured out the chain too late" |
| Reverse preview 1 L19, L20 | disliked: "easy" despite high dock pressure |
| Motif preview 1 L2 (and L3, same family) | disliked: "every vehicle filled up immediately" |
| Hybrid preview 3 (pack) | "the levels feel pretty good" |

Three liked levels and four disliked ones. Any model fitted to that would overfit, so nothing below is fitted: each
metric is derived from the rules first, then checked for whether it separates the two groups. Several candidates
separate seven points; the argument for the chosen ones is that they follow from the game's mechanics and also
explain the specific comments.

## 1. A decision is information; count only what the player cannot see

At a state with b legal moves of which w still win, a uniformly random move keeps the game winnable with
probability w/b. The decision carries log2(b/w) bits, and along a winning line the bits add up: 2^-bits estimates
the chance that a random player stays on a winning path.

Raw bits overcount. Half of all losing moves (48.5% of 15,329 over 124 levels) lose at once (horizon h = 1: docking
into the last slot when nothing matches). No player makes those. For a player who looks k moves ahead, losers with
h <= k are free to reject and the decision is worth log2((w + #losers with h > k) / w). With k = 1 this gives the
**hidden bits**; with k = 3 the **deep bits**. (w counts budget-exhausted solves as wins, so all counts are lower
bounds.)

| level | verdict | hidden bits | deep bits | hidden in 1st / 2nd / 3rd third | 1-ply random wins (MC) | mean free docks |
|---|---|---|---|---|---|---|
| Motif preview 2 L18 | + | **40.2** | 24.6 | 19.1 / 21.1 / 0.0 | 0/50 | 3.00 |
| Hybrid preview 2 L19 | + | **23.7** | 18.7 | 0.0 / 19.0 / 4.7 | 0/50 | 3.20 |
| Hybrid preview 3 L10 | + | **59.6** | 43.9 | 21.7 / 33.9 / 4.0 | 0/50 | 2.28 |
| Reverse preview 1 L19 | - | 4.2 | 3.2 | 0.0 / 2.2 / 2.0 | 8/50 | 3.32 |
| Reverse preview 1 L20 | - | 12.2 | 7.4 | 0.0 / 5.4 / 6.8 | 4/50 | 2.92 |
| Motif preview 1 L2 | - | 11.8 | 4.5 | 11.8 / 0.0 / 0.0 | 1/50 | 3.81 |
| Motif preview 1 L3 | - | 13.6 | 6.5 | 13.6 / 0.0 / 0.0 | 2/50 | 3.86 |

- Hidden bits separate the groups with a gap: liked 23.7-59.6, disliked 4.2-13.6. Deep bits too (18.7-43.9 vs
  3.2-7.4). Per move: 0.31-0.93 vs 0.08-0.23.
- Cross-check by simulation: over 124 levels the Spearman correlation between hidden bits and the Monte Carlo win
  rate of a random player who avoids immediate losses is -0.65. The single-line estimate 2^-hidden is pessimistic in
  absolute terms (Reverse preview 1 L19: 0.06 estimated, 0.16 simulated) because other winning paths exist; it ranks
  correctly.
- The bits explain "easy despite pressure": Reverse preview 1 L20 keeps 2-3 containers docked for 20 moves, but no
  decision in that stretch has a losing alternative (0 bits for 30 moves). Pressure without forks is just waiting.

## 2. Where the bits sit: the opening comment

The profile over thirds explains the opening verdicts. Hybrid preview 2 L19 carries no hidden bits in its first 30
moves: any move works, which is "find anything that works", and then 19 bits arrive at once ("easy until it
wasn't"). Pack-wide, Hybrid preview 2 had under one hidden bit in the first third of 19 of 23 levels and Reverse
preview 1 in 15 of 19; Hybrid preview 3 cut that to 5 of 23, which is the change that came with "the levels feel pretty good". The
converse also shows: Motif preview 1 L2/L3 have all their bits in the opening and none after; their total is what
fails them.

The last third is near zero everywhere (the endgame clears), so the climax lives in the middle third of a line.

## 3. Slack: where information can exist

Dock slots are the budget. Pooled over 7,112 states of six packs, the information per state depends on the free
docks before the move (live states, line not exhausted):

| free docks | states | raw bits / state | hidden bits / state | states with hidden bits |
|---|---|---|---|---|
| 1 | 1,000 | 2.19 | 0.10 | 9% |
| 2 | 667 | 1.33 | **1.33** | **74%** |
| 3 | 694 | 0.79 | 0.79 | 50% |
| 4 (empty) | 3,832 | 0.19 | 0.19 | 16% |

With one free dock almost every loss is immediate (visible); with an empty dock almost every move survives. Hidden
information lives at two free docks: two partly filled containers waiting, one slot to decide on. This is the
mechanism behind principle 14 (juggling with 2 docked). The level-wide mean occupancy says nothing (Spearman 0.05
with hidden bits over 124 levels; Reverse preview 1 L20 has more pressure than the liked Hybrid preview 2 L19):
slack matters at the decision moments, not on average.

Scarce key colour (principle 15): in the 8 states before the climax floods, the two scarce-key levels average 1.50
(HP2 L19) and 1.38 (HP3 L10) free docks; every disliked level is at 3.0-4.0. Motif preview 2 L18 (4.0) is liked
without it, so tight climax slack is the signature of the scarce-key structure, not a requirement for a good level.

## 4. Horizon and readability: the "too late" comment

A deep loser (h > 3) is a decision whose consequence the player cannot see by looking ahead; it can only be
planned from information on show. What is on show: the board, the 40 carousel seats and the next 20 of the line
(RingField). The climax chain is *on show* once >= 10 of its colour are on the carousel or in the shown line; its
**lead** is the number of moves between that and the flood, and the **blind share** is the share of deep bits
decided before it.

| level | lead | chain's blockers removed before it was on show | deep bits decided blind |
|---|---|---|---|
| Hybrid preview 3 L10 ("too late") | 4 moves | 79% | 42.3 of 43.9 |
| Hybrid preview 2 L19 | 27 moves | 50% | 0.0 of 18.7 |

L10's chain appeared four moves before it flooded, after most of the digging it needed: the right digging had to
be done blind. That matches the comment directly. It is one comment, so this is consistent with the data rather
than established by it.

## 5. What does not explain the labels

- Mean dock occupancy, or the share of states with a full-ish dock (see section 3).
- Bot outcomes: no naive or lookahead bot wins any labelled level; how early they lose overlaps (liked 0.54-0.84,
  disliked 0.40-0.71).
- Peak density of decisions (most hidden bits in any 10 moves): HP2 L19 13.2 vs Motif preview 1 L3 13.6.
- The preview-3 fitness separates, but HP2 L19 scores 2.2 against -17.5..-4.3, a thin margin.

## 6. What the data cannot show

- Seven labelled levels give gaps, not boundaries; the floors below sit inside those gaps by judgement.
- The liked levels cannot be ranked against each other (each was failed once or twice).
- Bits are measured for a random or k-ply player on solver lines; people prune moves by colour heuristics and
  follow other lines. The intended and solver lines of one level can differ by 2x in opening bits.
- Readability rests on one comment and a threshold (10 on show) chosen by judgement.
- More bits means harder, not necessarily more fun; the upper limit is unknown (59.6 was still liked).

## 7. What changed in the generator (Hybrid preview 4)

- **Fitness**: `fitness4 = fitness3 + 12·info + info·(5·opening + 6·readable + 3·slack)`, averaged over the
  intended and the solver line. info = min(1, hidden/40); opening = min(1, share of hidden bits in the first
  third / 0.2); readable = half lead (full at 12 moves), half (1 - blind share); slack = 1 at <= 1.5 free docks
  before the flood, 0 at >= 3. Layout axes scale with info: a level without decisions gains nothing from where they
  sit. All weights in `HybridFitnessConfig`; the preview-1..3 formulas read their weights from it unchanged.
- **Floors** (both lines averaged): hidden bits >= 3 / 8 / 15 / 24 by tier, opening share >= 0 / 0.08 / 0.10 /
  0.12; scarce-key levels: climax slack <= 2.5; heralded levels: lead >= 8 and blind share <= 0.6 (intended line).
- **Opening**: juggle filler share cut (0.25-0.5 -> 0.05-0.3) and juggle bonus 0.6 -> 1.5. Filler taps fill at once
  and carry no information; on Hybrid preview 3 L10's recipe this raised the median opening bits from 0 to 1-3.
- **Herald** (readable climax): on climax recipes ~10 elements of the climax colour arrive from about move 8 and
  wait on the carousel (a small squatter whose own containers are the climax run), and one small container of that
  colour is dug out mid-level as a foreshadowing instance. On L10's recipe it moves the median lead from 6 to 44
  moves and the median blind share to 0. Its effect on hidden bits is mixed (median over tries, intended line, with
  vs without: L10 recipe 9.7 vs 12.9, L19 8.9 vs 8.1, L20 17.1 vs 13.0); selection then keeps the best tries.
  L21 and L22 are left without a herald as a contrast (L22: lead 20, 85% of deep bits blind).

| (solver lines) | Hybrid preview 3 | Hybrid preview 4 |
|---|---|---|
| hidden bits, median / mean | 11.8 / 18.1 | 21.6 / 27.0 |
| levels >= 24 hidden bits | 5 / 23 | 11 / 23 |
| levels with < 1 hidden bit in the first third | 5 / 23 | 0 / 23 |
| hard levels (L10, L19-24): median hidden, median lead, deep bits decided blind (sum) | 28.5, 29, 57.8 | 49.6, 45, 31.1 |
| mean fitness4 | 30.1 | 42.5 |

Calibration (fitness4, solver lines): Motif preview 2 L18 45.2 vs the Hybrid preview 1 mean 24.1. HP2 L19 scores
14.7 overall but high on the axes the user praised (info 0.59 against <= 0.34 for every disliked level, readable
1.00, slack 1.00) and 0 on the opening it was criticised for; HP3 L10 scores 83.5 (info, opening and slack 1.00,
readable 0.18: the comment it got).

## 8. Ring size as a difficulty lever (playtest 2026-10-11)

Ring size test L2 is Hybrid preview 4 L5 (an early juggle level) replayed with 28 seats instead of 40. Label:
"+ hard, good balance: a few attempts, doable". Same layout and queue, solver lines (`QualityReport`, labelled set):

| | 40 seats (HP4 L5, not played) | 28 seats (liked) |
|---|---|---|
| hidden bits (all in the first third) | 3.9 | 11.0 |
| all bits | 19.0 | 39.8 |
| hard decisions | 1 | 7 |
| 2^-hidden (random line survives the hidden losses) | 7e-2 | 5e-4 |
| free docks (mean) | 2.14 | 2.03 |
| fitness4 | 13.4 | 27.9 |

The smaller ring nearly triples the hidden bits of a 37-move level without changing the layout: with 12 fewer seats
a wrong release fills the carousel sooner, so more early choices become losses beyond the next move. Per move it
sits with the liked HP2 L19 (0.30 vs 0.31 hidden bits per move) though its total is the lowest of the liked levels:
a short level can be "hard but doable" at ~11 hidden bits. Proposal, not built: let the generator choose the ring
size per level (20-40) as a dial towards a tier's hidden-bit target, after the layout is fixed.
