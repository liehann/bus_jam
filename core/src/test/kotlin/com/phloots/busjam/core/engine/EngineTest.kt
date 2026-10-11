package com.phloots.busjam.core.engine

import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.IgnoreReason
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Move
import com.phloots.busjam.core.model.ParkedVehicle
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.model.VehicleType
import com.phloots.busjam.core.solver.TestLevels
import com.phloots.busjam.core.solver.TestLevels.car
import com.phloots.busjam.core.solver.TestLevels.v
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineTest {
    private fun q(vararg runs: Pair<Int, Int>) = runs.flatMap { (c, k) -> List(k) { c } }

    @Test
    fun exitsInEachDirectionWhenRayClear() {
        for (dir in Dir.entries) {
            // Car in the middle of a 5x5 board facing [dir].
            val level = Level(1, 5, 5, listOf(car(0, 0, 2, 2, dir)), q(0 to 4))
            val t = Engine.apply(GameState.initial(level), Move(0))
            assertEquals(Event.Exited(0, 0), t.events.first(), "dir $dir")
            assertEquals(Status.WON, t.state.status, "dir $dir")
        }
    }

    @Test
    fun bumpStopsBeforeBlockerInEachDirection() {
        // Mover at the centre (2,2) of a 5x5 board facing dir. Blocker: a 1-cell-thick car two cells away
        // perpendicular to the ray, so the stop cell is one step ahead of the front.
        data class Case(val dir: Dir, val blockerFront: Pair<Int, Int>, val blockerDir: Dir, val stop: Pair<Int, Int>)
        val cases = listOf(
            Case(Dir.UP, 0 to 2, Dir.RIGHT, 1 to 2),
            Case(Dir.DOWN, 4 to 2, Dir.RIGHT, 3 to 2),
            Case(Dir.LEFT, 2 to 0, Dir.UP, 2 to 1),
            Case(Dir.RIGHT, 2 to 4, Dir.UP, 2 to 3),
        )
        for (c in cases) {
            // Mover is a car with front (2,2); its tail is behind it, out of the ray.
            val mover = car(0, 0, 2, 2, c.dir)
            val blocker = car(1, 1, c.blockerFront.first, c.blockerFront.second, c.blockerDir)
            val level = Level(1, 5, 5, listOf(mover, blocker), q(0 to 4, 1 to 4))
            assertTrue(level.validate().isEmpty(), "${c.dir}: ${level.validate()}")
            val s = GameState.initial(level)
            val t = Engine.apply(s, Move(0))
            assertEquals(listOf<Event>(Event.Bumped(0, c.stop.first, c.stop.second, 1)), t.events, "dir ${c.dir}")
            assertSame(s, t.state)
            assertFalse(Engine.isTappable(s, 0))
        }
    }

    @Test
    fun bumpAgainstAdjacentBlockerStopsAtFrontCell() {
        val mover = car(0, 0, 2, 1, Dir.RIGHT) // cells (2,1),(2,0)
        val blocker = car(1, 1, 2, 2, Dir.UP) // cells (2,2),(3,2)
        val level = Level(1, 5, 5, listOf(mover, blocker), q(0 to 4, 1 to 4))
        val t = Engine.apply(GameState.initial(level), Move(0))
        assertEquals(listOf<Event>(Event.Bumped(0, 2, 1, 1)), t.events)
    }

    @Test
    fun bumpReportsNearestOnBoardBlocker() {
        // Ray of car 0 (facing RIGHT from (0,1)) passes car 1 at col 2 and car 2 at col 4.
        val level = Level(
            1, 5, 5,
            listOf(car(0, 0, 0, 1, Dir.RIGHT), car(1, 1, 1, 2, Dir.DOWN), car(2, 2, 1, 4, Dir.DOWN)),
            q(1 to 4, 0 to 4, 2 to 4),
        )
        var s = GameState.initial(level)
        assertEquals(Event.Bumped(0, 0, 1, 1), Engine.apply(s, Move(0)).events.single())
        s = Engine.apply(s, Move(1)).state // car 1 exits downward (rows 1.. clear) and departs
        assertEquals(Event.Bumped(0, 0, 3, 2), Engine.apply(s, Move(0)).events.single())
    }

    @Test
    fun leftmostFreeSlotAfterDeparture() {
        // Three free cars facing UP in row 1 of a 3x2 board. Queue: colour 1 first.
        val level = Level(
            1, 3, 2,
            listOf(car(0, 0, 0, 0, Dir.UP), car(1, 1, 0, 1, Dir.UP), car(2, 2, 0, 2, Dir.UP)),
            q(1 to 4, 0 to 4, 2 to 4),
        )
        var s = GameState.initial(level)
        var t = Engine.apply(s, Move(0))
        assertEquals(listOf<Event>(Event.Exited(0, 0)), t.events)
        s = t.state
        t = Engine.apply(s, Move(2))
        assertEquals(listOf<Event>(Event.Exited(2, 1)), t.events)
        s = t.state
        t = Engine.apply(s, Move(1)) // slot 2; boards 4 then departs; then colour 0 boards car 0 in slot 0
        assertEquals(Event.Exited(1, 2), t.events[0])
        assertEquals((0 until 4).map { Event.Boarded(it, 2, 1) }, t.events.subList(1, 5))
        assertEquals(Event.Departed(2, 1), t.events[5])
        assertEquals((4 until 8).map { Event.Boarded(it, 0, 0) }, t.events.subList(6, 10))
        assertEquals(Event.Departed(0, 0), t.events[10])
        assertEquals((8 until 12).map { Event.Boarded(it, 1, 2) }, t.events.subList(11, 15))
        assertEquals(Event.Departed(1, 2), t.events[15])
        assertEquals(16, t.events.size)
        assertEquals(Status.WON, t.state.status)
    }

    @Test
    fun exitTakesLeftmostHoleNotEnd() {
        // car0 parks in slot 0, car1 in slot 1; car0 then fills and departs, leaving a hole at slot 0.
        val level = Level(
            1, 4, 2,
            listOf(car(0, 0, 0, 0, Dir.UP), car(1, 1, 0, 1, Dir.UP), car(2, 2, 0, 2, Dir.UP), car(3, 3, 0, 3, Dir.UP)),
            q(1 to 2, 0 to 4, 2 to 4, 1 to 2, 3 to 4),
        )
        var s = GameState.initial(level)
        s = Engine.apply(s, Move(0)).state // slot0, front is colour 1
        s = Engine.apply(s, Move(1)).state // slot1, boards 2 of colour 1, then colour 0 x4 -> car0 departs from slot0
        assertNull(s.slots[0])
        assertEquals(1, s.slots[1]!!.vehicleId)
        assertEquals(2, s.slots[1]!!.seatsLeft)
        val t = Engine.apply(s, Move(3))
        assertEquals(Event.Exited(3, 0), t.events.first())
    }

    @Test
    fun boardingPrefersEarliestArrivalOfSameColour() {
        // Two colour-0 vehicles (car id0, van id1) and a colour-1 car gating the queue.
        val level = Level(
            1, 3, 3,
            listOf(
                car(0, 0, 0, 0, Dir.UP),
                v(1, VehicleType.VAN, 0, 0, 1, Dir.UP),
                car(2, 1, 0, 2, Dir.UP),
            ),
            q(1 to 4, 0 to 10),
        )
        // Van first, then car: van (arrival 0) must receive the first 6 colour-0 passengers.
        var s = GameState.initial(level)
        s = Engine.apply(s, Move(1)).state
        s = Engine.apply(s, Move(0)).state
        val t = Engine.apply(s, Move(2))
        val boarded = t.events.filterIsInstance<Event.Boarded>().filter { it.passengerIndex >= 4 }
        assertEquals(List(6) { 1 }, boarded.take(6).map { it.vehicleId })
        assertEquals(List(4) { 0 }, boarded.drop(6).map { it.vehicleId })
        assertEquals(Status.WON, t.state.status)

        // Car first, then van: the car fills first.
        var s2 = GameState.initial(level)
        s2 = Engine.apply(s2, Move(0)).state
        s2 = Engine.apply(s2, Move(1)).state
        val t2 = Engine.apply(s2, Move(2))
        val b2 = t2.events.filterIsInstance<Event.Boarded>().filter { it.passengerIndex >= 4 }
        assertEquals(List(4) { 0 } + List(6) { 1 }, b2.map { it.vehicleId })
        // Departed for car 0 happens right after its 4th passenger.
        val depIdx = t2.events.indexOf(Event.Departed(0, 0))
        assertEquals(Event.Boarded(7, 0, 0), t2.events[depIdx - 1])
    }

    @Test
    fun partialBoardingKeepsVehicleParked() {
        val level = Level(1, 2, 2, listOf(car(0, 0, 0, 0, Dir.UP), car(1, 1, 0, 1, Dir.UP)), q(0 to 2, 1 to 4, 0 to 2))
        val t = Engine.apply(GameState.initial(level), Move(0))
        assertEquals(listOf(Event.Exited(0, 0), Event.Boarded(0, 0, 0), Event.Boarded(1, 0, 0)), t.events)
        assertEquals(ParkedVehicle(0, 0, 2, 0), t.state.slots[0])
        assertEquals(2, t.state.queueIndex)
        assertEquals(1, t.state.arrivalCounter)
        assertEquals(Status.PLAYING, t.state.status)
    }

    @Test
    fun deadlockWhenFifthSlotNeeded() {
        val level = TestLevels.chain(listOf(0, 1, 2, 3, 4), q(4 to 4, 0 to 4, 1 to 4, 2 to 4, 3 to 4))
        var s = GameState.initial(level)
        for (i in 0 until 3) {
            s = Engine.apply(s, Move(i)).state
            assertEquals(Status.PLAYING, s.status)
        }
        s = Engine.apply(s, Move(3)).state
        assertEquals(Status.DEADLOCK, s.status)
        assertEquals(emptyList(), Engine.legalMoves(s))
        assertFalse(Engine.isTappable(s, 4))
        assertEquals(listOf<Event>(Event.Ignored(4, IgnoreReason.GAME_OVER)), Engine.apply(s, Move(4)).events)
    }

    @Test
    fun stuckWhenNoTappableVehicle() {
        // Cars 0 and 1 face each other in row 0; car 2 is free and its passengers come first.
        val level = Level(
            1, 4, 3,
            listOf(car(0, 0, 0, 1, Dir.RIGHT), car(1, 1, 0, 2, Dir.LEFT), car(2, 2, 2, 0, Dir.LEFT)),
            q(2 to 4, 0 to 4, 1 to 4),
        )
        val s0 = GameState.initial(level)
        assertEquals(listOf(Move(2)), Engine.legalMoves(s0))
        val s = Engine.apply(s0, Move(2)).state
        assertEquals(Status.STUCK, s.status)
        assertEquals(Event.Ignored(0, IgnoreReason.GAME_OVER), Engine.apply(s, Move(0)).events.single())
    }

    @Test
    fun ignoredTaps() {
        val level = Level(
            1, 5, 2,
            List(5) { car(it, it, 0, it, Dir.UP) },
            q(4 to 4, 0 to 4, 1 to 4, 2 to 4, 3 to 4),
        )
        val s0 = GameState.initial(level)
        assertEquals(Event.Ignored(9, IgnoreReason.INVALID_ID), Engine.apply(s0, Move(9)).events.single())
        assertEquals(Event.Ignored(-1, IgnoreReason.INVALID_ID), Engine.apply(s0, Move(-1)).events.single())
        val s1 = Engine.apply(s0, Move(0)).state
        val ign = Engine.apply(s1, Move(0))
        assertEquals(Event.Ignored(0, IgnoreReason.NOT_ON_BOARD), ign.events.single())
        assertSame(s1, ign.state)
        // Hand-built state: 4 slots full but still PLAYING (cannot arise from Engine, which flags DEADLOCK).
        val full = s0.copy(
            onBoard = 1L shl 4,
            slots = List(4) { ParkedVehicle(it, it, 4, it) },
            arrivalCounter = 4,
        )
        assertEquals(Event.Ignored(4, IgnoreReason.SLOTS_FULL), Engine.apply(full, Move(4)).events.single())
        assertFalse(Engine.isTappable(full, 4))
        assertEquals(emptyList(), Engine.legalMoves(full))
    }

    @Test
    fun legalMovesAscendingAndConsistentWithIsTappable() {
        val rnd = Random(7)
        repeat(200) {
            val level = TestLevels.randomSmall(rnd)
            val s = GameState.initial(level)
            val legal = Engine.legalMoves(s).map { it.vehicleId }
            assertEquals(legal.sorted(), legal)
            for (i in level.vehicles.indices) {
                assertEquals(i in legal, Engine.isTappable(s, i))
                val exits = Engine.apply(s, Move(i)).events.first() is Event.Exited
                assertEquals(i in legal, exits)
            }
        }
    }

    /** Random playouts: Engine and the packed solver simulation must agree on state (canonically) and status. */
    @Test
    fun packedSimulationMatchesEngine() {
        val rnd = Random(42)
        var steps = 0
        repeat(300) {
            val level = TestLevels.randomSmall(rnd, width = 6, height = 6, maxVehicles = 9, colors = 4)
            val d = LevelData.of(level)
            var s = GameState.initial(level)
            var ob = s.onBoard
            var aux = d.toPacked(s)
            while (s.status == Status.PLAYING) {
                val moves = Engine.legalMoves(s)
                if (moves.isEmpty()) break
                val m = moves[rnd.nextInt(moves.size)]
                s = Engine.apply(s, m).state
                aux = d.exitAndBoard(ob, aux, m.vehicleId)
                ob = ob and (1L shl m.vehicleId).inv()
                steps++
                assertEquals(s.onBoard, ob)
                assertEquals(d.canon(d.toPacked(s)), d.canon(aux))
                assertEquals(s.status.ordinal, d.status(ob, aux))
            }
        }
        assertTrue(steps > 500)
    }
}
