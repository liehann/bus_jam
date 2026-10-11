package com.phloots.jam.core.scoring

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver

/**
 * One decision state on a line (DESIGN.md v2 principle 8, "Decisions = forks").
 * @property step index of the move about to be played (state before it)
 * @property b legal moves
 * @property w winning ("good") moves: budgeted [Solver.solve] of the child wins, or runs out of budget (unknown is
 *   not counted as losing)
 * @property h horizon: over the proven-losing moves, the largest number of moves (the bad move included) until
 *   every continuation has ended in DEADLOCK/STUCK, i.e. the search depth needed to see that the move is bad
 *   (1 = the bad move loses at once). Capped at [DifficultyConfig.forkHorizonCap] (also used when the proof did not
 *   finish within the budget). 0 if no move is proven bad.
 */
data class Fork(val step: Int, val b: Int, val w: Int, val h: Int)

/**
 * Fork metrics of a line. [value] (0..1) = min(1, Σ c / forkNorm) with, per decision state (b >= 2),
 * c = ((b − w) / b) · (h / cap), multiplied by 2 / w when w > 2 (many good options are penalised).
 * [tightForks] = decision states with w <= 2, b >= 3, h >= 2 (the floor counts these).
 */
data class ForkMetrics(val forks: List<Fork>, val value: Double, val tightForks: Int) {
    val decisionStates: Int get() = forks.size
}

object Forks {
    /** Measures every decision state (>= 2 legal moves) on [line], a winning line of [level]. */
    fun measure(level: Level, line: List<Move>, config: DifficultyConfig = DifficultyConfig.DEFAULT): ForkMetrics {
        val out = ArrayList<Fork>()
        var s = GameState.initial(level)
        for ((i, m) in line.withIndex()) {
            if (s.status != Status.PLAYING) break
            val f = fork(s, i, config)
            if (f != null) out += f
            s = Engine.apply(s, m).state
        }
        return summarise(out, config)
    }

    /** The fork at state [s] (step [step]), or null if fewer than 2 legal moves. */
    fun fork(s: GameState, step: Int, config: DifficultyConfig = DifficultyConfig.DEFAULT): Fork? {
        val legal = Engine.legalMoves(s)
        if (legal.size < 2) return null
        var w = 0
        var h = 0
        for (m in legal) {
            val child = Engine.apply(s, m).state
            val r = Solver.solve(child, config.forkChildBudget)
            if (r == null || r.solvable) { w++; continue }
            h = maxOf(h, 1 + survival(child, config.forkHorizonCap - 1, config.forkHorizonBudget))
        }
        return Fork(step, legal.size, w, minOf(h, config.forkHorizonCap))
    }

    fun summarise(forks: List<Fork>, config: DifficultyConfig): ForkMetrics {
        val cap = config.forkHorizonCap.toDouble()
        var sum = 0.0
        var tight = 0
        for (f in forks) {
            var c = (f.b - f.w).toDouble() / f.b * (f.h / cap)
            if (f.w > 2) c *= 2.0 / f.w
            sum += c
            if (f.w <= 2 && f.b >= 3 && f.h >= 2) tight++
        }
        return ForkMetrics(forks, (sum / config.forkNorm).coerceIn(0.0, 1.0), tight)
    }

    /**
     * Longest play from the (lost) state [s] until the game ends, searched to [depth] moves; returns [depth] + 1 (=
     * "deeper than the cap") if some line survives that long or the node [budget] runs out.
     */
    private fun survival(s: GameState, depth: Int, budget: Long): Int {
        var nodes = 0L
        val memo = HashMap<Any, Int>()
        fun key(x: GameState): Any = listOf(x.onBoard, x.dock, x.staging.groupingBy { it }.eachCount(), x.queueIndex)
        fun go(x: GameState, d: Int): Int {
            if (x.status != Status.PLAYING) return 0
            if (d == 0 || nodes > budget) return depth + 1
            val k = key(x)
            memo[k]?.let { if (it <= d) return it }
            nodes++
            var best = 0
            for (m in Engine.legalMoves(x)) {
                val r = 1 + go(Engine.apply(x, m).state, d - 1)
                if (r > best) best = r
                if (best > depth) break
            }
            val v = minOf(best, depth + 1)
            if (v <= d) memo[k] = v
            return v
        }
        return go(s, depth)
    }
}
