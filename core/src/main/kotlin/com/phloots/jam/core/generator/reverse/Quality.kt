package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver

/**
 * One state of a winning line, solved one ply deep (docs/LEVEL-QUALITY.md).
 * @property b legal moves; [w] winning moves (a child whose bounded solve runs out of budget counts as winning, so
 *   [w] is an upper bound and every bit count below is a lower bound); [unknown] such children
 * @property loserH per proven-losing move, its loss horizon: the moves (the bad one included) until every
 *   continuation has ended in DEADLOCK/STUCK; [Quality.DEEP] when that takes more than [Quality.H_CAP] moves
 * @property freeDocks free dock slots; [queueLeft] elements not yet on the carousel
 */
data class QState(val step: Int, val b: Int, val w: Int, val unknown: Int, val loserH: IntArray, val freeDocks: Int, val queueLeft: Int) {
    /** Information in the decision: log2(b / w). A uniformly random move keeps a win with probability w / b. */
    val bits: Double get() = if (b <= 1 || w <= 0) 0.0 else log2(b.toDouble() / w)

    /**
     * Bits left for a player who sees [k] moves ahead: losing moves whose loss shows within k moves are discarded,
     * the rest look as good as the winning ones: log2((w + #losers with h > k) / w).
     */
    fun bitsBeyond(k: Int): Double {
        if (w <= 0) return 0.0
        val l = loserH.count { it > k }
        return log2((w + l).toDouble() / w)
    }

    companion object {
        fun log2(x: Double): Double = StrictMath.log(x) / StrictMath.log(2.0)
    }
}

/**
 * Quality metrics of a level along a winning line (docs/LEVEL-QUALITY.md). The decision information is
 * bits = Σ log2(b / w); a player who sees k moves ahead faces bitsBeyond(k) (losers visible within k are free).
 * [hidden] = Σ bitsBeyond(1) (a bad move does not lose at once), [deep] = Σ bitsBeyond(3).
 * 2^-[bits] approximates the chance that a uniformly random player stays on a winning path.
 *
 * Chain (the climax, principle 15): the colour whose staged count peaks highest; [chainAt] the first state where it
 * floods the carousel (staged >= max(min(15, peak), 60% of peak)); [chainVisibleAt] the first state where at least [Quality.VISIBLE]
 * chain elements are on the carousel or in the shown part of the line ([Quality.SHOWN_QUEUE], RingField's queue
 * preview); [digShareBeforeVisible] = share of the chain's blocker containers (initial board, transitive) that the
 * line had already removed when the chain became visible (high = the player had to dig blind).
 * [climaxSlack] = mean free docks over the [Quality.CLIMAX_WINDOW] states before [chainAt].
 */
data class QualityMetrics(
    val states: List<QState>,
    val moves: Int,
    val bits: Double,
    val hidden: Double,
    val deep: Double,
    /** [hidden] per third of the line. */
    val hiddenThirds: DoubleArray,
    /** Largest [hidden] over any [Quality.PEAK_WINDOW] consecutive states and where that window starts. */
    val peakHidden: Double,
    val peakAt: Int,
    /** States with bitsBeyond(1) >= 1: at least half the moves that look fine lose. */
    val hardDecisions: Int,
    val meanFreeDocks: Double,
    val tightShare: Double,
    val chainColor: Int,
    val chainAt: Int,
    val chainLen: Int,
    val chainVisibleAt: Int,
    val digShareBeforeVisible: Double,
    val climaxSlack: Double,
    /** [hidden] in the states before [chainVisibleAt] whose losers are deep (h > 3): blind deep decisions. */
    val blindDeep: Double,
    /** [hidden] from [chainVisibleAt] to the end of the chain: informed climax decisions. */
    val climaxHidden: Double,
) {
    val pRandom: Double get() = StrictMath.pow(2.0, -bits)
    val pOnePly: Double get() = StrictMath.pow(2.0, -hidden)

    /** Hidden bits per move in the opening third (the "find anything that works" check). */
    val openingRate: Double get() = hiddenThirds[0] / maxOf(1.0, moves / 3.0)

    /** Lead between seeing the chain and its flood (moves); negative = never visible before the flood. */
    val chainLead: Int get() = if (chainAt < 0 || chainVisibleAt < 0) -1 else chainAt - chainVisibleAt

    fun summary(): String = ("bits %.1f hidden %.1f deep %.1f (thirds %.1f/%.1f/%.1f, peak10 %.1f@%d, hard %d) p1=%.3f | " +
        "free docks %.2f tight %.0f%% | chain %s x%d @%d seen @%d (lead %d, dug %.0f%% blind) climax slack %.2f, blind deep %.1f, climax hidden %.1f").format(
        bits, hidden, deep, hiddenThirds[0], hiddenThirds[1], hiddenThirds[2], peakHidden, peakAt, hardDecisions, pOnePly,
        meanFreeDocks, tightShare * 100, StallLevel.colorName(chainColor), chainLen, chainAt, chainVisibleAt, chainLead,
        digShareBeforeVisible * 100, climaxSlack, blindDeep, climaxHidden)
}

object Quality {
    const val H_CAP = 8
    const val DEEP = H_CAP + 1
    const val PEAK_WINDOW = 10
    const val CLIMAX_WINDOW = 8
    /** Elements of the line shown beyond the carousel (RingField.QUEUE_MAX). */
    const val SHOWN_QUEUE = 20
    /** Chain elements on show (carousel + shown line) for the chain to count as visible. */
    const val VISIBLE = 10

    /** One-ply analysis of [s]: each legal child is solved with [budget] nodes, each loser gets its horizon. */
    fun state(s: GameState, step: Int, budget: Long = 20_000, hBudget: Int = 30_000): QState {
        val legal = Engine.legalMoves(s)
        var w = 0
        var unk = 0
        val hs = ArrayList<Int>()
        if (legal.size >= 2) for (m in legal) {
            val child = Engine.apply(s, m).state
            if (child.status == Status.WON) { w++; continue }
            if (child.status != Status.PLAYING) { hs += 1; continue }
            val r = Solver.solve(child, budget)
            if (r == null) { unk++; w++; continue }
            if (r.solvable) { w++; continue }
            hs += ReverseAnalysis.lossHorizon(child, H_CAP - 1, hBudget)?.plus(1) ?: DEEP
        } else w = legal.size
        return QState(step, legal.size, w, unk, hs.toIntArray(), Limits.DOCK_SLOTS - s.dockUsed, s.queueRemaining)
    }

    fun measure(level: Level, line: List<Move>, budget: Long = 20_000): QualityMetrics {
        val gs = ArrayList<GameState>()
        val qs = ArrayList<QState>()
        var s = GameState.initial(level)
        for ((i, m) in line.withIndex()) {
            if (s.status != Status.PLAYING) break
            gs += s
            qs += state(s, i, budget)
            s = Engine.apply(s, m).state
        }
        return of(level, line, gs, qs)
    }

    /** Transitive blocker sets on the initial board. */
    fun blockers(level: Level): Array<Set<Int>> {
        val occ = HashMap<Pair<Int, Int>, Int>()
        for (v in level.containers) for (c in v.cells()) occ[c.row to c.col] = v.id
        val direct = Array(level.containers.size) { HashSet<Int>() }
        for (v in level.containers) {
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (level.inBounds(r, c)) {
                occ[r to c]?.let { if (it != v.id) direct[v.id] += it }
                r += v.dir.dRow; c += v.dir.dCol
            }
        }
        return Array(level.containers.size) { v ->
            val seen = HashSet<Int>()
            val stack = ArrayDeque(direct[v])
            while (stack.isNotEmpty()) { val u = stack.removeLast(); if (seen.add(u)) stack.addAll(direct[u]) }
            seen
        }
    }

    fun of(level: Level, line: List<Move>, gs: List<GameState>, qs: List<QState>): QualityMetrics {
        val n = qs.size
        val hb = qs.map { it.bitsBeyond(1) }
        val third = maxOf(1, n / 3)
        val thirds = DoubleArray(3) { t -> hb.drop(t * third).take(if (t == 2) n else third).sum() }
        var peak = 0.0
        var peakAt = 0
        for (i in 0 until n) {
            val v = hb.subList(i, minOf(n, i + PEAK_WINDOW)).sum()
            if (v > peak + 1e-9) { peak = v; peakAt = i }
        }
        val live = qs.filter { it.queueLeft > 0 }
        // Chain: as in ScarceMetrics (the colour flooding the carousel most).
        var chainColor = -1
        var top = 0
        for (st in gs) for (c in st.staging.toSet()) { val k = st.staging.count { it == c }; if (k > top) { top = k; chainColor = c } }
        // Flood threshold: 15 staged, or 60% of the peak when a herald / squatter keeps more than 15 waiting early.
        val flood = maxOf(minOf(15, top), (top * 3) / 5)
        val chainAt = gs.indexOfFirst { st -> st.staging.count { it == chainColor } >= flood }
        var chainEnd = chainAt
        var chainLen = 0
        if (chainAt >= 0) for (i in chainAt until gs.size) {
            if (i > chainAt && gs[i].staging.count { it == chainColor } < 4) break
            chainEnd = i
            if (level.containers[line[i].containerId].color == chainColor) chainLen++
        }
        fun shown(st: GameState): Int = st.staging.count { it == chainColor } +
            (st.queueIndex until minOf(level.queue.size, st.queueIndex + SHOWN_QUEUE)).count { level.queue[it] == chainColor }
        val visibleAt = if (chainColor < 0) -1 else gs.indexOfFirst { shown(it) >= minOf(VISIBLE, flood) }
        // Blockers of the chain containers tapped during the chain.
        val bl = blockers(level)
        val chainIds = if (chainAt < 0) emptyList() else (chainAt..chainEnd).map { line[it].containerId }.filter { level.containers[it].color == chainColor }
        val dig = chainIds.flatMap { bl[it] }.toSet() - chainIds.toSet()
        val dug = if (visibleAt < 0 || dig.isEmpty()) 0.0 else dig.count { !gs[visibleAt].onBoard[it] }.toDouble() / dig.size
        val slackWin = if (chainAt < 0) emptyList() else qs.subList(maxOf(0, chainAt - CLIMAX_WINDOW), chainAt)
        val vis = if (visibleAt < 0) n else visibleAt
        return QualityMetrics(
            states = qs, moves = line.size,
            bits = qs.sumOf { it.bits }, hidden = hb.sum(), deep = qs.sumOf { it.bitsBeyond(3) },
            hiddenThirds = thirds, peakHidden = peak, peakAt = peakAt,
            hardDecisions = hb.count { it >= 1.0 - 1e-9 },
            meanFreeDocks = if (live.isEmpty()) 0.0 else live.map { it.freeDocks }.average(),
            tightShare = if (live.isEmpty()) 0.0 else live.count { it.freeDocks <= 1 }.toDouble() / live.size,
            chainColor = chainColor, chainAt = chainAt, chainLen = chainLen, chainVisibleAt = visibleAt,
            digShareBeforeVisible = dug,
            climaxSlack = if (slackWin.isEmpty()) 0.0 else slackWin.map { it.freeDocks }.average(),
            blindDeep = qs.take(vis).sumOf { it.bitsBeyond(3) },
            climaxHidden = if (visibleAt < 0) 0.0 else hb.subList(visibleAt, minOf(n, chainEnd + 1)).sum(),
        )
    }

    /**
     * Monte Carlo survival of a random player who avoids moves that lose within [k] moves (k = 0: uniformly random),
     * [n] seeded plays. A cheap empirical check of the 2^-bits estimate.
     */
    fun randomSurvival(level: Level, k: Int, n: Int, seed: Long = 1L, hBudget: Int = 4_000): Double {
        val rnd = java.util.Random(seed)
        var wins = 0
        repeat(n) {
            var s = GameState.initial(level)
            while (s.status == Status.PLAYING) {
                val legal = Engine.legalMoves(s)
                if (legal.isEmpty()) break
                val ok = if (k <= 0) legal else legal.filter { m ->
                    val c = Engine.apply(s, m).state
                    c.status == Status.WON || (c.status == Status.PLAYING && ReverseAnalysis.lossHorizon(c, k - 1, hBudget) == null)
                }.ifEmpty { legal }
                s = Engine.apply(s, ok[rnd.nextInt(ok.size)]).state
            }
            if (s.status == Status.WON) wins++
        }
        return wins.toDouble() / n
    }
}
