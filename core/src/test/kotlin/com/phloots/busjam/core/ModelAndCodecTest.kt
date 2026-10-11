package com.phloots.busjam.core

import com.phloots.busjam.core.io.LevelPack
import com.phloots.busjam.core.io.LevelPackCodec
import com.phloots.busjam.core.io.PackedLevel
import com.phloots.busjam.core.model.Cell
import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.model.Vehicle
import com.phloots.busjam.core.model.VehicleType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelAndCodecTest {
    @Test
    fun cellsExtendBehindFront() {
        val v = Vehicle(0, VehicleType.VAN, 0, row = 2, col = 5, dir = Dir.UP)
        assertEquals(listOf(Cell(2, 5), Cell(3, 5), Cell(4, 5)), v.cells())
        val r = Vehicle(0, VehicleType.CAR, 0, row = 1, col = 3, dir = Dir.RIGHT)
        assertEquals(listOf(Cell(1, 3), Cell(1, 2)), r.cells())
    }

    @Test
    fun initialState() {
        val level = Level(1, 5, 5, listOf(Vehicle(0, VehicleType.CAR, 0, 1, 1, Dir.UP)), List(4) { 0 })
        val s = GameState.initial(level)
        assertEquals(1L, s.onBoard)
        assertEquals(4, s.slots.size)
        assertEquals(Status.PLAYING, s.status)
        assertEquals(0, s.frontPassenger)
        assertTrue(level.validate().isEmpty())
    }

    @Test
    fun codecRoundTrip() {
        val level = Level(7, 4, 6, listOf(Vehicle(0, VehicleType.BUS, 3, 3, 0, Dir.UP)), List(10) { 3 })
        val pack = LevelPack(levels = listOf(PackedLevel(level, 12.5)))
        assertEquals(pack, LevelPackCodec.decode(LevelPackCodec.encode(pack)))
        assertEquals(pack, LevelPackCodec.decode(LevelPackCodec.encode(pack, prettyPrint = true)))
        assertFailsWith<IllegalArgumentException> { LevelPackCodec.decode("""{"version":99,"levels":[]}""") }
    }

    @Test
    fun sampleAssetIsValid() {
        val file = File("../app/src/main/assets/levels.json")
        val pack = LevelPackCodec.decode(file.readText())
        assertTrue(pack.levels.isNotEmpty())
        for (p in pack.levels) assertEquals(emptyList(), p.level.validate(), "level ${p.level.id}")
    }
}
