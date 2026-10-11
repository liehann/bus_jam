package com.phloots.jam.levelgen

import com.phloots.jam.core.generator.GenParams
import com.phloots.jam.core.model.ContainerSize

/**
 * A candidate recipe: a family of [GenParams] sampled per candidate. Each candidate draws its grid, density and
 * queue knobs from the given ranges (deterministically from the candidate seed).
 */
data class Band(
    val name: String,
    val colors: Int,
    val sizes: List<Pair<Int, Int>>,           // (width, height) options
    val density: ClosedFloatingPointRange<Double>,
    val slots: IntRange,                       // maxIntendedSlots options (base slot budget)
    val crunches: IntRange,                    // crunch windows (only used when base slots < 4)
    val interleave: ClosedFloatingPointRange<Double>,
    val decoy: ClosedFloatingPointRange<Double>,
    val sizeWeights: Map<ContainerSize, Double>,
    val minSharp: Int,
    val tutorial: Boolean = false,
    val candidates: Int,
)

object Bands {
    private val smallHeavy = mapOf(ContainerSize.S to 0.75, ContainerSize.M to 0.2, ContainerSize.L to 0.05)
    private val mixed = mapOf(ContainerSize.S to 0.55, ContainerSize.M to 0.3, ContainerSize.L to 0.15)
    private val heavy = mapOf(ContainerSize.S to 0.45, ContainerSize.M to 0.33, ContainerSize.L to 0.22)

    /** [scale] multiplies every band's candidate count. */
    fun all(scale: Double = 1.0): List<Band> {
        fun n(x: Int) = maxOf(1, (x * scale).toInt())
        return listOf(
            // Tutorial: no traps, 3–4 colours, tiny boards, sequential queue.
            Band("tut3", 3, listOf(5 to 5, 5 to 6, 6 to 6), 0.30..0.50, 1..2, 0..0, 0.0..0.2, 0.0..0.0, smallHeavy, 0, true, n(1200)),
            Band("tut4", 4, listOf(5 to 6, 6 to 6, 6 to 7), 0.35..0.60, 1..3, 0..0, 0.0..0.4, 0.0..0.1, smallHeavy, 0, true, n(2400)),
            Band("c4", 4, listOf(5 to 6, 6 to 6, 6 to 7, 6 to 8, 7 to 8), 0.40..0.75, 2..4, 1..3, 0.2..0.7, 0.0..0.3, smallHeavy, 1, false, n(14000)),
            Band("c5", 5, listOf(6 to 7, 6 to 8, 7 to 8, 7 to 9, 8 to 9), 0.45..0.80, 2..4, 1..3, 0.2..0.8, 0.05..0.3, mixed, 1, false, n(12000)),
            Band("c6", 6, listOf(7 to 8, 7 to 9, 8 to 9, 8 to 10, 8 to 11), 0.50..0.85, 2..4, 1..4, 0.25..0.8, 0.05..0.3, mixed, 2, false, n(10000)),
            Band("c7", 7, listOf(8 to 9, 8 to 10, 9 to 10, 9 to 11, 9 to 12), 0.55..0.85, 3..4, 1..4, 0.3..0.9, 0.05..0.3, heavy, 2, false, n(10000)),
            Band("c8", 8, listOf(9 to 10, 9 to 11, 9 to 12, 10 to 11, 10 to 12), 0.55..0.85, 3..4, 1..4, 0.3..1.0, 0.05..0.3, heavy, 3, false, n(12000)),
        )
    }

    fun params(band: Band, rnd: kotlin.random.Random): GenParams {
        val (w, h) = band.sizes[rnd.nextInt(band.sizes.size)]
        val base = band.slots.first + rnd.nextInt(band.slots.last - band.slots.first + 1)
        fun r(range: ClosedFloatingPointRange<Double>) = range.start + rnd.nextDouble() * (range.endInclusive - range.start)
        return GenParams(
            width = w,
            height = h,
            colors = band.colors,
            density = r(band.density),
            sizeWeights = band.sizeWeights,
            minSharpDecisions = band.minSharp,
            maxAttempts = 4,
            maxIntendedSlots = base,
            crunches = if (base < 4) band.crunches.first + rnd.nextInt(band.crunches.last - band.crunches.first + 1) else 0,
            crunchLength = 4 + rnd.nextInt(8),
            skipAhead = rnd.nextDouble() * 0.8,
            interleave = r(band.interleave),
            decoyFraction = r(band.decoy),
            maxTrapRatio = if (band.tutorial) 0.0 else 1.0,
            rejectSizeDominated = !band.tutorial,
        )
    }
}
