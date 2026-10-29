package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.solver.Solver
import java.util.Random

/**
 * Fairness: can the player pick a winning move from what is on show? (levelgen/out/fairness-report.md)
 *
 * What the player sees: the board, the dock, the carousel (staging) and the next [shown] elements of the line (the
 * belt). The per-colour totals of everything not yet seen follow from the balance rule (elements == capacity per
 * colour), so the only hidden information is the ORDER of the queue beyond the belt (the hidden tail).
 *
 * Orderings of the hidden tail are sampled with a horizon H ([horizon]): the next H hidden elements are a uniform
 * random draw (without replacement, in random order) from the whole hidden multiset, and the elements not drawn keep
 * their real relative order after them. H >= the tail length gives a uniform permutation of the whole tail; a finite
 * H asks "would the move still be right if the near future beyond the belt were anything consistent with the colour
 * totals?" while the far future keeps its real shape.
 *
 * The player knows the level can be won and, on the winning line, has not lost yet. So only orderings under which the
 * state is still winnable count: up to [maxDraws] orderings are drawn and the first [samples] that the bounded solver
 * proves winnable are kept (rejection sampling from the winnable orderings). Uniform orderings are mostly unwinnable
 * (the generator places the queue precisely); without this conditioning every move would look equally bad. When the
 * winnable orderings are too rare for rejection, a Metropolis chain started at the real order fills up the samples:
 * a proposal swaps a hidden element within the horizon with any other hidden element of another colour and is
 * accepted when the state stays provably winnable; one sample every [spacing] proposals after [burnIn]. Those are
 * winnable orderings near the real one. Every legal move is then solved under each kept ordering (the same
 * orderings for every move of the state).
 *
 * Mechanics (v4): the sampled orderings run through [Engine] / [Solver], so frozen, tied, wide, colour-bench,
 * turntable and gate levels are measured under their rules. A [com.phloots.crittercafe.core.model.Container.hidden]
 * container's colour is also hidden from the player until its cover leaves; that information is NOT modelled here
 * (the board is treated as fully visible), so fairness on hidden-colour levels is an upper bound.
 *
 * @property shown elements of the line on show beyond the carousel; null (default): the level's own belt,
 *   [Level.queuePreview] (default [com.phloots.crittercafe.core.model.Limits.DEFAULT_QUEUE_PREVIEW] = RingField.QUEUE_MAX = 10)
 * @property horizon H, hidden elements re-drawn per sampled ordering ([Int.MAX_VALUE]: the whole tail)
 * @property samples kept (winnable) orderings of the hidden tail per decision state
 * @property maxDraws orderings drawn at most per decision state
 * @property minSamples a decision with fewer kept orderings is undetermined: the state is winnable only for rare
 *   orderings (reported apart, not judged)
 * @property budget node budget per child solve (an exhausted budget counts as a win, as in [Quality]); a sampled
 *   ordering is kept only when the state is proven winnable within [parentBudget]
 * @property robust a winning move is robust when it wins under at least this share of the kept orderings
 * @property visibleLoss a losing move is visibly losing when it loses under at least this share of the kept,
 *   decided orderings (orderings whose solve ran out of budget are left out of that share)
 * @property deep losers with horizon h > deep are deep (the player cannot see the loss by looking a few moves ahead)
 */
data class FairnessConfig(
    val shown: Int? = null,
    val horizon: Int = 40,
    val samples: Int = 16,
    val maxDraws: Int = 48,
    val burnIn: Int = 16,
    val spacing: Int = 4,
    val minSamples: Int = 4,
    val budget: Long = 20_000,
    val parentBudget: Long = 50_000,
    val hBudget: Int = 30_000,
    val robust: Double = 0.9,
    val visibleLoss: Double = 0.9,
    val deep: Int = 3,
    val seed: Long = 1L,
    /** Where the winnable orderings come from: [Sampling.HYBRID] (default), random draws only, or the chain only. */
    val sampling: Sampling = Sampling.HYBRID,
) {
    enum class Sampling { HYBRID, RANDOM, CHAIN }

    /** The belt length used for [level]: [shown], else the level's [Level.queuePreview]. */
    fun shownFor(level: Level): Int = shown ?: level.queuePreview

    companion object {
        /** RingField.QUEUE_MAX: the belt shows at most 10 plates by default (fewer on a narrow screen). */
        const val SHOWN_APP = Limits.DEFAULT_QUEUE_PREVIEW
        val DEFAULT = FairnessConfig()
    }
}

/**
 * One legal move at a state.
 * @property wins the move wins with the real queue (budget exhausted counts as a win, [unknown])
 * @property h loss horizon with the real queue (moves until every continuation has ended, [Quality.DEEP] beyond the
 *   cap); 0 for winning moves
 * @property sWin / [sLoss] / [sUnknown] outcomes over the sampled (winnable) orderings of the hidden tail
 */
data class MoveFairness(val containerId: Int, val wins: Boolean, val unknown: Boolean, val h: Int, val sWin: Int, val sLoss: Int, val sUnknown: Int) {
    val samples: Int get() = sWin + sLoss + sUnknown

    /**
     * p(m): the move's win probability for a player who knows only what is on show, estimated over the sampled
     * orderings plus the real one (budget-exhausted counts as a win).
     */
    val winShare: Double get() = (sWin + sUnknown + (if (wins) 1 else 0)).toDouble() / (samples + 1)

    /** Share of the decided orderings (sampled plus the real one) under which the move loses. */
    val lossShare: Double get() = (sLoss + (if (wins) 0 else 1)).toDouble() / (sWin + sLoss + 1)
}

/**
 * Fairness of one state of a line.
 *
 * A player who reads everything on show and knows the level is winnable rates each move by p(m)
 * ([MoveFairness.winShare]) and plays one of the moves with p >= [robust] (the robust set R). The decision is fair
 * when R is not empty and every move in R really wins; it is unfair when R is empty (nothing on show tells a good
 * move) or when R holds a real loser (a trap: a move that looks safe from everything on show and loses).
 *
 * @property hiddenTail queue elements beyond the shown belt (0: everything that matters is on show)
 * @property samples kept (winnable) orderings; [drawn] orderings drawn independently to find them (0 when the state
 *   was not sampled: not a decision, or a tail whose order cannot matter)
 */
data class FairState(
    val step: Int,
    val moves: List<MoveFairness>,
    val hiddenTail: Int,
    val samples: Int,
    val drawn: Int,
    /** Drawn orderings whose solve ran out of [FairnessConfig.parentBudget] (dropped like proven losses). */
    val drawnUnknown: Int,
    /** Kept orderings that came from the chain (stage 2), and the swaps the chain accepted. */
    val local: Int,
    val accepted: Int,
    val minSamples: Int,
    /** The state was sampled (a decision whose hidden tail can matter). */
    val sampled: Boolean,
    val freeDocks: Int,
    val robust: Double,
    val visibleLoss: Double,
    val deep: Int,
) {
    val b: Int get() = moves.size
    val w: Int get() = moves.count { it.wins }

    /**
     * ω: share of the independently drawn orderings under which the state is (provably) winnable: how much the state
     * depends on the hidden order at all (1 = not at all). Drawing stops at [samples] kept.
     */
    val winnable: Double get() = if (drawn == 0) 1.0 else (samples - local).toDouble() / drawn

    /** R: moves with p >= [robust]. */
    val robustSet: List<MoveFairness> get() = moves.filter { it.winShare >= robust - 1e-9 }

    /** Robustly winning moves: in R and winning with the real queue. */
    val wRobust: Int get() = robustSet.count { it.wins }

    /** Traps: real losers in R (they look safe from everything on show). */
    val traps: Int get() = robustSet.count { !it.wins }

    /** Best p of a real winner. */
    val bestShare: Double get() = moves.filter { it.wins }.maxOfOrNull { it.winShare } ?: 0.0

    /** Best p of a real loser (0 when there is none). */
    val bestLoserShare: Double get() = losers.maxOfOrNull { it.winShare } ?: 0.0

    /** Best winner's p minus best loser's p: negative when a loser looks better than every winner. */
    val margin: Double get() = bestShare - bestLoserShare

    val losers: List<MoveFairness> get() = moves.filter { !it.wins }

    /** The existing quality state (same budgets as [Quality.state]): hidden bits = bitsBeyond(1). */
    fun qstate(queueLeft: Int): QState = QState(step, b, w, moves.count { it.unknown }, losers.map { it.h }.toIntArray(), freeDocks, queueLeft)

    /** A choice: at least two legal moves. */
    val choice: Boolean get() = b >= 2

    /** A decision: some loser does not lose at once (h >= 2), i.e. the state carries hidden bits. */
    val decision: Boolean get() = b >= 2 && losers.any { it.h >= 2 }

    /**
     * A decision that cannot be judged: the hidden order is rigid there. Fewer than [minSamples] independent draws
     * leave the state winnable and the chain accepted fewer than [minSamples] swaps, so the samples would repeat the
     * real order.
     */
    val undetermined: Boolean get() = decision && sampled && samples - local < minSamples && (local == 0 || accepted < minSamples)

    /** Fair (see the class doc). Non-decisions are fair (every move that does not lose at once wins). */
    val fair: Boolean get() = !decision || (!undetermined && wRobust >= 1 && traps == 0)

    /** Judged and not fair. */
    val unfair: Boolean get() = decision && !undetermined && !fair

    /**
     * Losers whose loss does not follow from what is on show: they lose under fewer than [visibleLoss] of the
     * winnable orderings. Immediate losers (h = 1) and losers at unsampled or undetermined states never count.
     */
    fun hiddenLoser(m: MoveFairness): Boolean = !m.wins && m.h >= 2 && sampled && !undetermined && m.lossShare < visibleLoss - 1e-9

    val deepLosers: Int get() = losers.count { it.h > deep }
    val deepBlindLosers: Int get() = losers.count { it.h > deep && hiddenLoser(it) }
    val shallowBlindLosers: Int get() = losers.count { it.h in 2..deep && hiddenLoser(it) }

    /**
     * Blind bits: the decision information left for a player who reads everything on show perfectly. Visibly losing
     * moves are free to reject; real winners and hidden losers look alike: log2((w + hidden losers) / w).
     */
    val blindBits: Double get() {
        if (w <= 0) return 0.0
        val l = losers.count { hiddenLoser(it) }
        return QState.log2((w + l).toDouble() / w)
    }
}

/**
 * Fairness metrics along one line ([Fairness.measure]).
 */
data class FairnessMetrics(val states: List<FairState>, val quality: QualityMetrics, val shown: Int) {
    val decisions: List<FairState> get() = states.filter { it.decision }
    val choices: List<FairState> get() = states.filter { it.choice }
    val unfair: List<FairState> get() = states.filter { it.unfair }
    val undetermined: List<FairState> get() = states.filter { it.undetermined }

    /** Share of the judged (not undetermined) decisions that are fair (1 when there are none). */
    val fairShare: Double get() = decisions.filter { !it.undetermined }.let { d -> if (d.isEmpty()) 1.0 else d.count { it.fair }.toDouble() / d.size }

    /** Share of the kept orderings (over the sampled, judged decisions) that came from independent random draws. */
    val randomSampleShare: Double get() = states.filter { it.sampled && !it.undetermined }.let { d ->
        val n = d.sumOf { it.samples }
        if (n == 0) 1.0 else d.sumOf { it.samples - it.local }.toDouble() / n
    }

    /** Unfair decisions with no robust move, and with a trap (a real loser that looks robust). */
    val noRobust: Int get() = unfair.count { it.robustSet.isEmpty() }
    val trapDecisions: Int get() = unfair.count { it.traps > 0 }

    /** The worst judged decision: the lowest [FairState.margin]; ties: earliest. */
    val worst: FairState? get() = decisions.filter { !it.undetermined }.minByOrNull { it.margin }

    /** Mean ω over the decisions: how much the line's states depend on the hidden order at all. */
    val meanWinnable: Double get() = decisions.filter { it.drawn > 0 }.let { d -> if (d.isEmpty()) 1.0 else d.map { it.winnable }.average() }

    val firstUnfair: Int get() = unfair.firstOrNull()?.step ?: -1

    /** Options: share of choices with >= 2 winning moves, and the mean number of winning moves over choices. */
    val multiShare: Double get() = choices.let { c -> if (c.isEmpty()) 0.0 else c.count { it.w >= 2 }.toDouble() / c.size }
    val meanW: Double get() = choices.let { c -> if (c.isEmpty()) 0.0 else c.map { it.w }.average() }
    val multiRobustShare: Double get() = choices.let { c -> if (c.isEmpty()) 0.0 else c.count { it.wRobust >= 2 }.toDouble() / c.size }
    val meanWRobust: Double get() = choices.let { c -> if (c.isEmpty()) 0.0 else c.map { it.wRobust }.average() }

    /** Options at decisions only (states with hidden bits). */
    val decisionMultiShare: Double get() = decisions.let { c -> if (c.isEmpty()) 0.0 else c.count { it.w >= 2 }.toDouble() / c.size }

    val deepLosers: Int get() = states.sumOf { it.deepLosers }
    val deepBlindLosers: Int get() = states.sumOf { it.deepBlindLosers }
    val deepVisibleLosers: Int get() = deepLosers - deepBlindLosers
    val shallowBlindLosers: Int get() = states.sumOf { it.shallowBlindLosers }
    val blindBits: Double get() = states.sumOf { it.blindBits }

    /** Hidden bits at fair decisions: the hard-but-visible part of the difficulty. */
    val fairHidden: Double get() = states.filter { it.fair }.sumOf { st -> quality.states[st.step].bitsBeyond(1) }
}

/**
 * Release windows of the containers over many sampled winning lines ([Fairness.sampleLines]).
 * @property earliest / [latest] per container, the earliest and latest move index at which it was released
 * @property gap per container, the fewest moves (over the lines) between its release and the first state where an
 *   element of its colour is on show (carousel or belt); 0 = its colour was on show when it was released
 */
data class ReleaseWindows(val lines: Int, val moves: Int, val earliest: IntArray, val latest: IntArray, val gap: IntArray) {
    val width: IntArray get() = IntArray(earliest.size) { latest[it] - earliest[it] }
    val meanWidth: Double get() = if (lines == 0) 0.0 else width.average()

    /** Containers whose window is at most [narrow] moves wide. */
    fun narrow(narrow: Int = 2): Int = width.count { it <= narrow }

    /** Narrow containers that every sampled line released at least [minGap] moves before their colour was on show. */
    fun narrowBlind(narrow: Int = 2, minGap: Int = 5): Int = earliest.indices.count { width[it] <= narrow && gap[it] >= minGap }

    /** Mean (over move indices) number of containers whose window contains the index: an antichain-width proxy. */
    val meanConcurrency: Double get() = if (lines == 0) 0.0 else (0 until moves).map { t -> earliest.indices.count { earliest[it] <= t && t <= latest[it] } }.average()
    val minConcurrency: Int get() = if (lines == 0) 0 else (0 until moves).minOf { t -> earliest.indices.count { earliest[it] <= t && t <= latest[it] } }
}

object Fairness {
    /** [level] with the hidden tail (queue beyond index [from]) reordered by [rnd]. */
    fun shuffledTail(level: Level, from: Int, rnd: Random): Level {
        val q = level.queue.toIntArray()
        for (i in q.size - 1 downTo from + 1) {
            val j = from + rnd.nextInt(i - from + 1)
            val t = q[i]; q[i] = q[j]; q[j] = t
        }
        return level.copy(queue = q.toList())
    }

    /**
     * [level] with the hidden tail (queue from index [from]) re-drawn with horizon [h]: the next h elements are drawn
     * uniformly without replacement from the whole tail (in draw order), the rest keep their real relative order.
     */
    fun sampleTail(level: Level, from: Int, h: Int, rnd: Random): Level {
        val q = level.queue
        val n = q.size - from
        if (h >= n) return shuffledTail(level, from, rnd)
        val idx = IntArray(n) { it }
        for (k in 0 until h) { val j = k + rnd.nextInt(n - k); val t = idx[k]; idx[k] = idx[j]; idx[j] = t }
        val drawn = BooleanArray(n)
        val out = ArrayList<Int>(q.size)
        for (i in 0 until from) out += q[i]
        for (k in 0 until h) { out += q[from + idx[k]]; drawn[idx[k]] = true }
        for (i in 0 until n) if (!drawn[i]) out += q[from + i]
        return level.copy(queue = out)
    }

    /** True when reordering queue[from until size] can change nothing (fewer than two colours in it). */
    fun tailFixed(level: Level, from: Int): Boolean {
        if (from >= level.queue.size - 1) return true
        val c0 = level.queue[from]
        for (i in from + 1 until level.queue.size) if (level.queue[i] != c0) return false
        return true
    }

    private fun outcome(child: GameState, budget: Long): Int = when (child.status) {
        Status.WON -> 1
        Status.PLAYING -> when (Solver.solve(child, budget)?.solvable) { true -> 1; false -> 0; null -> 2 }
        else -> 0
    }

    /** Fairness of [s] (see [FairnessConfig]); [step] is its index on the line. */
    fun state(s: GameState, step: Int, cfg: FairnessConfig = FairnessConfig.DEFAULT): FairState {
        val level = s.level
        val legal = Engine.legalMoves(s)
        val from = minOf(level.queue.size, s.queueIndex + cfg.shownFor(level))
        val tail = level.queue.size - from
        // real queue
        val wins = BooleanArray(legal.size)
        val unk = BooleanArray(legal.size)
        val hs = IntArray(legal.size)
        for ((i, m) in legal.withIndex()) {
            val child = Engine.apply(s, m).state
            when (child.status) {
                Status.WON -> wins[i] = true
                Status.PLAYING -> {
                    if (legal.size < 2) { wins[i] = true; continue }
                    val r = Solver.solve(child, cfg.budget)
                    when {
                        r == null -> { wins[i] = true; unk[i] = true }
                        r.solvable -> wins[i] = true
                        else -> hs[i] = ReverseAnalysis.lossHorizon(child, Quality.H_CAP - 1, cfg.hBudget)?.plus(1) ?: Quality.DEEP
                    }
                }
                else -> hs[i] = 1
            }
        }
        val sw = IntArray(legal.size)
        val sl = IntArray(legal.size)
        val su = IntArray(legal.size)
        var kept = 0
        var drawn = 0
        var drawnUnknown = 0
        var local = 0
        var accepted = 0
        val sampled = legal.size >= 2 && legal.withIndex().any { (i, _) -> !wins[i] && hs[i] >= 2 } && !tailFixed(level, from)
        fun record(ps: GameState, first: Int?) {
            kept++
            for ((i, m) in legal.withIndex()) {
                if (m.containerId == first) { sw[i]++; continue }
                when (outcome(Engine.apply(ps, m).state, cfg.budget)) {
                    1 -> sw[i]++
                    0 -> sl[i]++
                    else -> su[i]++
                }
            }
        }
        if (sampled) {
            val rnd = Random(cfg.seed * 1_000_003L + step * 7919L + s.queueIndex)
            // 1. rejection: independent draws with horizon H, kept when provably winnable
            while (cfg.sampling != FairnessConfig.Sampling.CHAIN && kept < cfg.samples && drawn < cfg.maxDraws) {
                drawn++
                val ps = s.copy(level = sampleTail(level, from, cfg.horizon, rnd))
                val r = Solver.solve(ps, cfg.parentBudget)
                if (r == null) drawnUnknown++
                if (r == null || !r.solvable) continue
                record(ps, r.solution.firstOrNull()?.containerId)
            }
            // 2. too few: a Metropolis chain from the real order fills up. A proposal swaps a hidden element within
            // the horizon with any other hidden element of a different colour; it is accepted when the state stays
            // provably winnable.
            if (cfg.sampling != FairnessConfig.Sampling.RANDOM && kept < cfg.samples) {
                val q = level.queue.toIntArray()
                val n = q.size - from
                val near = minOf(n, cfg.horizon)
                var cur = s
                var curFirst: Int? = legal.withIndex().firstOrNull { (i, _) -> wins[i] && !unk[i] }?.value?.containerId
                var proposals = 0
                while (kept < cfg.samples) {
                    proposals++
                    val i = from + rnd.nextInt(near)
                    var j = from + rnd.nextInt(n)
                    var tries = 0
                    while (q[j] == q[i] && tries++ < 16) j = from + rnd.nextInt(n)
                    if (q[j] != q[i]) {
                        val t = q[i]; q[i] = q[j]; q[j] = t
                        val ps = s.copy(level = level.copy(queue = q.toList()))
                        val r = Solver.solve(ps, cfg.parentBudget)
                        if (r != null && r.solvable) { accepted++; cur = ps; curFirst = r.solution.firstOrNull()?.containerId }
                        else { q[j] = q[i]; q[i] = t }
                    }
                    if (proposals >= cfg.burnIn && (proposals - cfg.burnIn) % cfg.spacing == 0) { local++; record(cur, curFirst) }
                }
            }
        }
        val moves = legal.indices.map { i ->
            if (sampled) MoveFairness(legal[i].containerId, wins[i], unk[i], hs[i], sw[i], sl[i], su[i])
            // nothing hidden matters: the real outcome is the only outcome
            else MoveFairness(legal[i].containerId, wins[i], unk[i], hs[i], 0, 0, 0)
        }
        return FairState(step, moves, tail, kept, drawn, drawnUnknown, local, accepted, cfg.minSamples, sampled, Limits.DOCK_SLOTS - s.dockUsed,
            cfg.robust, cfg.visibleLoss, cfg.deep)
    }

    /**
     * Order dependence: of [draws] orderings of [s]'s hidden tail (beyond a belt of [shown], re-drawn with horizon
     * [h]; [Int.MAX_VALUE] = uniform permutations of the whole tail, unconditioned), how many leave the state proven
     * winnable, proven lost, or undecided within [budget]. (wins, losses, unknown).
     */
    fun orderDependence(s: GameState, shown: Int, h: Int, draws: Int, budget: Long = 50_000, seed: Long = 1L): Triple<Int, Int, Int> {
        val level = s.level
        val from = minOf(level.queue.size, s.queueIndex + shown)
        if (tailFixed(level, from)) {
            val r = Solver.solve(s, budget)
            return when (r?.solvable) { true -> Triple(draws, 0, 0); false -> Triple(0, draws, 0); null -> Triple(0, 0, draws) }
        }
        val rnd = Random(seed * 31L + s.queueIndex)
        var w = 0; var l = 0; var u = 0
        repeat(draws) {
            when (Solver.solve(s.copy(level = sampleTail(level, from, h, rnd)), budget)?.solvable) { true -> w++; false -> l++; null -> u++ }
        }
        return Triple(w, l, u)
    }

    /** Fairness and the existing quality metrics ([Quality.of]) along [line], in one pass. */
    fun measure(level: Level, line: List<Move>, cfg: FairnessConfig = FairnessConfig.DEFAULT): FairnessMetrics {
        val gs = ArrayList<GameState>()
        val fs = ArrayList<FairState>()
        var s = GameState.initial(level)
        for ((i, m) in line.withIndex()) {
            if (s.status != Status.PLAYING) break
            gs += s
            fs += state(s, i, cfg)
            s = Engine.apply(s, m).state
        }
        val qs = fs.mapIndexed { i, f -> f.qstate(gs[i].queueRemaining) }
        val shown = cfg.shownFor(level)
        return FairnessMetrics(fs, Quality.of(level, line, gs, qs, shown), shown)
    }

    /**
     * [n] winning lines found by a randomised walk: at each state the legal moves are tried in random order and the
     * first whose child the bounded solver does not prove lost is taken (the move the last solve's line starts with
     * is taken without a new solve). A walk that still ends lost (a budget-exhausted child that was lost) is
     * dropped; at most [3n] walks.
     */
    fun sampleLines(level: Level, n: Int, seed: Long = 1L, budget: Long = 20_000): List<List<Move>> {
        val rnd = Random(seed)
        val out = ArrayList<List<Move>>()
        var tries = 0
        while (out.size < n && tries < 3 * n) {
            tries++
            var s = GameState.initial(level)
            val line = ArrayList<Move>()
            var known: List<Move> = emptyList()
            while (s.status == Status.PLAYING) {
                val legal = Engine.legalMoves(s).toMutableList()
                if (legal.isEmpty()) break
                java.util.Collections.shuffle(legal, rnd)
                var next: GameState? = null
                for (m in legal) {
                    val child = Engine.apply(s, m).state
                    if (child.status == Status.WON) { next = child; line += m; break }
                    if (child.status != Status.PLAYING) continue
                    if (known.isNotEmpty() && known[0] == m) { known = known.drop(1); next = child; line += m; break }
                    val r = Solver.solve(child, budget)
                    if (r == null || r.solvable) { known = r?.solution ?: emptyList(); next = child; line += m; break }
                }
                s = next ?: break
            }
            if (s.status == Status.WON) out += line
        }
        return out
    }

    /** Release windows over [lines] (all winning, so all release every container once); [shown] as in [FairnessConfig]. */
    fun releaseWindows(level: Level, lines: List<List<Move>>, shown: Int = level.queuePreview): ReleaseWindows {
        val n = level.containers.size
        val earliest = IntArray(n) { Int.MAX_VALUE }
        val latest = IntArray(n) { -1 }
        val gap = IntArray(n) { Int.MAX_VALUE }
        val moves = lines.firstOrNull()?.size ?: 0
        for (line in lines) {
            val states = ArrayList<GameState>()
            var s = GameState.initial(level)
            for (m in line) { states += s; s = Engine.apply(s, m).state }
            states += s
            fun onShow(st: GameState, c: Int): Boolean {
                if (c in st.staging) return true
                for (j in st.queueIndex until minOf(level.queue.size, st.queueIndex + shown)) if (level.queue[j] == c) return true
                return false
            }
            for ((t, m) in line.withIndex()) {
                val id = m.containerId
                earliest[id] = minOf(earliest[id], t)
                latest[id] = maxOf(latest[id], t)
                val c = level.containers[id].color
                var g = 0
                while (t + g < states.size - 1 && !onShow(states[t + g], c)) g++
                gap[id] = minOf(gap[id], g)
            }
        }
        for (i in 0 until n) if (latest[i] < 0) { earliest[i] = 0; latest[i] = 0; gap[i] = 0 }
        return ReleaseWindows(lines.size, moves, earliest, latest, gap)
    }
}
