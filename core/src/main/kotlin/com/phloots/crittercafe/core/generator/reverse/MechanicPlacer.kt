package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import kotlin.math.abs

/**
 * A recipe's request for a v4 mechanic: [count] instances (containers, tie pairs, restricted bench slots,
 * turntables or gates), each of which must bind at least [minBinds] times on the intended line
 * ([MechanicUse.of], hidden: decisions taken before the reveal).
 * Frozen: the counter is the container's release step on the intended line, kept within [minN]..[maxN].
 */
data class MechanicRequest(
    val mechanic: Mechanic,
    val count: Int = 1,
    val minBinds: Int = 1,
    val minN: Int = 2,
    val maxN: Int = 40,
)

/** One placed instance, for reports: the containers it uses and what it does on the intended line. */
data class PlacedMechanic(val mechanic: Mechanic, val ids: List<Int>, val binds: Int, val note: String)

/** A level with mechanics placed on top of a generated level; the intended [StallLevel.line] still wins. */
data class MechanicLevel(val sl: StallLevel, val base: Level, val placed: List<PlacedMechanic>, val hidden: List<HiddenCheck>)

/**
 * Places v4 mechanics on a generated level so that they shape the intended line instead of decorating it
 * (DESIGN.md "Mechanics (v4)"). The intended line of the base level is the anchor: every placement keeps it a
 * winning line (checked by replay after every instance), and each instance is chosen for how often it binds on that
 * line ([MechanicUse]):
 *  - frozen: a container whose way out opens early but whose release comes late, frozen for exactly its release
 *    step, preferring one whose colour waits on the carousel meanwhile (it has to be waited for);
 *  - tied: a pair (a tied to p) where a's way out opens before p leaves the board on the line, p not blocking a
 *    (else the ribbon says nothing new), close together, preferring a p that docks as a dummy (off-colour);
 *  - hidden: a container with a cover, chosen for the decisions taken before its reveal and a short reveal-to-use
 *    lead, accepted only if [HiddenCheck] proves the blind moves right in every world that looks the same;
 *  - wide: an M/L container whose doubled footprint blocks a release or holds two slots on the line;
 *  - colour bench: a (slot, colour) restriction that pushes releases off their slot or refuses a free slot;
 *  - turntable: a size-S container, its start facing chosen so that it faces its intended exit exactly at its
 *    intended release, preferring one that has to wait for its turn;
 *  - gate: an exit cell crossed on the line only at releases of one parity, its start state chosen open at those
 *    releases, preferring cells where other containers find the gate closed.
 * Candidates are ranked by the bind gain; the [rng] picks among the best few so that tries differ.
 * Deterministic for a given (level, line, seed).
 */
class MechanicPlacer(private val sl: StallLevel, private val base: Level, seed: Long, private val hiddenBudget: Long = 150_000) {
    private val line: List<Move> = sl.line
    private val rng = java.util.Random(seed * 0x2545F4914F6CDD1DL + 0x6D)
    private var lv = base
    private val used = HashSet<Int>()
    private val placed = ArrayList<PlacedMechanic>()
    private val hidden = ArrayList<HiddenCheck>()
    private val n = base.containers.size
    private val step = IntArray(n) { -1 }.also { a -> line.forEachIndexed { i, m -> a[m.containerId] = i } }
    var failure = ""
        private set
    private var hiddenWhy = ""

    private class Cand(val level: Level, val gain: Double, val ids: List<Int>, val binds: Int, val note: String)

    /** Replays the intended line on [l]; true iff it wins. */
    private fun wins(l: Level): Boolean {
        if (l.validate().isNotEmpty()) return false
        var s = GameState.initial(l)
        for (m in line) {
            if (s.status != Status.PLAYING || !Engine.isTappable(s, m.containerId)) return false
            s = Engine.apply(s, m).state
        }
        return s.status == Status.WON
    }

    private fun use(l: Level) = MechanicUse.measure(l, line)

    /** Picks among the best candidates: the top one with probability 1/2, else one of the next two. */
    private fun choose(cands: List<Cand>): Cand? {
        if (cands.isEmpty()) return null
        val sorted = cands.sortedWith(compareByDescending<Cand> { it.gain }.thenBy { it.ids.first() })
        val r = rng.nextInt(4)
        return sorted[if (r < 2) 0 else minOf(sorted.size - 1, r - 1)]
    }

    fun run(requests: List<MechanicRequest>): MechanicLevel? {
        for (req in requests) repeat(req.count) {
            val ok = when (req.mechanic) {
                Mechanic.FROZEN -> frozen(req)
                Mechanic.TIED -> tied(req)
                Mechanic.HIDDEN -> hide(req)
                Mechanic.WIDE -> wide(req)
                Mechanic.COLOUR_BENCH -> bench(req)
                Mechanic.TURNTABLE -> turntable(req)
                Mechanic.GATE -> gate(req)
            }
            if (!ok) {
                failure = "no ${req.mechanic.id} placement #${it + 1}" + (if (req.mechanic == Mechanic.HIDDEN) " ($hiddenWhy)" else "")
                return null
            }
        }
        if (!wins(lv)) { failure = "line broken"; return null }
        return MechanicLevel(sl.copy(level = lv), base, placed, hidden)
    }

    private fun accept(c: Cand, m: Mechanic): Boolean {
        lv = c.level
        used += c.ids
        placed += PlacedMechanic(m, c.ids, c.binds, c.note)
        return true
    }

    private fun free(id: Int) = id !in used && step[id] >= 0

    private fun frozen(req: MechanicRequest): Boolean {
        val before = use(lv).frozenBinds
        val gs = MechanicUse.states(lv, line)
        val cands = ArrayList<Cand>()
        for (v in lv.containers) {
            val k = step[v.id]
            if (!free(v.id) || k < req.minN || k > req.maxN) continue
            val l = lv.copy(containers = lv.containers.map { if (it.id == v.id) it.copy(frozen = k) else it })
            if (!wins(l)) continue
            val binds = use(l).frozenBinds - before
            if (binds < req.minBinds) continue
            // Waiting pressure: mean staged elements of its colour while frozen.
            val wait = gs.take(k).map { s -> s.staging.count { it == v.color } }.average()
            cands += Cand(l, binds + minOf(wait, 8.0) / 2, listOf(v.id), binds, "frozen #${v.id} (${name(v.color)} ${v.size}) N=$k, binds $binds, waiting %.1f".format(wait))
        }
        return choose(cands)?.let { accept(it, Mechanic.FROZEN) } ?: false
    }

    private fun tied(req: MechanicRequest): Boolean {
        val before = use(lv).tiedBinds
        val gs = MechanicUse.states(lv, line)
        // First state at which each container could leave but for the tie (path and gate clear).
        val firstClear = IntArray(n) { -1 }
        for ((i, s) in gs.withIndex()) {
            val occ = MechanicUse.occupancy(s)
            for (v in lv.containers) if (firstClear[v.id] < 0 && s.onBoard[v.id] &&
                MechanicUse.rayClear(s, occ, v.id, s.dirOf(v.id)) && MechanicUse.gateOpen(s, v.id, s.dirOf(v.id))) firstClear[v.id] = i
        }
        val partners = lv.containers.map { it.tiedTo }.filter { it >= 0 }.toSet()
        val cands = ArrayList<Cand>()
        for (a in lv.containers) {
            if (!free(a.id) || firstClear[a.id] < 0) continue
            for (p in lv.containers) {
                if (p.id == a.id || !free(p.id) || p.id in partners) continue
                if (step[p.id] >= step[a.id] || step[p.id] <= firstClear[a.id]) continue
                if (abs(p.row - a.row) + abs(p.col - a.col) > 5) continue
                val l = lv.copy(containers = lv.containers.map { if (it.id == a.id) it.copy(tiedTo = p.id) else it })
                if (!wins(l)) continue
                val binds = use(l).tiedBinds - before
                if (binds < req.minBinds) continue
                val dummy = gs[step[p.id]].staging.none { it == p.color }
                cands += Cand(l, binds + (if (dummy) 2.0 else 0.0), listOf(a.id, p.id), binds,
                    "tied #${a.id} (${name(a.color)} ${a.size}) to #${p.id} (${name(p.color)} ${p.size}${if (dummy) ", docks as a dummy" else ""}), binds $binds")
            }
        }
        return choose(cands)?.let { accept(it, Mechanic.TIED) } ?: false
    }

    private fun hide(req: MechanicRequest): Boolean {
        val gs = MechanicUse.states(lv, line)
        val cands = ArrayList<Cand>()
        for (v in lv.containers) {
            if (!free(v.id) || lv.onTurntable(v.id)) continue
            val cover = lv.coverOf(v.id)
            if (cover < 0 || cover in used && lv.containers[cover].hidden) continue
            val rc = step[cover]
            if (rc < 0) continue
            val l = lv.copy(containers = lv.containers.map { if (it.id == v.id) it.copy(hidden = true) else it })
            if (!wins(l)) continue
            // Decisions taken while this one is unrevealed.
            val dec = gs.take(rc + 1).count { Engine.legalMoves(it).size >= 2 }
            if (dec < req.minBinds) continue
            val lead = step[v.id] - rc - 1
            val gain = minOf(dec, 12) + (if (lead in 0..3) 3.0 else 0.0) + (if (v.size != ContainerSize.S) 1.0 else 0.0)
            cands += Cand(l, gain, listOf(v.id), dec, "hidden #${v.id} (${name(v.color)} ${v.size}) under #$cover: $dec decisions unrevealed, used $lead moves after the reveal")
        }
        // Accept the best candidate that passes the information check; only those for which a look-alike world
        // exists can (the others' colour follows from what is on show, so hiding it says nothing).
        val sorted = cands.filter { HiddenCheck.hasWorld(it.level, line, it.ids[0]) }
            .sortedWith(compareByDescending<Cand> { it.gain }.thenBy { it.ids.first() })
        val start = if (sorted.size > 1 && rng.nextBoolean()) 1 else 0
        var noWorld = 0; var lost = 0; var unknown = 0
        for (c in sorted.drop(start).take(8)) {
            val chk = HiddenCheck.run(c.level, line, c.ids[0], hiddenBudget)
            if (chk.worlds == 0) noWorld++ else if (chk.lost > 0) lost++ else if (chk.unknown > 0) unknown++
            if (!chk.robust) continue
            hidden += chk
            return accept(Cand(c.level, c.gain, c.ids, c.binds, c.note + ", " + chk.summary()), Mechanic.HIDDEN)
        }
        hiddenWhy = "${cands.size} candidates, ${sorted.size} with a look-alike world; checked: no world $noWorld, lost $lost, unknown $unknown"
        return false
    }

    private fun wide(req: MechanicRequest): Boolean {
        val u0 = use(lv)
        val cands = ArrayList<Cand>()
        val sizes = if (lv.containers.any { free(it.id) && it.size != ContainerSize.S }) setOf(ContainerSize.M, ContainerSize.L) else ContainerSize.entries.toSet()
        for (v in lv.containers) {
            if (!free(v.id) || v.size !in sizes) continue
            val l = lv.copy(containers = lv.containers.map { if (it.id == v.id) it.copy(wide = true) else it })
            if (!wins(l)) continue
            val u = use(l)
            val blocks = u.wideBlocks - u0.wideBlocks
            val tight = u.wideTight - u0.wideTight
            val docked = u.wideDocked - u0.wideDocked
            if (blocks + tight < req.minBinds) continue
            cands += Cand(l, 2.0 * blocks + tight + docked / 4.0, listOf(v.id), blocks + tight,
                "wide #${v.id} (${name(v.color)} ${v.size}): blocks $blocks, tight dock $tight of $docked docked states")
        }
        return choose(cands)?.let { accept(it, Mechanic.WIDE) } ?: false
    }

    private fun bench(req: MechanicRequest): Boolean {
        val bc = if (lv.benchColors.size == Limits.DOCK_SLOTS) lv.benchColors else List(Limits.DOCK_SLOTS) { -1 }
        val u0 = use(lv)
        val cands = ArrayList<Cand>()
        for (slot in 0 until Limits.DOCK_SLOTS) {
            if (bc[slot] >= 0) continue
            for (c in lv.colors()) {
                val l = lv.copy(benchColors = bc.toMutableList().also { it[slot] = c })
                if (!wins(l)) continue
                val u = use(l)
                val b = (u.benchSkips + u.benchBlocks) - (u0.benchSkips + u0.benchBlocks)
                if (b < req.minBinds) continue
                cands += Cand(l, b.toDouble(), listOf(slot, c), b, "bench slot $slot = ${name(c)} only: skips ${u.benchSkips - u0.benchSkips}, blocks ${u.benchBlocks - u0.benchBlocks}")
            }
        }
        val c = choose(cands) ?: return false
        lv = c.level
        placed += PlacedMechanic(Mechanic.COLOUR_BENCH, emptyList(), c.binds, c.note)
        return true
    }

    private fun turntable(req: MechanicRequest): Boolean {
        val before = use(lv).turnWaits
        val cands = ArrayList<Cand>()
        for (v in lv.containers) {
            if (!free(v.id) || v.size != ContainerSize.S || v.hidden) continue
            val k = step[v.id]
            val cell = Cell(v.row, v.col)
            if (cell in lv.turntables) continue
            // Facing at state k (k releases so far) is the original one: start turned back k times.
            val d0 = v.dir.turnedClockwise(-k)
            val l = lv.copy(containers = lv.containers.map { if (it.id == v.id) it.copy(dir = d0) else it }, turntables = lv.turntables + cell)
            if (!wins(l)) continue
            val binds = use(l).turnWaits - before
            if (binds < req.minBinds) continue
            cands += Cand(l, binds.toDouble(), listOf(v.id), binds, "turntable under #${v.id} (${name(v.color)}) at (${v.row},${v.col}), starts $d0, exits ${v.dir} at release $k, waits $binds")
        }
        return choose(cands)?.let { accept(it, Mechanic.TURNTABLE) } ?: false
    }

    private fun gate(req: MechanicRequest): Boolean {
        val gs = MechanicUse.states(lv, line)
        val crossings = HashMap<Pair<com.phloots.crittercafe.core.model.Dir, Int>, MutableList<Int>>()
        for ((k, s) in gs.withIndex()) {
            val id = line[k].containerId
            val v = lv.containers[id]
            crossings.getOrPut(MechanicUse.exitKey(s.dirOf(id), v.row, v.col)) { ArrayList() } += k
        }
        val taken = lv.gates.map { it.side to it.index }.toSet()
        val before = use(lv).gateBlocks
        val cands = ArrayList<Cand>()
        for ((key, ks) in crossings) {
            if (key in taken || ks.map { it % 2 }.distinct().size != 1) continue
            val g = Gate(key.first, key.second, open = ks[0] % 2 == 0)
            val l = lv.copy(gates = lv.gates + g)
            if (!wins(l)) continue
            val binds = use(l).gateBlocks - before
            if (binds < req.minBinds) continue
            cands += Cand(l, binds + 0.5 * ks.size, listOf(key.second), binds,
                "gate ${key.first} ${key.second} (${if (g.open) "open" else "closed"} at start): ${ks.size} crossings, blocks $binds")
        }
        val c = choose(cands) ?: return false
        lv = c.level
        placed += PlacedMechanic(Mechanic.GATE, emptyList(), c.binds, c.note)
        return true
    }

    companion object {
        private fun name(c: Int) = StallLevel.colorName(c)

        /**
         * Places [requests] on [sl] (its intended line stays a win). Null if some request cannot be met; the level's
         * [Level.queuePreview] is set to [queuePreview].
         */
        fun place(sl: StallLevel, requests: List<MechanicRequest>, seed: Long, queuePreview: Int = sl.level.queuePreview,
                  hiddenBudget: Long = 150_000): MechanicLevel? {
            return MechanicPlacer(sl, sl.level.copy(queuePreview = queuePreview), seed, hiddenBudget).run(requests)
        }
    }
}
