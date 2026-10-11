package com.phloots.jam.levelgen

import com.phloots.jam.core.generator.Bot
import com.phloots.jam.core.generator.GenParams
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.scoring.DifficultyConfig
import kotlin.random.Random

/**
 * A candidate recipe: colour count + difficulty tier. Each candidate draws its grid, density and construction knobs
 * from the tier's ranges (deterministically from the candidate seed).
 */
data class Band(
    val name: String,
    val colors: Int,
    val tier: Int,                 // 0..3 = blocking-chain length / hard links / runs ramp; -1 = tutorial
    val candidates: Int,
) {
    val tutorial: Boolean get() = tier < 0
}

object Bands {
    private val mixed = mapOf(ContainerSize.S to 0.5, ContainerSize.M to 0.3, ContainerSize.L to 0.2)
    private val smallHeavy = mapOf(ContainerSize.S to 0.65, ContainerSize.M to 0.25, ContainerSize.L to 0.1)

    /** Tier knobs: chain length range, hard-link fraction, container cap range. */
    private data class Tier(val chain: IntRange, val hard: Double, val maxN: IntRange, val density: ClosedFloatingPointRange<Double>)
    private val tiers = listOf(
        Tier(1..3, 0.4, 42..52, 0.75..0.85),
        Tier(2..5, 0.5, 48..60, 0.78..0.88),
        Tier(4..8, 0.6, 52..68, 0.80..0.90),
        Tier(6..10, 0.7, 58..78, 0.82..0.92),
    )

    /** [scale] multiplies every band's candidate count. */
    fun all(scale: Double = 1.0): List<Band> {
        fun n(x: Int) = maxOf(1, (x * scale).toInt())
        val out = ArrayList<Band>()
        out += Band("tut", 3, -1, n(200))
        for (c in 5..10) for (t in tiers.indices) out += Band("c${c}t$t", c, t, n(1000))
        return out
    }

    private fun grids(colors: Int): List<Pair<Int, Int>> = when (colors) {
        5, 6 -> listOf(10 to 12, 10 to 13, 11 to 12, 11 to 13)
        7, 8 -> listOf(11 to 13, 12 to 13, 11 to 14, 12 to 14)
        else -> listOf(12 to 13, 12 to 14)
    }

    fun params(band: Band, rnd: Random, config: DifficultyConfig, maxSolveNodes: Long): GenParams {
        fun r(range: ClosedFloatingPointRange<Double>) = range.start + rnd.nextDouble() * (range.endInclusive - range.start)
        fun ri(range: IntRange) = range.first + rnd.nextInt(range.last - range.first + 1)
        if (band.tutorial) {
            return GenParams(width = 10, height = 12, colors = band.colors, density = 0.08, maxContainers = 9, minContainers = 6,
                runs = 0, decoyFraction = 0.0, window = 1.0, sizeWeights = smallHeavy, maxTrapRatio = 0.0,
                rejectSizeDominated = false, bounded = false, maxAttempts = 4, maxSolveNodes = maxSolveNodes)
        }
        val t = tiers[band.tier]
        val (w, h) = grids(band.colors).let { it[rnd.nextInt(it.size)] }
        val bots = buildSet {
            if (config.botOneOfEach) add(Bot.ONE_OF_EACH)
            if (config.botGreedyMatch) add(Bot.GREEDY_MATCH)
            if (config.botFirstFree) add(Bot.FIRST_FREE)
        }
        val maxRuns = if (band.colors >= 6) 2 else 1
        return GenParams(
            width = w,
            height = h,
            colors = band.colors,
            density = r(t.density),
            sizeWeights = mixed,
            minContainers = config.floorMinContainers,
            maxContainers = ri(t.maxN),
            blockBias = 2.0 + 2.0 * rnd.nextDouble(),
            runs = if (band.tier == 0) 1 else minOf(maxRuns, 1 + rnd.nextInt(2)),
            runLength = 40..52,
            runMaxBlockers = if (band.tier == 0) 2 else 3,
            chainLength = t.chain,
            hardLinkFraction = t.hard,
            window = 1.0 + 2.0 * rnd.nextDouble(),
            decoyFraction = 0.05 + 0.2 * rnd.nextDouble(),
            rejectBots = bots,
            minRolloutLoss = config.floorMinRolloutLoss,
            maxSolveNodes = maxSolveNodes,
            bounded = true,
            maxUnknownChildren = Int.MAX_VALUE,
            rejectSizeDominated = false,
            maxAttempts = 3,
        )
    }
}
