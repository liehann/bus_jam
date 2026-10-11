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
        val weighted = config.wRollout * r + config.wChain * c + config.wDeadWeight * w + config.wSize * s +
            config.wTrap * t + config.wDepth * d + config.wOrdering * o + config.wPressure * p
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
        )
    }

    /**
     * DESIGN.md v2 "too easy" floor (levels 2+), except the naive-bot check (generator): fewer than
     * [DifficultyConfig.floorMinContainers] containers or [DifficultyConfig.floorMinColors] colours, rollout loss
     * below [DifficultyConfig.floorMinRolloutLoss] (so [report] must be scored with a rollout loss rate), score below
     * [DifficultyConfig.floorMinScore], or fewer than [DifficultyConfig.floorMinSharpDecisions] sharp decisions.
     */
    fun isTooEasy(level: Level, report: DifficultyReport, config: DifficultyConfig = DifficultyConfig.DEFAULT): Boolean =
        level.containers.size < config.floorMinContainers ||
            level.colors().size < config.floorMinColors ||
            report.rollout < config.floorMinRolloutLoss ||
            report.score < config.floorMinScore ||
            report.sharpDecisions < config.floorMinSharpDecisions

    private fun norm(value: Int, min: Int, max: Int): Double =
        if (max <= min) 0.0 else ((value - min).toDouble() / (max - min)).coerceIn(0.0, 1.0)
}
