package com.phloots.busjam.core.engine

import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Move
import com.phloots.busjam.core.model.Transition

/**
 * Pure rules engine (DESIGN.md "Rules"). Owner: gamestate agent.
 */
object Engine {
    /**
     * Applies a tap. Pure: never mutates [state].
     *
     * - If the game is over, the id is invalid, the vehicle is not on board, or all slots are full:
     *   returns the same state with a single [com.phloots.busjam.core.model.Event.Ignored].
     * - If the exit ray (cells from front+1 to the edge) is blocked: returns the same state with
     *   [com.phloots.busjam.core.model.Event.Bumped].
     * - Otherwise: vehicle exits into the leftmost free slot ([com.phloots.busjam.core.model.Event.Exited]),
     *   then the boarding loop runs (Boarded/Departed events), then status is recomputed.
     */
    fun apply(state: GameState, move: Move): Transition = TODO("gamestate agent")

    /**
     * Moves that change the state (vehicle on board, exit ray clear, a slot free), in ascending vehicle id.
     * Empty if status != PLAYING.
     */
    fun legalMoves(state: GameState): List<Move> = TODO("gamestate agent")

    /** True iff tapping [vehicleId] would make the vehicle exit (i.e. `Move(vehicleId)` is in [legalMoves]). */
    fun isTappable(state: GameState, vehicleId: Int): Boolean = TODO("gamestate agent")
}
