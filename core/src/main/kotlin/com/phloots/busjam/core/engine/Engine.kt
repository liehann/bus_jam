package com.phloots.busjam.core.engine

import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.IgnoreReason
import com.phloots.busjam.core.model.Limits
import com.phloots.busjam.core.model.Move
import com.phloots.busjam.core.model.ParkedVehicle
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.model.Transition

/**
 * Pure rules engine (DESIGN.md "Rules"). Owner: gamestate agent.
 */
object Engine {
    /**
     * Applies a tap. Pure: never mutates [state].
     *
     * - If the game is over, the id is invalid, the vehicle is not on board, or all slots are full:
     *   returns the same state with a single [Event.Ignored].
     * - If the exit ray (cells from front+1 to the edge) is blocked: returns the same state with [Event.Bumped].
     * - Otherwise: vehicle exits into the leftmost free slot ([Event.Exited]), then the boarding loop runs
     *   (Boarded/Departed events), then status is recomputed.
     */
    fun apply(state: GameState, move: Move): Transition {
        val v = move.vehicleId
        val level = state.level
        fun ignored(r: IgnoreReason) = Transition(state, listOf(Event.Ignored(v, r)))
        if (state.status != Status.PLAYING) return ignored(IgnoreReason.GAME_OVER)
        if (v !in level.vehicles.indices) return ignored(IgnoreReason.INVALID_ID)
        if (!state.isOnBoard(v)) return ignored(IgnoreReason.NOT_ON_BOARD)
        val free = state.slots.indexOfFirst { it == null }
        if (free < 0) return ignored(IgnoreReason.SLOTS_FULL)

        val d = LevelData.of(level)
        val vehicle = level.vehicles[v]
        val ray = d.rayCells[v]
        for (i in ray.indices) {
            val o = d.occupant[ray[i]]
            if (o >= 0 && o != v && state.isOnBoard(o)) {
                val stop = if (i == 0) vehicle.row * d.width + vehicle.col else ray[i - 1]
                return Transition(state, listOf(Event.Bumped(v, stop / d.width, stop % d.width, o)))
            }
        }

        val events = ArrayList<Event>()
        val slots = state.slots.toMutableList()
        slots[free] = ParkedVehicle(v, vehicle.color, vehicle.type.seats, state.arrivalCounter)
        events += Event.Exited(v, free)
        val onBoard = state.onBoard and (1L shl v).inv()

        // Boarding loop.
        var qi = state.queueIndex
        val queue = level.queue
        while (qi < queue.size) {
            val c = queue[qi]
            var best = -1
            for (s in slots.indices) {
                val p = slots[s] ?: continue
                if (p.color == c && p.seatsLeft > 0 && (best < 0 || p.arrival < slots[best]!!.arrival)) best = s
            }
            if (best < 0) break
            val p = slots[best]!!
            events += Event.Boarded(qi, best, p.vehicleId)
            qi++
            if (p.seatsLeft == 1) {
                slots[best] = null
                events += Event.Departed(best, p.vehicleId)
            } else {
                slots[best] = p.copy(seatsLeft = p.seatsLeft - 1)
            }
        }

        val next = GameState(level, onBoard, slots.toList(), qi, state.arrivalCounter + 1, Status.PLAYING)
        return Transition(next.copy(status = computeStatus(next)), events)
    }

    /**
     * Moves that change the state (vehicle on board, exit ray clear, a slot free), in ascending vehicle id.
     * Empty if status != PLAYING.
     */
    fun legalMoves(state: GameState): List<Move> {
        if (state.status != Status.PLAYING) return emptyList()
        if (state.slots.none { it == null }) return emptyList()
        val d = LevelData.of(state.level)
        var m = d.exitableMask(state.onBoard and d.allMask)
        val out = ArrayList<Move>(java.lang.Long.bitCount(m))
        while (m != 0L) {
            out += Move(java.lang.Long.numberOfTrailingZeros(m))
            m = m and (m - 1)
        }
        return out
    }

    /** True iff tapping [vehicleId] would make the vehicle exit (i.e. `Move(vehicleId)` is in [legalMoves]). */
    fun isTappable(state: GameState, vehicleId: Int): Boolean {
        if (state.status != Status.PLAYING) return false
        if (vehicleId !in state.level.vehicles.indices || !state.isOnBoard(vehicleId)) return false
        if (state.slots.none { it == null }) return false
        val d = LevelData.of(state.level)
        return d.rayMask[vehicleId] and state.onBoard == 0L
    }

    /**
     * Status as a pure function of the state's fields (WON > DEADLOCK > STUCK > PLAYING). Assumes the boarding
     * loop has already run (as it has for every Engine-produced state).
     */
    fun computeStatus(state: GameState): Status {
        val used = state.slotsUsed
        if (state.onBoard == 0L && used == 0 && state.queueIndex >= state.level.queue.size) return Status.WON
        if (used == Limits.SLOT_COUNT) return Status.DEADLOCK
        val d = LevelData.of(state.level)
        if (d.exitableMask(state.onBoard) == 0L) return Status.STUCK
        return Status.PLAYING
    }
}
