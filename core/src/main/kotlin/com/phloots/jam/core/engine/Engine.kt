package com.phloots.jam.core.engine

import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.model.Transition

/**
 * Pure rules engine (DESIGN.md v2 "Staging area"). Owner: gamestate agent.
 *
 * v2 contract (signatures frozen; bodies pending):
 *
 * [apply]:
 * - Game over / invalid id / not on board / all 4 dock slots occupied: same state + single Event.Ignored
 *   (GAME_OVER / INVALID_ID / NOT_ON_BOARD / DOCK_FULL, checked in that order).
 * - Exit ray blocked: same state + Event.Blocked(containerId, stopRow, stopCol, blockerId).
 * - Otherwise: the container takes the leftmost free dock slot (Event.Released), then the boarding loop runs until
 *   nothing changes: scan dock slots left to right; for the leftmost docked container with free space for which a
 *   staged element has its colour, the OLDEST such staged element boards it (Event.Boarded); a container that becomes
 *   full dispatches at once (Event.Dispatched); staging refills from the queue (Event.Staged per element, in queue
 *   order). Then status is recomputed.
 *
 * Status: WON (board, dock, staging and queue empty) > DEADLOCK (4 slots occupied and no staged element matches a
 * docked container) > STUCK (no tappable container and not won) > PLAYING.
 */
object Engine {
    /** Applies a tap. Pure: never mutates [state]. */
    fun apply(state: GameState, move: Move): Transition {
        TODO("v2")
    }

    /**
     * Moves that change the state (container on board, exit ray clear, a dock slot free), in ascending container id.
     * Empty if status != PLAYING.
     */
    fun legalMoves(state: GameState): List<Move> {
        TODO("v2")
    }

    /** True iff `Move(containerId)` is in [legalMoves]. */
    fun isTappable(state: GameState, containerId: Int): Boolean {
        TODO("v2")
    }

    /**
     * Status as a pure function of the state's fields (WON > DEADLOCK > STUCK > PLAYING). Assumes the boarding
     * loop has already run (as it has for every Engine-produced state).
     */
    fun computeStatus(state: GameState): Status {
        TODO("v2")
    }
}
