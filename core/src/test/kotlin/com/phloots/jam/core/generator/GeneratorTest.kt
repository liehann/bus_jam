package com.phloots.jam.core.generator

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Cell
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver
import com.phloots.jam.core.model.ContainerSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratorTest {
    private val paramSets = listOf(
        GenParams(width = 5, height = 5, colors = 3, density = 0.4, maxIntendedSlots = 1, interleave = 0.0),
        GenParams(width = 6, height = 8, colors = 4, density = 0.6),
        GenParams(width = 8, height = 10, colors = 6, density = 0.75, maxIntendedSlots = 4, interleave = 0.6),
        GenParams(width = 10, height = 12, colors = 8, density = 0.85, maxIntendedSlots = 4, interleave = 0.5,
            sizeWeights = mapOf(ContainerSize.S to 0.4, ContainerSize.M to 0.35, ContainerSize.L to 0.25)),
    )

    @Test
    fun structuralInvariants() {
        for (p in paramSets) for (seed in 0L until 150L) {
            val (level, order) = assertNotNull(Generator(seed).constructWithOrder(p), "seed $seed $p")
            assertEquals(emptyList(), level.validate(), "seed $seed")
            level.containers.forEachIndexed { i, v -> assertEquals(i, v.id) }
            val cells = HashSet<Cell>()
            for (v in level.containers) for (c in v.cells()) {
                assertTrue(level.inBounds(c.row, c.col))
                assertTrue(cells.add(c), "overlap at $c")
            }
            val capacity = level.containers.groupBy { it.color }.mapValues { (_, vs) -> vs.sumOf { it.size.capacity } }
            assertEquals(capacity, level.queue.groupingBy { it }.eachCount())
            assertEquals(minOf(p.colors, level.containers.size), level.colors().size)
            assertEquals(level.containers.indices.toSet(), order.toSet())
            val peak = assertNotNull(LineSim.peakSlotsIfWins(level, order), "intended line must win, seed $seed")
            assertTrue(peak <= p.maxIntendedSlots, "peak $peak > ${p.maxIntendedSlots}")
        }
    }

    @Test
    fun densityIsApproached() {
        val p = GenParams(width = 8, height = 10, colors = 5, density = 0.7)
        val cov = (0L until 50L).map { s ->
            Generator(s).construct(p)!!.containers.sumOf { it.size.length }.toDouble() / (p.width * p.height)
        }.average()
        assertTrue(cov > 0.6, "mean coverage $cov")
    }

    @Test
    fun deterministicPerSeed() {
        for (p in paramSets) for (seed in listOf(1L, 42L, 9_999_999L)) {
            assertEquals(Generator(seed).constructWithOrder(p), Generator(seed).constructWithOrder(p))
        }
        val p = paramSets[1]
        assertTrue((0L until 20L).map { Generator(it).construct(p) }.toSet().size > 15, "seeds should differ")
    }

    @Test
    fun brokenOrderIsRejectedBySim() {
        val (level, order) = Generator(7).constructWithOrder(paramSets[2])!!
        // Reversed intended order: the first tapped container was placed... it must be blocked or fail somewhere.
        assertEquals(null, LineSim.peakSlotsIfWins(level, order.reversed()))
    }

    @Test
    fun intendedLineWinsInEngineAndSolverAgrees() {
        for (p in paramSets) for (seed in 0L until 40L) {
            val (level, order) = Generator(seed).constructWithOrder(p.copy(crunches = (seed % 3).toInt()))!!
            var s = GameState.initial(level)
            for (id in order) s = Engine.apply(s, Move(id)).state
            assertEquals(Status.WON, s.status, "intended line, seed $seed")
            assertTrue(Solver.solve(level).solvable, "solver must find a win, seed $seed")
        }
    }

    @Test
    fun generateReturnsSolvableLevelsInBand() {
        val p = GenParams(width = 7, height = 8, colors = 5, density = 0.65, maxIntendedSlots = 4,
            minScore = 20.0, maxScore = 80.0, minSharpDecisions = 1, maxAttempts = 50)
        for (seed in 0L until 10L) {
            val g = assertNotNull(Generator(seed).generateScored(p), "seed $seed")
            assertEquals(emptyList(), g.level.validate())
            assertTrue(g.report.score in 20.0..80.0)
            assertTrue(g.analysis.sharpDecisions >= 1)
            assertTrue(!g.report.sizeDominated)
            var s = GameState.initial(g.level)
            for (m in Solver.solve(g.level).solution) s = Engine.apply(s, m).state
            assertEquals(Status.WON, s.status)
            assertEquals(g.level, Generator(seed).generate(p), "deterministic")
        }
    }

    @Test
    fun tutorialParamsGiveTrapFreeLevels() {
        val p = GenParams(width = 5, height = 6, colors = 3, density = 0.4, maxIntendedSlots = 1, interleave = 0.0,
            decoyFraction = 0.0, maxTrapRatio = 0.0, rejectSizeDominated = false, maxAttempts = 20)
        for (seed in 0L until 10L) {
            val g = assertNotNull(Generator(seed).generateScored(p))
            assertEquals(0.0, g.analysis.trapRatio)
        }
    }
}
