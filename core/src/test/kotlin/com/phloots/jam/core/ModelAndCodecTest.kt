package com.phloots.jam.core

import com.phloots.jam.core.io.LevelPack
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.Cell
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Limits
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelAndCodecTest {
    @Test
    fun cellsExtendBehindFront() {
        val v = Container(0, ContainerSize.L, 0, row = 2, col = 5, dir = Dir.UP)
        assertEquals(listOf(Cell(2, 5), Cell(3, 5), Cell(4, 5)), v.cells())
        val r = Container(0, ContainerSize.M, 0, row = 1, col = 3, dir = Dir.RIGHT)
        assertEquals(listOf(Cell(1, 3), Cell(1, 2)), r.cells())
        assertEquals(listOf(Cell(4, 4)), Container(0, ContainerSize.S, 0, 4, 4, Dir.LEFT).cells())
    }

    @Test
    fun sizes() {
        assertEquals(listOf(1 to 4, 2 to 6, 3 to 10), ContainerSize.entries.map { it.length to it.capacity })
    }

    @Test
    fun bits128() {
        val b = Bits128.firstN(100)
        assertEquals(100, b.count())
        assertTrue(b[0] && b[63] && b[64] && b[99] && !b[100])
        assertEquals(Bits128(-1L, 0L), Bits128.firstN(64))
        assertEquals(Bits128(-1L, -1L), Bits128.firstN(128))
        assertTrue(Bits128.firstN(0).isEmpty())
        val c = b.without(70).without(3)
        assertEquals(98, c.count())
        assertFalse(c[70] || c[3])
        assertEquals(b, c.with(70).with(3))
        assertEquals(listOf(3, 70), (b andNot c).ids())
    }

    @Test
    fun initialStateFillsStaging() {
        val containers = listOf(Container(0, ContainerSize.S, 0, 1, 1, Dir.UP), Container(1, ContainerSize.M, 1, 5, 5, Dir.LEFT))
        val queue = listOf(0, 1, 1, 0, 1, 0, 1, 1, 0, 1)
        val level = Level(1, 10, 12, containers, queue)
        assertEquals(emptyList(), level.validate())
        val s = GameState.initial(level)
        assertEquals(Bits128.firstN(2), s.onBoard)
        assertEquals(List(Limits.DOCK_SLOTS) { null }, s.dock)
        val k = minOf(Limits.MAX_STAGING_SPOTS, queue.size)
        assertEquals(queue.take(k), s.staging)
        assertEquals(k, s.queueIndex)
        assertEquals(queue.size - k, s.queueRemaining)
        assertEquals((0 until k).toList(), s.stagedElementIndices())
        assertEquals(Status.PLAYING, s.status)

        val short = GameState.initial(Level(2, 10, 12, containers.take(1), List(4) { 0 }))
        assertEquals(List(4) { 0 }, short.staging)
        assertEquals(4, short.queueIndex)

        // the level's own staging size bounds the initial staging
        val long = List(30) { 0 }
        val twenty = GameState.initial(Level(3, 10, 12, List(7) { Container(it, ContainerSize.S, 0, 0, it, Dir.UP) } + Container(7, ContainerSize.S, 0, 2, 0, Dir.UP), long + List(2) { 0 }, stagingSpots = 20))
        assertEquals(20, twenty.staging.size)
        assertEquals(20, twenty.queueIndex)
    }

    @Test
    fun stagedElementIndicesFollowOldestFirstBoarding() {
        // queue colours: 0 1 0 1 0 | staged all 5; two oldest colour-0 elements (0, 2) boarded -> staging [1, 1, 0].
        val level = Level(1, 10, 12, emptyList(), listOf(0, 1, 0, 1, 0))
        val s = GameState.initial(level).copy(staging = listOf(1, 1, 0))
        assertEquals(listOf(1, 3, 4), s.stagedElementIndices())
    }

    @Test
    fun validateV2Limits() {
        val one = listOf(Container(0, ContainerSize.S, 0, 0, 0, Dir.UP))
        assertTrue(Level(1, 9, 12, one, List(4) { 0 }).validate().any { "width" in it })
        assertTrue(Level(1, 13, 12, one, List(4) { 0 }).validate().any { "width" in it })
        assertTrue(Level(1, 10, 11, one, List(4) { 0 }).validate().any { "height" in it })
        assertTrue(Level(1, 12, 15, one, List(4) { 0 }).validate().any { "height" in it })
        assertEquals(emptyList(), Level(1, 12, 14, one, List(4) { 0 }).validate())
        assertTrue(Level(1, 10, 12, one, List(5) { 0 }).validate().isNotEmpty()) // unbalanced
        assertTrue(Level(1, 10, 12, one, List(4) { 0 } + Limits.MAX_COLORS).validate().any { "queue" in it })
        // 128 S containers fit on 12x14 (168 cells); 129 do not pass.
        val many = List(129) { Container(it, ContainerSize.S, 0, it / 12, it % 12, Dir.UP) }
        assertEquals(emptyList(), Level(1, 12, 14, many.take(128), List(128 * 4) { 0 }).validate())
        assertTrue(Level(1, 12, 14, many, List(129 * 4) { 0 }).validate().any { "too many" in it })
        // staging size per level: 20..40
        for (n in listOf(20, 28, 40)) assertEquals(emptyList(), Level(1, 10, 12, one, List(4) { 0 }, n).validate())
        for (n in listOf(19, 41, 0)) assertTrue(Level(1, 10, 12, one, List(4) { 0 }, n).validate().any { "stagingSpots" in it })
    }

    @Test
    fun codecRoundTrip() {
        val level = Level(7, 10, 12, listOf(Container(0, ContainerSize.L, 3, 3, 0, Dir.UP)), List(10) { 3 })
        val pack = LevelPack(levels = listOf(PackedLevel(level, 12.5)))
        assertEquals(LevelPackCodec.CURRENT_VERSION, pack.version)
        assertEquals(2, LevelPackCodec.CURRENT_VERSION)
        assertEquals(pack, LevelPackCodec.decode(LevelPackCodec.encode(pack)))
        assertEquals(pack, LevelPackCodec.decode(LevelPackCodec.encode(pack, prettyPrint = true)))
        assertTrue(""""containers":[{"id":0,"size":"L"""" in LevelPackCodec.encode(pack))
        assertFailsWith<IllegalArgumentException> { LevelPackCodec.decode("""{"version":99,"levels":[]}""") }
    }

    @Test
    fun stagingSpotsFieldIsOptionalAndOnlyWrittenWhenNotDefault() {
        val level = Level(7, 10, 12, listOf(Container(0, ContainerSize.L, 3, 3, 0, Dir.UP)), List(10) { 3 })
        assertEquals(Limits.MAX_STAGING_SPOTS, level.stagingSpots)
        // default (40) is never written, so existing packs re-encode byte for byte
        val json = LevelPackCodec.encode(LevelPack(levels = listOf(PackedLevel(level))))
        assertFalse("stagingSpots" in json)
        // absent -> 40
        assertEquals(40, LevelPackCodec.decode(json).levels.single().level.stagingSpots)
        // set -> written and read back
        val small = level.copy(stagingSpots = 20)
        val json20 = LevelPackCodec.encode(LevelPack(levels = listOf(PackedLevel(small))))
        assertTrue("\"stagingSpots\":20" in json20)
        assertEquals(small, LevelPackCodec.decode(json20).levels.single().level)
    }

    @Test
    fun shippedAssetDecodesAndIsValidOnceV2() {
        val file = File("../app/src/main/assets/levels.json")
        val pack = LevelPackCodec.decode(file.readText())
        assertTrue(pack.levels.isNotEmpty())
        // v1 packs (version 1) decode but do not meet v2 limits; validate only v2 packs.
        if (pack.version >= 2) {
            for (p in pack.levels) assertEquals(emptyList(), p.level.validate(), "level ${p.level.id}")
        }
    }
}
