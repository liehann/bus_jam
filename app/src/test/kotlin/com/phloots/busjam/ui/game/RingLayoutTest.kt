package com.phloots.busjam.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.phloots.busjam.ui.skin.FieldStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Belt geometry (Critter Café style) on a Pixel 9 Pro sized field (dp = px at density 1). The belt continues the
 * ring: plates one seat apart, the front place one seat upstream of the entry, and the belt's end exactly at the
 * entry, so a plate moving up with the ring meets its seat there without a jump in position or speed.
 */
class RingLayoutTest {
    private val belt = FieldStyle(queueBelt = true, dockBandDp = 92f)

    // the default config: gate window centred at position 3.5, entry at N / 2
    private val gate = 3.5f

    private fun layout(seats: Int) =
        RingLayout(411f, 860f, 12, 14, Density(1f), seats, style = belt, entryFromGate = seats / 2f - gate)

    private fun assertNear(expected: Offset, actual: Offset, tolerance: Float = 0.5f) =
        assertTrue("$expected vs $actual", (expected - actual).getDistance() < tolerance)

    @Test
    fun beltPlatesMatchTheLoopAndTenFitOnABigRing() {
        val lay = layout(40)
        assertEquals(lay.seatToken, lay.queueToken, 0f)
        assertEquals(RingLayout.QUEUE_MAX, lay.queuePlaces(Offset.Zero).size)
    }

    @Test
    fun theBeltEndsAtTheEntrySeat() {
        for (seats in listOf(20, 28, 40)) {
            val lay = layout(seats)
            assertNear(lay.seatPoint(seats / 2f, gate), lay.queuePoint(-1f, emptyList()))
        }
    }

    @Test
    fun beltPlatesMoveAtRingSpeed() {
        for (seats in listOf(20, 28, 40)) {
            val lay = layout(seats)
            // equal arc length per step along the belt as along the ring: sample both over the last seat step
            var beltLen = 0f
            var ringLen = 0f
            var b = lay.queuePoint(0f, emptyList())
            var r = lay.seatPoint(seats / 2f - 1f, gate)
            for (i in 1..200) {
                val f = i / 200f
                val b2 = lay.queuePoint(-f, emptyList())
                val r2 = lay.seatPoint(seats / 2f - 1f + f, gate)
                beltLen += (b2 - b).getDistance()
                ringLen += (r2 - r).getDistance()
                b = b2
                r = r2
            }
            assertEquals("seats $seats", ringLen, beltLen, ringLen * 0.02f)
        }
    }

    @Test
    fun theBeltStaysClearOfTheLoopUntilItJoins() {
        for (seats in listOf(20, 28, 40)) {
            val lay = layout(seats)
            val loop = (0 until 400).map { lay.trackPoint(lay.perimeter * it / 400) }
            val clear = lay.beltBand / 2 + lay.seatR * 1.3f // the two bands' half widths, rims included
            var d = lay.beltBand * 1.6f // the last stretch before the entry merges into the loop
            while (d < lay.beltLength) {
                val p = lay.beltPoint(d)
                val nearest = loop.minOf { (it - p).getDistance() }
                assertTrue("seats $seats: belt at $d is $nearest from the loop (< $clear)", nearest >= clear)
                d += 2f
            }
        }
    }
}
