package com.phloots.jam.core.generator.reverse

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StallDirectorTest {
    private val sw = listOf(2.0, 1.0, 0.3)
    private val scripts = mapOf(
        "stall" to StallScript(11, 13, 9, lead = 5, stalls = listOf(StallDef(3, 4)), exitFill = 16, sizeWeights = sw, candidates = 1),
        "roll" to StallScript(11, 13, 10, lead = 5, stalls = listOf(StallDef(3, 4, partial = 0.5, roll = 1), StallDef(1, 3, partial = 0.5)),
            exitFill = 16, sizeWeights = sw, candidates = 1),
        "ir" to StallScript(11, 13, 10, lead = 5, stalls = listOf(IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 4, partOne = 6)),
            exitFill = 16, sizeWeights = sw, candidates = 1, runSizeWeights = listOf(0.0, 0.3, 1.5)),
        "ir2+squat" to StallScript(12, 14, 10, lead = 5, stalls = listOf(
            IRunDef(aDummies = 3, pre = 2, bDummies = 1, absorb = 3, aRun = 3, partOne = 6, roll = 2, partial = 0.3),
            IRunDef(aDummies = 2, pre = 1, bDummies = 1, absorb = 2, aRun = 3, partOne = 5, partial = 0.5)),
            exitFill = 18, sizeWeights = sw, candidates = 1,
            squat = SquatDef(squat = 20, bigs = 2, valves = listOf(ContainerSize.S to 1, ContainerSize.M to 1))),
    )

    private val scripts2 = mapOf(
        "steal" to StallScript(11, 13, 9, lead = 5, stalls = listOf(StallDef(3, 4, steal = true), StallDef(2, 3)), exitFill = 16, sizeWeights = sw, candidates = 1),
        "cascade" to StallScript(11, 13, 9, lead = 5, stalls = listOf(CascadeDef(2, 2), StallDef(2, 3)), exitFill = 16, sizeWeights = sw, candidates = 1),
        "keystone+gutter" to StallScript(11, 13, 9, lead = 5, stalls = listOf(StallDef(3, 4, keystone = true, domino = true)), exitFill = 18, sizeWeights = sw,
            candidates = 1, gutter = true, mirror = 2.0),
        "squatter dig" to StallScript(11, 13, 9, lead = 5, stalls = listOf(StallDef(2, 3), StallDef(3, 3, squatRun = true)), exitFill = 14, sizeWeights = sw,
            candidates = 1, squat = SquatDef(18, bigs = 0)),
        "dense" to StallScript(12, 14, 9, lead = 4, stalls = listOf(StallDef(3, 3, keystone = true, roll = 1), CascadeDef(2, 1), StallDef(2, 3, steal = true), StallDef(1, 3)),
            exitFill = 14, sizeWeights = listOf(0.5, 1.0, 1.6), candidates = 1, density = 0.9),
    )

    /** Preview-2 features: exact replay, cascades dispatch more than the tapped container, density target reached. */
    @Test
    fun preview2FeaturesReplayToWon() {
        for ((name, sc) in scripts2) {
            var built = 0
            for (seed in 0L until 16L) {
                val sl = StallDirector(sc, seed).run() ?: continue
                built++
                assertTrue(sl.level.validate().isEmpty(), "$name seed $seed: ${sl.level.validate()}")
                assertTrue(ReverseAnalysis.replay(sl.level, sl.line), "$name seed $seed does not replay")
                val m = LevelMetrics.measure(sl.level, sl.line)
                if (name == "cascade") assertTrue(m.chainCascades >= 1, "$name seed $seed: no chained cascade")
                if (name == "dense") assertTrue(m.density >= 0.85, "$name seed $seed: density ${m.density}")
                if (name == "keystone+gutter") {
                    val mid = sl.level.width / 2
                    assertTrue(sl.level.containers.none { c -> c.cells().any { it.col == mid } }, "gutter occupied")
                }
            }
            assertTrue(built >= 4, "$name: only $built of 16 built")
        }
    }

    /** Every built level replays through the real Engine to WON along its intended line. */
    @Test
    fun hybridLinesReplayToWon() {
        for ((name, sc) in scripts) {
            var built = 0
            for (seed in 0L until 30L) {
                val sl = StallDirector(sc, seed).run() ?: continue
                built++
                assertTrue(sl.level.validate().isEmpty(), "$name seed $seed: ${sl.level.validate()}")
                var s = GameState.initial(sl.level)
                for (m in sl.line) {
                    val t = Engine.apply(s, m)
                    assertTrue(t.events.first() is Event.Released, "$name seed $seed: tap ${m.containerId} did not release")
                    s = t.state
                }
                assertEquals(Status.WON, s.status, "$name seed $seed")
            }
            assertTrue(built >= 12, "$name: only $built of 30 built")
        }
    }

    /** Dummies are dead weight when docked: none fills on docking (its colour is not fully staged). */
    @Test
    fun dummiesWaitWhenDocked() {
        for ((name, sc) in scripts) for (seed in 0L until 8L) {
            val sl = StallDirector(sc, seed).run() ?: continue
            val dummyIds = sl.stalls.flatMap { st -> st.dummies.map { it[0] } }.toSet()
            assertTrue(dummyIds.isNotEmpty())
            var s = GameState.initial(sl.level)
            for (m in sl.line) {
                s = Engine.apply(s, m).state
                if (m.containerId in dummyIds) assertTrue(s.dock.any { it?.containerId == m.containerId }, "$name seed $seed: dummy ${m.containerId} did not wait")
            }
        }
    }

    /** The squatter occupies a large share of staging in mid-level. */
    @Test
    fun squatterShrinksStaging() {
        val sc = scripts.getValue("ir2+squat")
        var n = 0
        for (seed in 0L until 10L) {
            val sl = StallDirector(sc, seed).run() ?: continue
            val q = sl.squat!!.color
            var s = GameState.initial(sl.level)
            for (m in sl.line.take(sl.line.size / 3)) s = Engine.apply(s, m).state
            assertTrue(s.staging.count { it == q } >= 12, "seed $seed: only ${s.staging.count { it == q }} squatters staged")
            assertTrue(sl.labels().any { it.startsWith("Squatter") })
            n++
        }
        assertTrue(n >= 4)
    }

    @Test
    fun deterministicWithCandidates() {
        val sc = scripts.getValue("roll").copy(candidates = 2)
        val a = StallDirector(sc, 3L).run()
        val b = StallDirector(sc, 3L).run()
        assertEquals(a?.level, b?.level)
        assertEquals(a?.line, b?.line)
        if (a != null) assertTrue(HybridGenerator.quick(a.level, a.line) != null)
    }

    @Test
    fun labelsDescribeInterruptedRuns() {
        val sc = scripts.getValue("ir")
        val sl = (0L until 10L).firstNotNullOf { StallDirector(sc, it).run() }
        assertTrue(sl.labels().any { it.startsWith("InterruptedRun ") && "dummies B:1 A:2+1 slack" in it }, "${sl.labels()}")
        assertTrue(sl.line.isNotEmpty() && sl.line.all { it is Move })
    }
}
