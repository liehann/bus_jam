package com.phloots.crittercafe.core.generator.reverse

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.solver.Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MechanicPlacerTest {
    /** The mechanics preview's introduction script: 10x12, 6 colours, a short juggle and one easy stall. */
    private fun script(queuePreview: Int = Limits.DEFAULT_QUEUE_PREVIEW) = StallScript(
        width = 10, height = 12, colors = 6, lead = 1,
        stalls = listOf(JuggleDef(taps = 4, hold = 2, fill = 0.35), StallDef(2, 3, partial = 0.5)), exitFill = 8,
        sizeWeights = listOf(2.5, 1.0, 0.4), dummySizeWeights = listOf(0.6, 1.0, 0.8), candidates = 2, topUp = 1,
        queuePreview = queuePreview,
    )

    /** Base levels built from fixed seeds (the director fails on some seeds; these are the first that build). */
    private val bases: List<StallLevel> by lazy { (0L until 60L).mapNotNull { StallDirector(script(), 500 + it).run() }.take(6) }

    private fun placed(m: Mechanic, count: Int): Pair<StallLevel, MechanicLevel> {
        for ((k, sl) in bases.withIndex()) {
            val ml = MechanicPlacer.place(sl, listOf(MechanicRequest(m, count)), 11L + k) ?: continue
            return sl to ml
        }
        error("no base level took $count ${m.id}")
    }

    private fun replayWins(ml: MechanicLevel): Boolean {
        var s = GameState.initial(ml.sl.level)
        for (mv in ml.sl.line) {
            if (!Engine.isTappable(s, mv.containerId)) return false
            s = Engine.apply(s, mv).state
        }
        return s.status == Status.WON
    }

    /** Common invariants: valid, uses exactly the mechanic, the intended line still wins, it binds on that line, solvable. */
    private fun check(m: Mechanic, count: Int): Pair<StallLevel, MechanicLevel> {
        val (sl, ml) = placed(m, count)
        val lv = ml.sl.level
        assertEquals(emptyList(), lv.validate(), "valid")
        assertEquals(listOf(m), lv.mechanics(), "mechanics")
        assertEquals(count, ml.placed.size)
        assertTrue(replayWins(ml), "intended line wins")
        assertTrue(MechanicUse.measure(lv, ml.sl.line).of(m) >= count, "binds on the intended line")
        assertTrue(ml.placed.all { it.binds >= 1 }, "every instance binds")
        assertTrue(Solver.solve(lv, 2_000_000)?.solvable == true, "solver")
        // Round trip through the pack format.
        assertEquals(lv, LevelPackCodec.decode(LevelPackCodec.encode(com.phloots.crittercafe.core.io.LevelPack(levels = listOf(com.phloots.crittercafe.core.io.PackedLevel(lv))))).levels[0].level)
        // Nothing else about the board changed except the mechanic fields.
        assertEquals(sl.level.queue, lv.queue)
        assertEquals(sl.level.containers.map { Triple(it.size, it.color, Cell(it.row, it.col)) }, lv.containers.map { Triple(it.size, it.color, Cell(it.row, it.col)) })
        return sl to ml
    }

    private fun steps(ml: MechanicLevel): Map<Int, Int> = ml.sl.line.withIndex().associate { (i, mv) -> mv.containerId to i }

    @Test
    fun frozenWaitsForItsReleaseStep() {
        val (_, ml) = check(Mechanic.FROZEN, 2)
        val st = steps(ml)
        for (v in ml.sl.level.containers.filter { it.frozen > 0 }) assertEquals(st[v.id], v.frozen, "frozen #${v.id} thaws at its release")
    }

    @Test
    fun tiedPartnerLeavesFirstAndIsNotABlocker() {
        val (_, ml) = check(Mechanic.TIED, 2)
        val lv = ml.sl.level
        val st = steps(ml)
        val blockers = Quality.blockers(lv)
        for (v in lv.containers.filter { it.tiedTo >= 0 }) {
            assertTrue(st.getValue(v.tiedTo) < st.getValue(v.id))
            assertFalse(v.tiedTo in blockers[v.id], "the ribbon must add a constraint the board does not")
        }
    }

    @Test
    fun hiddenColourIsNeverNeededBeforeTheReveal() {
        val (_, ml) = check(Mechanic.HIDDEN, 2)
        val lv = ml.sl.level
        for (v in lv.containers.filter { it.hidden }) {
            assertTrue(lv.coverOf(v.id) >= 0)
            val chk = HiddenCheck.run(lv, ml.sl.line, v.id)
            assertTrue(chk.robust, chk.summary())
            // Unrevealed means blocked by its cover: never a legal move while hidden.
            var s = GameState.initial(lv)
            for (mv in ml.sl.line) {
                if (!s.isRevealed(v.id)) assertFalse(Engine.isTappable(s, v.id))
                s = Engine.apply(s, mv).state
            }
        }
        assertEquals(2, ml.hidden.size)
    }

    @Test
    fun wideTakesAnMOrL() {
        val (_, ml) = check(Mechanic.WIDE, 1)
        assertTrue(ml.sl.level.containers.filter { it.wide }.all { it.size != ContainerSize.S })
    }

    @Test
    fun benchRestrictsOneSlot() {
        val (_, ml) = check(Mechanic.COLOUR_BENCH, 1)
        assertEquals(1, ml.sl.level.benchColors.count { it >= 0 })
    }

    @Test
    fun turntableFacesItsExitAtItsRelease() {
        val (sl, ml) = check(Mechanic.TURNTABLE, 2)
        val lv = ml.sl.level
        val st = steps(ml)
        assertEquals(2, lv.turntables.size)
        for (t in lv.turntables) {
            val v = lv.containers.single { Cell(it.row, it.col) == t }
            assertEquals(ContainerSize.S, v.size)
            assertEquals(sl.level.containers[v.id].dir, v.dir.turnedClockwise(st.getValue(v.id)))
        }
    }

    @Test
    fun gateIsOpenAtEveryCrossing() {
        val (_, ml) = check(Mechanic.GATE, 2)
        val lv = ml.sl.level
        var s = GameState.initial(lv)
        for (mv in ml.sl.line) {
            val v = lv.containers[mv.containerId]
            val (side, index) = MechanicUse.exitKey(s.dirOf(v.id), v.row, v.col)
            val g = lv.gates.indexOfFirst { it.side == side && it.index == index }
            if (g >= 0) assertTrue(s.isGateOpen(g))
            s = Engine.apply(s, mv).state
        }
    }

    @Test
    fun twoMechanicsTogether() {
        val sl = bases.first()
        val ml = assertNotNull((0L until 20L).firstNotNullOfOrNull {
            MechanicPlacer.place(sl, listOf(MechanicRequest(Mechanic.FROZEN, 1), MechanicRequest(Mechanic.GATE, 1)), it)
        })
        assertEquals(listOf(Mechanic.FROZEN, Mechanic.GATE), ml.sl.level.mechanics())
        assertTrue(replayWins(ml))
        assertEquals(emptyList(), ml.sl.level.validate())
    }

    @Test
    fun sameSeedSameLevel() {
        val reqs = listOf(MechanicRequest(Mechanic.TIED, 1), MechanicRequest(Mechanic.HIDDEN, 1), MechanicRequest(Mechanic.TURNTABLE, 1))
        fun build(seed: Long) = (0 until 40).firstNotNullOfOrNull { k ->
            StallDirector(script(), seed + k).run()?.let { MechanicPlacer.place(it, reqs, seed + k) }
        }
        val a = assertNotNull(build(900))
        val b = assertNotNull(build(900))
        assertEquals(a.sl.level, b.sl.level)
        assertEquals(a.sl.level.toString(), b.sl.level.toString())
        assertEquals(a.placed, b.placed)
        assertEquals(a.sl.line, b.sl.line)
    }

    @Test
    fun queuePreviewIsADial() {
        val sl = (0L until 60L).firstNotNullOf { StallDirector(script(queuePreview = 16), 500 + it).run() }
        assertEquals(16, sl.level.queuePreview)
        assertEquals(emptyList(), sl.level.validate())
        // Default belt: the field is not written, so existing packs are unchanged.
        val plain = bases.first().level
        assertEquals(Limits.DEFAULT_QUEUE_PREVIEW, plain.queuePreview)
        assertFalse("queuePreview" in LevelPackCodec.encode(com.phloots.crittercafe.core.io.LevelPack(levels = listOf(com.phloots.crittercafe.core.io.PackedLevel(plain)))))
    }

    @Test
    fun useCountsAFrozenBindByHand() {
        // A frozen container whose path is clear from the start binds in every state before it thaws.
        val (_, ml) = placed(Mechanic.FROZEN, 1)
        val lv = ml.sl.level
        val f = lv.containers.single { it.frozen > 0 }
        val use = MechanicUse.measure(lv, ml.sl.line)
        var expect = 0
        var s = GameState.initial(lv)
        for (mv in ml.sl.line) {
            if (s.onBoard[f.id] && s.frozenRemaining(f.id) > 0 &&
                Engine.isTappable(GameState.initial(lv.copy(containers = lv.containers.map { if (it.id == f.id) it.copy(frozen = 0) else it })).let { s0 ->
                    s.copy(level = s0.level) }, f.id)) expect++
            s = Engine.apply(s, mv).state
        }
        assertEquals(expect, use.frozenBinds)
        assertTrue(expect >= 1)
    }
}
