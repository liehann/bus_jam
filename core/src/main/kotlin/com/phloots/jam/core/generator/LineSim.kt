package com.phloots.jam.core.generator

import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits

/**
 * Minimal, self-contained rules simulation used by the generator to verify an intended extraction order
 * (independent of the Engine so the generator can be checked on its own).
 *
 * Plays [order] tap by tap: each tapped container must be on board, have a clear exit ray and a free slot; it takes
 * the leftmost free slot, then the boarding loop runs (front element boards the earliest-arrived matching docked
 * container with free capacity; full containers dispatch). Returns the peak number of occupied slots if the line wins,
 * or null if it fails (blocked, slots full, or not won at the end).
 */
internal object LineSim {
    private class Docked(val color: Int, var spaceLeft: Int, val arrival: Int)

    fun peakSlotsIfWins(level: Level, order: List<Int>): Int? {
        val n = level.containers.size
        if (order.size != n || order.toSet().size != n) return null
        val occ = IntArray(level.width * level.height) { -1 }
        for (v in level.containers) for (c in v.cells()) occ[c.row * level.width + c.col] = v.id
        val slots = arrayOfNulls<Docked>(Limits.DOCK_SLOTS)
        var q = 0
        var arrival = 0
        var peak = 0
        for (id in order) {
            val v = level.containers[id]
            val free = slots.indexOfFirst { it == null }
            if (free < 0) return null
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (level.inBounds(r, c)) {
                if (occ[r * level.width + c] >= 0) return null
                r += v.dir.dRow; c += v.dir.dCol
            }
            for (cell in v.cells()) occ[cell.row * level.width + cell.col] = -1
            slots[free] = Docked(v.color, v.size.capacity, arrival++)
            peak = maxOf(peak, slots.count { it != null })
            // Boarding loop.
            while (q < level.queue.size) {
                val col = level.queue[q]
                var best = -1
                for (i in slots.indices) {
                    val p = slots[i] ?: continue
                    if (p.color == col && p.spaceLeft > 0 && (best < 0 || p.arrival < slots[best]!!.arrival)) best = i
                }
                if (best < 0) break
                val p = slots[best]!!
                p.spaceLeft--
                q++
                if (p.spaceLeft == 0) slots[best] = null
            }
            if (slots.all { it != null }) {
                val front = level.queue.getOrNull(q)
                if (front == null || slots.none { it!!.color == front }) return null
            }
        }
        val won = q == level.queue.size && slots.all { it == null }
        return if (won) peak else null
    }
}
