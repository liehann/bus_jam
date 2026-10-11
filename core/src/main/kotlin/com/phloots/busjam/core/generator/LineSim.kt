package com.phloots.busjam.core.generator

import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Limits

/**
 * Minimal, self-contained rules simulation used by the generator to verify an intended extraction order
 * (independent of the Engine so the generator can be checked on its own).
 *
 * Plays [order] tap by tap: each tapped vehicle must be on board, have a clear exit ray and a free slot; it takes
 * the leftmost free slot, then the boarding loop runs (front passenger boards the earliest-arrived matching parked
 * vehicle with free seats; full vehicles depart). Returns the peak number of occupied slots if the line wins,
 * or null if it fails (blocked, slots full, or not won at the end).
 */
internal object LineSim {
    private class Parked(val color: Int, var seatsLeft: Int, val arrival: Int)

    fun peakSlotsIfWins(level: Level, order: List<Int>): Int? {
        val n = level.vehicles.size
        if (order.size != n || order.toSet().size != n) return null
        val occ = IntArray(level.width * level.height) { -1 }
        for (v in level.vehicles) for (c in v.cells()) occ[c.row * level.width + c.col] = v.id
        val slots = arrayOfNulls<Parked>(Limits.SLOT_COUNT)
        var q = 0
        var arrival = 0
        var peak = 0
        for (id in order) {
            val v = level.vehicles[id]
            val free = slots.indexOfFirst { it == null }
            if (free < 0) return null
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (level.inBounds(r, c)) {
                if (occ[r * level.width + c] >= 0) return null
                r += v.dir.dRow; c += v.dir.dCol
            }
            for (cell in v.cells()) occ[cell.row * level.width + cell.col] = -1
            slots[free] = Parked(v.color, v.type.seats, arrival++)
            peak = maxOf(peak, slots.count { it != null })
            // Boarding loop.
            while (q < level.queue.size) {
                val col = level.queue[q]
                var best = -1
                for (i in slots.indices) {
                    val p = slots[i] ?: continue
                    if (p.color == col && p.seatsLeft > 0 && (best < 0 || p.arrival < slots[best]!!.arrival)) best = i
                }
                if (best < 0) break
                val p = slots[best]!!
                p.seatsLeft--
                q++
                if (p.seatsLeft == 0) slots[best] = null
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
