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
    fun score(level: Level, analysis: FullAnalysis, config: DifficultyConfig = DifficultyConfig.DEFAULT): DifficultyReport =
        TODO("gamestate agent")
}
