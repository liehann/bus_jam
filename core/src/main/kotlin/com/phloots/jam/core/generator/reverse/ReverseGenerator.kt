package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.model.Level
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.ForkMetrics
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.scoring.Pressure
import com.phloots.jam.core.scoring.PressureMetrics

/** Acceptance thresholds for a reverse-generated level (measured along the intended line). */
data class ReverseTargets(
    val minDockHigh: Double = 0.45,
    val minDwell: Double = 1.8,
    val minTightForks: Int = 3,
    val botsMustLose: Boolean = true,
    val maxLookaheadWinners: Int = 0,
    /** Difficulty target: preferred number of tight forks (fitness peaks here instead of growing without bound). */
    val targetTightForks: Int = 10,
)

/**
 * A fully evaluated candidate. [pressure] and [forks] use the shared scoring API (scoring/Pressure.kt, Forks.kt) so
 * the numbers are comparable with the forward generator; [report] adds bot playthroughs and planted-fork checks.
 */
data class ReverseCandidate(
    val rl: ReverseLevel,
    val report: ReverseReport,
    val pressure: PressureMetrics,
    val forks: ForkMetrics,
    val fitness: Double,
    val accepted: Boolean,
    val reject: String,
)

/**
 * Reverse generator front end: director runs over seeds, forward validation, and a small deterministic hill-climb over
 * director parameters.
 */
object ReverseGenerator {
    /** Cheap gates first (replay, bots, line pressure), then the expensive fork / tightness measurements. */
    fun evaluate(rl: ReverseLevel, targets: ReverseTargets, config: DifficultyConfig = DifficultyConfig.DEFAULT): ReverseCandidate? {
        val quick = ReverseAnalysis.analyze(rl, withSolver = false, decisions = false)
        check(quick.replayWon) { "intended line does not replay to WON (seed ${rl.seed})" }
        val winners = quick.bots.filter { it.won }
        val lookWinners = winners.count { it.name.startsWith("look") }
        var reject = ""
        if (targets.botsMustLose && winners.any { !it.name.startsWith("look") }) reject = "naive bot wins"
        else if (targets.botsMustLose && lookWinners > targets.maxLookaheadWinners) reject = "lookahead bot wins"
        else if (quick.dockHigh < targets.minDockHigh) reject = "dock pressure"
        else if (quick.meanDwell < targets.minDwell) reject = "dwell"
        if (reject.isNotEmpty() && targets.botsMustLose) {
            return ReverseCandidate(rl, quick, PressureMetrics(quick.dockHigh, quick.meanDwell, 0.0, 0, 0, 0),
                ForkMetrics(emptyList(), 0.0, 0), cheapFitness(quick), false, reject)
        }
        val full = ReverseAnalysis.analyze(rl, withSolver = true, decisions = true)
        val pressure = Pressure.measure(rl.level, rl.line, config)
        val forks = Forks.measure(rl.level, rl.line, config)
        if (reject.isEmpty() && full.solverSolvable != true) reject = "solver"
        if (reject.isEmpty() && forks.tightForks < targets.minTightForks) reject = "forks"
        val fit = fitness(full, pressure, forks, targets)
        return ReverseCandidate(rl, full, pressure, forks, fit, reject.isEmpty(), reject)
    }

    private fun cheapFitness(r: ReverseReport): Double =
        r.dockHigh + r.meanDwell / 4.0 + r.bots.count { !it.won } * 0.2 - 10.0

    fun fitness(r: ReverseReport, p: PressureMetrics, f: ForkMetrics, t: ReverseTargets): Double =
        4.0 - 0.4 * StrictMath.abs((f.tightForks - t.targetTightForks).toDouble()) + p.dockHigh +
            minOf(p.meanDwell, 4.0) / 4.0 + r.bots.count { !it.won } * 0.2

    /** Best candidate over [seeds] for [params] (accepted ones first, then by fitness). */
    fun best(params: DirectorParams, seeds: List<Long>, targets: ReverseTargets): ReverseCandidate? {
        var best: ReverseCandidate? = null
        for (s in seeds) {
            val rl = Director(params, s).run() ?: continue
            val c = evaluate(rl, targets) ?: continue
            if (best == null || better(c, best)) best = c
        }
        return best
    }

    fun better(a: ReverseCandidate, b: ReverseCandidate): Boolean =
        if (a.accepted != b.accepted) a.accepted else a.fitness > b.fitness

    /**
     * Deterministic hill-climb over director knobs: from [base], each round mutates the current best parameters
     * (seeded by [seed]) and keeps the mutation if its best candidate over [seedsPerTry] seeds is better.
     */
    fun climb(
        base: DirectorParams,
        seed: Long,
        rounds: Int,
        seedsPerTry: Int,
        targets: ReverseTargets,
        log: (String) -> Unit = {},
    ): ReverseCandidate? {
        val rng = Rng(seed xor 0x5DEECE66DL)
        var cur = base
        val seedList = { r: Int -> List(seedsPerTry) { seed * 1000 + r * 31L + it } }
        var best = best(cur, seedList(0), targets)
        log("  r0 ${describe(best)}")
        for (r in 1..rounds) {
            if (best != null && best.accepted && best.fitness > 5.6) break
            val cand = mutate(cur, rng)
            val c = best(cand, seedList(r), targets) ?: continue
            if (best == null || better(c, best)) {
                best = c; cur = cand
                log("  r$r ${describe(best)}")
            }
        }
        return best
    }

    fun describe(c: ReverseCandidate?): String = if (c == null) "none" else
        "fit=%.2f acc=%s %s dock3=%.2f dwell=%.2f tightForks=%d F=%.2f".format(
            c.fitness, c.accepted, c.reject, c.pressure.dockHigh, c.pressure.meanDwell, c.forks.tightForks, c.forks.value)

    private fun mutate(p: DirectorParams, rng: Rng): DirectorParams {
        fun jitter(x: Double, lo: Double, hi: Double) = (x * (0.7 + 0.6 * rng.nextDouble())).coerceIn(lo, hi)
        return when (rng.nextInt(7)) {
            0 -> p.copy(phases = p.phases.map { ph -> if (ph.hold > 0) ph.copy(dwellMin = (ph.dwellMin + rng.nextInt(3) - 1).coerceIn(2, 10), dwellMax = (ph.dwellMax + rng.nextInt(3) - 1).coerceIn(ph.dwellMin + 1, 14)) else ph })
            1 -> p.copy(swapProb = jitter(p.swapProb, 0.2, 0.95))
            2 -> p.copy(emptyWaitProb = jitter(p.emptyWaitProb, 0.1, 0.8))
            3 -> p.copy(sizeWeights = p.sizeWeights.map { jitter(it, 0.3, 3.0) })
            4 -> p.copy(coverFillable = jitter(p.coverFillable, 1.0, 12.0), colorFillPenalty = jitter(p.colorFillPenalty, 1.0, 10.0))
            5 -> p.copy(phases = p.phases.map { ph -> if (ph.hold > 0) ph.copy(decoy = jitter(ph.decoy + 0.05, 0.0, 0.8), chain = (ph.chain + rng.nextInt(3) - 1).coerceIn(0, 6)) else ph })
            else -> p.copy(reserve = (p.reserve + rng.nextInt(3) - 1).coerceIn(1, 3))
        }
    }

    /** Copy of [level] with a new id. */
    fun withId(level: Level, id: Int): Level = level.copy(id = id)
}
