package com.phloots.jam.core.generator

import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.Forks
import com.phloots.jam.core.scoring.Pressure
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PressureForksTest {
    /**
     * A (L, colour 0) docks with 4 of its 10 staged; the other 6 come after 40 colour-1 elements, i.e. while B1
     * (L, colour 1) boards on the next move: A dwells exactly one move, the rest fill on arrival.
     */
    private val dwellLevel = Level(
        id = 1, width = 10, height = 12,
        containers = (0 until 5).map { Container(it, ContainerSize.L, if (it == 0) 0 else 1, 0, it * 2, Dir.UP) },
        queue = List(4) { 0 } + List(40) { 1 } + List(6) { 0 },
    )

    @Test
    fun dwellAndDock() {
        assertEquals(emptyList(), dwellLevel.validate())
        val pm = Pressure.measure(dwellLevel, (0 until 5).map { Move(it) })
        assertEquals(0.2, pm.meanDwell, 1e-9)
        assertEquals(0.0, pm.dockHigh)
    }

    @Test
    fun forksOnAnEasyLevel() {
        val fk = Forks.measure(dwellLevel, (0 until 5).map { Move(it) })
        assertTrue(fk.forks.all { it.w == it.b && it.h == 0 }, "nothing loses here: ${fk.forks}")
        assertEquals(0, fk.tightForks)
        assertEquals(0.0, fk.value)
    }

    @Test
    fun forksOnAStallLevel() {
        val p = MotifParams(width = 11, height = 13, colors = 8, maxContainers = 64, groups = listOf(RunGroup(stack = true)))
        val ml = (0L until 10L).firstNotNullOf { MotifGenerator(it).construct(p) }
        val line = Solver.solve(ml.level).solution
        val fk = Forks.measure(ml.level, line)
        val cap = DifficultyConfig.DEFAULT.forkHorizonCap
        assertTrue(fk.forks.all { it.w in 1..it.b && it.h in 0..cap && (it.h == 0) == (it.w == it.b) })
        // The stack point: one good move among many, seen only a few moves deep.
        assertTrue(fk.tightForks >= 1, "stall level has tight forks: ${fk.forks}")
        val report = Scorer.score(ml.level, Solver.analyzeBounded(ml.level), DifficultyConfig.DEFAULT, 1.0,
            Pressure.measure(ml.level, line), fk)
        assertTrue(report.forksMeasured && report.pressureMeasured && report.tightForks == fk.tightForks)
    }

    @Test
    fun legacyScoreUnchangedWithoutMetrics() {
        val a = Solver.analyzeBounded(dwellLevel)
        val legacy = Scorer.score(dwellLevel, a, DifficultyConfig.DEFAULT, 0.3)
        val withNone = Scorer.score(dwellLevel, a, DifficultyConfig.DEFAULT, 0.3, null, null)
        assertEquals(legacy.score, withNone.score)
        assertTrue(!legacy.pressureMeasured && !legacy.forksMeasured)
    }

    @Test
    fun ladderIsDeterministic() {
        val p = MotifParams(width = 11, height = 13, colors = 8, maxContainers = 64, groups = listOf(RunGroup(stack = true)))
        val lv = MotifGenerator(3).construct(p)!!.level
        assertEquals(Ladder.winRate(lv, 1, runs = 3), Ladder.winRate(lv, 1, runs = 3))
    }
}
