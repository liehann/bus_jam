package com.phloots.busjam.game.rt

import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Status

/**
 * A container in a dock slot, with the tick timestamps of its life cycle (all absolute ticks; -1 = not yet known).
 * [seated] = elements already inside (walkers still on their way are not counted).
 */
data class RtSlotView(
    val containerId: Int,
    val color: Int,
    val capacity: Int,
    val seated: Int,
    val releasedAt: Long,
    val dockedAt: Long,
    val dispatchAt: Long = -1,
    val freeAt: Long = -1,
)

/** An element walking from the gate window (track position [fromPosition]) into the container in dock [slot]. */
data class RtWalkerView(val color: Int, val slot: Int, val startTick: Long, val arriveTick: Long, val fromPosition: Float)

/**
 * Everything the real-time screen draws at one tick. Pure data mapped from the core real-time state by a
 * [RealTimeRules] adapter (no rules here). Motion between ticks is interpolated from the timestamps.
 *
 * Carousel: physical seat `i` sits at track position `(i + rotation) mod seats.size` (seat units, increasing in the
 * direction of travel = clockwise on screen). [gate] (the CENTRE of the boarding window, [gateWidth] seats wide) and
 * [entry] are fixed track positions; the screen puts the gate at the bottom centre, next to the dock.
 */
data class RtView(
    val level: Level,
    val tick: Long,
    val onBoard: Set<Int>,
    val slots: List<RtSlotView?>,
    val walkers: List<RtWalkerView>,
    /** Element colour per physical seat, null = empty. */
    val seats: List<Int?>,
    /** Ring rotation at [tick], in seats (continuous: includes the sub-seat fraction). */
    val rotation: Float,
    /** Rotation added per tick, in seats. */
    val rotationPerTick: Float,
    val gate: Float,
    /** Seats covered by the boarding window, centred on [gate]. */
    val gateWidth: Int,
    val entry: Float,
    /** Next queue element not yet on the ring. */
    val queueIndex: Int,
    /** Nothing left to board, walk, dispatch or step on. */
    val settled: Boolean,
    /** PLAYING until settled; then the turn-based equivalent. */
    val status: Status,
) {
    val queueRemaining: Int get() = level.queue.size - queueIndex
}

/** The few event kinds the UI reacts to (everything else is visible in [RtView]). */
sealed interface RtEvent {
    data class Blocked(val containerId: Int, val stopRow: Int, val stopCol: Int, val blockerId: Int) : RtEvent
    data class Ignored(val containerId: Int, val reason: IgnoreReason) : RtEvent
    data class Released(val containerId: Int, val slot: Int) : RtEvent
    /** An element left its seat at the gate towards a container (haptic tick). */
    data object BoardingStarted : RtEvent
    data object Other : RtEvent
}

data class RtStep<S>(val state: S, val events: List<RtEvent>)

/** The real-time rules as the UI needs them; [CoreRealTimeRules] implements them over core's `realtime` package. */
interface RealTimeRules<S> {
    /** Wall-clock length of one tick. */
    val tickMs: Double
    fun initial(level: Level): S
    fun tick(state: S): RtStep<S>
    fun tap(state: S, containerId: Int): RtStep<S>
    fun view(state: S): RtView

    /** The equivalent turn-based state when the carousel is settled, else null (hints). */
    fun settled(state: S): GameState?
}
