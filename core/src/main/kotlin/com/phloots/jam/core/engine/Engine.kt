package com.phloots.jam.core.engine

import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.model.Transition

/**
 * Pure rules engine (DESIGN.md "Rules"). Owner: gamestate agent.
 */
object Engine {
    /**
     * Applies a tap. Pure: never mutates [state].
     *
     * - If the game is over, the id is invalid, the container is not on board, or all slots are full:
     *   returns the same state with a single [Event.Ignored].
     * - If the exit ray (cells from front+1 to the edge) is blocked: returns the same state with [Event.Blocked].
     * - Otherwise: container exits into the leftmost free slot ([Event.Released]), then the boarding loop runs
     *   (Boarded/Dispatched events), then status is recomputed.
     */
    fun apply(state: GameState, move: Move): Transition {
        val v = move.containerId
        val level = state.level
        fun ignored(r: IgnoreReason) = Transition(state, listOf(Event.Ignored(v, r)))
        if (state.status != Status.PLAYING) return ignored(IgnoreReason.GAME_OVER)
        if (v !in level.containers.indices) return ignored(IgnoreReason.INVALID_ID)
        if (!state.isOnBoard(v)) return ignored(IgnoreReason.NOT_ON_BOARD)
        val free = state.dock.indexOfFirst { it == null }
        if (free < 0) return ignored(IgnoreReason.DOCK_FULL)

        val d = LevelData.of(level)
        val container = level.containers[v]
        val ray = d.rayCells[v]
        for (i in ray.indices) {
            val o = d.occupant[ray[i]]
            if (o >= 0 && o != v && state.isOnBoard(o)) {
                val stop = if (i == 0) container.row * d.width + container.col else ray[i - 1]
                return Transition(state, listOf(Event.Blocked(v, stop / d.width, stop % d.width, o)))
            }
        }

        val events = ArrayList<Event>()
        val slots = state.dock.toMutableList()
        slots[free] = DockedContainer(v, container.color, container.size.capacity, state.arrivalCounter)
        events += Event.Released(v, free)
        val onBoard = state.onBoard and (1L shl v).inv()

        // Boarding loop.
        var qi = state.queueIndex
        val queue = level.queue
        while (qi < queue.size) {
            val c = queue[qi]
            var best = -1
            for (s in slots.indices) {
                val p = slots[s] ?: continue
                if (p.color == c && p.spaceLeft > 0 && (best < 0 || p.arrival < slots[best]!!.arrival)) best = s
            }
            if (best < 0) break
            val p = slots[best]!!
            events += Event.Boarded(qi, best, p.containerId)
            qi++
            if (p.spaceLeft == 1) {
                slots[best] = null
                events += Event.Dispatched(best, p.containerId)
            } else {
                slots[best] = p.copy(spaceLeft = p.spaceLeft - 1)
            }
        }

        val next = GameState(level, onBoard, slots.toList(), qi, state.arrivalCounter + 1, Status.PLAYING)
        return Transition(next.copy(status = computeStatus(next)), events)
    }

    /**
     * Moves that change the state (container on board, exit ray clear, a slot free), in ascending container id.
     * Empty if status != PLAYING.
     */
    fun legalMoves(state: GameState): List<Move> {
        if (state.status != Status.PLAYING) return emptyList()
        if (state.dock.none { it == null }) return emptyList()
        val d = LevelData.of(state.level)
        var m = d.exitableMask(state.onBoard and d.allMask)
        val out = ArrayList<Move>(java.lang.Long.bitCount(m))
        while (m != 0L) {
            out += Move(java.lang.Long.numberOfTrailingZeros(m))
            m = m and (m - 1)
        }
        return out
    }

    /** True iff tapping [containerId] would make the container exit (i.e. `Move(containerId)` is in [legalMoves]). */
    fun isTappable(state: GameState, containerId: Int): Boolean {
        if (state.status != Status.PLAYING) return false
        if (containerId !in state.level.containers.indices || !state.isOnBoard(containerId)) return false
        if (state.dock.none { it == null }) return false
        val d = LevelData.of(state.level)
        return d.rayMask[containerId] and state.onBoard == 0L
    }

    /**
     * Status as a pure function of the state's fields (WON > DEADLOCK > STUCK > PLAYING). Assumes the boarding
     * loop has already run (as it has for every Engine-produced state).
     */
    fun computeStatus(state: GameState): Status {
        val used = state.dockUsed
        if (state.onBoard == 0L && used == 0 && state.queueIndex >= state.level.queue.size) return Status.WON
        if (used == Limits.DOCK_SLOTS) return Status.DEADLOCK
        val d = LevelData.of(state.level)
        if (d.exitableMask(state.onBoard) == 0L) return Status.STUCK
        return Status.PLAYING
    }
}
