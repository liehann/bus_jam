package com.phloots.crittercafe.core.engine

import com.phloots.crittercafe.core.model.Bits128
import com.phloots.crittercafe.core.model.DockedContainer
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.IgnoreReason
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.model.Transition

/**
 * Pure rules engine (DESIGN.md v2 "Staging area", v4 "Mechanics"). Owner: gamestate agent.
 *
 * [apply]:
 * - Game over / invalid id / not on board / frozen / tie-locked / all 4 dock slots occupied / no fitting slot: same
 *   state + single Event.Ignored (GAME_OVER / INVALID_ID / NOT_ON_BOARD / FROZEN / TIED / DOCK_FULL /
 *   NO_FITTING_SLOT, checked in that order).
 * - Exit path blocked (by a container on the ray in the current facing, or by a closed gate where it leaves the
 *   board): same state + Event.Blocked(containerId, stopRow, stopCol, blockerId), blockerId = Event.Blocked.GATE for
 *   a gate.
 * - Otherwise: the container takes the leftmost free dock slot that accepts its colour (a wide one: the leftmost
 *   pair of adjacent free slots that both accept it) (Event.Released), then the boarding loop runs until nothing
 *   changes: scan dock slots left to right (a wide container counts at its left slot); for the leftmost docked
 *   container with free space for which a staged element has its colour, the OLDEST such staged element boards it
 *   (Event.Boarded); a container that becomes full dispatches at once (Event.Dispatched; a wide one frees both
 *   slots); staging refills from the queue (Event.Staged per element, in queue order). Then status is recomputed.
 *   The release advances every tap-driven mechanic: frozen counters, turntable facings and gate phases are
 *   functions of the number of releases (see [com.phloots.crittercafe.core.model.MechanicsView]).
 *
 * Status: WON (board, dock, staging and queue empty) > DEADLOCK (no legal move because of the dock: all 4 slots
 * occupied with no staged element matching a docked container, or a container could otherwise leave but no free slot
 * fits it) > STUCK (no legal move otherwise) > PLAYING.
 */
object Engine {
    /** Applies a tap. Pure: never mutates [state]. */
    fun apply(state: GameState, move: Move): Transition {
        val id = move.containerId
        val level = state.level
        if (state.status != Status.PLAYING) return ignored(state, id, IgnoreReason.GAME_OVER)
        val check = checkTap(level, state.onBoard, occupiedMask(state.dock), id)
        check.ignore?.let { return ignored(state, id, it) }
        check.blocked?.let { return Transition(state, listOf(it)) }
        val slot = check.slot

        val events = ArrayList<Event>()
        val dock = state.dock.toMutableList()
        val v = level.containers[id]
        val docked = DockedContainer(id, v.color, v.size.capacity)
        dock[slot] = docked
        if (v.wide) dock[slot + 1] = docked
        events += Event.Released(id, slot)

        val staging = state.staging.toMutableList()
        val stagedIdx = state.stagedElementIndices().toMutableList()
        var qi = state.queueIndex
        val queue = level.queue
        val spots = level.stagingSpots
        while (true) {
            var boarded = false
            for (s in 0 until Limits.DOCK_SLOTS) {
                val dc = dock[s] ?: continue
                if (s > 0 && dock[s - 1]?.containerId == dc.containerId) continue // right half of a wide container
                val j = staging.indexOf(dc.color)
                if (j < 0) continue
                val tail = s + 1 < Limits.DOCK_SLOTS && dock[s + 1]?.containerId == dc.containerId
                events += Event.Boarded(stagedIdx[j], j, s, dc.containerId)
                staging.removeAt(j)
                stagedIdx.removeAt(j)
                if (dc.spaceLeft == 1) {
                    dock[s] = null
                    if (tail) dock[s + 1] = null
                    events += Event.Dispatched(s, dc.containerId)
                } else {
                    val nd = dc.copy(spaceLeft = dc.spaceLeft - 1)
                    dock[s] = nd
                    if (tail) dock[s + 1] = nd
                }
                while (staging.size < spots && qi < queue.size) {
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

    /** Bit s set <=> dock slot s is occupied (both halves of a wide container). */
    internal fun <T> occupiedMask(dock: List<T?>): Int {
        var m = 0
        for (s in dock.indices) if (dock[s] != null) m = m or (1 shl s)
        return m
    }

    /** Outcome of the tap checks shared by [apply] and the real-time layer: a [slot], or [ignore], or [blocked]. */
    internal class TapCheck(val slot: Int, val ignore: IgnoreReason?, val blocked: Event.Blocked?)

    /**
     * The tap checks after GAME_OVER, in order: INVALID_ID, NOT_ON_BOARD, FROZEN, TIED, DOCK_FULL (all 4 slots
     * occupied), NO_FITTING_SLOT, then the exit path (Blocked). [occupied]: bit s = dock slot s occupied.
     */
    internal fun checkTap(level: Level, onBoard: Bits128, occupied: Int, id: Int): TapCheck {
        if (id !in level.containers.indices) return TapCheck(-1, IgnoreReason.INVALID_ID, null)
        if (!onBoard[id]) return TapCheck(-1, IgnoreReason.NOT_ON_BOARD, null)
        val d = LevelData.of(level)
        val lo = onBoard.lo and d.allLo
        val hi = onBoard.hi and d.allHi
        val rel = d.released(lo, hi)
        if (d.mech) {
            if (d.frozen[id] > rel) return TapCheck(-1, IgnoreReason.FROZEN, null)
            val t = d.tiedTo[id]
            if (t >= 0 && onBoard[t]) return TapCheck(-1, IgnoreReason.TIED, null)
        }
        if (occupied and 0xF == 0xF) return TapCheck(-1, IgnoreReason.DOCK_FULL, null)
        val slot = if (d.mech) d.fitSlotOcc(occupied, d.color[id], d.wide[id]) else Integer.numberOfTrailingZeros(occupied.inv())
        if (slot < 0) return TapCheck(-1, IgnoreReason.NO_FITTING_SLOT, null)
        val clear = if (d.mech) d.pathClear(id, lo, hi, rel) else d.rayClear(id, lo, hi)
        if (clear) return TapCheck(slot, null, null)
        // Walk the current facing to the first on-board occupant, else the closed gate at the edge.
        val v = level.containers[id]
        val dir = LevelData.CW[d.dirIndex(id, rel)]
        var r = v.row
        var c = v.col
        while (true) {
            val nr = r + dir.dRow
            val nc = c + dir.dCol
            if (!level.inBounds(nr, nc)) return TapCheck(-1, null, Event.Blocked(id, r, c, Event.Blocked.GATE))
            val o = d.occupant[nr * d.width + nc]
            if (o >= 0 && o != id && onBoard[o]) return TapCheck(-1, null, Event.Blocked(id, r, c, o))
            r = nr
            c = nc
        }
    }

    /**
     * Moves that change the state (container on board, thawed, not tie-locked, exit path clear, a fitting dock slot
     * free), in ascending container id. Empty if status != PLAYING.
     */
    fun legalMoves(state: GameState): List<Move> {
        if (state.status != Status.PLAYING || state.dockUsed >= Limits.DOCK_SLOTS) return emptyList()
        val d = LevelData.of(state.level)
        val lo = state.onBoard.lo and d.allLo
        val hi = state.onBoard.hi and d.allHi
        val out = ArrayList<Move>()
        if (d.mech) {
            val occ = occupiedMask(state.dock)
            val rel = d.released(lo, hi)
            for (i in 0 until d.n) {
                val on = if (i < 64) (lo ushr i) and 1L != 0L else (hi ushr (i - 64)) and 1L != 0L
                if (on && d.canLeave(i, lo, hi, rel) && d.fitSlotOcc(occ, d.color[i], d.wide[i]) >= 0) out += Move(i)
            }
            return out
        }
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
        val d = LevelData.of(state.level)
        if (!d.mech) return d.rayClear(containerId, state.onBoard.lo, state.onBoard.hi)
        return checkTap(state.level, state.onBoard, occupiedMask(state.dock), containerId).slot >= 0
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
        if (d.mech) {
            val occ = occupiedMask(state.dock)
            val rel = d.released(lo, hi)
            var dockBlocked = false
            for (i in 0 until d.n) {
                val on = if (i < 64) (lo ushr i) and 1L != 0L else (hi ushr (i - 64)) and 1L != 0L
                if (on && d.canLeave(i, lo, hi, rel)) {
                    if (d.fitSlotOcc(occ, d.color[i], d.wide[i]) >= 0) return Status.PLAYING
                    dockBlocked = true
                }
            }
            return if (dockBlocked) Status.DEADLOCK else Status.STUCK
        }
        for (i in 0 until d.n) {
            val on = if (i < 64) (lo ushr i) and 1L != 0L else (hi ushr (i - 64)) and 1L != 0L
            if (on && d.rayClear(i, lo, hi)) return Status.PLAYING
        }
        return Status.STUCK
    }
}
