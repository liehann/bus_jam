package com.phloots.jam.core.generator.reverse

/**
 * Every weight and threshold of the hybrid generator's level fitness in one place (fitness / fitness2 / fitness3 of
 * Hybrid previews 1-3, and the quality axes of [HybridGenerator.fitness4], docs/LEVEL-QUALITY.md). Defaults reproduce
 * the preview-3 formulas exactly; the quality section was set from the playtest labels (docs/LEVEL-QUALITY.md).
 */
data class HybridFitnessConfig(
    // ---- fitness (preview 1): mass + wWide·wide + wBotEarly·botEarly ----
    val wWide: Double = 0.5,
    val wBotEarly: Double = 2.0,
    // ---- fitness2 (preview 2, principles 12-13) ----
    val wLongestWindow: Double = 0.3, val capLongestWindow: Int = 15,
    val wWindows: Double = 0.2, val capWindows: Int = 8,
    val wChainCascades: Double = 0.5, val capChainCascades: Int = 4,
    val wCriticalKeystones: Double = 0.5, val capCriticalKeystones: Int = 3,
    val wSteals: Double = 0.6, val capSteals: Int = 10,
    val wMeanB: Double = 0.3, val meanBFrom: Double = 8.0,
    val wDensity: Double = 10.0, val densityFrom: Double = 0.6,
    // ---- fitness3 (preview 3, principles 14-15) ----
    val wJuggleFirst: Double = 4.0,
    val wJuggleMiddle: Double = 3.0,
    val wLateStart: Double = 0.5, val lateStartFrom: Int = 3, val capLateStart: Int = 12,
    val wFreeRun: Double = 0.25, val freeRunFrom: Int = 3, val capFreeRun: Int = 30,
    val wEarlyFreeWins: Double = 0.8, val earlyFreeWinsFrom: Double = 2.0,
    val wEarlyForks: Double = 1.0, val capEarlyForks: Int = 4,
    val wLateDeepForks: Double = 0.5, val capLateDeepForks: Int = 4,
    val wScarce: Double = 0.8,
    val wFreeOpener: Double = 0.4, val freeOpenerFrom: Int = 3, val capFreeOpener: Int = 25,
    // ---- fitness4 (preview 4): quality axes, each scaled to 0..1 then weighted ----
    /** Hidden decision bits (Σ log2((w + losers with h >= 2) / w)), saturating at [hiddenCap]. */
    val wHidden: Double = 12.0,
    val hiddenCap: Double = 40.0,
    /** Share of the hidden bits in the first third of the line; full credit at [openShareFull]. */
    val wOpening: Double = 5.0,
    val openShareFull: Double = 0.2,
    /** Readability of the climax: half chain lead (moves the chain is on show before it floods, full at [leadFull]),
     *  half the share of deep bits taken after the chain is on show (1 - blind share). */
    val wReadable: Double = 6.0,
    val leadFull: Int = 12,
    /** Climax slack (mean free docks before the flood): full credit at <= [slackTight], none at >= [slackLoose]. */
    val wSlack: Double = 3.0,
    val slackTight: Double = 1.5,
    val slackLoose: Double = 3.0,
    // ---- acceptance floors on the intended line, by tier (0 gentle .. 3 super hard) ----
    /** Minimum hidden bits per tier (liked levels measured 23.7-59.6 on solver lines, disliked 4.2-13.6). */
    val minHidden: List<Double> = listOf(3.0, 8.0, 15.0, 24.0),
    /** Minimum share of hidden bits in the first third per tier (0 = no floor). */
    val minOpenShare: List<Double> = listOf(0.0, 0.08, 0.10, 0.12),
    /** Herald levels: minimum chain lead (moves) and maximum blind deep share. */
    val minLead: Int = 8,
    val maxBlindShare: Double = 0.6,
    /**
     * Scarce-key levels: mean free docks over the states before the flood (averaged over both lines) at most this.
     * Liked HP2 L19 1.50 and HP3 L10 1.38, every disliked level 3.0-4.0; per state, hidden bits peak at 2 free docks
     * (1 free dock makes losses immediate), so the ceiling sits between 2 and 3 rather than at the liked values.
     */
    val maxClimaxSlack: Double = 2.5,
) {
    companion object {
        val DEFAULT = HybridFitnessConfig()
    }
}
