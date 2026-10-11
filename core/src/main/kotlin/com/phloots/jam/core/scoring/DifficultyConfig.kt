package com.phloots.jam.core.scoring

/**
 * The single difficulty-tuning config (DESIGN.md "Difficulty score").
 *
 * score = 100 × (wTrap·T + wDepth·D + wOrdering·O + wPressure·P + wSize·S)
 */
data class DifficultyConfig(
    val wTrap: Double = 0.30,
    val wDepth: Double = 0.20,
    val wOrdering: Double = 0.20,
    val wPressure: Double = 0.15,
    val wSize: Double = 0.15,
    /** D = meanTrapDepth / trapDepthNorm, capped at 1. */
    val trapDepthNorm: Double = 8.0,
    /** S normalisation ranges: value mapped linearly from min→0 to max→1, clamped. */
    val colorsMin: Int = 3,
    val colorsMax: Int = 8,
    val containersMin: Int = 4,
    val containersMax: Int = 40,
    val cellsMin: Int = 25,
    val cellsMax: Int = 120,
    /** Reject if wSize·S > sizeDominanceMax × score/100. */
    val sizeDominanceMax: Double = 0.40,
    /** A path state is a "sharp decision" if winningMoves / legalMoves <= this. */
    val sharpThreshold: Double = 0.5,
    /** Minimum sharp decisions required in the tutorial band (cycle 1). */
    val tutorialMinSharpDecisions: Int = 0,
    /** Minimum sharp decisions required outside the tutorial band (generator agent may refine per band). */
    val minSharpDecisions: Int = 1,
) {
    companion object {
        val DEFAULT = DifficultyConfig()
    }
}
