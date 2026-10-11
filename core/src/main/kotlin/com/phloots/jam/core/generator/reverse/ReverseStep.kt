package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Limits

/**
 * Reverse ("director") generation, DESIGN.md v2 principle 7. Owner: reverse level designer.
 *
 * The game is played backwards from the empty end state. A reverse state mirrors a STABLE forward state (one between
 * taps, after the boarding loop reached its fixpoint):
 *  - [dock]: 4 positional slots, each a docked container (id, colour, spaceLeft >= 1) or null;
 *  - [w]: the remaining elements in order, staging first: `w[0 until min(40, w.size)]` is the staging area (oldest
 *    first) and the rest is the queue (front first). Using one list makes un-refill implicit: putting k elements
 *    back into the front part of `w` pushes the last k staged elements back to the queue front.
 *
 * One reverse step undoes one tap and its whole cascade. Given S' (after the tap) the step chooses
 *  - the tapped container X: a docked container of S' whose current load was boarded entirely during this tap
 *    ([XSpec.Existing]) or a new container that filled and dispatched during the tap ([XSpec.New]);
 *  - the cascade recipients: docked containers that received `j` elements from refills during the tap, either still
 *    docked in S' or un-dispatched (new, placed back into a free slot of S').
 * It then builds S: X's `jX` elements go back into staging (they were the oldest of colour c, so they are inserted
 * before the first c of S'), the recipients' elements go to the queue front (they were revealed by refills, one per
 * boarding), and the stayed refills fall back into the queue implicitly. Finally S is checked exactly by running the
 * forward tap ([ForwardSim.tap], a mirror of Engine.apply) and comparing with S', and by checking S is stable. Steps
 * that do not reproduce S' exactly are rejected, so the intended line is valid by construction; the generator
 * additionally replays every finished level through the real Engine.
 */
data class Docked(val id: Int, val color: Int, val space: Int)

/** Mutable-by-copy reverse state. */
data class RevState(val dock: List<Docked?>, val w: List<Int>) {
    val staging: List<Int> get() = w.subList(0, minOf(Limits.STAGING_SPOTS, w.size))
    val dockUsed: Int get() = dock.count { it != null }

    companion object {
        val END = RevState(List(Limits.DOCK_SLOTS) { null }, emptyList())
    }
}

/** Exact mirror of Engine.apply's release + boarding loop on a (dock, w) pair. */
object ForwardSim {
    /** True if no staged element matches a docked container (dock containers always have space). */
    fun stable(s: RevState): Boolean {
        val st = s.staging
        return s.dock.all { d -> d == null || d.color !in st }
    }

    /** Applies a tap of container (id, colour, capacity); null if the dock is full. */
    fun tap(s: RevState, id: Int, color: Int, capacity: Int): RevState? {
        val slot = s.dock.indexOfFirst { it == null }
        if (slot < 0) return null
        val dock = s.dock.toMutableList()
        dock[slot] = Docked(id, color, capacity)
        val staging = ArrayList(s.staging)
        var qi = staging.size
        val w = s.w
        while (true) {
            var boarded = false
            for (k in 0 until Limits.DOCK_SLOTS) {
                val dc = dock[k] ?: continue
                val j = staging.indexOf(dc.color)
                if (j < 0) continue
                staging.removeAt(j)
                dock[k] = if (dc.space == 1) null else dc.copy(space = dc.space - 1)
                while (staging.size < Limits.STAGING_SPOTS && qi < w.size) staging += w[qi++]
                boarded = true
                break
            }
            if (!boarded) break
        }
        return RevState(dock, staging + w.subList(qi, w.size))
    }
}

/** The tapped container of a reverse step. */
sealed interface XSpec {
    /** X is docked in S' at [slot]; all of its load was boarded during this tap. */
    data class Existing(val slot: Int) : XSpec

    /** X is new: it filled during this tap and dispatched. */
    data class New(val id: Int, val size: ContainerSize, val color: Int) : XSpec
}

/**
 * A cascade recipient: [j] elements reached the container in [slot] from refills during the tap. If [newId] is
 * non-null the container was dispatched by this tap (it is absent from S'); it is un-dispatched into [slot] with
 * spaceLeft [j] in S.
 */
data class Recipient(val slot: Int, val j: Int, val newId: Int? = null, val size: ContainerSize? = null, val color: Int = -1)

/** A built reverse step: the pre-tap state S and the tapped container. */
data class BuiltStep(val before: RevState, val xId: Int, val xColor: Int, val xCap: Int, val xSlot: Int)

object ReverseStep {
    /**
     * Builds S from [after] for the given X and recipients, trying several element orders; returns the first
     * candidate that reproduces [after] exactly under [ForwardSim.tap] and is stable, or null. [insertAt] puts X's
     * elements at that staging index (capped at the first element of X's colour) instead of near the front.
     */
    fun build(
        after: RevState,
        x: XSpec,
        recipients: List<Recipient>,
        rng: Rng,
        capacityOf: (Int) -> Int,
        spread: Int = 4,
        tries: Int = 6,
        insertAt: Int? = null,
    ): BuiltStep? {
        val dockS = after.dock.toMutableList()
        val xId: Int
        val xColor: Int
        val xCap: Int
        val jX: Int
        when (x) {
            is XSpec.Existing -> {
                val d = after.dock[x.slot] ?: return null
                xId = d.id; xColor = d.color
                xCap = capacityOf(d.id)
                jX = xCap - d.space
                dockS[x.slot] = null
            }
            is XSpec.New -> {
                xId = x.id; xColor = x.color; xCap = x.size.capacity; jX = xCap
            }
        }
        var sumJ = 0
        // Existing recipients take their colour from the dock.
        val recipients = recipients.map { r -> if (r.newId == null) r.copy(color = after.dock[r.slot]?.color ?: -1) else r }
        for (r in recipients) {
            if (r.j <= 0) return null
            sumJ += r.j
            if (r.newId != null) {
                if (after.dock[r.slot] != null || dockS[r.slot] != null) return null
                val cap = r.size!!.capacity
                if (r.j > cap) return null
                dockS[r.slot] = Docked(r.newId, r.color, r.j)
            } else {
                val d = dockS[r.slot] ?: return null
                if (d.id == xId) return null
                val cap = capacityOf(d.id)
                if (d.space + r.j > cap) return null
                dockS[r.slot] = d.copy(space = d.space + r.j)
            }
        }
        if (dockS.count { it != null } >= Limits.DOCK_SLOTS) return null
        if (sumJ > 0 && jX == 0) return null
        val w = after.w
        val p = Limits.STAGING_SPOTS - jX
        if (sumJ > 0 && w.size < p) return null
        val head = w.subList(0, minOf(p, w.size))
        val tail = w.subList(minOf(p, w.size), w.size)
        val firstC = head.indexOf(xColor).let { if (it < 0) head.size else it }
        val orders = recipientOrders(recipients)
        repeat(tries) { attempt ->
            // X's elements: the oldest of colour xColor, so inserted at or before the first xColor of the head; near
            // the front ([spread]) so staging behaves like a sliding window over the consumption order.
            val a = ArrayList<Int>(head.size + jX)
            val picks = if (insertAt != null) List(jX) { minOf(insertAt, firstC) } else IntArray(jX) { rng.nextInt(minOf(firstC, spread) + 1) }.sorted()
            var pi = 0
            for (i in 0..head.size) {
                while (pi < jX && picks[pi] == i) { a += xColor; pi++ }
                if (i < head.size) a += head[i]
            }
            val rb = orders[attempt % orders.size]
            val ws = ArrayList<Int>(w.size + jX + sumJ)
            ws += a; ws += rb; ws += tail
            val before = RevState(dockS, ws)
            if (!ForwardSim.stable(before)) return@repeat
            val slot = dockS.indexOfFirst { it == null }
            val fwd = ForwardSim.tap(before, xId, xColor, xCap) ?: return@repeat
            if (fwd == after) return BuiltStep(before, xId, xColor, xCap, slot)
        }
        return null
    }

    private fun recipientOrders(rs: List<Recipient>): List<List<Int>> {
        if (rs.isEmpty()) return listOf(emptyList())
        val bySlot = rs.sortedBy { it.slot }
        val blocks = bySlot.flatMap { r -> List(r.j) { r.color } }
        val rev = bySlot.reversed().flatMap { r -> List(r.j) { r.color } }
        val rr = ArrayList<Int>()
        val left = bySlot.map { it.j }.toIntArray()
        while (left.any { it > 0 }) for (i in bySlot.indices) if (left[i] > 0) { rr += bySlot[i].color; left[i]-- }
        return listOf(blocks, rev, rr).distinct()
    }

}

/** SplitMix64: small deterministic RNG (no global Random). */
class Rng(seed: Long) {
    private var s = seed xor -0x61c8864680b583ebL

    fun nextLong(): Long {
        s += -0x61c8864680b583ebL
        var z = s
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(n: Int): Int {
        require(n > 0)
        return ((nextLong() ushr 1) % n).toInt()
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun chance(p: Double): Boolean = nextDouble() < p

    fun <T> pick(xs: List<T>): T = xs[nextInt(xs.size)]

    /** Fisher-Yates shuffle (copy). */
    fun <T> shuffle(xs: List<T>): List<T> {
        val a = xs.toMutableList()
        for (i in a.size - 1 downTo 1) { val j = nextInt(i + 1); val t = a[i]; a[i] = a[j]; a[j] = t }
        return a
    }
}
