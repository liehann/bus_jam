package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReverseGeneratorTest {
    private fun params(colors: Int, hold: Int, candidates: Int = 1, w: Int = 11, h: Int = 13) = DirectorParams(
        width = w, height = h, colors = colors, candidates = candidates,
        phases = listOf(
            Phase(taps = 4, hold = hold, dwellMin = 4, dwellMax = 8),
            Phase(taps = 12, hold = hold, dwellMin = 4, dwellMax = 9, chain = 3, decoy = 0.4),
            Phase(taps = 12, hold = hold, dwellMin = 5, dwellMax = 10, chain = 4, decoy = 0.4),
            Phase(taps = 7, hold = 0),
        ),
        reserve = if (colors <= 7) 1 else 2,
    )

    /** The intended line of every generated level replays through the real Engine: each tap releases, ends WON. */
    @Test
    fun intendedLineReplaysToWonOverManySeeds() {
        val sets = listOf(params(10, 3), params(8, 3), params(9, 2), params(7, 1, w = 10, h = 12))
        var built = 0
        for ((pi, p) in sets.withIndex()) for (seed in 0L until 100L) {
            val rl = Director(p, seed + 1000L * pi).run() ?: continue
            built++
            val lv = rl.level
            assertTrue(lv.validate().isEmpty(), "invalid level: ${lv.validate()}")
            assertEquals(lv.containers.size, rl.line.size)
            var s = GameState.initial(lv)
            for (m in rl.line) {
                assertEquals(Status.PLAYING, s.status, "seed $seed: game over before the line ended")
                val t = Engine.apply(s, m)
                assertTrue(t.events.first() is Event.Released, "seed $seed: tap ${m.containerId} did not release")
                s = t.state
                assertTrue(s.dockUsed < Limits.DOCK_SLOTS, "stable state with a full dock")
            }
            assertEquals(Status.WON, s.status, "params $pi seed $seed")
            assertTrue(ReverseAnalysis.replay(lv, rl.line))
        }
        assertTrue(built >= 250, "too few levels built: $built")
    }

    /** With fork-measuring candidates the construction is still exact, and the solver agrees it is solvable. */
    @Test
    fun candidateSelectionStaysExactAndSolvable() {
        var ok = 0
        for (seed in 0L until 10L) {
            val rl = Director(params(9, 3, candidates = 3), seed).run() ?: continue
            assertTrue(ReverseAnalysis.replay(rl.level, rl.line))
            assertTrue(Solver.solve(rl.level, 2_000_000)!!.solvable)
            ok++
        }
        assertTrue(ok >= 4, "only $ok of 10 seeds built")
    }

    @Test
    fun deterministicPerSeed() {
        val p = params(9, 3, candidates = 2)
        val a = Director(p, 7L).run()!!
        val b = Director(p, 7L).run()!!
        assertEquals(a.level, b.level)
        assertEquals(a.line, b.line)
        assertEquals(a.forks, b.forks)
    }

    /** Sustained pressure is built in: the dock holds 3 containers for a large share of the line. */
    @Test
    fun pressureIsSustained() {
        var sum = 0.0
        var n = 0
        for (seed in 0L until 10L) {
            val rl = Director(params(10, 3), seed).run() ?: continue
            sum += ReverseAnalysis.analyze(rl, withSolver = false, decisions = false).dockHigh; n++
        }
        assertTrue(n >= 6)
        assertTrue(sum / n >= 0.45, "mean dock>=3 fraction ${sum / n}")
    }

    /** Un-dispatch + un-board + un-refill: the inverse step reproduces the after-state exactly. */
    @Test
    fun inverseStepMatchesForwardTap() {
        val rng = Rng(3)
        // After-state: a waiting red L (colour 0, space 4) in slot 0, 40 staged + 10 queued without colour 0.
        val w = List(50) { 1 + it % 5 }
        val after = RevState(listOf(Docked(0, 0, 4), null, null, null), w)
        val caps = mapOf(0 to ContainerSize.L.capacity)
        // X = new instant-fill M of colour 6 whose 6 boardings reveal 3 reds for the L (it stays waiting).
        val built = assertNotNull(ReverseStep.build(after, XSpec.New(1, ContainerSize.M, 6), listOf(Recipient(0, 3)), rng, { caps[it]!! }))
        assertEquals(7, built.before.dock[0]!!.space)
        assertTrue(ForwardSim.stable(built.before))
        assertEquals(after, ForwardSim.tap(built.before, 1, 6, 6))
        // A recipient that cannot be reached in time (X boards nothing) is rejected.
        val noLoad = RevState(listOf(Docked(0, 0, 4), Docked(2, 7, 6), null, null), w)
        assertNull(ReverseStep.build(noLoad, XSpec.Existing(1), listOf(Recipient(0, 2)), rng, { if (it == 2) 6 else 10 }))
    }
}
