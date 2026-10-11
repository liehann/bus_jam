package com.phloots.jam.core.engine

import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits

/**
 * Per-level precomputed data plus a packed, allocation-free state representation used by the solver hot path.
 *
 * Packed state = (onBoard: Long, aux: Long):
 *  - aux bits [13*s, 13*s+13) for slot s (0..3): spaceLeft (4 bits, 0 == empty slot), colour (3 bits),
 *    arrival (6 bits; only the relative order matters).
 *  - aux bits [52, 64): queueIndex (12 bits).
 *
 * The arrival stamp for a newly docked container is `n - bitCount(onBoard)` (number of containers already released),
 * which is strictly increasing along any line.
 */
internal class LevelData private constructor(val level: Level) {
    val n: Int = level.containers.size
    val width = level.width
    val height = level.height
    val color = IntArray(n) { level.containers[it].color }
    val capacity = IntArray(n) { level.containers[it].size.capacity }
    val allMask: Long = if (n == 64) -1L else (1L shl n) - 1L
    val queue: IntArray = level.queue.toIntArray()
    val qSize = queue.size

    /** cell index (row*width+col) -> container id occupying it, or -1. */
    val occupant = IntArray(width * height) { -1 }

    /** Cell indices along each container's exit ray, nearest first. */
    val rayCells: Array<IntArray>

    /** Bit j set <=> container j occupies a cell on container i's exit ray. */
    val rayMask = LongArray(n)

    /** Symmetry group of each container: colour * 3 + size index (S/M/L by capacity). */
    val group = IntArray(n) { color[it] * 3 + when (capacity[it]) { 4 -> 0; 6 -> 1; else -> 2 } }

    /** groupMask[g] = containers in symmetry group g. */
    val groupMask = LongArray(COLORS * 3).also { m -> for (i in 0 until n) m[group[i]] = m[group[i]] or (1L shl i) }

    /** blocks[i] = bitmask of containers whose exit ray container i occupies (i.e. that i blocks). */
    val blocks = LongArray(n)

    /** runLen[i] = number of consecutive elements of colour queue[i] starting at i. Size qSize+1 (last = 0). */
    val runLen = IntArray(qSize + 1)

    /** nextOcc[i*8+c] = smallest j >= i with queue[j]==c, or qSize + 1000 if none. Size (qSize+1)*8. */
    val nextOcc = IntArray((qSize + 1) * COLORS)

    init {
        require(n <= Limits.MAX_CONTAINERS) { "at most ${Limits.MAX_CONTAINERS} containers" }
        require(qSize < (1 shl 12)) { "queue too long" }
        for (v in level.containers) {
            for (c in v.cells()) {
                if (level.inBounds(c.row, c.col)) occupant[c.row * width + c.col] = v.id
            }
        }
        rayCells = Array(n) { i ->
            val v = level.containers[i]
            val cells = ArrayList<Int>()
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            while (level.inBounds(r, c)) {
                cells += r * width + c
                r += v.dir.dRow
                c += v.dir.dCol
            }
            cells.toIntArray()
        }
        for (i in 0 until n) {
            var m = 0L
            for (cell in rayCells[i]) {
                val o = occupant[cell]
                if (o >= 0 && o != i) m = m or (1L shl o)
            }
            rayMask[i] = m
        }
        for (i in 0 until n) {
            var m = rayMask[i]
            while (m != 0L) {
                val j = java.lang.Long.numberOfTrailingZeros(m)
                m = m and (m - 1)
                blocks[j] = blocks[j] or (1L shl i)
            }
        }
        for (i in qSize - 1 downTo 0) {
            runLen[i] = if (i + 1 < qSize && queue[i + 1] == queue[i]) runLen[i + 1] + 1 else 1
        }
        for (c in 0 until COLORS) nextOcc[qSize * COLORS + c] = qSize + 1000
        for (i in qSize - 1 downTo 0) {
            for (c in 0 until COLORS) nextOcc[i * COLORS + c] = nextOcc[(i + 1) * COLORS + c]
            val qc = queue[i]
            if (qc in 0 until COLORS) nextOcc[i * COLORS + qc] = i
        }
    }

    /** colorMask[c] = bitmask of containers with colour c. */
    val colorMask = LongArray(COLORS).also { m ->
        for (i in 0 until n) if (color[i] in 0 until COLORS) m[color[i]] = m[color[i]] or (1L shl i)
    }

    /**
     * Necessary condition for progress: after the boarding loop no docked container matches the front element, so
     * until a container of the front colour docks, every tapped container stays docked (no boarding happens).
     *
     * Returns the smallest set of on-board containers that must exit before some front-colour container can exit,
     * restricted to sets that fit the free slots (the front-colour container needs a slot too). Returns
     * [NO_CLOSURE] if no front-colour container can be freed in time: the state is lost and no boarding can happen
     * before the game ends. Returns 0 if the queue is empty or a front-colour container is exitable.
     */
    fun frontClosure(onBoard: Long, aux: Long): Long {
        val qi = (aux ushr 52).toInt()
        if (qi >= qSize) return 0L
        var budget = 3 - dockUsed(aux) // containers that may dock before the front-colour container
        if (budget < 0) return NO_CLOSURE
        var best = NO_CLOSURE
        var m = onBoard and colorMask[queue[qi]]
        while (m != 0L) {
            val v = java.lang.Long.numberOfTrailingZeros(m)
            m = m and (m - 1)
            val cl = closureWithin(v, onBoard, budget)
            if (cl != NO_CLOSURE) {
                if (cl == 0L) return 0L
                best = cl
                budget = java.lang.Long.bitCount(cl) - 1
                if (budget < 0) break
            }
        }
        return best
    }

    /** Number of on-board containers that could exit one after another if nothing ever left the board otherwise. */
    fun extractableCount(onBoard: Long): Int {
        var ob = onBoard
        var count = 0
        while (true) {
            val e = exitableMask(ob)
            if (e == 0L) return count
            count += java.lang.Long.bitCount(e)
            ob = ob and e.inv()
        }
    }

    /**
     * The set of on-board containers that must exit before [v] (transitive blockers), or [NO_CLOSURE] if it is
     * larger than [budget] or cyclic.
     */
    private fun closureWithin(v: Int, onBoard: Long, budget: Int): Long {
        var cl = rayMask[v] and onBoard
        if (java.lang.Long.bitCount(cl) > budget) return NO_CLOSURE
        var frontier = cl
        val self = 1L shl v
        while (frontier != 0L) {
            var add = 0L
            var f = frontier
            while (f != 0L) {
                val u = java.lang.Long.numberOfTrailingZeros(f)
                f = f and (f - 1)
                add = add or (rayMask[u] and onBoard)
            }
            if (add and self != 0L) return NO_CLOSURE
            add = add and cl.inv()
            cl = cl or add
            if (java.lang.Long.bitCount(cl) > budget) return NO_CLOSURE
            frontier = add
        }
        return cl
    }

    // ---------------------------------------------------------------- packed-state operations

    /** Exitable mask after [v] leaves, given the exitable mask [exitable] of [onBoard] (which contains v). */
    fun exitableAfter(onBoard: Long, exitable: Long, v: Int): Long {
        val ob = onBoard and (1L shl v).inv()
        var out = exitable and (1L shl v).inv()
        var cand = blocks[v] and ob
        while (cand != 0L) {
            val u = java.lang.Long.numberOfTrailingZeros(cand)
            cand = cand and (cand - 1)
            if (rayMask[u] and ob == 0L) out = out or (1L shl u)
        }
        return out
    }

    /**
     * Canonical board mask for memo keys. "Free leaves" (exitable containers that block no on-board container) of the
     * same colour and size are interchangeable: the state's future depends only on the rest of the board N and the
     * number of free leaves per (colour, size) group. This rebuilds an equivalent board N ∪ L' where L' greedily
     * takes the lowest-id group members that are compatible with N and with each other (not blocked by, and not
     * blocking, anything chosen). If the greedy choice cannot place every leaf it returns [onBoard] unchanged
     * (still a sound key, it only misses a merge).
     */
    fun canonBoard(onBoard: Long, exitable: Long): Long {
        var leaves = 0L
        var m = exitable
        while (m != 0L) {
            val u = java.lang.Long.numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (blocks[u] and onBoard == 0L) leaves = leaves or (1L shl u)
        }
        if (leaves == 0L) return onBoard
        var out = onBoard and leaves.inv()
        var rest = leaves
        while (rest != 0L) {
            val g = group[java.lang.Long.numberOfTrailingZeros(rest)]
            val gm = groupMask[g]
            var need = java.lang.Long.bitCount(rest and gm)
            rest = rest and gm.inv()
            var cand = gm
            while (need > 0 && cand != 0L) {
                val u = java.lang.Long.numberOfTrailingZeros(cand)
                cand = cand and (cand - 1)
                val bit = 1L shl u
                if (out and bit == 0L && rayMask[u] and out == 0L && blocks[u] and out == 0L) {
                    out = out or bit
                    need--
                }
            }
            if (need > 0) return onBoard
        }
        return out
    }

    /** Bitmask of on-board containers whose exit ray is clear (ignores slot availability). */
    fun exitableMask(onBoard: Long): Long {
        var rest = onBoard
        var out = 0L
        while (rest != 0L) {
            val bit = rest and -rest
            val i = java.lang.Long.numberOfTrailingZeros(rest)
            if (rayMask[i] and onBoard == 0L) out = out or bit
            rest = rest xor bit
        }
        return out
    }

    /** Container [v] exits into the leftmost free slot, then the boarding loop runs. Requires a free slot. */
    fun exitAndBoard(onBoard: Long, aux: Long, v: Int): Long {
        var s = 0
        while (((aux ushr (13 * s)) and 0xFL) != 0L) s++
        val arrival = n - java.lang.Long.bitCount(onBoard)
        val field = capacity[v] or (color[v] shl 4) or (arrival shl 7)
        return board(aux or (field.toLong() shl (13 * s)))
    }

    /** Boarding loop on packed aux. */
    fun board(aux0: Long): Long {
        var a = aux0
        var qi = (a ushr 52).toInt()
        while (qi < qSize) {
            val c = queue[qi]
            var best = -1
            var bestArr = Int.MAX_VALUE
            for (s in 0 until 4) {
                val f = ((a ushr (13 * s)) and 0x1FFFL).toInt()
                if (f and 0xF != 0 && (f ushr 4) and 7 == c) {
                    val arr = f ushr 7
                    if (arr < bestArr) {
                        bestArr = arr; best = s
                    }
                }
            }
            if (best < 0) break
            val shift = 13 * best
            val f = ((a ushr shift) and 0x1FFFL).toInt()
            val left = f and 0xF
            val k = if (runLen[qi] < left) runLen[qi] else left
            qi += k
            val nl = left - k
            val nf = if (nl == 0) 0 else (f and 0xF.inv()) or nl
            a = (a and (0x1FFFL shl shift).inv()) or (nf.toLong() shl shift)
        }
        return (a and QI_CLEAR) or (qi.toLong() shl 52)
    }

    /** Canonical memo key component: slots sorted by (colour, arrival) packed as (capacity|colour<<4), plus qi. */
    fun canon(aux: Long): Long {
        var k0 = sortKey(aux, 0)
        var k1 = sortKey(aux, 1)
        var k2 = sortKey(aux, 2)
        var k3 = sortKey(aux, 3)
        var t: Int
        if (k0 > k1) { t = k0; k0 = k1; k1 = t }
        if (k2 > k3) { t = k2; k2 = k3; k3 = t }
        if (k0 > k2) { t = k0; k0 = k2; k2 = t }
        if (k1 > k3) { t = k1; k1 = k3; k3 = t }
        if (k1 > k2) { t = k1; k1 = k2; k2 = t }
        val qi = aux ushr 52
        return (low7(k0).toLong()) or (low7(k1).toLong() shl 7) or (low7(k2).toLong() shl 14) or
            (low7(k3).toLong() shl 21) or (qi shl 28)
    }

    private fun low7(k: Int) = if (k == Int.MAX_VALUE) 0 else k and 0x7F

    private fun sortKey(aux: Long, s: Int): Int {
        val f = ((aux ushr (13 * s)) and 0x1FFFL).toInt()
        if (f and 0xF == 0) return Int.MAX_VALUE
        val col = (f ushr 4) and 7
        val arr = f ushr 7
        return (((col shl 6) or arr) shl 7) or (f and 0x7F)
    }

    /** Packed status: 0 PLAYING, 1 WON, 2 DEADLOCK, 3 STUCK. */
    fun status(onBoard: Long, aux: Long): Int {
        val used = dockUsed(aux)
        val qi = (aux ushr 52).toInt()
        if (onBoard == 0L && used == 0 && qi >= qSize) return WON
        if (used == 4) return DEADLOCK
        if (exitableMask(onBoard) == 0L) return STUCK
        return PLAYING
    }

    fun toPacked(state: GameState): Long {
        var aux = 0L
        val docked = state.dock.withIndex().filter { it.value != null && it.value!!.spaceLeft > 0 }
        val byArrival = docked.sortedBy { it.value!!.arrival }
        byArrival.forEachIndexed { rank, (s, pv) ->
            val field = pv!!.spaceLeft or (pv.color shl 4) or (rank shl 7)
            aux = aux or (field.toLong() shl (13 * s))
        }
        return aux or (state.queueIndex.toLong() shl 52)
    }

    companion object {
        const val COLORS = 8
        /** Sentinel for [frontClosure]: never a valid closure (a closure never contains every container). */
        const val NO_CLOSURE = -1L
        const val PLAYING = 0
        const val WON = 1
        const val DEADLOCK = 2
        const val STUCK = 3
        private const val QI_CLEAR = (1L shl 52) - 1L

        fun dockUsed(aux: Long): Int {
            var c = 0
            if (aux and 0xFL != 0L) c++
            if ((aux ushr 13) and 0xFL != 0L) c++
            if ((aux ushr 26) and 0xFL != 0L) c++
            if ((aux ushr 39) and 0xFL != 0L) c++
            return c
        }

        fun queueIndex(aux: Long): Int = (aux ushr 52).toInt()

        private val cache = ThreadLocal<LevelData?>()

        /** Cached by Level identity (one entry per thread). */
        fun of(level: Level): LevelData {
            val c = cache.get()
            if (c != null && c.level === level) return c
            val d = LevelData(level)
            cache.set(d)
            return d
        }
    }
}
