package com.phloots.jam.core.engine

import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.model.Transition

/**
 * Pure rules engine (DESIGN.md v2 "Staging area"). Owner: gamestate agent.
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
        val id = move.containerId
        val level = state.level
        if (state.status != Status.PLAYING) return ignored(state, id, IgnoreReason.GAME_OVER)
        if (id !in level.containers.indices) return ignored(state, id, IgnoreReason.INVALID_ID)
        if (!state.onBoard[id]) return ignored(state, id, IgnoreReason.NOT_ON_BOARD)
        val slot = state.dock.indexOfFirst { it == null }
        if (slot < 0) return ignored(state, id, IgnoreReason.DOCK_FULL)

        val d = LevelData.of(level)
        val lo = state.onBoard.lo
        val hi = state.onBoard.hi
        if (!d.rayClear(id, lo, hi)) {
            val ray = d.rayCells[id]
            val v = level.containers[id]
            for ((k, cell) in ray.withIndex()) {
                val o = d.occupant[cell]
                if (o >= 0 && o != id && state.onBoard[o]) {
                    val stopRow = v.row + v.dir.dRow * k
                    val stopCol = v.col + v.dir.dCol * k
                    return Transition(state, listOf(Event.Blocked(id, stopRow, stopCol, o)))
                }
            }
            error("ray mask says blocked but no blocker found")
        }

        val events = ArrayList<Event>()
        val dock = state.dock.toMutableList()
        val v = level.containers[id]
        dock[slot] = DockedContainer(id, v.color, v.size.capacity)
        events += Event.Released(id, slot)

        val staging = state.staging.toMutableList()
        val stagedIdx = state.stagedElementIndices().toMutableList()
        var qi = state.queueIndex
        val queue = level.queue
        while (true) {
            var boarded = false
            for (s in 0 until Limits.DOCK_SLOTS) {
                val dc = dock[s] ?: continue
                val j = staging.indexOf(dc.color)
                if (j < 0) continue
                events += Event.Boarded(stagedIdx[j], j, s, dc.containerId)
                staging.removeAt(j)
                stagedIdx.removeAt(j)
                if (dc.spaceLeft == 1) {
                    dock[s] = null
                    events += Event.Dispatched(s, dc.containerId)
                } else {
                    dock[s] = dc.copy(spaceLeft = dc.spaceLeft - 1)
                }
                while (staging.size < Limits.STAGING_SPOTS && qi < queue.size) {
                    events += Event.Staged(qi, staging.size)
                    staging += queue[qi]
                    stagedIdx += qi
                    qi++
                }
                boarded = true
                break
            }
            if (!boarded) break
        }

        val next = GameState(level, state.onBoard.without(id), dock, staging, qi, Status.PLAYING)
        return Transition(next.copy(status = computeStatus(next)), events)
    }

    private fun ignored(state: GameState, id: Int, reason: IgnoreReason) =
        Transition(state, listOf(Event.Ignored(id, reason)))

    /**
     * Moves that change the state (container on board, exit ray clear, a dock slot free), in ascending container id.
     * Empty if status != PLAYING.
     */
    fun legalMoves(state: GameState): List<Move> {
        if (state.status != Status.PLAYING || state.dockUsed >= Limits.DOCK_SLOTS) return emptyList()
        val d = LevelData.of(state.level)
        val lo = state.onBoard.lo and d.allLo
        val hi = state.onBoard.hi and d.allHi
        val out = ArrayList<Move>()
        for (i in 0 until d.n) {
            val on = if (i < 64) (lo ushr i) and 1L != 0L else (hi ushr (i - 64)) and 1L != 0L
            if (on && d.rayClear(i, lo, hi)) out += Move(i)
        }
        return out
    }

    /** True iff `Move(containerId)` is in [legalMoves]. */
    fun isTappable(state: GameState, containerId: Int): Boolean {
        if (state.status != Status.PLAYING || state.dockUsed >= Limits.DOCK_SLOTS) return false
        if (containerId !in state.level.containers.indices || !state.onBoard[containerId]) return false
        return LevelData.of(state.level).rayClear(containerId, state.onBoard.lo, state.onBoard.hi)
    }

    /**
     * Status as a pure function of the state's fields (WON > DEADLOCK > STUCK > PLAYING). Assumes the boarding
     * loop has already run (as it has for every Engine-produced state).
     */
    fun computeStatus(state: GameState): Status {
        val won = state.onBoard.isEmpty() && state.dock.all { it == null } && state.staging.isEmpty() &&
            state.queueIndex >= state.level.queue.size
        if (won) return Status.WON
        if (state.dockUsed >= Limits.DOCK_SLOTS) {
            val match = state.dock.any { dc -> dc != null && dc.color in state.staging }
            if (!match) return Status.DEADLOCK
            return Status.STUCK
        }
        val d = LevelData.of(state.level)
        val lo = state.onBoard.lo and d.allLo
        val hi = state.onBoard.hi and d.allHi
        for (i in 0 until d.n) {
            val on = if (i < 64) (lo ushr i) and 1L != 0L else (hi ushr (i - 64)) and 1L != 0L
            if (on && d.rayClear(i, lo, hi)) return Status.PLAYING
        }
        return Status.STUCK
    }
}
