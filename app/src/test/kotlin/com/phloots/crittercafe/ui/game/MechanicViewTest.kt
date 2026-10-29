package com.phloots.crittercafe.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.phloots.crittercafe.game.rt.CoreRealTimeRules
import com.phloots.crittercafe.ui.skin.FieldStyle
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.realtime.RealTime
import com.phloots.crittercafe.core.realtime.RtConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The view-side mechanic logic: wide spans in the dock, release animations, tie ends, the belt's queue preview. */
class MechanicViewTest {
    private val rules = CoreRealTimeRules()

    /** Wide M 0 (colour 0) and a plain S 1 (colour 1), both free. */
    private fun wideLevel(benches: List<Int> = emptyList()) = Level(
        id = 1, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.M, 0, 1, 1, Dir.UP, wide = true),
            Container(1, ContainerSize.S, 1, 1, 3, Dir.UP),
        ),
        queue = List(4) { 1 } + List(6) { 0 },
        benchColors = benches,
    )

    @Test
    fun aWideTicketSpansTheTwoSlotsItTakes() {
        val start = RealTime.runUntilSettled(rules.initial(wideLevel())).first
        val view = rules.view(rules.tap(start, 0).state)
        assertEquals(2, view.slotSpan(0))
        assertTrue(view.isWideTail(1))
        assertFalse(view.isWideTail(0))
        assertEquals(1, view.slotSpan(2))
        assertEquals(view.slots[0], view.slots[1])
    }

    @Test
    fun aWideTicketSkipsABenchOfAnotherColour() {
        val start = RealTime.runUntilSettled(rules.initial(wideLevel(benches = listOf(1, -1, -1, -1)))).first
        val view = rules.view(rules.tap(start, 0).state)
        assertNull(view.slots[0])
        assertEquals(1, view.slotSpan(0))
        assertEquals(2, view.slotSpan(1))
        assertTrue(view.isWideTail(2))
        assertEquals(1, view.slotSpan(3))
    }

    /** S 0 on a turntable facing right, S 1 hidden behind S 2 (its cover), a gate open at start. */
    private val board = Level(
        id = 2, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 2, 2, Dir.RIGHT),
            Container(1, ContainerSize.S, 1, 1, 0, Dir.UP, hidden = true),
            Container(2, ContainerSize.S, 2, 0, 0, Dir.UP),
            Container(3, ContainerSize.S, 3, 4, 4, Dir.DOWN),
        ),
        queue = List(16) { it / 4 },
        turntables = listOf(Cell(2, 2)),
        gates = listOf(Gate(Dir.DOWN, 4)),
    )

    @Test
    fun aReleaseTurnsTheTurntableFlipsTheUncoveredTicketAndSwingsTheGate() {
        val all = setOf(0, 1, 2, 3)
        val after = setOf(0, 1, 3) // the cover left
        val start = BoardMechanics(board, after, all, change = 0f)
        assertEquals(90f, start.facingDegrees(0), 0.01f) // still facing right
        assertEquals(1f, start.gateOpenness(0), 0.01f)
        assertEquals(0f, start.revealFlip(1)!!, 0f)
        val mid = BoardMechanics(board, after, all, change = 0.5f)
        assertTrue(mid.facingDegrees(0) in 90f..180f)
        assertTrue(mid.gateOpenness(0) in 0f..1f)
        assertTrue(mid.animating)
        assertEquals(setOf(0, 1), mid.moving(after))
        val done = BoardMechanics(board, after, all, change = 1f)
        assertEquals(180f, done.facingDegrees(0), 0.01f)
        assertEquals(0f, done.gateOpenness(0), 0.01f)
        assertNull(done.revealFlip(1))
        assertTrue(done.isRevealed(1))
        assertFalse(done.animating)
    }

    @Test
    fun theTurntableTurnsClockwiseThroughUp() {
        // the third release turns it from left to up: a quarter turn clockwise (-90 -> 0), not back through 180
        val mid = BoardMechanics(board, setOf(0), setOf(0, 3), change = 0.5f)
        val f = mid.facingDegrees(0)
        assertEquals(Dir.UP, mid.dirOf(0))
        assertTrue("$f", f in -90f..0f)
    }

    @Test
    fun theTrackerAnimatesASingleReleaseAndSnapsOnUndo() {
        val all = setOf(0, 1, 2, 3)
        val tracker = ReleaseTracker(board, all)
        assertFalse(tracker.mechanics(all, 0)!!.animating)
        assertTrue(tracker.mechanics(setOf(0, 1, 3), 1000)!!.animating)
        assertTrue(tracker.mechanics(setOf(0, 1, 3), 1000 + ReleaseTracker.CHANGE_MS / 2L)!!.animating)
        assertFalse(tracker.mechanics(setOf(0, 1, 3), 1000 + ReleaseTracker.CHANGE_MS.toLong())!!.animating)
        // undo: the ticket comes back without an animation
        assertFalse(tracker.mechanics(all, 3000)!!.animating)
        // levels without mechanics skip all of it
        assertNull(ReleaseTracker(board.copy(turntables = emptyList(), gates = emptyList(), containers = board.containers.map { it.copy(hidden = false) }), all).mechanics(all, 0))
    }

    @Test
    fun theTieRibbonJoinsTheNearestCells() {
        val waiting = Container(0, ContainerSize.L, 0, 0, 0, Dir.LEFT) // cells (0,0) (0,1) (0,2)
        val partner = Container(1, ContainerSize.M, 1, 2, 3, Dir.UP) // cells (2,3) (3,3)
        assertEquals(Cell(0, 2) to Cell(2, 3), tieEnds(waiting, partner))
    }

    private val belt = FieldStyle(queueBelt = true, dockBandDp = 92f)
    private val cfg = RtConfig.DEFAULT

    private fun layout(seats: Int, preview: Int) = RingLayout(
        411f, 860f, 12, 14, Density(1f), seats, style = belt,
        entryFromGate = cfg.entryFor(seats) - (cfg.gatePosition + (cfg.gateWidth - 1) / 2f), queueMax = preview,
    )

    @Test
    fun theBeltShowsTheLevelsQueuePreview() {
        for (seats in listOf(20, 28, 40)) for (preview in listOf(1, 5, 10, 20, 30, 40)) {
            val lay = layout(seats, preview)
            assertEquals("seats $seats preview $preview", preview, lay.queuePlaces(Offset.Zero).size)
            assertTrue(lay.queuePitch <= lay.pitch + 1e-3f)
        }
        // the default fits at the ring's pitch; a long preview on a small ring shrinks the plates to fit
        assertEquals(layout(40, 10).pitch, layout(40, 10).queuePitch, 1e-3f)
        val long = layout(20, 30)
        assertTrue(long.queueToken < long.seatToken)
        assertTrue(long.beltBand < long.seatR * 2.6f)
    }

    @Test
    fun theInsideQueueShrinksForLongPreviews() {
        val short = RingLayout(411f, 860f, 12, 14, Density(1f), 40)
        val long = RingLayout(411f, 860f, 12, 14, Density(1f), 40, queueMax = 40)
        assertTrue(long.queueToken < short.queueToken)
        // with the entry at the left end both rows fill (the entry's place decides how much of row 1 is used)
        assertTrue(long.queuePlaces(Offset(0f, 0f)).size >= 34)
    }
}
