package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
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
    val metrics: LevelMetrics? = null,
    /** Preview-2 fitness ([HybridGenerator.fitness2] + verified checks). */
    val fitness2: Double = fitness,
    /** Juggling metrics of the intended line (principle 14). */
    val juggle: JuggleMetrics? = null,
    /** Scarce key colour metrics of the intended line (principle 15). */
    val scarce: ScarceMetrics? = null,
    /** Preview-3 fitness ([HybridGenerator.fitness3] on top of [fitness2]). */
    val fitness3: Double = fitness2,
)

object HybridGenerator {
    fun mass(forks: List<Fork>): Double =
        forks.filter { it.w <= 2 }.sumOf { (it.b - it.w).toDouble() / it.b * minOf(it.h, 8) / 8.0 * ln(it.b.toDouble()) }

    fun wide(forks: List<Fork>): Int = forks.count { it.b >= 6 && it.w <= 2 && it.h >= 2 }

    fun fitness(mass: Double, wide: Int, botEarly: Double) = mass + 0.5 * wide + 2.0 * botEarly

    /**
     * Preview-2 fitness: [fitness] plus the principle-12 mismatch windows (longest window, number of windows), chained
     * cascades and critical keystones (see [LevelMetrics]).
     */
    fun fitness2(mass: Double, wide: Int, botEarly: Double, m: LevelMetrics, meanB: Double = 0.0) = fitness(mass, wide, botEarly) +
        0.3 * minOf(m.longestWindow, 15) + 0.2 * minOf(m.windows, 8) + 0.5 * minOf(m.chainCascades, 4) +
        0.5 * minOf(m.criticalKeystones, 3) + 0.6 * minOf(m.steals, 10) + 0.3 * maxOf(0.0, meanB - 8.0) +
        10.0 * maxOf(0.0, m.density - 0.6)

    /** Tight forks (w <= 2, b >= 3) in the first third of the line with a short horizon (2-4): stakes seen at once. */
    fun earlyForks(forks: List<Fork>, moves: Int): List<Fork> = forks.filter { it.step < moves / 3 && it.w <= 2 && it.b >= 3 && it.h in 2..4 }

    /** Tight forks in the last third with a deep horizon (>= 6): the climax. */
    fun lateDeepForks(forks: List<Fork>, moves: Int): List<Fork> = forks.filter { it.step >= 2 * moves / 3 && it.w <= 2 && it.b >= 3 && it.h >= 6 }

    /**
     * Preview-3 fitness (DESIGN.md principle 14): [fit2] plus juggling from the opening (share of juggling states in
     * the first and middle thirds), minus late starts, pressure-free stretches and opening free wins, plus early
     * short-horizon forks and deep forks near the climax, plus the principle-15 axis ([ScarceMetrics.score]: precise
     * spends of a scarce colour leading into a long chain), minus a long [JuggleMetrics.freeOpener] (pure free wins in
     * a row from the start: "find anything that works").
     */
    fun fitness3(fit2: Double, jm: JuggleMetrics, forks: List<Fork>, moves: Int, scarce: ScarceMetrics? = null, freeOpener: Int = 0): Double {
        val start = if (jm.start < 0) moves / 3 else jm.start
        return fit2 + 4.0 * jm.firstThird + 3.0 * jm.middle - 0.5 * maxOf(0, start - 3).coerceAtMost(12) -
            0.25 * maxOf(0, jm.longestFree - 3).coerceAtMost(30) - 0.8 * maxOf(0.0, jm.earlyFreeWins - 2.0) +
            1.0 * minOf(earlyForks(forks, moves).size, 4) + 0.5 * minOf(lateDeepForks(forks, moves).size, 4) + 0.8 * (scarce?.score ?: 0.0) -
            0.4 * maxOf(0, freeOpener - 3).coerceAtMost(25)
    }

    private val LOST_H = Regex("""lost h=(>?\d+)""")
    private val ALT = Regex("""alt lost (\d+) \(h<=(>?\d+)\)""")

    /** Designed failure modes proven lost with a horizon >= 2 (each adds 1 to the fitness). */
    fun verified(checks: List<String>): Int = checks.count { c ->
        val m = LOST_H.find(c)
        val a = ALT.find(c)
        (m != null && (m.groupValues[1].startsWith(">") || m.groupValues[1].toInt() >= 2)) ||
            (a != null && a.groupValues[1].toInt() > 0 && (a.groupValues[2].startsWith(">") || a.groupValues[2].toInt() >= 2))
    }

    /**
     * "Rescue just outside a full ring" (reference L15 loss): play the lost state on with the 1-ply bot until it ends;
     * true if it ends DEADLOCKED with a full staging area while the next elements in line include a docked colour.
     */
    fun rescueLocked(start: GameState): Boolean {
        val bot = LookaheadBot(1)
        var s = start
        var guard = 0
        while (s.status == Status.PLAYING && guard++ < 200) {
            val m = bot.choose(s) ?: break
            s = Engine.apply(s, m).state
        }
        if (s.status != Status.DEADLOCK || s.staging.size < com.phloots.jam.core.model.Limits.STAGING_SPOTS) return false
        val docked = s.dock.filterNotNull().map { it.color }.toSet()
        return s.level.queue.drop(s.queueIndex).take(12).any { it in docked }
    }

    /** Cheap gate: replay + bots. Null if the line does not replay (a construction bug). */
    fun quick(level: Level, line: List<Move>): Pair<List<BotRun>, Double>? {
        if (!ReverseAnalysis.replay(level, line)) return null
        val bots = ReverseAnalysis.runBots(level)
        val losers = bots.filter { !it.won }
        val early = if (losers.isEmpty()) 0.0 else losers.map { 1.0 - it.moves.toDouble() / line.size }.average()
        return bots to early
    }

    fun evaluate(sl: StallLevel, config: DifficultyConfig = DifficultyConfig.DEFAULT, withChecks: Boolean = true, juggleN: Int = 10): HybridEval {
        val lv = sl.level
        val (bots, early) = quick(lv, sl.line) ?: error("hybrid line does not replay (seed ${sl.seed})")
        val solvable = Solver.solve(lv, 2_000_000)?.solvable
        val f = Forks.measure(lv, sl.line, config)
        val p = Pressure.measure(lv, sl.line, config)
        val m = mass(f.forks)
        val w = wide(f.forks)
        val ch = if (withChecks) checks(sl) else emptyList()
        val lm = LevelMetrics.measure(lv, sl.line)
        val meanB = f.forks.map { it.b }.average()
        val f2 = fitness2(m, w, early, lm, meanB) + verified(ch)
        val jm = JuggleMetrics.measure(lv, sl.line, juggleN)
        val sc = ScarceMetrics.measure(lv, sl.line)
        return HybridEval(true, solvable, f, w, m, meanB, bots, early, p, fitness(m, w, early) + verified(ch), ch,
            lm, f2, jm, sc, fitness3(f2, jm, f.forks, sl.line.size, sc, JuggleMetrics.freeOpener(lv)))
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
        fun reachableOffColour(st: GameState, intended: Move): List<Move> {
            val staged = st.staging.toSet()
            return Engine.legalMoves(st).filter { it != intended && st.level.containers[it.containerId].color !in staged }
        }
        fun worst(st: GameState, moves: List<Move>): String {
            if (moves.isEmpty()) return "n/a"
            var lost = 0; var hMax = 0; var won = 0; var unk = 0; var rescue = 0
            for (m in moves) {
                val child = Engine.apply(st, m).state
                val r = Solver.solve(child, budget)
                when {
                    r == null -> unk++
                    r.solvable -> won++
                    else -> {
                        lost++
                        hMax = maxOf(hMax, ReverseAnalysis.lossHorizon(child, 7, 30_000)?.plus(1) ?: 9)
                        if (rescueLocked(child)) rescue++
                    }
                }
            }
            return "off-colour docks lost $lost (h<=${if (hMax == 9) ">8" else hMax}, rescue-locked $rescue) won $won unknown $unk"
        }
        for ((gi, g) in sl.stalls.withIndex()) {
            when {
                g.kind.startsWith("cascade") && g.triggerStep >= 0 ->
                    out += "cascade#$gi@${g.triggerStep}: " + worst(states[g.triggerStep], reachableOffColour(states[g.triggerStep], sl.line[g.triggerStep]))
                g.kind.startsWith("steal") && g.dummies.size >= 2 -> {
                    val cheap = Move(g.dummies[0][0]); val big = Move(g.dummies[1][0])
                    val step = sl.line.indexOf(cheap)
                    if (step >= 0) out += "steal#$gi big-first@$step: " + outcome(states[step], big)
                }
            }
            // Wrong dummy at the first dummy of a wall group: dock an off-colour container that is not the wall.
            if (!g.kind.startsWith("cascade") && g.dummies.isNotEmpty()) {
                val step = sl.line.indexOf(Move(g.dummies[0][0]))
                if (step >= 0) out += "wrong-dummy#$gi@$step: " + worst(states[step], reachableOffColour(states[step], sl.line[step]))
            }
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
