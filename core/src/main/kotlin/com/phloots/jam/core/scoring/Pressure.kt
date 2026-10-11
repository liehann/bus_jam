package com.phloots.jam.core.scoring

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver

/**
 * Sustained dock pressure (DESIGN.md v2 principle 6), measured along a winning line (deterministic). Owner: generator
 * agent (with the lead's go-ahead to touch scoring).
 *
 * Decision states = the states before each move of the line except the first (the player looks at the dock the
 * previous move left behind).
 * @property dockHigh fraction of decision states with >= 3 occupied dock slots
 * @property meanDwell mean over containers of the moves a container stays docked (release move -> dispatch move;
 *   0 = filled on arrival)
 * @property tight fraction of decision states with >= 3 legal moves where only 1-2 moves are safe. A move is safe if
 *   a budgeted [Solver.solve] of the child wins or runs out of budget (unknown is not counted as losing).
 * @property wide number of decision states with >= 3 legal moves (the denominator of [tight])
 */
data class PressureMetrics(
    val dockHigh: Double,
    val meanDwell: Double,
    val tight: Double,
    val wide: Int,
    val tightStates: Int,
    val unknownChildren: Int,
) {
    /**
     * 0..1 combination used by [Scorer]: (dockHigh / dockHighNorm + dwell / dwellNorm + tight / tightNorm) / 3,
     * each capped at 1.
     */
    fun sustained(c: DifficultyConfig): Double =
        ((dockHigh / c.pressureDockHighNorm).coerceIn(0.0, 1.0) +
            (meanDwell / c.pressureDwellNorm).coerceIn(0.0, 1.0) +
            (tight / c.pressureTightNorm).coerceIn(0.0, 1.0)) / 3.0
}

object Pressure {
    /** Measures [line] (must be a winning line of [level]); child solves use [DifficultyConfig.pressureChildBudget]. */
    fun measure(level: Level, line: List<Move>, config: DifficultyConfig = DifficultyConfig.DEFAULT): PressureMetrics {
        var s = GameState.initial(level)
        val released = IntArray(level.containers.size) { -1 }
        var dwellSum = 0L
        var dwellN = 0
        var high = 0
        var states = 0
        var wide = 0
        var tight = 0
        var unknown = 0
        for ((i, m) in line.withIndex()) {
            if (s.status != Status.PLAYING) break
            if (i > 0) {
                states++
                if (s.dockUsed >= 3) high++
                val legal = Engine.legalMoves(s)
                if (legal.size >= 3) {
                    wide++
                    var safe = 0
                    for (c in legal) {
                        val child = Engine.apply(s, c).state
                        val r = Solver.solve(child, config.pressureChildBudget)
                        if (r == null) { unknown++; safe++ } else if (r.solvable) safe++
                        if (safe > 2) break
                    }
                    if (safe in 1..2) tight++
                }
            }
            val t = Engine.apply(s, m)
            for (e in t.events) when (e) {
                is Event.Released -> released[e.containerId] = i
                is Event.Dispatched -> if (released[e.containerId] >= 0) {
                    dwellSum += i - released[e.containerId]; dwellN++
                }
                else -> {}
            }
            s = t.state
        }
        return PressureMetrics(
            dockHigh = if (states == 0) 0.0 else high.toDouble() / states,
            meanDwell = if (dwellN == 0) 0.0 else dwellSum.toDouble() / dwellN,
            tight = if (wide == 0) 0.0 else tight.toDouble() / wide,
            wide = wide,
            tightStates = tight,
            unknownChildren = unknown,
        )
    }
}
