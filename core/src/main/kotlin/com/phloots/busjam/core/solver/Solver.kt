package com.phloots.busjam.core.solver

import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Move

/** Search statistics (for diagnostics / scorer). */
data class SolveStats(val nodesExpanded: Long, val statesMemoized: Long)

/** Result of [Solver.solve]. [solution] is empty when not solvable (or when the state is already WON). */
data class SolveResult(val solvable: Boolean, val solution: List<Move>, val stats: SolveStats)

/**
 * Raw scoring metrics from the full offline search (DESIGN.md "Difficulty score"). Normalisation and weighting
 * happen in [com.phloots.busjam.core.scoring.Scorer]; values here are un-weighted.
 *
 * @property solution the min-slot-pressure winning line (empty if unsolvable)
 * @property trapRatio T: mean over solution-path states of losingLegalMoves / legalMoves (0..1)
 * @property meanTrapDepth D before normalisation: mean over trap moves of the longest play from the mistake to a
 *   visible deadlock, in moves (scorer divides by DifficultyConfig.trapDepthNorm and caps at 1)
 * @property ordering O: fraction of decision states (>=2 legal moves) on the path with exactly 1 winning move (0..1)
 * @property peakSlots peak slots used along [solution] (1..4)
 * @property sharpDecisions number of path states where <= 50% of legal moves are winning
 * @property decisionStates number of path states with >= 2 legal moves
 * @property reachableStates number of distinct states explored by the full search
 */
data class FullAnalysis(
    val solvable: Boolean,
    val solution: List<Move>,
    val trapRatio: Double,
    val meanTrapDepth: Double,
    val ordering: Double,
    val peakSlots: Int,
    val sharpDecisions: Int,
    val decisionStates: Int,
    val reachableStates: Long,
)

/**
 * DFS + memoization solver. Memo key: onBoard + sorted (colour, seatsLeft) of slots + queueIndex.
 * Owner: gamestate agent.
 */
object Solver {
    /** Finds one winning line from [state]. Must be < 50 ms for any shipped level on JVM. */
    fun solve(state: GameState): SolveResult = TODO("gamestate agent")

    fun solve(level: Level): SolveResult = solve(GameState.initial(level))

    /** Next move of a winning line from [state], or null if no win is reachable (or game over). */
    fun hint(state: GameState): Move? = TODO("gamestate agent")

    /** Full offline search computing scoring metrics. May be slow; used by generator/levelgen only. */
    fun analyze(level: Level): FullAnalysis = TODO("gamestate agent")
}
