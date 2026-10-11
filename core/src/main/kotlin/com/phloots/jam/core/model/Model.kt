package com.phloots.jam.core.model

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
    const val STAGING_SPOTS = 40
    const val MAX_COLORS = 10
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
 */
@Serializable
data class Container(
    val id: Int,
    val size: ContainerSize,
    val color: Int,
    val row: Int,
    val col: Int,
    val dir: Dir,
) {
    /** Occupied cells, front first, then towards the tail. Size == size.length. */
    fun cells(): List<Cell> {
        val back = dir.opposite
        return List(size.length) { i -> Cell(row + back.dRow * i, col + back.dCol * i) }
    }

    /** The tail (last body) cell. */
    fun tail(): Cell = cells().last()
}

/**
 * A puzzle definition. Immutable.
 *
 * - [id]: level number (in the shipped campaign: 1-based campaign index). Opaque to the engine.
 * - [width] in [Limits.MIN_WIDTH]..[Limits.MAX_WIDTH], [height] in [Limits.MIN_HEIGHT]..[Limits.MAX_HEIGHT].
 * - [containers]: <= [Limits.MAX_CONTAINERS], `containers[i].id == i`, all cells in bounds, no overlaps.
 * - [queue]: element colours, FRONT FIRST (index 0 boards first).
 * - Per colour: count of that colour in [queue] == total capacity of that colour's containers.
 */
@Serializable
data class Level(
    val id: Int,
    val width: Int,
    val height: Int,
    val containers: List<Container>,
    val queue: List<Int>,
) {
    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    /** Distinct colours used by containers, ascending. */
    fun colors(): List<Int> = containers.map { it.color }.distinct().sorted()

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
}

/**
 * A container occupying a dock slot. [spaceLeft] in 1..capacity (a full container is dispatched immediately, so it
 * never stays docked). v2: no arrival stamp; boarding priority is the slot POSITION (leftmost first).
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
 *   [Limits.STAGING_SPOTS]. Compacted: when an element boards, the ones after it move one spot left.
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
         * All containers on board, dock empty, staging filled from the queue (first min([Limits.STAGING_SPOTS], queue.size) elements),
         * status PLAYING. No boarding can happen yet (the dock is empty).
         */
        fun initial(level: Level): GameState {
            val n = level.containers.size
            require(n <= Limits.MAX_CONTAINERS) { "at most ${Limits.MAX_CONTAINERS} containers" }
            val staged = minOf(Limits.STAGING_SPOTS, level.queue.size)
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

/** Why a tap had no effect. */
enum class IgnoreReason { NOT_ON_BOARD, DOCK_FULL, GAME_OVER, INVALID_ID }

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
     */
    data class Blocked(val containerId: Int, val stopRow: Int, val stopCol: Int, val blockerId: Int) : Event

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
