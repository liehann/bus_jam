# Research: level structure, difficulty and generation for "Bus Out"-style puzzles

Scope: commercial genre conventions, designer guidance, academic work on puzzle generation and difficulty estimation, and recommendations for our generator. Labels used below: **[F]** = stated by a cited source; **[I]** = our inference.

Public data on this genre is thin. Store pages, help centres and a few market write-ups exist; no developer postmortem for Bus Jam / Bus Out / Car Out / Bus Fever was found. Level-structure numbers for these games are mostly not published, so section 1 is short on hard figures.

---

## 1. How commercial games structure levels

### 1.1 Two different sub-genres share the name
- **Rollic's Bus Jam** (released 26 Jun 2023; ~14.6M downloads, ~$7M revenue by late 2024 [F, costcenter]) has the *player move passengers*: tapping a stickman on a grid sends it to the bus at the front of a bus queue; mismatched passengers wait on a small set of empty tiles; "You are going to fail if there are no empty tiles" [F, Rollic help]. Buses arrive in a fixed sequence.
- **Bus Out / Bus Fever / Car Out / Car Jam** (our model) have the *player move containers*: vehicles drive out along their facing direction, park in a limited slot row, and a passenger queue boards matching colours. Bus Out's store page: "guide buses in the direction their arrows indicate… parking slots are limited, so you must clear space strategically" [F]. Bus Fever (Microjoy, launched Sep 2024, 4.7★/~42–48K ratings) starts with **4 open parking slots**, extra slots purchasable per level only [F, App Store review quoted on store page and game-solver].
- A market analysis frames the "Sort & Jam" genre as three elements: untouchable *items*, movable *containers* that must collect N same-coloured items, and *slots* to park incomplete containers. It descends from Parking Jam 3D (Popcore, 2020, 215M+ downloads) and Block Jam 3D (Voodoo, 2022, $30M+ IAP). Park Match! (Apr 2024) is cited as an early full example; a wave of clones (Bus Out, Bus Sort, Bus Escape, Car Jam, Bus Frenzy, Boat Out…) followed in Aug–Oct 2024 [F, costcenter].
- Deconstructor of Fun calls Rollic's family (Twisted Tangle, Seat Away, Screw Jam, Color Block Jam) the **"static levels"** recipe: "the level gives you the full problem set upfront"; players solve mini-puzzles inside the board ("clearing a blocked pocket, freeing a trapped area, opening space in a set order"); later generations add obstacles/boosters for "sharper difficulty spikes" that create "clearer fail moments and reasons to buy help" [F, DoF 2026].

### 1.2 Special mechanics (fullest public list: Rollic Bus Jam)
Rollic's help centre lists 16 obstacles [F]: Chain (needs matching key carrier), Pin (blocks until those in front leave), Reserved "R" passengers (only flagged buses), **Secret passengers** (hidden until neighbours are cleared), Cloaks (tap toggles visibility), **Tunnel** (releases N passengers of unknown colour one at a time), **Glass tunnel** (next colour visible), **Ice** (thaws after 4 moves, needs an open side), **Bomb** (countdown, lose if not moved), Ropes, Holding hands (multi-slot linked passengers), Bridge, Locked area (opens after N same-colour sends, reveals more content), Slinky, Ivy (countdown that covers same-colour stickmen), Conveyor.

Container-side games advertise "special vehicles", "conveyor belts", "extra routes", "dynamic passenger flows", "attempt limits" [F, Playturbo] and VIP parking / queue sort / passenger sort boosters [F, CrazyGames Car OUT!]. We found no public description of mystery-colour vehicles in container-side games, though hidden-information obstacles are standard in the Rollic family [I: hidden-colour containers and "tunnels" that spawn containers would transfer directly].

**Pattern [I]:** obstacles fall into four kinds: (a) hidden information (secret, tunnel, cloak), (b) delayed availability (ice, locked area, chain/key), (c) time pressure in moves (bomb, ivy), (d) coupling (holding hands, conveyor). (a) and (b) fit our model without changing the core rules.

### 1.3 Difficulty tiers and pacing
- Rollic: "there are hard and very hard levels", and "every level [is] designed to be completed without using boosters or purchasing coins" [F]. App Store reviews complain about Hard/Super Hard levels and coin pressure [F].
- Bus Fever reviewers: "difficulty ramps up at a gradual pace"; others hit walls at level 27 and at "level 124 (hard)", where they ran out of slots even after buying them [F]. Ads intensify after ~level 70–100 [F, reviews].
- Candy Crush fan-wiki episode pages show mixed tiers within 15-level episodes (e.g. one episode with 4 hard, 4 extremely hard, 1 nearly impossible) [F, community data]. Practitioner posts recommend a **sawtooth** curve with spikes every 5–10 levels and new mechanics introduced at low stakes before being combined [F, informal blogs]. An evolutionary word-puzzle generator (ELIMINATION) was built to produce a sawtooth curve and ~1000 players' traces confirmed it [F].
- Level counts: Bus Jam "hundreds of levels" [F]; Royal Match 12,400+ levels, new ones every two weeks [F, Wikipedia]; Candy Crush 15,000+ [F, GDC session title].

### 1.4 What the market says about pressure
Playturbo (ad-creative guidance for car-jam games) describes the core tension: "each move either preserves or limits the options available for the next move"; a vehicle that cannot immediately load "occupies a slot and reduces the margin for error"; tension is shown as the parking area filling, or ending "with one slot left"; good openings pair "one or two clearly movable vehicles with one plausible but poorly timed option" [F]. This matches the user's playtest observation that pressure comes from a near-full dock and partly filled containers.

---

## 2. What makes a level good vs trivial or unfair

**King (Candy Crush), from GDC talks reported by mobilegamer.biz and Pocket Gamer.biz [F]:**
- "Disentangle fun from difficulty." Churn rises with difficulty, so churn alone cannot flag a bad level. King measures fun with *time to abandon* and *time to pass*, and "puzzliness": the least engaging levels were the harder **skill/luck-based** ones.
- "Crazy hard levels never pay off, at least in the long term." "Designing for averages is not a good idea." "Retention always wins." Making the famous level 65 easier lowered conversion but improved long-term retention.
- "The harder the level, the shorter it should be."
- King periodically finds the 100 least-fun levels and fixes them; this gave a significant engagement uplift.
- Four level pillars: difficulty, **rhythm** (vary the experience), flow, **hooks** (each level distinct, often a twist on an existing mechanic). Designers' intuition is often wrong: "most often it's not [awesome]".

**Puzzle-design tradition:**
- Jonathan Blow (via Game Maker's Toolkit): good puzzles make the obvious approach fail so the player must understand the underlying idea; show *why* something doesn't work; reuse layouts with a changed rule [F].
- Smith, Butler & Popović (Refraction): generated puzzles must not admit **shortcut solutions** that bypass the intended concept; they constrain the generator to forbid them [F].
- Rush Hour enumeration (Fogleman): a puzzle is "interesting" only if it is *minimal* (removing any piece changes the solution) and the start is the farthest state in its cluster [F]. **[I]** In our terms: every blocker should matter; filler containers that never interact are noise.
- Near-miss: near-miss losses produce more urge to replay than large losses (small video-game experiment, n=40); a Wordle study found near-misses raise motivation [F]. **[I]** Losses should usually happen with 1–3 containers left, not at move 5.

**Our synthesis [I]:** trivial = many safe moves at every step, containers fill on arrival, no blocker chains. Unfair = the loss is decided by hidden information or an early move whose consequence is invisible for 20 moves. Good = a small number of **critical decisions** per level, each readable from the board (visible blocker chain, visible queue head), with a plausible wrong option next to the right one.

---

## 3. Academic and technical literature

### 3.1 Static metrics are weak predictors of human difficulty
Jarušek & Pelánek (Sokoban, 2,000 solved problems, 785 hours of human data) [F]:

| Metric | Spearman ρ vs median human solve time |
|---|---|
| State-space size | −0.07 (not significant) |
| Shortest solution length | 0.47 |
| Counter-intuitive moves (moving a box *away* from goal) | 0.69 |
| Problem decomposition ("2-decomposition") | **0.82** |
| Simulated human traversal model | 0.66 |

Their follow-up on transport puzzles: difficulty is "caused mainly by the structure of a problem's state space and cannot be easily captured by static metrics like size of the state space or the length of a solution" [F]. Fogleman likewise finds Rush Hour cluster size does not track solution length; the hardest 0-wall puzzle needs 51 moves in a 4,780-state cluster, while the largest cluster (541,934 states) needs only 15 [F].

**[I] Translation:** counter-intuitive moves ≈ "having to tap a container whose colour is not wanted yet" (park a blocker in the dock); decomposition ≈ "how many independent sub-problems" (a board that splits into separate regions with no shared colours is easy).

### 3.2 Generation
- **Exhaustive / reverse enumeration** (Rush Hour): enumerate states, BFS from goal, keep farthest states; earlier simulated annealing on solution length was "unsatisfying" [F, Fogleman]. Collette, Raskin & Servais computed the hardest Rush Hour configurations symbolically (BDDs) [F].
- **Backward search from goal states**: Bento, Pereira & Lelis (IJCAI 2019) generate hard, solvable Sokoban starts by searching backward and selecting states with pattern-database hardness metrics [F].
- **Constructive + solver check**: Taylor & Parberry generate guaranteed-solvable Sokoban levels offline [F]; water-sort hobby generators use generate-then-solve-with-budget loops [F].
- **MCTS as generator**: Kartal, Sohre & Guy (AIIDE 2016) treat level creation as MCTS over *generation actions* with simulated play, guaranteeing solvability; their score is a weighted combination of cheap features fitted from a user study (box path congestion, terrain), and scores correlated with perceived difficulty in a second study [F].
- **Answer set programming** (Refraction): declarative constraints on solutions, including forbidding shortcuts [F].
- **Search-based PCG** (Shaker, Togelius & Nelson, *PCG in Games*, free at pcgbook.com): evolutionary/optimisation over content with simulation-based fitness [F].

### 3.3 Estimating difficulty with simulated players
- **King**: imitation-learned CNN agent trained on human moves predicts level difficulty better than MCTS and much faster (Gudmundsson et al. 2018) [F]; an agent-strategy thesis improved success-rate prediction MAE by 13% [F]. Secondary reports: difficulty in under a minute vs 7 days of human playtesting per 15-level episode [F, secondary].
- **Rovio / Aalto** (Roohi et al., CHI PLAY 2021, Angry Birds Dream Blast): AI pass rate is the strongest single predictor of human pass rate (ρ = 0.80); combining DRL with MCTS helped most on the **hardest levels**; features from an agent's **best runs** (e.g. top 5%) sometimes correlate better than averages; a population model maps agent features to human pass/churn rates [F].
- **Procedural personas** (Holmgård, Liapis, Togelius, Yannakakis): agents with different utility functions stand in for player archetypes; MCTS with evolved selection heuristics as playtesters [F].
- **Survival analysis** (Isaksen et al.): Monte-Carlo play with a parameterised human-skill model gives a difficulty curve over game parameters [F].

**[I] Key point:** none of these use a perfect solver as the difficulty estimate. They use a *population of imperfect agents* and calibrate against human data.

---

## 4. Recommendations for our generator (ranked by expected impact)

### R1. Measure pressure along a *plausible-player* trajectory, not along the optimal solution (highest impact)
The user's metrics (dock occupancy 3–4, containers waiting partly filled, 1–2 safe moves) are trajectory properties. Run a family of imperfect agents (below) and record per step [I]:
- `dockOcc`: mean and fraction of steps with ≥3 docked; target roughly ≥3 on 30–50% of steps for hard levels.
- `safeMoves`: number of tappable containers whose move does not lead to a forced loss within k plies (k≈3–6, via shallow search). Target: ≥3–5 "tight" steps (safeMoves ≤ 2) spread across the level, not just at the end.
- `waitFill`: dock-steps a container spends partly filled; `instantFillRate`: fraction of containers fully loaded on arrival. **Penalise high instant-fill** directly; this is the triviality the user reports.
- `criticalDecisions`: steps where the best and second-best moves differ in win probability by > Δ (e.g. 0.3).
Use these as the difficulty and quality signal; use solution length and state count only as sanity checks (they correlate poorly with human difficulty, §3.1).

### R2. Calibrate with a persona population and a win-rate target band
Agents [I]: (a) *greedy*: tap any colour that matches the queue/staging head, else random free container; (b) *greedy+lookahead-1/2*; (c) *planner*: MCTS or beam search with a budget; (d) *random-legal* as a floor. Run each N=100–500 times per level (seeded). Accept a level when: planner win rate ≈ 100% (solvable, ideally proven by exhaustive/IDA* search with dock-state memo), lookahead-2 win rate in a **tier band** (e.g. normal 60–85%, hard 30–55%, super hard 15–30%), greedy ≤ upper bound (a greedy-solvable level is trivial). Losses should be near-misses (median ≤ 3 containers left). Once real players exist, regress human pass rate on agent features, following Roohi et al. and King, and re-fit the bands.

### R3. Construct by reverse play with set pieces, then optimise
1. **Plan the solution first** [I]: choose an intended exit order of containers and a queue that, played along that order, keeps the dock at 2–4 and makes several containers wait partly filled (the queue interleaves colours so a container docks before its passengers arrive).
2. **Place containers backward** (reverse-search idea, §3.2): insert containers in reverse exit order so each one, when placed, blocks the exit path of earlier-exiting containers → blocker chains by construction. Chain depth is a direct knob (1–10).
3. **Set pieces / motifs** stamped into the board [I]: *keystone* (one container blocks 3+ chains), *decoy* (two same-colour containers, the reachable one is the wrong size for what's waiting), *dense core* (a 3×3–4×4 interlocked knot with ≤2 valid unpacking orders), *slow filler* (a capacity-10 container whose colour arrives late in the queue, visibly hard to fill). The Rollic "mini puzzles inside the board" pattern [F, DoF] supports this.
4. **Optimise** with hill-climbing or a (μ+λ) evolutionary loop: mutations = swap two containers' colours, swap queue runs, rotate/move a container, change a length; fitness = distance from tier targets of R1/R2 metrics + minimality penalty. Fogleman found annealing on solution length unsatisfying [F]; a multi-objective fitness on trajectory metrics avoids that.

### R4. Enforce minimality and no shortcuts
After generation, try removing each container (and redistributing its passengers) and re-score; if the level's metrics barely change, that container is filler (Fogleman's minimality [F]). Check that greedy play does not win via a shortcut (Refraction's shortcut constraint [F]). Penalise boards that decompose into independent regions (Pelánek's decomposition [F]): require colour sharing across regions.

### R5. Queue composition as a primary knob
**[I]** Queue order controls pressure more than board layout does. Long same-colour runs → instant fills → trivial. Interleaving with a controlled "lag" (the passengers for a container arrive k slots after its natural exit time) produces waiting containers. Parameterise per level: run-length distribution, colour lag, and number of colours active in a window of ~8 queue items. With a 40-spot staging area, staging absorbs a lot of mismatch; pressure must come from the 4-slot dock, so the queue should often make the *needed* container one that is deep in a blocker chain.

### R6. Pace a sawtooth across hundreds of levels
**[I, from §1.3 and King]**: cycles of ~10 levels: 6–7 normal, 1–2 hard, 1 super hard (super-hard shorter, per King's "harder → shorter"), followed by an easy "breather". Introduce each new mechanic on an easy level, combine it with old ones 5–10 levels later. Board size, colour count and container count ramp slowly over the first ~100 levels, then plateau; difficulty after that comes from structure (chain depth, queue lag, dense cores), not just size. Keep a "hook" per level (one motif that defines it). Track the least-fun levels (fast abandon, many retries) and regenerate them, as King does.

### R7. Mechanics to add later, in order of fit
**[I]** (1) hidden-colour containers revealed when a neighbour leaves (Rollic's secret passengers [F]); (2) locked/ice containers that free after N departures (Rollic ice/locked area [F]); (3) tunnel/garage cells that spawn a sequence of containers, glass variant shows the next one [F analogue]; (4) reserved/VIP colours. Hidden information makes solver-proven solvability depend on unknown content; generate the hidden contents up front and keep the agents unaware of them for difficulty estimation, while the planner verifies solvability with full knowledge.

---

## Sources

Commercial / market
- Rollic Bus Jam App Store: https://apps.apple.com/us/app/6450176534
- Rollic help: How to play: https://rollic.helpshift.com/hc/en/13-bus-jam/faq/331-how-to-play-bus-jam/
- Rollic help: Obstacles: https://rollic.helpshift.com/hc/en/13-bus-jam/faq/562-what-are-the-obstacles/?p=ios
- Rollic help: "The level is too difficult": https://rollic.helpshift.com/hc/en/13-bus-jam/faq/335-the-level-is-too-difficult-there-is-no-solution/?p=all
- Bus Out App Store: https://apps.apple.com/us/app/-/id6737303138
- Bus Fever (Microjoy) App Store: https://apps.apple.com/us/app/-/id6670540076 ; game-solver page: https://game-solver.com/bus-fever-car-parking-jam/
- Car OUT! (CrazyGames): https://www.crazygames.com/ru/game/car-out-jam-parking-puzzle
- Sort & Jam competitor analysis (costcenter): https://costcenter.net/post/sort-jam-competitor-analysis
- Deconstructor of Fun, Post-Block Blast Playbook: https://www.deconstructoroffun.com/blog/2026/1/19/from-tetris-to-block-blast-why-block-puzzles-never-stop-printing
- Playturbo, Car Jam creative best practices: https://www.playturbo.com/blog/car-jam-games-creative-best-practices-2026
- Royal Match (Wikipedia): https://en.wikipedia.org/wiki/Royal_Match
- Candy Crush difficulty (fan wiki): https://candycrush.fandom.com/wiki/Difficulty

Design
- mobilegamer.biz, How King defines a good Candy Crush level: https://mobilegamer.biz/how-king-defines-a-good-candy-crush-saga-level-and-why-it-constantly-prunes-the-bad-ones/
- Pocket Gamer.biz, King on finding the fun: http://www.pocketgamer.biz/king-on-finding-the-fun-in-candy-crush/
- GDC: Sugary Statistics (King): https://www.gdcvault.com/play/1034392/Sugary-Statistics-What-We-Have ; Blockers talk: https://gdconf.com/news/come-gdc-summer-and-learn-kings-new-method-balancing-match-3-game-difficulty
- GMTK, How Jonathan Blow Designs a Puzzle (transcript): https://amara.org/videos/LaGKzzSXnuQl/en-gb/3101350
- Smith, Butler, Popović, Quantifying over Play (Refraction): https://grail.cs.washington.edu/wp-content/uploads/2015/08/smith2013qop.pdf ; summary: https://www.gamedeveloper.com/business/the-saturday-paper-goldilocks-and-the-three-puzzles
- ELIMINATION sawtooth curve: https://www.um.edu.mt/library/oar/handle/123456789/82109
- Near-miss video game experiment: https://bora.uib.no/bora-xmlui/handle/1956/23601 ; Wordle near-miss: https://doi.org/10.1038/s41598-024-74450-0

Academic / technical
- Fogleman, Solving Rush Hour: https://michaelfogleman.com/rush
- Collette, Raskin, Servais, hardest Rush Hour configurations (CG 2006), DOI 10.1007/978-3-540-75538-8_20
- Rush Hour (Wikipedia): https://en.wikipedia.org/wiki/Rush_Hour_(puzzle)
- Jarušek & Pelánek, Difficulty Rating of Sokoban Puzzle: https://www.fi.muni.cz/~xpelanek/publications/stairs2010-final.pdf
- Pelánek & Jarušek, What Determines Difficulty of Transport Puzzles?: https://is.muni.cz/publication/941424
- Kartal, Sohre, Guy, Data-Driven Sokoban Generation with MCTS: https://ojs.aaai.org/index.php/AIIDE/article/view/12859
- Bento, Pereira, Lelis, Procedural Generation of Initial States of Sokoban: https://arxiv.org/pdf/1907.02548
- Taylor & Parberry, Procedural Generation of Sokoban Levels: https://ianparberry.com/pubs/GAMEON-NA_METH_03.pdf
- Gudmundsson et al., Human-Like Playtesting with Deep Learning (King): https://www.gwern.net/doc/reinforcement-learning/imitation-learning/2018-gudmundsson.pdf
- Anghileri thesis (Candy Crush agent strategies): https://www.politesi.polimi.it/handle/10589/141799
- Roohi et al., Predicting Game Difficulty and Engagement Using AI Players: https://arxiv.org/pdf/2107.12061
- Holmgård et al., Automated playtesting with procedural personas: https://antoniosliapis.com/papers/automated_playtesting_with_procedural_personas_through_mcts_with_evolved_heuristics.pdf
- Isaksen et al., Exploring Game Space via Survival Analysis: https://nealen.net/papers.htm
- Shaker, Togelius, Nelson, Procedural Content Generation in Games: https://link.springer.com/978-3-319-42714-0 (chapters at pcgbook.com)
