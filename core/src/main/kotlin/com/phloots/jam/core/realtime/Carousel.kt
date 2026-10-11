package com.phloots.jam.core.realtime

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.engine.LevelData
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Status

/*
 * Deterministic real-time layer (DESIGN.md "Real-time carousel (v3)"). Owner: gamestate agent.
 *
 * The waiting area is a ring of N = [Level.stagingSpots] seats (20..40, per level). Seats are fixed to the ring and the ring turns:
 * seat `s` stands at ring POSITION `(s + phase) mod N`; positions are fixed places on screen, numbered in the
 * direction of travel. Every [RtConfig.ticksPerSeat] ticks the phase advances by one (each seat moves to the next
 * position). Two places are special:
 *  - the GATE WINDOW: [RtConfig.gateWidth] consecutive positions starting at [RtConfig.gatePosition] (default 0..7;
 *    elements enter it at gatePosition and leave it after gatePosition + gateWidth - 1). Whenever the stagger allows
 *    (at most one boarding per [RtConfig.boardStaggerTicks] ticks), the most DOWNSTREAM element in the window whose
 *    colour has a docked (arrived) container with unreserved space leaves its seat and walks to the LEFTMOST such
 *    container. A burst of matching elements therefore boards as a wave rolling down the window, front first;
 *  - the ENTRY ([RtConfig.entryPosition], default N/2 (20 for 40 seats, 10 for 20), outside the window): when a seat arrives there empty
 *    and the queue is not exhausted, the next queue element steps on.
 * The gate is checked on every tick (after the rotation on a seat-step tick); the entry only on seat steps, after the
 * gate. A level starts with an EMPTY ring: every element waits in the queue line and the ring fills through the entry
 * (one element per seat step, ~4 s for 40 seats), during which the player may already tap.
 *
 * Patient play converges to Engine.apply's settled state (see RealTimeTest): boarding fills same-colour containers
 * left to right (space is reserved at the gate, so a container stops being a target the moment its last space is
 * taken), and the final per-colour boarded counts and queue index are a unique fixpoint independent of timing.
 */

/** Timing configuration. All durations are in ticks and must be >= 1. */
data class RtConfig(
    /** Ticks per one-seat rotation step. At 25 ms per tick that is 0.2 s per seat (~8 s per rotation at 40). */
    val ticksPerSeat: Int = 8,
    /** Container drive from the board into its dock slot; it can't receive elements before it arrives. */
    val releaseTicks: Int = 12,
    /** Element walk from the gate into its container. */
    val boardTicks: Int = 8,
    /** Full container leaving the dock; its slot stays occupied until the drive completes. */
    val dispatchTicks: Int = 16,
    /** Ring position of the boarding window's upstream end (where elements enter the window). */
    val gatePosition: Int = 0,
    /** Number of consecutive ring positions in the boarding window. */
    val gateWidth: Int = 8,
    /** Minimum ticks between two boarding starts (the "wave" spacing; 3 ticks = 75 ms). */
    val boardStaggerTicks: Int = 3,
    /**
     * Ring position where queue elements step onto empty seats; [ENTRY_HALF_RING] (the default) = half the ring
     * (N / 2), resolved per level by [entryFor].
     */
    val entryPosition: Int = ENTRY_HALF_RING,
) {
    init {
        require(ticksPerSeat >= 1 && releaseTicks >= 1 && boardTicks >= 1 && dispatchTicks >= 1) { "durations must be >= 1" }
        require(gatePosition >= 0 && (entryPosition >= 0 || entryPosition == ENTRY_HALF_RING)) { "negative position" }
        require(gateWidth >= 1) { "gateWidth out of range" }
        require(boardStaggerTicks >= 1) { "boardStaggerTicks must be >= 1" }
    }

    /** The entry position on a ring of [seats] seats. */
    fun entryFor(seats: Int): Int = if (entryPosition == ENTRY_HALF_RING) seats / 2 else entryPosition

    /** Checks the gate window and entry fit a ring of [seats] seats (any size 20..40 with the defaults). */
    fun validateFor(seats: Int) {
        val entry = entryFor(seats)
        require(gatePosition in 0 until seats && entry in 0 until seats) { "gate/entry outside a ring of $seats" }
        require(gateWidth in 1..seats / 2) { "gateWidth out of range for a ring of $seats" }
        require(Math.floorMod(entry - gatePosition, seats) >= gateWidth) { "entry inside the gate window" }
    }

    /** Seconds per tick when one seat step takes [seatSeconds]. */
    fun secondsPerTick(seatSeconds: Double = 0.2): Double = seatSeconds / ticksPerSeat

    companion object {
        /** [entryPosition] value meaning "half the ring". */
        const val ENTRY_HALF_RING = -1
        val DEFAULT = RtConfig()
    }
}

/** Life cycle of a dock slot's container, as a function of the current tick. */
enum class SlotPhase {
    /** Driving from the board to the slot (tick < dockedAt). */
    ARRIVING,
    /** Docked and accepting elements (spaceLeft > 0). */
    DOCKED,
    /** All space reserved; waiting for the last element to walk in (fullAt <= tick < dispatchAt). */
    FILLING,
    /** Driving away (dispatchAt <= tick < freeAt); the slot is still occupied. */
    DISPATCHING,
}

/**
 * A container occupying a dock slot. [spaceLeft] = UNRESERVED space (an element reserves a space when it leaves its
 * seat at the gate). Timestamps are absolute ticks; [fullAt]/[dispatchAt]/[freeAt] are -1 until the last space is
 * reserved, then `dispatchAt = fullAt + boardTicks`, `freeAt = dispatchAt + dispatchTicks` (slot becomes null).
 */
data class RtSlot(
    val containerId: Int,
    val color: Int,
    val capacity: Int,
    val spaceLeft: Int,
    val releasedAt: Long,
    val dockedAt: Long,
    val fullAt: Long = -1,
    val dispatchAt: Long = -1,
    val freeAt: Long = -1,
) {
    fun phase(tick: Long): SlotPhase = when {
        tick < dockedAt -> SlotPhase.ARRIVING
        fullAt < 0 -> SlotPhase.DOCKED
        tick < dispatchAt -> SlotPhase.FILLING
        else -> SlotPhase.DISPATCHING
    }

    /** True iff an element of [color] reaching the gate at [tick] may board this container. */
    fun accepts(tick: Long): Boolean = spaceLeft > 0 && tick >= dockedAt
}

/** An element walking from the gate into a container (UI only; its space is already reserved). */
data class RtWalker(
    val elementIndex: Int,
    val color: Int,
    /** Seat it left (now empty). */
    val seat: Int,
    /** Ring position of that seat when it left (inside the gate window). */
    val position: Int,
    val slot: Int,
    val containerId: Int,
    val startTick: Long,
    val arriveTick: Long,
)

/** Events for animation/audio. Emitted in chronological order within a tick. */
sealed interface RtEvent {
    /** Tap accepted: container left the board towards [slot]; it docks at [dockedAt]. */
    data class Released(val containerId: Int, val slot: Int, val dockedAt: Long) : RtEvent

    /** Same meaning as Engine's Event.Blocked. No state change. */
    data class Blocked(val containerId: Int, val stopRow: Int, val stopCol: Int, val blockerId: Int) : RtEvent

    /** Same reasons as Engine (GAME_OVER / INVALID_ID / NOT_ON_BOARD / DOCK_FULL, checked in that order). */
    data class Ignored(val containerId: Int, val reason: IgnoreReason) : RtEvent

    /** Released container reached its slot and starts accepting elements. */
    data class Docked(val containerId: Int, val slot: Int) : RtEvent

    /** Element `queue[elementIndex]` left [seat] in the gate window and starts walking to [containerId] in [slot]. */
    data class BoardingStarted(val elementIndex: Int, val seat: Int, val slot: Int, val containerId: Int) : RtEvent

    /** Element finished walking into the container. */
    data class Boarded(val elementIndex: Int, val slot: Int, val containerId: Int) : RtEvent

    /** Full container starts driving away; [slot] stays occupied until [Dispatched]. */
    data class DispatchStarted(val slot: Int, val containerId: Int) : RtEvent

    /** Dispatch drive finished; [slot] is free. */
    data class Dispatched(val slot: Int, val containerId: Int) : RtEvent

    /** Element `queue[elementIndex]` stepped from the queue onto empty [seat] at the entry. */
    data class SteppedOn(val elementIndex: Int, val seat: Int) : RtEvent

    /** The state became settled (see [RealTime.isSettled]); [status] is its (Engine-equivalent) status. */
    data class Settled(val status: Status) : RtEvent
}

/**
 * Immutable real-time game state. Every operation returns a new value; the UI keeps the value it had just before
 * each tap as the undo snapshot (it includes ring phase, tick, in-flight items), so undo restores the carousel exactly.
 *
 * [status] is PLAYING while not [settled]; once settled it equals `Engine.computeStatus` of [toGameState].
 */
class RealTimeState internal constructor(
    val level: Level,
    val config: RtConfig,
    /** Ticks elapsed since the level started. */
    val tick: Long,
    /** Ring phase 0 until N: seat s is at position (s + phase) mod N. */
    val phase: Int,
    /** Seat -> queue index of the element sitting there, or -1. Never mutated after construction. */
    private val seats: IntArray,
    /** Number of occupied seats. */
    val ringCount: Int,
    val onBoard: Bits128,
    /** Exactly [Limits.DOCK_SLOTS] entries, positional (0 = leftmost); null = free. */
    val dock: List<RtSlot?>,
    /** Next queue element not yet on the ring. */
    val queueIndex: Int,
    /** Elements walking from the gate to a container. */
    val walkers: List<RtWalker>,
    /** Earliest tick at which the next boarding may start (stagger). */
    val nextBoardTick: Long,
    val settled: Boolean,
    val status: Status,
    /**
     * Tick of the most recent step-on from the queue, or -1 (presentation only: lets the UI glide the plate from the
     * queue line onto its seat; not part of [equals] / [hashCode]).
     */
    val lastEntryTick: Long = -1L,
) {
    val seatCount: Int get() = seats.size

    /** Ring position of the entry on this ring ([RtConfig.entryFor]). */
    val entryPosition: Int get() = config.entryFor(seats.size)

    /** Queue index of the element on [seat], or -1 if empty. */
    fun seatElement(seat: Int): Int = seats[seat]

    /** Colour of the element on [seat], or -1 if empty. */
    fun seatColor(seat: Int): Int = seats[seat].let { if (it < 0) -1 else level.queue[it] }

    /** Ring position of [seat] (0 until N, direction of travel). */
    fun positionOf(seat: Int): Int = (seat + phase) % seats.size

    /** Seat currently standing at ring [position]. */
    fun seatAt(position: Int): Int = Math.floorMod(position - phase, seats.size)

    /** Fraction 0 <= f < 1 of the way from the current position to the next (for smooth rendering). */
    val subSeatFraction: Float get() = (tick % config.ticksPerSeat).toFloat() / config.ticksPerSeat

    /** Elements still in the queue line (not yet on the ring). */
    val queueRemaining: Int get() = level.queue.size - queueIndex

    val dockUsed: Int get() = dock.count { it != null }

    /** Colours of the ring elements ordered by queue index (oldest first). */
    fun ringColors(): List<Int> = seats.filter { it >= 0 }.sorted().map { level.queue[it] }

    /**
     * The equivalent Engine state. Requires [settled] (no in-flight items, so every docked container has
     * spaceLeft >= 1). Staging = ring colours ordered by queue index; the multiset (all that the solver uses) equals
     * Engine's, element identities may differ (the ring boards whichever matching element reaches the gate first).
     */
    fun toGameState(): GameState {
        check(settled) { "toGameState requires a settled state" }
        return GameState(
            level, onBoard,
            dock.map { s -> s?.let { DockedContainer(it.containerId, it.color, it.spaceLeft) } },
            ringColors(), queueIndex, status,
        )
    }

    internal fun seatsRef(): IntArray = seats

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RealTimeState) return false
        return level == other.level && config == other.config && tick == other.tick && phase == other.phase &&
            seats.contentEquals(other.seats) && onBoard == other.onBoard && dock == other.dock &&
            queueIndex == other.queueIndex && walkers == other.walkers && nextBoardTick == other.nextBoardTick &&
            settled == other.settled &&
            status == other.status
    }

    override fun hashCode(): Int {
        var h = tick.hashCode()
        h = 31 * h + phase
        h = 31 * h + seats.contentHashCode()
        h = 31 * h + onBoard.hashCode()
        h = 31 * h + dock.hashCode()
        h = 31 * h + queueIndex
        h = 31 * h + walkers.hashCode()
        h = 31 * h + nextBoardTick.hashCode()
        return h
    }

    override fun toString(): String =
        "RealTimeState(tick=$tick, phase=$phase, ring=$ringCount, dock=$dock, qi=$queueIndex, " +
            "walkers=${walkers.size}, settled=$settled, status=$status)"

    internal fun copy(
        tick: Long = this.tick,
        phase: Int = this.phase,
        seats: IntArray = this.seats,
        ringCount: Int = this.ringCount,
        onBoard: Bits128 = this.onBoard,
        dock: List<RtSlot?> = this.dock,
        queueIndex: Int = this.queueIndex,
        walkers: List<RtWalker> = this.walkers,
        nextBoardTick: Long = this.nextBoardTick,
        settled: Boolean = this.settled,
        status: Status = this.status,
        lastEntryTick: Long = this.lastEntryTick,
    ) = RealTimeState(
        level, config, tick, phase, seats, ringCount, onBoard, dock, queueIndex, walkers, nextBoardTick, settled, status,
        lastEntryTick,
    )
}

/** Real-time rules. Pure functions over [RealTimeState]. */
object RealTime {
    /**
     * Start of a level: tick 0, phase 0, dock empty, EMPTY ring, every element in the queue line (queueIndex 0).
     * The ring fills through the entry, one element per seat step, so element k steps on at seat step k+1 and the
     * oldest reaches the gate window first. Not settled until the ring is full (or the queue is exhausted); patient
     * play from then on matches Engine from GameState.initial (first N staged). Taps during the fill are legal.
     */
    fun initial(level: Level, config: RtConfig = RtConfig.DEFAULT): RealTimeState {
        val n = level.stagingSpots
        config.validateFor(n)
        val s = RealTimeState(
            level, config, 0L, 0, IntArray(n) { -1 }, 0, GameState.initial(level).onBoard,
            List(Limits.DOCK_SLOTS) { null }, 0, emptyList(), 0L, settled = false, status = Status.PLAYING,
        )
        return if (isSettled(s)) s.copy(settled = true, status = settledStatus(s)) else s
    }

    /**
     * Builds a settled real-time state from a settled Engine state (e.g. a restored save): tick 0, phase 0, staged
     * elements (by [GameState.stagedElementIndices], oldest first) pre-placed as a line just upstream of the gate
     * window (element k at position `gate - 1 - k`); docked containers already arrived.
     */
    fun fromGameState(state: GameState, config: RtConfig = RtConfig.DEFAULT): RealTimeState {
        val n = state.level.stagingSpots
        config.validateFor(n)
        val seats = IntArray(n) { -1 }
        val idx = state.stagedElementIndices()
        for ((k, qi) in idx.withIndex()) seats[Math.floorMod(config.gatePosition - 1 - k, n)] = qi
        val dock = state.dock.map { dc ->
            dc?.let { RtSlot(it.containerId, it.color, state.level.containers[it.containerId].size.capacity, it.spaceLeft, -config.releaseTicks.toLong(), 0L) }
        }
        val s = RealTimeState(
            state.level, config, 0L, 0, seats, idx.size, state.onBoard, dock, state.queueIndex, emptyList(), 0L,
            settled = false, status = Status.PLAYING,
        )
        val settled = isSettled(s)
        return s.copy(settled = settled, status = if (settled) state.status else Status.PLAYING)
    }

    /**
     * Settled: nothing will change without a tap — no container arriving/filling/dispatching, no walker, the ring is
     * full or the queue is exhausted (no refill possible), and no ring element matches a docked container with
     * space (no boarding possible in a full rotation). A settled state corresponds 1:1 to an Engine state.
     */
    fun isSettled(s: RealTimeState): Boolean {
        if (s.walkers.isNotEmpty()) return false
        var colorMask = 0
        for (slot in s.dock) {
            if (slot == null) continue
            if (s.tick < slot.dockedAt || slot.fullAt >= 0) return false
            colorMask = colorMask or (1 shl slot.color)
        }
        if (s.ringCount < s.seatCount && s.queueIndex < s.level.queue.size) return false
        if (colorMask == 0) return true
        val seats = s.seatsRef()
        val q = s.level.queue
        for (e in seats) if (e >= 0 && (colorMask ushr q[e]) and 1 != 0) return false
        return true
    }

    /**
     * A tap at the current tick. Same rules and check order as Engine.apply: GAME_OVER (status != PLAYING — only
     * possible when settled), INVALID_ID, NOT_ON_BOARD, DOCK_FULL, then Blocked. DOCK_FULL counts EVERY occupied
     * slot, including containers still arriving, waiting for their last walker, or mid-dispatch (a slot frees only
     * when the dispatch drive ends). Otherwise the container leaves the board at once and reserves the leftmost free
     * slot; it accepts elements from `tick + releaseTicks`.
     */
    fun tap(state: RealTimeState, containerId: Int): Pair<RealTimeState, List<RtEvent>> {
        val id = containerId
        val level = state.level
        if (state.status != Status.PLAYING) return state to listOf(RtEvent.Ignored(id, IgnoreReason.GAME_OVER))
        if (id !in level.containers.indices) return state to listOf(RtEvent.Ignored(id, IgnoreReason.INVALID_ID))
        if (!state.onBoard[id]) return state to listOf(RtEvent.Ignored(id, IgnoreReason.NOT_ON_BOARD))
        val slot = state.dock.indexOfFirst { it == null }
        if (slot < 0) return state to listOf(RtEvent.Ignored(id, IgnoreReason.DOCK_FULL))
        val d = LevelData.of(level)
        if (!d.rayClear(id, state.onBoard.lo, state.onBoard.hi)) {
            val v = level.containers[id]
            for ((k, cell) in d.rayCells[id].withIndex()) {
                val o = d.occupant[cell]
                if (o >= 0 && o != id && state.onBoard[o]) {
                    return state to listOf(RtEvent.Blocked(id, v.row + v.dir.dRow * k, v.col + v.dir.dCol * k, o))
                }
            }
            error("ray mask says blocked but no blocker found")
        }
        val v = level.containers[id]
        val docked = state.tick + state.config.releaseTicks
        val dock = state.dock.toMutableList()
        dock[slot] = RtSlot(id, v.color, v.size.capacity, v.size.capacity, state.tick, docked)
        val next = state.copy(onBoard = state.onBoard.without(id), dock = dock, settled = false, status = Status.PLAYING)
        return next to listOf(RtEvent.Released(id, slot, docked))
    }

    /** True iff [tap] would release [containerId] now (PLAYING, on board, a slot free, exit ray clear). */
    fun isTappable(state: RealTimeState, containerId: Int): Boolean {
        if (state.status != Status.PLAYING || state.dock.none { it == null }) return false
        if (containerId !in state.level.containers.indices || !state.onBoard[containerId]) return false
        return LevelData.of(state.level).rayClear(containerId, state.onBoard.lo, state.onBoard.hi)
    }

    /**
     * Advances one tick (tick+1), in this order: walkers arriving (Boarded), slot timers (Docked / DispatchStarted /
     * Dispatched); if `tick % ticksPerSeat == 0` the ring turns one seat; then the gate window (at most one
     * BoardingStarted, when `tick >= nextBoardTick`); then, on a seat step, the entry (SteppedOn). Finally, if anything happened, settledness/status are recomputed (Settled event on
     * becoming settled). Allocation-light: a quiet tick allocates only the new state value and the Pair; the seat
     * array and lists are copied only when they change.
     */
    fun tick(state: RealTimeState): Pair<RealTimeState, List<RtEvent>> {
        val cfg = state.config
        val t = state.tick + 1
        var events: ArrayList<RtEvent>? = null
        fun emit(e: RtEvent) {
            (events ?: ArrayList<RtEvent>(4).also { events = it }).add(e)
        }

        // 1. walkers
        var walkers = state.walkers
        if (walkers.isNotEmpty() && walkers.any { it.arriveTick <= t }) {
            val keep = ArrayList<RtWalker>(walkers.size)
            for (w in walkers) {
                if (w.arriveTick <= t) emit(RtEvent.Boarded(w.elementIndex, w.slot, w.containerId)) else keep += w
            }
            walkers = keep
        }

        // 2. slot timers
        var dock = state.dock
        var dockCopied = false
        for (i in 0 until Limits.DOCK_SLOTS) {
            val s = dock[i] ?: continue
            when (t) {
                s.dockedAt -> emit(RtEvent.Docked(s.containerId, i))
                s.dispatchAt -> emit(RtEvent.DispatchStarted(i, s.containerId))
                s.freeAt -> {
                    if (!dockCopied) { dock = dock.toMutableList(); dockCopied = true }
                    (dock as MutableList)[i] = null
                    emit(RtEvent.Dispatched(i, s.containerId))
                }
            }
        }

        // 3. rotation, gate window (staggered), entry
        var phase = state.phase
        var seats = state.seatsRef()
        var ringCount = state.ringCount
        var qi = state.queueIndex
        var nextBoard = state.nextBoardTick
        var lastEntry = state.lastEntryTick
        val n = seats.size
        val seatStep = t % cfg.ticksPerSeat == 0L
        if (seatStep) phase = (phase + 1) % n
        val queue = state.level.queue
        if (t >= nextBoard && ringCount > 0) {
            var accepting = 0 // colour mask of docked containers with unreserved space
            for (i in 0 until Limits.DOCK_SLOTS) {
                val s = dock[i] ?: continue
                if (s.accepts(t)) accepting = accepting or (1 shl s.color)
            }
            if (accepting != 0) {
                // most downstream first: the wave rolls from the window's exit back towards its entrance
                for (k in cfg.gateWidth - 1 downTo 0) {
                    val pos = (cfg.gatePosition + k) % n
                    val g = Math.floorMod(pos - phase, n)
                    val e = seats[g]
                    if (e < 0) continue
                    val c = queue[e]
                    if ((accepting ushr c) and 1 == 0) continue
                    var i = 0
                    while (dock[i]?.let { it.color == c && it.accepts(t) } != true) i++ // leftmost; exists (mask)
                    val s = dock[i]!!
                    seats = seats.copyOf()
                    seats[g] = -1
                    ringCount--
                    val left = s.spaceLeft - 1
                    val ns = if (left == 0) {
                        val da = t + cfg.boardTicks
                        s.copy(spaceLeft = 0, fullAt = t, dispatchAt = da, freeAt = da + cfg.dispatchTicks)
                    } else s.copy(spaceLeft = left)
                    if (!dockCopied) { dock = dock.toMutableList(); dockCopied = true }
                    (dock as MutableList)[i] = ns
                    walkers = walkers + RtWalker(e, c, g, pos, i, s.containerId, t, t + cfg.boardTicks)
                    emit(RtEvent.BoardingStarted(e, g, i, s.containerId))
                    nextBoard = t + cfg.boardStaggerTicks
                    break
                }
            }
        }
        if (seatStep) {
            val en = Math.floorMod(cfg.entryFor(n) - phase, n)
            if (seats[en] < 0 && qi < queue.size) {
                if (seats === state.seatsRef()) seats = seats.copyOf()
                seats[en] = qi
                ringCount++
                emit(RtEvent.SteppedOn(qi, en))
                qi++
                lastEntry = t
            }
        }

        var next = state.copy(
            tick = t, phase = phase, seats = seats, ringCount = ringCount, dock = dock, queueIndex = qi, walkers = walkers,
            nextBoardTick = nextBoard, lastEntryTick = lastEntry,
        )
        val ev = events
        if (ev != null) {
            val settled = isSettled(next)
            if (settled != state.settled) {
                val status = if (settled) settledStatus(next) else Status.PLAYING
                next = next.copy(settled = settled, status = status)
                if (settled) ev += RtEvent.Settled(status)
            }
            return next to ev
        }
        return next to emptyList()
    }

    /** Ticks until [isSettled] (at most [maxTicks]); returns the settled state and all events. */
    fun runUntilSettled(state: RealTimeState, maxTicks: Int = 100_000): Pair<RealTimeState, List<RtEvent>> {
        var s = state
        val all = ArrayList<RtEvent>()
        var n = 0
        while (!s.settled) {
            check(n++ < maxTicks) { "not settled after $maxTicks ticks: $s" }
            val (ns, ev) = tick(s)
            all += ev
            s = ns
        }
        return s to all
    }

    /** Advances [ticks] ticks (e.g. one UI frame). */
    fun advance(state: RealTimeState, ticks: Int): Pair<RealTimeState, List<RtEvent>> {
        var s = state
        var all: ArrayList<RtEvent>? = null
        repeat(ticks) {
            val (ns, ev) = tick(s)
            if (ev.isNotEmpty()) (all ?: ArrayList<RtEvent>().also { all = it }).addAll(ev)
            s = ns
        }
        return s to (all ?: emptyList())
    }

    private fun settledStatus(s: RealTimeState): Status {
        val gs = GameState(
            s.level, s.onBoard,
            s.dock.map { slot -> slot?.let { DockedContainer(it.containerId, it.color, it.spaceLeft) } },
            s.ringColors(), s.queueIndex, Status.PLAYING,
        )
        return Engine.computeStatus(gs)
    }
}
