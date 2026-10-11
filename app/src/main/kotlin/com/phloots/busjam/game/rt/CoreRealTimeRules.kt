package com.phloots.busjam.game.rt

import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.realtime.RealTime
import com.phloots.jam.core.realtime.RealTimeState
import com.phloots.jam.core.realtime.RtConfig
import com.phloots.jam.core.realtime.RtEvent as CoreEvent

/** [RealTimeRules] over core's [RealTime] (pure mapping, no rules). One seat step takes [seatSeconds]. */
class CoreRealTimeRules(
    private val config: RtConfig = RtConfig.DEFAULT,
    seatSeconds: Double = 0.1,
) : RealTimeRules<RealTimeState> {
    override val tickMs: Double = config.secondsPerTick(seatSeconds) * 1000.0

    override fun initial(level: Level): RealTimeState = RealTime.initial(level, config)

    override fun tick(state: RealTimeState): RtStep<RealTimeState> = RealTime.tick(state).let { (s, e) -> RtStep(s, map(e)) }

    override fun tap(state: RealTimeState, containerId: Int): RtStep<RealTimeState> =
        RealTime.tap(state, containerId).let { (s, e) -> RtStep(s, map(e)) }

    override fun settled(state: RealTimeState): GameState? = if (state.settled) state.toGameState() else null

    override fun view(state: RealTimeState): RtView {
        val heading = IntArray(state.dock.size)
        for (w in state.walkers) heading[w.slot]++
        return RtView(
            level = state.level,
            tick = state.tick,
            onBoard = state.onBoard.ids().toSet(),
            slots = state.dock.mapIndexed { i, s ->
                s?.let {
                    RtSlotView(
                        containerId = it.containerId,
                        color = it.color,
                        capacity = it.capacity,
                        seated = it.capacity - it.spaceLeft - heading[i],
                        releasedAt = it.releasedAt,
                        dockedAt = it.dockedAt,
                        dispatchAt = it.dispatchAt,
                        freeAt = it.freeAt,
                    )
                }
            },
            walkers = state.walkers.map { RtWalkerView(it.color, it.slot, it.startTick, it.arriveTick) },
            seats = List(state.seatCount) { seat -> state.seatColor(seat).takeIf { it >= 0 } },
            rotation = state.phase + state.subSeatFraction,
            rotationPerTick = 1f / config.ticksPerSeat,
            gate = config.gatePosition.toFloat(),
            entry = config.entryPosition.toFloat(),
            queueIndex = state.queueIndex,
            settled = state.settled,
            status = state.status,
        )
    }

    private fun map(events: List<CoreEvent>): List<RtEvent> = events.map { e ->
        when (e) {
            is CoreEvent.Blocked -> RtEvent.Blocked(e.containerId, e.stopRow, e.stopCol, e.blockerId)
            is CoreEvent.Ignored -> RtEvent.Ignored(e.containerId, e.reason)
            is CoreEvent.Released -> RtEvent.Released(e.containerId, e.slot)
            is CoreEvent.BoardingStarted -> RtEvent.BoardingStarted
            else -> RtEvent.Other
        }
    }
}
