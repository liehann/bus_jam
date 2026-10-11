package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.Fork
import com.phloots.jam.core.scoring.ForkMetrics
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.scoring.Pressure
import com.phloots.jam.core.scoring.PressureMetrics
import com.phloots.jam.core.solver.Solver
import kotlin.math.ln

/**
 * Evaluation of a hybrid (stall / interrupted-run / squatter) level.
 *
 * Calibrated on the user's two data points (2026-10-11): motif preview 2 L18 ("challenging, restarted twice") vs
 * reverse preview 1 L19/L20 ("still kinda easy"). Pressure and the saturated fork value do not separate them; these do
 * (L18 vs L19 / L20, measured on solver lines):
 *  - [mass] = sum over decision states with w <= 2 of (b-w)/b * min(h,8)/8 * ln b: 8.7 vs 0.8 / 4.3;
 *  - [wide] = decision states with b >= 6, w <= 2, h >= 2 (many tempting options, few good, not obvious): 5 vs 0 / 0;
 *  - [botEarly] = mean over losing bots of the fraction of the line still ahead when they lose: 0.84 vs 0.41 / 0.40.
 * [fitness] = mass + 0.5 wide + 2 botEarly (L18 12.9, L20 5.1), plus 1 per designed failure mode verified lost with
 * h >= 2 ([checks]).
 */
data class HybridEval(
    val replayWon: Boolean,
    val solvable: Boolean?,
    val forks: ForkMetrics,
    val wide: Int,
    val mass: Double,
    val meanB: Double,
    val bots: List<BotRun>,
    val botEarly: Double,
    val pressure: PressureMetrics,
    val fitness: Double,
    val checks: List<String>,
)

object HybridGenerator {
    fun mass(forks: List<Fork>): Double =
        forks.filter { it.w <= 2 }.sumOf { (it.b - it.w).toDouble() / it.b * minOf(it.h, 8) / 8.0 * ln(it.b.toDouble()) }

    fun wide(forks: List<Fork>): Int = forks.count { it.b >= 6 && it.w <= 2 && it.h >= 2 }

    fun fitness(mass: Double, wide: Int, botEarly: Double) = mass + 0.5 * wide + 2.0 * botEarly

    private val LOST_H = Regex("""lost h=(>?\d+)""")
    private val ALT = Regex("""alt lost (\d+) \(h<=(>?\d+)\)""")

    /** Designed failure modes proven lost with a horizon >= 2 (each adds 1 to the fitness). */
    fun verified(checks: List<String>): Int = checks.count { c ->
        val m = LOST_H.find(c)
        val a = ALT.find(c)
        (m != null && (m.groupValues[1].startsWith(">") || m.groupValues[1].toInt() >= 2)) ||
            (a != null && a.groupValues[1].toInt() > 0 && (a.groupValues[2].startsWith(">") || a.groupValues[2].toInt() >= 2))
    }

    /** Cheap gate: replay + bots. Null if the line does not replay (a construction bug). */
    fun quick(level: Level, line: List<Move>): Pair<List<BotRun>, Double>? {
        if (!ReverseAnalysis.replay(level, line)) return null
        val bots = ReverseAnalysis.runBots(level)
        val losers = bots.filter { !it.won }
        val early = if (losers.isEmpty()) 0.0 else losers.map { 1.0 - it.moves.toDouble() / line.size }.average()
        return bots to early
    }

    fun evaluate(sl: StallLevel, config: DifficultyConfig = DifficultyConfig.DEFAULT, withChecks: Boolean = true): HybridEval {
        val lv = sl.level
        val (bots, early) = quick(lv, sl.line) ?: error("hybrid line does not replay (seed ${sl.seed})")
        val solvable = Solver.solve(lv, 2_000_000)?.solvable
        val f = Forks.measure(lv, sl.line, config)
        val p = Pressure.measure(lv, sl.line, config)
        val m = mass(f.forks)
        val w = wide(f.forks)
        val ch = if (withChecks) checks(sl) else emptyList()
        return HybridEval(true, solvable, f, w, m, f.forks.map { it.b }.average(), bots, early, p, fitness(m, w, early) + verified(ch), ch)
    }

    /**
     * Forward verification of the designed failure modes:
     *  - interrupted run, "dig A too early": at the first absorb state, docking A's next (late) dummy;
     *  - interrupted run, "wait too long": at the island target's state, every other move;
     *  - squatter valve "too late": at the state before a valve, every other move; "too early": the valve two taps
     *    earlier (if reachable then).
     * Each is reported as "name: lost h=.." / "won" / "unknown" / "n/a".
     */
    fun checks(sl: StallLevel, budget: Long = 60_000): List<String> {
        val out = ArrayList<String>()
        val states = ArrayList<GameState>()
        var s = GameState.initial(sl.level)
        for (m in sl.line) { states += s; s = Engine.apply(s, m).state }
        fun outcome(st: GameState, m: Move): String {
            if (m !in Engine.legalMoves(st)) return "n/a"
            val child = Engine.apply(st, m).state
            val r = Solver.solve(child, budget) ?: return "unknown"
            if (r.solvable) return "won"
            val h = ReverseAnalysis.lossHorizon(child, 7, 60_000)?.plus(1)
            return "lost h=${h ?: ">8"}" + (if (h == 1) " (${child.status}, dock ${child.dockUsed})" else "")
        }
        fun alternatives(st: GameState, intended: Move): String {
            val legal = Engine.legalMoves(st).filter { it != intended }
            var lost = 0; var won = 0; var unk = 0; var hMax = 0
            for (m in legal) {
                val child = Engine.apply(st, m).state
                val r = Solver.solve(child, budget)
                when {
                    r == null -> unk++
                    r.solvable -> won++
                    else -> { lost++; hMax = maxOf(hMax, ReverseAnalysis.lossHorizon(child, 7, 30_000)?.plus(1) ?: 9) }
                }
            }
            return "b=${legal.size + 1} alt lost $lost (h<=${if (hMax == 9) ">8" else hMax}) won $won unknown $unk"
        }
        for ((gi, g) in sl.stalls.withIndex()) {
            if (g.islandOf < 0 || g.islandStep < 0) continue
            val a = sl.stalls[g.islandOf]
            val late = a.dummies.getOrNull(a.dummies.size - a.lateDummies)?.get(0)
            val bFirst = g.dummies.firstOrNull()?.get(0)
            val bStep = if (bFirst != null) sl.line.indexOf(Move(bFirst)) else -1
            if (bStep >= 0 && late != null) out += "IR#$gi dig-early@$bStep: " + outcome(states[bStep], Move(late))
            out += "IR#$gi island@${g.islandStep}: " + alternatives(states[g.islandStep], sl.line[g.islandStep])
        }
        sl.squat?.let { sq ->
            for (v in sq.valves) {
                val step = v[2]
                if (step < 0) continue
                out += "valve@$step: " + alternatives(states[step], sl.line[step])
                if (step >= 2) out += "valve-early@${step - 2}: " + outcome(states[step - 2], Move(v[0]))
            }
        }
        return out
    }
}
