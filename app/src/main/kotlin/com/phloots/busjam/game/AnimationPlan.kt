package com.phloots.busjam.game

import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level

/** A container shown in a dock slot. [filled] of [capacity] spots used. */
data class SlotView(val containerId: Int, val color: Int, val filled: Int, val capacity: Int)

/** An element standing in a staging spot (list index = spot). [elementIndex] indexes `level.queue`. */
data class StagedView(val elementIndex: Int, val color: Int)

/**
 * What the screen shows at a point in time. Built either from a [GameState] or by replaying an Engine event
 * list over one (pure playback of what the Engine reported; no rules here).
 */
data class Snapshot(
    val level: Level,
    val onBoard: Set<Int>,
    val slots: List<SlotView?>,
    /** Staged elements, oldest first (index = staging spot). */
    val staging: List<StagedView>,
    /** Next queue element not yet staged. */
    val queueIndex: Int,
) {
    /** Elements still waiting in the queue (not staged). */
    val queueRemaining: Int get() = level.queue.size - queueIndex

    fun replay(e: Event): Snapshot = when (e) {
        is Event.Released -> {
            val v = level.containers[e.containerId]
            copy(
                onBoard = onBoard - e.containerId,
                slots = slots.toMutableList().also { it[e.slot] = SlotView(v.id, v.color, 0, v.size.capacity) },
            )
        }
        is Event.Staged -> copy(
            staging = staging + StagedView(e.elementIndex, level.queue[e.elementIndex]),
            queueIndex = maxOf(queueIndex, e.elementIndex + 1),
        )
        is Event.Boarded -> copy(
            staging = staging.toMutableList().also { s ->
                val i = s.indexOfFirst { it.elementIndex == e.elementIndex }.takeIf { it >= 0 } ?: e.fromStagingSpot
                if (i in s.indices) s.removeAt(i)
            },
            slots = slots.toMutableList().also { s -> s[e.slot] = s[e.slot]?.let { it.copy(filled = it.filled + 1) } },
        )
        is Event.Dispatched -> copy(slots = slots.toMutableList().also { it[e.slot] = null })
        is Event.Blocked, is Event.Ignored -> this
    }

    companion object {
        fun of(state: GameState): Snapshot {
            val level = state.level
            val staged = state.stagedElementIndices()
            return Snapshot(
                level = level,
                onBoard = level.containers.indices.filter { state.isOnBoard(it) }.toSet(),
                slots = state.dock.map { p ->
                    p?.let {
                        val cap = level.containers[it.containerId].size.capacity
                        SlotView(it.containerId, it.color, cap - it.spaceLeft, cap)
                    }
                },
                staging = state.staging.indices.map { i -> StagedView(staged.getOrElse(i) { -1 }, state.staging[i]) },
                queueIndex = state.queueIndex,
            )
        }
    }
}

/**
 * RELEASE: one container drives off the board into its dock slot. BLOCK: drive to the blocker and back.
 * FLOW: a run of Boarded / Staged events (elements hop staging -> dock, the queue walks into staging).
 * DISPATCH: full containers leave the dock.
 */
enum class PhaseKind { RELEASE, BLOCK, FLOW, DISPATCH }

/** One animated step: [before] is the snapshot at the start, [events] are played during it. */
data class Phase(val kind: PhaseKind, val before: Snapshot, val events: List<Event>) {
    val after: Snapshot by lazy { events.fold(before) { s, e -> s.replay(e) } }

    /** Boarded events in this phase, in order. */
    val boardings: List<Event.Boarded> get() = events.filterIsInstance<Event.Boarded>()

    /** Duration in ms; every phase is <= 300 ms. */
    val durationMs: Int
        get() = when (kind) {
            PhaseKind.RELEASE -> 300
            PhaseKind.BLOCK -> 260
            PhaseKind.FLOW -> 300
            PhaseKind.DISPATCH -> 220
        }
}

/**
 * Groups an event list into phases: each Released / Blocked alone, a maximal run of Boarded + Staged events as one
 * FLOW, consecutive Dispatched together. Ignored events are skipped.
 */
fun planPhases(from: GameState, events: List<Event>): List<Phase> {
    val phases = mutableListOf<Phase>()
    var snap = Snapshot.of(from)
    var i = 0
    fun kindOf(e: Event): PhaseKind? = when (e) {
        is Event.Released -> PhaseKind.RELEASE
        is Event.Blocked -> PhaseKind.BLOCK
        is Event.Boarded, is Event.Staged -> PhaseKind.FLOW
        is Event.Dispatched -> PhaseKind.DISPATCH
        is Event.Ignored -> null
    }
    while (i < events.size) {
        val e = events[i]
        val kind = kindOf(e)
        if (kind == null) { i++; continue }
        val group = mutableListOf(e)
        i++
        if (kind == PhaseKind.FLOW || kind == PhaseKind.DISPATCH) {
            while (i < events.size && kindOf(events[i]) == kind) group += events[i++]
        }
        val phase = Phase(kind, snap, group)
        phases += phase
        snap = phase.after
    }
    return phases
}
