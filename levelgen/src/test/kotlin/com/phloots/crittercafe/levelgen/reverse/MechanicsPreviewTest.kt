package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.generator.reverse.HybridFitnessConfig
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.scoring.DifficultyConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MechanicsPreviewTest {
    private fun intro(m: Mechanic) = mechRecipes().withIndex().first { it.value.intro && it.value.mechanics == listOf(m) }

    @Test
    fun introSameSeedSameLevel() {
        val (idx, r) = intro(Mechanic.TIED)
        fun build() = assertNotNull(buildMech(r, idx, 3L, 6, HybridFitnessConfig.DEFAULT, DifficultyConfig.DEFAULT, MechStats()))
        val a = build()
        val b = build()
        assertEquals(a.tryIndex, b.tryIndex)
        assertEquals(a.level, b.level)
        assertEquals(Provenance.hash(a.level), Provenance.hash(b.level))
        assertEquals(listOf(Mechanic.TIED), a.level.mechanics())
        assertTrue(a.useIntended.of(Mechanic.TIED) >= 1)
    }

    @Test
    fun everyMechanicHasAnIntroAndThreeHarderLevels() {
        val rs = mechRecipes()
        for (m in Mechanic.entries) {
            assertEquals(1, rs.count { it.intro && it.mechanics == listOf(m) }, m.id)
            assertEquals(3, rs.count { !it.intro && it.mechanics == listOf(m) }, m.id)
        }
        assertTrue(rs.count { it.mechanics.size == 2 } >= 3)
    }

    @Test
    fun hashIsTheAnalyticsCrc() {
        val lv = intro(Mechanic.GATE).let { (idx, r) -> buildMech(r, idx, 1L, 10, HybridFitnessConfig.DEFAULT, DifficultyConfig.DEFAULT, MechStats()) }!!.level
        val c = CRC32()
        c.update(lv.toString().toByteArray())
        val h = Provenance.hash(lv)
        assertEquals(8, h.length)
        assertEquals(c.value, h.toLong(16))
        // Record round trip, keyed by hash.
        val f = File(Files.createTempDirectory("prov").toFile(), "meta.json")
        Provenance.write(f, mapOf(Provenance.entry(lv, "intro: gate", 1L, 42L, 3, listOf(Move(2), Move(0)), mapOf("ring" to JsonPrimitive(40)))))
        val back = Provenance.read(f).getValue(h)
        assertEquals("intro: gate", back["recipe"]!!.jsonPrimitive.content)
        assertEquals("42", back["directorSeed"]!!.jsonPrimitive.content)
        assertEquals("3", back["try"]!!.jsonPrimitive.content)
        assertEquals(listOf("2", "0"), back["line"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(back["commit"]!!.jsonPrimitive.content.isNotEmpty())
    }
}
