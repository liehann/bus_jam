package com.phloots.busjam.core.solver

import com.phloots.busjam.core.engine.Engine
import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Move
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.model.VehicleType
import com.phloots.busjam.core.solver.TestLevels.car
import com.phloots.busjam.core.solver.TestLevels.v
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolverTest {
    private fun q(vararg runs: Pair<Int, Int>) = runs.flatMap { (c, k) -> List(k) { c } }

    /** Exhaustive search through the public Engine only, no memo. Ground truth for small levels. */
    private fun bruteForceWins(s: GameState): Boolean {
        if (s.status == Status.WON) return true
        if (s.status != Status.PLAYING) return false
        return Engine.legalMoves(s).any { bruteForceWins(Engine.apply(s, it).state) }
    }

    @Test
    fun solvesChainInOrder() {
        val level = TestLevels.chain(listOf(0, 1, 2, 3, 4), q(0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4))
        val r = Solver.solve(level)
        assertTrue(r.solvable)
        assertEquals((0..4).map { Move(it) }, r.solution)
        assertEquals(Status.WON, TestLevels.play(level, r.solution).status)
    }

    @Test
    fun chainNeedingFifthSlotIsUnsolvable() {
        // Balanced, but colour 4's car is at the bottom and its passengers come first: needs a 5th slot.
        val level = TestLevels.chain(listOf(0, 1, 2, 3, 4), q(4 to 4, 0 to 4, 1 to 4, 2 to 4, 3 to 4))
        assertTrue(level.validate().isEmpty())
        val r = Solver.solve(level)
        assertFalse(r.solvable)
        assertEquals(emptyList(), r.solution)
        assertNull(Solver.hint(GameState.initial(level)))
        val a = Solver.analyze(level)
        assertFalse(a.solvable)
        assertFalse(a.capped)
    }

    @Test
    fun chainWithFourthSlotIsSolvable() {
        // Same idea with colour 3 at depth 4: exactly 4 slots suffice.
        val level = TestLevels.chain(listOf(0, 1, 2, 3), q(3 to 4, 0 to 4, 1 to 4, 2 to 4))
        val r = Solver.solve(level)
        assertTrue(r.solvable)
        val a = Solver.analyze(level)
        assertTrue(a.solvable)
        assertEquals(4, a.peakSlots)
    }

    @Test
    fun stuckLevelIsUnsolvable() {
        val level = Level(
            1, 4, 3,
            listOf(car(0, 0, 0, 1, Dir.RIGHT), car(1, 1, 0, 2, Dir.LEFT), car(2, 2, 2, 0, Dir.LEFT)),
            q(2 to 4, 0 to 4, 1 to 4),
        )
        assertFalse(Solver.solve(level).solvable)
        assertFalse(Solver.analyze(level).solvable)
    }

    @Test
    fun keyDistinguishesArrivalOrderOfUntouchedSameColourVehicles() {
        // Colour-0 car (id0) and colour-0 bus (id1) are both parked untouched before colour-0 passengers come.
        // Which one fills first decides whether a slot frees in time.
        val level = Level(
            1, 4, 5,
            listOf(
                car(0, 0, 0, 0, Dir.UP),
                v(1, VehicleType.BUS, 0, 0, 1, Dir.UP),
                car(2, 1, 0, 2, Dir.UP),
                car(3, 2, 0, 3, Dir.UP),
                car(4, 3, 2, 3, Dir.UP),
            ),
            q(1 to 4, 0 to 4, 3 to 4, 0 to 10, 2 to 4),
        )
        assertTrue(level.validate().isEmpty(), level.validate().toString())
        val truth = bruteForceWins(GameState.initial(level))
        assertEquals(truth, Solver.solve(level).solvable)
        assertEquals(truth, Solver.analyze(level).solvable)
    }

    @Test
    fun matchesBruteForceOnRandomSmallLevels() {
        val rnd = Random(1234)
        var solvable = 0
        repeat(400) {
            val level = TestLevels.randomSmall(rnd, width = 5, height = 5, maxVehicles = 7, colors = 3)
            val s0 = GameState.initial(level)
            val truth = bruteForceWins(s0)
            val r = Solver.solve(level)
            assertEquals(truth, r.solvable, "level $level")
            val a = Solver.analyze(level)
            assertEquals(truth, a.solvable)
            if (truth) {
                solvable++
                assertEquals(Status.WON, TestLevels.play(level, r.solution).status)
                assertEquals(Status.WON, TestLevels.play(level, a.solution).status)
                assertEquals(a.peakSlots, replayPeak(level, a.solution))
                assertTrue(a.trapRatio in 0.0..1.0)
                assertTrue(a.ordering in 0.0..1.0)
                assertTrue(a.peakSlots in 1..4)
                assertTrue(a.sharpDecisions <= a.solution.size)
                assertTrue(a.decisionStates <= a.solution.size)
            }
        }
        assertTrue(solvable in 20..380, "solvable=$solvable")
    }

    @Test
    fun solveAndAnalyzeAgreeOnMediumLevels() {
        // solve (front-colour pruning + move symmetry) and analyze (canonical leaf key + closed-form losses) use
        // different reductions; they must agree, and both lines must replay to a win.
        val rnd = Random(77)
        var solvable = 0
        repeat(150) {
            val level = TestLevels.randomSmall(rnd, width = 7, height = 7, maxVehicles = 14, colors = 5)
            val r = Solver.solve(level)
            val a = Solver.analyze(level)
            assertFalse(a.capped)
            assertEquals(r.solvable, a.solvable, "level $level")
            if (r.solvable) {
                solvable++
                assertEquals(Status.WON, TestLevels.play(level, r.solution).status)
                assertEquals(Status.WON, TestLevels.play(level, a.solution).status)
                assertEquals(a.peakSlots, replayPeak(level, a.solution))
            }
        }
        assertTrue(solvable > 5, "solvable=$solvable")
    }

    /** Peak slots right after each exit, along [moves]. */
    private fun replayPeak(level: Level, moves: List<Move>): Int {
        var s = GameState.initial(level)
        var peak = 0
        for (m in moves) {
            peak = maxOf(peak, s.slotsUsed + 1)
            s = Engine.apply(s, m).state
        }
        return peak
    }

    @Test
    fun minPressureLineIsBestOverAllWinningLines() {
        val rnd = Random(99)
        repeat(150) {
            val level = TestLevels.randomSmall(rnd, width = 5, height = 5, maxVehicles = 6, colors = 3)
            val a = Solver.analyze(level)
            if (!a.solvable) return@repeat
            assertEquals(bruteMinPeak(GameState.initial(level)), a.peakSlots)
        }
    }

    private fun bruteMinPeak(s: GameState): Int {
        if (s.status == Status.WON) return 0
        if (s.status != Status.PLAYING) return Int.MAX_VALUE
        var best = Int.MAX_VALUE
        for (m in Engine.legalMoves(s)) {
            val sub = bruteMinPeak(Engine.apply(s, m).state)
            if (sub != Int.MAX_VALUE) best = minOf(best, maxOf(s.slotsUsed + 1, sub))
        }
        return best
    }

    private fun bruteLossDepth(s: GameState): Int {
        if (s.status != Status.PLAYING) return 0
        return Engine.legalMoves(s).maxOf { 1 + bruteLossDepth(Engine.apply(s, it).state) }
    }

    @Test
    fun pathMetricsMatchBruteForce() {
        val rnd = Random(2024)
        var withTraps = 0
        repeat(500) {
            val level = TestLevels.randomSmall(rnd, width = 5, height = 6, maxVehicles = 8, colors = 4)
            val a = Solver.analyze(level)
            if (!a.solvable) return@repeat
            var s = GameState.initial(level)
            var trapSum = 0.0
            var depthSum = 0.0
            var traps = 0
            var decisions = 0
            var single = 0
            var sharp = 0
            for (m in a.solution) {
                val legal = Engine.legalMoves(s)
                val children = legal.map { Engine.apply(s, it).state }
                val wins = children.count { bruteForceWins(it) }
                trapSum += (legal.size - wins).toDouble() / legal.size
                children.filter { !bruteForceWins(it) }.forEach { traps++; depthSum += bruteLossDepth(it) }
                if (legal.size >= 2) { decisions++; if (wins == 1) single++ }
                if (wins.toDouble() / legal.size <= 0.5) sharp++
                s = Engine.apply(s, m).state
            }
            if (traps > 0) withTraps++
            val n = a.solution.size
            assertEquals(trapSum / n, a.trapRatio, 1e-9)
            assertEquals(if (traps == 0) 0.0 else depthSum / traps, a.meanTrapDepth, 1e-9)
            assertEquals(decisions, a.decisionStates)
            assertEquals(if (decisions == 0) 0.0 else single.toDouble() / decisions, a.ordering, 1e-9)
            assertEquals(sharp, a.sharpDecisions)
        }
        assertTrue(withTraps >= 10, "withTraps=$withTraps")
    }

    @Test
    fun hintsFromInitialStateWin() {
        val rnd = Random(5)
        var checked = 0
        repeat(100) {
            val level = TestLevels.randomSmall(rnd, width = 6, height = 6, maxVehicles = 9, colors = 4)
            if (!Solver.solve(level).solvable) return@repeat
            var s = GameState.initial(level)
            while (s.status == Status.PLAYING) {
                val h = assertNotNull(Solver.hint(s))
                val t = Engine.apply(s, h)
                assertTrue(t.events.first() is Event.Exited)
                s = t.state
            }
            assertEquals(Status.WON, s.status)
            assertNull(Solver.hint(s))
            checked++
        }
        assertTrue(checked > 10)
    }

    /**
     * Chain of 4 cars (ids 0..3, colours 3..6, top first) in column 0 whose passengers come first, deepest first,
     * plus free cars 4..6 (colours 0..2). Tapping a free car before the chain is cleared is a trap.
     */
    private val trapLevel = Level(
        1, 4, 8,
        listOf(
            car(0, 3, 0, 0, Dir.UP), car(1, 4, 2, 0, Dir.UP), car(2, 5, 4, 0, Dir.UP), car(3, 6, 6, 0, Dir.UP),
            car(4, 0, 0, 1, Dir.UP), car(5, 1, 0, 2, Dir.UP), car(6, 2, 0, 3, Dir.UP),
        ),
        q(6 to 4, 5 to 4, 4 to 4, 3 to 4, 0 to 4, 1 to 4, 2 to 4),
    )

    @Test
    fun trapLevelMetrics() {
        assertTrue(trapLevel.validate().isEmpty())
        val a = Solver.analyze(trapLevel)
        assertTrue(a.solvable)
        assertEquals((0..6).map { Move(it) }, a.solution)
        assertEquals(4, a.peakSlots)
        // Path states: 4 chain states with 3/4 losing moves, then 3 states with none.
        assertEquals(3.0 / 7.0, a.trapRatio, 1e-9)
        // Decision states: 4 chain states (1 winning move) + 2 tail states (all winning).
        assertEquals(6, a.decisionStates)
        assertEquals(4.0 / 6.0, a.ordering, 1e-9)
        assertEquals(4, a.sharpDecisions)
        // Trap depths: 3 traps each at depth 3, 2, 1, 0 moves after the mistake.
        assertEquals(1.5, a.meanTrapDepth, 1e-9)
        assertEquals(Move(0), Solver.hint(GameState.initial(trapLevel)))
    }

    @Test
    fun hintIsNullFromLostStates() {
        var s = GameState.initial(trapLevel)
        s = Engine.apply(s, Move(4)).state // mistake: still PLAYING but lost
        assertEquals(Status.PLAYING, s.status)
        assertNull(Solver.hint(s))
        assertFalse(Solver.solve(s).solvable)

        val dead = TestLevels.chain(listOf(0, 1, 2, 3, 4), q(4 to 4, 0 to 4, 1 to 4, 2 to 4, 3 to 4))
        var d = GameState.initial(dead)
        assertNull(Solver.hint(d))
        repeat(4) { d = Engine.apply(d, Move(it)).state }
        assertEquals(Status.DEADLOCK, d.status)
        assertNull(Solver.hint(d))

        val won = TestLevels.play(trapLevel, (0..6).map { Move(it) })
        assertEquals(Status.WON, won.status)
        assertNull(Solver.hint(won))
        assertTrue(Solver.solve(won).solvable)
    }

    @Test
    fun analyzeCapReportsFailure() {
        val level = TestLevels.bigSolvable(seed = 11, vehicles = 30)
        val a = Solver.analyze(level, maxStates = 50)
        assertTrue(a.capped)
        assertFalse(a.solvable)
        assertEquals(50L, a.reachableStates)
    }

    @Test
    fun trivialLevelMetrics() {
        val level = Level(
            1, 3, 2,
            listOf(car(0, 0, 0, 0, Dir.UP), car(1, 1, 0, 1, Dir.UP), car(2, 2, 0, 2, Dir.UP)),
            q(0 to 4, 1 to 4, 2 to 4),
        )
        val a = Solver.analyze(level)
        assertTrue(a.solvable)
        assertEquals((0..2).map { Move(it) }, a.solution)
        assertEquals(1, a.peakSlots)
        assertEquals(0.0, a.trapRatio)
        assertEquals(0.0, a.ordering)
        assertEquals(0, a.sharpDecisions)
        assertEquals(2, a.decisionStates)
    }

    @Test
    fun solvesLargeLevelsFast() {
        val levels = (0 until 12).map { TestLevels.bigSolvable(seed = 1000L + it * 7919L) }
        // Warm up the JIT.
        repeat(3) { levels.forEach { Solver.solve(it) } }
        val times = levels.map { level ->
            val t0 = System.nanoTime()
            val r = Solver.solve(level)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue(r.solvable)
            assertEquals(Status.WON, TestLevels.play(level, r.solution).status)
            println(
                "solve: ${level.vehicles.size} vehicles, ${level.colors().size} colours, " +
                    "${"%.2f".format(ms)} ms, nodes=${r.stats.nodesExpanded}, memo=${r.stats.statesMemoized}",
            )
            ms
        }
        val sorted = times.sorted()
        println("solve median=${"%.2f".format(sorted[sorted.size / 2])} ms max=${"%.2f".format(sorted.last())} ms")
        // Generous bound for shared CI machines; the target is < 50 ms.
        assertTrue(sorted[sorted.size / 2] < 50.0, "median ${sorted[sorted.size / 2]} ms")
    }
}
