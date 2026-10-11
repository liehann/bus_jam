package com.phloots.jam.core.generator.reverse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityTest {
    @Test
    fun bitsCountOnlyLosersBeyondTheHorizon() {
        // 8 legal moves: 2 win, 6 lose with horizons 1, 1, 1, 2, 3 and deeper than the cap.
        val s = QState(0, b = 8, w = 2, unknown = 0, loserH = intArrayOf(1, 1, 1, 2, 3, Quality.DEEP), freeDocks = 2, queueLeft = 10)
        assertEquals(2.0, s.bits, 1e-9)
        assertEquals(QState.log2(5.0 / 2.0), s.bitsBeyond(1), 1e-9)
        assertEquals(QState.log2(3.0 / 2.0), s.bitsBeyond(3), 1e-9)
        assertEquals(0.0, QState(0, 1, 1, 0, IntArray(0), 4, 0).bits, 1e-9)
    }

    /** The herald keeps the climax colour on show long before it floods; quality metrics are consistent on the line. */
    @Test
    fun heraldedClimaxIsVisibleEarly() {
        val sc = StallScript(11, 13, 8, lead = 1, stalls = listOf(JuggleDef(taps = 6, hold = 2, fill = 0.2), StallDef(2, 3, partial = 0.5),
            StallDef(2, 4, partial = 0.7, squatRun = true)), exitFill = 12, sizeWeights = listOf(1.0, 1.0, 0.8), candidates = 1,
            squat = SquatDef(10, bigs = 0, valves = listOf(com.phloots.jam.core.model.ContainerSize.S to 1), rampTaps = 8, valveCover = 2))
        var built = 0
        var visibleEarly = 0
        for (seed in 0L until 12L) {
            if (built >= 3) break
            val sl = StallDirector(sc, seed).run() ?: continue
            built++
            assertTrue(ReverseAnalysis.replay(sl.level, sl.line), "seed $seed does not replay")
            val q = Quality.measure(sl.level, sl.line, budget = 8_000)
            assertEquals(sl.line.size, q.states.size)
            assertTrue(q.states.all { it.w >= 1 }, "seed $seed: a state on the winning line without a winning move")
            assertTrue(q.hidden <= q.bits + 1e-9 && q.deep <= q.hidden + 1e-9, "seed $seed: bits out of order")
            if (q.chainLead >= 8) visibleEarly++
            val a = HybridGenerator.axes(q)
            for (x in listOf(a.info, a.opening, a.readable, a.slack)) assertTrue(x in 0.0..1.0, "seed $seed: axis $x")
        }
        assertTrue(built >= 2, "only $built built")
        assertTrue(visibleEarly >= built - 1, "herald visible early in only $visibleEarly of $built")
    }
}
