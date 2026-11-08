package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.solver.Solver
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FairnessTest {
    private fun s(id: Int, color: Int, row: Int, col: Int, dir: Dir) = Container(id, ContainerSize.S, color, row, col, dir)

    /**
     * 3x2 board, 3 staging spots, every container S (4 elements):
     * ```
     * row 0:  c2(1)->  c0(1)->  .
     * row 1:  c1(1)^   <-c3(0)  c4(1)->
     * ```
     * Only c0 and c4 (both colour 1) can leave. The four colour-0 elements belong to c3, at the bottom of the chain
     * c0 -> c2 -> c1 -> c3. With the real queue, docking c4 first loses (4 moves later: the zeros fill the carousel
     * before c3 is dug out); under almost every other order of the hidden queue it wins just as c0 does. Nothing on
     * show tells the two apart: a blind trap.
     */
    private val trap = Level(
        1, 3, 2,
        listOf(s(0, 1, 0, 1, Dir.RIGHT), s(1, 1, 1, 0, Dir.UP), s(2, 1, 0, 0, Dir.RIGHT), s(3, 0, 1, 1, Dir.LEFT), s(4, 1, 1, 2, Dir.RIGHT)),
        listOf(0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        stagingSpots = 3,
    )

    private val blindCfg = FairnessConfig(shown = 0, horizon = Int.MAX_VALUE, samples = 24, maxDraws = 200, budget = 200_000, parentBudget = 200_000)

    @Test
    fun blindTrapIsUnfair() {
        val f = Fairness.state(GameState.initial(trap), 0, blindCfg)
        assertEquals(listOf(0, 4), f.moves.map { it.containerId })
        assertEquals(1, f.w)
        val loser = f.moves.single { !it.wins }
        assertEquals(4, loser.containerId)
        assertTrue(loser.h > blindCfg.deep, "c4 should lose beyond the look-ahead, h=${loser.h}")
        assertTrue(f.decision)
        assertTrue(loser.winShare >= blindCfg.robust, "c4 should look safe from what is on show: p=${loser.winShare}")
        assertEquals(1, f.traps)
        assertTrue(f.unfair)
        assertFalse(f.fair)
        assertEquals(1, f.deepBlindLosers)
        assertTrue(f.blindBits > 0.99)
    }

    @Test
    fun theSameTrapOnShowIsFair() {
        // A belt that shows the whole queue: nothing is hidden, so the loss of c4 follows from what is on show.
        val f = Fairness.state(GameState.initial(trap), 0, blindCfg.copy(shown = 20))
        assertTrue(f.decision)
        assertEquals(0, f.hiddenTail)
        assertTrue(f.fair)
        assertEquals(1, f.wRobust)
        assertEquals(0, f.deepBlindLosers)
        assertEquals(0.0, f.blindBits, 1e-9)
    }

    /**
     * Two interchangeable winning moves: c0 and c1 are both colour 0, both free, and the queue holds their 8
     * elements followed by colour 1 for c2, which they do not block.
     */
    @Test
    fun interchangeableMovesCountAsOptions() {
        val lv = Level(1, 3, 2, listOf(s(0, 0, 0, 0, Dir.UP), s(1, 0, 0, 2, Dir.UP), s(2, 1, 1, 1, Dir.DOWN)),
            List(8) { 0 } + List(4) { 1 }, stagingSpots = 3)
        val line = Solver.solve(lv).solution
        val m = Fairness.measure(lv, line, FairnessConfig(shown = 0, horizon = Int.MAX_VALUE))
        val first = m.states.first()
        assertEquals(3, first.b)
        assertEquals(3, first.w)
        assertTrue(first.fair)
        assertEquals(1.0, m.multiShare, 1e-9)
        assertTrue(m.meanW >= 2.0)
        assertTrue(m.unfair.isEmpty())
        assertEquals(1.0, m.fairShare, 1e-9)
        // the quality metrics come out of the same pass
        assertEquals(m.states.size, m.quality.states.size)
        assertEquals(0.0, m.quality.hidden, 1e-9)
    }

    @Test
    fun hiddenTailSamplingKeepsTheMultisetAndThePrefix() {
        val lv = trap
        val rnd = Random(5)
        for (h in listOf(0, 3, 10, Int.MAX_VALUE)) repeat(20) {
            val q = Fairness.sampleTail(lv, 6, h, rnd).queue
            assertEquals(lv.queue.subList(0, 6), q.subList(0, 6))
            assertEquals(lv.queue.sorted(), q.sorted())
        }
        // h = 0 keeps the real order
        assertEquals(lv.queue, Fairness.sampleTail(lv, 6, 0, rnd).queue)
        assertTrue(Fairness.tailFixed(lv, 11)) // only colour 1 left after index 11
        assertFalse(Fairness.tailFixed(lv, 0))
    }

    @Test
    fun orderDependenceCountsWinnableOrders() {
        val start = GameState.initial(trap)
        val (w, l, u) = Fairness.orderDependence(start, 0, Int.MAX_VALUE, 32, budget = 200_000)
        assertEquals(32, w + l + u)
        // the trap is in the decision, not in the level: from the start almost every order can be won
        assertTrue(w >= 24, "$w won, $l lost, $u undecided")
        // with the whole queue on show nothing is drawn: the real order wins
        assertEquals(Triple(32, 0, 0), Fairness.orderDependence(start, 20, Int.MAX_VALUE, 32, budget = 200_000))
    }

    @Test
    fun sampledLinesWinAndGiveReleaseWindows() {
        val lines = Fairness.sampleLines(trap, 8, seed = 3, budget = 200_000)
        assertTrue(lines.isNotEmpty())
        for (l in lines) assertTrue(ReverseAnalysis.replay(trap, l))
        val w = Fairness.releaseWindows(trap, lines, shown = 0)
        assertEquals(trap.containers.size, w.moves)
        // c3 is at the bottom of the chain c0 -> c2 -> c1 -> c3: never before its three blockers
        assertTrue(w.earliest[3] >= 3)
        assertTrue(w.earliest[0] < w.earliest[2] && w.earliest[2] < w.earliest[1])
        assertTrue(w.width.all { it >= 0 })
        assertTrue(w.meanConcurrency >= 1.0)
        // the solver's own line is one of the possible orders
        assertTrue(ReverseAnalysis.replay(trap, Solver.solve(trap).solution.map { Move(it.containerId) }))
    }
}
