package com.phloots.busjam.levelgen

import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.Level

/**
 * Symmetry-aware de-duplication key. Two levels get the same key if one is a horizontal flip, vertical flip,
 * 180° rotation (or, for square boards, a 90°/270° rotation or diagonal reflection) of the other, up to a
 * relabelling of colours. Colours are relabelled by first appearance in the queue (geometry-independent).
 */
object Canonical {
    fun key(level: Level): String {
        val relabel = HashMap<Int, Int>()
        for (c in level.queue) relabel.getOrPut(c) { relabel.size }
        val queuePart = level.queue.joinToString("") { ('a' + relabel.getValue(it)).toString() }
        val w = level.width
        val h = level.height
        val transforms = ArrayList<(Int, Int, Dir) -> Triple<Int, Int, Dir>>()
        transforms += { r, c, d -> Triple(r, c, d) }
        transforms += { r, c, d -> Triple(r, w - 1 - c, flipH(d)) }
        transforms += { r, c, d -> Triple(h - 1 - r, c, flipV(d)) }
        transforms += { r, c, d -> Triple(h - 1 - r, w - 1 - c, flipV(flipH(d))) }
        if (w == h) {
            // Transpose and its compositions with the flips give the remaining 4 square symmetries.
            transforms += { r, c, d -> Triple(c, r, transpose(d)) }
            transforms += { r, c, d -> Triple(c, w - 1 - r, flipH(transpose(d))) }
            transforms += { r, c, d -> Triple(h - 1 - c, r, flipV(transpose(d))) }
            transforms += { r, c, d -> Triple(h - 1 - c, w - 1 - r, flipV(flipH(transpose(d)))) }
        }
        val best = transforms.minOf { t ->
            level.vehicles.map { v ->
                val (r, c, d) = t(v.row, v.col, v.dir)
                "${r.toString().padStart(2, '0')}${c.toString().padStart(2, '0')}${d.ordinal}${v.type.ordinal}${('a' + relabel.getValue(v.color))}"
            }.sorted().joinToString(",")
        }
        // Square transposes swap width/height, which is fine since w == h; otherwise dims are preserved.
        return "${w}x$h|$best|$queuePart"
    }

    private fun flipH(d: Dir) = when (d) { Dir.LEFT -> Dir.RIGHT; Dir.RIGHT -> Dir.LEFT; else -> d }
    private fun flipV(d: Dir) = when (d) { Dir.UP -> Dir.DOWN; Dir.DOWN -> Dir.UP; else -> d }
    private fun transpose(d: Dir) = when (d) { Dir.UP -> Dir.LEFT; Dir.LEFT -> Dir.UP; Dir.DOWN -> Dir.RIGHT; Dir.RIGHT -> Dir.DOWN }
}
