package com.phloots.busjam.ui.game

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import com.phloots.busjam.ui.skin.FieldStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Belt geometry (Critter Café style) and the step-on glide, on a Pixel 9 Pro sized field (dp = px at density 1). */
class RingLayoutTest {
    private val belt = FieldStyle(queueBelt = true, dockBandDp = 92f)

    // the default config: gate window centred at position 3.5, entry at N / 2
    private val gate = 3.5f

    private fun layout(seats: Int) =
        RingLayout(411f, 860f, 12, 14, Density(1f), seats, style = belt, entryFromGate = seats / 2f - gate)

    private fun assertNear(expected: Offset, actual: Offset) =
        assertTrue("$expected vs $actual", (expected - actual).getDistance() < 0.5f)

    @Test
    fun beltPlatesMatchTheLoopAndTenFitOnABigRing() {
        val lay = layout(40)
        assertEquals(lay.seatToken, lay.queueToken, 0f)
        assertEquals(RingLayout.QUEUE_MAX, lay.queuePlaces(Offset.Zero).size)
    }

    @Test
    fun steppingOnStartsAtTheFrontPlaceAndEndsOnTheSeat() {
        for (seats in listOf(20, 28, 40)) {
            val lay = layout(seats)
            val places = lay.queuePlaces(Offset.Zero)
            val entry = seats / 2f
            assertNear(places[0], lay.enteringPoint(0f, entry, gate, places))
            assertNear(lay.seatPoint(entry + 1f, gate), lay.enteringPoint(1f, entry, gate, places))
            // the queue slides: place k at slide 1 is where place k + 1 was
            assertNear(places[1], lay.queuePoint(0f + 1f, places))
        }
    }

    @Test
    fun steppingOnMovesWithoutJumps() {
        val lay = layout(40)
        val places = lay.queuePlaces(Offset.Zero)
        var prev = lay.enteringPoint(0f, 20f, gate, places)
        for (i in 1..100) {
            val p = lay.enteringPoint(i / 100f, 20f, gate, places)
            assertTrue("step $i", (p - prev).getDistance() < lay.pitch * 0.2f)
            prev = p
        }
    }
}
