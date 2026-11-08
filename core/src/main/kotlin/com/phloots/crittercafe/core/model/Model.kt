package com.phloots.crittercafe.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * FROZEN SHARED API (see docs/ARCHITECTURE.md). Changes must be minimal and recorded there.
 *
 * Coordinate system: (row, col), row 0 is the TOP row, col 0 is the LEFT column.
 * UP = row - 1, DOWN = row + 1, LEFT = col - 1, RIGHT = col + 1.
 */

/** Global limits from DESIGN.md (v2). */
object Limits {
    const val MIN_WIDTH = 10
    const val MAX_WIDTH = 12
    const val MIN_HEIGHT = 12
    const val MAX_HEIGHT = 14
    /** onBoard is a 128-bit mask ([Bits128]), so at most 128 containers per level. */
    const val MAX_CONTAINERS = 128
    const val DOCK_SLOTS = 4
    /** Staging (waiting carousel) size is per level ([Level.stagingSpots]), in [MIN_STAGING_SPOTS]..[MAX_STAGING_SPOTS]. */
    const val MIN_STAGING_SPOTS = 20
    /** Largest staging size, and the default when a level does not set one (every v2 pack so far). */
    const val MAX_STAGING_SPOTS = 40
    const val MAX_COLORS = 10
    /** Queue elements shown on the belt when a level does not set [Level.queuePreview] (the app's RingField value). */
    const val DEFAULT_QUEUE_PREVIEW = 10
    const val MAX_QUEUE_PREVIEW = 40
}

/** Facing / driving direction. [dRow]/[dCol] is the unit step in that direction. */
@Serializable
enum class Dir(val dRow: Int, val dCol: Int) {
    UP(-1, 0), DOWN(1, 0), LEFT(0, -1), RIGHT(0, 1);

    val opposite: Dir
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }

    /** The direction after a 90-degree clockwise turn (UP -> RIGHT -> DOWN -> LEFT -> UP). */
    val clockwise: Dir
        get() = when (this) {
            UP -> RIGHT
            RIGHT -> DOWN
            DOWN -> LEFT
            LEFT -> UP
        }

    /** This direction turned clockwise [times] times (4 turns = identity; negative counts turn anticlockwise). */
    fun turnedClockwise(times: Int): Dir {
        var d = this
        repeat(Math.floorMod(times, 4)) { d = d.clockwise }
        return d
    }
}

/** Container sizes (v2): S = 1 cell / 4, M = 2 cells / 6, L = 3 cells / 10. A length-1 container still faces [Dir]. */
@Serializable
enum class ContainerSize(val length: Int, val capacity: Int) { S(1, 4), M(2, 6), L(3, 10) }

/** A board cell. */
@Serializable
data class Cell(val row: Int, val col: Int)

/**
 * A container on the board.
 *
 * - [id] equals the container's index in [Level.containers] (0 until n, n <= [Limits.MAX_CONTAINERS]).
 * - [color] is a colour index 0 until [Limits.MAX_COLORS]; the UI maps it to palette + shape icon.
 * - ([row], [col]) is the FRONT cell (the cell nearest the exit in direction [dir]).
 *   The body extends BEHIND the front, i.e. in [Dir.opposite] of [dir], for `size.length` cells total.
 * - Driving forward moves the container in [dir]; it exits through the board edge it faces.
 *
 * Mechanics (v4, DESIGN.md "Mechanics (v4)"; all optional, JSON omits them at their defaults):
 * - [frozen] N > 0: can't be released until N containers have been released (counts down on every release).
 * - [tiedTo] p >= 0: tied to container p by a ribbon; this container can't be released while p is on the board.
 *   Directional: p itself is not restricted.
 * - [hidden]: the colour is shown as "?" until the container that occupied the cell directly in front of the front
 *   cell (front + dir) at level start has left the board ([Level.coverOf]). Information only; the rules use [color].
 * - [wide]: needs two ADJACENT free dock slots when released and occupies both (it boards and dispatches as one
 *   container at the left slot's position). Capacity is still `size.capacity`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Container(
    val id: Int,
    val size: ContainerSize,
    val color: Int,
    val row: Int,
    val col: Int,
    val dir: Dir,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val frozen: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val tiedTo: Int = -1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hidden: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val wide: Boolean = false,
) {
    /** Occupied cells, front first, then towards the tail. Size == size.length. */
    fun cells(): List<Cell> {
        val back = dir.opposite
        return List(size.length) { i -> Cell(row + back.dRow * i, col + back.dCol * i) }
    }

    /** The tail (last body) cell. */
    fun tail(): Cell = cells().last()

    /** Dock slots this container occupies when docked: 2 if [wide], else 1. */
    val dockSlots: Int get() = if (wide) 2 else 1
}

/**
 * An exit flap on the board boundary (v4 mechanic "gate"). [side] names the board edge by the direction a container
 * drives to leave through it (UP = top edge, DOWN = bottom, LEFT = left, RIGHT = right); [index] is the column (UP /
 * DOWN) or row (LEFT / RIGHT) of the edge cell it sits at. [open] is its state at level start; it toggles after every
 * release (state-changing tap). A container whose exit path crosses the edge at a closed gate is blocked.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Gate(
    val side: Dir,
    val index: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val open: Boolean = true,
)

/** Level mechanics (v4) with their stable ids (the app keys first-time tutorials on [id]). */
enum class Mechanic(val id: String) {
    FROZEN("frozen"),
    TIED("tied"),
    HIDDEN("hidden"),
    WIDE("wide"),
    COLOUR_BENCH("colour_bench"),
    TURNTABLE("turntable"),
    GATE("gate");

    companion object {
        fun fromId(id: String): Mechanic? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A puzzle definition. Immutable.
 *
 * - [id]: level number (in the shipped campaign: 1-based campaign index). Opaque to the engine.
 * - [width] in [Limits.MIN_WIDTH]..[Limits.MAX_WIDTH], [height] in [Limits.MIN_HEIGHT]..[Limits.MAX_HEIGHT].
 * - [containers]: <= [Limits.MAX_CONTAINERS], `containers[i].id == i`, all cells in bounds, no overlaps.
 * - [queue]: element colours, FRONT FIRST (index 0 boards first).
 * - Per colour: count of that colour in [queue] == total capacity of that colour's containers.
 * - [stagingSpots]: number of staging spots (the real-time carousel's seats), in
 *   [Limits.MIN_STAGING_SPOTS]..[Limits.MAX_STAGING_SPOTS]. JSON field `stagingSpots`, optional: absent means
 *   [Limits.MAX_STAGING_SPOTS] (40), and the default is never written, so packs made before it existed are unchanged.
 *
 * v4 fields (all optional and never written at their defaults, so older packs decode and re-encode unchanged):
 * - [benchColors]: empty (no restriction) or exactly [Limits.DOCK_SLOTS] entries, one per dock slot (left to right):
 *   -1 = any colour, else the only colour that may dock there ("colour-only bench").
 * - [turntables]: board cells that turn the (size-S) container standing on them 90 degrees clockwise after every
 *   release.
 * - [gates]: exit flaps on the board boundary, see [Gate].
 * - [queuePreview]: queue elements the belt shows (view only; the rules ignore it), 1..[Limits.MAX_QUEUE_PREVIEW],
 *   default [Limits.DEFAULT_QUEUE_PREVIEW].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Level(
    val id: Int,
    val width: Int,
    val height: Int,
    val containers: List<Container>,
    val queue: List<Int>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stagingSpots: Int = Limits.MAX_STAGING_SPOTS,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val benchColors: List<Int> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val turntables: List<Cell> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val gates: List<Gate> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val queuePreview: Int = Limits.DEFAULT_QUEUE_PREVIEW,
) {
    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    /** Distinct colours used by containers, ascending. */
    fun colors(): List<Int> = containers.map { it.color }.distinct().sorted()

    /** True iff dock [slot] accepts a container of [color] ([benchColors]). */
    fun benchAccepts(slot: Int, color: Int): Boolean =
        benchColors.size != Limits.DOCK_SLOTS || benchColors[slot] < 0 || benchColors[slot] == color

    /** True iff container [id] stands on a turntable. */
    fun onTurntable(id: Int): Boolean = containers[id].let { c -> Cell(c.row, c.col) in turntables }

    /**
     * The container occupying the cell directly in front of container [id] (front cell + dir) at level start, or -1
     * (board edge or empty cell). A [Container.hidden] container's colour is revealed once this one has left.
     */
    fun coverOf(id: Int): Int {
        val v = containers[id]
        val r = v.row + v.dir.dRow
        val c = v.col + v.dir.dCol
        if (!inBounds(r, c)) return -1
        val cell = Cell(r, c)
        return containers.firstOrNull { it.id != id && cell in it.cells() }?.id ?: -1
    }

    /** Mechanics this level uses, in [Mechanic] order (e.g. for first-time tutorials). Empty for pre-v4 levels. */
    fun mechanics(): List<Mechanic> {
        val out = ArrayList<Mechanic>()
        if (containers.any { it.frozen > 0 }) out += Mechanic.FROZEN
        if (containers.any { it.tiedTo >= 0 }) out += Mechanic.TIED
        if (containers.any { it.hidden }) out += Mechanic.HIDDEN
        if (containers.any { it.wide }) out += Mechanic.WIDE
        if (benchColors.any { it >= 0 }) out += Mechanic.COLOUR_BENCH
        if (turntables.isNotEmpty()) out += Mechanic.TURNTABLE
        if (gates.isNotEmpty()) out += Mechanic.GATE
        return out
    }

    /**
     * Structural validation of the invariants above. Returns human-readable problems; empty == valid.
     * Does NOT check solvability.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (width !in Limits.MIN_WIDTH..Limits.MAX_WIDTH) {
            errors += "width $width out of ${Limits.MIN_WIDTH}..${Limits.MAX_WIDTH}"
        }
        if (height !in Limits.MIN_HEIGHT..Limits.MAX_HEIGHT) {
            errors += "height $height out of ${Limits.MIN_HEIGHT}..${Limits.MAX_HEIGHT}"
        }
        if (containers.size > Limits.MAX_CONTAINERS) errors += "too many containers: ${containers.size}"
        if (stagingSpots !in Limits.MIN_STAGING_SPOTS..Limits.MAX_STAGING_SPOTS) {
            errors += "stagingSpots $stagingSpots out of ${Limits.MIN_STAGING_SPOTS}..${Limits.MAX_STAGING_SPOTS}"
        }
        val occupied = HashMap<Cell, Int>()
        containers.forEachIndexed { i, v ->
            if (v.id != i) errors += "container at index $i has id ${v.id}"
            if (v.color !in 0 until Limits.MAX_COLORS) errors += "container $i colour ${v.color} out of range"
            for (c in v.cells()) {
                if (!inBounds(c.row, c.col)) errors += "container $i cell $c out of bounds"
                val other = occupied.put(c, i)
                if (other != null) errors += "containers $other and $i overlap at $c"
            }
        }
        errors += validateMechanics(occupied)
        val capacity = containers.groupBy { it.color }.mapValues { (_, vs) -> vs.sumOf { it.size.capacity } }
        queue.forEachIndexed { i, c -> if (c !in 0 until Limits.MAX_COLORS) errors += "queue[$i] colour $c out of range" }
        val elements = queue.groupingBy { it }.eachCount()
        for (color in (capacity.keys + elements.keys).toSortedSet()) {
            val s = capacity[color] ?: 0
            val p = elements[color] ?: 0
            if (s != p) errors += "colour $color: $p elements but $s capacity"
        }
        return errors
    }

    /** v4 mechanic checks (DESIGN.md "Mechanics (v4)"). [occupied]: cell -> container id. */
    private fun validateMechanics(occupied: Map<Cell, Int>): List<String> {
        val errors = mutableListOf<String>()
        val n = containers.size
        if (queuePreview !in 1..Limits.MAX_QUEUE_PREVIEW) {
            errors += "queuePreview $queuePreview out of 1..${Limits.MAX_QUEUE_PREVIEW}"
        }
        val benchesOk = benchColors.isEmpty() || benchColors.size == Limits.DOCK_SLOTS
        if (!benchesOk) errors += "benchColors must have ${Limits.DOCK_SLOTS} entries, has ${benchColors.size}"
        benchColors.forEachIndexed { s, c ->
            if (c !in -1 until Limits.MAX_COLORS) errors += "benchColors[$s] colour $c out of range"
        }
        val tiedBy = HashMap<Int, Int>()
        for (v in containers) {
            val i = v.id
            if (v.frozen < 0) errors += "container $i frozen ${v.frozen} < 0"
            if (v.frozen > n - 1) errors += "container $i frozen ${v.frozen} can never thaw (only ${n - 1} others)"
            if (v.tiedTo != -1) {
                val p = v.tiedTo
                when {
                    p !in 0 until n -> errors += "container $i tiedTo $p out of range"
                    p == i -> errors += "container $i is tied to itself"
                    containers[p].tiedTo != -1 -> errors += "container $i is tied to $p, which is tied itself (ties are disjoint pairs)"
                    else -> tiedBy.put(p, i)?.let { errors += "containers $it and $i are both tied to $p (ties are disjoint pairs)" }
                }
            }
            if (v.hidden) {
                if (Cell(v.row, v.col) in turntables) errors += "hidden container $i stands on a turntable"
                else if (coverOf(i) < 0) errors += "hidden container $i has nothing in front of it"
            }
            if (benchesOk) {
                val fits = (0..Limits.DOCK_SLOTS - v.dockSlots).any { s ->
                    (0 until v.dockSlots).all { k -> benchAccepts(s + k, v.color) }
                }
                if (!fits) errors += "container $i (colour ${v.color}${if (v.wide) ", wide" else ""}) fits no dock slot"
            }
        }
        val seenTurn = HashSet<Cell>()
        for (t in turntables) {
            if (!inBounds(t.row, t.col)) errors += "turntable $t out of bounds"
            if (!seenTurn.add(t)) errors += "duplicate turntable $t"
            val o = occupied[t] ?: continue
            if (containers[o].size != ContainerSize.S) errors += "container $o on turntable $t is not size S"
        }
        val seenGate = HashSet<Pair<Dir, Int>>()
        for (g in gates) {
            val len = if (g.side == Dir.UP || g.side == Dir.DOWN) width else height
            if (g.index !in 0 until len) errors += "gate $g index out of 0 until $len"
            if (!seenGate.add(g.side to g.index)) errors += "duplicate gate at ${g.side} ${g.index}"
        }
        return errors
    }
}

/**
 * A container occupying a dock slot. [spaceLeft] in 1..capacity (a full container is dispatched immediately, so it
 * never stays docked). v2: no arrival stamp; boarding priority is the slot POSITION (leftmost first).
 * v4: a [Container.wide] container occupies two adjacent slots s and s + 1; both entries hold the same (equal) value,
 * and the container counts as docked at s (see [GameState.isWideTail]).
 */
data class DockedContainer(val containerId: Int, val color: Int, val spaceLeft: Int)

enum class Status { PLAYING, WON, DEADLOCK, STUCK }

/**
 * Immutable 128-bit set of container ids (bit i = container i). [lo] holds ids 0..63, [hi] ids 64..127.
 */
data class Bits128(val lo: Long, val hi: Long) {
    operator fun get(i: Int): Boolean =
        if (i < 64) (lo ushr i) and 1L == 1L else ((hi ushr (i - 64)) and 1L == 1L)

    fun with(i: Int): Bits128 = if (i < 64) copy(lo = lo or (1L shl i)) else copy(hi = hi or (1L shl (i - 64)))

    fun without(i: Int): Bits128 =
        if (i < 64) copy(lo = lo and (1L shl i).inv()) else copy(hi = hi and (1L shl (i - 64)).inv())

    fun isEmpty(): Boolean = lo == 0L && hi == 0L

    fun count(): Int = java.lang.Long.bitCount(lo) + java.lang.Long.bitCount(hi)

    infix fun and(o: Bits128) = Bits128(lo and o.lo, hi and o.hi)
    infix fun or(o: Bits128) = Bits128(lo or o.lo, hi or o.hi)
    infix fun andNot(o: Bits128) = Bits128(lo and o.lo.inv(), hi and o.hi.inv())

    /** Set ids in ascending order. */
    fun ids(): List<Int> = (0 until 128).filter { get(it) }

    companion object {
        val EMPTY = Bits128(0L, 0L)

        /** Ids 0 until [n] set (n in 0..128). */
        fun firstN(n: Int): Bits128 {
            require(n in 0..128) { "n=$n out of 0..128" }
            fun mask(k: Int) = if (k >= 64) -1L else if (k <= 0) 0L else (1L shl k) - 1L
            return Bits128(mask(n), mask(n - 64))
        }
    }
}

/**
 * Immutable game state (v2). Produced only by [GameState.initial] and the Engine.
 *
 * - [onBoard]: bit `i` set <=> container `i` is still on the board.
 * - [dock]: exactly [Limits.DOCK_SLOTS] entries, POSITIONAL (index 0 = leftmost); null = free.
 *   A released container takes the leftmost free slot. Dispatches free a slot in place (no compaction).
 *   Boarding scans slots left to right.
 * - [staging]: colours of the staged elements in age order (oldest first, index = staging spot), size <=
 *   `level.stagingSpots`. Compacted: when an element boards, the ones after it move one spot left.
 *   Always full unless the queue is exhausted.
 * - [queueIndex]: index into `level.queue` of the next element NOT yet staged; `== queue.size` when every element
 *   has been staged.
 * - [status]: computed by the Engine after each transition (a pure function of the other fields).
 */
data class GameState(
    val level: Level,
    val onBoard: Bits128,
    val dock: List<DockedContainer?>,
    val staging: List<Int>,
    val queueIndex: Int,
    val status: Status = Status.PLAYING,
) {
    fun isOnBoard(containerId: Int): Boolean = onBoard[containerId]

    /** Elements still in the queue (not yet staged). */
    val queueRemaining: Int get() = level.queue.size - queueIndex

    val dockUsed: Int get() = dock.count { it != null }

    /** True iff dock [slot] holds the right half of a wide container docked at `slot - 1`. */
    fun isWideTail(slot: Int): Boolean {
        val dc = dock[slot] ?: return false
        return slot > 0 && dock[slot - 1]?.containerId == dc.containerId
    }

    // ---- v4 mechanic views (derived from onBoard; DESIGN.md "Mechanics (v4)") ----

    /** Releases (state-changing taps) so far: containers no longer on the board. Drives every tap-based mechanic. */
    val releases: Int get() = MechanicsView.releases(level, onBoard)

    /** Releases still needed before frozen container [id] thaws (0 = not frozen / thawed). */
    fun frozenRemaining(id: Int): Int = MechanicsView.frozenRemaining(level, onBoard, id)

    /** True iff [id] is tied to a container that is still on the board. */
    fun isTieLocked(id: Int): Boolean = MechanicsView.isTieLocked(level, onBoard, id)

    /** Current facing of container [id] (a container on a turntable has turned once per release). */
    fun dirOf(id: Int): Dir = MechanicsView.dirOf(level, onBoard, id)

    /** True iff `level.gates[gate]` is open now. */
    fun isGateOpen(gate: Int): Boolean = MechanicsView.isGateOpen(level, onBoard, gate)

    /** True iff the player can see container [id]'s colour (false only for a hidden container not yet revealed). */
    fun isRevealed(id: Int): Boolean = MechanicsView.isRevealed(level, onBoard, id)

    /**
     * Queue indices of the staged elements, parallel to [staging]. Derived: boarding always takes the oldest staged
     * element of a colour, so the staged elements of colour c are the last `count(c in staging)` occurrences of c in
     * `queue[0 until queueIndex]`, in queue order.
     */
    fun stagedElementIndices(): List<Int> {
        val need = IntArray(Limits.MAX_COLORS)
        for (c in staging) need[c]++
        val picked = ArrayList<Int>(staging.size)
        var i = queueIndex - 1
        while (i >= 0 && picked.size < staging.size) {
            val c = level.queue[i]
            if (need[c] > 0) {
                need[c]--
                picked += i
            }
            i--
        }
        picked.reverse()
        return picked
    }

    companion object {
        /**
         * All containers on board, dock empty, staging filled from the queue (first min(`level.stagingSpots`, queue.size) elements),
         * status PLAYING. No boarding can happen yet (the dock is empty).
         */
        fun initial(level: Level): GameState {
            val n = level.containers.size
            require(n <= Limits.MAX_CONTAINERS) { "at most ${Limits.MAX_CONTAINERS} containers" }
            val staged = minOf(level.stagingSpots, level.queue.size)
            return GameState(
                level = level,
                onBoard = Bits128.firstN(n),
                dock = List(Limits.DOCK_SLOTS) { null },
                staging = level.queue.subList(0, staged).toList(),
                queueIndex = staged,
                status = Status.PLAYING,
            )
        }
    }
}

/** The only player action: tap a container. */
data class Move(val containerId: Int)

/**
 * Why a tap had no effect. v4: [FROZEN] (frozen counter not yet 0), [TIED] (tie partner still on the board),
 * [NO_FITTING_SLOT] (a dock slot is free, but none this container may use: colour-only benches, or a wide container
 * without two adjacent free slots).
 */
enum class IgnoreReason { NOT_ON_BOARD, DOCK_FULL, GAME_OVER, INVALID_ID, FROZEN, TIED, NO_FITTING_SLOT }

/**
 * Tap-driven mechanic state as pure functions of (level, onBoard): every v4 mechanic advances only on releases, and
 * the number of releases so far is `n - |onBoard|`, so nothing beyond the v2 state is stored (and the solver's memo
 * key stays lossless). Public entry points: the [GameState] members and `RealTimeState`'s.
 */
object MechanicsView {
    fun releases(level: Level, onBoard: Bits128): Int = level.containers.size - onBoard.count()

    fun frozenRemaining(level: Level, onBoard: Bits128, id: Int): Int =
        maxOf(0, level.containers[id].frozen - releases(level, onBoard))

    fun isTieLocked(level: Level, onBoard: Bits128, id: Int): Boolean {
        val p = level.containers[id].tiedTo
        return p >= 0 && onBoard[p]
    }

    fun dirOf(level: Level, onBoard: Bits128, id: Int): Dir {
        val v = level.containers[id]
        return if (level.turntables.isNotEmpty() && level.onTurntable(id)) v.dir.turnedClockwise(releases(level, onBoard)) else v.dir
    }

    fun isGateOpen(level: Level, onBoard: Bits128, gate: Int): Boolean =
        level.gates[gate].open != (releases(level, onBoard) % 2 == 1)

    fun isRevealed(level: Level, onBoard: Bits128, id: Int): Boolean {
        if (!level.containers[id].hidden || !onBoard[id]) return true
        val cover = level.coverOf(id)
        return cover < 0 || !onBoard[cover]
    }
}

/**
 * Events emitted by one Engine.apply, in chronological order, for UI animation.
 *
 * A successful tap emits [Released], then the boarding loop's events, in order: zero or more
 * [Boarded] (each possibly followed by [Dispatched] when it filled the container) interleaved with [Staged] refills.
 * Staging-spot indices refer to the staging list AT THE MOMENT of the event (it is compacted after each boarding).
 */
sealed interface Event {
    /** Container drove off the board and docked in dock [slot]. */
    data class Released(val containerId: Int, val slot: Int) : Event

    /**
     * Container drove forward until blocked by [blockerId], then returned. No state change.
     * ([stopRow], [stopCol]) is where its FRONT cell is at the moment of the block (the cell just before the blocker).
     * v4: [blockerId] == [GATE] means a closed gate on the board edge; the stop is then the last cell before the edge.
     * The drive follows the container's current facing (`GameState.dirOf`; turntables).
     */
    data class Blocked(val containerId: Int, val stopRow: Int, val stopCol: Int, val blockerId: Int) : Event {
        companion object {
            /** [blockerId] of a block by a closed gate. */
            const val GATE = -1
        }
    }

    /** Element `level.queue[elementIndex]` moved from the queue into staging spot [stagingSpot] (the last spot). */
    data class Staged(val elementIndex: Int, val stagingSpot: Int) : Event

    /**
     * Element `level.queue[elementIndex]`, standing in staging spot [fromStagingSpot], boarded the container
     * [containerId] docked in dock [slot]. Afterwards the staged elements after it shift one spot left.
     */
    data class Boarded(val elementIndex: Int, val fromStagingSpot: Int, val slot: Int, val containerId: Int) : Event

    /** Full container left dock [slot], freeing it. */
    data class Dispatched(val slot: Int, val containerId: Int) : Event

    /** Tap was a no-op. */
    data class Ignored(val containerId: Int, val reason: IgnoreReason) : Event
}

/** Result of applying a move: the new state plus events describing what happened. */
data class Transition(val state: GameState, val events: List<Event>)
