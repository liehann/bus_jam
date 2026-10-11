package com.phloots.jam.core.solver

import com.phloots.jam.core.engine.LevelData
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status

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
 * @property solution the min-slot-pressure winning line (empty if unsolvable); ties broken by lowest container id
 * @property trapRatio T: mean over solution-path states of losingLegalMoves / legalMoves (0..1)
 * @property meanTrapDepth D before normalisation: mean over trap moves (losing legal moves from path states) of the
 *   longest play after the mistake until the game ends in DEADLOCK/STUCK, in moves (0 = the mistake itself ends
 *   the game). The scorer divides by DifficultyConfig.trapDepthNorm and caps at 1.
 * @property ordering O: fraction of decision states (>=2 legal moves) on the path with exactly 1 winning move (0..1)
 * @property peakSlots peak slots used along [solution] (1..4), counted right after each exit (before boarding)
 * @property sharpDecisions number of path states where <= 50% of legal moves are winning
 * @property decisionStates number of path states with >= 2 legal moves
 * @property reachableStates number of distinct (memo-key) states memoized by the full search (states proven lost
 *   by the front-colour lookahead are evaluated in closed form and not counted)
 * @property capped true if the search hit the state cap; all other metrics are then meaningless (solvable=false)
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
)

/**
 * DFS + memoization solver. Owner: gamestate agent.
 *
 * Memo key: (onBoard, canonical slots + queueIndex). The canonical slot form sorts the docked containers by
 * (colour, arrival) and keeps (colour, spaceLeft) in that order. This is the DESIGN.md key refined so it stays
 * lossless when two untouched same-colour containers of different sizes are docked (their arrival order decides
 * which one fills first).
 *
 * Reductions (all exact):
 *  - Front-colour lookahead ([LevelData.frontClosure]): after the boarding loop nothing docked matches the front
 *    element, so a front-colour container plus its transitive blockers must fit in the free slots, else the state
 *    is lost.
 *  - Move symmetry: exitable containers of the same colour and size that block nothing are interchangeable; only one
 *    per group is tried. `analyze` also merges such states in its memo ([LevelData.canonBoard]).
 *  - Move ordering (solve only): front-colour containers that dispatch at once, then blockers of the cheapest
 *    front-colour container, then by distance to the container colour's next element; ties prefer containers that block
 *    more. Moves that fill the 4th slot with a non-matching container are skipped (instant deadlock).
 */
object Solver {
    /** Default state cap for [analyze]. */
    const val DEFAULT_MAX_STATES: Int = 2_000_000

    /** Finds one winning line from [state]. */
    fun solve(state: GameState): SolveResult {
        when (state.status) {
            Status.WON -> return SolveResult(true, emptyList(), SolveStats(0, 0))
            Status.DEADLOCK, Status.STUCK -> return SolveResult(false, emptyList(), SolveStats(0, 0))
            Status.PLAYING -> {}
        }
        val d = LevelData.of(state.level)
        val ctx = SolveCtx(d)
        val ob = state.onBoard and d.allMask
        val ok = ctx.dfs(ob, d.toPacked(state), d.exitableMask(ob), 0)
        val sol = if (ok) List(ctx.solLen) { Move(ctx.path[it]) } else emptyList()
        return SolveResult(ok, sol, SolveStats(ctx.nodes, ctx.visited.size.toLong()))
    }

    fun solve(level: Level): SolveResult = solve(GameState.initial(level))

    /** Next move of a winning line from [state], or null if no win is reachable (or game over). */
    fun hint(state: GameState): Move? {
        if (state.status != Status.PLAYING) return null
        val r = solve(state)
        return if (r.solvable) r.solution.firstOrNull() else null
    }

    /** Full offline search computing scoring metrics. May be slow; used by generator/levelgen only. */
    fun analyze(level: Level): FullAnalysis = analyze(level, DEFAULT_MAX_STATES)

    /**
     * Full search with a cap on distinct states. If the cap is exceeded, returns
     * `FullAnalysis(solvable = false, capped = true, reachableStates = maxStates, ...)`.
     */
    fun analyze(level: Level, maxStates: Int): FullAnalysis {
        val d = LevelData.of(level)
        val ctx = AnalyzeCtx(d, maxStates)
        val start = GameState.initial(level)
        val ob0 = start.onBoard
        val aux0 = d.toPacked(start)
        val root = try {
            ctx.eval(ob0, aux0)
        } catch (e: CapExceeded) {
            return FullAnalysis(false, emptyList(), 0.0, 0.0, 0.0, 0, 0, 0, maxStates.toLong(), capped = true)
        }
        val reachable = ctx.map.size.toLong()
        if (root and 1 == 0) return FullAnalysis(false, emptyList(), 0.0, 0.0, 0.0, 0, 0, 0, reachable)

        // Walk the min-pressure winning line, collecting metrics.
        val solution = ArrayList<Move>()
        var ob = ob0
        var aux = aux0
        var info = root
        var pathStates = 0
        var trapSum = 0.0
        var depthSum = 0L
        var trapMoves = 0
        var decision = 0
        var singleWin = 0
        var sharp = 0
        while (d.status(ob, aux) == LevelData.PLAYING) {
            val ex = d.exitableMask(ob)
            var m = ex
            var legal = 0
            var wins = 0
            var seenGroups = 0
            var best = -1
            var bestPeak = Int.MAX_VALUE
            while (m != 0L) {
                val v = java.lang.Long.numberOfTrailingZeros(m)
                m = m and (m - 1)
                // Interchangeable free leaves (see SolveCtx) have identical values: evaluate one, count all.
                var mult = 1
                if (d.blocks[v] and ob == 0L) {
                    val g = 1 shl d.group[v]
                    if (seenGroups and g != 0) continue
                    seenGroups = seenGroups or g
                    mult = countLeavesInGroup(d, ob, ex, d.group[v])
                }
                val cob = ob and (1L shl v).inv()
                val ci = ctx.eval(cob, d.exitAndBoard(ob, aux, v))
                legal += mult
                if (ci and 1 == 1) {
                    wins += mult
                    // Memo hits may come from equivalent states, so pick the line's move here, not from the memo.
                    val peak = maxOf(LevelData.dockUsed(aux) + 1, minPeak(ci))
                    if (peak < bestPeak) {
                        bestPeak = peak; best = v
                    }
                } else {
                    trapMoves += mult
                    depthSum += lossDepth(ci).toLong() * mult
                }
            }
            pathStates++
            trapSum += (legal - wins).toDouble() / legal
            if (legal >= 2) {
                decision++
                if (wins == 1) singleWin++
            }
            if (wins.toDouble() / legal <= 0.5) sharp++
            check(best >= 0 && bestPeak <= minPeak(info))
            solution += Move(best)
            aux = d.exitAndBoard(ob, aux, best)
            ob = ob and (1L shl best).inv()
            info = ctx.eval(ob, aux)
        }
        return FullAnalysis(
            solvable = true,
            solution = solution,
            trapRatio = if (pathStates == 0) 0.0 else trapSum / pathStates,
            meanTrapDepth = if (trapMoves == 0) 0.0 else depthSum.toDouble() / trapMoves,
            ordering = if (decision == 0) 0.0 else singleWin.toDouble() / decision,
            peakSlots = minPeak(root),
            sharpDecisions = sharp,
            decisionStates = decision,
            reachableStates = reachable,
        )
    }

    /** Number of exitable containers in [group] that block no on-board container. */
    private fun countLeavesInGroup(d: LevelData, onBoard: Long, exitable: Long, group: Int): Int {
        var m = exitable
        var k = 0
        while (m != 0L) {
            val u = java.lang.Long.numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (d.group[u] == group && d.blocks[u] and onBoard == 0L) k++
        }
        return k
    }

    // ------------------------------------------------------------------ solve

    private class SolveCtx(val d: LevelData) {
        val visited = LongPairMap(1 shl 12)
        val path = IntArray(65)
        var solLen = 0
        var nodes = 0L
        private val cand = Array(65) { IntArray(64) }
        private val prio = Array(65) { IntArray(64) }

        fun dfs(onBoard: Long, aux: Long, exitable: Long, depth: Int): Boolean {
            nodes++
            val qi = LevelData.queueIndex(aux)
            val used = LevelData.dockUsed(aux)
            if (onBoard == 0L) {
                if (used == 0 && qi >= d.qSize) {
                    solLen = depth
                    return true
                }
                return false
            }
            if (used == 4) return false
            val closure = d.frontClosure(onBoard, aux)
            if (closure == LevelData.NO_CLOSURE) return false
            if (!visited.add(onBoard, d.canon(aux))) return false

            var m = exitable
            if (m == 0L) return false
            val front = if (qi < d.qSize) d.queue[qi] else -1
            val run = d.runLen[qi]
            val cs = cand[depth]
            val ps = prio[depth]
            var count = 0
            var seenGroups = 0
            while (m != 0L) {
                val v = java.lang.Long.numberOfTrailingZeros(m)
                m = m and (m - 1)
                val c = d.color[v]
                // Filling the last slot with a non-matching container deadlocks immediately.
                if (used == 3 && c != front) continue
                // Symmetry: exitable containers of the same colour and size that block nobody are interchangeable.
                if (d.blocks[v] and onBoard == 0L) {
                    val g = 1 shl d.group[v]
                    if (seenGroups and g != 0) continue
                    seenGroups = seenGroups or g
                }
                // Distance (in elements) to the next element of this colour.
                val dist = d.nextOcc[qi * LevelData.COLORS + c] - qi
                val base = when {
                    c == front -> if (d.capacity[v] <= run) 0 else 1
                    // Blockers of the cheapest front-colour container must go first anyway.
                    (closure ushr v) and 1L == 1L -> 2 + dist
                    else -> 4096 + dist
                }
                // Tie-break: prefer containers that block more on-board containers.
                val p = base * 64 + (63 - java.lang.Long.bitCount(d.blocks[v] and onBoard))
                // insertion sort by priority (stable on id)
                var j = count
                while (j > 0 && ps[j - 1] > p) {
                    ps[j] = ps[j - 1]; cs[j] = cs[j - 1]; j--
                }
                ps[j] = p; cs[j] = v
                count++
            }
            for (k in 0 until count) {
                val v = cs[k]
                val child = d.exitAndBoard(onBoard, aux, v)
                if (dfs(onBoard and (1L shl v).inv(), child, d.exitableAfter(onBoard, exitable, v), depth + 1)) {
                    path[depth] = v
                    return true
                }
            }
            return false
        }
    }

    // ------------------------------------------------------------------ full analysis

    private class CapExceeded : RuntimeException(null, null, false, false)

    /*
     * Info packing (non-negative Int): bit 0 = winning; bits 1..3 = minPeak (winning states);
     * bits 4..10 = lossDepth (losing states: longest play to a terminal loss);
     */
    private fun minPeak(info: Int) = (info ushr 1) and 7
    private fun lossDepth(info: Int) = (info ushr 4) and 0x7F

    private class AnalyzeCtx(val d: LevelData, val cap: Int) {
        val map = LongPairMap(1 shl 14)

        fun eval(onBoard: Long, aux: Long): Int {
            // Exact shortcut: if no front-colour container can be freed with the free slots, nothing boards again,
            // so the longest play is min(free slots, containers extractable in sequence). Not memoized.
            val used = LevelData.dockUsed(aux)
            if (used < 4 && LevelData.queueIndex(aux) < d.qSize &&
                d.frontClosure(onBoard, aux) == LevelData.NO_CLOSURE
            ) {
                return minOf(4 - used, d.extractableCount(onBoard)) shl 4
            }
            val key = d.canon(aux)
            val board = d.canonBoard(onBoard, d.exitableMask(onBoard))
            val known = map.get(board, key)
            if (known >= 0) return known
            val info = compute(onBoard, aux)
            if (map.size >= cap) throw CapExceeded()
            map.put(board, key, info)
            return info
        }

        private fun compute(onBoard: Long, aux: Long): Int {
            when (d.status(onBoard, aux)) {
                LevelData.WON -> return 1
                LevelData.DEADLOCK, LevelData.STUCK -> return 0
            }
            val usedAfterExit = LevelData.dockUsed(aux) + 1
            var m = d.exitableMask(onBoard)
            var bestPeak = Int.MAX_VALUE
            var best = -1
            var maxLoss = 0
            var seenGroups = 0
            while (m != 0L) {
                val v = java.lang.Long.numberOfTrailingZeros(m)
                m = m and (m - 1)
                if (d.blocks[v] and onBoard == 0L) {
                    val g = 1 shl d.group[v]
                    if (seenGroups and g != 0) continue
                    seenGroups = seenGroups or g
                }
                val ci = eval(onBoard and (1L shl v).inv(), d.exitAndBoard(onBoard, aux, v))
                if (ci and 1 == 1) {
                    val peak = maxOf(usedAfterExit, (ci ushr 1) and 7)
                    if (peak < bestPeak) {
                        bestPeak = peak; best = v
                    }
                } else {
                    val l = 1 + ((ci ushr 4) and 0x7F)
                    if (l > maxLoss) maxLoss = l
                }
            }
            return if (best >= 0) 1 or (bestPeak shl 1) else (maxLoss shl 4)
        }
    }
}
