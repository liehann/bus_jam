package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The clued-squeeze construction dial ([CluedDef]): levelgen/out/clued-squeezes.md. */
class CluedSqueezesTest {
    /** A juggle opening, a stall and a climax stall with decoy exit fillers: the recipes' shape at test size. */
    private val base = StallScript(11, 13, 9, lead = 1, stalls = listOf(JuggleDef(taps = 6, hold = 2, fill = 0.25), StallDef(3, 3, partial = 0.5), StallDef(2, 4, partial = 0.7)),
        exitFill = 12, sizeWeights = listOf(1.0, 1.0, 0.8), dummySizeWeights = listOf(0.4, 1.0, 1.2), candidates = 3, openCandidates = 4, openSpan = 20,
        coverStaged = 2.0, mismatch = 1.0, climaxAt = 0.65, queuePreview = 16)
    private val clued = base.copy(clued = CluedDef(weight = 3.0, coverDead = 10.0))

    private fun states(level: Level, line: List<Move>): List<GameState> {
        val out = ArrayList<GameState>()
        var s = GameState.initial(level)
        for (m in line) { out += s; s = Engine.apply(s, m).state }
        return out
    }

    /** Reachable containers whose colour is nowhere on show (carousel + belt), summed over the line's states. */
    private fun deadReachable(level: Level, line: List<Move>): Int = states(level, line).sumOf { s ->
        val onShow = HashSet(s.staging)
        for (k in s.queueIndex until minOf(level.queue.size, s.queueIndex + level.queuePreview)) onShow += level.queue[k]
        Engine.legalMoves(s).count { level.containers[it.containerId].color !in onShow }
    }

    @Test
    fun theDialIsOffByDefault() {
        assertEquals(null, base.clued)
        // An explicit null builds exactly the default level (same rng stream, same choices).
        for (seed in 0L until 4L) {
            val a = StallDirector(base, seed).run()
            val b = StallDirector(base.copy(clued = null), seed).run()
            assertEquals(a?.level, b?.level)
            assertEquals(a?.line, b?.line)
        }
    }

    /** [Readability.hiddenBoarded] from the initial state agrees with [Readability.arrivals] on every fork's first step. */
    @Test
    fun hiddenBoardedFromTheInitialStateMatchesArrivals() {
        var checked = 0
        for (seed in 0L until 8L) {
            val sl = StallDirector(base, seed).run() ?: continue
            val q = Quality.measure(sl.level, sl.line)
            val a = Readability.arrivals(sl.level, sl.line, q.states)
            val f = a.forks.firstOrNull { it.step == 0 } ?: continue
            val s0 = GameState.initial(sl.level)
            assertEquals(f.hiddenBoarded, Readability.hiddenBoarded(s0, sl.line, f.window, s0.queueIndex + sl.level.queuePreview), "seed $seed")
            checked++
        }
        assertTrue(checked >= 1, "no level with a fork at step 0")
    }

    /**
     * Clued construction: every level replays to WON and validates; over the seeds it leaves fewer reachable
     * containers whose colour is not on show (the deep losers that stretch a fork's loss window past the belt), and
     * its forks are clued more often on the construction line.
     */
    @Test
    fun cluedConstructionHidesDeadContainersAndReadsMoreForks() {
        var deadBase = 0; var deadRead = 0; var shareBase = 0.0; var shareRead = 0.0; var n = 0
        for (seed in 0L until 24L) {
            val a = StallDirector(base, seed).run() ?: continue
            val b = StallDirector(clued, seed).run() ?: continue
            for (sl in listOf(a, b)) {
                assertTrue(sl.level.validate().isEmpty(), "seed $seed: ${sl.level.validate()}")
                assertTrue(ReverseAnalysis.replay(sl.level, sl.line), "seed $seed does not replay")
            }
            deadBase += deadReachable(a.level, a.line); deadRead += deadReachable(b.level, b.line)
            shareBase += Readability.arrivals(a.level, a.line, Quality.measure(a.level, a.line).states).cluedShare
            shareRead += Readability.arrivals(b.level, b.line, Quality.measure(b.level, b.line).states).cluedShare
            n++
        }
        assertTrue(n >= 4, "only $n seeds built both ways")
        assertTrue(deadRead < deadBase, "dead reachable: clued $deadRead vs default $deadBase")
        assertTrue(shareRead >= shareBase, "clued share: clued ${shareRead / n} vs default ${shareBase / n}")
    }

    /**
     * Early emission ([CluedDef.runBudget]): with a budget of one target the stall's dummies complete on the
     * target's tap (the dispatches of the dummies happen during it), and the run follows on a free dock.
     */
    @Test
    fun runBudgetCompletesTheDummiesOnTheTargetTap() {
        val sc = StallScript(11, 13, 9, lead = 5, stalls = listOf(StallDef(3, 4)), exitFill = 16, sizeWeights = listOf(2.0, 1.0, 0.3), candidates = 1,
            clued = CluedDef(weight = 0.0, deepOnlyClued = false, runBudget = 10))
        var built = 0
        for (seed in 0L until 16L) {
            val sl = StallDirector(sc, seed).run() ?: continue
            built++
            assertTrue(ReverseAnalysis.replay(sl.level, sl.line), "seed $seed does not replay")
            val info = sl.stalls.first()
            val target = info.target
            val dummyIds = info.dummies.map { it[0] }.toSet()
            var s = GameState.initial(sl.level)
            var dispatchedAtTarget = emptySet<Int>()
            for (m in sl.line) {
                val t = Engine.apply(s, m)
                if (m.containerId == target) dispatchedAtTarget = t.events.filterIsInstance<Event.Dispatched>().map { it.containerId }.toSet()
                s = t.state
            }
            assertEquals(Status.WON, s.status)
            assertTrue(dispatchedAtTarget.containsAll(dummyIds), "seed $seed: dummies $dummyIds, dispatched at the target $dispatchedAtTarget")
        }
        assertTrue(built >= 4, "only $built of 16 built")
    }
}
