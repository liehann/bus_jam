package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.solver.Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Fitness5Test {
    private fun s(id: Int, color: Int, row: Int, col: Int, dir: Dir) = Container(id, ContainerSize.S, color, row, col, dir)

    /** The blind trap of [FairnessTest]: docking c4 first looks safe from what is on show and loses 4+ moves later. */
    private val trap = Level(
        1, 3, 2,
        listOf(s(0, 1, 0, 1, Dir.RIGHT), s(1, 1, 1, 0, Dir.UP), s(2, 1, 0, 0, Dir.RIGHT), s(3, 0, 1, 1, Dir.LEFT), s(4, 1, 1, 2, Dir.RIGHT)),
        listOf(0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        stagingSpots = 3,
    )
    private val blind = FairnessConfig(shown = 0, horizon = Int.MAX_VALUE, samples = 24, maxDraws = 200, budget = 200_000, parentBudget = 200_000)
    private val line: List<Move> = Solver.solve(trap).solution

    @Test
    fun blindTrapFailsTheFloorsAndCostsFitness() {
        val hidden = Fitness5.line(trap, line, blind, draws = 16, budget = 50_000)
        val shown = Fitness5.line(trap, line, blind.copy(shown = 20), draws = 16, budget = 50_000)
        assertEquals(1, hidden.m.unfair.size)
        assertEquals(1, hidden.deepTraps)
        assertTrue(hidden.blindRatio > Fitness5Config.DEFAULT.maxBlindRatio[0], "blind ratio ${hidden.blindRatio}")
        assertEquals(0, shown.m.unfair.size)
        assertEquals(0.0, shown.blindRatio, 1e-9)
        val c = Fitness5Config.DEFAULT
        val failsHidden = Fitness5.floorFails(0, listOf(hidden), c)
        assertTrue(failsHidden.any { it.startsWith("unfair") }, "$failsHidden")
        assertTrue(failsHidden.any { it.startsWith("deeptraps") }, "$failsHidden")
        assertTrue(failsHidden.any { it.startsWith("blindratio") }, "$failsHidden")
        assertTrue(Fitness5.floorFails(0, listOf(shown), c).none { it.startsWith("unfair") || it.startsWith("deeptraps") || it.startsWith("blindratio") })
        // the same level scores lower when its decision cannot be read
        assertTrue(Fitness5.bonus(hidden, c) < Fitness5.bonus(shown, c) - c.wUnfair, "${Fitness5.bonus(hidden, c)} vs ${Fitness5.bonus(shown, c)}")
        assertEquals(Fitness5.fitness5(1.0, listOf(hidden, shown), c), 1.0 + (Fitness5.bonus(hidden, c) + Fitness5.bonus(shown, c)) / 2, 1e-9)
    }

    @Test
    fun floorsArePerTier() {
        val hidden = Fitness5.line(trap, line, blind, draws = 16, budget = 50_000)
        // one unfair decision is allowed from tier 2 on, a deep trap only on tier 3
        val c = Fitness5Config.DEFAULT.copy(maxBlindRatio = listOf(1.0, 1.0, 1.0, 1.0), minFairHidden = listOf(0.0, 0.0, 0.0, 0.0),
            minMultiShare = listOf(0.0, 0.0, 0.0, 0.0), minOrderThird = listOf(0.0, 0.0, 0.0, 0.0))
        assertTrue(Fitness5.floorFails(1, listOf(hidden), c).any { it.startsWith("unfair") })
        assertTrue(Fitness5.floorFails(2, listOf(hidden), c).none { it.startsWith("unfair") })
        assertTrue(Fitness5.floorFails(2, listOf(hidden), c).any { it.startsWith("deeptraps") })
        assertEquals(emptyList(), Fitness5.floorFails(3, listOf(hidden), c))
    }

    @Test
    fun optionsAreABand() {
        val c = Fitness5Config.DEFAULT
        val f = Fitness5.line(trap, line, blind.copy(shown = 20), draws = 8, budget = 50_000)
        val a = Fitness5.axes(f, c)
        assertTrue(a.options in -1.0..1.0)
        // mean w beyond meanWFrom costs (the "every container filled at once" levels)
        assertTrue(f.m.meanW < c.meanWFrom)
    }
}
