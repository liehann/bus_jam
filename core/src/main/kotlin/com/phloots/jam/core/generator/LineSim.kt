package com.phloots.jam.core.generator

import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits

/**
 * Minimal, self-contained v2 rules simulation used by the generator to verify an intended extraction order
 * (independent of the Engine so the generator can be checked on its own).
 *
 * v2 rules (DESIGN.md "Staging area"): 4 positional dock slots, 7 staging spots auto-refilled from the queue in
 * queue order. A tapped container must be on board with a clear exit ray and a free slot; it takes the leftmost
 * free slot. Boarding loop: scan slots left to right; the leftmost docked container with a matching staged element
 * takes the OLDEST such element; a full container dispatches; staging refills; repeat until nothing changes.
 * The line fails on a blocked/duplicate tap, on DEADLOCK (4 slots occupied, nothing staged matches) or if it does
 * not end WON.
 */
internal object LineSim {
    /** Outcome of a winning line: peak dock slots used (right after a release) and the mean slots used before each tap. */
    data class Result(val peakSlots: Int, val meanDockBeforeTap: Double)

    private class Docked(val color: Int, var spaceLeft: Int)

    fun peakSlotsIfWins(level: Level, order: List<Int>): Int? = run(level, order)?.peakSlots

    fun run(level: Level, order: List<Int>): Result? {
        val n = level.containers.size
        if (order.size != n || order.toSet().size != n) return null
        val w = level.width
        val occ = IntArray(w * level.height) { -1 }
        for (v in level.containers) for (c in v.cells()) occ[c.row * w + c.col] = v.id
        val slots = arrayOfNulls<Docked>(Limits.DOCK_SLOTS)
        val staging = ArrayList<Int>(Limits.STAGING_SPOTS)
        var q = 0
        fun refill() {
            while (staging.size < Limits.STAGING_SPOTS && q < level.queue.size) staging += level.queue[q++]
        }
        fun boardLoop() {
            refill()
            while (true) {
                var boarded = false
                for (i in slots.indices) {
                    val p = slots[i] ?: continue
                    val e = staging.indexOf(p.color)
                    if (e < 0) continue
                    staging.removeAt(e)
                    p.spaceLeft--
                    if (p.spaceLeft == 0) slots[i] = null
                    boarded = true
                    break
                }
                if (!boarded) return
                refill()
            }
        }
        refill()
        var peak = 0
        var dockSum = 0
        for (id in order) {
            val v = level.containers[id]
            val free = slots.indexOfFirst { it == null }
            if (free < 0) return null
            dockSum += slots.count { it != null }
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (level.inBounds(r, c)) {
                if (occ[r * w + c] >= 0) return null
                r += v.dir.dRow; c += v.dir.dCol
            }
            for (cell in v.cells()) occ[cell.row * w + cell.col] = -1
            slots[free] = Docked(v.color, v.size.capacity)
            peak = maxOf(peak, slots.count { it != null })
            boardLoop()
            if (slots.all { it != null } && slots.none { s -> staging.contains(s!!.color) }) return null
        }
        val won = q == level.queue.size && staging.isEmpty() && slots.all { it == null }
        return if (won) Result(peak, dockSum.toDouble() / n) else null
    }
}
