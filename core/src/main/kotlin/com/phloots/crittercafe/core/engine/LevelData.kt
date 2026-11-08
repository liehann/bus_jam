package com.phloots.crittercafe.core.engine

import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Mechanic
import java.lang.Long.bitCount
import java.lang.Long.numberOfTrailingZeros

/**
 * v2 internals: per-level precomputed blocking data plus a packed, allocation-free state used by the solver hot path.
 * Owner: gamestate agent.
 *
 * Container sets are 128-bit masks held as two Longs (lo = ids 0..63, hi = ids 64..127), like [com.phloots.crittercafe.core.model.Bits128].
 *
 * Packed state = four Longs (onBoard lo, onBoard hi, a, b):
 *  - a bits [8*s, 8*s+8) for dock slot s (0..3): spaceLeft (4 bits, 0 == free slot) | colour (4 bits) << 4.
 *    Positional (slot 0 = leftmost). The container id is not kept: once docked, only its colour and space matter.
 *  - a bits [32, 44): queueIndex (next element not yet staged), < 4096.
 *  - b bits [6*c, 6*c+6) for colour c (0 until [Limits.MAX_COLORS] <= 10, so <= 60 bits): number of staged elements of colour c (0..63; staging holds
 *    `level.stagingSpots` <= [Limits.MAX_STAGING_SPOTS] <= 63). Element order inside staging never matters (boarding picks by colour;
 *    same-colour elements are identical).
 *
 * For a balanced level b is a function of (onBoard, a), so (lo, hi, a, b) is a lossless and compact memo key.
 * Two-Long results (a, b) are written to an `out` LongArray (out[0] = a, out[1] = b).
 *
 * v4 mechanics (DESIGN.md "Mechanics (v4)"): every tap-driven mechanic (frozen counters, ties, turntable facings,
 * gate phases) is a function of the board, via releases = n - |onBoard|, so the key stays (lo, hi, a, b). A wide
 * container docked at slot s also marks slot s + 1 with spaceLeft [TAIL] (15; capacities are <= 10) and its colour.
 * [mech] is false for levels without a rule mechanic: every v2 code path is then unchanged.
 */
internal class LevelData private constructor(val level: Level) {
    val n: Int = level.containers.size
    val width = level.width
    val height = level.height
    val color = IntArray(n) { level.containers[it].color }
    val capacity = IntArray(n) { level.containers[it].size.capacity }
    val allLo: Long = maskBits(n)
    val allHi: Long = maskBits(n - 64)
    val queue: IntArray = level.queue.toIntArray()
    val qSize = queue.size
    /** Staging spots of this level ([Level.stagingSpots]). */
    val staging: Int = level.stagingSpots

    /** cell index (row*width+col) -> container id occupying it, or -1. */
    val occupant = IntArray(width * height) { -1 }

    /** Cell indices along each container's exit ray, nearest first. */
    val rayCells: Array<IntArray>

    /** Containers occupying a cell of container i's exit ray (i's potential blockers). */
    val rayLo = LongArray(n)
    val rayHi = LongArray(n)

    /** Containers whose exit ray container i occupies (the containers i blocks). */
    val blkLo = LongArray(n)
    val blkHi = LongArray(n)

    /** Symmetry group: colour * 3 + size index. Same group = same colour and capacity. */
    val group = IntArray(n) { color[it] * 3 + level.containers[it].size.ordinal }
    val groupLo = LongArray(COLORS * 3)
    val groupHi = LongArray(COLORS * 3)
    val colorLo = LongArray(COLORS)
    val colorHi = LongArray(COLORS)

    /** nextOcc[i*8+c] = smallest j >= i with queue[j]==c, or [FAR] if none. Size (qSize+1)*8. */
    val nextOcc = IntArray((qSize + 1) * COLORS)

    // ---------------------------------------------------------------- v4 mechanics (static data)

    /** True iff the level uses a rule mechanic (frozen, tied, wide, colour bench, turntable, gate). */
    val mech: Boolean = level.mechanics().any { it != Mechanic.HIDDEN }
    val wide = BooleanArray(n) { level.containers[it].wide }
    val frozen = IntArray(n) { level.containers[it].frozen }
    /** Tie partner that must leave first, or -1. */
    val tiedTo = IntArray(n) { level.containers[it].tiedTo }
    /** Container stands on a turntable (turns clockwise once per release). */
    val turn = BooleanArray(n) { level.turntables.isNotEmpty() && level.onTurntable(it) }
    /** Clockwise index (UP 0, RIGHT 1, DOWN 2, LEFT 3) of each container's initial facing. */
    val dir0 = IntArray(n) { CW.indexOf(level.containers[it].dir) }
    /** Ray occupants per (container, clockwise direction) = index 4*i+k. Only k == dir0 is set unless [turn]. */
    val dRayLo = LongArray(4 * n)
    val dRayHi = LongArray(4 * n)
    /** Gate crossed when container i exits in clockwise direction k (index 4*i+k), or -1. */
    val exitGate = IntArray(4 * n) { -1 }
    val gateOpen0 = BooleanArray(level.gates.size) { level.gates[it].open }
    /** Colour mask each dock slot accepts ([Level.benchColors]). */
    val benchMask = IntArray(Limits.DOCK_SLOTS) { s ->
        if (level.benchColors.size != Limits.DOCK_SLOTS || level.benchColors[s] < 0) ALL_COLORS else 1 shl level.benchColors[s]
    }
    /** May take part in free-leaf symmetry: not wide, not on a turntable, not frozen, not tied or a tie partner, no gate on its exit. */
    val symOk = BooleanArray(n)
    /** Release prerequisites for the closure bound: static exit-ray occupants (none for turntable containers) plus the tie partner. Equal to (rayLo, rayHi) for v2 levels. */
    val preLo = LongArray(n)
    val preHi = LongArray(n)
    val wideLo: Long
    val wideHi: Long

    init {
        require(n <= Limits.MAX_CONTAINERS) { "at most ${Limits.MAX_CONTAINERS} containers" }
        require(qSize < (1 shl 12)) { "queue too long" }
        require(staging in 1..63) { "staging counts are 6 bits (stagingSpots=$staging)" }
        require(COLORS <= 10) { "staging counts: 6 bits x colours must fit a Long; dock colour is 4 bits" }
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
            for (cell in rayCells[i]) {
                val o = occupant[cell]
                if (o >= 0 && o != i) {
                    if (o < 64) rayLo[i] = rayLo[i] or (1L shl o) else rayHi[i] = rayHi[i] or (1L shl (o - 64))
                    if (i < 64) blkLo[o] = blkLo[o] or (1L shl i) else blkHi[o] = blkHi[o] or (1L shl (i - 64))
                }
            }
            val g = group[i]
            val c = color[i]
            if (i < 64) {
                groupLo[g] = groupLo[g] or (1L shl i)
                colorLo[c] = colorLo[c] or (1L shl i)
            } else {
                groupHi[g] = groupHi[g] or (1L shl (i - 64))
                colorHi[c] = colorHi[c] or (1L shl (i - 64))
            }
        }
        for (c in 0 until COLORS) nextOcc[qSize * COLORS + c] = FAR
        for (i in qSize - 1 downTo 0) {
            for (c in 0 until COLORS) nextOcc[i * COLORS + c] = nextOcc[(i + 1) * COLORS + c]
            val qc = queue[i]
            if (qc in 0 until COLORS) nextOcc[i * COLORS + qc] = i
        }
    }

    init {
        var wLo = 0L
        var wHi = 0L
        val partner = BooleanArray(n)
        for (i in 0 until n) {
            val t = tiedTo[i]
            if (t in 0 until n) partner[t] = true
            if (wide[i]) { if (i < 64) wLo = wLo or (1L shl i) else wHi = wHi or (1L shl (i - 64)) }
        }
        wideLo = wLo
        wideHi = wHi
        val gateAt = HashMap<Long, Int>()
        level.gates.forEachIndexed { g, gate -> gateAt.putIfAbsent(gate.side.ordinal.toLong() shl 32 or gate.index.toLong(), g) }
        for (i in 0 until n) {
            val v = level.containers[i]
            preLo[i] = rayLo[i]
            preHi[i] = rayHi[i]
            for (k in 0 until 4) {
                if (k != dir0[i] && !turn[i]) continue
                val dir = CW[k]
                var lo = 0L
                var hi = 0L
                var r = v.row + dir.dRow
                var c = v.col + dir.dCol
                while (level.inBounds(r, c)) {
                    val o = occupant[r * width + c]
                    if (o >= 0 && o != i) {
                        if (o < 64) lo = lo or (1L shl o) else hi = hi or (1L shl (o - 64))
                        // a turntable container may face any way: it counts as blocked by every ray's occupants
                        if (turn[i]) { if (i < 64) blkLo[o] = blkLo[o] or (1L shl i) else blkHi[o] = blkHi[o] or (1L shl (i - 64)) }
                    }
                    r += dir.dRow
                    c += dir.dCol
                }
                dRayLo[4 * i + k] = lo
                dRayHi[4 * i + k] = hi
                val index = if (dir == Dir.UP || dir == Dir.DOWN) v.col else v.row
                exitGate[4 * i + k] = gateAt[dir.ordinal.toLong() shl 32 or index.toLong()] ?: -1
            }
            if (turn[i]) { preLo[i] = 0L; preHi[i] = 0L }
            val t = tiedTo[i]
            if (t in 0 until n) { if (t < 64) preLo[i] = preLo[i] or (1L shl t) else preHi[i] = preHi[i] or (1L shl (t - 64)) }
            symOk[i] = !wide[i] && !turn[i] && frozen[i] == 0 && t < 0 && !partner[i] && exitGate[4 * i + dir0[i]] < 0
        }
    }

    // ---------------------------------------------------------------- v4 mechanics (rules)

    /** Releases so far on the board (lo, hi): the phase of every tap-driven mechanic. */
    fun released(lo: Long, hi: Long): Int = n - bitCount(lo and allLo) - bitCount(hi and allHi)

    /** Clockwise index of container [v]'s facing after [rel] releases. */
    fun dirIndex(v: Int, rel: Int): Int = if (turn[v]) (dir0[v] + rel) and 3 else dir0[v]

    /** Exit path clear after [rel] releases: no on-board container on the current ray, no closed gate on its exit. */
    fun pathClear(v: Int, lo: Long, hi: Long, rel: Int): Boolean {
        val idx = 4 * v + dirIndex(v, rel)
        if ((dRayLo[idx] and lo) or (dRayHi[idx] and hi) != 0L) return false
        val g = exitGate[idx]
        return g < 0 || gateOpen0[g] != (rel and 1 == 1)
    }

    /** Exit path clear (v2: the exit ray is empty). */
    fun pathClear(v: Int, lo: Long, hi: Long): Boolean = if (!mech) rayClear(v, lo, hi) else pathClear(v, lo, hi, released(lo, hi))

    /** On-board [v] may leave the board after [rel] releases (thawed, not tie-locked, path clear); slots aside. */
    fun canLeave(v: Int, lo: Long, hi: Long, rel: Int): Boolean {
        if (frozen[v] > rel) return false
        val t = tiedTo[v]
        if (t >= 0 && (if (t < 64) (lo ushr t) and 1L else (hi ushr (t - 64)) and 1L) != 0L) return false
        return pathClear(v, lo, hi, rel)
    }

    /** [canLeave] (v2: [rayClear]). */
    fun canLeave(v: Int, lo: Long, hi: Long): Boolean = if (!mech) rayClear(v, lo, hi) else canLeave(v, lo, hi, released(lo, hi))

    /**
     * Leftmost dock slot a container of colour [c] (two adjacent slots if [w]) may take, given the occupied-slot mask
     * [occ] (bit s = slot s occupied); -1 if none. Benches restricted to another colour are skipped.
     */
    fun fitSlotOcc(occ: Int, c: Int, w: Boolean): Int {
        val cb = 1 shl c
        if (!w) {
            for (s in 0 until 4) if ((occ ushr s) and 1 == 0 && benchMask[s] and cb != 0) return s
            return -1
        }
        for (s in 0 until 3) {
            if ((occ ushr s) and 3 == 0 && benchMask[s] and cb != 0 && benchMask[s + 1] and cb != 0) return s
        }
        return -1
    }

    /** [fitSlotOcc] for container [v] on the packed dock [a]. */
    fun fitSlot(a: Long, v: Int): Int = fitSlotOcc(occupied(a), color[v], wide[v])

    /** True iff container [v] has a dock slot on the packed dock [a] (always, for v2 levels with a free slot). */
    fun fits(a: Long, v: Int): Boolean = !mech || fitSlot(a, v) >= 0

    /** Exitable [v] that blocks nothing on the board and may be swapped with others of its group (free-leaf symmetry). */
    fun isFreeLeaf(v: Int, lo: Long, hi: Long): Boolean = symOk[v] && blocksNone(v, lo, hi)

    /**
     * A container of colour [c], capacity [cap] (wide if [w]) docks in its leftmost fitting slot, then the boarding
     * loop runs. Returns false (out untouched) if no slot fits.
     */
    fun dockAndBoardMech(a: Long, b: Long, c: Int, cap: Int, w: Boolean, out: LongArray): Boolean {
        val s = fitSlotOcc(occupied(a), c, w)
        if (s < 0) return false
        var na = a or ((cap or (c shl 4)).toLong() shl (8 * s))
        if (w) na = na or ((TAIL or (c shl 4)).toLong() shl (8 * (s + 1)))
        board(na, b, out)
        return true
    }

    /** Status of a mechanic level (see Engine.computeStatus). */
    private fun statusMech(lo: Long, hi: Long, a: Long): Int {
        val rel = released(lo, hi)
        var dockBlocked = false
        var m = lo
        var base = 0
        for (pass in 0 until 2) {
            while (m != 0L) {
                val i = numberOfTrailingZeros(m) + base
                m = m and (m - 1)
                if (canLeave(i, lo, hi, rel)) {
                    if (fitSlot(a, i) >= 0) return PLAYING
                    dockBlocked = true
                }
            }
            m = hi
            base = 64
        }
        return if (dockBlocked) DEADLOCK else STUCK
    }

    /**
     * [cheapestRelease] for mechanic levels: a lower bound with the same meaning. Until the first container of a
     * staged colour docks, every release stays docked. For that container v, everything in its prerequisite closure
     * (static ray occupants, tie partners) leaves first, a wide one taking 2 slots; frozen counters need at least
     * frozen(v) - released releases before v, and frozen(u) - released + 1 for each u of the closure. Turntable rays,
     * gates and benches are ignored (relaxations), so a -1 is a proof of loss.
     */
    private fun cheapestReleaseMech(lo: Long, hi: Long, a: Long, b: Long, out: LongArray, tmp: LongArray): Int {
        val staged = stagedColors(b)
        if (staged == 0) return -1
        val free = 4 - dockUsed(a)
        val rel = released(lo, hi)
        var best = -1
        var cs = staged
        while (cs != 0) {
            val c = Integer.numberOfTrailingZeros(cs)
            cs = cs and (cs - 1)
            var m = colorLo[c] and lo
            var base = 0
            for (pass in 0 until 2) {
                while (m != 0L) {
                    val v = numberOfTrailingZeros(m) + base
                    m = m and (m - 1)
                    val budget = free - (if (wide[v]) 2 else 1)
                    if (budget < 0 || !closureWithin(v, lo, hi, budget, tmp)) continue
                    var need = bitCount(tmp[0]) + bitCount(tmp[1]) + bitCount(tmp[0] and wideLo) + bitCount(tmp[1] and wideHi)
                    var fz = frozen[v] - rel
                    var u0 = tmp[0]
                    while (u0 != 0L) { val u = numberOfTrailingZeros(u0); u0 = u0 and (u0 - 1); fz = maxOf(fz, frozen[u] - rel + 1) }
                    var u1 = tmp[1]
                    while (u1 != 0L) { val u = numberOfTrailingZeros(u1) + 64; u1 = u1 and (u1 - 1); fz = maxOf(fz, frozen[u] - rel + 1) }
                    if (fz > need) need = fz
                    if (need > budget) continue
                    if (best < 0 || need < best) {
                        best = need
                        out[0] = tmp[0]; out[1] = tmp[1]
                        if (need == 0) return 0
                    }
                }
                m = colorHi[c] and hi
                base = 64
            }
        }
        return best
    }

    // ---------------------------------------------------------------- board (mask) operations

    /** True if container [v]'s exit ray holds no container of the board (lo, hi). */
    fun rayClear(v: Int, lo: Long, hi: Long): Boolean = (rayLo[v] and lo) or (rayHi[v] and hi) == 0L

    /** True if container [v] lies on no exit ray of a container of the board (lo, hi). */
    fun blocksNone(v: Int, lo: Long, hi: Long): Boolean = (blkLo[v] and lo) or (blkHi[v] and hi) == 0L

    /**
     * Exitable containers (on board, ray clear) of the board (lo, hi), written to out[0], out[1]. Mechanic levels:
     * [canLeave] (thawed, not tie-locked, current ray clear, gate open); dock slots are checked separately ([fits]).
     */
    fun exitable(lo: Long, hi: Long, out: LongArray) {
        if (mech) {
            exitableMech(lo, hi, out)
            return
        }
        var oLo = 0L
        var oHi = 0L
        var m = lo
        while (m != 0L) {
            val i = numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (rayClear(i, lo, hi)) oLo = oLo or (1L shl i)
        }
        m = hi
        while (m != 0L) {
            val b = numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (rayClear(b + 64, lo, hi)) oHi = oHi or (1L shl b)
        }
        out[0] = oLo
        out[1] = oHi
    }

    /**
     * Exitable set after [v] leaves the board (lo, hi), given its exitable set (exLo, exHi) (which contains v).
     * Written to out[0], out[1]. Only containers that v blocked can become exitable.
     */
    fun exitableAfter(lo: Long, hi: Long, exLo: Long, exHi: Long, v: Int, out: LongArray) {
        val nLo: Long
        val nHi: Long
        var oLo = exLo
        var oHi = exHi
        if (v < 64) {
            nLo = lo and (1L shl v).inv(); nHi = hi; oLo = oLo and (1L shl v).inv()
        } else {
            nLo = lo; nHi = hi and (1L shl (v - 64)).inv(); oHi = oHi and (1L shl (v - 64)).inv()
        }
        if (mech) {
            // every release turns turntables, flips gates, counts frozen containers down and may untie: recompute
            exitableMech(nLo, nHi, out)
            return
        }
        var m = blkLo[v] and nLo
        while (m != 0L) {
            val u = numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (rayClear(u, nLo, nHi)) oLo = oLo or (1L shl u)
        }
        m = blkHi[v] and nHi
        while (m != 0L) {
            val b = numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (rayClear(b + 64, nLo, nHi)) oHi = oHi or (1L shl b)
        }
        out[0] = oLo
        out[1] = oHi
    }

    private fun exitableMech(lo: Long, hi: Long, out: LongArray) {
        val rel = released(lo, hi)
        var oLo = 0L
        var oHi = 0L
        var m = lo
        while (m != 0L) {
            val i = numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (canLeave(i, lo, hi, rel)) oLo = oLo or (1L shl i)
        }
        m = hi
        while (m != 0L) {
            val b = numberOfTrailingZeros(m)
            m = m and (m - 1)
            if (canLeave(b + 64, lo, hi, rel)) oHi = oHi or (1L shl b)
        }
        out[0] = oLo
        out[1] = oHi
    }

    /** Number of containers that could leave the board one after another if nothing else changed (iterated peel). */
    fun extractableCount(lo0: Long, hi0: Long, tmp: LongArray): Int {
        var lo = lo0
        var hi = hi0
        var count = 0
        while (true) {
            exitable(lo, hi, tmp)
            val eLo = tmp[0]
            val eHi = tmp[1]
            if (eLo == 0L && eHi == 0L) return count
            count += bitCount(eLo) + bitCount(eHi)
            lo = lo and eLo.inv()
            hi = hi and eHi.inv()
        }
    }

    /**
     * Transitive on-board blockers of [v] (the containers that must leave before v can), written to out[0], out[1].
     * Returns false (out undefined) if there are more than [budget] of them or v is (transitively) blocked by itself.
     */
    fun closureWithin(v: Int, lo: Long, hi: Long, budget: Int, out: LongArray): Boolean {
        var cLo = preLo[v] and lo
        var cHi = preHi[v] and hi
        if (bitCount(cLo) + bitCount(cHi) > budget) return false
        var fLo = cLo
        var fHi = cHi
        while (fLo != 0L || fHi != 0L) {
            var aLo = 0L
            var aHi = 0L
            while (fLo != 0L) {
                val u = numberOfTrailingZeros(fLo)
                fLo = fLo and (fLo - 1)
                aLo = aLo or (preLo[u] and lo); aHi = aHi or (preHi[u] and hi)
            }
            while (fHi != 0L) {
                val u = numberOfTrailingZeros(fHi) + 64
                fHi = fHi and (fHi - 1)
                aLo = aLo or (preLo[u] and lo); aHi = aHi or (preHi[u] and hi)
            }
            val self = if (v < 64) (aLo ushr v) and 1L else (aHi ushr (v - 64)) and 1L
            if (self != 0L) return false
            aLo = aLo and cLo.inv(); aHi = aHi and cHi.inv()
            cLo = cLo or aLo; cHi = cHi or aHi
            if (bitCount(cLo) + bitCount(cHi) > budget) return false
            fLo = aLo; fHi = aHi
        }
        out[0] = cLo
        out[1] = cHi
        return true
    }

    /**
     * Exact lost-state test, for a PLAYING state after the boarding loop (no docked container matches a staged
     * colour). The next boarding needs a container of a staged colour to dock; until then every released container
     * stays docked. So some on-board container of a staged colour, plus all its transitive blockers, must fit in the
     * free slots. Returns the size of the cheapest such set (its members in out[0], out[1]), or -1 if there is none:
     * the state is lost and no element ever boards again.
     */
    fun cheapestRelease(lo: Long, hi: Long, a: Long, b: Long, out: LongArray, tmp: LongArray): Int {
        if (mech) return cheapestReleaseMech(lo, hi, a, b, out, tmp)
        val staged = stagedColors(b)
        if (staged == 0) return -1
        var budget = 3 - dockUsed(a)
        if (budget < 0) return -1
        var best = -1
        var cs = staged
        while (cs != 0) {
            val c = Integer.numberOfTrailingZeros(cs)
            cs = cs and (cs - 1)
            var m = colorLo[c] and lo
            var base = 0
            var pass = 0
            while (pass < 2) {
                while (m != 0L) {
                    val v = numberOfTrailingZeros(m) + base
                    m = m and (m - 1)
                    if (closureWithin(v, lo, hi, budget, tmp)) {
                        val size = bitCount(tmp[0]) + bitCount(tmp[1])
                        out[0] = tmp[0]; out[1] = tmp[1]
                        best = size
                        if (size == 0) return 0
                        budget = size - 1
                    }
                }
                pass++
                m = colorHi[c] and hi
                base = 64
            }
        }
        return best
    }

    /**
     * Canonical board for memo keys, written to out[0], out[1]. "Free leaves" (exitable containers that block no
     * on-board container) of the same group are interchangeable: they never interact with the rest of the board, so
     * the future depends only on the rest N and the number of free leaves per group. This rebuilds an equivalent
     * board N ∪ L' where L' greedily takes the lowest-id group members compatible with N and with each other (not
     * blocked by and not blocking anything chosen). If the greedy choice fails it returns the board unchanged
     * (still a sound key; it only misses a merge).
     */
    fun canonBoard(lo: Long, hi: Long, exLo: Long, exHi: Long, out: LongArray) {
        var lLo = 0L
        var lHi = 0L
        var m = exLo
        while (m != 0L) {
            val u = numberOfTrailingZeros(m); m = m and (m - 1)
            if (isFreeLeaf(u, lo, hi)) lLo = lLo or (1L shl u)
        }
        m = exHi
        while (m != 0L) {
            val b = numberOfTrailingZeros(m); m = m and (m - 1)
            if (isFreeLeaf(b + 64, lo, hi)) lHi = lHi or (1L shl b)
        }
        out[0] = lo; out[1] = hi
        if (lLo == 0L && lHi == 0L) return
        var oLo = lo and lLo.inv()
        var oHi = hi and lHi.inv()
        var rLo = lLo
        var rHi = lHi
        while (rLo != 0L || rHi != 0L) {
            val first = if (rLo != 0L) numberOfTrailingZeros(rLo) else numberOfTrailingZeros(rHi) + 64
            val g = group[first]
            val gLo = groupLo[g]
            val gHi = groupHi[g]
            var need = bitCount(rLo and gLo) + bitCount(rHi and gHi)
            rLo = rLo and gLo.inv(); rHi = rHi and gHi.inv()
            var cLo = gLo
            var cHi = gHi
            while (need > 0 && (cLo != 0L || cHi != 0L)) {
                val u: Int
                if (cLo != 0L) {
                    u = numberOfTrailingZeros(cLo); cLo = cLo and (cLo - 1)
                    if (oLo and (1L shl u) != 0L) continue
                } else {
                    val b = numberOfTrailingZeros(cHi); cHi = cHi and (cHi - 1)
                    u = b + 64
                    if (oHi and (1L shl b) != 0L) continue
                }
                if (symOk[u] && rayClear(u, oLo, oHi) && blocksNone(u, oLo, oHi)) {
                    if (u < 64) oLo = oLo or (1L shl u) else oHi = oHi or (1L shl (u - 64))
                    need--
                }
            }
            if (need > 0) return
        }
        out[0] = oLo; out[1] = oHi
    }

    // ---------------------------------------------------------------- packed-state operations

    /**
     * Container [v] (a free dock slot is required; mechanic levels: a fitting one, see [fits]) docks in the leftmost
     * free (fitting) slot, then the boarding loop runs.
     */
    fun exitAndBoard(a: Long, b: Long, v: Int, out: LongArray) {
        if (mech) {
            check(dockAndBoardMech(a, b, color[v], capacity[v], wide[v], out)) { "no fitting slot for $v" }
            return
        }
        dockAndBoard(a, b, color[v], capacity[v], out)
    }

    /** A container of colour [c] and capacity [cap] docks in the leftmost free slot, then the boarding loop runs. */
    fun dockAndBoard(a: Long, b: Long, c: Int, cap: Int, out: LongArray) {
        var s = 0
        while (((a ushr (8 * s)) and 0xFL) != 0L) s++
        val field = (cap or (c shl 4)).toLong()
        board(a or (field shl (8 * s)), b, out)
    }

    // ---------------------------------------------------------------- relaxed (blocking-free) game

    /** Groups present in the level (key (colour * 3 + size) * 2 + wide), and for each: bit offset / width of its
     *  count in the relaxed key (two Longs: bits 0..63 in r0, 64..127 in r1), colour, capacity, wideness and members.
     *  Without wide containers these are exactly the symmetry groups ([group]). */
    val relGroups: IntArray
    val relOffset: IntArray
    val relWidth: IntArray
    val relColor: IntArray
    val relCap: IntArray
    val relWide: BooleanArray
    val relLo: LongArray
    val relHi: LongArray

    init {
        val key = IntArray(n) { group[it] * 2 + (if (wide[it]) 1 else 0) }
        val present = (0 until COLORS * 6).filter { k -> key.any { it == k } }
        relGroups = present.toIntArray()
        relOffset = IntArray(present.size)
        relWidth = IntArray(present.size)
        relLo = LongArray(present.size)
        relHi = LongArray(present.size)
        for (i in 0 until n) {
            val k = present.indexOf(key[i])
            if (i < 64) relLo[k] = relLo[k] or (1L shl i) else relHi[k] = relHi[k] or (1L shl (i - 64))
        }
        var off = 0
        for (k in present.indices) {
            val cnt = bitCount(relLo[k]) + bitCount(relHi[k])
            val w = 32 - Integer.numberOfLeadingZeros(cnt)
            // never straddle the two Longs
            if (off < 64 && off + w > 64) off = 64
            relOffset[k] = off
            relWidth[k] = w
            off += w
        }
        check(off <= 128) { "relaxed key overflow" }
        relColor = IntArray(present.size) { present[it] / 6 }
        relCap = IntArray(present.size) { level.containers.first { c -> key[c.id] == present[it] }.size.capacity }
        relWide = BooleanArray(present.size) { present[it] % 2 == 1 }
    }

    /** Relaxed key of the board (lo, hi): per-group on-board counts, packed; written to out[0], out[1]. */
    fun relaxedCounts(lo: Long, hi: Long, out: LongArray) {
        var r0 = 0L
        var r1 = 0L
        for (k in relGroups.indices) {
            val cnt = (bitCount(relLo[k] and lo) + bitCount(relHi[k] and hi)).toLong()
            val off = relOffset[k]
            if (off < 64) r0 = r0 or (cnt shl off) else r1 = r1 or (cnt shl (off - 64))
        }
        out[0] = r0
        out[1] = r1
    }

    /**
     * Boarding loop to its fixpoint. Batched: the leftmost docked container with a staged match takes as many of its
     * colour as fit, then staging refills, then the scan restarts at slot 0. Per colour, elements always go to the
     * leftmost container of that colour with space, and boardings of different colours commute, so the fixpoint is
     * the same as the Engine's one-element-at-a-time loop (checked by the differential tests).
     */
    fun board(a0: Long, b0: Long, out: LongArray) {
        var a = a0
        var b = b0
        var total = stagedTotal(b)
        var s = 0
        while (s < 4) {
            val shift = 8 * s
            val f = ((a ushr shift) and 0xFFL).toInt()
            val space = f and 0xF
            if (space == 0 || space == TAIL) { s++; continue }
            val c = f ushr 4
            val cShift = 6 * c
            val cnt = ((b ushr cShift) and 63L).toInt()
            if (cnt == 0) { s++; continue }
            val k = if (cnt < space) cnt else space
            val nl = space - k
            val nf = if (nl == 0) 0 else (f and 0xF.inv()) or nl
            a = (a and (0xFFL shl shift).inv()) or (nf.toLong() shl shift)
            // a dispatched wide container also frees its right half (a TAIL right of a head is always its own)
            if (nl == 0 && s < 3 && ((a ushr (shift + 8)) and 0xFL).toInt() == TAIL) a = a and (0xFFL shl (shift + 8)).inv()
            b -= k.toLong() shl cShift
            total -= k
            var qi = ((a ushr 32) and 0xFFFL).toInt()
            while (total < staging && qi < qSize) {
                b += 1L shl (6 * queue[qi])
                qi++
                total++
            }
            a = (a and QI_CLEAR) or (qi.toLong() shl 32)
            s = 0
        }
        out[0] = a
        out[1] = b
    }

    /** Packed status: [PLAYING], [WON], [DEADLOCK], [STUCK]. Assumes the boarding loop has run. */
    fun status(lo: Long, hi: Long, a: Long, b: Long): Int {
        if (lo == 0L && hi == 0L && a and DOCK_MASK == 0L && b == 0L && queueIndex(a) >= qSize) return WON
        if (dockUsed(a) == 4) return if (hasMatch(a, b)) STUCK else DEADLOCK
        if (mech) return statusMech(lo, hi, a)
        var m = lo
        while (m != 0L) {
            val i = numberOfTrailingZeros(m); m = m and (m - 1)
            if (rayClear(i, lo, hi)) return PLAYING
        }
        m = hi
        while (m != 0L) {
            val x = numberOfTrailingZeros(m); m = m and (m - 1)
            if (rayClear(x + 64, lo, hi)) return PLAYING
        }
        return STUCK
    }

    /** True if a docked container's colour is staged. */
    fun hasMatch(a: Long, b: Long): Boolean {
        val staged = stagedColors(b)
        for (s in 0 until 4) {
            val f = ((a ushr (8 * s)) and 0xFFL).toInt()
            if (f and 0xF != 0 && (staged ushr (f ushr 4)) and 1 != 0) return true
        }
        return false
    }

    /** Packs [state] into out[0] = a, out[1] = b (dock by colour + spaceLeft, staging counts, queueIndex). */
    fun toPacked(state: GameState, out: LongArray) {
        var a = 0L
        for (s in 0 until Limits.DOCK_SLOTS) {
            val dc = state.dock[s] ?: continue
            val space = if (state.isWideTail(s)) TAIL else dc.spaceLeft
            a = a or ((space or (dc.color shl 4)).toLong() shl (8 * s))
        }
        var b = 0L
        for (c in state.staging) b += 1L shl (6 * c)
        out[0] = a or (state.queueIndex.toLong() shl 32)
        out[1] = b
    }

    companion object {
        const val COLORS = Limits.MAX_COLORS
        const val FAR = 1 shl 20
        const val PLAYING = 0
        const val WON = 1
        const val DEADLOCK = 2
        const val STUCK = 3
        private const val QI_CLEAR = (0xFFFL shl 32).inv()
        const val DOCK_MASK = (1L shl 32) - 1L
        /** spaceLeft value marking the right half of a docked wide container (capacities are <= 10). */
        const val TAIL = 15
        const val ALL_COLORS = (1 shl COLORS) - 1
        /** Directions in clockwise order (turntable turns). */
        val CW = arrayOf(Dir.UP, Dir.RIGHT, Dir.DOWN, Dir.LEFT)

        /** Occupied-slot mask of the packed dock [a] (bit s = slot s occupied, wide halves included). */
        fun occupied(a: Long): Int {
            var m = 0
            for (s in 0 until 4) if ((a ushr (8 * s)) and 0xFL != 0L) m = m or (1 shl s)
            return m
        }

        private fun maskBits(k: Int): Long = if (k >= 64) -1L else if (k <= 0) 0L else (1L shl k) - 1L

        fun dockUsed(a: Long): Int {
            var c = 0
            if (a and 0xFL != 0L) c++
            if ((a ushr 8) and 0xFL != 0L) c++
            if ((a ushr 16) and 0xFL != 0L) c++
            if ((a ushr 24) and 0xFL != 0L) c++
            return c
        }

        fun queueIndex(a: Long): Int = ((a ushr 32) and 0xFFFL).toInt()

        fun stagedCount(b: Long, c: Int): Int = ((b ushr (6 * c)) and 63L).toInt()

        /** Bit c set <=> at least one staged element of colour c. */
        fun stagedColors(b: Long): Int {
            var m = 0
            for (c in 0 until COLORS) if ((b ushr (6 * c)) and 63L != 0L) m = m or (1 shl c)
            return m
        }

        /** Total staged elements. */
        fun stagedTotal(b: Long): Int {
            var t = 0
            for (c in 0 until COLORS) t += ((b ushr (6 * c)) and 63L).toInt()
            return t
        }

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
