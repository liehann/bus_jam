package com.phloots.jam.core.solver

import com.phloots.jam.core.model.Cell
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.model.Transition

/**
 * Deliberately naive reference implementation of the v2 rules (DESIGN.md "Staging area"), written straight from the
 * spec without any of the engine's precomputation: cells are found by scanning every container, boarding is one
 * element at a time. Used as ground truth for differential tests.
 */
object RefEngine {
    private fun occupant(s: GameState, cell: Cell): Int? =
        s.level.containers.firstOrNull { s.onBoard[it.id] && cell in it.cells() }?.id

    /** (stop cell, blocker id) if container [id]'s drive is blocked, else null. */
    private fun blocker(s: GameState, id: Int): Pair<Cell, Int>? {
        val v = s.level.containers[id]
        var front = Cell(v.row, v.col)
        while (true) {
            val next = Cell(front.row + v.dir.dRow, front.col + v.dir.dCol)
            if (!s.level.inBounds(next.row, next.col)) return null
            val o = occupant(s, next)
            if (o != null && o != id) return front to o
            front = next
        }
    }

    fun legal(s: GameState): List<Move> {
        if (s.status != Status.PLAYING || s.dock.none { it == null }) return emptyList()
        return s.level.containers.filter { s.onBoard[it.id] && blocker(s, it.id) == null }.map { Move(it.id) }
    }

    fun status(s: GameState): Status {
        if (s.onBoard.isEmpty() && s.dock.all { it == null } && s.staging.isEmpty() &&
            s.queueIndex == s.level.queue.size
        ) return Status.WON
        if (s.dock.all { it != null } && s.dock.none { it!!.color in s.staging }) return Status.DEADLOCK
        val anyTappable = s.dock.any { it == null } &&
            s.level.containers.any { s.onBoard[it.id] && blocker(s, it.id) == null }
        return if (anyTappable) Status.PLAYING else Status.STUCK
    }

    fun apply(s: GameState, m: Move): Transition {
        val id = m.containerId
        fun ign(r: IgnoreReason) = Transition(s, listOf(Event.Ignored(id, r)))
        if (s.status != Status.PLAYING) return ign(IgnoreReason.GAME_OVER)
        if (id < 0 || id >= s.level.containers.size) return ign(IgnoreReason.INVALID_ID)
        if (!s.onBoard[id]) return ign(IgnoreReason.NOT_ON_BOARD)
        if (s.dock.none { it == null }) return ign(IgnoreReason.DOCK_FULL)
        blocker(s, id)?.let { (stop, b) -> return Transition(s, listOf(Event.Blocked(id, stop.row, stop.col, b))) }

        val ev = ArrayList<Event>()
        val dock = s.dock.toMutableList()
        val slot = dock.indexOf(null)
        val c = s.level.containers[id]
        dock[slot] = DockedContainer(id, c.color, c.size.capacity)
        ev += Event.Released(id, slot)
        // staging as (colour, element index), oldest first
        val staging = s.staging.zip(s.stagedElementIndices()).toMutableList()
        var qi = s.queueIndex
        loop@ while (true) {
            for (slotIdx in 0 until Limits.DOCK_SLOTS) {
                val dc = dock[slotIdx] ?: continue
                val spot = staging.indexOfFirst { it.first == dc.color }
                if (spot < 0) continue
                ev += Event.Boarded(staging[spot].second, spot, slotIdx, dc.containerId)
                staging.removeAt(spot)
                dock[slotIdx] = if (dc.spaceLeft == 1) null else dc.copy(spaceLeft = dc.spaceLeft - 1)
                if (dc.spaceLeft == 1) ev += Event.Dispatched(slotIdx, dc.containerId)
                while (staging.size < Limits.STAGING_SPOTS && qi < s.level.queue.size) {
                    ev += Event.Staged(qi, staging.size)
                    staging += s.level.queue[qi] to qi
                    qi++
                }
                continue@loop
            }
            break
        }
        val n = GameState(s.level, s.onBoard.without(id), dock, staging.map { it.first }, qi)
        return Transition(n.copy(status = status(n)), ev)
    }
}

/**
 * Brute-force exhaustive search over [RefEngine], memoised on the complete raw state (onBoard, dock with container
 * ids, ordered staging, queueIndex) — no canonicalisation, no pruning. Ground truth for the solver.
 */
class Brute {
    private data class Key(val lo: Long, val hi: Long, val dock: List<DockedContainer?>, val staging: List<Int>, val qi: Int)

    private fun key(s: GameState) = Key(s.onBoard.lo, s.onBoard.hi, s.dock, s.staging, s.queueIndex)

    private val winMemo = HashMap<Key, Boolean>()
    private val occMemo = HashMap<Key, Int>()
    private val lossMemo = HashMap<Key, Int>()

    fun children(s: GameState): List<Pair<Move, GameState>> = RefEngine.legal(s).map { it to RefEngine.apply(s, it).state }

    fun wins(s: GameState): Boolean {
        if (s.status == Status.WON) return true
        if (s.status != Status.PLAYING) return false
        return winMemo.getOrPut(key(s)) { children(s).any { wins(it.second) } }
    }

    /** Min over winning lines of the summed dockUsed of the line's states (final WON excluded); MAX if lost. */
    fun minOcc(s: GameState): Int {
        if (s.status == Status.WON) return 0
        if (s.status != Status.PLAYING) return Int.MAX_VALUE
        return occMemo.getOrPut(key(s)) {
            val best = children(s).minOfOrNull { minOcc(it.second) } ?: Int.MAX_VALUE
            if (best == Int.MAX_VALUE) best else best + s.dockUsed
        }
    }

    /** Longest play from a losing state until the game ends. */
    fun lossDepth(s: GameState): Int {
        if (s.status != Status.PLAYING) return 0
        return lossMemo.getOrPut(key(s)) { children(s).maxOf { 1 + lossDepth(it.second) } }
    }
}
