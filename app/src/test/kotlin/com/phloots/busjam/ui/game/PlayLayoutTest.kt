package com.phloots.busjam.ui.game

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Board cell sizes for the 40-spot staging on common phone sizes (dp = px at density 1). */
class PlayLayoutTest {
    private val d = Density(1f)

    // play field = screen - status/nav bars (72) - top bar (44) - buttons (~56) - paddings; width - 16
    private fun field(w: Int, h: Int) = (w - 16f) to (h - 72f - 44f - 64f - 8f)

    @Test
    fun regularPhoneKeepsTheFullLayout() {
        val (w, h) = field(411, 914)
        val lay = PlayLayout.fit(w, h, 12, 14, d, stagingSpots = 40)
        assertFalse(lay.compact)
        assertEquals(w / 12, lay.cell, 0.01f) // width-limited, ~32.9dp
        assertEquals(3, lay.stagingRows)
    }

    @Test
    fun smallPhoneSwitchesToCompact() {
        val (w, h) = field(360, 640)
        val regular = PlayLayout(w, h, 12, 14, d, stagingSpots = 40)
        val lay = PlayLayout.fit(w, h, 12, 14, d, stagingSpots = 40)
        assertTrue(lay.compact)
        assertTrue("compact ${lay.cell} vs regular ${regular.cell}", lay.cell > regular.cell + 3f)
        assertEquals(2, lay.stagingRows)
        val ten = PlayLayout.fit(w, h, 10, 12, d, stagingSpots = 40)
        assertTrue("10x12 cell ${ten.cell}", ten.cell >= 28f)
    }

    @Test
    fun everyStagingSpotIsOnScreenAndDistinct() {
        for (n in listOf(7, 40)) {
            val lay = PlayLayout.fit(395f, 650f, 12, 14, d, stagingSpots = n)
            val centres = (0 until n).map { lay.stagingCenter(it) }
            assertEquals(n, centres.toSet().size)
            assertTrue(centres.all { it.x in 0f..395f && it.y < lay.slotsTop })
            // snake order: consecutive spots are neighbours
            for (i in 1 until n) assertTrue((centres[i] - centres[i - 1]).getDistance() <= lay.spotPitch * 1.01f)
        }
    }
}
