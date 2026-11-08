package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.solver.Solver

/**
 * How often each v4 mechanic (DESIGN.md "Mechanics (v4)") mattered along a line: per state, the containers that every
 * other rule would let leave but this mechanic stops (a *bind*). Counts are (state, container) pairs unless noted.
 *
 * @property frozenBinds frozen, but otherwise free to leave (untied, path and exit gate clear, a fitting slot)
 * @property tiedBinds tie partner still on the board, otherwise free
 * @property hiddenDecisions states with >= 2 legal moves while some hidden container is still unrevealed
 * @property hiddenLeads per hidden container (ascending id): moves between its reveal and its release (0 = released
 *   right after its cover); -1 if the line never released it
 * @property wideBlocks a wide container otherwise free, a slot free, but no adjacent fitting pair
 * @property wideDocked states with a wide container docked (taking two slots)
 * @property wideTight states with a wide container docked and at most one slot free (its second slot is the slack)
 * @property benchSkips releases on the line that passed over a free slot because a bench refused the colour
 * @property benchBlocks a (non-wide) container otherwise free, a slot free, but no free slot accepts its colour
 * @property turnWaits a turntable container otherwise free whose current facing is blocked while another is clear
 * @property turnReleases turntable containers released on the line
 * @property gateBlocks a container otherwise free whose exit cell's gate is closed
 * @property gateCrossings releases on the line through a gate
 */
data class MechanicUse(
    val frozenBinds: Int = 0,
    val tiedBinds: Int = 0,
    val hiddenDecisions: Int = 0,
    val hiddenLeads: List<Int> = emptyList(),
    val wideBlocks: Int = 0,
    val wideDocked: Int = 0,
    val wideTight: Int = 0,
    val benchSkips: Int = 0,
    val benchBlocks: Int = 0,
    val turnWaits: Int = 0,
    val turnReleases: Int = 0,
    val gateBlocks: Int = 0,
    val gateCrossings: Int = 0,
) {
    /** The headline count for [m]: how often the mechanic constrained a move (hidden: decisions taken unrevealed). */
    fun of(m: Mechanic): Int = when (m) {
        Mechanic.FROZEN -> frozenBinds
        Mechanic.TIED -> tiedBinds
        Mechanic.HIDDEN -> hiddenDecisions
        Mechanic.WIDE -> wideBlocks + wideTight
        Mechanic.COLOUR_BENCH -> benchSkips + benchBlocks
        Mechanic.TURNTABLE -> turnWaits
        Mechanic.GATE -> gateBlocks
    }

    fun summary(ms: List<Mechanic>): String = ms.joinToString(", ") { m ->
        when (m) {
            Mechanic.FROZEN -> "frozen binds $frozenBinds"
            Mechanic.TIED -> "tied binds $tiedBinds"
            Mechanic.HIDDEN -> "hidden: $hiddenDecisions decisions unrevealed, leads ${hiddenLeads.joinToString("/")}"
            Mechanic.WIDE -> "wide blocks $wideBlocks, tight $wideTight, docked $wideDocked states"
            Mechanic.COLOUR_BENCH -> "bench skips $benchSkips, blocks $benchBlocks"
            Mechanic.TURNTABLE -> "turn waits $turnWaits, releases $turnReleases"
            Mechanic.GATE -> "gate blocks $gateBlocks, crossings $gateCrossings"
        }
    }

    companion object {
        /** Exit cell of a container leaving in [d] from front cell ([row], [col]): the gate key (side, index). */
        fun exitKey(d: Dir, row: Int, col: Int): Pair<Dir, Int> = d to (if (d == Dir.UP || d == Dir.DOWN) col else row)

        /** Board occupancy of [s]: cell index (row * width + col) -> on-board container id, else -1. */
        fun occupancy(s: GameState): IntArray {
            val lv = s.level
            val occ = IntArray(lv.width * lv.height) { -1 }
            for (v in lv.containers) if (s.onBoard[v.id]) for (c in v.cells()) occ[c.row * lv.width + c.col] = v.id
            return occ
        }

        /** No other on-board container on [id]'s ray in [d] (gates ignored). */
        fun rayClear(s: GameState, occ: IntArray, id: Int, d: Dir): Boolean {
            val v = s.level.containers[id]
            var r = v.row + d.dRow
            var c = v.col + d.dCol
            while (s.level.inBounds(r, c)) {
                val o = occ[r * s.level.width + c]
                if (o >= 0 && o != id) return false
                r += d.dRow; c += d.dCol
            }
            return true
        }

        /** The exit gate on [id]'s way out in [d] is open (or there is none). */
        fun gateOpen(s: GameState, id: Int, d: Dir): Boolean {
            val v = s.level.containers[id]
            val (side, index) = exitKey(d, v.row, v.col)
            val g = s.level.gates.indexOfFirst { it.side == side && it.index == index }
            return g < 0 || s.isGateOpen(g)
        }

        /** Leftmost slot (pair for a wide container) [id] may take, honouring benches iff [benches]; -1 if none. */
        fun fitSlot(s: GameState, id: Int, benches: Boolean = true): Int {
            val v = s.level.containers[id]
            val k = v.dockSlots
            for (start in 0..Limits.DOCK_SLOTS - k) {
                if ((start until start + k).all { s.dock[it] == null && (!benches || s.level.benchAccepts(it, v.color)) }) return start
            }
            return -1
        }

        /** Replays [line] from the start; the states before each move (stops early if a move is not legal). */
        fun states(level: Level, line: List<Move>): List<GameState> {
            val out = ArrayList<GameState>()
            var s = GameState.initial(level)
            for (m in line) {
                if (s.status != Status.PLAYING || !Engine.isTappable(s, m.containerId)) break
                out += s
                s = Engine.apply(s, m).state
            }
            return out
        }

        fun measure(level: Level, line: List<Move>): MechanicUse {
            val gs = states(level, line)
            val lv = level
            var frozen = 0; var tied = 0; var hidDec = 0; var wideB = 0; var wideD = 0; var wideT = 0; var benchS = 0; var benchB = 0
            var turnW = 0; var turnR = 0; var gateB = 0; var gateC = 0
            val hiddenIds = lv.containers.filter { it.hidden }.map { it.id }
            val hasBench = lv.benchColors.any { it >= 0 }
            for ((step, s) in gs.withIndex()) {
                val occ = occupancy(s)
                val anyFree = s.dock.any { it == null }
                if (s.dock.withIndex().any { (k, dc) -> dc != null && s.isWideTail(k) }) {
                    wideD++
                    if (s.dockUsed >= Limits.DOCK_SLOTS - 1) wideT++
                }
                if (hiddenIds.any { s.onBoard[it] && !s.isRevealed(it) } && Engine.legalMoves(s).size >= 2) hidDec++
                for (v in lv.containers) {
                    val id = v.id
                    if (!s.onBoard[id]) continue
                    val fz = s.frozenRemaining(id) > 0
                    val td = s.isTieLocked(id)
                    val d = s.dirOf(id)
                    val ray = rayClear(s, occ, id, d)
                    val gate = gateOpen(s, id, d)
                    val slot = fitSlot(s, id) >= 0
                    if (fz && !td && ray && gate && slot) frozen++
                    if (!fz && td && ray && gate && slot) tied++
                    if (!fz && !td && ray && !gate && slot) gateB++
                    if (!fz && !td && ray && gate && !slot && anyFree) {
                        if (v.wide && fitSlot(s, id, benches = false) < 0) wideB++ else if (hasBench) benchB++
                    }
                    if (lv.turntables.isNotEmpty() && lv.onTurntable(id) && !fz && !td && slot && !(ray && gate)) {
                        if (Dir.entries.any { it != d && rayClear(s, occ, id, it) && gateOpen(s, id, it) }) turnW++
                    }
                }
                // The move on the line: bench skips, turntable releases, gate crossings.
                val m = line[step].containerId
                val v = lv.containers[m]
                val d = s.dirOf(m)
                if (hasBench) {
                    val taken = (Engine.apply(s, Move(m)).events.firstOrNull { it is Event.Released } as? Event.Released)?.slot ?: -1
                    if (taken >= 0 && taken != fitSlot(s, m, benches = false)) benchS++
                }
                if (lv.turntables.isNotEmpty() && lv.onTurntable(m)) turnR++
                if (lv.gates.isNotEmpty()) {
                    val (side, index) = exitKey(d, v.row, v.col)
                    if (lv.gates.any { it.side == side && it.index == index }) gateC++
                }
            }
            val stepOf = HashMap<Int, Int>()
            line.forEachIndexed { i, mv -> stepOf[mv.containerId] = i }
            val leads = hiddenIds.map { h ->
                val rel = stepOf[h] ?: return@map -1
                val cover = lv.coverOf(h)
                val rc = stepOf[cover] ?: return@map -1
                rel - rc - 1
            }
            return MechanicUse(frozen, tied, hidDec, leads, wideB, wideD, wideT, benchS, benchB, turnW, turnR, gateB, gateC)
        }
    }
}

/**
 * The hidden-ticket information check (DESIGN.md "Mechanics (v4)", hidden): the player must never need a hidden
 * colour before it is revealed. For hidden container h with cover c, every *world* that looks identical to the
 * player until c leaves is built: h gets another colour c' and the last capacity(h) elements of h's colour that were
 * never on show before the reveal (beyond the carousel and the belt's [Level.queuePreview]) are recoloured c'. The
 * intended line up to and including c's release is then replayed in each world (the states before it are the same,
 * so it is legal) and the state at the reveal is solved. If every world is solvable, the moves made blind were
 * correct whatever the colour turned out to be.
 *
 * @property worlds colours c' for which an indistinguishable world could be built
 * @property won worlds whose reveal state the solver proved solvable; [lost] proved lost; [unknown] over budget
 */
data class HiddenCheck(val id: Int, val cover: Int, val worlds: Int, val won: Int, val lost: Int, val unknown: Int) {
    val robust: Boolean get() = worlds > 0 && lost == 0 && unknown == 0

    fun summary() = "#$id (cover #$cover): $won/$worlds worlds won" + (if (lost > 0) ", $lost lost" else "") + (if (unknown > 0) ", $unknown unknown" else "")

    companion object {
        /**
         * Queue indices of [id]'s colour never on show before its reveal (beyond the carousel and the belt in every
         * state up to its cover's release), or null if the cover is not released on [line].
         */
        private fun unseen(level: Level, line: List<Move>, id: Int): List<Int>? {
            val cover = level.coverOf(id)
            val rc = line.indexOf(Move(cover))
            if (cover < 0 || rc < 0) return null
            val gs = MechanicUse.states(level, line.take(rc + 1))
            if (gs.size != rc + 1) return null
            val shown = gs.maxOf { it.queueIndex + level.queuePreview }
            val c = level.containers[id].color
            return (shown until level.queue.size).filter { level.queue[it] == c }
        }

        /** True iff a look-alike world exists (enough of its colour stays unseen until the reveal). */
        fun hasWorld(level: Level, line: List<Move>, id: Int): Boolean =
            (unseen(level, line, id)?.size ?: -1) >= level.containers[id].size.capacity

        fun run(level: Level, line: List<Move>, id: Int, budget: Long = 150_000): HiddenCheck {
            val cover = level.coverOf(id)
            val rc = line.indexOf(Move(cover))
            if (cover < 0 || rc < 0) return HiddenCheck(id, cover, 0, 0, 0, 0)
            val beyond = unseen(level, line, id) ?: return HiddenCheck(id, cover, 0, 0, 0, 0)
            val h = level.containers[id]
            val cap = h.size.capacity
            var worlds = 0; var won = 0; var lost = 0; var unk = 0
            if (beyond.size >= cap) for (alt in level.colors()) {
                if (alt == h.color) continue
                val recolour = beyond.takeLast(cap).toSet()
                val q = level.queue.mapIndexed { i, c -> if (i in recolour) alt else c }
                val conts = level.containers.map { if (it.id == id) it.copy(color = alt) else it }
                val w = level.copy(containers = conts, queue = q)
                if (w.validate().isNotEmpty()) continue
                var s = GameState.initial(w)
                var ok = true
                for (m in line.take(rc + 1)) {
                    if (!Engine.isTappable(s, m.containerId)) { ok = false; break }
                    s = Engine.apply(s, m).state
                }
                worlds++
                if (!ok) { lost++; continue }
                when {
                    s.status == Status.WON -> won++
                    s.status != Status.PLAYING -> lost++
                    else -> {
                        val r = Solver.solve(s, budget)
                        when {
                            r == null -> unk++
                            r.solvable -> won++
                            else -> lost++
                        }
                    }
                }
            }
            return HiddenCheck(id, cover, worlds, won, lost, unk)
        }
    }
}

/** Front cell of a container (turntable key). */
internal fun frontCell(level: Level, id: Int): Cell = level.containers[id].let { Cell(it.row, it.col) }
