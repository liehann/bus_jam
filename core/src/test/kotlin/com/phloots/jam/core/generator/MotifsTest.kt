package com.phloots.jam.core.generator

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Cell
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.scoring.DifficultyConfig
import com.phloots.jam.core.scoring.RolloutDifficulty
import com.phloots.jam.core.solver.Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotifsTest {
    private val filter = MotifFilter(maxAttempts = 3)

    private val recipes = mapOf(
        MotifKind.CapacityChoice to MotifParams(width = 10, height = 12, colors = 6, maxContainers = 60, groups = listOf(RunGroup(capacityChoice = true))),
        MotifKind.BigUnblocks to MotifParams(width = 10, height = 12, colors = 6, maxContainers = 60, groups = listOf(RunGroup(bigUnblocks = true))),
        MotifKind.StackToUnlock to MotifParams(width = 10, height = 12, colors = 6, maxContainers = 60, groups = listOf(RunGroup(stack = true))),
        MotifKind.DenseCore to MotifParams(width = 11, height = 13, colors = 6, denseCore = true, maxContainers = 66, groups = listOf(RunGroup(mixed = 2))),
    )

    private fun firstAccepted(p: MotifParams, seeds: LongRange = 0L until 40L): MotifResult =
        seeds.firstNotNullOfOrNull { MotifGenerator(it).generate(p, DifficultyConfig.DEFAULT, filter) }
            ?: error("no accepted level for $p")

    /** Structure + solvability + the intended line; shared by every motif test. */
    private fun assertSound(r: MotifResult) {
        val level = r.motif.level
        assertEquals(emptyList(), level.validate())
        assertTrue(level.containers.all { it.size.length <= 3 }, "no length > 3")
        val cells = HashSet<Cell>()
        for (v in level.containers) for (c in v.cells()) assertTrue(cells.add(c), "overlap at $c")
        val capacity = level.containers.groupBy { it.color }.mapValues { (_, vs) -> vs.sumOf { it.size.capacity } }
        assertEquals(capacity, level.queue.groupingBy { it }.eachCount(), "balance")
        assertTrue(Solver.solve(level).solvable, "solvable")
        val states = MotifCheck.states(r.motif)
        assertEquals(Status.WON, states.last().status, "intended line wins")
        assertNotNull(LineSim.run(level, r.motif.order), "LineSim agrees")
        // v2 floor.
        assertTrue(Bot.entries.none { it.wins(level) }, "bots lose")
        assertTrue(RolloutDifficulty.lossRate(level) >= DifficultyConfig.DEFAULT.floorMinRolloutLoss)
        assertTrue(level.containers.size >= DifficultyConfig.DEFAULT.floorMinContainers)
    }

    /** Each decision: the intended child is won (replay), the alternative is proven lost. */
    private fun assertDecisions(r: MotifResult, kind: MotifKind) {
        val ds = r.checks.filter { it.decision.kind == kind }
        assertTrue(ds.isNotEmpty(), "level has a $kind decision")
        for (c in r.checks) {
            assertTrue(c.outcome == DecisionOutcome.LOSS || c.outcome == DecisionOutcome.CERTIFIED_LOSS, "$c")
            val states = MotifCheck.states(r.motif)
            val s = states[c.decision.step]
            assertEquals(r.motif.order[c.decision.step], c.decision.intended.containerId, "intended move is on the line")
            assertTrue(Engine.isTappable(s, c.decision.alternative.containerId), "alternative is a real option")
            val bad = Engine.apply(s, c.decision.alternative).state
            // Budgeted exact solve of the wrong branch: never winnable.
            val solved = Solver.solve(bad, 300_000)
            if (solved != null) assertFalse(solved.solvable, "wrong branch must not win: $c")
        }
    }

    @Test
    fun capacityChoice() {
        val r = firstAccepted(recipes.getValue(MotifKind.CapacityChoice))
        assertSound(r)
        assertDecisions(r, MotifKind.CapacityChoice)
        val d = r.checks.first { it.decision.kind == MotifKind.CapacityChoice }.decision
        val cs = r.motif.level.containers
        assertEquals(cs[d.intended.containerId].color, cs[d.alternative.containerId].color)
        assertTrue(cs[d.intended.containerId].size.capacity < cs[d.alternative.containerId].size.capacity, "exact fit is the smaller one")
        val staged = MotifCheck.states(r.motif)[d.step].staging.count { it == d.color }
        assertEquals(cs[d.intended.containerId].size.capacity, staged, "exactly the small one's capacity staged")
    }

    @Test
    fun bigUnblocks() {
        val r = firstAccepted(recipes.getValue(MotifKind.BigUnblocks))
        assertSound(r)
        assertDecisions(r, MotifKind.BigUnblocks)
        val d = r.checks.first { it.decision.kind == MotifKind.BigUnblocks }.decision
        val cs = r.motif.level.containers
        val staged = MotifCheck.states(r.motif)[d.step].staging.count { it == d.color }
        // The tempting move is the exact fit; the right one is the big L, which is part of the run's wall.
        assertEquals(cs[d.alternative.containerId].size.capacity, staged)
        assertEquals(10, cs[d.intended.containerId].size.capacity)
        assertTrue(d.intended.containerId in r.motif.vaults[d.group].wall)
    }

    @Test
    fun stackToUnlock() {
        val r = firstAccepted(recipes.getValue(MotifKind.StackToUnlock))
        assertSound(r)
        assertDecisions(r, MotifKind.StackToUnlock)
        val v = r.motif.vaults.first()
        val cs = r.motif.level.containers
        val linkColors = v.wall.map { cs[it].color }.toSet()
        assertEquals(1, linkColors.size, "stack links share one colour")
        assertEquals(3, v.wall.size)
        // Right before the target comes out the three links are stacked in the dock: T takes the last slot.
        val states = MotifCheck.states(r.motif)
        val before = states[r.motif.order.indexOf(v.target)]
        assertEquals(Limits.DOCK_SLOTS - 1, before.dockUsed)
        assertEquals(linkColors, before.dock.filterNotNull().map { it.color }.toSet())
    }

    @Test
    fun denseCore() {
        val r = firstAccepted(recipes.getValue(MotifKind.DenseCore))
        assertSound(r)
        assertDecisions(r, MotifKind.DenseCore)
        assertTrue(r.motif.coreDensity >= 0.88, "core density ${r.motif.coreDensity}")
        assertNotNull(r.core)
        assertTrue(r.core!!.sharpFraction >= filter.coreMinSharp)
    }

    @Test
    fun combinationTwoGroups() {
        val p = MotifParams(width = 12, height = 14, colors = 9, maxContainers = 80, peripheryDensity = 0.6, uniformDensity = 0.75, midGap = 6,
            groups = listOf(RunGroup(stack = true, capacityChoice = true), RunGroup(bigUnblocks = true)))
        val r = firstAccepted(p)
        assertSound(r)
        assertEquals(setOf(MotifKind.CapacityChoice, MotifKind.StackToUnlock, MotifKind.BigUnblocks), r.checks.map { it.decision.kind }.toSet())
        assertTrue(r.checks.all { it.passed })
        assertTrue(r.tags().any { it.startsWith("StackToUnlock") } && r.tags().any { it.startsWith("Squeeze") })
    }

    @Test
    fun deterministicPerSeed() {
        for ((_, p) in recipes) {
            val a = MotifGenerator(7).construct(p)?.level
            val b = MotifGenerator(7).construct(p)?.level
            assertEquals(a, b)
        }
    }

    /**
     * Soundness of the wall certificate: on every constructed decision the INTENDED child (known to win: the intended
     * line is replayed to WON) must never be certified lost, and a certified alternative must never be solved as won.
     */
    @Test
    fun wallCertificateNeverFiresOnAWinningState() {
        var certified = 0
        for ((_, p) in recipes) for (seed in 0L until 12L) {
            val ml = MotifGenerator(seed).construct(p) ?: continue
            val states = MotifCheck.states(ml)
            if (states.last().status != Status.WON) continue
            for (d in ml.decisions) {
                val v = ml.vaults[d.group]
                val good = Engine.apply(states[d.step], d.intended).state
                assertFalse(MotifCheck.wallCertificate(ml, good, v), "certificate fired on the intended (winning) child, seed $seed $d")
                // Every later intended-line state is winning too.
                for (j in d.step + 1 until states.size) assertFalse(MotifCheck.wallCertificate(ml, states[j], v), "state $j seed $seed")
                val bad = Engine.apply(states[d.step], d.alternative).state
                if (MotifCheck.wallCertificate(ml, bad, v)) {
                    certified++
                    val r = Solver.solve(bad, 200_000)
                    if (r != null) assertFalse(r.solvable, "certified loss solved as a win, seed $seed $d")
                }
            }
        }
        assertTrue(certified > 0)
    }
}
