package com.phloots.jam.core.model

import kotlinx.serialization.Serializable

/*
 * FROZEN SHARED API (see docs/ARCHITECTURE.md). Changes must be minimal and recorded there.
 *
 * Coordinate system: (row, col), row 0 is the TOP row, col 0 is the LEFT column.
 * UP = row - 1, DOWN = row + 1, LEFT = col - 1, RIGHT = col + 1.
 */

/** Global limits from DESIGN.md. */
object Limits {
    const val MAX_WIDTH = 10
    const val MAX_HEIGHT = 12
    /** onBoard is a 64-bit mask, so at most 64 containers per level. */
    const val MAX_CONTAINERS = 64
    const val DOCK_SLOTS = 4
    const val MAX_COLORS = 8
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

@Serializable
enum class ContainerSize(val length: Int, val capacity: Int) { S(2, 4), M(3, 6), L(4, 10) }

/** A board cell. */
@Serializable
data class Cell(val row: Int, val col: Int)

/**
 * A container on the board.
 *
 * - [id] equals the container's index in [Level.containers] (0 until n, n <= 64).
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
 * - [width] <= 10, [height] <= 12.
 * - [containers]: <= 64, `containers[i].id == i`, all cells in bounds, no overlaps.
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
        if (width !in 1..Limits.MAX_WIDTH) errors += "width $width out of 1..${Limits.MAX_WIDTH}"
        if (height !in 1..Limits.MAX_HEIGHT) errors += "height $height out of 1..${Limits.MAX_HEIGHT}"
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
 * A container occupying a dock slot.
 * [arrival] is a monotonically increasing stamp ([GameState.arrivalCounter] at the time of dock);
 * lower == arrived earlier. Boarding picks the matching container with the lowest [arrival].
 */
data class DockedContainer(val containerId: Int, val color: Int, val spaceLeft: Int, val arrival: Int)

enum class Status { PLAYING, WON, DEADLOCK, STUCK }

/**
 * Immutable game state. Produced only by [GameState.initial] and the Engine.
 *
 * - [onBoard]: bit `i` set <=> container `i` is still on the board.
 * - [dock]: exactly [Limits.DOCK_SLOTS] entries, POSITIONAL (index 0 = leftmost); null = free.
 *   An exiting container takes the leftmost free slot. Dispatches free a slot in place (no compaction).
 * - [queueIndex]: index into `level.queue` of the current front element; `== queue.size` when the queue is empty.
 * - [arrivalCounter]: next arrival stamp to assign.
 * - [status]: computed by the Engine after each transition (a pure function of the other fields).
 */
data class GameState(
    val level: Level,
    val onBoard: Long,
    val dock: List<DockedContainer?>,
    val queueIndex: Int,
    val arrivalCounter: Int,
    val status: Status = Status.PLAYING,
) {
    fun isOnBoard(containerId: Int): Boolean = (onBoard ushr containerId) and 1L == 1L

    /** Colour of the front element, or null if the queue is empty. */
    val frontElement: Int? get() = level.queue.getOrNull(queueIndex)

    val queueRemaining: Int get() = level.queue.size - queueIndex

    val dockUsed: Int get() = dock.count { it != null }

    companion object {
        /** All containers on board, slots empty, queue at front, status PLAYING. */
        fun initial(level: Level): GameState {
            val n = level.containers.size
            require(n <= Limits.MAX_CONTAINERS) { "at most ${Limits.MAX_CONTAINERS} containers" }
            val mask = if (n == 64) -1L else (1L shl n) - 1L
            return GameState(
                level = level,
                onBoard = mask,
                dock = List(Limits.DOCK_SLOTS) { null },
                queueIndex = 0,
                arrivalCounter = 0,
                status = Status.PLAYING,
            )
        }
    }
}

/** The only player action: tap a container. */
data class Move(val containerId: Int)

/** Why a tap had no effect. */
enum class IgnoreReason { NOT_ON_BOARD, DOCK_FULL, GAME_OVER, INVALID_ID }

/** Events emitted by one Engine.apply, in chronological order, for UI animation. */
sealed interface Event {
    /** Container drove off the board and docked in [slot]. */
    data class Released(val containerId: Int, val slot: Int) : Event

    /**
     * Container drove forward until blocked by [blockerId], then returned. No state change.
     * ([stopRow], [stopCol]) is where its FRONT cell is at the moment of the block (the cell just before the blocker).
     */
    data class Blocked(val containerId: Int, val stopRow: Int, val stopCol: Int, val blockerId: Int) : Event

    /** Element at `level.queue[elementIndex]` boarded the container docked in [slot]. */
    data class Boarded(val elementIndex: Int, val slot: Int, val containerId: Int) : Event

    /** Full container left [slot], freeing it. */
    data class Dispatched(val slot: Int, val containerId: Int) : Event

    /** Tap was a no-op. */
    data class Ignored(val containerId: Int, val reason: IgnoreReason) : Event
}

/** Result of applying a move: the new state plus events describing what happened. */
data class Transition(val state: GameState, val events: List<Event>)
