package com.phloots.busjam.core.generator

import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.VehicleType

/**
 * Generation parameters. Owner: generator agent (may extend with defaulted fields).
 *
 * @property levelId id assigned to the produced [Level]
 * @property width board width (<= 10)
 * @property height board height (<= 12)
 * @property colors number of distinct colours (1..8)
 * @property density target fraction of board cells covered by vehicles (0..1)
 * @property typeWeights relative frequency of each vehicle type
 * @property minScore minimum acceptable difficulty score (0..100), inclusive
 * @property maxScore maximum acceptable difficulty score (0..100), inclusive
 * @property minSharpDecisions reject levels with fewer sharp decisions than this
 * @property maxAttempts give up (return null) after this many rejected candidates
 */
data class GenParams(
    val levelId: Int = 0,
    val width: Int = 6,
    val height: Int = 8,
    val colors: Int = 3,
    val density: Double = 0.5,
    val typeWeights: Map<VehicleType, Double> = mapOf(VehicleType.CAR to 0.6, VehicleType.VAN to 0.3, VehicleType.BUS to 0.1),
    val minScore: Double = 0.0,
    val maxScore: Double = 100.0,
    val minSharpDecisions: Int = 0,
    val maxAttempts: Int = 1000,
)

/**
 * Reverse-extraction level generator (DESIGN.md "Generator"). Deterministic for a given [seed] and call sequence.
 * Owner: generator agent.
 */
class Generator(private val seed: Long) {
    /** Returns a solvable level within the requested band, or null if none was found within maxAttempts. */
    fun generate(params: GenParams): Level? = TODO("generator agent")
}
