package com.phloots.jam.core.realtime

import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver
import com.phloots.jam.core.solver.TestLevels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RealTimeTest {
    private val configs = listOf(
        RtConfig.DEFAULT,
        RtConfig(ticksPerSeat = 1, releaseTicks = 1, boardTicks = 1, dispatchTicks = 1, gatePosition = 0, entryPosition = 1, gateWidth = 1, boardStaggerTicks = 1),
        RtConfig(ticksPerSeat = 3, releaseTicks = 7, boardTicks = 5, dispatchTicks = 11, gatePosition = 13, entryPosition = 12, boardStaggerTicks = 2),
        RtConfig(ticksPerSeat = 2, releaseTicks = 40, boardTicks = 1, dispatchTicks = 90, gatePosition = 5, entryPosition = 30, gateWidth = 10, boardStaggerTicks = 7),
        RtConfig(ticksPerSeat = 4, gateWidth = 3, boardStaggerTicks = 1),
    )

    /** The ring after the initial fill (patient start); checks the fill takes one seat step per element. */
    private fun filled(level: Level, cfg: RtConfig): RealTimeState {
        val (rt, ev) = RealTime.runUntilSettled(RealTime.initial(level, cfg))
        val n = minOf(Limits.STAGING_SPOTS, level.queue.size)
        assertEquals(n.toLong() * cfg.ticksPerSeat, rt.tick)
        assertEquals((0 until n).toList(), ev.filterIsInstance<RtEvent.SteppedOn>().map { it.elementIndex })
        return rt
    }

    /** Settled RT state must equal the Engine state: positional dock incl. spaceLeft, staged multiset, queueIndex, onBoard, status. */
    private fun assertMatches(e: GameState, rt: RealTimeState, ctx: () -> String) {
        assertTrue(rt.settled, ctx())
        assertTrue(RealTime.isSettled(rt), ctx())
        val g = rt.toGameState()
        assertEquals(e.dock, g.dock, ctx())
        assertEquals(e.staging.sorted(), g.staging.sorted(), ctx())
        assertEquals(e.queueIndex, g.queueIndex, ctx())
        assertEquals(e.onBoard, g.onBoard, ctx())
        assertEquals(e.status, rt.status, ctx())
        assertTrue(rt.walkers.isEmpty(), ctx())
    }

    /** Elements are never created or lost: queue = boarded + walking + on ring + still queued. */
    private fun assertConserved(rt: RealTimeState, boardedSoFar: Int) {
        assertEquals(rt.level.queue.size, boardedSoFar + rt.walkers.size + rt.ringCount + rt.queueRemaining)
        var occupied = 0
        for (i in 0 until rt.seatCount) if (rt.seatElement(i) >= 0) occupied++
        assertEquals(rt.ringCount, occupied)
    }

    private fun levels(rnd: Random, small: Int, big: Int): List<Level> =
        List(small) {
            val l = TestLevels.randomSmall(rnd, width = 6, height = 6, maxContainers = 10, colors = 2 + rnd.nextInt(3), largeOdds = 4)
            if (rnd.nextBoolean()) TestLevels.padded(l) else l
        } + List(big) { TestLevels.bigSolvable(seed = 9100L + it, containers = 50 + 10 * (it % 6), perturb = 150) }

    /** Convergence invariant: patient play (tap only when settled, any idle time) == Engine.apply after every tap. */
    @Test
    fun patientPlayConvergesToEngine() {
        val rnd = Random(777)
        var taps = 0
        var sameColourDocked = 0
        var midSettleFrees = 0
        var won = 0
        var lost = 0
        var longest = 0
        var fillTaps = 0
        var bursts = 0
        for ((li, level) in levels(rnd, 500, 24).withIndex()) {
            val cfg = configs[li % configs.size]
            var e = GameState.initial(level)
            val start = RealTime.initial(level, cfg)
            assertEquals(0, start.ringCount)
            assertEquals(0, start.queueIndex)
            assertFalse(start.settled)
            val full = filled(level, cfg)
            assertMatches(e, full) { "initial $li" }
            // a third of the levels: the first tap lands during the initial fill (the ring is still filling)
            var rt = if (rnd.nextInt(3) == 0) {
                fillTaps++
                RealTime.advance(start, rnd.nextInt(full.tick.toInt())).first
            } else full
            var steps = 0
            while (e.status == Status.PLAYING && steps < 300) {
                val legal = Engine.legalMoves(e)
                assertEquals(legal.map { it.containerId }, level.containers.indices.filter { RealTime.isTappable(rt, it) })
                val id = if (legal.isNotEmpty() && rnd.nextInt(5) != 0) legal[rnd.nextInt(legal.size)].containerId
                else rnd.nextInt(-1, level.containers.size + 1)
                val t = Engine.apply(e, Move(id))
                val (afterTap, tapEv) = RealTime.tap(rt, id)
                val engineFirst = t.events.first()
                when (val first = tapEv.single()) {
                    is RtEvent.Released -> assertEquals(engineFirst, com.phloots.jam.core.model.Event.Released(id, first.slot))
                    is RtEvent.Blocked -> assertEquals(engineFirst, com.phloots.jam.core.model.Event.Blocked(id, first.stopRow, first.stopCol, first.blockerId))
                    is RtEvent.Ignored -> assertEquals(engineFirst, com.phloots.jam.core.model.Event.Ignored(id, first.reason))
                    else -> error("unexpected $first")
                }
                val (settled, ev) = RealTime.runUntilSettled(afterTap)
                // idle a random while after settling (phase changes, nothing else may)
                val idle = rnd.nextInt(0, 3 * cfg.ticksPerSeat * Limits.STAGING_SPOTS)
                val (idled, idleEv) = RealTime.advance(settled, idle)
                assertTrue(idleEv.isEmpty(), "idle events $idleEv")
                e = t.state
                rt = idled
                assertMatches(e, rt) { "level $li step $steps tap $id cfg $cfg" }
                assertEquals(t.events.count { it is com.phloots.jam.core.model.Event.Boarded }, ev.count { it is RtEvent.Boarded })
                assertEquals(t.events.count { it is com.phloots.jam.core.model.Event.Dispatched }, ev.count { it is RtEvent.Dispatched })
                // (a tap during the initial fill also sees the rest of the fill step on)
                assertEquals(e.queueIndex - afterTap.queueIndex, ev.count { it is RtEvent.SteppedOn })
                val firstFree = ev.indexOfFirst { it is RtEvent.Dispatched }
                if (firstFree >= 0 && ev.drop(firstFree).any { it is RtEvent.BoardingStarted }) midSettleFrees++
                if (ev.count { it is RtEvent.BoardingStarted } >= 3) bursts++
                val docked = e.dock.filterNotNull().map { it.color }
                if (docked.size != docked.toSet().size) sameColourDocked++
                if (tapEv.single() is RtEvent.Released) taps++
                steps++
            }
            longest = maxOf(longest, steps)
            if (e.status == Status.WON) won++
            if (e.status == Status.DEADLOCK || e.status == Status.STUCK) lost++
        }
        val stats = "taps=$taps same=$sameColourDocked midFree=$midSettleFrees won=$won lost=$lost longest=$longest " +
            "fillTaps=$fillTaps bursts=$bursts"
        println("convergence: $stats")
        assertTrue(taps > 1500 && sameColourDocked > 90 && midSettleFrees > 100 && lost > 50 && longest >= 40 &&
            fillTaps > 100 && bursts > 500, stats)
    }

    /** Long winning runs: the solver's (patient) line wins in real time with identical states throughout. */
    @Test
    fun solverLinesWinInRealTime() {
        var wins = 0
        var moves = 0
        for (seed in 0 until 6) {
            val level = TestLevels.bigSolvable(seed = 31L + seed, containers = 70, perturb = 200)
            val sol = Solver.solve(level, 3_000_000) ?: continue
            if (!sol.solvable) continue
            val cfg = configs[seed % configs.size]
            var e = GameState.initial(level)
            var rt = filled(level, cfg)
            for (m in sol.solution) {
                e = Engine.apply(e, m).state
                rt = RealTime.runUntilSettled(RealTime.tap(rt, m.containerId).first).first
                assertMatches(e, rt) { "seed $seed move $m" }
                moves++
            }
            assertEquals(Status.WON, rt.status)
            wins++
        }
        println("solver lines: wins=$wins moves=$moves")
        assertTrue(wins >= 3 && moves > 150, "wins=$wins moves=$moves")
    }

    @Test
    fun fromGameStateRoundTrips() {
        val rnd = Random(5)
        for (level in levels(rnd, 60, 2)) {
            var e = GameState.initial(level)
            repeat(10) {
                val legal = Engine.legalMoves(e)
                if (legal.isEmpty()) return@repeat
                e = Engine.apply(e, legal[rnd.nextInt(legal.size)]).state
            }
            val rt = RealTime.fromGameState(e)
            assertMatches(e, rt) { "fromGameState" }
            assertEquals(e.staging, rt.toGameState().staging) // oldest-first ordering is preserved
        }
    }

    @Test
    fun levelStartsWithEverythingQueuedAndFillsThroughTheEntry() {
        val level = TestLevels.bigSolvable(seed = 3L, containers = 60)
        val cfg = RtConfig.DEFAULT
        val rt0 = RealTime.initial(level, cfg)
        assertEquals(0, rt0.ringCount)
        assertEquals(level.queue.size, rt0.queueRemaining)
        assertFalse(rt0.settled)
        assertEquals(Status.PLAYING, rt0.status)
        assertTrue(rt0.dock.all { it == null })
        // taps are legal during the fill
        assertEquals(Engine.legalMoves(GameState.initial(level)).map { it.containerId },
            level.containers.indices.filter { RealTime.isTappable(rt0, it) })
        val rt = filled(level, cfg)
        assertEquals(Limits.STAGING_SPOTS, rt.ringCount)
        // a line in queue order: the oldest element is the most downstream (first to reach the gate window)
        val entrySeat = rt.seatAt(cfg.entryPosition)
        for (k in 0 until Limits.STAGING_SPOTS) {
            assertEquals(Limits.STAGING_SPOTS - 1 - k, rt.seatElement(Math.floorMod(entrySeat + k, Limits.STAGING_SPOTS)))
        }
        assertTrue(rt.settled)
        assertEquals(Status.PLAYING, rt.status)
        assertMatches(GameState.initial(level), rt) { "fill" }
    }

    /** Taps during settling are legal; random impatient play never breaks invariants and is deterministic. */
    @Test
    fun impatientPlayIsDeterministicAndConsistent() {
        val rnd = Random(99)
        var midTaps = 0
        for ((li, level) in levels(rnd, 120, 6).withIndex()) {
            val cfg = configs[li % configs.size]
            val schedule = ArrayList<Pair<Long, Int>>() // (tick, container) taps
            fun run(endTick: Long): Pair<RealTimeState, List<RtEvent>> {
                val record = endTick < 0
                var rt = RealTime.initial(level, cfg)
                val all = ArrayList<RtEvent>()
                var boarded = 0
                var si = 0
                while (if (record) rt.tick < 20_000 && !(rt.settled && rt.status != Status.PLAYING) else rt.tick < endTick) {
                    if (record && rnd.nextInt(6) == 0) {
                        val cand = level.containers.indices.filter { RealTime.isTappable(rt, it) }
                        val id = if (cand.isNotEmpty()) cand[rnd.nextInt(cand.size)] else rnd.nextInt(level.containers.size)
                        schedule += rt.tick to id
                    }
                    while (si < schedule.size && schedule[si].first == rt.tick) {
                        val before = rt
                        val (ns, ev) = RealTime.tap(rt, schedule[si].second)
                        if (ev.single() is RtEvent.Released && !before.settled) midTaps++
                        if (ev.single() !is RtEvent.Released) assertEquals(before, ns) // ignored/blocked: no change
                        all += ev
                        rt = ns
                        si++
                    }
                    val (ns, ev) = RealTime.tick(rt)
                    boarded += ev.count { it is RtEvent.Boarded }
                    all += ev
                    rt = ns
                    assertConserved(rt, boarded)
                    for (s in rt.dock) if (s != null) assertTrue(s.spaceLeft in 0..s.capacity)
                    if (rt.settled) {
                        assertEquals(Engine.computeStatus(rt.toGameState().copy(status = Status.PLAYING)), rt.status)
                    } else {
                        assertEquals(Status.PLAYING, rt.status)
                    }
                }
                return rt to all
            }
            val a = run(-1)
            val b = run(a.first.tick)
            assertEquals(a.first, b.first, "level $li")
            assertEquals(a.second, b.second)
            RealTime.runUntilSettled(a.first)
        }
        println("impatient: midTaps=$midTaps")
        assertTrue(midTaps > 300, "midTaps=$midTaps")
    }

    @Test
    fun tapIgnoredWhileAllSlotsOccupiedIncludingDispatching() {
        // 5 free red S containers, 20 reds: dock fills with 4; the first dispatches; a 5th tap is DOCK_FULL until the
        // dispatch drive ends, then lands in slot 0.
        val vs = List(5) { TestLevels.small(it, 0, 0, 2 * it, Dir.UP) }
        val level = Level(1, 10, 12, vs, List(20) { 0 })
        val cfg = RtConfig(ticksPerSeat = 1, releaseTicks = 1, boardTicks = 1, dispatchTicks = 50)
        var rt = RealTime.initial(level, cfg)
        for (i in 0 until 4) rt = RealTime.tap(rt, i).first
        while (rt.dock[0]!!.phase(rt.tick) != SlotPhase.DISPATCHING) rt = RealTime.tick(rt).first
        val (same, ev) = RealTime.tap(rt, 4)
        assertEquals(listOf<RtEvent>(RtEvent.Ignored(4, IgnoreReason.DOCK_FULL)), ev)
        assertEquals(rt, same)
        while (rt.dock[0] != null) rt = RealTime.tick(rt).first
        assertEquals(0, (RealTime.tap(rt, 4).second.single() as RtEvent.Released).slot)
    }

    /**
     * Impatient second same-colour release loses a level that patient play wins (same taps, same order).
     *
     * Ring window of 2 (38 filler elements). Reds: A (S4), B (S4), C (L10). Queue after fillers:
     * R×6, G G, R R, P×4, Y×4, G G, R×10.
     * Patient: A fills and dispatches -> B takes slot 0 and gets 2 reds -> C slot 1 -> Gc slot 2; greens board Gc,
     * then R R finish B (leftmost) and it dispatches: dock [_, C, Gc]. Y1 -> slot 0, P -> slot 3: purples board. Win.
     * Impatient: B tapped while A is mid-dispatch -> B slot 1; A's slot frees -> C slot 0 (LEFT of B) -> R R go to C:
     * dock [C, B, Gc]; Y1 fills slot 3, ring holds P P: DEADLOCK.
     */
    @Test
    fun impatientSecondSameColourReleaseLoses() {
        val r = 0; val g = 1; val p = 2; val y = 3; val f = 7
        val vs = listOf(
            TestLevels.small(0, r, 0, 0, Dir.UP), // A
            TestLevels.small(1, r, 0, 1, Dir.UP), // B
            TestLevels.v(2, ContainerSize.L, r, 0, 2, Dir.UP), // C (rows 0..2)
            TestLevels.small(3, g, 0, 3, Dir.UP), // Gc
            TestLevels.small(4, y, 0, 4, Dir.UP), // Y1 blocks P
            TestLevels.small(5, p, 1, 4, Dir.UP), // P
            TestLevels.v(6, ContainerSize.L, f, 5, 9, Dir.RIGHT),
            TestLevels.v(7, ContainerSize.L, f, 6, 9, Dir.RIGHT),
            TestLevels.v(8, ContainerSize.L, f, 7, 9, Dir.RIGHT),
            TestLevels.small(9, f, 8, 9, Dir.RIGHT),
            TestLevels.small(10, f, 9, 9, Dir.RIGHT),
        )
        fun q(c: Int, n: Int) = List(n) { c }
        val queue = q(f, 38) + q(r, 6) + q(g, 2) + q(r, 2) + q(p, 4) + q(y, 4) + q(g, 2) + q(r, 10)
        val level = Level(1, 10, 12, vs, queue)
        assertEquals(emptyList(), level.validate())
        val line = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

        // Engine / patient real-time: wins.
        assertEquals(Status.WON, TestLevels.play(level, line.map { Move(it) }).status)
        val cfg = RtConfig.DEFAULT
        var patient = filled(level, cfg)
        for (id in line) patient = RealTime.runUntilSettled(RealTime.tap(patient, id).first).first
        assertEquals(Status.WON, patient.status)

        // Impatient: B tapped while A is dispatching.
        var rt = filled(level, cfg)
        rt = RealTime.tap(rt, 0).first
        while (rt.dock[0]?.phase(rt.tick) != SlotPhase.DISPATCHING) rt = RealTime.tick(rt).first
        val (afterB, evB) = RealTime.tap(rt, 1)
        assertEquals(RtEvent.Released(1, 1, rt.tick + cfg.releaseTicks), evB.single())
        rt = RealTime.runUntilSettled(afterB).first
        for (id in line.drop(2)) {
            if (rt.status != Status.PLAYING) break
            rt = RealTime.runUntilSettled(RealTime.tap(rt, id).first).first
        }
        assertEquals(Status.DEADLOCK, rt.status)
        assertEquals(listOf(2, 1, 3, 4), rt.dock.map { it!!.containerId })
        assertNotEquals(patient.status, rt.status)
    }

    @Test
    fun boardingWaitsForArrival() {
        // Two red S containers; nothing boards before a container has docked.
        val vs = listOf(TestLevels.small(0, 0, 0, 0, Dir.UP), TestLevels.small(1, 0, 0, 1, Dir.UP))
        val level = Level(1, 10, 12, vs, List(8) { 0 })
        val cfg = RtConfig(ticksPerSeat = 1, releaseTicks = 5, boardTicks = 2, dispatchTicks = 3, boardStaggerTicks = 1)
        var rt = filled(level, cfg)
        // turn the ring until the oldest element is inside the window
        while ((0 until cfg.gateWidth).none { rt.seatElement(rt.seatAt(cfg.gatePosition + it)) >= 0 }) rt = RealTime.tick(rt).first
        val (s1, e1) = RealTime.tap(rt, 0)
        assertEquals(RtEvent.Released(0, 0, rt.tick + 5), e1.single())
        rt = s1
        val evs = ArrayList<RtEvent>()
        repeat(4) { val (n, ev) = RealTime.tick(rt); evs += ev; rt = n }
        assertTrue(evs.none { it is RtEvent.BoardingStarted }, "no boarding before arrival")
        assertEquals(SlotPhase.ARRIVING, rt.dock[0]!!.phase(rt.tick))
        val (n, ev) = RealTime.tick(rt)
        assertTrue(ev.first() is RtEvent.Docked)
        assertTrue(ev.any { it is RtEvent.BoardingStarted && it.slot == 0 })
        rt = RealTime.runUntilSettled(n).first
        assertTrue(rt.dock.all { it == null })
        assertEquals(4, rt.ringCount)
        assertFalse(rt.status == Status.WON)
    }

    // ---------------------------------------------------------------- gate window

    private val red = 0
    private val blue = 1

    /**
     * 40-element queue. Once the ring has filled (gate 0, width 6, entry 20, phase 0) element k sits at position
     * 19 - k for k < 20 and 59 - k for k >= 20: the window (positions 0..5, travel towards 5) holds elements 19..14
     * (14 most downstream) and elements 20, 21, ... are next to enter it. [reds] = red queue indices; the rest blue.
     */
    private fun windowLevel(reds: Set<Int>, redContainers: List<ContainerSize>): Level {
        val queue = List(40) { if (it in reds) red else blue }
        val vs = ArrayList<com.phloots.jam.core.model.Container>()
        for (sz in redContainers) vs += TestLevels.v(vs.size, sz, red, 0, vs.size, Dir.UP)
        fun split(n: Int): List<ContainerSize>? = if (n == 0) emptyList() else
            ContainerSize.entries.sortedByDescending { it.capacity }.firstNotNullOfOrNull { sz ->
                if (sz.capacity > n) null else split(n - sz.capacity)?.let { listOf(sz) + it }
            }
        for (sz in split(queue.count { it == blue })!!) vs += TestLevels.v(vs.size, sz, blue, 0, vs.size, Dir.UP)
        val level = Level(1, 10, 12, vs, queue)
        assertEquals(emptyList(), level.validate())
        return level
    }

    /** Default timing (4 ticks per seat, stagger 3, width 6) with a short release drive. */
    private val windowCfg = RtConfig(releaseTicks = 2)

    private fun windowElements(rt: RealTimeState, cfg: RtConfig) =
        (0 until cfg.gateWidth).map { rt.seatElement(rt.seatAt(cfg.gatePosition + it)) }

    private fun ringReds(rt: RealTimeState) =
        (0 until rt.seatCount).map { rt.seatElement(it) }.filter { it >= 0 && rt.level.queue[it] == red }.sorted()

    /** Ticks [rt0] until settled, collecting (tick, BoardingStarted) pairs. */
    private fun boardings(rt0: RealTimeState): Pair<RealTimeState, List<Pair<Long, RtEvent.BoardingStarted>>> {
        var rt = rt0
        val out = ArrayList<Pair<Long, RtEvent.BoardingStarted>>()
        do {
            val (n, ev) = RealTime.tick(rt)
            rt = n
            for (e in ev) if (e is RtEvent.BoardingStarted) out += rt.tick to e
        } while (!rt.settled)
        return rt to out
    }

    @Test
    fun burstInTheWindowBoardsAsAStaggeredWaveFrontFirst() {
        // Six reds fill the window, four more are next to enter it; one red L (capacity 10).
        val level = windowLevel((14..23).toSet(), listOf(ContainerSize.L))
        val cfg = windowCfg
        val rt = filled(level, cfg)
        assertEquals(listOf(19, 18, 17, 16, 15, 14), windowElements(rt, cfg))
        val (end, b) = boardings(RealTime.tap(rt, 0).first)
        // one boarding per stagger step, starting the tick the container docks; the most downstream first (14), so
        // the wave rolls back up the window, and the reds entering afterwards board as they arrive
        val docked = rt.tick + cfg.releaseTicks
        assertEquals(List(10) { docked + it * cfg.boardStaggerTicks }, b.map { it.first })
        assertEquals((14..23).toList(), b.map { it.second.elementIndex })
        assertTrue(b.all { it.second.slot == 0 })
        assertTrue(end.dock.all { it == null })
        assertEquals(Status.PLAYING, end.status)
    }

    @Test
    fun windowBoardingRespectsSpace() {
        // Six reds in the window but only a red S (capacity 4) is docked: exactly 4 board, the rest stay on the ring.
        val level = windowLevel((14..21).toSet(), listOf(ContainerSize.S, ContainerSize.S))
        val cfg = windowCfg
        val rt = filled(level, cfg)
        val (afterFirst, b) = boardings(RealTime.tap(rt, 0).first)
        assertEquals(listOf(14, 15, 16, 17), b.map { it.second.elementIndex })
        assertTrue(b.all { it.second.slot == 0 })
        assertEquals(36, afterFirst.ringCount)
        assertEquals(listOf(18, 19, 20, 21), ringReds(afterFirst))
        val (afterSecond, b2) = boardings(RealTime.tap(afterFirst, 1).first)
        assertEquals(4, b2.size)
        assertEquals(emptyList(), ringReds(afterSecond))
    }

    @Test
    fun windowBoardingFillsTheLeftmostContainerFirst() {
        // Two red S containers dock at the same tick: the first four of the wave go to slot 0, the rest to slot 1.
        val level = windowLevel((14..21).toSet(), listOf(ContainerSize.S, ContainerSize.S))
        val cfg = windowCfg
        var rt = filled(level, cfg)
        rt = RealTime.tap(rt, 0).first
        rt = RealTime.tap(rt, 1).first
        val (end, b) = boardings(rt)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1), b.map { it.second.slot })
        assertEquals((14..21).toList(), b.map { it.second.elementIndex })
        for (i in 1 until b.size) assertEquals(cfg.boardStaggerTicks.toLong(), b[i].first - b[i - 1].first)
        assertTrue(end.dock.all { it == null })
    }

    @Test
    fun walkersRecordTheirWindowPosition() {
        val level = windowLevel((14..19).toSet(), listOf(ContainerSize.M))
        val cfg = windowCfg
        var rt = RealTime.tap(filled(level, cfg), 0).first
        val positions = ArrayList<Int>()
        while (!rt.settled || positions.isEmpty()) {
            val (n, ev) = RealTime.tick(rt)
            for (e in ev) if (e is RtEvent.BoardingStarted) positions += n.walkers.single { it.elementIndex == e.elementIndex }.position
            rt = n
        }
        assertEquals(6, positions.size)
        assertTrue(positions.all { it in cfg.gatePosition until cfg.gatePosition + cfg.gateWidth })
    }

    /** tick must be cheap: a full level of patient play plus long idle stretches runs well under budget. */
    @Test
    fun tickIsCheap() {
        val level = TestLevels.bigSolvable(seed = 5L, containers = 80)
        var rt = RealTime.initial(level, RtConfig.DEFAULT)
        repeat(20_000) { rt = RealTime.tick(rt).first } // warm-up
        val n = 2_000_000
        val t0 = System.nanoTime()
        repeat(n) { rt = RealTime.tick(rt).first }
        val nsPerTick = (System.nanoTime() - t0).toDouble() / n
        println("ns/tick=$nsPerTick")
        assertTrue(nsPerTick < 2_000, "ns/tick=$nsPerTick")
    }
}
