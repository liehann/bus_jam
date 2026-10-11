package com.phloots.jam.core.solver

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.solver.TestLevels.small
import com.phloots.jam.core.solver.TestLevels.v
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolverTest {
    private fun q(vararg runs: Pair<Int, Int>) = runs.flatMap { (c, k) -> List(k) { c } }

    /** L containers of colour 4 needed so that their elements fill the whole staging area (40 spots). */
    private val ls = (Limits.MAX_STAGING_SPOTS + 9) / 10

    /**
     * Column of [k] S containers (colours 0 until k, top first) above [ls] L containers of colour 4 whose elements
     * come first and fill staging: the S containers must all dock before anything boards.
     */
    private fun column(k: Int): Level {
        val vs = (0 until k).map { small(it, it, 2 * it, 0, Dir.UP) } +
            (0 until ls).map { v(k + it, ContainerSize.L, 4, 2 * k + 3 * it, 0, Dir.UP) }
        return Level(1, 2, 2 * k + 3 * ls, vs, q(4 to 10 * ls) + (0 until k).flatMap { c -> List(4) { c } })
    }

    /** Needs a 5th dock slot: unsolvable. */
    private val deadLevel = column(4)

    private var spotsCycle = 0

    /** Next staging size of TestLevels.SPOTS (20, 28, 40 in turn), so every random-level test covers each size. */
    private fun nextSpots(): Int = TestLevels.SPOTS[spotsCycle++ % TestLevels.SPOTS.size]

    /** Random small level; every other one padded so the staging window is 7 spots. Staging sizes cycle 20/28/40. */
    private fun rs(rnd: Random, w: Int, h: Int, n: Int, colors: Int, largeOdds: Int = 6): Level {
        val l = TestLevels.randomSmall(rnd, w, h, n, colors, largeOdds)
        val spots = nextSpots()
        return if (rnd.nextBoolean()) TestLevels.padded(l, spots) else l.copy(stagingSpots = spots)
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
    fun levelNeedingFifthSlotIsUnsolvable() {
        val r = Solver.solve(deadLevel)
        assertFalse(r.solvable)
        assertEquals(emptyList(), r.solution)
        assertNull(Solver.hint(GameState.initial(deadLevel)))
        val a = Solver.analyze(deadLevel)
        assertFalse(a.solvable)
        assertFalse(a.capped)
        assertFalse(Solver.analyzeBounded(deadLevel).solvable)
    }

    @Test
    fun levelNeedingExactlyFourSlotsIsSolvable() {
        val level = column(3)
        assertTrue(Solver.solve(level).solvable)
        val a = Solver.analyze(level)
        assertTrue(a.solvable)
        assertEquals(4, a.peakSlots)
        assertEquals(listOf(0, 1, 2, 3), a.solution.take(4).map { it.containerId })
    }

    @Test
    fun stuckLevelIsUnsolvable() {
        val level = Level(
            1, 4, 3,
            listOf(small(0, 0, 0, 1, Dir.RIGHT), small(1, 1, 0, 2, Dir.LEFT), small(2, 2, 2, 0, Dir.LEFT)),
            q(2 to 4, 0 to 4, 1 to 4),
        )
        assertFalse(Solver.solve(level).solvable)
        assertFalse(Solver.analyze(level).solvable)
    }

    @Test
    fun positionalDockMattersForSameColourContainers() {
        // Two colour-0 containers of different sizes; which one sits left decides which fills first.
        val rnd = Random(31)
        var checked = 0
        repeat(300) {
            val level = rs(rnd, 5, 5, 7, 2, largeOdds = 3)
            val truth = Brute().wins(GameState.initial(level))
            assertEquals(truth, Solver.solve(level).solvable, "level $level")
            checked++
        }
        assertEquals(300, checked)
    }

    @Test
    fun matchesBruteForceOnRandomSmallLevels() {
        val rnd = Random(1234)
        var solvable = 0
        repeat(400) {
            val level = rs(rnd, 5, 5, 7, 3)
            val brute = Brute()
            val truth = brute.wins(GameState.initial(level))
            val r = Solver.solve(level)
            assertEquals(truth, r.solvable, "level $level")
            val a = Solver.analyze(level)
            assertEquals(truth, a.solvable)
            if (truth) {
                solvable++
                assertEquals(Status.WON, TestLevels.play(level, r.solution).status)
                assertEquals(Status.WON, TestLevels.play(level, a.solution).status)
                assertEquals(replayPeak(level, a.solution), a.peakSlots)
                assertEquals(brute.minOcc(GameState.initial(level)).toDouble() / level.containers.size, a.meanDockUsed, 1e-9)
            }
        }
        assertTrue(solvable in 20..380, "solvable=$solvable")
    }

    /**
     * Off-path states (reviewer gap): random play with deliberate random mistakes; at every state reached, solve and
     * hint must agree with brute force, and a hint must lead to a state brute force still wins.
     */
    @Test
    fun solveAndHintMatchBruteForceOffPath() {
        val rnd = Random(777)
        var states = 0
        var lostPlaying = 0
        var winnableMid = 0
        repeat(250) {
            val level = rs(rnd, 6, 6, 9, 4, largeOdds = 4)
            val brute = Brute()
            var s = GameState.initial(level)
            while (true) {
                val truth = brute.wins(s)
                val r = Solver.solve(s)
                assertEquals(truth, r.solvable, "state $s")
                val h = Solver.hint(s)
                if (s.status == Status.PLAYING && truth) {
                    winnableMid++
                    assertNotNull(h)
                    val t = Engine.apply(s, h)
                    assertTrue(t.events.first() is Event.Released)
                    assertTrue(brute.wins(t.state))
                    assertEquals(Status.WON, TestLevels.play(s, r.solution).status)
                } else {
                    assertNull(h)
                    if (s.status == Status.PLAYING) lostPlaying++
                }
                states++
                if (s.status != Status.PLAYING) break
                val legal = Engine.legalMoves(s)
                // Mostly random moves (mistakes), sometimes the hint.
                val m = if (h != null && rnd.nextInt(3) == 0) h else legal[rnd.nextInt(legal.size)]
                s = Engine.apply(s, m).state
            }
        }
        assertTrue(states > 1000, "states=$states")
        assertTrue(lostPlaying > 100, "lostPlaying=$lostPlaying")
        assertTrue(winnableMid > 100, "winnableMid=$winnableMid")
    }

    /**
     * Ids >= 64 (Bits128.hi): [shift] dummy containers take ids 0 until shift and start off the board (already
     * dispatched), so the real containers sit at ids shift.. and straddle the lo/hi boundary.
     */
    private fun highIdState(level: Level, shift: Int): GameState {
        val cols = level.width
        val dummies = (0 until shift).map { small(it, 0, level.height + it / cols, it % cols, Dir.UP) }
        val real = level.containers.map { it.copy(id = it.id + shift) }
        val big = Level(level.id, level.width, level.height + (shift + cols - 1) / cols, dummies + real, level.queue, level.stagingSpots)
        val start = GameState.initial(big)
        var onBoard = start.onBoard
        for (i in 0 until shift) onBoard = onBoard.without(i)
        val s = start.copy(onBoard = onBoard)
        return s.copy(status = Engine.computeStatus(s))
    }

    @Test
    fun solveAndHintMatchBruteForceWithHighIds() {
        val rnd = Random(6464)
        var states = 0
        var crossing = 0
        repeat(150) {
            val base = rs(rnd, 6, 6, 9, 4, largeOdds = 4)
            val shift = listOf(58, 60, 64, 100, 110)[rnd.nextInt(5)].coerceAtMost(128 - base.containers.size)
            if (shift < 64 && shift + base.containers.size > 64) crossing++
            val brute = Brute()
            var s = highIdState(base, shift)
            while (true) {
                val truth = brute.wins(s)
                val r = Solver.solve(s)
                assertEquals(truth, r.solvable, "shift $shift state $s")
                val h = Solver.hint(s)
                if (s.status == Status.PLAYING && truth) {
                    assertNotNull(h)
                    assertTrue(h.containerId >= shift)
                    assertTrue(brute.wins(Engine.apply(s, h).state))
                    assertEquals(Status.WON, TestLevels.play(s, r.solution).status)
                } else {
                    assertNull(h)
                }
                states++
                if (s.status != Status.PLAYING) break
                val legal = Engine.legalMoves(s)
                assertTrue(legal.all { it.containerId >= shift })
                s = Engine.apply(s, if (h != null && rnd.nextInt(3) == 0) h else legal[rnd.nextInt(legal.size)]).state
            }
        }
        assertTrue(states > 500 && crossing > 10, "states=$states crossing=$crossing")
    }

    @Test
    fun cancellationAbortsSearch() {
        // A large level with a shuffled queue: a search of a few thousand nodes.
        val level = (0 until 50).asSequence().map { seed ->
            val l = TestLevels.bigSolvable(seed = 300L + seed, containers = 110, perturb = 0)
            l.copy(queue = l.queue.shuffled(Random(seed)))
        }.first { Solver.solve(it, 1_000_000)!!.stats.nodesExpanded > 2 * Solver.CANCEL_CHECK_NODES }
        var calls = 0
        assertTrue(Solver.solve(GameState.initial(level), Long.MAX_VALUE) { ++calls; false }!!.solvable)
        assertTrue(calls >= 2)
        calls = 0
        assertNull(Solver.solve(GameState.initial(level), Long.MAX_VALUE) { ++calls >= 2 })
        assertEquals(2, calls)
        calls = 0
        assertNull(Solver.hint(GameState.initial(level)) { ++calls >= 1 })
        assertEquals(1, calls)
        // Cheap searches finish before the first poll.
        val easy = column(3)
        assertNotNull(Solver.hint(GameState.initial(easy)) { true })
    }

    /**
     * Shipped pack: follow the solver line, deviate 1..3 random moves, and the hint-budget solve must decide
     * (non-null) almost always; anything it reports is exact (checked against a bigger budget when lost).
     */
    @Test
    fun hintBudgetOffPathOnShippedLevels() {
        val f = java.io.File("../app/src/main/assets/levels.json")
        org.junit.jupiter.api.Assumptions.assumeTrue(f.exists())
        val pack = com.phloots.jam.core.io.LevelPackCodec.decode(f.readText())
        org.junit.jupiter.api.Assumptions.assumeTrue(pack.version >= 2)
        val levels = pack.levels.map { it.level }
        val sample = levels.indices.filter { it % maxOf(1, levels.size / 60) == 0 }.map { levels[it] }
        val rnd = Random(99)
        var checked = 0
        var undecided = 0
        var lost = 0
        for (l in sample) {
            val sol = Solver.solve(l, Solver.HINT_MAX_NODES)?.solution ?: continue
            repeat(3) {
                var s = GameState.initial(l)
                for (m in sol.take(rnd.nextInt(sol.size))) s = Engine.apply(s, m).state
                repeat(1 + rnd.nextInt(3)) {
                    val legal = Engine.legalMoves(s)
                    if (legal.isNotEmpty()) s = Engine.apply(s, legal[rnd.nextInt(legal.size)]).state
                }
                if (s.status != Status.PLAYING) return@repeat
                checked++
                val r = Solver.solve(s, Solver.HINT_MAX_NODES)
                when {
                    r == null -> undecided++
                    r.solvable -> assertEquals(Status.WON, TestLevels.play(s, r.solution).status)
                    else -> {
                        lost++
                        assertNull(Solver.hint(s))
                    }
                }
            }
        }
        println("hintBudgetOffPath: checked=$checked lost=$lost undecided=$undecided over ${sample.size} levels")
        assertTrue(checked > 50, "checked=$checked")
        assertTrue(undecided * 50 <= checked, "undecided=$undecided of $checked")
    }

    @Test
    fun solveAndAnalyzeAgreeOnMediumLevels() {
        val rnd = Random(77)
        var solvable = 0
        repeat(150) {
            val level = rs(rnd, 7, 7, 14, 5)
            val r = Solver.solve(level)
            val a = Solver.analyze(level)
            assertFalse(a.capped)
            assertEquals(r.solvable, a.solvable, "level $level")
            assertEquals(r.solvable, Solver.analyzeBounded(level).solvable)
            if (r.solvable) {
                solvable++
                assertEquals(Status.WON, TestLevels.play(level, r.solution).status)
                assertEquals(Status.WON, TestLevels.play(level, a.solution).status)
                assertEquals(replayPeak(level, a.solution), a.peakSlots)
            }
        }
        assertTrue(solvable > 5, "solvable=$solvable")
    }

    /** Peak slots right after each release, along [moves]. */
    private fun replayPeak(level: Level, moves: List<Move>): Int {
        var s = GameState.initial(level)
        var peak = 0
        for (m in moves) {
            peak = maxOf(peak, s.dockUsed + 1)
            s = Engine.apply(s, m).state
        }
        return peak
    }

    private data class PathMetrics(
        val trap: Double, val depth: Double, val decisions: Int, val ordering: Double, val sharp: Int, val occ: Double,
    )

    /** Metrics along [line] computed by brute force (trap depths capped at [depthCap]). */
    private fun bruteMetrics(level: Level, line: List<Move>, depthCap: Int = Int.MAX_VALUE): PathMetrics {
        val brute = Brute()
        var s = GameState.initial(level)
        var trapSum = 0.0
        var depthSum = 0.0
        var traps = 0
        var decisions = 0
        var single = 0
        var sharp = 0
        var occ = 0
        for (m in line) {
            val children = brute.children(s).map { it.second }
            val wins = children.count { brute.wins(it) }
            trapSum += (children.size - wins).toDouble() / children.size
            children.filter { !brute.wins(it) }.forEach { traps++; depthSum += minOf(depthCap, brute.lossDepth(it)) }
            if (children.size >= 2) { decisions++; if (wins == 1) single++ }
            if (wins.toDouble() / children.size <= 0.5) sharp++
            occ += s.dockUsed
            s = Engine.apply(s, m).state
        }
        val n = line.size
        return PathMetrics(
            trapSum / n, if (traps == 0) 0.0 else depthSum / traps, decisions,
            if (decisions == 0) 0.0 else single.toDouble() / decisions, sharp, occ.toDouble() / n,
        )
    }

    @Test
    fun exhaustivePathMetricsMatchBruteForce() {
        val rnd = Random(2024)
        var withTraps = 0
        repeat(600) {
            // Dense 5x5 boards with many colours: small levels need dock pressure to have traps under v2 staging.
            val level = if (rnd.nextInt(3) == 0) rs(rnd, 5, 5, 8, 4) else TestLevels.trapSmall(rnd, nextSpots())
            val a = Solver.analyze(level)
            if (!a.solvable) return@repeat
            val b = bruteMetrics(level, a.solution)
            if (b.trap > 0) withTraps++
            assertEquals(b.trap, a.trapRatio, 1e-9)
            assertEquals(b.depth, a.meanTrapDepth, 1e-9)
            assertEquals(b.decisions, a.decisionStates)
            assertEquals(b.ordering, a.ordering, 1e-9)
            assertEquals(b.sharp, a.sharpDecisions)
            assertEquals(b.occ, a.meanDockUsed, 1e-9)
        }
        assertTrue(withTraps >= 10, "withTraps=$withTraps")
    }

    /** With budgets large enough, the bounded analysis is exact along its own (solve) line. */
    @Test
    fun boundedAnalysisMatchesBruteForceOnSmallLevels() {
        val rnd = Random(5150)
        var withTraps = 0
        var sameLine = 0
        repeat(600) {
            val level = if (rnd.nextInt(3) == 0) rs(rnd, 5, 5, 8, 4) else TestLevels.trapSmall(rnd, nextSpots())
            val b = Solver.analyzeBounded(level)
            val a = Solver.analyze(level)
            assertEquals(a.solvable, b.solvable)
            assertFalse(b.capped)
            if (!b.solvable) return@repeat
            assertTrue(b.bounded)
            assertEquals(0, b.unknownChildren)
            assertEquals(Status.WON, TestLevels.play(level, b.solution).status)
            val m = bruteMetrics(level, b.solution, depthCap = 8)
            if (m.trap > 0) withTraps++
            assertEquals(m.trap, b.trapRatio, 1e-9)
            assertEquals(m.depth, b.meanTrapDepth, 1e-9)
            assertEquals(m.decisions, b.decisionStates)
            assertEquals(m.ordering, b.ordering, 1e-9)
            assertEquals(m.sharp, b.sharpDecisions)
            assertEquals(m.occ, b.meanDockUsed, 1e-9)
            assertEquals(replayPeak(level, b.solution), b.peakSlots)
            // Same line => same metrics as the exhaustive analysis (trap depths here are far below the cap).
            if (a.solution == b.solution) {
                sameLine++
                assertEquals(a.trapRatio, b.trapRatio, 1e-9)
                assertEquals(a.meanTrapDepth, b.meanTrapDepth, 1e-9)
                assertEquals(a.ordering, b.ordering, 1e-9)
                assertEquals(a.sharpDecisions, b.sharpDecisions)
                assertEquals(a.meanDockUsed, b.meanDockUsed, 1e-9)
            }
        }
        assertTrue(withTraps >= 10, "withTraps=$withTraps")
        assertTrue(sameLine >= 20, "sameLine=$sameLine")
    }

    @Test
    fun boundedAnalysisReportsUnknownsUnderTinyBudget() {
        val level = TestLevels.bigSolvable(seed = 21, containers = 70, perturb = 400)
        val tiny = DifficultyConfig(childSolveNodeBudget = 1, trapDepthNodeBudget = 1)
        val b = Solver.analyzeBounded(level, tiny)
        assertTrue(b.solvable)
        assertTrue(b.unknownChildren > 0)
        assertTrue(b.trapRatio in 0.0..1.0)
        val capped = Solver.analyzeBounded(level, DifficultyConfig(rootSolveNodeBudget = 3))
        assertTrue(capped.capped)
        assertFalse(capped.solvable)
    }

    @Test
    fun blockingChainMetrics() {
        // column(3): three S containers (colours 0..2) above `ls` L containers of colour 4, whose elements fill
        // staging from the start. Colour 4 waits from state 0; the j-th L release digs 3 + j containers deep
        // (measured on the board where the wait began). The S containers dock as dead weight (up to 3).
        val level = column(3)
        for (a in listOf(Solver.analyze(level), Solver.analyzeBounded(level))) {
            assertTrue(a.solvable)
            assertEquals(3 + ls - 1, a.digDepthMax)
            assertEquals(3 + (ls - 1) / 2.0, a.digDepthMean, 1e-9)
            assertEquals(3, a.peakDeadWeight)
        }
        val easy = Level(1, 3, 1, listOf(small(0, 0, 0, 0, Dir.UP), small(1, 1, 0, 1, Dir.UP), small(2, 2, 0, 2, Dir.UP)), q(0 to 4, 1 to 4, 2 to 4))
        val e = Solver.analyzeBounded(easy)
        assertEquals(0, e.digDepthMax)
        assertEquals(0.0, e.digDepthMean)
        assertEquals(0, e.peakDeadWeight)
    }

    @Test
    fun minOccupancyLineIsBestOverAllWinningLines() {
        val rnd = Random(99)
        repeat(150) {
            val level = rs(rnd, 5, 5, 6, 3)
            val a = Solver.analyze(level)
            if (!a.solvable) return@repeat
            val best = Brute().minOcc(GameState.initial(level))
            assertEquals(best.toDouble() / level.containers.size, a.meanDockUsed, 1e-9)
        }
    }

    @Test
    fun hintsFromInitialStateWin() {
        val rnd = Random(5)
        var checked = 0
        repeat(100) {
            val level = rs(rnd, 6, 6, 9, 4)
            if (!Solver.solve(level).solvable) return@repeat
            var s = GameState.initial(level)
            while (s.status == Status.PLAYING) {
                val h = assertNotNull(Solver.hint(s))
                val t = Engine.apply(s, h)
                assertTrue(t.events.first() is Event.Released)
                s = t.state
            }
            assertEquals(Status.WON, s.status)
            assertNull(Solver.hint(s))
            checked++
        }
        assertTrue(checked > 10)
    }

    @Test
    fun hintIsNullFromLostAndFinishedStates() {
        var d = GameState.initial(deadLevel)
        assertNull(Solver.hint(d))
        repeat(4) { d = Engine.apply(d, Move(it)).state }
        assertEquals(Status.DEADLOCK, d.status)
        assertNull(Solver.hint(d))
        assertFalse(Solver.solve(d).solvable)

        val level = TestLevels.chain(listOf(0, 1, 2), q(0 to 4, 1 to 4, 2 to 4))
        val won = TestLevels.play(level, (0..2).map { Move(it) })
        assertEquals(Status.WON, won.status)
        assertNull(Solver.hint(won))
        assertTrue(Solver.solve(won).solvable)
    }

    @Test
    fun analyzeCapReportsFailure() {
        val level = TestLevels.bigSolvable(seed = 11, containers = 40)
        val a = Solver.analyze(level, maxStates = 50)
        assertTrue(a.capped)
        assertFalse(a.solvable)
        assertEquals(50L, a.reachableStates)
    }

    @Test
    fun trivialLevelMetrics() {
        val level = Level(
            1, 3, 2,
            listOf(small(0, 0, 0, 0, Dir.UP), small(1, 1, 0, 1, Dir.UP), small(2, 2, 0, 2, Dir.UP)),
            q(0 to 4, 1 to 4, 2 to 4),
        )
        for (a in listOf(Solver.analyze(level), Solver.analyzeBounded(level))) {
            assertTrue(a.solvable)
            assertEquals(1, a.peakSlots)
            assertEquals(0.0, a.trapRatio)
            assertEquals(0.0, a.meanDockUsed)
            assertEquals(0, a.sharpDecisions)
            assertEquals(2, a.decisionStates)
        }
    }

    @Test
    fun solvesLargeLevelsFast() {
        val levels = (0 until 12).map {
            TestLevels.bigSolvable(seed = 1000L + it * 7919L, containers = 60 + it * 4, perturb = 300)
        }
        repeat(3) { levels.forEach { Solver.solve(it) } }
        val times = levels.map { level ->
            val t0 = System.nanoTime()
            val r = Solver.solve(level)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue(r.solvable)
            assertEquals(Status.WON, TestLevels.play(level, r.solution).status)
            println(
                "solve: ${level.containers.size} containers, ${level.colors().size} colours, " +
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
