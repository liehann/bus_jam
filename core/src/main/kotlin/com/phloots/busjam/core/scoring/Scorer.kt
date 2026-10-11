package com.phloots.busjam.core.scoring

import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.solver.FullAnalysis
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
)

/** Owner: gamestate agent. */
object Scorer {
    /**
     * DESIGN.md "Difficulty score":
     * score = 100 × (wTrap·T + wDepth·D + wOrdering·O + wPressure·P + wSize·S), with
     * D = min(1, meanTrapDepth / trapDepthNorm), P = (peakSlots − 1) / 3, S = mean of normalised
     * colours / vehicles / cells. `sizeDominated` = wSize·S > sizeDominanceMax × score/100.
     * An unsolvable (or capped) analysis still gets a report; callers reject it via `analysis.solvable`.
     */
    fun score(level: Level, analysis: FullAnalysis, config: DifficultyConfig = DifficultyConfig.DEFAULT): DifficultyReport {
        val t = analysis.trapRatio.coerceIn(0.0, 1.0)
        val d = (analysis.meanTrapDepth / config.trapDepthNorm).coerceIn(0.0, 1.0)
        val o = analysis.ordering.coerceIn(0.0, 1.0)
        val p = ((analysis.peakSlots - 1) / 3.0).coerceIn(0.0, 1.0)
        val s = (
            norm(level.colors().size, config.colorsMin, config.colorsMax) +
                norm(level.vehicles.size, config.vehiclesMin, config.vehiclesMax) +
                norm(level.width * level.height, config.cellsMin, config.cellsMax)
            ) / 3.0
        val weighted = config.wTrap * t + config.wDepth * d + config.wOrdering * o +
            config.wPressure * p + config.wSize * s
        val score = 100.0 * weighted
        return DifficultyReport(
            score = score,
            trap = t,
            depth = d,
            ordering = o,
            pressure = p,
            size = s,
            sharpDecisions = analysis.sharpDecisions,
            solutionLength = analysis.solution.size,
            sizeDominated = config.wSize * s > config.sizeDominanceMax * weighted,
        )
    }

    private fun norm(value: Int, min: Int, max: Int): Double =
        if (max <= min) 0.0 else ((value - min).toDouble() / (max - min)).coerceIn(0.0, 1.0)
}
