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
    /** Quality metrics of the intended line (docs/LEVEL-QUALITY.md), when evaluated with quality. */
    val quality: QualityMetrics? = null,
    /** Quality metrics of the solver's winning line (a second skilled line; the playtest labels were read on it). */
    val qualitySolver: QualityMetrics? = null,
    /** Preview-4 fitness ([HybridGenerator.fitness4] on top of [fitness3], averaged over both lines). */
    val fitness4: Double = fitness3,
)

object HybridGenerator {
    fun mass(forks: List<Fork>): Double =
        forks.filter { it.w <= 2 }.sumOf { (it.b - it.w).toDouble() / it.b * minOf(it.h, 8) / 8.0 * ln(it.b.toDouble()) }

    fun wide(forks: List<Fork>): Int = forks.count { it.b >= 6 && it.w <= 2 && it.h >= 2 }

    fun fitness(mass: Double, wide: Int, botEarly: Double, c: HybridFitnessConfig = HybridFitnessConfig.DEFAULT) =
        mass + c.wWide * wide + c.wBotEarly * botEarly

    /**
     * Preview-2 fitness: [fitness] plus the principle-12 mismatch windows (longest window, number of windows), chained
     * cascades and critical keystones (see [LevelMetrics]). Weights: [HybridFitnessConfig].
     */
    fun fitness2(mass: Double, wide: Int, botEarly: Double, m: LevelMetrics, meanB: Double = 0.0, c: HybridFitnessConfig = HybridFitnessConfig.DEFAULT) =
        fitness(mass, wide, botEarly, c) +
            c.wLongestWindow * minOf(m.longestWindow, c.capLongestWindow) + c.wWindows * minOf(m.windows, c.capWindows) +
            c.wChainCascades * minOf(m.chainCascades, c.capChainCascades) + c.wCriticalKeystones * minOf(m.criticalKeystones, c.capCriticalKeystones) +
            c.wSteals * minOf(m.steals, c.capSteals) + c.wMeanB * maxOf(0.0, meanB - c.meanBFrom) + c.wDensity * maxOf(0.0, m.density - c.densityFrom)

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
    fun fitness3(fit2: Double, jm: JuggleMetrics, forks: List<Fork>, moves: Int, scarce: ScarceMetrics? = null, freeOpener: Int = 0,
                 c: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): Double {
        val start = if (jm.start < 0) moves / 3 else jm.start
        return fit2 + c.wJuggleFirst * jm.firstThird + c.wJuggleMiddle * jm.middle -
            c.wLateStart * maxOf(0, start - c.lateStartFrom).coerceAtMost(c.capLateStart) -
            c.wFreeRun * maxOf(0, jm.longestFree - c.freeRunFrom).coerceAtMost(c.capFreeRun) -
            c.wEarlyFreeWins * maxOf(0.0, jm.earlyFreeWins - c.earlyFreeWinsFrom) +
            c.wEarlyForks * minOf(earlyForks(forks, moves).size, c.capEarlyForks) +
            c.wLateDeepForks * minOf(lateDeepForks(forks, moves).size, c.capLateDeepForks) + c.wScarce * (scarce?.score ?: 0.0) -
            c.wFreeOpener * maxOf(0, freeOpener - c.freeOpenerFrom).coerceAtMost(c.capFreeOpener)
    }

    /** Quality axes of docs/LEVEL-QUALITY.md, each 0..1: information, opening share, readability, climax slack. */
    data class QualityAxes(val info: Double, val opening: Double, val readable: Double, val slack: Double) {
        fun summary() = "info %.2f opening %.2f readable %.2f slack %.2f".format(info, opening, readable, slack)
    }

    fun axes(q: QualityMetrics, c: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): QualityAxes {
        val info = minOf(1.0, q.hidden / c.hiddenCap)
        val open = if (q.hidden <= 0.0) 0.0 else minOf(1.0, q.hiddenThirds[0] / q.hidden / c.openShareFull)
        val hasChain = q.chainAt > 0 && q.chainLen >= 4
        val readable = if (!hasChain) 0.0 else
            0.5 * minOf(1.0, maxOf(0, q.chainLead).toDouble() / c.leadFull) + 0.5 * (1.0 - blindShare(q))
        val slack = if (!hasChain) 0.0 else ((c.slackLoose - q.climaxSlack) / (c.slackLoose - c.slackTight)).coerceIn(0.0, 1.0)
        return QualityAxes(info, open, readable, slack)
    }

    /** Share of the deep bits (h > 3) decided before the chain was on show. */
    fun blindShare(q: QualityMetrics): Double = if (q.deep <= 0.0) 0.0 else minOf(1.0, q.blindDeep / q.deep)

    /**
     * Preview-4 fitness: [fit3] plus the weighted quality axes ([axes]). Information is the base; the opening share,
     * readability and climax slack describe how that information is laid out, so they are scaled by it (a level
     * with few decisions gains nothing from where they sit).
     */
    fun fitness4(fit3: Double, q: QualityMetrics, c: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): Double = fit3 + qualityBonus(q, c)

    /** The quality part of [fitness4] for one line. */
    fun qualityBonus(q: QualityMetrics, c: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): Double {
        val a = axes(q, c)
        return c.wHidden * a.info + a.info * (c.wOpening * a.opening + c.wReadable * a.readable + c.wSlack * a.slack)
    }

    /** [fitness4] averaged over several lines (intended + solver): no single route decides the quality. */
    fun fitness4(fit3: Double, qs: List<QualityMetrics>, c: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): Double =
        fit3 + qs.map { qualityBonus(it, c) }.average()

    /** Forks (principle 8) read off quality states: h = the deepest loser, capped at [cap]. */
    fun forksOf(q: QualityMetrics, cap: Int = 8): List<Fork> = q.states.filter { it.b >= 2 }.map { s ->
        Fork(s.step, s.b, s.w, if (s.loserH.isEmpty()) 0 else minOf(s.loserH.max(), cap))
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
        if (s.status != Status.DEADLOCK || s.staging.size < s.level.stagingSpots) return false
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

    /**
     * Full evaluation of [sl] on its intended line. With [quality], the decision states are measured once by
     * [Quality.measure] (per-loser horizons) and the forks are read off them; [HybridEval.fitness4] is then set.
     */
    fun evaluate(sl: StallLevel, config: DifficultyConfig = DifficultyConfig.DEFAULT, withChecks: Boolean = true, juggleN: Int = 10,
                 quality: Boolean = false, fc: HybridFitnessConfig = HybridFitnessConfig.DEFAULT): HybridEval {
        val lv = sl.level
        val (bots, early) = quick(lv, sl.line) ?: error("hybrid line does not replay (seed ${sl.seed})")
        val solved = Solver.solve(lv, 2_000_000)
        val solvable = solved?.solvable
        val q = if (quality) Quality.measure(lv, sl.line, config.forkChildBudget) else null
        val qs = if (quality && solvable == true) Quality.measure(lv, solved!!.solution, config.forkChildBudget) else null
        val f = if (q != null) Forks.summarise(forksOf(q, config.forkHorizonCap), config) else Forks.measure(lv, sl.line, config)
        val p = Pressure.measure(lv, sl.line, config)
        val m = mass(f.forks)
        val w = wide(f.forks)
        val ch = if (withChecks) checks(sl) else emptyList()
        val lm = LevelMetrics.measure(lv, sl.line)
        val meanB = f.forks.map { it.b }.average()
        val f2 = fitness2(m, w, early, lm, meanB, fc) + verified(ch)
        val jm = JuggleMetrics.measure(lv, sl.line, juggleN)
        val sc = ScarceMetrics.measure(lv, sl.line)
        val f3 = fitness3(f2, jm, f.forks, sl.line.size, sc, JuggleMetrics.freeOpener(lv), fc)
        return HybridEval(true, solvable, f, w, m, meanB, bots, early, p, fitness(m, w, early, fc) + verified(ch), ch,
            lm, f2, jm, sc, f3, q, qs, if (q != null) fitness4(f3, listOfNotNull(q, qs), fc) else f3)
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
