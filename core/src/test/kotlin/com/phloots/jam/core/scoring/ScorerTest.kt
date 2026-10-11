package com.phloots.jam.core.scoring

import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.solver.FullAnalysis
import com.phloots.jam.core.solver.Solver
import com.phloots.jam.core.solver.TestLevels
import com.phloots.jam.core.solver.TestLevels.small
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScorerTest {
    private fun analysis(t: Double, depth: Double, o: Double, occ: Double, sharp: Int = 0, len: Int = 5) =
        FullAnalysis(true, List(len) { Move(it) }, t, depth, o, 2, sharp, 3, 100, meanDockUsed = occ)

    /** 10x12 board (120 cells), 4 containers, 4 colours: size term S == 0. */
    private val minimal = Level(
        1, 10, 12,
        listOf(small(0, 0, 0, 0, Dir.UP), small(1, 1, 0, 1, Dir.UP), small(2, 2, 0, 2, Dir.UP), small(3, 3, 0, 3, Dir.UP)),
        List(4) { 0 } + List(4) { 1 } + List(4) { 2 } + List(4) { 3 },
    )

    @Test
    fun formulaMatchesDesign() {
        val r = Scorer.score(minimal, analysis(t = 0.5, depth = 4.0, o = 0.25, occ = 1.5))
        assertEquals(0.0, r.size, 1e-12)
        assertEquals(0.5, r.trap, 1e-12)
        assertEquals(0.5, r.depth, 1e-12)
        assertEquals(0.25, r.ordering, 1e-12)
        assertEquals(1.5 / 4, r.pressure, 1e-12)
        val expected = 100 * (0.05 * 0.5 + 0.04 * 0.5 + 0.03 * 0.25 + 0.03 * (1.5 / 4))
        assertEquals(expected, r.score, 1e-9)
        assertEquals(5, r.solutionLength)
        assertFalse(r.sizeDominated)
    }

    @Test
    fun depthIsCappedAndPressureIsMeanOccupancyOverFour() {
        val r = Scorer.score(minimal, analysis(t = 1.0, depth = 100.0, o = 1.0, occ = 3.0))
        assertEquals(1.0, r.depth)
        assertEquals(0.75, r.pressure)
        assertEquals(100 * (0.05 + 0.04 + 0.03 + 0.03 * 0.75), r.score, 1e-9)
        val low = Scorer.score(minimal, analysis(t = 0.0, depth = 0.0, o = 0.0, occ = 0.0))
        assertEquals(0.0, low.pressure)
        assertEquals(0.0, low.score, 1e-12)
    }

    @Test
    fun sizeNormalisationV2() {
        val c = DifficultyConfig.DEFAULT
        assertEquals(listOf(4, 10, 30, 110, 120, 168), listOf(c.colorsMin, c.colorsMax, c.containersMin, c.containersMax, c.cellsMin, c.cellsMax))
        // 12x14 (168 cells -> 1), 10 colours (-> 1), 70 containers (-> 0.5).
        val vs = (0 until 70).map { TestLevels.v(it, ContainerSize.S, it % 10, it / 12, it % 12, Dir.UP) }
        val level = Level(1, 12, 14, vs, vs.flatMap { v -> List(4) { v.color } })
        val r = Scorer.score(level, analysis(0.0, 0.0, 0.0, 0.0))
        assertEquals((1.0 + 0.5 + 1.0) / 3, r.size, 1e-12)
        assertEquals(10.0 * r.size, r.score, 1e-9)
        assertTrue(r.sizeDominated)
    }

    @Test
    fun sizeDominanceThreshold() {
        val big = TestLevels.bigSolvable(seed = 3, containers = 60)
        val s = Scorer.score(big, analysis(0.0, 0.0, 0.0, 0.0)).size
        assertTrue(s > 0.3)
        // 0.10 S <= 0.40 × (0.10 S + rest)  <=>  rest >= 0.10 S × 1.5, provided via the rollout term 0.45 R.
        val needed = 0.10 * s * 1.5
        val justBelow = Scorer.score(big, analysis(0.0, 0.0, 0.0, 0.0), rolloutLossRate = needed / 0.45 * 0.99)
        val justAbove = Scorer.score(big, analysis(0.0, 0.0, 0.0, 0.0), rolloutLossRate = needed / 0.45 * 1.01)
        assertTrue(justBelow.sizeDominated)
        assertFalse(justAbove.sizeDominated)
    }

    @Test
    fun rolloutChainAndDeadWeightTerms() {
        val a = analysis(0.0, 0.0, 0.0, 0.0).copy(digDepthMean = 2.0, digDepthMax = 20, peakDeadWeight = 2)
        val r = Scorer.score(minimal, a, rolloutLossRate = 0.5)
        assertEquals(0.5, r.rollout, 1e-12)
        assertEquals(0.5 * 0.5 + 0.5 * 1.0, r.chain, 1e-12)
        assertEquals(2.0 / 3.0, r.deadWeight, 1e-12)
        assertEquals(100 * (0.45 * 0.5 + 0.20 * 0.75 + 0.10 * (2.0 / 3.0)), r.score, 1e-9)
        assertEquals(0.0, Scorer.score(minimal, a).rollout)
        val c = DifficultyConfig.DEFAULT
        assertEquals(1.0, c.wRollout + c.wChain + c.wDeadWeight + c.wSize + c.wTrap + c.wDepth + c.wOrdering + c.wPressure, 1e-12)
    }

    @Test
    fun tooEasyFloor() {
        val c = DifficultyConfig(floorMinContainers = 30, floorMinColors = 5, floorMinRolloutLoss = 0.25, floorMinScore = 20.0, floorMinSharpDecisions = 2)
        fun lvl(n: Int, colours: Int): Level {
            val vs = (0 until n).map { TestLevels.v(it, ContainerSize.S, it % colours, it / 10, it % 10, Dir.UP) }
            return Level(1, 10, 12, vs, vs.flatMap { v -> List(4) { v.color } })
        }
        val level = lvl(40, 5)
        val a = analysis(0.6, 6.0, 0.5, 2.0, sharp = 3)
        assertFalse(Scorer.isTooEasy(level, Scorer.score(level, a, c, rolloutLossRate = 0.6), c))
        assertTrue(Scorer.isTooEasy(level, Scorer.score(level, a, c, rolloutLossRate = 0.2), c)) // rollout
        assertTrue(Scorer.isTooEasy(level, Scorer.score(level, a, c), c)) // no rollout given
        assertTrue(Scorer.isTooEasy(level, Scorer.score(level, analysis(0.6, 6.0, 0.5, 2.0, sharp = 1), c, 0.6), c)) // sharp
        assertTrue(Scorer.isTooEasy(lvl(20, 5), Scorer.score(lvl(20, 5), a, c, 0.6), c)) // containers
        assertTrue(Scorer.isTooEasy(lvl(40, 4), Scorer.score(lvl(40, 4), a, c, 0.6), c)) // colours
        assertTrue(Scorer.isTooEasy(level, Scorer.score(level, analysis(0.0, 0.0, 0.0, 0.0, sharp = 3), c, 0.3), c)) // score
        // Defaults: no score / sharp floor.
        assertFalse(Scorer.isTooEasy(level, Scorer.score(level, analysis(0.0, 0.0, 0.0, 0.0), rolloutLossRate = 0.3)))
    }

    @Test
    fun scoresRealAnalysis() {
        val level = TestLevels.bigSolvable(seed = 5, containers = 60, perturb = 400)
        val a = Solver.analyzeBounded(level)
        assertTrue(a.solvable)
        val r = Scorer.score(level, a)
        assertTrue(r.score in 0.0..100.0)
        assertEquals(a.meanDockUsed / 4, r.pressure, 1e-12)
        assertEquals(level.containers.size, r.solutionLength)
    }
}
