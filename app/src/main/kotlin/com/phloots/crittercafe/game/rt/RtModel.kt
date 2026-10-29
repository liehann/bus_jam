package com.phloots.crittercafe.game.rt

import com.phloots.crittercafe.core.model.Bits128
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.IgnoreReason
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.MechanicsView
import com.phloots.crittercafe.core.model.Status

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

    // ---- v4 mechanic views: pure functions of (level, onBoard), delegated to core's MechanicsView ----

    private val board: Bits128 by lazy { onBoard.fold(Bits128.EMPTY) { b, id -> b.with(id) } }

    /** Releases so far (containers no longer on the board); every mechanic advances on these. */
    val releases: Int get() = level.containers.size - onBoard.size

    /** Releases still needed before frozen container [id] thaws (0 = not frozen or thawed). */
    fun frozenRemaining(id: Int): Int = MechanicsView.frozenRemaining(level, board, id)

    /** True iff [id] waits for its tie partner, which is still on the board. */
    fun isTieLocked(id: Int): Boolean = MechanicsView.isTieLocked(level, board, id)

    /** Current facing of [id] (a container on a turntable has turned once per release). */
    fun dirOf(id: Int): Dir = MechanicsView.dirOf(level, board, id)

    /** True iff `level.gates[gate]` is open now. */
    fun isGateOpen(gate: Int): Boolean = MechanicsView.isGateOpen(level, board, gate)

    /** True iff the player can see [id]'s colour (false only for a hidden container whose cover is still there). */
    fun isRevealed(id: Int): Boolean = MechanicsView.isRevealed(level, board, id)

    /** True iff dock [slot] holds the right half of a wide container whose left half is at `slot - 1`. */
    fun isWideTail(slot: Int): Boolean {
        val s = slots[slot] ?: return false
        return slot > 0 && slots[slot - 1]?.containerId == s.containerId
    }

    /** Dock slots the container docked at [slot] spans: 2 for the left half of a wide one, else 1. */
    fun slotSpan(slot: Int): Int = if (slot + 1 < slots.size && isWideTail(slot + 1)) 2 else 1
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
