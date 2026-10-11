package com.phloots.busjam.game

import com.phloots.busjam.ui.game.QueueLanes
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure event playback (no Engine): hand-written event lists in the order the Engine contract describes. */
class AnimationPlanTest {
    private val level = Level(
        id = 1, width = 10, height = 12,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 1, 1, Dir.UP),
            Container(1, ContainerSize.S, 1, 3, 3, Dir.RIGHT),
        ),
        queue = listOf(1, 0, 0, 0, 1, 0, 1, 1, 1),
    )
    // Explicit 7-spot staging so the fixture does not depend on the staging size.
    private val initial = GameState(level, Bits128.firstN(2), List(4) { null }, level.queue.take(7), 7)

    // Staging [1,0,0,0,1,0,1] (queue 0..6), queueIndex 7. Container 0 (colour 0, capacity 4) docks in slot 0;
    // the oldest colour-0 staged elements (queue 1, 2, 3, 5) board, each followed by a refill; the 4th fills it.
    private val events = listOf(
        Event.Released(0, 0),
        Event.Boarded(1, 1, 0, 0), Event.Staged(7, 6),
        Event.Boarded(2, 1, 0, 0), Event.Staged(8, 6),
        Event.Boarded(3, 1, 0, 0),
        Event.Boarded(5, 2, 0, 0),
        Event.Dispatched(0, 0),
    )

    @Test
    fun groupsEventsIntoPhases() {
        val phases = planPhases(initial, events)
        assertEquals(listOf(PhaseKind.RELEASE, PhaseKind.FLOW, PhaseKind.DISPATCH), phases.map { it.kind })
        assertEquals(6, phases[1].events.size)
        assertEquals(4, phases[1].boardings.size)
        phases.forEach { assertEquals(true, it.durationMs <= 300) }
    }

    @Test
    fun replayMatchesTheResultingState() {
        val end = planPhases(initial, events).last().after
        assertEquals(setOf(1), end.onBoard)
        assertEquals(listOf<SlotView?>(null, null, null, null), end.slots)
        assertEquals(listOf(0, 4, 6, 7, 8), end.staging.map { it.elementIndex })
        assertEquals(listOf(1, 1, 1, 1, 1), end.staging.map { it.color })
        assertEquals(9, end.queueIndex)
        val expected = GameState(
            level = level,
            onBoard = initial.onBoard.without(0),
            dock = listOf(null, null, null, null),
            staging = listOf(1, 1, 1, 1, 1),
            queueIndex = 9,
        )
        assertEquals(Snapshot.of(expected), end)
    }

    @Test
    fun fillCountsDuringTheFlow() {
        val flow = planPhases(initial, events)[1]
        assertEquals(SlotView(0, 0, 0, 4), flow.before.slots[0])
        assertEquals(SlotView(0, 0, 4, 4), flow.after.slots[0])
    }

    @Test
    fun queueLanesSplitAtTheHalfwayPoint() {
        val short = QueueLanes.of(5)
        assertEquals(listOf(5, 0, 0), listOf(short.leftCount, short.rightCount, short.leftPlus))
        // 40 unstaged: left lane 0..11, right lane from 20 (halfway) for 12; +8 between, +8 after
        val l = QueueLanes.of(40)
        assertEquals(listOf(12, 20, 12, 8, 8), listOf(l.leftCount, l.rightStart, l.rightCount, l.leftPlus, l.rightPlus))
    }
}
