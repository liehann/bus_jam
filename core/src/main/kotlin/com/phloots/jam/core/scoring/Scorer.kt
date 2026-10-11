package com.phloots.jam.core.scoring

import com.phloots.jam.core.model.Level
import com.phloots.jam.core.solver.FullAnalysis
import kotlinx.serialization.Serializable

/**
 * Scored difficulty. Components are normalised to 0..1 (before weighting).
 *
 * @property score 0..100
 * @property sizeDominated true if the size term exceeds the allowed share of the score (reject)
 */
@Serializable
data class DifficultyReport(
    val score: Double,
    val trap: Double,
    val depth: Double,
    val ordering: Double,
    val pressure: Double,
    val size: Double,
    val sharpDecisions: Int,
    val solutionLength: Int,
    val sizeDominated: Boolean,
    val rollout: Double = 0.0,
    val chain: Double = 0.0,
    val deadWeight: Double = 0.0,
    /** Sustained pressure ([PressureMetrics]); meaningful only when [pressureMeasured]. */
    val pressureMeasured: Boolean = false,
    val dockHigh: Double = 0.0,
    val dwell: Double = 0.0,
    val tight: Double = 0.0,
    /** Q = [PressureMetrics.sustained], 0..1. */
    val sustained: Double = 0.0,
    /** Forks ([ForkMetrics]); meaningful only when [forksMeasured]. */
    val forksMeasured: Boolean = false,
    val forkValue: Double = 0.0,
    val tightForks: Int = 0,
    val forkStates: Int = 0,
)

/** Owner: gamestate agent. */
object Scorer {
    /**
     * DESIGN.md "Difficulty score" (v2), see [DifficultyConfig] for the formula. [rolloutLossRate] (0..1) comes from
     * the generator's rollout players; null scores the rollout term as 0. `sizeDominated` =
     * wSize·S > sizeDominanceMax × score/100. An unsolvable (or capped) analysis still gets a report; callers reject
     * it via `analysis.solvable`.
     */
    fun score(
        level: Level,
        analysis: FullAnalysis,
        config: DifficultyConfig = DifficultyConfig.DEFAULT,
        rolloutLossRate: Double? = null,
        pressure: PressureMetrics? = null,
        forks: ForkMetrics? = null,
    ): DifficultyReport {
        val t = analysis.trapRatio.coerceIn(0.0, 1.0)
        val d = (analysis.meanTrapDepth / config.trapDepthNorm).coerceIn(0.0, 1.0)
        val o = analysis.ordering.coerceIn(0.0, 1.0)
        val p = (analysis.meanDockUsed / 4.0).coerceIn(0.0, 1.0)
        val s = (
            norm(level.colors().size, config.colorsMin, config.colorsMax) +
                norm(level.containers.size, config.containersMin, config.containersMax) +
                norm(level.width * level.height, config.cellsMin, config.cellsMax)
            ) / 3.0
        val r = (rolloutLossRate ?: 0.0).coerceIn(0.0, 1.0)
        val c = 0.5 * (analysis.digDepthMean / config.digMeanNorm).coerceIn(0.0, 1.0) +
            0.5 * (analysis.digDepthMax / config.digMaxNorm).coerceIn(0.0, 1.0)
        val w = (analysis.peakDeadWeight / 3.0).coerceIn(0.0, 1.0)
        val legacy = config.wRollout * r + config.wChain * c + config.wDeadWeight * w + config.wSize * s +
            config.wTrap * t + config.wDepth * d + config.wOrdering * o + config.wPressure * p
        val q = pressure?.sustained(config) ?: 0.0
        val wq = if (pressure != null) config.wSustained else 0.0
        val wf = if (forks != null) config.wForks else 0.0
        val fv = forks?.value ?: 0.0
        val weighted = (1.0 - wq - wf) * legacy + wq * q + wf * fv
        return DifficultyReport(
            score = 100.0 * weighted,
            trap = t,
            depth = d,
            ordering = o,
            pressure = p,
            size = s,
            sharpDecisions = analysis.sharpDecisions,
            solutionLength = analysis.solution.size,
            sizeDominated = config.wSize * s > config.sizeDominanceMax * weighted,
            rollout = r,
            chain = c,
            deadWeight = w,
            pressureMeasured = pressure != null,
            dockHigh = pressure?.dockHigh ?: 0.0,
            dwell = pressure?.meanDwell ?: 0.0,
            tight = pressure?.tight ?: 0.0,
            sustained = q,
            forksMeasured = forks != null,
            forkValue = fv,
            tightForks = forks?.tightForks ?: 0,
            forkStates = forks?.decisionStates ?: 0,
        )
    }

    /**
     * DESIGN.md v2 "too easy" floor (levels 2+), except the naive-bot check (generator): fewer than
     * [DifficultyConfig.floorMinContainers] containers or [DifficultyConfig.floorMinColors] colours, rollout loss
     * below [DifficultyConfig.floorMinRolloutLoss] (so [report] must be scored with a rollout loss rate), score below
     * [DifficultyConfig.floorMinScore], fewer than [DifficultyConfig.floorMinSharpDecisions] sharp decisions, or (for
     * reports scored with [PressureMetrics]) below the pressure floor (dock>=3 fraction, mean dwell, tight fraction).
     */
    fun isTooEasy(level: Level, report: DifficultyReport, config: DifficultyConfig = DifficultyConfig.DEFAULT): Boolean =
        level.containers.size < config.floorMinContainers ||
            level.colors().size < config.floorMinColors ||
            report.rollout < config.floorMinRolloutLoss ||
            report.score < config.floorMinScore ||
            report.sharpDecisions < config.floorMinSharpDecisions ||
            (report.pressureMeasured && (report.dockHigh < config.floorMinDockHigh ||
                report.dwell < config.floorMinDwell || report.tight < config.floorMinTight)) ||
            (report.forksMeasured && report.tightForks < config.floorMinForks)

    private fun norm(value: Int, min: Int, max: Int): Double =
        if (max <= min) 0.0 else ((value - min).toDouble() / (max - min)).coerceIn(0.0, 1.0)
}
