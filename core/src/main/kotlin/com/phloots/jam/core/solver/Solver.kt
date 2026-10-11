package com.phloots.jam.core.solver

import com.phloots.jam.core.engine.LevelData
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.scoring.DifficultyConfig
import java.lang.Long.bitCount
import java.lang.Long.numberOfTrailingZeros

/** Search statistics (for diagnostics / scorer). */
data class SolveStats(val nodesExpanded: Long, val statesMemoized: Long)

/** Result of [Solver.solve]. [solution] is empty when not solvable (or when the state is already WON). */
data class SolveResult(val solvable: Boolean, val solution: List<Move>, val stats: SolveStats)

/**
 * Raw scoring metrics from the full offline search (DESIGN.md "Difficulty score"). Normalisation and weighting
 * happen in [com.phloots.jam.core.scoring.Scorer]; values here are un-weighted.
 *
 * "Path states" are the states along [solution] before each of its moves (the final WON state excluded).
 *
 * @property solution the min-dock-occupancy winning line (empty if unsolvable): it minimises the sum over path
 *   states of occupied dock slots (every full line has the same length, so this also minimises the mean); ties
 *   broken by lowest container id at each step
 * @property trapRatio T: mean over solution-path states of losingLegalMoves / legalMoves (0..1)
 * @property meanTrapDepth D before normalisation: mean over trap moves (losing legal moves from path states) of the
 *   longest play after the mistake until the game ends in DEADLOCK/STUCK, in moves (0 = the mistake itself ends
 *   the game). The scorer divides by DifficultyConfig.trapDepthNorm and caps at 1.
 * @property ordering O: fraction of decision states (>=2 legal moves) on the path with exactly 1 winning move (0..1)
 * @property peakSlots peak slots used along [solution] (1..4), counted right after each release (before boarding)
 * @property sharpDecisions number of path states where <= 50% of legal moves are winning
 * @property decisionStates number of path states with >= 2 legal moves
 * @property reachableStates number of distinct (memo-key) states memoized by the full search (states proven lost
 *   by the release lookahead are evaluated in closed form and not counted)
 * @property capped true if the search hit the state cap; all other metrics are then meaningless (solvable=false)
 * @property meanDockUsed v2 P input: mean over path states of occupied dock slots (0..3; a PLAYING state never has
 *   all 4 occupied). The scorer uses P = meanDockUsed / 4.
 * @property unknownChildren [Solver.analyzeBounded] only: legal moves from path states whose outcome could not be
 *   decided within the per-child node budget (counted with free-leaf multiplicity). 0 for the exhaustive analysis.
 * @property bounded true if produced by [Solver.analyzeBounded]
 * @property digDepthMax blocking chains (DESIGN.md v2): over the solution's releases that board at once and whose
 *   colour was "waiting" (staged, with no docked container of that colour), the size of the released container's
 *   transitive on-board blocker set at the path state where that wait began; max over those releases (0 if none)
 * @property digDepthMean the mean of the same (0.0 if none)
 * @property peakDeadWeight max over path states of docked containers with no matching staged element (0..3)
 * @property meanDeadWeight mean over path states of the same
 */
data class FullAnalysis(
    val solvable: Boolean,
    val solution: List<Move>,
    val trapRatio: Double,
    val meanTrapDepth: Double,
    val ordering: Double,
    val peakSlots: Int,
    val sharpDecisions: Int,
    val decisionStates: Int,
    val reachableStates: Long,
    val capped: Boolean = false,
    val meanDockUsed: Double = 0.0,
    val unknownChildren: Int = 0,
    val bounded: Boolean = false,
    val digDepthMax: Int = 0,
    val digDepthMean: Double = 0.0,
    val peakDeadWeight: Int = 0,
    val meanDeadWeight: Double = 0.0,
)

/**
 * DFS + memoization solver (v2 rules). Owner: gamestate agent.
 *
 * State / memo key: four Longs = onBoard (lo, hi; canonicalised over interchangeable free leaves) + packed (a, b):
 * dock slots positional as (colour, spaceLeft), queueIndex, staging as per-colour counts; see [LevelData]. The
 * container id of a docked container is not needed (only its colour and space matter once it is off the board), so
 * the key keeps the colour: lossless and coarser than (id, spaceLeft).
 *
 * Reductions (all exact):
 *  - Release lookahead ([LevelData.cheapestRelease]): after the boarding loop nothing docked matches a staged
 *    colour, so some container of a staged colour plus its transitive blockers must fit in the free slots, else no
 *    element ever boards again and the state is lost.
 *  - Relaxed game ([Relaxed]): the same dock/staging/queue rules with blocking ignored (the board reduced to a count
 *    per colour+size group). Every real line is a relaxed line, so a relaxed loss proves a real loss. Memoised.
 *  - Releasing into the last free slot a container whose colour is not staged deadlocks at once: skipped.
 *  - Move symmetry: exitable containers of the same colour and size that block nothing on the board are
 *    interchangeable (they never interact with the rest); only one per group is tried, and the memo merges such
 *    states ([LevelData.canonBoard]).
 *  - Move ordering (solve only, heuristic): fewest docked slots after the move, then staged-colour containers,
 *    then blockers of the cheapest staged-colour release, then by distance to the colour's next queue element,
 *    then most elements boarded; ties prefer containers that block more.
 */
object Solver {
    /** Default state cap for [analyze]. */
    const val DEFAULT_MAX_STATES: Int = 2_000_000

    /** Node budget [hint] uses (real + relaxed search nodes): worst case ~50-100 ms and ~40 MB of memo on a desktop JVM. */
    const val HINT_MAX_NODES: Long = 500_000

    private const val LOSS = 0
    private const val WIN = 1
    private const val UNKNOWN = -1

    /** Finds one winning line from [state]. Exact: not solvable means no winning line exists. Unbounded. */
    fun solve(state: GameState): SolveResult = solve(state, Long.MAX_VALUE)!!

    fun solve(level: Level): SolveResult = solve(GameState.initial(level))

    fun solve(level: Level, maxNodes: Long): SolveResult? = solve(GameState.initial(level), maxNodes)

    /** How often (in search nodes) the solver polls an `isCancelled` callback. */
    const val CANCEL_CHECK_NODES: Long = 1024

    /**
     * [solve] with a node budget (real + relaxed search nodes). Returns null if the budget was exceeded or
     * [isCancelled] returned true (polled every [CANCEL_CHECK_NODES] nodes) before the search finished (outcome
     * unknown); otherwise the exact result.
     */
    fun solve(state: GameState, maxNodes: Long, isCancelled: () -> Boolean = { false }): SolveResult? {
        when (state.status) {
            Status.WON -> return SolveResult(true, emptyList(), SolveStats(0, 0))
            Status.DEADLOCK, Status.STUCK -> return SolveResult(false, emptyList(), SolveStats(0, 0))
            Status.PLAYING -> {}
        }
        val d = LevelData.of(state.level)
        val ctx = SolveCtx(d)
        ctx.cancelled = isCancelled
        val p = LongArray(2)
        d.toPacked(state, p)
        val r = ctx.run(state.onBoard.lo and d.allLo, state.onBoard.hi and d.allHi, p[0], p[1], maxNodes)
        if (r == UNKNOWN) return null
        val sol = if (r == WIN) List(ctx.solLen) { Move(ctx.path[it]) } else emptyList()
        return SolveResult(r == WIN, sol, SolveStats(ctx.totalNodes, ctx.memoSize()))
    }

    /**
     * Next move of a winning line from [state], or null if no win is reachable (or game over). Bounded by
     * [HINT_MAX_NODES]: if the search does not finish in budget it also returns null (callers cannot tell "lost" from
     * "too hard to prove"; see [solve] with a budget for the distinction). [isCancelled] is polled every
     * [CANCEL_CHECK_NODES] nodes (e.g. `{ !coroutineContext.isActive }`); a cancelled search returns null.
     */
    fun hint(state: GameState, isCancelled: () -> Boolean = { false }): Move? {
        if (state.status != Status.PLAYING) return null
        val r = solve(state, HINT_MAX_NODES, isCancelled) ?: return null
        return if (r.solvable) r.solution.firstOrNull() else null
    }

    /** Full offline search computing scoring metrics. May be slow; for small levels and tests (see [analyzeBounded]). */
    fun analyze(level: Level): FullAnalysis = analyze(level, DEFAULT_MAX_STATES)

    /**
     * Full search with a cap on distinct states. If the cap is exceeded, returns
     * `FullAnalysis(solvable = false, capped = true, reachableStates = maxStates, ...)`.
     */
    fun analyze(level: Level, maxStates: Int): FullAnalysis {
        val d = LevelData.of(level)
        val ctx = AnalyzeCtx(d, maxStates)
        val start = GameState.initial(level)
        val capped = FullAnalysis(false, emptyList(), 0.0, 0.0, 0.0, 0, 0, 0, maxStates.toLong(), capped = true)
        val p = LongArray(2)
        d.toPacked(start, p)
        val lo0 = start.onBoard.lo
        val hi0 = start.onBoard.hi
        val root = try {
            ctx.eval(lo0, hi0, p[0], p[1])
        } catch (e: CapExceeded) {
            return capped
        }
        if (root and 1 == 0) return FullAnalysis(false, emptyList(), 0.0, 0.0, 0.0, 0, 0, 0, ctx.map.size.toLong())

        // Walk the min-occupancy winning line, collecting metrics.
        val solution = ArrayList<Move>()
        var lo = lo0
        var hi = hi0
        var a = p[0]
        var b = p[1]
        var pathStates = 0
        var trapSum = 0.0
        var depthSum = 0L
        var trapMoves = 0
        var decision = 0
        var singleWin = 0
        var sharp = 0
        var occTotal = 0L
        var peak = 0
        val ex = LongArray(2)
        val c = LongArray(2)
        try {
            while (d.status(lo, hi, a, b) == LevelData.PLAYING) {
                d.exitable(lo, hi, ex)
                val eLo = ex[0]
                val eHi = ex[1]
                val used = LevelData.dockUsed(a)
                var legal = 0
                var wins = 0
                var seenGroups = 0
                var best = -1
                var bestOcc = Int.MAX_VALUE
                var m = eLo
                var base = 0
                for (pass in 0 until 2) {
                    while (m != 0L) {
                        val v = numberOfTrailingZeros(m) + base
                        m = m and (m - 1)
                        // Interchangeable free leaves have identical values: evaluate one, count all.
                        var mult = 1
                        if (d.blocksNone(v, lo, hi)) {
                            val g = 1 shl d.group[v]
                            if (seenGroups and g != 0) continue
                            seenGroups = seenGroups or g
                            mult = countLeavesInGroup(d, lo, hi, eLo, eHi, d.group[v])
                        }
                        d.exitAndBoard(a, b, v, c)
                        val ci = ctx.eval(clear(lo, v, 0), clear(hi, v, 64), c[0], c[1])
                        legal += mult
                        if (ci and 1 == 1) {
                            wins += mult
                            // Memo hits may come from equivalent states, so pick the line's move here.
                            if (occSum(ci) < bestOcc) {
                                bestOcc = occSum(ci); best = v
                            }
                        } else {
                            trapMoves += mult
                            depthSum += lossDepth(ci).toLong() * mult
                        }
                    }
                    m = eHi
                    base = 64
                }
                pathStates++
                occTotal += used
                peak = maxOf(peak, used + 1)
                trapSum += (legal - wins).toDouble() / legal
                if (legal >= 2) {
                    decision++
                    if (wins == 1) singleWin++
                }
                if (wins.toDouble() / legal <= 0.5) sharp++
                check(best >= 0)
                solution += Move(best)
                d.exitAndBoard(a, b, best, c)
                a = c[0]
                b = c[1]
                lo = clear(lo, best, 0)
                hi = clear(hi, best, 64)
            }
        } catch (e: CapExceeded) {
            return capped
        }
        check(occTotal == occSum(root).toLong())
        val cs = chainStats(d, lo0, hi0, p[0], p[1], IntArray(solution.size) { solution[it].containerId })
        return FullAnalysis(
            solvable = true,
            solution = solution,
            trapRatio = if (pathStates == 0) 0.0 else trapSum / pathStates,
            meanTrapDepth = if (trapMoves == 0) 0.0 else depthSum.toDouble() / trapMoves,
            ordering = if (decision == 0) 0.0 else singleWin.toDouble() / decision,
            peakSlots = peak,
            sharpDecisions = sharp,
            decisionStates = decision,
            reachableStates = ctx.map.size.toLong(),
            meanDockUsed = if (pathStates == 0) 0.0 else occTotal.toDouble() / pathStates,
            digDepthMax = cs.digMax,
            digDepthMean = cs.digMean,
            peakDeadWeight = cs.deadPeak,
            meanDeadWeight = cs.deadMean,
        )
    }

    /**
     * Bounded, deterministic analysis for v2-size levels (the exhaustive [analyze] caps out there). Budgets come
     * from [config]:
     * - Path: the [solve] line (its move ordering is pressure-first: fewest occupied dock slots after the move), found
     *   within [DifficultyConfig.rootSolveNodeBudget] nodes; exceeding it returns `capped = true, solvable = false`.
     * - Every legal move from each path state is classified by a solve of the child limited to
     *   [DifficultyConfig.childSolveNodeBudget] nodes: WIN, LOSS, or UNKNOWN (budget hit). UNKNOWN is never counted as
     *   losing: it is excluded from the T and sharp-decision denominators (wins / known moves), and states with any
     *   UNKNOWN child are excluded from O. The total is reported as [FullAnalysis.unknownChildren].
     * - Trap depth: for each losing child, the longest play until DEADLOCK/STUCK, by a DFS limited to depth
     *   ceil(trapDepthNorm) and [DifficultyConfig.trapDepthNodeBudget] nodes (hitting either counts as the cap).
     * - [FullAnalysis.ordering] = states with exactly one winning move / decision states without unknowns;
     *   [FullAnalysis.decisionStates] counts all path states with >= 2 legal moves. P, peak and sharp as in
     *   [analyze], on this path. [FullAnalysis.reachableStates] = total solver nodes spent (diagnostic).
     * Losses proven by a complete failed search are kept in a memo shared across children (sound: a finished failed
     * search only marks losing states); the relaxed-game memo is always shared.
     */
    fun analyzeBounded(level: Level, config: DifficultyConfig = DifficultyConfig.DEFAULT): FullAnalysis {
        val d = LevelData.of(level)
        val ctx = SolveCtx(d)
        val start = GameState.initial(level)
        val p = LongArray(2)
        d.toPacked(start, p)
        val lo0 = start.onBoard.lo
        val hi0 = start.onBoard.hi
        val root = ctx.run(lo0, hi0, p[0], p[1], config.rootSolveNodeBudget)
        if (root == UNKNOWN) {
            return FullAnalysis(
                false, emptyList(), 0.0, 0.0, 0.0, 0, 0, 0, ctx.totalNodes, capped = true, bounded = true,
            )
        }
        if (root == LOSS) return FullAnalysis(false, emptyList(), 0.0, 0.0, 0.0, 0, 0, 0, ctx.totalNodes, bounded = true)
        val line = IntArray(ctx.solLen) { ctx.path[it] }

        val depthCap = kotlin.math.ceil(config.trapDepthNorm).toInt().coerceAtLeast(1)
        val trap = TrapDepth(d, depthCap, config.trapDepthNodeBudget)
        var lo = lo0
        var hi = hi0
        var a = p[0]
        var b = p[1]
        var trapSum = 0.0
        var depthSum = 0L
        var trapMoves = 0
        var decision = 0
        var oDen = 0
        var singleWin = 0
        var sharp = 0
        var unknownTotal = 0
        var occTotal = 0L
        var peak = 0
        val ex = LongArray(2)
        val c = LongArray(2)
        for (lineMove in line) {
            d.exitable(lo, hi, ex)
            val eLo = ex[0]
            val eHi = ex[1]
            val used = LevelData.dockUsed(a)
            val lineLeaf = d.blocksNone(lineMove, lo, hi)
            var legal = 0
            var wins = 0
            var losses = 0
            var unknown = 0
            var seenGroups = 0
            var m = eLo
            var base = 0
            for (pass in 0 until 2) {
                while (m != 0L) {
                    val v = numberOfTrailingZeros(m) + base
                    m = m and (m - 1)
                    var mult = 1
                    val leaf = d.blocksNone(v, lo, hi)
                    if (leaf) {
                        val g = 1 shl d.group[v]
                        if (seenGroups and g != 0) continue
                        seenGroups = seenGroups or g
                        mult = countLeavesInGroup(d, lo, hi, eLo, eHi, d.group[v])
                    }
                    legal += mult
                    val clo = clear(lo, v, 0)
                    val chi = clear(hi, v, 64)
                    d.exitAndBoard(a, b, v, c)
                    val ca = c[0]
                    val cb = c[1]
                    val outcome = if (v == lineMove || (leaf && lineLeaf && d.group[v] == d.group[lineMove])) {
                        WIN
                    } else {
                        ctx.run(clo, chi, ca, cb, config.childSolveNodeBudget)
                    }
                    when (outcome) {
                        WIN -> wins += mult
                        LOSS -> {
                            losses += mult
                            trapMoves += mult
                            depthSum += trap.depth(clo, chi, ca, cb).toLong() * mult
                        }
                        else -> unknown += mult
                    }
                }
                m = eHi
                base = 64
            }
            val known = legal - unknown
            occTotal += used
            peak = maxOf(peak, used + 1)
            trapSum += losses.toDouble() / known
            if (legal >= 2) {
                decision++
                if (unknown == 0) {
                    oDen++
                    if (wins == 1) singleWin++
                }
            }
            if (wins.toDouble() / known <= config.sharpThreshold) sharp++
            unknownTotal += unknown
            d.exitAndBoard(a, b, lineMove, c)
            a = c[0]
            b = c[1]
            lo = clear(lo, lineMove, 0)
            hi = clear(hi, lineMove, 64)
        }
        val n = line.size
        val cs = chainStats(d, lo0, hi0, p[0], p[1], line)
        return FullAnalysis(
            solvable = true,
            solution = line.map { Move(it) },
            trapRatio = if (n == 0) 0.0 else trapSum / n,
            meanTrapDepth = if (trapMoves == 0) 0.0 else depthSum.toDouble() / trapMoves,
            ordering = if (oDen == 0) 0.0 else singleWin.toDouble() / oDen,
            peakSlots = peak,
            sharpDecisions = sharp,
            decisionStates = decision,
            reachableStates = ctx.totalNodes + trap.totalNodes,
            meanDockUsed = if (n == 0) 0.0 else occTotal.toDouble() / n,
            unknownChildren = unknownTotal,
            bounded = true,
            digDepthMax = cs.digMax,
            digDepthMean = cs.digMean,
            peakDeadWeight = cs.deadPeak,
            meanDeadWeight = cs.deadMean,
        )
    }

    private class ChainStats(val digMax: Int, val digMean: Double, val deadPeak: Int, val deadMean: Double)

    /** Blocking-chain metrics along [line] from the packed start state (see [FullAnalysis.digDepthMax]). */
    private fun chainStats(d: LevelData, lo0: Long, hi0: Long, a0: Long, b0: Long, line: IntArray): ChainStats {
        val n = line.size
        val boardLo = LongArray(n + 1)
        val boardHi = LongArray(n + 1)
        val waitStart = IntArray(LevelData.COLORS) { -1 }
        val c = LongArray(2)
        val tmp = LongArray(2)
        var lo = lo0
        var hi = hi0
        var a = a0
        var b = b0
        var digSum = 0L
        var digCount = 0
        var digMax = 0
        var deadPeak = 0
        var deadSum = 0L
        for (j in 0 until n) {
            boardLo[j] = lo
            boardHi[j] = hi
            val staged = LevelData.stagedColors(b)
            var docked = 0
            var dead = 0
            for (s in 0 until 4) {
                val f = ((a ushr (8 * s)) and 0xFFL).toInt()
                if (f and 0xF == 0) continue
                val col = f ushr 4
                docked = docked or (1 shl col)
                if ((staged ushr col) and 1 == 0) dead++
            }
            deadPeak = maxOf(deadPeak, dead)
            deadSum += dead
            for (col in 0 until LevelData.COLORS) {
                val waiting = (staged ushr col) and 1 != 0 && (docked ushr col) and 1 == 0
                if (!waiting) waitStart[col] = -1 else if (waitStart[col] < 0) waitStart[col] = j
            }
            val v = line[j]
            val ws = waitStart[d.color[v]]
            if (ws >= 0) {
                val ok = d.closureWithin(v, boardLo[ws], boardHi[ws], d.n, tmp)
                val dig = if (ok) bitCount(tmp[0]) + bitCount(tmp[1]) else 0
                digSum += dig
                digCount++
                digMax = maxOf(digMax, dig)
            }
            d.exitAndBoard(a, b, v, c)
            a = c[0]
            b = c[1]
            lo = clear(lo, v, 0)
            hi = clear(hi, v, 64)
        }
        return ChainStats(
            digMax, if (digCount == 0) 0.0 else digSum.toDouble() / digCount,
            deadPeak, if (n == 0) 0.0 else deadSum.toDouble() / n,
        )
    }

    /** [mask] (holding ids base..base+63) without container [v]. */
    private fun clear(mask: Long, v: Int, base: Int): Long =
        if (v - base in 0 until 64) mask and (1L shl (v - base)).inv() else mask

    /** Number of exitable containers in [group] that block no on-board container. */
    private fun countLeavesInGroup(d: LevelData, lo: Long, hi: Long, eLo: Long, eHi: Long, group: Int): Int {
        var k = 0
        var m = eLo and d.groupLo[group]
        while (m != 0L) {
            val u = numberOfTrailingZeros(m); m = m and (m - 1)
            if (d.blocksNone(u, lo, hi)) k++
        }
        m = eHi and d.groupHi[group]
        while (m != 0L) {
            val u = numberOfTrailingZeros(m) + 64; m = m and (m - 1)
            if (d.blocksNone(u, lo, hi)) k++
        }
        return k
    }

    private class CapExceeded : RuntimeException(null, null, false, false)

    // ------------------------------------------------------------------ solve

    private class SolveCtx(val d: LevelData) {
        val visited = StateMap(1 shl 12)
        val path = IntArray(d.n + 1)
        var solLen = 0
        var nodes = 0L
        var limit = Long.MAX_VALUE
        var totalNodes = 0L
        var cancelled: () -> Boolean = { false }

        /** Counts a node; aborts the search on budget exhaustion or cancellation. */
        fun tick() {
            if (++nodes > limit) throw CapExceeded()
            if (nodes and (CANCEL_CHECK_NODES - 1) == 0L && cancelled()) throw CapExceeded()
        }
        val relaxed = Relaxed(d, this)
        private val tmp = LongArray(2)
        private val tmp2 = LongArray(2)
        private val rel = LongArray(2)
        private val canon = LongArray(2)
        private val po = LongArray(2)
        private val exLo = LongArray(d.n + 2)
        private val exHi = LongArray(d.n + 2)
        private val cand = Array(d.n + 1) { IntArray(d.n) }
        private val prio = Array(d.n + 1) { LongArray(d.n) }
        private val childA = Array(d.n + 1) { LongArray(d.n) }
        private val childB = Array(d.n + 1) { LongArray(d.n) }

        fun memoSize(): Long = visited.size.toLong() + relaxed.size

        /**
         * Budgeted solve from a packed state: [WIN] (line in [path]/[solLen]), [LOSS] or [UNKNOWN] (budget hit).
         * The failure memo is kept only after a complete failed search (then it holds losing states only).
         */
        fun run(lo: Long, hi: Long, a: Long, b: Long, budget: Long): Int {
            when (d.status(lo, hi, a, b)) {
                LevelData.WON -> { solLen = 0; return WIN }
                LevelData.DEADLOCK, LevelData.STUCK -> return LOSS
            }
            d.exitable(lo, hi, tmp)
            exLo[0] = tmp[0]
            exHi[0] = tmp[1]
            nodes = 0
            limit = budget
            val r = try {
                if (dfs(lo, hi, a, b, 0)) WIN else LOSS
            } catch (e: CapExceeded) {
                UNKNOWN
            }
            totalNodes += nodes
            if (r != LOSS) visited.clear()
            return r
        }

        private fun dfs(lo: Long, hi: Long, a: Long, b: Long, depth: Int): Boolean {
            tick()
            if (lo == 0L && hi == 0L) {
                if (a and LevelData.DOCK_MASK == 0L && b == 0L && LevelData.queueIndex(a) >= d.qSize) {
                    solLen = depth
                    return true
                }
                return false
            }
            val used = LevelData.dockUsed(a)
            if (used == 4) return false
            val eLo = exLo[depth]
            val eHi = exHi[depth]
            if (eLo == 0L && eHi == 0L) return false
            if (d.cheapestRelease(lo, hi, a, b, rel, tmp2) < 0) return false
            val rLo = rel[0]
            val rHi = rel[1]
            d.canonBoard(lo, hi, eLo, eHi, canon)
            if (!visited.add(canon[0], canon[1], a, b)) return false
            d.relaxedCounts(lo, hi, tmp2)
            if (!relaxed.wins(tmp2[0], tmp2[1], a, b)) return false

            val staged = LevelData.stagedColors(b)
            val qi = LevelData.queueIndex(a)
            val consumedBefore = qi - LevelData.stagedTotal(b)
            val cs = cand[depth]
            val ps = prio[depth]
            val ca = childA[depth]
            val cb = childB[depth]
            var count = 0
            var seenGroups = 0
            var m = eLo
            var base = 0
            for (pass in 0 until 2) {
                while (m != 0L) {
                    val v = numberOfTrailingZeros(m) + base
                    m = m and (m - 1)
                    val c = d.color[v]
                    val inStaging = (staged ushr c) and 1 != 0
                    // Filling the last slot with a container nobody staged can board deadlocks immediately.
                    if (used == 3 && !inStaging) continue
                    val blockedCount = bitCount(d.blkLo[v] and lo) + bitCount(d.blkHi[v] and hi)
                    if (blockedCount == 0) {
                        val g = 1 shl d.group[v]
                        if (seenGroups and g != 0) continue
                        seenGroups = seenGroups or g
                    }
                    d.exitAndBoard(a, b, v, po)
                    val na = po[0]
                    val nb = po[1]
                    val usedChild = LevelData.dockUsed(na)
                    val consumed = LevelData.queueIndex(na) - LevelData.stagedTotal(nb) - consumedBefore
                    val inRelease = if (v < 64) (rLo ushr v) and 1L != 0L else (rHi ushr (v - 64)) and 1L != 0L
                    val cls = when {
                        inStaging -> 0L
                        inRelease -> 1L
                        else -> 2L
                    }
                    val dist = if (inStaging) 0 else minOf(d.nextOcc[qi * LevelData.COLORS + c] - qi, 0xFFFF)
                    val p = (usedChild.toLong() shl 50) or (cls shl 46) or (dist.toLong() shl 28) or
                        ((0xFFFL - minOf(consumed, 0xFFF)) shl 8) or (0xFFL - blockedCount)
                    var j = count
                    while (j > 0 && ps[j - 1] > p) {
                        ps[j] = ps[j - 1]; cs[j] = cs[j - 1]; ca[j] = ca[j - 1]; cb[j] = cb[j - 1]; j--
                    }
                    ps[j] = p; cs[j] = v; ca[j] = na; cb[j] = nb
                    count++
                }
                m = eHi
                base = 64
            }
            for (k in 0 until count) {
                val v = cs[k]
                d.exitableAfter(lo, hi, eLo, eHi, v, tmp)
                exLo[depth + 1] = tmp[0]
                exHi[depth + 1] = tmp[1]
                if (dfs(clear(lo, v, 0), clear(hi, v, 64), ca[k], cb[k], depth + 1)) {
                    path[depth] = v
                    return true
                }
            }
            return false
        }
    }

    /**
     * The relaxed game: the same dock / staging / queue rules but with blocking ignored, so the board is just the
     * number of on-board containers per (colour, size) group. Every real line is a relaxed line, so a relaxed loss
     * proves a real loss (exact pruning). Results are complete and memoised for the solver context's lifetime (also
     * across budget aborts: only finished results are stored). Relaxed nodes count towards the node budget.
     */
    private class Relaxed(val d: LevelData, val owner: SolveCtx) {
        private val memo = StateMap(1 shl 12)
        private val po = LongArray(2)
        val size: Int get() = memo.size

        fun wins(r0: Long, r1: Long, a: Long, b: Long): Boolean {
            owner.tick()
            if (r0 == 0L && r1 == 0L) {
                return a and LevelData.DOCK_MASK == 0L && b == 0L && LevelData.queueIndex(a) >= d.qSize
            }
            val used = LevelData.dockUsed(a)
            if (used == 4) return false
            val staged = LevelData.stagedColors(b)
            if (staged == 0) return false
            val known = memo.get(r0, r1, a, b)
            if (known >= 0) return known == 1
            var win = false
            // Staged colours first (they board at once), then the rest.
            loop@ for (pass in 0 until 2) {
                for (k in d.relGroups.indices) {
                    val c = d.relColor[k]
                    val inStaging = (staged ushr c) and 1 != 0
                    if (inStaging != (pass == 0)) continue
                    if (used == 3 && !inStaging) continue
                    val off = d.relOffset[k]
                    val w = d.relWidth[k]
                    val cnt = if (off < 64) (r0 ushr off) and ((1L shl w) - 1) else (r1 ushr (off - 64)) and ((1L shl w) - 1)
                    if (cnt == 0L) continue
                    val n0 = if (off < 64) r0 - (1L shl off) else r0
                    val n1 = if (off < 64) r1 else r1 - (1L shl (off - 64))
                    d.dockAndBoard(a, b, c, d.relCap[k], po)
                    if (wins(n0, n1, po[0], po[1])) {
                        win = true
                        break@loop
                    }
                }
            }
            memo.put(r0, r1, a, b, if (win) 1 else 0)
            return win
        }
    }

    // ------------------------------------------------------------------ trap depth (bounded analysis)

    /** Longest play from a state until DEADLOCK/STUCK, limited to [cap] moves and [budget] nodes (hit = [cap]). */
    private class TrapDepth(val d: LevelData, val cap: Int, val budget: Long) {
        var totalNodes = 0L
        private var nodes = 0L
        private val tmp = LongArray(2)
        private val tmp2 = LongArray(2)
        private val exs = Array(cap + 1) { LongArray(2) }
        private val pos = Array(cap + 1) { LongArray(2) }

        fun depth(lo: Long, hi: Long, a: Long, b: Long): Int {
            nodes = 0
            val r = try {
                go(lo, hi, a, b, 0)
            } catch (e: CapExceeded) {
                cap
            }
            totalNodes += nodes
            return r
        }

        private fun go(lo: Long, hi: Long, a: Long, b: Long, depth: Int): Int {
            if (++nodes > budget) throw CapExceeded()
            if (d.status(lo, hi, a, b) != LevelData.PLAYING) return 0
            if (depth >= cap) return 0
            if (d.cheapestRelease(lo, hi, a, b, tmp, tmp2) < 0) {
                return minOf(4 - LevelData.dockUsed(a), d.extractableCount(lo, hi, tmp), cap - depth)
            }
            val ex = exs[depth]
            val po = pos[depth]
            d.exitable(lo, hi, ex)
            var best = 0
            var seenGroups = 0
            var m = ex[0]
            var base = 0
            val eHi = ex[1]
            for (pass in 0 until 2) {
                while (m != 0L) {
                    val v = numberOfTrailingZeros(m) + base
                    m = m and (m - 1)
                    if (d.blocksNone(v, lo, hi)) {
                        val g = 1 shl d.group[v]
                        if (seenGroups and g != 0) continue
                        seenGroups = seenGroups or g
                    }
                    d.exitAndBoard(a, b, v, po)
                    val l = 1 + go(clear(lo, v, 0), clear(hi, v, 64), po[0], po[1], depth + 1)
                    if (l > best) best = l
                    if (depth + best >= cap) return best
                }
                m = eHi
                base = 64
            }
            return best
        }
    }

    // ------------------------------------------------------------------ full analysis

    /*
     * Info packing (non-negative Int): bit 0 = winning; bits 1..11 = occSum (winning states: min over winning
     * lines of the summed occupied dock slots of the line's states, this state included); bits 12..19 = lossDepth
     * (losing states: longest play to a terminal loss).
     */
    private fun occSum(info: Int) = (info ushr 1) and 0x7FF
    private fun lossDepth(info: Int) = (info ushr 12) and 0xFF

    private class AnalyzeCtx(val d: LevelData, val cap: Int) {
        val map = StateMap(1 shl 14)
        private val tmp = LongArray(2)
        private val tmp2 = LongArray(2)
        private val po = LongArray(2)

        fun eval(lo: Long, hi: Long, a: Long, b: Long): Int {
            when (d.status(lo, hi, a, b)) {
                LevelData.WON -> return 1
                LevelData.DEADLOCK, LevelData.STUCK -> return 0
            }
            // Exact shortcut: no staged-colour container can be released in time, so nothing boards again and the
            // longest play is min(free slots, containers extractable in sequence). Not memoized.
            val used = LevelData.dockUsed(a)
            if (d.cheapestRelease(lo, hi, a, b, tmp, tmp2) < 0) {
                return minOf(4 - used, d.extractableCount(lo, hi, tmp)) shl 12
            }
            d.exitable(lo, hi, tmp)
            val eLo = tmp[0]
            val eHi = tmp[1]
            d.canonBoard(lo, hi, eLo, eHi, tmp)
            val kLo = tmp[0]
            val kHi = tmp[1]
            val known = map.get(kLo, kHi, a, b)
            if (known >= 0) return known
            val info = compute(lo, hi, a, b, eLo, eHi, used)
            if (map.size >= cap) throw CapExceeded()
            map.put(kLo, kHi, a, b, info)
            return info
        }

        private fun compute(lo: Long, hi: Long, a: Long, b: Long, eLo: Long, eHi: Long, used: Int): Int {
            var bestOcc = Int.MAX_VALUE
            var maxLoss = 0
            var seenGroups = 0
            var m = eLo
            var base = 0
            for (pass in 0 until 2) {
                while (m != 0L) {
                    val v = numberOfTrailingZeros(m) + base
                    m = m and (m - 1)
                    if (d.blocksNone(v, lo, hi)) {
                        val g = 1 shl d.group[v]
                        if (seenGroups and g != 0) continue
                        seenGroups = seenGroups or g
                    }
                    d.exitAndBoard(a, b, v, po)
                    val ci = eval(clear(lo, v, 0), clear(hi, v, 64), po[0], po[1])
                    if (ci and 1 == 1) {
                        val o = occSum(ci)
                        if (o < bestOcc) bestOcc = o
                    } else {
                        val l = 1 + lossDepth(ci)
                        if (l > maxLoss) maxLoss = l
                    }
                }
                m = eHi
                base = 64
            }
            return if (bestOcc != Int.MAX_VALUE) 1 or ((used + bestOcc) shl 1) else (maxLoss shl 12)
        }
    }
}
