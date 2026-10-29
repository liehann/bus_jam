package com.phloots.crittercafe.core.realtime

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.engine.MechLevels
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.IgnoreReason
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real-time carousel implements the v4 mechanics exactly like Engine (patient play converges, same tap outcomes). */
class MechanicsRealTimeTest {
    /** Configs that fit small rings (4..9 seats). */
    private val configs = listOf(
        RtConfig(ticksPerSeat = 1, releaseTicks = 1, boardTicks = 1, dispatchTicks = 1, gatePosition = 0, entryPosition = 2, gateWidth = 1, boardStaggerTicks = 1),
        RtConfig(ticksPerSeat = 3, releaseTicks = 7, boardTicks = 5, dispatchTicks = 11, gatePosition = 1, entryPosition = 3, gateWidth = 2, boardStaggerTicks = 2),
        RtConfig(ticksPerSeat = 2, releaseTicks = 20, boardTicks = 2, dispatchTicks = 30, gatePosition = 0, entryPosition = 2, gateWidth = 2, boardStaggerTicks = 1),
    )

    @Test
    fun patientPlayConvergesToEngine() {
        val rnd = Random(4242)
        var released = 0
        var wideDocked = 0
        val reasons = HashSet<IgnoreReason>()
        repeat(600) { li ->
            val level = MechLevels.random(rnd)
            val cfg = configs[li % configs.size]
            // GameState.initial is always PLAYING; a random level may be dead from the start (the carousel reports it)
            var e = GameState.initial(level).let { it.copy(status = Engine.computeStatus(it)) }
            var rt = RealTime.runUntilSettled(RealTime.initial(level, cfg)).first
            var steps = 0
            while (e.status == Status.PLAYING && steps++ < 40) {
                val legal = Engine.legalMoves(e)
                assertEquals(legal.map { it.containerId }, level.containers.indices.filter { RealTime.isTappable(rt, it) })
                for (id in level.containers.indices) {
                    assertEquals(e.isRevealed(id), rt.isRevealed(id))
                    assertEquals(e.dirOf(id), rt.dirOf(id))
                    assertEquals(e.frozenRemaining(id), rt.frozenRemaining(id))
                }
                val id = if (legal.isNotEmpty() && rnd.nextInt(4) != 0) legal.random(rnd).containerId else rnd.nextInt(-1, level.containers.size + 1)
                val t = Engine.apply(e, Move(id))
                val (afterTap, tapEv) = RealTime.tap(rt, id)
                when (val first = tapEv.single()) {
                    is RtEvent.Released -> { released++; assertEquals(t.events.first(), Event.Released(id, first.slot)) }
                    is RtEvent.Blocked -> assertEquals(t.events.first(), Event.Blocked(id, first.stopRow, first.stopCol, first.blockerId))
                    is RtEvent.Ignored -> { reasons += first.reason; assertEquals(t.events.first(), Event.Ignored(id, first.reason)) }
                    else -> error("unexpected $first")
                }
                val (settled, ev) = RealTime.runUntilSettled(afterTap)
                e = t.state
                rt = settled
                val g = rt.toGameState()
                assertEquals(e.dock, g.dock, "level $li step $steps")
                assertEquals(e.staging.sorted(), g.staging.sorted())
                assertEquals(e.queueIndex, g.queueIndex)
                assertEquals(e.onBoard, g.onBoard)
                assertEquals(e.status, rt.status, "level $li step $steps")
                assertEquals(t.events.count { it is Event.Dispatched }, ev.count { it is RtEvent.Dispatched })
                assertEquals(t.events.count { it is Event.Released }, ev.count { it is RtEvent.Docked })
                if ((0 until 4).any { e.isWideTail(it) }) wideDocked++
            }
        }
        assertTrue(released > 1000 && wideDocked > 50, "released $released wide $wideDocked")
        assertTrue(reasons.containsAll(listOf(IgnoreReason.FROZEN, IgnoreReason.TIED, IgnoreReason.NO_FITTING_SLOT)), "$reasons")
    }

    @Test
    fun wideHoldsBothSlotsUntilItsDispatchDriveEnds() {
        val cs = listOf(
            Container(0, ContainerSize.S, 0, 0, 0, Dir.UP, wide = true),
            Container(1, ContainerSize.S, 1, 0, 2, Dir.UP),
            Container(2, ContainerSize.S, 1, 0, 3, Dir.UP),
        )
        val level = Level(1, 5, 5, cs, List(4) { 0 } + List(8) { 1 }, 4)
        val cfg = configs[1]
        var rt = RealTime.runUntilSettled(RealTime.initial(level, cfg)).first
        rt = RealTime.tap(rt, 0).first
        assertTrue(rt.isWideTail(1))
        assertEquals(rt.dock[0], rt.dock[1])
        // while it fills and drives away, both of its slots stay occupied: the next release goes to slot 2
        rt = RealTime.advance(rt, cfg.releaseTicks + 1).first
        val (next, ev) = RealTime.tap(rt, 1)
        assertEquals(RtEvent.Released(1, 2, next.tick + cfg.releaseTicks), ev.single())
        val (done, all) = RealTime.runUntilSettled(next)
        val dispatched = all.filterIsInstance<RtEvent.Dispatched>()
        assertEquals(RtEvent.Dispatched(0, 0), dispatched.first())
        assertEquals(null, done.dock[0])
        assertEquals(null, done.dock[1])
    }
}
