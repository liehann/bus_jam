package com.phloots.jam.core.campaign

import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.solver.Solver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validates the shipped official campaign (app/src/main/assets/levels.json, written by
 * levelgen reverse/OfficialPack.kt): 100 levels in cycles of 10 with a hard closer at every tenth; shape, limits and
 * ring sizes; no level the solver can prove unsolvable (the smaller rings are proven by the generator, which
 * replays its own winning line at the chosen size); a trap-free 10x12 tutorial at level 1.
 */
class CampaignPackTest {
    private val config = DifficultyConfig.DEFAULT
    private val pack by lazy { LevelPackCodec.decode(File("../app/src/main/assets/levels.json").readText()) }
    private val levels: List<PackedLevel> by lazy { pack.levels }

    @Test
    fun packShape() {
        assertEquals(LevelPackCodec.CURRENT_VERSION, pack.version)
        assertEquals(100, levels.size)
        levels.forEachIndexed { i, p ->
            val l = p.level
            assertEquals(i + 1, l.id)
            assertEquals(emptyList(), l.validate(), "level ${i + 1}")
            assertTrue(l.width in Limits.MIN_WIDTH..Limits.MAX_WIDTH && l.height in Limits.MIN_HEIGHT..Limits.MAX_HEIGHT, "level ${i + 1} grid")
            assertTrue(l.containers.size <= Limits.MAX_CONTAINERS)
            assertTrue(l.stagingSpots in 20..40, "level ${i + 1} ring ${l.stagingSpots}")
        }
    }

    @Test
    fun everyTenthLevelIsHard() {
        for ((i, p) in levels.withIndex()) {
            val hard = p.motifs.any { it.startsWith("difficultyLabel:") }
            assertEquals((i + 1) % 10 == 0, hard, "level ${i + 1} tags ${p.motifs.take(2)}")
        }
    }

    @Test
    fun noLevelIsProvenUnsolvable() {
        val bad = levels.parallelStream().filter { p ->
            val r = Solver.solve(p.level, 2_000_000)
            r != null && !r.solvable
        }.map { it.level.id }.toList()
        assertEquals(emptyList(), bad)
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
}
