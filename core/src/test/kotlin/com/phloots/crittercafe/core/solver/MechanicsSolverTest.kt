package com.phloots.crittercafe.core.solver

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.engine.MechBrute
import com.phloots.crittercafe.core.engine.MechLevels
import com.phloots.crittercafe.core.engine.MechRef
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Solver on v4 mechanic levels: exact against brute force, lines replay through Engine, hints keep winning. */
class MechanicsSolverTest {
    private fun replayWins(start: GameState, line: List<Move>): Boolean {
        var s = start
        for (m in line) {
            val t = Engine.apply(s, m)
            if (t.events.first() !is com.phloots.crittercafe.core.model.Event.Released) return false
            s = t.state
        }
        return s.status == Status.WON
    }

    private fun checkAgainstBrute(mechs: Set<Mechanic>, seed: Long, levels: Int): Pair<Int, Int> {
        val rnd = Random(seed)
        var wins = 0
        var losses = 0
        repeat(levels) { li ->
            val l = MechLevels.random(rnd, mechs)
            val start = GameState.initial(l)
            val truth = MechBrute().wins(start)
            val r = Solver.solve(start)
            assertEquals(truth, r.solvable, "level $li $l")
            if (truth) {
                wins++
                assertTrue(replayWins(start, r.solution), "line replays, level $li")
            } else losses++
            val a = Solver.analyze(l, 200_000)
            assertFalse(a.capped)
            assertEquals(truth, a.solvable, "analyze level $li")
            if (truth) assertTrue(replayWins(start, a.solution), "analyze line, level $li")
            val b = Solver.analyzeBounded(l)
            if (!b.capped) {
                assertEquals(truth, b.solvable, "analyzeBounded level $li")
                if (truth) assertTrue(replayWins(start, b.solution))
            }
        }
        return wins to losses
    }

    @Test
    fun eachMechanicAloneMatchesBruteForce() {
        for ((k, m) in Mechanic.entries.withIndex()) {
            val (w, l) = checkAgainstBrute(setOf(m), 100L + k, 250)
            assertTrue(w > 10 && l > 10, "$m: $w wins, $l losses")
        }
    }

    @Test
    fun allMechanicsTogetherMatchBruteForce() {
        val (w, l) = checkAgainstBrute(Mechanic.entries.toSet(), 77, 1200)
        assertTrue(w > 50 && l > 50, "$w wins, $l losses")
    }

    @Test
    fun hintsFromMidGameStates() {
        val rnd = Random(31)
        var hinted = 0
        repeat(400) {
            val l = MechLevels.random(rnd)
            var s = GameState.initial(l)
            // wander a few random legal moves (possibly into a lost state), then ask for a hint
            repeat(rnd.nextInt(4)) {
                val legal = Engine.legalMoves(s)
                if (legal.isNotEmpty()) s = Engine.apply(s, legal.random(rnd)).state
            }
            val truth = MechBrute().wins(s)
            val h = Solver.hint(s)
            if (!truth || s.status != Status.PLAYING) {
                assertNull(h)
                return@repeat
            }
            assertNotNull(h)
            hinted++
            val next = Engine.apply(s, h).state
            assertTrue(MechBrute().wins(next), "hint keeps a win")
        }
        assertTrue(hinted > 50, "hinted $hinted")
    }

    @Test
    fun stateKeyIsLosslessAcrossReleaseCounts() {
        // Two boards with the same containers left but reached by different numbers of releases cannot occur
        // (releases = n - |onBoard|), so the key needs nothing beyond onBoard. Check that MechRef agrees: every
        // derived mechanic value is a function of onBoard alone.
        val rnd = Random(5)
        repeat(200) {
            val l = MechLevels.random(rnd)
            var s = GameState.initial(l)
            repeat(6) {
                val legal = MechRef.legal(s)
                if (legal.isEmpty()) return@repeat
                s = MechRef.apply(s, legal.random(rnd)).state
                assertEquals(l.containers.size - s.onBoard.count(), s.releases)
            }
        }
    }

    // ---------------------------------------------------------------- one designed level per mechanic

    private fun s(id: Int, color: Int, row: Int, col: Int, dir: Dir) = Container(id, ContainerSize.S, color, row, col, dir)

    private fun solveAndCheck(l: Level): List<Int> {
        val start = GameState.initial(l)
        val r = Solver.solve(start)
        assertTrue(r.solvable, "solvable")
        assertTrue(replayWins(start, r.solution))
        assertEquals(true, MechBrute().wins(start))
        return r.solution.map { it.containerId }
    }

    @Test
    fun gateForcesAnOrder() {
        // G exits RIGHT through a gate closed at start: it must go after an odd number of releases.
        // Queue: G's colour first, staging 4 -> releasing G first would be ideal but is impossible.
        val g = s(0, 0, 2, 5, Dir.RIGHT)
        val x = s(1, 1, 0, 0, Dir.UP)
        val l = Level(1, 6, 6, listOf(g, x), List(4) { 1 } + List(4) { 0 }, 4, gates = listOf(Gate(Dir.RIGHT, 2, open = false)))
        assertEquals(listOf(1, 0), solveAndCheck(l))
    }

    @Test
    fun frozenAndTiedOrders() {
        // 0 frozen for 2, 1 tied to 2: only 2 -> 1 -> 0 or 2 -> 3 ...; queue makes 2, 1, 3, 0 the only win with 2 spots
        val cs = listOf(
            s(0, 0, 0, 0, Dir.UP).copy(frozen = 2),
            s(1, 1, 0, 1, Dir.UP).copy(tiedTo = 2),
            s(2, 2, 0, 2, Dir.UP),
            s(3, 3, 0, 3, Dir.UP),
        )
        val q = listOf(2, 2, 2, 2, 1, 1, 1, 1, 3, 3, 3, 3, 0, 0, 0, 0)
        val line = solveAndCheck(Level(1, 6, 6, cs, q, 2))
        assertTrue(line.indexOf(1) > line.indexOf(2))
        assertTrue(line.indexOf(0) >= 2)
    }

    @Test
    fun turntableAndWideAndBench() {
        val t = s(0, 0, 2, 2, Dir.UP)
        val b = s(1, 1, 1, 2, Dir.LEFT).copy(wide = true)
        val c = s(2, 2, 2, 4, Dir.UP)
        val e = s(3, 3, 5, 0, Dir.LEFT)
        val l = Level(
            1, 5, 6, listOf(t, b, c, e),
            listOf(3, 3, 3, 3, 1, 1, 1, 1, 2, 2, 2, 2, 0, 0, 0, 0), 2,
            benchColors = listOf(-1, -1, 3, -1), turntables = listOf(Cell(2, 2)),
        )
        solveAndCheck(l)
    }

    @Test
    fun solverIsFastOnBiggerMechanicLevels() {
        // Larger random levels (8x8, up to 18 containers, 4 colours): solve must finish well within the hint budget.
        val rnd = Random(808)
        var solved = 0
        var maxNodes = 0L
        repeat(60) {
            val l = MechLevels.random(rnd, width = 8, height = 8, maxContainers = 18, colors = 4, spots = 8 + rnd.nextInt(8))
            val r = Solver.solve(GameState.initial(l), Solver.HINT_MAX_NODES) ?: return@repeat
            solved++
            maxNodes = maxOf(maxNodes, r.stats.nodesExpanded)
            if (r.solvable) assertTrue(replayWins(GameState.initial(l), r.solution))
        }
        assertTrue(solved >= 55, "solved within budget: $solved / 60 (max nodes $maxNodes)")
    }
}
