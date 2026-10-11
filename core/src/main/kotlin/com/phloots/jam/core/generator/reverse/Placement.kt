package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.model.Cell
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir

/**
 * Board construction for reverse generation: un-release = a container slides onto the board from the edge it will
 * exit through, travelling inward along its exit lane. Any stop point along the way leaves its exit ray clear (it
 * just came through there), so every placement is valid for the intended line; a container that stops in another's
 * exit ray blocks it (it is released earlier in forward order), which builds blocking chains naturally.
 */
class Board(val width: Int, val height: Int) {
    /** Cell index -> container id, or -1. */
    val occ = IntArray(width * height) { -1 }
    val placed = HashMap<Int, Container>()

    /** Cells nothing may occupy (e.g. a gutter); exit rays pass through them. */
    val reserved = BooleanArray(width * height)

    fun free(r: Int, c: Int) = r in 0 until height && c in 0 until width && occ[r * width + c] < 0

    private fun placeable(r: Int, c: Int) = free(r, c) && !reserved[r * width + c]

    fun inside(r: Int, c: Int) = r in 0 until height && c in 0 until width

    fun place(v: Container) {
        for (x in v.cells()) {
            check(free(x.row, x.col)) { "overlap at $x" }
            occ[x.row * width + x.col] = v.id
        }
        placed[v.id] = v
    }

    /** Exit-ray cells of [v] (front+1 .. edge). */
    fun ray(v: Container): List<Cell> {
        val out = ArrayList<Cell>()
        var r = v.row + v.dir.dRow
        var c = v.col + v.dir.dCol
        while (inside(r, c)) { out += Cell(r, c); r += v.dir.dRow; c += v.dir.dCol }
        return out
    }

    fun rayClear(v: Container): Boolean = ray(v).all { occ[it.row * width + it.col] < 0 }

    /** All slide-in placements of a container of [size] facing any direction (front cell index f along the lane). */
    fun candidates(id: Int, size: ContainerSize, color: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        val len = size.length
        for (dir in Dir.entries) {
            val lanes = if (dir == Dir.UP || dir == Dir.DOWN) width else height
            val depth = if (dir == Dir.UP || dir == Dir.DOWN) height else width
            for (lane in 0 until lanes) {
                // e_k = k-th cell from the exit edge going inward.
                fun cell(k: Int): Cell = when (dir) {
                    Dir.UP -> Cell(k, lane)
                    Dir.DOWN -> Cell(height - 1 - k, lane)
                    Dir.LEFT -> Cell(lane, k)
                    Dir.RIGHT -> Cell(lane, width - 1 - k)
                }
                var freeRun = 0
                while (freeRun < depth && cell(freeRun).let { free(it.row, it.col) }) freeRun++
                val fmax = freeRun - len
                for (f in 0..fmax) {
                    val front = cell(f)
                    if ((0 until len).any { k -> cell(f + k).let { !placeable(it.row, it.col) } }) continue
                    out += Candidate(Container(id, size, color, front.row, front.col, dir), f == fmax, f)
                }
            }
        }
        return out
    }

    data class Candidate(val v: Container, val stopped: Boolean, val depth: Int)

    /** 4-neighbours of [v]'s cells that are occupied (1 each) or walls ([wall] each), excluding its own cells. */
    fun contact(v: Container, wall: Double = 0.3): Double {
        val own = v.cells().toSet()
        var n = 0.0
        for (x in own) for (d in Dir.entries) {
            val r = x.row + d.dRow
            val c = x.col + d.dCol
            if (Cell(r, c) in own) continue
            if (!inside(r, c)) n += wall else if (occ[r * width + c] >= 0) n += 1.0
        }
        return n
    }

    /** True if [v]'s tail touches another container (it slid in until blocked by one, not by the far wall). */
    fun stoppedByContainer(v: Container): Boolean {
        val t = v.tail()
        val r = t.row - v.dir.dRow
        val c = t.col - v.dir.dCol
        return inside(r, c) && occ[r * width + c] >= 0
    }

    /** Free cells adjacent to [v] that would have all four neighbours blocked after placing it (dead holes). */
    fun holes(v: Container): Int {
        val own = v.cells().toSet()
        var n = 0
        val seen = HashSet<Cell>()
        for (x in own) for (d in Dir.entries) {
            val h = Cell(x.row + d.dRow, x.col + d.dCol)
            if (h in own || !free(h.row, h.col) || !seen.add(h)) continue
            val enclosed = Dir.entries.all { e ->
                val r = h.row + e.dRow
                val c = h.col + e.dCol
                !inside(r, c) || occ[r * width + c] >= 0 || Cell(r, c) in own
            }
            if (enclosed) n++
        }
        return n
    }

    fun cellsUsed(): Int = occ.count { it >= 0 }
}
