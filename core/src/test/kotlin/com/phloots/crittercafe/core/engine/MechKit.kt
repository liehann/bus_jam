package com.phloots.crittercafe.core.engine

import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.DockedContainer
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.IgnoreReason
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.model.Transition
import kotlin.random.Random

/**
 * Deliberately naive reference implementation of the v4 mechanic rules (DESIGN.md "Mechanics (v4)"), written from the
 * spec without the engine's precomputation (cells found by scanning containers, one element boards at a time).
 * Ground truth for differential tests of Engine, Solver and the real-time layer.
 */
object MechRef {
    fun releases(s: GameState) = s.level.containers.size - s.onBoard.count()

    fun dir(s: GameState, id: Int): Dir {
        val c = s.level.containers[id]
        return if (Cell(c.row, c.col) in s.level.turntables) c.dir.turnedClockwise(releases(s)) else c.dir
    }

    private fun occupant(s: GameState, cell: Cell): Int? =
        s.level.containers.firstOrNull { s.onBoard[it.id] && cell in it.cells() }?.id

    /** (stop cell, blocker id or -1 for a closed gate), or null if the exit path is clear. */
    fun blocker(s: GameState, id: Int): Pair<Cell, Int>? {
        val v = s.level.containers[id]
        val d = dir(s, id)
        var front = Cell(v.row, v.col)
        while (true) {
            val next = Cell(front.row + d.dRow, front.col + d.dCol)
            if (!s.level.inBounds(next.row, next.col)) {
                val index = if (d == Dir.UP || d == Dir.DOWN) front.col else front.row
                val gate = s.level.gates.firstOrNull { it.side == d && it.index == index } ?: return null
                val open = gate.open != (releases(s) % 2 == 1)
                return if (open) null else front to -1
            }
            val o = occupant(s, next)
            if (o != null && o != id) return front to o
            front = next
        }
    }

    fun frozen(s: GameState, id: Int) = s.level.containers[id].frozen > releases(s)

    fun tied(s: GameState, id: Int) = s.level.containers[id].tiedTo.let { it >= 0 && s.onBoard[it] }

    fun fitSlot(s: GameState, id: Int): Int? {
        val v = s.level.containers[id]
        val k = if (v.wide) 2 else 1
        fun accepts(slot: Int) = s.level.benchColors.isEmpty() || s.level.benchColors[slot] == -1 || s.level.benchColors[slot] == v.color
        for (start in 0..Limits.DOCK_SLOTS - k) {
            if ((start until start + k).all { s.dock[it] == null && accepts(it) }) return start
        }
        return null
    }

    private fun canLeave(s: GameState, id: Int) = s.onBoard[id] && !frozen(s, id) && !tied(s, id) && blocker(s, id) == null

    private fun moves(s: GameState): List<Move> =
        s.level.containers.filter { canLeave(s, it.id) && fitSlot(s, it.id) != null }.map { Move(it.id) }

    fun legal(s: GameState): List<Move> = if (s.status != Status.PLAYING) emptyList() else moves(s)

    fun status(s: GameState): Status {
        if (s.onBoard.isEmpty() && s.dock.all { it == null } && s.staging.isEmpty() && s.queueIndex == s.level.queue.size) {
            return Status.WON
        }
        if (s.dock.all { it != null }) return if (s.dock.any { it!!.color in s.staging }) Status.STUCK else Status.DEADLOCK
        if (moves(s).isNotEmpty()) return Status.PLAYING
        return if (s.level.containers.any { canLeave(s, it.id) }) Status.DEADLOCK else Status.STUCK
    }

    fun apply(s: GameState, m: Move): Transition {
        val id = m.containerId
        fun ign(r: IgnoreReason) = Transition(s, listOf(Event.Ignored(id, r)))
        if (s.status != Status.PLAYING) return ign(IgnoreReason.GAME_OVER)
        if (id < 0 || id >= s.level.containers.size) return ign(IgnoreReason.INVALID_ID)
        if (!s.onBoard[id]) return ign(IgnoreReason.NOT_ON_BOARD)
        if (frozen(s, id)) return ign(IgnoreReason.FROZEN)
        if (tied(s, id)) return ign(IgnoreReason.TIED)
        if (s.dock.none { it == null }) return ign(IgnoreReason.DOCK_FULL)
        val slot = fitSlot(s, id) ?: return ign(IgnoreReason.NO_FITTING_SLOT)
        blocker(s, id)?.let { (stop, b) -> return Transition(s, listOf(Event.Blocked(id, stop.row, stop.col, b))) }

        val ev = ArrayList<Event>()
        val dock = s.dock.toMutableList()
        val c = s.level.containers[id]
        val dc0 = DockedContainer(id, c.color, c.size.capacity)
        dock[slot] = dc0
        if (c.wide) dock[slot + 1] = dc0
        ev += Event.Released(id, slot)
        val staging = s.staging.zip(s.stagedElementIndices()).toMutableList()
        var qi = s.queueIndex
        loop@ while (true) {
            for (slotIdx in 0 until Limits.DOCK_SLOTS) {
                val dc = dock[slotIdx] ?: continue
                if (slotIdx > 0 && dock[slotIdx - 1]?.containerId == dc.containerId) continue
                val spot = staging.indexOfFirst { it.first == dc.color }
                if (spot < 0) continue
                ev += Event.Boarded(staging[spot].second, spot, slotIdx, dc.containerId)
                staging.removeAt(spot)
                val wideHere = s.level.containers[dc.containerId].wide
                val nd = if (dc.spaceLeft == 1) null else dc.copy(spaceLeft = dc.spaceLeft - 1)
                dock[slotIdx] = nd
                if (wideHere) dock[slotIdx + 1] = nd
                if (nd == null) ev += Event.Dispatched(slotIdx, dc.containerId)
                while (staging.size < s.level.stagingSpots && qi < s.level.queue.size) {
                    ev += Event.Staged(qi, staging.size)
                    staging += s.level.queue[qi] to qi
                    qi++
                }
                continue@loop
            }
            break
        }
        val n = GameState(s.level, s.onBoard.without(id), dock, staging.map { it.first }, qi)
        return Transition(n.copy(status = status(n)), ev)
    }
}

/** Exhaustive search over [MechRef], memoised on the raw state. Ground truth for the solver on mechanic levels. */
class MechBrute {
    private data class Key(val lo: Long, val hi: Long, val dock: List<DockedContainer?>, val staging: List<Int>, val qi: Int)

    private val memo = HashMap<Key, Boolean>()

    fun wins(s: GameState): Boolean {
        if (s.status == Status.WON) return true
        if (s.status != Status.PLAYING) return false
        val k = Key(s.onBoard.lo, s.onBoard.hi, s.dock, s.staging, s.queueIndex)
        memo[k]?.let { return it }
        val r = MechRef.legal(s).any { wins(MechRef.apply(s, it).state) }
        memo[k] = r
        return r
    }
}

/** Small random levels using v4 mechanics, for differential tests (not validated against board-size limits). */
object MechLevels {
    fun random(
        rnd: Random,
        mechanics: Set<Mechanic> = Mechanic.entries.toSet(),
        width: Int = 5,
        height: Int = 5,
        maxContainers: Int = 7,
        colors: Int = 3,
        spots: Int = 4 + rnd.nextInt(6),
    ): Level {
        val target = 3 + rnd.nextInt(maxContainers - 2)
        val used = HashSet<Cell>()
        val raw = ArrayList<Container>()
        var tries = 0
        while (raw.size < target && tries++ < 200) {
            val size = if (rnd.nextInt(3) == 0) ContainerSize.M else ContainerSize.S
            val dir = Dir.entries[rnd.nextInt(4)]
            val c = Container(raw.size, size, rnd.nextInt(colors), rnd.nextInt(height), rnd.nextInt(width), dir)
            val cells = c.cells()
            if (cells.any { it.row !in 0 until height || it.col !in 0 until width || it in used }) continue
            used += cells
            raw += c
        }
        val n = raw.size
        var cs = raw.toList()
        val turntables = ArrayList<Cell>()
        if (Mechanic.TURNTABLE in mechanics) {
            for (c in cs) if (c.size == ContainerSize.S && rnd.nextInt(3) == 0) turntables += Cell(c.row, c.col)
            if (rnd.nextInt(4) == 0) {
                val free = Cell(rnd.nextInt(height), rnd.nextInt(width))
                if (free !in used) turntables += free
            }
        }
        val onTurn = { c: Container -> Cell(c.row, c.col) in turntables }
        if (Mechanic.FROZEN in mechanics) {
            cs = cs.map { if (n > 1 && rnd.nextInt(4) == 0) it.copy(frozen = 1 + rnd.nextInt(minOf(3, n - 1))) else it }
        }
        if (Mechanic.TIED in mechanics) {
            val free = cs.indices.shuffled(rnd).toMutableList()
            val ties = HashMap<Int, Int>()
            while (free.size >= 2 && rnd.nextInt(2) == 0) {
                val a = free.removeAt(0)
                val b = free.removeAt(0)
                ties[a] = b
            }
            cs = cs.map { c -> ties[c.id]?.let { c.copy(tiedTo = it) } ?: c }
        }
        if (Mechanic.WIDE in mechanics) cs = cs.map { if (rnd.nextInt(4) == 0) it.copy(wide = true) else it }
        var bench = emptyList<Int>()
        if (Mechanic.COLOUR_BENCH in mechanics && rnd.nextBoolean()) {
            bench = List(Limits.DOCK_SLOTS) { if (rnd.nextInt(3) == 0) rnd.nextInt(colors) else -1 }
        }
        val gates = ArrayList<Gate>()
        if (Mechanic.GATE in mechanics) {
            val seen = HashSet<Pair<Dir, Int>>()
            repeat(1 + rnd.nextInt(3)) {
                val side = Dir.entries[rnd.nextInt(4)]
                val index = rnd.nextInt(if (side == Dir.UP || side == Dir.DOWN) width else height)
                if (seen.add(side to index)) gates += Gate(side, index, rnd.nextBoolean())
            }
        }
        val queue = cs.flatMap { c -> List(c.size.capacity) { c.color } }.shuffled(rnd)
        var level = Level(1, width, height, cs, queue, spots, bench, turntables, gates)
        if (Mechanic.HIDDEN in mechanics) {
            level = level.copy(containers = level.containers.map { c ->
                if (!onTurn(c) && level.coverOf(c.id) >= 0 && rnd.nextInt(3) == 0) c.copy(hidden = true) else c
            })
        }
        // drop bench restrictions that would leave some container no slot at all
        val fitsAll = level.containers.all { v ->
            (0..Limits.DOCK_SLOTS - v.dockSlots).any { s -> (0 until v.dockSlots).all { level.benchAccepts(s + it, v.color) } }
        }
        if (!fitsAll) level = level.copy(benchColors = emptyList())
        return level
    }

    /** [random] restricted to valid-modulo-size levels (Level.validate ignoring board-size limits). */
    fun validRandom(rnd: Random, mechanics: Set<Mechanic> = Mechanic.entries.toSet(), spots: Int = 4 + rnd.nextInt(6)): Level {
        while (true) {
            val l = random(rnd, mechanics, spots = spots)
            val errs = l.validate().filterNot { it.startsWith("width") || it.startsWith("height") || it.startsWith("stagingSpots") }
            if (errs.isEmpty()) return l
        }
    }
}
