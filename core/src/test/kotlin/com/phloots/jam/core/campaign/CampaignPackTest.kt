package com.phloots.jam.core.campaign

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.generator.Bot
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.scoring.Scorer
import com.phloots.jam.core.solver.Solver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Validates the shipped campaign (app/src/main/assets/levels.json, written by :levelgen) under the v2 rules:
 * shape and limits; every level solves and replays to WON within the solve budget; stored scores match a fresh
 * analyzeBounded + rollout + Scorer; the difficulty curve (median non-decreasing, L1→L9 spread, boss clearly above
 * L9, next cycle drops back); the "too easy" floor from level 2 (containers, colours, no naive bot wins, rollout
 * loss); the colour ramp 5 → 10; a trap-free 10x12 tutorial at level 1.
 */
class CampaignPackTest {
    private val config = DifficultyConfig.DEFAULT
    private val pack by lazy { LevelPackCodec.decode(File("../app/src/main/assets/levels.json").readText()) }
    private val levels: List<PackedLevel> by lazy { pack.levels }
    private val cycles get() = levels.size / 10

    private val rescored: List<Double> by lazy {
        levels.parallelStream().map { p ->
            val a = Solver.analyzeBounded(p.level, config)
            assertTrue(a.solvable, "level ${p.level.id} analyze")
            Scorer.score(p.level, a, config, RolloutDifficulty.lossRate(p.level, config)).score
        }.toList()
    }

    @Test
    fun packShape() {
        assertEquals(LevelPackCodec.CURRENT_VERSION, pack.version)
        assertEquals(2, pack.version)
        assertEquals(1000, levels.size)
        levels.forEachIndexed { i, p ->
            val l = p.level
            assertEquals(i + 1, l.id)
            assertEquals(emptyList(), l.validate(), "level ${i + 1}")
            assertTrue(l.width in Limits.MIN_WIDTH..Limits.MAX_WIDTH && l.height in Limits.MIN_HEIGHT..Limits.MAX_HEIGHT, "level ${i + 1} grid")
            assertTrue(l.containers.size <= Limits.MAX_CONTAINERS)
            assertTrue(l.containers.all { it.size.length in 1..3 }, "level ${i + 1}: no length-4 containers")
        }
    }

    @Test
    fun everyLevelSolvesAndReplays() {
        // Warm up the JIT, then time each solve (best of 3 so a GC pause or a busy machine doesn't count).
        repeat(2) { levels.take(100).forEach { Solver.solve(it.level) } }
        var slowest = 0.0
        var slowestId = 0
        for (p in levels) {
            var best = Double.MAX_VALUE
            var r: com.phloots.jam.core.solver.SolveResult? = null
            repeat(3) {
                val t0 = System.nanoTime()
                r = Solver.solve(GameState.initial(p.level), config.rootSolveNodeBudget)
                best = minOf(best, (System.nanoTime() - t0) / 1e6)
            }
            if (best > slowest) { slowest = best; slowestId = p.level.id }
            val res = assertNotNull(r, "level ${p.level.id} exceeded the solve budget")
            assertTrue(res.solvable, "level ${p.level.id} unsolvable")
            var s = GameState.initial(p.level)
            for (m in res.solution) s = Engine.apply(s, m).state
            assertEquals(Status.WON, s.status, "level ${p.level.id} replay")
        }
        println("slowest solve: level $slowestId, $slowest ms (best of 3)")
        // 50 ms is the product budget; allow slack for a loaded CI machine.
        assertTrue(slowest < 150.0, "slowest solve $slowest ms (level $slowestId)")
    }

    @Test
    fun storedScoresMatchRecomputed() {
        levels.forEachIndexed { i, p -> assertEquals(p.score, rescored[i], 0.006, "level ${i + 1}") }
    }

    @Test
    fun difficultyCurve() {
        var prevMedian = Double.NEGATIVE_INFINITY
        var prevL9 = Double.NaN
        for (c in 0 until cycles) {
            val normals = (0 until 9).map { rescored[c * 10 + it] }
            val boss = rescored[c * 10 + 9]
            val median = normals.sorted()[4]
            val l1 = normals.first()
            val l9 = normals.last()
            assertTrue(median >= prevMedian - 1e-9, "cycle ${c + 1} median $median < previous $prevMedian")
            assertTrue(boss - l9 >= BOSS_MIN_GAP, "cycle ${c + 1} boss $boss not >= L9 $l9 + $BOSS_MIN_GAP")
            assertTrue(boss >= normals.max() - 1e-9, "cycle ${c + 1} boss below the cycle top")
            assertTrue(l9 - l1 >= MIN_SPREAD, "cycle ${c + 1} L1→L9 spread ${l9 - l1} < $MIN_SPREAD")
            if (c > 0) assertTrue(l1 < prevL9, "cycle ${c + 1} L1 $l1 does not drop back below previous L9 $prevL9")
            prevMedian = median
            prevL9 = l9
        }
    }

    @Test
    fun tooEasyFloorFromLevel2() {
        levels.drop(1).parallelStream().forEach { p ->
            val l = p.level
            assertTrue(l.containers.size >= config.floorMinContainers, "level ${l.id}: ${l.containers.size} containers")
            assertTrue(l.colors().size >= config.floorMinColors, "level ${l.id}: ${l.colors().size} colours")
            assertEquals(emptyList(), Bot.winners(l), "level ${l.id} is won by a naive bot")
            val loss = RolloutDifficulty.lossRate(l, config)
            assertTrue(loss >= config.floorMinRolloutLoss, "level ${l.id}: rollout loss $loss")
        }
    }

    @Test
    fun colourRamp() {
        val colours = levels.map { it.level.colors().size }
        assertTrue(colours.all { it <= Limits.MAX_COLORS })
        var prev = 0
        for (c in 0 until cycles) {
            val med = (0 until 9).map { colours[c * 10 + it] }.sorted()[4]
            assertTrue(med >= prev, "cycle ${c + 1} median colours $med < previous $prev")
            prev = med
        }
        assertEquals(5, (1 until 9).map { colours[it] }.min(), "early levels start at 5 colours")
        assertTrue((0 until 9).map { colours[(cycles - 1) * 10 + it] }.min() >= 9, "last cycle reaches 9-10 colours")
    }

    @Test
    fun tutorialLevel() {
        val l = levels[0].level
        assertEquals(10 to 12, l.width to l.height)
        assertTrue(l.containers.size < config.floorMinContainers)
        val a = Solver.analyze(l, 200_000)
        assertTrue(a.solvable && !a.capped)
        assertEquals(0.0, a.trapRatio, "level 1 must have no traps")
    }

    private companion object {
        /** Boss must sit at least this far above L9 of its cycle (target +6..10). */
        const val BOSS_MIN_GAP = 5.0
        /** Minimum L1 → L9 score spread within a cycle. */
        const val MIN_SPREAD = 5.0
    }
}
