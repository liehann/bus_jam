package com.phloots.busjam.core.model

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
    /** onBoard is a 64-bit mask, so at most 64 vehicles per level. */
    const val MAX_VEHICLES = 64
    const val SLOT_COUNT = 4
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
enum class VehicleType(val length: Int, val seats: Int) { CAR(2, 4), VAN(3, 6), BUS(4, 10) }

/** A board cell. */
@Serializable
data class Cell(val row: Int, val col: Int)

/**
 * A vehicle on the board.
 *
 * - [id] equals the vehicle's index in [Level.vehicles] (0 until n, n <= 64).
 * - [color] is a colour index 0 until [Limits.MAX_COLORS]; the UI maps it to palette + shape icon.
 * - ([row], [col]) is the FRONT cell (the cell nearest the exit in direction [dir]).
 *   The body extends BEHIND the front, i.e. in [Dir.opposite] of [dir], for `type.length` cells total.
 * - Driving forward moves the vehicle in [dir]; it exits through the board edge it faces.
 */
@Serializable
data class Vehicle(
    val id: Int,
    val type: VehicleType,
    val color: Int,
    val row: Int,
    val col: Int,
    val dir: Dir,
) {
    /** Occupied cells, front first, then towards the tail. Size == type.length. */
    fun cells(): List<Cell> {
        val back = dir.opposite
        return List(type.length) { i -> Cell(row + back.dRow * i, col + back.dCol * i) }
    }

    /** The tail (last body) cell. */
    fun tail(): Cell = cells().last()
}

/**
 * A puzzle definition. Immutable.
 *
 * - [id]: level number (in the shipped campaign: 1-based campaign index). Opaque to the engine.
 * - [width] <= 10, [height] <= 12.
 * - [vehicles]: <= 64, `vehicles[i].id == i`, all cells in bounds, no overlaps.
 * - [queue]: passenger colours, FRONT FIRST (index 0 boards first).
 * - Per colour: count of that colour in [queue] == total seats of that colour's vehicles.
 */
@Serializable
data class Level(
    val id: Int,
    val width: Int,
    val height: Int,
    val vehicles: List<Vehicle>,
    val queue: List<Int>,
) {
    fun inBounds(row: Int, col: Int): Boolean = row in 0 until height && col in 0 until width

    /** Distinct colours used by vehicles, ascending. */
    fun colors(): List<Int> = vehicles.map { it.color }.distinct().sorted()

    /**
     * Structural validation of the invariants above. Returns human-readable problems; empty == valid.
     * Does NOT check solvability.
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (width !in 1..Limits.MAX_WIDTH) errors += "width $width out of 1..${Limits.MAX_WIDTH}"
        if (height !in 1..Limits.MAX_HEIGHT) errors += "height $height out of 1..${Limits.MAX_HEIGHT}"
        if (vehicles.size > Limits.MAX_VEHICLES) errors += "too many vehicles: ${vehicles.size}"
        val occupied = HashMap<Cell, Int>()
        vehicles.forEachIndexed { i, v ->
            if (v.id != i) errors += "vehicle at index $i has id ${v.id}"
            if (v.color !in 0 until Limits.MAX_COLORS) errors += "vehicle $i colour ${v.color} out of range"
            for (c in v.cells()) {
                if (!inBounds(c.row, c.col)) errors += "vehicle $i cell $c out of bounds"
                val other = occupied.put(c, i)
                if (other != null) errors += "vehicles $other and $i overlap at $c"
            }
        }
        val seats = vehicles.groupBy { it.color }.mapValues { (_, vs) -> vs.sumOf { it.type.seats } }
        val passengers = queue.groupingBy { it }.eachCount()
        for (color in (seats.keys + passengers.keys).toSortedSet()) {
            val s = seats[color] ?: 0
            val p = passengers[color] ?: 0
            if (s != p) errors += "colour $color: $p passengers but $s seats"
        }
        return errors
    }
}

/**
 * A vehicle occupying a parking slot.
 * [arrival] is a monotonically increasing stamp ([GameState.arrivalCounter] at the time of parking);
 * lower == arrived earlier. Boarding picks the matching vehicle with the lowest [arrival].
 */
data class ParkedVehicle(val vehicleId: Int, val color: Int, val seatsLeft: Int, val arrival: Int)

enum class Status { PLAYING, WON, DEADLOCK, STUCK }

/**
 * Immutable game state. Produced only by [GameState.initial] and the Engine.
 *
 * - [onBoard]: bit `i` set <=> vehicle `i` is still on the board.
 * - [slots]: exactly [Limits.SLOT_COUNT] entries, POSITIONAL (index 0 = leftmost); null = free.
 *   An exiting vehicle takes the leftmost free slot. Departures free a slot in place (no compaction).
 * - [queueIndex]: index into `level.queue` of the current front passenger; `== queue.size` when the queue is empty.
 * - [arrivalCounter]: next arrival stamp to assign.
 * - [status]: computed by the Engine after each transition (a pure function of the other fields).
 */
data class GameState(
    val level: Level,
    val onBoard: Long,
    val slots: List<ParkedVehicle?>,
    val queueIndex: Int,
    val arrivalCounter: Int,
    val status: Status = Status.PLAYING,
) {
    fun isOnBoard(vehicleId: Int): Boolean = (onBoard ushr vehicleId) and 1L == 1L

    /** Colour of the front passenger, or null if the queue is empty. */
    val frontPassenger: Int? get() = level.queue.getOrNull(queueIndex)

    val queueRemaining: Int get() = level.queue.size - queueIndex

    val slotsUsed: Int get() = slots.count { it != null }

    companion object {
        /** All vehicles on board, slots empty, queue at front, status PLAYING. */
        fun initial(level: Level): GameState {
            val n = level.vehicles.size
            require(n <= Limits.MAX_VEHICLES) { "at most ${Limits.MAX_VEHICLES} vehicles" }
            val mask = if (n == 64) -1L else (1L shl n) - 1L
            return GameState(
                level = level,
                onBoard = mask,
                slots = List(Limits.SLOT_COUNT) { null },
                queueIndex = 0,
                arrivalCounter = 0,
                status = Status.PLAYING,
            )
        }
    }
}

/** The only player action: tap a vehicle. */
data class Move(val vehicleId: Int)

/** Why a tap had no effect. */
enum class IgnoreReason { NOT_ON_BOARD, SLOTS_FULL, GAME_OVER, INVALID_ID }

/** Events emitted by one Engine.apply, in chronological order, for UI animation. */
sealed interface Event {
    /** Vehicle drove off the board and parked in [slot]. */
    data class Exited(val vehicleId: Int, val slot: Int) : Event

    /**
     * Vehicle drove forward until blocked by [blockerId], then returned. No state change.
     * ([stopRow], [stopCol]) is where its FRONT cell is at the moment of the bump (the cell just before the blocker).
     */
    data class Bumped(val vehicleId: Int, val stopRow: Int, val stopCol: Int, val blockerId: Int) : Event

    /** Passenger at `level.queue[passengerIndex]` boarded the vehicle parked in [slot]. */
    data class Boarded(val passengerIndex: Int, val slot: Int, val vehicleId: Int) : Event

    /** Full vehicle left [slot], freeing it. */
    data class Departed(val slot: Int, val vehicleId: Int) : Event

    /** Tap was a no-op. */
    data class Ignored(val vehicleId: Int, val reason: IgnoreReason) : Event
}

/** Result of applying a move: the new state plus events describing what happened. */
data class Transition(val state: GameState, val events: List<Event>)
