package com.phloots.jam.core.engine

import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.RefEngine
import com.phloots.jam.core.solver.TestLevels
import com.phloots.jam.core.solver.TestLevels.small
import com.phloots.jam.core.solver.TestLevels.v
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EngineTest {
    private fun q(vararg runs: Pair<Int, Int>) = runs.flatMap { (c, k) -> List(k) { c } }

    private fun boardedTo(events: List<Event>) = events.filterIsInstance<Event.Boarded>().map { it.containerId }

    @Test
    fun releasesInEachDirectionWhenRayClear() {
        for (dir in Dir.entries) {
            val level = Level(1, 5, 5, listOf(small(0, 0, 2, 2, dir)), q(0 to 4))
            val t = Engine.apply(GameState.initial(level), Move(0))
            assertEquals(Event.Released(0, 0), t.events.first(), "dir $dir")
            assertEquals(Status.WON, t.state.status, "dir $dir")
        }
    }

    @Test
    fun blockStopsBeforeBlockerInEachDirection() {
        data class Case(val dir: Dir, val blockerFront: Pair<Int, Int>, val blockerDir: Dir, val stop: Pair<Int, Int>)
        val cases = listOf(
            Case(Dir.UP, 0 to 2, Dir.RIGHT, 1 to 2),
            Case(Dir.DOWN, 4 to 2, Dir.RIGHT, 3 to 2),
            Case(Dir.LEFT, 2 to 0, Dir.UP, 2 to 1),
            Case(Dir.RIGHT, 2 to 4, Dir.UP, 2 to 3),
        )
        for (c in cases) {
            val mover = small(0, 0, 2, 2, c.dir)
            val blocker = small(1, 1, c.blockerFront.first, c.blockerFront.second, c.blockerDir)
            val level = Level(1, 5, 5, listOf(mover, blocker), q(0 to 4, 1 to 4))
            val s = GameState.initial(level)
            val t = Engine.apply(s, Move(0))
            assertEquals(listOf<Event>(Event.Blocked(0, c.stop.first, c.stop.second, 1)), t.events, "dir ${c.dir}")
            assertSame(s, t.state)
            assertFalse(Engine.isTappable(s, 0))
        }
    }

    @Test
    fun blockOfLongContainerReportsFrontCellAtStop() {
        // L container (front (4,1), facing UP, body (4,1),(5,1),(6,1)) blocked by an S container at (1,1).
        val level = Level(
            1, 3, 7,
            listOf(v(0, ContainerSize.L, 0, 4, 1, Dir.UP), small(1, 1, 1, 1, Dir.LEFT)),
            q(0 to 10, 1 to 4),
        )
        assertEquals(Event.Blocked(0, 2, 1, 1), Engine.apply(GameState.initial(level), Move(0)).events.single())
    }

    @Test
    fun blockAgainstAdjacentBlockerStopsAtFrontCell() {
        val mover = small(0, 0, 2, 1, Dir.RIGHT)
        val blocker = small(1, 1, 2, 2, Dir.UP)
        val level = Level(1, 5, 5, listOf(mover, blocker), q(0 to 4, 1 to 4))
        assertEquals(listOf<Event>(Event.Blocked(0, 2, 1, 1)), Engine.apply(GameState.initial(level), Move(0)).events)
    }

    @Test
    fun blockReportsNearestOnBoardBlocker() {
        // Ray of container 0 (facing RIGHT from (0,1)) crosses M container 1 (cells (1,2),(0,2)) and M container 2
        // (cells (1,4),(0,4)).
        val level = Level(
            1, 5, 5,
            listOf(
                small(0, 0, 0, 1, Dir.RIGHT),
                v(1, ContainerSize.M, 1, 1, 2, Dir.DOWN),
                v(2, ContainerSize.M, 2, 1, 4, Dir.DOWN),
            ),
            q(1 to 6, 0 to 4, 2 to 6),
        )
        var s = GameState.initial(level)
        assertEquals(Event.Blocked(0, 0, 1, 1), Engine.apply(s, Move(0)).events.single())
        s = Engine.apply(s, Move(1)).state // container 1 leaves downward, fills from staging and dispatches
        assertNull(s.dock[0])
        assertEquals(Event.Blocked(0, 0, 3, 2), Engine.apply(s, Move(0)).events.single())
    }

    // ---- Staging scenarios. Each is [padded]: OFF filler elements keep staging spots 0 until OFF busy, so exactly
    // WINDOW (7) spots take part, whatever the level's staging size is. Queue indices and spots shift by OFF. Each
    // scenario runs for every staging size in TestLevels.SPOTS (20, 28, 40).

    private var spots = Limits.MAX_STAGING_SPOTS
    private val off get() = TestLevels.off(spots)

    private fun eachSpots(block: () -> Unit) {
        for (n in TestLevels.SPOTS) {
            spots = n
            try { block() } catch (e: AssertionError) { throw AssertionError("stagingSpots=$n: ${e.message}", e) }
        }
        spots = Limits.MAX_STAGING_SPOTS
    }

    private fun pad(level: Level) = TestLevels.padded(level, spots)

    private fun b(e: Int, spot: Int, slot: Int, id: Int) = Event.Boarded(e + off, spot + off, slot, id)
    private fun st(e: Int, spot: Int) = Event.Staged(e + off, spot + off)
    private val fillerStaging get() = List(off) { TestLevels.FILLER }

    /** Releases the filler containers of a padded [level] (built from one with [n] containers) and expects a win. */
    private fun finish(state: GameState, n: Int): GameState {
        var s = state
        for (id in TestLevels.fillerIds(n, spots)) s = Engine.apply(s, Move(id)).state
        assertEquals(Status.WON, s.status)
        return s
    }

    @Test
    fun initialStagingIsFirstStagingSpotsElements() = eachSpots {
        val k = spots
        val vs = (0 until 20).map { small(it, it % 5, it / 10, it % 10, Dir.UP) }
        val level = Level(1, 10, 2, vs, vs.flatMap { c -> List(4) { c.color } }.shuffled(Random(1)), spots)
        val s = GameState.initial(level)
        assertEquals(level.queue.take(k), s.staging)
        assertEquals(minOf(k, level.queue.size), s.queueIndex)
        assertEquals((0 until minOf(k, 80)).toList(), s.stagedElementIndices())
    }

    @Test
    fun stagingRefillsInQueueOrderAndOldestMatchBoards() = eachSpots {
        // S colour 0 (id 0) and L colour 1 (id 1). Colour-0 elements at queue indices 0, 2, 4, 6.
        val level = pad(
            Level(1, 2, 3, listOf(small(0, 0, 0, 0, Dir.UP), v(1, ContainerSize.L, 1, 0, 1, Dir.UP)), listOf(0, 1, 0, 1, 0, 1, 0) + List(7) { 1 }),
        )
        val s0 = GameState.initial(level)
        assertEquals(fillerStaging + listOf(0, 1, 0, 1, 0, 1, 0), s0.staging)
        val t = Engine.apply(s0, Move(0))
        assertEquals(
            listOf(
                Event.Released(0, 0),
                b(0, 0, 0, 0), st(7, 6),
                b(2, 1, 0, 0), st(8, 6),
                b(4, 2, 0, 0), st(9, 6),
                b(6, 3, 0, 0), Event.Dispatched(0, 0), st(10, 6),
            ),
            t.events,
        )
        val s = t.state
        assertEquals(fillerStaging + List(7) { 1 }, s.staging)
        assertEquals(List(off) { it } + listOf(1, 3, 5, 7, 8, 9, 10).map { it + off }, s.stagedElementIndices())
        assertEquals(11 + off, s.queueIndex)
        assertEquals(List(4) { null }, s.dock)
        // The L container takes all 10 colour-1 elements, oldest first; the queue then drains.
        val t2 = Engine.apply(s, Move(1))
        assertEquals(List(10) { 1 }, boardedTo(t2.events))
        assertEquals(listOf(1, 3, 5, 7, 8, 9, 10, 11, 12, 13).map { it + off }, t2.events.filterIsInstance<Event.Boarded>().map { it.elementIndex })
        assertEquals(3 + TestLevels.trail(spots), t2.events.filterIsInstance<Event.Staged>().size)
        finish(t2.state, 2)
    }

    @Test
    fun oldestStagedElementOfTheColourBoards() = eachSpots {
        val level = pad(
            Level(1, 2, 1, listOf(small(0, 0, 0, 0, Dir.UP), small(1, 1, 0, 1, Dir.UP)), q(1 to 2, 0 to 1, 1 to 2, 0 to 3)),
        )
        // window [1,1,0,1,1,0,0]: the colour-0 element at window spot 2 (queue index 2) is the oldest and boards first.
        val t = Engine.apply(GameState.initial(level), Move(0))
        assertEquals(b(2, 2, 0, 0), t.events[1])
        assertEquals(listOf(2, 5, 6, 7).map { it + off }, t.events.filterIsInstance<Event.Boarded>().map { it.elementIndex })
    }

    @Test
    fun leftContainerFillsBeforeRightOfSameColour() = eachSpots {
        // c0 = M colour 0 (slot 0), c1 = S colour 0 (slot 1) docked while no colour 0 is staged; c2 (colour 1)
        // then lets the colour-0 elements flow: c0 takes all 6 before c1 gets any.
        val level = pad(
            Level(
                1, 4, 2,
                listOf(
                    v(0, ContainerSize.M, 0, 0, 0, Dir.UP),
                    small(1, 0, 0, 1, Dir.UP),
                    small(2, 1, 0, 2, Dir.UP),
                    small(3, 2, 0, 3, Dir.UP),
                ),
                q(1 to 4, 2 to 4, 0 to 10),
            ),
        )
        var s = GameState.initial(level)
        s = Engine.apply(s, Move(0)).state
        s = Engine.apply(s, Move(1)).state
        assertEquals(listOf(DockedContainer(0, 0, 6), DockedContainer(1, 0, 4), null, null), s.dock)
        val t = Engine.apply(s, Move(2))
        val colour0 = t.events.filterIsInstance<Event.Boarded>().filter { it.containerId != 2 }.map { it.containerId }
        assertEquals(List(6) { 0 } + List(4) { 1 }, colour0)
        assertEquals(listOf(null, null, null, null), t.state.dock)
        finish(Engine.apply(t.state, Move(3)).state, 4)
    }

    @Test
    fun laterContainerInFreedLeftSlotTakesPriorityEvenIfRightNeedsOne() = eachSpots {
        // c0 = S colour 2, c1 = S colour 0, c2 = M colour 0, c3 = L colour 1.
        val level = pad(
            Level(
                1, 4, 3,
                listOf(
                    small(0, 2, 0, 0, Dir.UP),
                    small(1, 0, 0, 1, Dir.UP),
                    v(2, ContainerSize.M, 0, 0, 2, Dir.UP),
                    v(3, ContainerSize.L, 1, 0, 3, Dir.UP),
                ),
                q(0 to 3, 1 to 4, 2 to 4, 1 to 3, 0 to 7, 1 to 3),
            ),
        )
        var s = GameState.initial(level)
        s = Engine.apply(s, Move(0)).state // slot 0, nothing staged for colour 2
        val t1 = Engine.apply(s, Move(1)) // slot 1: c1 takes 3 zeros; the refills bring c0's four 2s -> c0 dispatches
        assertEquals(
            listOf(
                Event.Released(1, 1),
                b(0, 0, 1, 1), st(7, 6),
                b(7, 6, 0, 0), st(8, 6),
                b(8, 6, 0, 0), st(9, 6),
                b(9, 6, 0, 0), st(10, 6),
                b(10, 6, 0, 0), Event.Dispatched(0, 0), st(11, 6),
                b(1, 0, 1, 1), st(12, 6),
                b(2, 0, 1, 1), st(13, 6),
            ),
            t1.events,
        )
        s = t1.state
        assertEquals(listOf(null, DockedContainer(1, 0, 1), null, null), s.dock)
        val t2 = Engine.apply(s, Move(2)) // M colour 0 takes the freed slot 0, left of c1
        assertEquals(listOf(Event.Released(2, 0)), t2.events)
        s = t2.state
        val t3 = Engine.apply(s, Move(3)) // colour 1 flows; the colour-0 elements it uncovers go to c2 first
        val colour0 = t3.events.filterIsInstance<Event.Boarded>().filter { it.containerId != 3 }.map { it.containerId }
        assertEquals(List(6) { 2 } + listOf(1), colour0)
        val c1Dispatch = t3.events.indexOf(Event.Dispatched(1, 1))
        val c2Dispatch = t3.events.indexOf(Event.Dispatched(0, 2))
        assertTrue(c2Dispatch in 0 until c1Dispatch)
        finish(t3.state, 4)
    }

    @Test
    fun partialBoardingKeepsContainerDocked() = eachSpots {
        val level = pad(
            Level(1, 2, 2, listOf(v(0, ContainerSize.M, 0, 0, 0, Dir.UP), small(1, 1, 0, 1, Dir.UP)), q(0 to 2, 1 to 4, 0 to 4)),
        )
        val t = Engine.apply(GameState.initial(level), Move(0))
        // window [0,0,1,1,1,1,0]: three colour-0 staged, then refills (indices 7..9, colour 0) board too.
        val expected = mutableListOf<Event>(
            Event.Released(0, 0),
            b(0, 0, 0, 0), st(7, 6),
            b(1, 0, 0, 0), st(8, 6),
            b(6, 4, 0, 0), st(9, 6),
            b(7, 4, 0, 0),
        )
        // After the last real element the trailing filler elements (if any) are staged.
        for (k in 0 until minOf(TestLevels.trail(spots), 1)) expected += st(10, 6)
        expected += listOf(b(8, 4, 0, 0))
        for (k in 1 until minOf(TestLevels.trail(spots), 2)) expected += st(11, 6)
        expected += listOf(b(9, 4, 0, 0), Event.Dispatched(0, 0))
        // a third trailing filler refills the spot freed by the last boarding (after the dispatch)
        if (TestLevels.trail(spots) >= 3) expected += st(12, 6)
        assertEquals(expected, t.events)

        // Partial: the L takes the 3 staged zeros, the refills bring colour 1 only; it stays docked.
        val lvl = pad(
            Level(1, 2, 3, listOf(v(0, ContainerSize.L, 0, 0, 0, Dir.UP), v(1, ContainerSize.L, 1, 0, 1, Dir.UP)), q(0 to 3, 1 to 10, 0 to 7)),
        )
        val t5 = Engine.apply(GameState.initial(lvl), Move(0))
        assertEquals(DockedContainer(0, 0, 7), t5.state.dock[0])
        assertEquals(fillerStaging + List(7) { 1 }, t5.state.staging)
        assertEquals(Status.PLAYING, t5.state.status)
    }

    @Test
    fun dispatchFreesSlotAndNextReleaseTakesLeftmostHole() = eachSpots {
        // c0 (S colour 0) docks in slot 0, c1 (M colour 1) in slot 1; c1's first element uncovers c0's colour, c0
        // fills and dispatches, leaving a hole at slot 0 left of the partially filled c1.
        val level = pad(
            Level(
                1, 4, 2,
                listOf(small(0, 0, 0, 0, Dir.UP), v(1, ContainerSize.M, 1, 0, 1, Dir.UP), small(2, 2, 0, 2, Dir.UP), small(3, 3, 0, 3, Dir.UP)),
                q(2 to 4, 3 to 2, 1 to 1, 0 to 4, 1 to 3, 3 to 2, 1 to 2),
            ),
        )
        var s = GameState.initial(level)
        s = Engine.apply(s, Move(0)).state // window [2,2,2,2,3,3,1]: no colour 0 staged
        assertEquals(DockedContainer(0, 0, 4), s.dock[0])
        val t = Engine.apply(s, Move(1))
        assertTrue(Event.Dispatched(0, 0) in t.events)
        s = t.state
        assertEquals(listOf(null, DockedContainer(1, 1, 2), null, null), s.dock)
        assertEquals(fillerStaging + listOf(2, 2, 2, 2, 3, 3, 3), s.staging)
        val t2 = Engine.apply(s, Move(2))
        assertEquals(Event.Released(2, 0), t2.events.first())
        finish(Engine.apply(t2.state, Move(3)).state, 4)
    }

    @Test
    fun winWhenEverythingEmpty() {
        val level = Level(1, 3, 1, listOf(small(0, 0, 0, 0, Dir.UP), small(1, 1, 0, 1, Dir.UP), small(2, 2, 0, 2, Dir.UP)), q(0 to 4, 1 to 4, 2 to 4))
        val s = TestLevels.play(level, (0..2).map { Move(it) })
        assertEquals(Status.WON, s.status)
        assertTrue(s.onBoard.isEmpty() && s.staging.isEmpty() && s.dockUsed == 0 && s.queueRemaining == 0)
        assertEquals(emptyList(), Engine.legalMoves(s))
        assertEquals(Event.Ignored(0, IgnoreReason.GAME_OVER), Engine.apply(s, Move(0)).events.single())
    }

    @Test
    fun deadlockWhenDockFillsWithoutMatch() = eachSpots {
        // Column of S containers colours 0..3 above an L container of colour 4; the window holds only colour 4.
        val vs = (0 until 4).map { small(it, it, 2 * it, 0, Dir.UP) } + v(4, ContainerSize.L, 4, 8, 0, Dir.UP)
        val level = pad(Level(1, 2, 11, vs, q(4 to 10, 0 to 4, 1 to 4, 2 to 4, 3 to 4)))
        var s = GameState.initial(level)
        for (i in 0 until 3) {
            s = Engine.apply(s, Move(i)).state
            assertEquals(Status.PLAYING, s.status)
        }
        s = Engine.apply(s, Move(3)).state
        assertEquals(Status.DEADLOCK, s.status)
        assertEquals(Status.DEADLOCK, Engine.computeStatus(s))
        assertEquals(emptyList(), Engine.legalMoves(s))
        assertFalse(Engine.isTappable(s, 4))
        assertEquals(listOf<Event>(Event.Ignored(4, IgnoreReason.GAME_OVER)), Engine.apply(s, Move(4)).events)
    }

    @Test
    fun stuckWhenNoTappableContainer() {
        // S containers 0 and 1 face each other in row 0; S container 2 is free and its elements come first.
        val level = Level(
            1, 4, 3,
            listOf(small(0, 0, 0, 1, Dir.RIGHT), small(1, 1, 0, 2, Dir.LEFT), small(2, 2, 2, 0, Dir.LEFT)),
            q(2 to 4, 0 to 4, 1 to 4),
        )
        val s0 = GameState.initial(level)
        assertEquals(listOf(Move(2)), Engine.legalMoves(s0))
        val s = Engine.apply(s0, Move(2)).state
        assertEquals(Status.STUCK, s.status)
        assertEquals(Event.Ignored(0, IgnoreReason.GAME_OVER), Engine.apply(s, Move(0)).events.single())
    }

    @Test
    fun ignoredTaps() {
        val level = Level(1, 5, 2, List(5) { small(it, it, 0, it, Dir.UP) }, q(4 to 4, 0 to 4, 1 to 4, 2 to 4, 3 to 4))
        val s0 = GameState.initial(level)
        assertEquals(Event.Ignored(9, IgnoreReason.INVALID_ID), Engine.apply(s0, Move(9)).events.single())
        assertEquals(Event.Ignored(-1, IgnoreReason.INVALID_ID), Engine.apply(s0, Move(-1)).events.single())
        val s1 = Engine.apply(s0, Move(0)).state
        val ign = Engine.apply(s1, Move(0))
        assertEquals(Event.Ignored(0, IgnoreReason.NOT_ON_BOARD), ign.events.single())
        assertSame(s1, ign.state)
        // Hand-built state: 4 slots full but still PLAYING (cannot arise from Engine, which flags DEADLOCK).
        val full = s0.copy(onBoard = Bits128(1L shl 4, 0L), dock = List(4) { DockedContainer(it, it, 4) })
        val t = Engine.apply(full, Move(4))
        assertEquals(Event.Ignored(4, IgnoreReason.DOCK_FULL), t.events.single())
        assertSame(full, t.state)
        assertFalse(Engine.isTappable(full, 4))
        assertEquals(emptyList(), Engine.legalMoves(full))
        // GAME_OVER is checked before INVALID_ID.
        val over = full.copy(status = Status.DEADLOCK)
        assertEquals(Event.Ignored(99, IgnoreReason.GAME_OVER), Engine.apply(over, Move(99)).events.single())
    }

    @Test
    fun handlesHighContainerIds() {
        // 100 S containers; ids >= 64 live in Bits128.hi.
        val vs = (0 until 100).map { small(it, it % 8, it / 10, it % 10, Dir.UP) }
        val level = Level(1, 10, 10, vs, vs.flatMap { c -> List(4) { c.color } }.sortedBy { it })
        val s = GameState.initial(level)
        assertEquals((0 until 10).map { Move(it) }, Engine.legalMoves(s))
        val t = Engine.apply(s, Move(95))
        assertEquals(Event.Blocked(95, 9, 5, 85), t.events.single())
        assertTrue(Engine.isTappable(s, 5))
        assertFalse(Engine.isTappable(s, 95))
    }

    @Test
    fun legalMovesAscendingAndConsistentWithIsTappable() {
        val rnd = Random(7)
        repeat(200) {
            val level = TestLevels.randomSmall(rnd)
            val s = GameState.initial(level)
            val legal = Engine.legalMoves(s).map { it.containerId }
            assertEquals(legal.sorted(), legal)
            for (i in level.containers.indices) {
                assertEquals(i in legal, Engine.isTappable(s, i))
                assertEquals(i in legal, Engine.apply(s, Move(i)).events.first() is Event.Released)
            }
        }
    }

    /**
     * Random levels for differential tests: small padded ones (7-spot window) and large natural ones, for every staging
     * size in TestLevels.SPOTS.
     */
    private fun diffLevels(rnd: Random, small: Int, big: Int): List<Level> = TestLevels.SPOTS.flatMap { n ->
        List(small) {
            val l = TestLevels.randomSmall(rnd, width = 6, height = 6, maxContainers = 10, colors = 4, largeOdds = 4)
            if (rnd.nextBoolean()) TestLevels.padded(l, n) else l.copy(stagingSpots = n)
        } + List(big) { TestLevels.bigSolvable(seed = 7000L + it, containers = 50 + 10 * (it % 6), perturb = 150, spots = n) }
    }

    /** Engine vs the naive [RefEngine]: identical transitions (state, status and every event) on random taps. */
    @Test
    fun matchesReferenceEngineOnRandomPlay() {
        val rnd = Random(4242)
        var releases = 0
        var boards = 0
        var stagedEvents = 0
        for (level in diffLevels(rnd, 400, 8)) {
            var s = GameState.initial(level)
            var steps = 0
            while (steps++ < 200) {
                val legal = Engine.legalMoves(s)
                assertEquals(RefEngine.legal(s), legal)
                val move = if (legal.isNotEmpty() && rnd.nextInt(4) != 0) legal[rnd.nextInt(legal.size)]
                else Move(rnd.nextInt(-1, level.containers.size + 1))
                val t = Engine.apply(s, move)
                val r = RefEngine.apply(s, move)
                assertEquals(r.events, t.events, "level $level move $move")
                assertEquals(r.state, t.state)
                assertEquals(RefEngine.status(t.state), Engine.computeStatus(t.state))
                if (t.events.first() is Event.Released) releases++
                boards += t.events.count { it is Event.Boarded }
                stagedEvents += t.events.count { it is Event.Staged }
                s = t.state
                if (s.status != Status.PLAYING) break
            }
        }
        assertTrue(releases > 1000 && boards > 5000 && stagedEvents > 2000, "releases=$releases boards=$boards staged=$stagedEvents")
    }

    /** The solver's batched packed simulation must reach the same states and statuses as the Engine. */
    @Test
    fun packedSimulationMatchesEngine() {
        val rnd = Random(42)
        var steps = 0
        val p = LongArray(2)
        val ex = LongArray(2)
        for (level in diffLevels(rnd, 300, 8)) {
            val d = LevelData.of(level)
            var s = GameState.initial(level)
            var lo = s.onBoard.lo
            var hi = s.onBoard.hi
            d.toPacked(s, p)
            var a = p[0]
            var b = p[1]
            while (s.status == Status.PLAYING) {
                val moves = Engine.legalMoves(s)
                d.exitable(lo, hi, ex)
                assertEquals(moves.map { it.containerId }, Bits128(ex[0], ex[1]).ids())
                if (moves.isEmpty()) break // a random level can start with nothing exitable (initial status is PLAYING)
                val m = moves[rnd.nextInt(moves.size)].containerId
                s = Engine.apply(s, Move(m)).state
                d.exitAndBoard(a, b, m, p)
                a = p[0]
                b = p[1]
                if (m < 64) lo = lo and (1L shl m).inv() else hi = hi and (1L shl (m - 64)).inv()
                steps++
                assertEquals(Bits128(lo, hi), s.onBoard)
                d.toPacked(s, p)
                assertEquals(p[0], a)
                assertEquals(p[1], b)
                assertEquals(s.status.ordinal, d.status(lo, hi, a, b))
            }
        }
        assertTrue(steps > 1500, "steps=$steps")
    }
}
