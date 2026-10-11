package com.phloots.jam.core.scoring

import org.junit.jupiter.api.Disabled
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
    private fun analysis(t: Double, depth: Double, o: Double, peak: Int, sharp: Int = 0, len: Int = 5) =
        FullAnalysis(true, List(len) { Move(it) }, t, depth, o, peak, sharp, 3, 100)

    /** 5x5 board (25 cells), 4 containers, 3 colours: size term S == 0. */
    private val minimal = Level(
        1, 5, 5,
        listOf(small(0, 0, 0, 0, Dir.UP), small(1, 1, 0, 1, Dir.UP), small(2, 2, 0, 2, Dir.UP), small(3, 0, 0, 3, Dir.UP)),
        List(8) { 0 } + List(4) { 1 } + List(4) { 2 },
    )

    @Test
    fun formulaMatchesDesign() {
        val r = Scorer.score(minimal, analysis(t = 0.5, depth = 4.0, o = 0.25, peak = 3))
        assertEquals(0.0, r.size, 1e-12)
        assertEquals(0.5, r.trap, 1e-12)
        assertEquals(0.5, r.depth, 1e-12)
        assertEquals(0.25, r.ordering, 1e-12)
        assertEquals(2.0 / 3.0, r.pressure, 1e-12)
        val expected = 100 * (0.30 * 0.5 + 0.20 * 0.5 + 0.20 * 0.25 + 0.15 * (2.0 / 3.0))
        assertEquals(expected, r.score, 1e-9)
        assertEquals(5, r.solutionLength)
        assertFalse(r.sizeDominated)
    }

    @Test
    fun depthIsCappedAndPressureClamped() {
        val r = Scorer.score(minimal, analysis(t = 1.0, depth = 100.0, o = 1.0, peak = 4))
        assertEquals(1.0, r.depth)
        assertEquals(1.0, r.pressure)
        assertEquals(85.0, r.score, 1e-9)
        val low = Scorer.score(minimal, analysis(t = 0.0, depth = 0.0, o = 0.0, peak = 1))
        assertEquals(0.0, low.pressure)
        assertEquals(0.0, low.score, 1e-12)
    }

    @Test
    @Disabled("v2 pending")
    fun sizeTermSaturatesAtMaxima() {
        val big = TestLevels.bigSolvable(seed = 3, containers = 40)
        val r = Scorer.score(big, analysis(0.0, 0.0, 0.0, 1))
        // cells 120 -> 1, colours 8 -> 1 (if all used), containers up to 40.
        assertTrue(r.size > 0.6 && r.size <= 1.0, "size=${r.size}")
        assertEquals(15.0 * r.size, r.score, 1e-9)
        // Only size contributes: dominated.
        assertTrue(r.sizeDominated)
    }

    @Test
    @Disabled("v2 pending")
    fun sizeDominanceThreshold() {
        val big = TestLevels.bigSolvable(seed = 3, containers = 40)
        val s = Scorer.score(big, analysis(0.0, 0.0, 0.0, 1)).size
        // Need 0.15 S <= 0.40 × (0.15 S + rest)  <=>  rest >= 0.15 S × 1.5
        val needed = 0.15 * s * 1.5 // provided via trap term 0.30 T
        val justBelow = Scorer.score(big, analysis(t = needed / 0.30 * 0.99, depth = 0.0, o = 0.0, peak = 1))
        val justAbove = Scorer.score(big, analysis(t = needed / 0.30 * 1.01, depth = 0.0, o = 0.0, peak = 1))
        assertTrue(justBelow.sizeDominated)
        assertFalse(justAbove.sizeDominated)
    }

    @Test
    @Disabled("v2 pending")
    fun scoresRealAnalysis() {
        val level = TestLevels.chain(listOf(0, 1, 2, 3), List(4) { 3 } + List(4) { 0 } + List(4) { 1 } + List(4) { 2 })
        val a = Solver.analyze(level)
        val r = Scorer.score(level, a)
        assertTrue(r.score in 0.0..100.0)
        assertEquals(1.0, r.pressure) // peak 4
        assertEquals(4, r.solutionLength)
    }
}
