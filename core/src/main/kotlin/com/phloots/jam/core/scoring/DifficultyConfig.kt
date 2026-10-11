package com.phloots.jam.core.scoring

/**
 * The single difficulty-tuning config (DESIGN.md "Difficulty score", v2).
 *
 * score = 100 × (wRollout·R + wChain·C + wDeadWeight·W + wSize·S + wTrap·T + wDepth·D + wOrdering·O + wPressure·P)
 *
 * - R: rollout loss rate of seeded heuristic players (computed by the generator's RolloutDifficulty, passed to
 *   [Scorer.score]); dominant at 40 staging, where one-deviation trap metrics read ~0.
 * - C (blocking chains): 0.5·min(1, digDepthMean / digMeanNorm) + 0.5·min(1, digDepthMax / digMaxNorm).
 * - W (dock dead weight): peakDeadWeight / 3.
 * - P = mean over solution-path states of occupied dock slots / 4 (a PLAYING state has at most 3 occupied, so
 *   P <= 0.75). S normalises colours 4→10, containers 30→110, cells 120→168. T, D, O as in v1.
 * Weights sum to 1.
 *
 * Sustained pressure (DESIGN.md v2 principle 6): when [Scorer.score] gets [PressureMetrics], the score becomes
 * 100 × ((1 − wSustained)·legacy + wSustained·Q) with legacy = the weighted sum above and Q =
 * [PressureMetrics.sustained]; without metrics (e.g. the stored campaign pack) the legacy formula is unchanged.
 * With [ForkMetrics] as well: 100 × ((1 − wSustained − wForks)·legacy + wSustained·Q + wForks·F), F =
 * [ForkMetrics.value]. Each of Q / F only takes its weight when its metrics were supplied.
 */
data class DifficultyConfig(
    val wRollout: Double = 0.45,
    val wChain: Double = 0.20,
    val wDeadWeight: Double = 0.10,
    val wSize: Double = 0.10,
    val wTrap: Double = 0.05,
    val wDepth: Double = 0.04,
    val wOrdering: Double = 0.03,
    val wPressure: Double = 0.03,
    /** C normalisers (dig depths in containers). */
    val digMeanNorm: Double = 4.0,
    val digMaxNorm: Double = 10.0,
    /** D = meanTrapDepth / trapDepthNorm, capped at 1. */
    val trapDepthNorm: Double = 8.0,
    /** S normalisation ranges: value mapped linearly from min→0 to max→1, clamped. */
    val colorsMin: Int = 4,
    val colorsMax: Int = 10,
    val containersMin: Int = 30,
    val containersMax: Int = 110,
    val cellsMin: Int = 120,
    val cellsMax: Int = 168,
    /** Reject if wSize·S > sizeDominanceMax × score/100. */
    val sizeDominanceMax: Double = 0.40,
    /** A path state is a "sharp decision" if winningMoves / legalMoves <= this. */
    val sharpThreshold: Double = 0.5,
    /** Minimum sharp decisions required in the tutorial band (level 1). */
    val tutorialMinSharpDecisions: Int = 0,
    /** Minimum sharp decisions required outside the tutorial band (generator agent may refine per band). */
    val minSharpDecisions: Int = 1,
    /**
     * "Too easy" floor (DESIGN.md v2), applied to every level from level 2 on; the generator discards anything
     * below it. See [Scorer.isTooEasy].
     */
    val floorMinContainers: Int = 30,
    val floorMinScore: Double = 0.0,
    val floorMinSharpDecisions: Int = 0,
    /** v2 floor: at least this many colours from level 2 on (user playtest: 4 colours is trivial). */
    val floorMinColors: Int = 5,
    // ---- naive-bot rejection (levels 2+): reject a level if any enabled bot wins it (bots: generator/Bots.kt) ----
    /** Bot: prefer a free container whose colour isn't docked and has staged elements. */
    val botOneOfEach: Boolean = true,
    /** Bot: prefer a free container matching the most-staged colour (oldest staged breaks ties). */
    val botGreedyMatch: Boolean = true,
    /** Bot: lowest-id free container. */
    val botFirstFree: Boolean = true,
    // ---- rollout difficulty (generator's RolloutDifficulty; its loss rate is Scorer.score's rolloutLossRate) ----
    val rolloutCount: Int = 64,
    /** Softmax temperatures; rollout i uses [i % size]. */
    val rolloutTemperatures: List<Double> = listOf(0.03, 0.08, 0.15, 0.3),
    /** Chance of a uniformly random legal move. */
    val rolloutEpsilon: Double = 0.03,
    /** Every Nth rollout is "careless" ... */
    val rolloutCarelessEvery: Int = 8,
    /** ... with this epsilon. */
    val rolloutCarelessEpsilon: Double = 0.15,
    /** Value penalty for releasing a colour already docked with space. */
    val rolloutStarvePenalty: Double = 0.5,
    /** Too-easy floor (levels 2+), together with all bots losing. */
    val floorMinRolloutLoss: Double = 0.25,
    // ---- sustained pressure (scoring/Pressure.kt; applied when the report was scored with PressureMetrics) ----
    /** Weight of Q in the blended score (Q and rollout are the primary terms). */
    val wSustained: Double = 0.45,
    /** Q normalisers: dock>=3 fraction, mean dwell (moves), tight-decision fraction. */
    val pressureDockHighNorm: Double = 0.6,
    val pressureDwellNorm: Double = 4.0,
    val pressureTightNorm: Double = 0.4,
    /** Solver node budget per child when classifying safe moves. */
    val pressureChildBudget: Long = 20_000,
    /** Pressure floor from level 2 (only for reports scored with PressureMetrics). */
    // Calibrated to what forced-pressure construction reaches today (motif preview v3); DESIGN targets are higher
    // (dock>=3 on 30-50% of steps).
    val floorMinDockHigh: Double = 0.10,
    val floorMinDwell: Double = 0.4,
    val floorMinTight: Double = 0.10,
    // ---- forks (scoring/Forks.kt, DESIGN principle 8; applied when the report was scored with ForkMetrics) ----
    /** Weight of the fork value F in the blended score. */
    val wForks: Double = 0.35,
    /** Solver node budget per child when classifying good / bad moves. */
    val forkChildBudget: Long = 20_000,
    /** Horizon cap (moves) and node budget of the horizon proof per bad move. */
    val forkHorizonCap: Int = 8,
    val forkHorizonBudget: Long = 20_000,
    /** F = min(1, Σ c / forkNorm). */
    val forkNorm: Double = 4.0,
    /** Floor from level 2: at least this many forks with w <= 2, b >= 3, h >= 2. */
    val floorMinForks: Int = 3,
    /**
     * Budgets for [com.phloots.jam.core.solver.Solver.analyzeBounded] (solver nodes): the root solve (exceeded =
     * capped, reject), each path-child classification (exceeded = UNKNOWN, not counted as losing) and each
     * trap-depth search (exceeded = depth counts as ceil(trapDepthNorm)).
     */
    val rootSolveNodeBudget: Long = 2_000_000,
    val childSolveNodeBudget: Long = 20_000,
    val trapDepthNodeBudget: Long = 20_000,
    /** Suggested generator reject threshold for [com.phloots.jam.core.solver.FullAnalysis.unknownChildren]. */
    val maxUnknownChildren: Int = 0,
) {
    companion object {
        val DEFAULT = DifficultyConfig()
    }
}
