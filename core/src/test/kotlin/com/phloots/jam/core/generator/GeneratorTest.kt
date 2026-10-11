package com.phloots.jam.core.generator

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Cell
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratorTest {
    private val heavy = mapOf(ContainerSize.S to 0.4, ContainerSize.M to 0.35, ContainerSize.L to 0.25)
    private val paramSets = listOf(
        GenParams(width = 10, height = 12, colors = 3, density = 0.08, maxContainers = 9, runs = 0, decoyFraction = 0.0, window = 1.0),
        GenParams(width = 10, height = 12, colors = 5, density = 0.6, runs = 1),
        GenParams(width = 11, height = 13, colors = 7, density = 0.8, runs = 1, chainLength = 3..6, blockBias = 4.0, maxContainers = 60),
        GenParams(width = 12, height = 14, colors = 8, density = 0.85, sizeWeights = heavy, runs = 2, chainLength = 5..10, blockBias = 4.0),
        GenParams(width = 12, height = 14, colors = 10, density = 0.95, runs = 2, sizeWeights = mapOf(ContainerSize.S to 1.0)),
    )

    @Test
    fun constructionsMostlySucceed() {
        for (p in paramSets) {
            val ok = (0L until 60L).count { Generator(it).construct(p) != null }
            assertTrue(ok >= 30, "only $ok / 60 constructions succeeded for $p")
        }
    }

    @Test
    fun runsArePureAndLong() {
        val p = paramSets[3]
        var checked = 0
        for (seed in 0L until 30L) {
            val level = Generator(seed).construct(p) ?: continue
            checked++
            // Longest single-colour stretch must be at least the minimum run length.
            var best = 0
            var cur = 0
            for (i in level.queue.indices) {
                cur = if (i > 0 && level.queue[i] == level.queue[i - 1]) cur + 1 else 1
                best = maxOf(best, cur)
            }
            assertTrue(best >= p.runLength.first, "seed $seed longest run $best")
        }
        assertTrue(checked > 10)
    }

    @Test
    fun structuralInvariants() {
        for (p in paramSets) for (seed in 0L until 100L) {
            val (level, order) = Generator(seed).constructWithOrder(p) ?: continue
            assertEquals(emptyList(), level.validate(), "seed $seed")
            assertTrue(level.width in Limits.MIN_WIDTH..Limits.MAX_WIDTH && level.height in Limits.MIN_HEIGHT..Limits.MAX_HEIGHT)
            assertTrue(level.containers.size <= Limits.MAX_CONTAINERS)
            assertTrue(level.containers.all { it.size.length <= 3 }, "no length-4 containers")
            level.containers.forEachIndexed { i, v -> assertEquals(i, v.id) }
            val cells = HashSet<Cell>()
            for (v in level.containers) for (c in v.cells()) {
                assertTrue(level.inBounds(c.row, c.col))
                assertTrue(cells.add(c), "overlap at $c")
            }
            // Balance: per colour, elements == capacity.
            val capacity = level.containers.groupBy { it.color }.mapValues { (_, vs) -> vs.sumOf { it.size.capacity } }
            assertEquals(capacity, level.queue.groupingBy { it }.eachCount())
            assertEquals(minOf(p.colors, level.containers.size), level.colors().size)
            assertEquals(level.containers.indices.toSet(), order.toSet())
            val peak = assertNotNull(LineSim.peakSlotsIfWins(level, order), "intended line must win, seed $seed")
            assertTrue(peak <= Limits.DOCK_SLOTS)
        }
    }

    @Test
    fun manyContainersAtFullSize() {
        val p = paramSets[4]
        val counts = (0L until 20L).mapNotNull { Generator(it).construct(p)?.containers?.size }
        assertTrue(counts.size >= 10)
        assertTrue(counts.min() >= 110, "S-only dense 12x14 boards should hold >= 110 containers: $counts")
        assertTrue(counts.max() <= Limits.MAX_CONTAINERS)
    }

    @Test
    fun densityIsApproached() {
        val p = GenParams(width = 11, height = 13, colors = 5, density = 0.7)
        val cov = (0L until 40L).mapNotNull { s -> Generator(s).construct(p) }.map {
            it.containers.sumOf { c -> c.size.length }.toDouble() / (p.width * p.height)
        }.average()
        assertTrue(cov > 0.65, "mean coverage $cov")
    }

    @Test
    fun deterministicPerSeed() {
        for (p in paramSets) for (seed in listOf(1L, 42L, 9_999_999L)) {
            assertEquals(Generator(seed).constructWithOrder(p), Generator(seed).constructWithOrder(p))
        }
        val p = paramSets[1]
        assertTrue((0L until 20L).mapNotNull { Generator(it).construct(p) }.toSet().size > 12, "seeds should differ")
    }

    @Test
    fun brokenOrderIsRejectedBySim() {
        val (level, order) = (0L until 50L).firstNotNullOf { Generator(it).constructWithOrder(paramSets[2]) }
        assertEquals(null, LineSim.peakSlotsIfWins(level, order.reversed()))
        assertEquals(null, LineSim.peakSlotsIfWins(level, order.drop(1)))
    }

    @Test
    fun intendedLineWinsInEngineAndSolverAgrees() {
        for (p in paramSets.take(4)) for (seed in 0L until 15L) {
            val (level, order) = Generator(seed).constructWithOrder(p) ?: continue
            var s = GameState.initial(level)
            for (id in order) s = Engine.apply(s, Move(id)).state
            assertEquals(Status.WON, s.status, "intended line, seed $seed")
            val r = Solver.solve(level)
            assertTrue(r.solvable, "solver must find a win, seed $seed")
            var t = GameState.initial(level)
            for (m in r.solution) t = Engine.apply(t, m).state
            assertEquals(Status.WON, t.status, "solver line replays, seed $seed")
        }
    }

    @Test
    fun generateReturnsSolvableBotBeatingLevels() {
        val p = GenParams(width = 12, height = 14, colors = 8, density = 0.85, runs = 2, chainLength = 5..10, blockBias = 4.0,
            maxContainers = 70, rejectBots = Bot.entries.toSet(), minRolloutLoss = 0.25, maxAttempts = 80)
        for (seed in 0L until 3L) {
            val g = assertNotNull(Generator(seed).generateScored(p), "seed $seed")
            assertEquals(emptyList(), g.level.validate())
            assertEquals(emptyList(), Bot.winners(g.level), "no naive bot may win")
            assertTrue(g.rollout.lossRate >= 0.25)
            assertTrue(g.report.rollout >= 0.25)
            var s = GameState.initial(g.level)
            for (m in Solver.solve(g.level).solution) s = Engine.apply(s, m).state
            assertEquals(Status.WON, s.status)
            assertEquals(g.level, Generator(seed).generate(p), "deterministic")
        }
    }

    @Test
    fun tutorialParamsGiveTrapFreeLevels() {
        val p = paramSets[0].copy(maxTrapRatio = 0.0, rejectSizeDominated = false, bounded = false, maxAttempts = 200)
        for (seed in 0L until 5L) {
            val g = assertNotNull(Generator(seed).generateScored(p), "seed $seed")
            assertEquals(0.0, g.analysis.trapRatio)
        }
    }
}
