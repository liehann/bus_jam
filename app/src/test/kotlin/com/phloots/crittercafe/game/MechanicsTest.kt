package com.phloots.crittercafe.game

import com.phloots.crittercafe.ui.game.MechanicIntroQueue
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Mechanic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechanicsTest {
    @Test
    fun introsFollowTheFixedOrderAndSkipSeenAndUnknown() {
        val level = setOf(Mechanics.GATE, "lasers", Mechanics.FROZEN, Mechanics.TIED)
        assertEquals(listOf(Mechanics.FROZEN, Mechanics.TIED, Mechanics.GATE), introsToShow(level, emptySet()))
        assertEquals(listOf(Mechanics.GATE), introsToShow(level, setOf(Mechanics.FROZEN, Mechanics.TIED)))
        assertTrue(introsToShow(emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun adapterReportsCoreMechanicsAsIntroIds() {
        val plain = Level(1, 3, 3, listOf(Container(0, ContainerSize.S, 0, 1, 1, Dir.UP)), queue = listOf(0, 0, 0, 0))
        assertTrue(levelMechanics(plain).isEmpty())

        val frozen = plain.copy(containers = plain.containers.map { it.copy(frozen = 1) })
        assertEquals(setOf(Mechanics.FROZEN), levelMechanics(frozen))
        val gated = plain.copy(gates = listOf(Gate(Dir.UP, 1)), turntables = listOf(Cell(1, 1)))
        assertEquals(setOf(Mechanics.TURNTABLE, Mechanics.GATE), levelMechanics(gated))
        assertEquals(setOf(Mechanics.COLOUR_BENCH), levelMechanics(plain.copy(benchColors = listOf(0, -1, -1, -1))))
    }

    @Test
    fun everyCoreMechanicHasAnIntro() {
        assertEquals(Mechanic.entries.map { it.id }.toSet(), Mechanics.ALL.toSet())
    }

    @Test
    fun queueBlocksUntilLoadedAndStepsThroughCards() {
        val q = MechanicIntroQueue(null)
        assertTrue("unknown seen set: play waits", q.blocking)
        assertNull(q.current)
        q.load(listOf(Mechanics.FROZEN, Mechanics.WIDE))
        q.load(listOf(Mechanics.GATE)) // ignored once loaded
        assertEquals(Mechanics.FROZEN, q.current)
        assertEquals(1 to 2, q.step to q.total)
        assertEquals(Mechanics.FROZEN, q.dismiss())
        assertEquals(Mechanics.WIDE, q.current)
        assertEquals(2, q.step)
        assertTrue(q.blocking)
        assertEquals(Mechanics.WIDE, q.dismiss())
        assertFalse(q.blocking)
        assertNull(q.dismiss())
        assertFalse(MechanicIntroQueue(emptyList()).blocking)
    }
}
