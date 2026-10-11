package com.phloots.busjam.core.campaign

import com.phloots.busjam.core.engine.Engine
import com.phloots.busjam.core.io.LevelPackCodec
import com.phloots.busjam.core.io.PackedLevel
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.scoring.Scorer
import com.phloots.busjam.core.solver.Solver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the shipped campaign (app/src/main/assets/levels.json, written by :levelgen):
 * every level is valid and solvable (solution replays to WON), scores re-computed from a fresh analysis match the
 * stored ones, per-cycle median of the 9 normal levels is non-decreasing, each boss >= the top of its own cycle,
 * and cycle 1 is trap-free with 3 colours for levels 1–3.
 */
class CampaignPackTest {
    private val levels: List<PackedLevel> by lazy {
        LevelPackCodec.decode(File("../app/src/main/assets/levels.json").readText()).levels
    }

    private val rescored: List<Double> by lazy {
        levels.parallelStream().map { p ->
            val a = Solver.analyze(p.level)
            assertTrue(a.solvable, "level ${p.level.id} analyze")
            Scorer.score(p.level, a).score
        }.toList()
    }

    @Test
    fun packShape() {
        assertEquals(1000, levels.size)
        levels.forEachIndexed { i, p ->
            assertEquals(i + 1, p.level.id)
            assertEquals(emptyList(), p.level.validate(), "level ${i + 1}")
        }
    }

    @Test
    fun everyLevelSolvesAndReplays() {
        // Warm up the JIT, then time each solve.
        repeat(2) { levels.take(100).forEach { Solver.solve(it.level) } }
        var slowest = 0.0
        for (p in levels) {
            // Best of 3 so a GC pause or a busy machine doesn't count as solver time.
            var best = Double.MAX_VALUE
            lateinit var r: com.phloots.busjam.core.solver.SolveResult
            repeat(3) {
                val t0 = System.nanoTime()
                r = Solver.solve(p.level)
                best = minOf(best, (System.nanoTime() - t0) / 1e6)
            }
            slowest = maxOf(slowest, best)
            assertTrue(r.solvable, "level ${p.level.id} unsolvable")
            var s = GameState.initial(p.level)
            for (m in r.solution) s = Engine.apply(s, m).state
            assertEquals(Status.WON, s.status, "level ${p.level.id} replay")
        }
        // 50 ms is the product budget; allow slack for a loaded CI machine.
        assertTrue(slowest < 150.0, "slowest solve $slowest ms")
    }

    @Test
    fun storedScoresMatchRecomputed() {
        levels.forEachIndexed { i, p -> assertEquals(p.score, rescored[i], 0.006, "level ${i + 1}") }
    }

    @Test
    fun difficultyCurve() {
        val cycles = levels.size / 10
        var prevMedian = Double.NEGATIVE_INFINITY
        for (c in 0 until cycles) {
            val normals = (0 until 9).map { rescored[c * 10 + it] }
            val boss = rescored[c * 10 + 9]
            val median = normals.sorted()[4]
            assertTrue(median >= prevMedian - 1e-9, "cycle ${c + 1} median $median < previous $prevMedian")
            assertTrue(boss >= normals.max() - 1e-9, "cycle ${c + 1} boss $boss < cycle top ${normals.max()}")
            prevMedian = median
        }
    }

    @Test
    fun tutorialCycle() {
        for (i in 0 until 10) {
            val a = Solver.analyze(levels[i].level)
            assertEquals(0.0, a.trapRatio, "level ${i + 1} must have no traps")
            assertEquals(if (i < 3) 3 else 4, levels[i].level.colors().size, "level ${i + 1} colours")
        }
    }
}
