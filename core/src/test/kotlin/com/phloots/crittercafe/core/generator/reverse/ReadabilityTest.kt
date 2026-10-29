package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.solver.Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadabilityTest {
    private fun s(id: Int, color: Int, row: Int, col: Int, dir: Dir) = Container(id, ContainerSize.S, color, row, col, dir)

    /** [FairnessTest]'s blind trap: the colour-0 batch that decides the opening fork sits deep in the queue. */
    private val trap = Level(
        1, 3, 2,
        listOf(s(0, 1, 0, 1, Dir.RIGHT), s(1, 1, 1, 0, Dir.UP), s(2, 1, 0, 0, Dir.RIGHT), s(3, 0, 1, 1, Dir.LEFT), s(4, 1, 1, 2, Dir.RIGHT)),
        listOf(0, 1, 1, 0, 1, 0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
        stagingSpots = 3,
    )

    @Test
    fun everyElementBoardsOnceInFifoOrderPerColour() {
        val line = Solver.solve(trap).solution
        val (qi, b) = Readability.boarded(trap, line)
        assertEquals(line.size, b.size)
        val all = b.flatMap { it.toList() }
        assertEquals(trap.queue.indices.toList(), all.sorted(), "each queue element boards exactly once")
        // per colour, elements board in queue order (the oldest staged element of a colour boards first)
        for (c in trap.queue.distinct()) {
            val seq = all.filter { trap.queue[it] == c }
            assertEquals(seq.sorted(), seq, "colour $c")
        }
        assertEquals(trap.stagingSpots, qi[0])
        assertTrue(qi.toList().zipWithNext().all { (x, y) -> x <= y })
    }

    @Test
    fun theDecidingBatchIsBeyondAShortBeltAndOnShowWithALongOne() {
        val line = Solver.solve(trap).solution
        val q = Quality.measure(trap, line)
        val short = Readability.arrivals(trap, line, q.states, shown = 0)
        assertTrue(short.forks.isNotEmpty())
        val first = short.forks.first()
        assertEquals(0, first.step)
        assertFalse(first.clued)
        assertTrue(first.lag > 0)
        assertTrue(0 in first.colors, "the colour-0 batch decides the opening fork: ${first.colors}")
        val long = Readability.arrivals(trap, line, q.states, shown = trap.queue.size)
        assertTrue(long.forks.all { it.clued })
        assertEquals(1.0, long.cluedShare, 1e-9)
        assertEquals(0, long.maxLag)
    }

    /** On generated levels the repair keeps the line a win, only re-orders the queue, and never touches a fork's carousel. */
    @Test
    fun repairKeepsTheLineAndTheColourTotals() {
        val sc = StallScript(12, 14, 9, lead = 4, stalls = listOf(StallDef(3, 3, keystone = true, roll = 1), CascadeDef(2, 1), StallDef(2, 3, steal = true), StallDef(1, 3)),
            exitFill = 14, sizeWeights = listOf(0.5, 1.0, 1.6), candidates = 1, density = 0.9, queuePreview = 4)
        var repaired = 0
        var improved = 0
        var built = 0
        var unclued = 0
        for (seed in 0L until 6L) {
            val sl = StallDirector(sc, seed).run() ?: continue
            assertEquals(4, sl.level.queuePreview)
            val q = Quality.measure(sl.level, sl.line, budget = 4_000)
            val rr = Readability.repair(sl.level, sl.line, q.states)
            built++
            unclued += rr.before.unclued
            assertTrue(Readability.wins(rr.level, sl.line), "seed $seed: the line must still win")
            assertEquals(sl.level.queue.groupingBy { it }.eachCount(), rr.level.queue.groupingBy { it }.eachCount())
            assertEquals(sl.level.containers, rr.level.containers)
            if (!rr.changed) { assertEquals(sl.level, rr.level); continue }
            repaired++
            val firstFork = rr.before.forks.first { !it.clued }.step
            val (qi, _) = Readability.boarded(sl.level, sl.line)
            assertEquals(sl.level.queue.subList(0, qi[firstFork]), rr.level.queue.subList(0, qi[firstFork]), "seed $seed: the queue before the first repaired fork moved")
            if (rr.after.forks.sumOf { it.hiddenBoarded } < rr.before.forks.sumOf { it.hiddenBoarded }) improved++
        }
        assertTrue(repaired >= 1, "no level needed a repair (built $built, unclued forks $unclued)")
        assertTrue(improved >= 1, "the repair never reduced the hidden part of a deciding batch")
    }
}
