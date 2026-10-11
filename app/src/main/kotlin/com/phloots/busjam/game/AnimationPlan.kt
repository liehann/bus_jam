package com.phloots.busjam.game

import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level

/** A container shown in a dock slot. [filled] of [capacity] spots used. */
data class SlotView(val containerId: Int, val color: Int, val filled: Int, val capacity: Int)

/**
 * What the screen shows at a point in time. Built either from a [GameState] or by replaying an Engine event
 * list over one (pure playback of what the Engine reported, no rules here).
 */
data class Snapshot(
    val level: Level,
    val onBoard: Set<Int>,
    val slots: List<SlotView?>,
    val queueIndex: Int,
) {
    fun replay(e: Event): Snapshot = when (e) {
        is Event.Released -> {
            val v = level.containers[e.containerId]
            copy(
                onBoard = onBoard - e.containerId,
                slots = slots.toMutableList().also {
                    it[e.slot] = SlotView(v.id, v.color, 0, v.size.capacity)
                },
            )
        }
        is Event.Boarded -> copy(
            slots = slots.toMutableList().also { s ->
                s[e.slot] = s[e.slot]?.let { it.copy(filled = it.filled + 1) }
            },
            queueIndex = e.elementIndex + 1,
        )
        is Event.Dispatched -> copy(slots = slots.toMutableList().also { it[e.slot] = null })
        is Event.Blocked, is Event.Ignored -> this
    }

    companion object {
        fun of(state: GameState): Snapshot {
            val level = state.level
            return Snapshot(
                level = level,
                onBoard = level.containers.indices.filter { state.isOnBoard(it) }.toSet(),
                slots = state.dock.map { p ->
                    p?.let {
                        val cap = level.containers[it.containerId].size.capacity
                        SlotView(it.containerId, it.color, cap - it.spaceLeft, cap)
                    }
                },
                queueIndex = state.queueIndex,
            )
        }
    }
}

enum class PhaseKind { RELEASE, BLOCK, BOARD, DISPATCH }

/** One animated step: [before] is the snapshot at the start, [events] are played during it. */
data class Phase(val kind: PhaseKind, val before: Snapshot, val events: List<Event>) {
    val after: Snapshot get() = events.fold(before) { s, e -> s.replay(e) }

    /** Duration in ms; every phase is <= 300 ms. */
    val durationMs: Int
        get() = when (kind) {
            PhaseKind.RELEASE -> 280
            PhaseKind.BLOCK -> 260
            PhaseKind.BOARD -> 300
            PhaseKind.DISPATCH -> 240
        }
}

/** Groups an event list into phases: each Released/Blocked alone, consecutive Boarded together, consecutive Dispatched together. */
fun planPhases(from: GameState, events: List<Event>): List<Phase> {
    val phases = mutableListOf<Phase>()
    var snap = Snapshot.of(from)
    var i = 0
    while (i < events.size) {
        val e = events[i]
        val kind = when (e) {
            is Event.Released -> PhaseKind.RELEASE
            is Event.Blocked -> PhaseKind.BLOCK
            is Event.Boarded -> PhaseKind.BOARD
            is Event.Dispatched -> PhaseKind.DISPATCH
            is Event.Ignored -> null
        }
        if (kind == null) { i++; continue }
        val group = mutableListOf(e)
        i++
        if (kind == PhaseKind.BOARD || kind == PhaseKind.DISPATCH) {
            while (i < events.size && events[i]::class == e::class) group += events[i++]
        }
        val phase = Phase(kind, snap, group)
        phases += phase
        snap = phase.after
    }
    return phases
}
