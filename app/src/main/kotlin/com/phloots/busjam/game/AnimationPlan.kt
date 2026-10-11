package com.phloots.busjam.game

import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Level

/** A vehicle shown in a parking slot. [filled] seats of [capacity]. */
data class SlotView(val vehicleId: Int, val color: Int, val filled: Int, val capacity: Int)

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
        is Event.Exited -> {
            val v = level.vehicles[e.vehicleId]
            copy(
                onBoard = onBoard - e.vehicleId,
                slots = slots.toMutableList().also {
                    it[e.slot] = SlotView(v.id, v.color, 0, v.type.seats)
                },
            )
        }
        is Event.Boarded -> copy(
            slots = slots.toMutableList().also { s ->
                s[e.slot] = s[e.slot]?.let { it.copy(filled = it.filled + 1) }
            },
            queueIndex = e.passengerIndex + 1,
        )
        is Event.Departed -> copy(slots = slots.toMutableList().also { it[e.slot] = null })
        is Event.Bumped, is Event.Ignored -> this
    }

    companion object {
        fun of(state: GameState): Snapshot {
            val level = state.level
            return Snapshot(
                level = level,
                onBoard = level.vehicles.indices.filter { state.isOnBoard(it) }.toSet(),
                slots = state.slots.map { p ->
                    p?.let {
                        val cap = level.vehicles[it.vehicleId].type.seats
                        SlotView(it.vehicleId, it.color, cap - it.seatsLeft, cap)
                    }
                },
                queueIndex = state.queueIndex,
            )
        }
    }
}

enum class PhaseKind { EXIT, BUMP, BOARD, DEPART }

/** One animated step: [before] is the snapshot at the start, [events] are played during it. */
data class Phase(val kind: PhaseKind, val before: Snapshot, val events: List<Event>) {
    val after: Snapshot get() = events.fold(before) { s, e -> s.replay(e) }

    /** Duration in ms; every phase is <= 300 ms. */
    val durationMs: Int
        get() = when (kind) {
            PhaseKind.EXIT -> 280
            PhaseKind.BUMP -> 260
            PhaseKind.BOARD -> 300
            PhaseKind.DEPART -> 240
        }
}

/** Groups an event list into phases: each Exited/Bumped alone, consecutive Boarded together, consecutive Departed together. */
fun planPhases(from: GameState, events: List<Event>): List<Phase> {
    val phases = mutableListOf<Phase>()
    var snap = Snapshot.of(from)
    var i = 0
    while (i < events.size) {
        val e = events[i]
        val kind = when (e) {
            is Event.Exited -> PhaseKind.EXIT
            is Event.Bumped -> PhaseKind.BUMP
            is Event.Boarded -> PhaseKind.BOARD
            is Event.Departed -> PhaseKind.DEPART
            is Event.Ignored -> null
        }
        if (kind == null) { i++; continue }
        val group = mutableListOf(e)
        i++
        if (kind == PhaseKind.BOARD || kind == PhaseKind.DEPART) {
            while (i < events.size && events[i]::class == e::class) group += events[i++]
        }
        val phase = Phase(kind, snap, group)
        phases += phase
        snap = phase.after
    }
    return phases
}
