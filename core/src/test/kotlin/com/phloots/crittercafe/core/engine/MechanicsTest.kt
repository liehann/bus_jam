package com.phloots.crittercafe.core.engine

import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.DockedContainer
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.IgnoreReason
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v4 mechanic rules (DESIGN.md "Mechanics (v4)") in the turn-based Engine. */
class MechanicsTest {
    private fun s(id: Int, color: Int, row: Int, col: Int, dir: Dir) = Container(id, ContainerSize.S, color, row, col, dir)

    /** Level whose queue holds every container's elements, colour by colour in [order] (default: container order). */
    private fun level(cs: List<Container>, spots: Int = 4, order: List<Int> = cs.indices.toList(), w: Int = 6, h: Int = 6) =
        Level(1, w, h, cs, order.flatMap { i -> List(cs[i].size.capacity) { cs[i].color } }, spots)

    private fun tap(st: GameState, id: Int) = Engine.apply(st, Move(id))

    private fun GameState.tapOk(id: Int): GameState {
        val t = tap(this, id)
        assertTrue(t.events.first() is Event.Released, "tap $id: ${t.events.first()}")
        return t.state
    }

    private fun ignoredReason(st: GameState, id: Int): IgnoreReason? = (tap(st, id).events.single() as? Event.Ignored)?.reason

    // ------------------------------------------------------------------ frozen

    @Test
    fun frozenCountsDownOnReleases() {
        // three free S containers on the top row facing UP; container 0 is frozen for 2 releases
        val cs = listOf(s(0, 0, 0, 0, Dir.UP).copy(frozen = 2), s(1, 1, 0, 2, Dir.UP), s(2, 2, 0, 4, Dir.UP))
        var st = GameState.initial(level(cs, order = listOf(1, 2, 0)))
        assertEquals(2, st.frozenRemaining(0))
        assertEquals(IgnoreReason.FROZEN, ignoredReason(st, 0))
        assertFalse(Engine.isTappable(st, 0))
        assertEquals(listOf(1, 2), Engine.legalMoves(st).map { it.containerId })
        st = st.tapOk(1)
        assertEquals(1, st.frozenRemaining(0))
        assertEquals(IgnoreReason.FROZEN, ignoredReason(st, 0))
        st = st.tapOk(2)
        assertEquals(0, st.frozenRemaining(0))
        assertTrue(Engine.isTappable(st, 0))
        st = st.tapOk(0)
        assertEquals(Status.WON, st.status)
    }

    @Test
    fun blockedAndIgnoredTapsDoNotCountAsReleases() {
        // 1 is blocked by 2 (2 stands in front of it); 0 is frozen for 1 release
        val cs = listOf(s(0, 0, 0, 0, Dir.UP).copy(frozen = 1), s(1, 1, 3, 3, Dir.UP), s(2, 2, 1, 3, Dir.LEFT))
        val st = GameState.initial(level(cs, order = listOf(2, 1, 0)))
        val b = tap(st, 1)
        assertEquals(Event.Blocked(1, 2, 3, 2), b.events.single())
        assertEquals(st, b.state)
        assertEquals(1, b.state.frozenRemaining(0))
        assertEquals(1, tap(st, 0).state.frozenRemaining(0))
    }

    @Test
    fun onlyFrozenLeftIsStuck() {
        // the only other container is blocked, so 0 never thaws
        val cs = listOf(s(0, 0, 0, 0, Dir.UP).copy(frozen = 1), s(1, 1, 3, 3, Dir.UP), s(2, 2, 1, 3, Dir.DOWN))
        val st = GameState.initial(level(cs))
        assertEquals(Status.STUCK, Engine.computeStatus(st))
    }

    // ------------------------------------------------------------------ tied

    @Test
    fun tiedWaitsForItsPartnerOnly() {
        val cs = listOf(s(0, 0, 0, 0, Dir.UP).copy(tiedTo = 1), s(1, 1, 0, 2, Dir.UP))
        val st = GameState.initial(level(cs, order = listOf(1, 0)))
        assertTrue(st.isTieLocked(0))
        assertEquals(IgnoreReason.TIED, ignoredReason(st, 0))
        assertEquals(listOf(1), Engine.legalMoves(st).map { it.containerId })
        // directional: the partner is free to go first
        val after = st.tapOk(1)
        assertFalse(after.isTieLocked(0))
        assertEquals(Status.WON, after.tapOk(0).status)
    }

    @Test
    fun tiedPartnerBuriedMeansStuck() {
        // 0 is tied to 1, 1 is blocked by 2, 2 is frozen for 1 release: nothing can move
        val cs = listOf(
            s(0, 0, 0, 0, Dir.UP).copy(tiedTo = 1),
            s(1, 1, 3, 3, Dir.UP),
            s(2, 2, 1, 3, Dir.LEFT).copy(frozen = 1),
        )
        assertEquals(Status.STUCK, Engine.computeStatus(GameState.initial(level(cs))))
    }

    // ------------------------------------------------------------------ hidden

    @Test
    fun hiddenIsRevealedWhenItsCoverLeaves() {
        // 0 faces UP at (2,0); 1 stands at (1,0) in front of it; 2 elsewhere
        val cs = listOf(s(0, 0, 2, 0, Dir.UP).copy(hidden = true), s(1, 1, 1, 0, Dir.RIGHT), s(2, 2, 5, 5, Dir.DOWN))
        val l = level(cs, order = listOf(2, 1, 0))
        assertEquals(1, l.coverOf(0))
        var st = GameState.initial(l)
        assertFalse(st.isRevealed(0))
        assertTrue(st.isRevealed(1) && st.isRevealed(2))
        st = st.tapOk(2)
        assertFalse(st.isRevealed(0))
        st = st.tapOk(1)
        assertTrue(st.isRevealed(0))
    }

    @Test
    fun hiddenNeverChangesTheRules() {
        val rnd = Random(4)
        repeat(300) {
            val l = MechLevels.random(rnd)
            if (l.containers.none { it.hidden }) return@repeat
            val plain = l.copy(containers = l.containers.map { it.copy(hidden = false) })
            var a = GameState.initial(l)
            var b = GameState.initial(plain)
            repeat(20) {
                val id = rnd.nextInt(-1, l.containers.size + 1)
                val ta = tap(a, id)
                val tb = tap(b, id)
                assertEquals(tb.events, ta.events)
                a = ta.state
                b = tb.state
                assertEquals(b.copy(level = l), a)
            }
        }
    }

    // ------------------------------------------------------------------ wide

    @Test
    fun wideNeedsTwoAdjacentFreeSlots() {
        // colours 0,1 S at the top; 2 wide; 3 S. Queue: none of their elements first, so nothing boards.
        val cs = listOf(
            s(0, 0, 0, 0, Dir.UP), s(1, 1, 0, 1, Dir.UP), s(2, 2, 0, 2, Dir.UP).copy(wide = true), s(3, 3, 0, 3, Dir.UP),
            s(4, 4, 0, 4, Dir.UP),
        )
        val l = level(cs, spots = 3, order = listOf(4, 0, 1, 2, 3))
        var st = GameState.initial(l)
        st = st.tapOk(0) // slot 0
        // dock [0, -, -, -]: the wide one takes 1..2
        val t = tap(st, 2)
        assertEquals(Event.Released(2, 1), t.events.first())
        val dc = DockedContainer(2, 2, 4)
        assertEquals(listOf(DockedContainer(0, 0, 4), dc, dc, null), t.state.dock)
        assertTrue(t.state.isWideTail(2))
        assertFalse(t.state.isWideTail(1))
        // dock [0, -, 3, -] instead: free slots 1 and 3 are not adjacent
        val split = st.copy(dock = listOf(DockedContainer(0, 0, 4), null, DockedContainer(3, 3, 4), null), onBoard = st.onBoard.without(3))
        assertEquals(IgnoreReason.NO_FITTING_SLOT, ignoredReason(split, 2))
        assertFalse(Engine.isTappable(split, 2))
        assertTrue(Engine.isTappable(split, 4))
    }

    @Test
    fun wideBoardsAtItsLeftSlotAndDispatchFreesBoth() {
        // wide colour 0 (S, 4) released first into 0..1, then a plain colour-0 S into 2. Four colour-0 elements arrive
        // after a filler colour 1: they all go to the wide one (left), which dispatches and frees slots 0 and 1.
        val cs = listOf(s(0, 0, 0, 0, Dir.UP).copy(wide = true), s(1, 0, 0, 2, Dir.UP), s(2, 1, 0, 4, Dir.UP))
        val l = Level(1, 6, 6, cs, List(4) { 1 } + List(8) { 0 }, 4)
        var st = GameState.initial(l)
        st = st.tapOk(0)
        assertEquals(listOf(DockedContainer(0, 0, 4), DockedContainer(0, 0, 4), null, null), st.dock)
        st = st.tapOk(1)
        assertEquals(DockedContainer(1, 0, 4), st.dock[2])
        // colour 1 S into slot 3: its elements board; the colour-0 elements arriving behind them fill the wide one
        // (left, slot 0) completely before the plain one in slot 2 gets any
        val t = tap(st, 2)
        val boarded = t.events.filterIsInstance<Event.Boarded>()
        assertEquals(listOf(3, 3, 3, 3), boarded.filter { l.queue[it.elementIndex] == 1 }.map { it.slot })
        assertEquals(listOf(0, 0, 0, 0, 2, 2, 2, 2), boarded.filter { l.queue[it.elementIndex] == 0 }.map { it.slot })
        assertTrue(Event.Dispatched(0, 0) in t.events)
        assertEquals(Status.WON, t.state.status)
    }

    @Test
    fun wideDeadlockWhenNoPairIsFree() {
        // dock [A, -, B, -] with A, B waiting for colours that are not staged; only a wide container is left to tap
        val cs = listOf(s(0, 0, 0, 0, Dir.UP).copy(wide = true), s(1, 1, 5, 5, Dir.DOWN), s(2, 2, 5, 4, Dir.DOWN))
        val l = level(cs, spots = 2, order = listOf(0, 1, 2))
        val st = GameState(
            l, GameState.initial(l).onBoard.without(1).without(2),
            listOf(DockedContainer(1, 1, 4), null, DockedContainer(2, 2, 4), null),
            listOf(0, 0), 2,
        )
        assertEquals(Status.DEADLOCK, Engine.computeStatus(st))
    }

    // ------------------------------------------------------------------ colour benches

    @Test
    fun benchesSkipOtherColours() {
        val cs = listOf(s(0, 0, 0, 0, Dir.UP), s(1, 1, 0, 1, Dir.UP), s(2, 2, 0, 2, Dir.UP), s(3, 0, 0, 3, Dir.UP))
        val l = level(cs, spots = 1, order = listOf(2, 0, 1, 3)).copy(benchColors = listOf(1, -1, 0, -1))
        var st = GameState.initial(l) // staging holds one colour-2 element
        val t = tap(st, 0) // colour 0: slot 0 is colour 1 only -> slot 1
        assertEquals(Event.Released(0, 1), t.events.first())
        st = t.state
        assertEquals(Event.Released(1, 0), tap(st, 1).events.first()) // colour 1 takes its own bench 0
        // fill slot 3 too: then a colour-0 container finds 2 (its bench)
        st = st.tapOk(1)
        assertEquals(Event.Released(3, 2), tap(st, 3).events.first())
    }

    @Test
    fun benchWithOnlyForeignSlotsFreeIsNoFit() {
        val cs = listOf(s(0, 0, 0, 0, Dir.UP), s(1, 1, 0, 1, Dir.UP), s(2, 1, 0, 2, Dir.UP))
        val l = level(cs, spots = 1, order = listOf(0, 1, 2)).copy(benchColors = listOf(-1, 0, 0, 0))
        val st = GameState(l, GameState.initial(l).onBoard.without(0), listOf(DockedContainer(0, 0, 3), null, null, null), listOf(1), 5)
        // colour 1 can only use slot 0, which is taken
        assertEquals(IgnoreReason.NO_FITTING_SLOT, ignoredReason(st, 1))
        assertEquals(emptyList(), Engine.legalMoves(st))
        assertEquals(Status.DEADLOCK, Engine.computeStatus(st))
    }

    // ------------------------------------------------------------------ turntables

    @Test
    fun turntableTurnsClockwiseOnEveryRelease() {
        // T on a turntable at (2,2) facing UP, blocked by B at (1,2); C at (2,4) is in its way once it faces RIGHT
        val t = s(0, 0, 2, 2, Dir.UP)
        val bb = s(1, 1, 1, 2, Dir.LEFT)
        val c = s(2, 2, 2, 4, Dir.UP)
        val e = s(3, 3, 5, 0, Dir.LEFT)
        val l = level(listOf(t, bb, c, e), spots = 1, order = listOf(3, 1, 2, 0), w = 5, h = 6).copy(turntables = listOf(Cell(2, 2)))
        var st = GameState.initial(l)
        assertEquals(Dir.UP, st.dirOf(0))
        assertEquals(Event.Blocked(0, 2, 2, 1), tap(st, 0).events.single())
        st = st.tapOk(1) // release 1: T now faces RIGHT, blocked by C
        assertEquals(Dir.RIGHT, st.dirOf(0))
        assertEquals(Dir.LEFT, st.dirOf(3)) // not on a turntable
        assertEquals(Event.Blocked(0, 2, 3, 2), tap(st, 0).events.single())
        st = st.tapOk(3) // release 2: faces DOWN, clear
        assertEquals(Dir.DOWN, st.dirOf(0))
        assertTrue(Engine.isTappable(st, 0))
        st = st.tapOk(2) // release 3: faces LEFT, clear
        assertEquals(Dir.LEFT, st.dirOf(0))
        assertEquals(Status.WON, st.tapOk(0).status)
    }

    // ------------------------------------------------------------------ gates

    @Test
    fun gateAlternatesOnEveryRelease() {
        // G at (2,5) faces RIGHT through a gate at RIGHT row 2, closed at start; X, Y free elsewhere
        val g = s(0, 0, 2, 5, Dir.RIGHT)
        val x = s(1, 1, 0, 0, Dir.UP)
        val y = s(2, 2, 0, 1, Dir.UP)
        val l = level(listOf(g, x, y), spots = 1, order = listOf(1, 2, 0)).copy(gates = listOf(Gate(Dir.RIGHT, 2, open = false)))
        var st = GameState.initial(l)
        assertFalse(st.isGateOpen(0))
        assertEquals(Event.Blocked(0, 2, 5, Event.Blocked.GATE), tap(st, 0).events.single())
        assertEquals(listOf(1, 2), Engine.legalMoves(st).map { it.containerId })
        st = st.tapOk(1)
        assertTrue(st.isGateOpen(0))
        assertTrue(Engine.isTappable(st, 0))
        st = st.tapOk(2)
        assertFalse(st.isGateOpen(0))
        assertEquals(Status.STUCK, st.status) // only G is left, behind a gate that only a release could open
    }

    @Test
    fun gateBlockStopsAtTheLastCell() {
        val g = s(0, 0, 4, 2, Dir.UP)
        val l = level(listOf(g, s(1, 1, 5, 5, Dir.DOWN)), order = listOf(1, 0)).copy(gates = listOf(Gate(Dir.UP, 2, open = false)))
        assertEquals(Event.Blocked(0, 0, 2, Event.Blocked.GATE), tap(GameState.initial(l), 0).events.single())
    }

    // ------------------------------------------------------------------ differential against the naive reference

    @Test
    fun engineMatchesReference() {
        val rnd = Random(2026)
        var released = 0
        val reasons = HashSet<IgnoreReason>()
        var gateBlocks = 0
        val statuses = HashSet<Status>()
        repeat(1500) { li ->
            val l = MechLevels.random(rnd)
            var e = GameState.initial(l)
            var r = GameState.initial(l)
            var steps = 0
            while (e.status == Status.PLAYING && steps++ < 40) {
                assertEquals(MechRef.legal(r), Engine.legalMoves(e), "level $li $l")
                for (id in l.containers.indices) assertEquals(MechRef.legal(r).any { it.containerId == id }, Engine.isTappable(e, id))
                val legal = Engine.legalMoves(e)
                val id = if (legal.isNotEmpty() && rnd.nextInt(3) != 0) legal.random(rnd).containerId else rnd.nextInt(-1, l.containers.size + 1)
                val te = Engine.apply(e, Move(id))
                val tr = MechRef.apply(r, Move(id))
                assertEquals(tr.events, te.events, "level $li tap $id")
                assertEquals(tr.state, te.state, "level $li tap $id")
                when (val f = te.events.first()) {
                    is Event.Released -> released++
                    is Event.Ignored -> reasons += f.reason
                    is Event.Blocked -> if (f.blockerId == Event.Blocked.GATE) gateBlocks++
                    else -> {}
                }
                e = te.state
                r = tr.state
            }
            statuses += e.status
        }
        assertTrue(released > 3000, "released $released")
        assertTrue(reasons.containsAll(listOf(IgnoreReason.FROZEN, IgnoreReason.TIED, IgnoreReason.NO_FITTING_SLOT, IgnoreReason.NOT_ON_BOARD)), "$reasons")
        assertTrue(gateBlocks > 0)
        assertTrue(statuses.containsAll(listOf(Status.WON, Status.DEADLOCK, Status.STUCK)), "$statuses")
    }

    @Test
    fun mechanicsListing() {
        val base = level(listOf(s(0, 0, 0, 0, Dir.UP), s(1, 1, 2, 0, Dir.UP)))
        assertEquals(emptyList(), base.mechanics())
        val all = base.copy(
            containers = listOf(
                s(0, 0, 0, 0, Dir.UP).copy(frozen = 1, tiedTo = 1),
                s(1, 1, 2, 0, Dir.UP).copy(hidden = true, wide = true),
            ),
            benchColors = listOf(-1, 1, -1, -1), turntables = listOf(Cell(3, 3)), gates = listOf(Gate(Dir.UP, 0)),
        )
        assertEquals(Mechanic.entries.toList(), all.mechanics())
        assertEquals(
            listOf("frozen", "tied", "hidden", "wide", "colour_bench", "turntable", "gate"),
            all.mechanics().map { it.id },
        )
        assertEquals(Mechanic.COLOUR_BENCH, Mechanic.fromId("colour_bench"))
        // an all -1 bench list restricts nothing
        assertEquals(emptyList(), base.copy(benchColors = listOf(-1, -1, -1, -1)).mechanics())
    }
}
