package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.io.LevelPackCodec
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The shipped campaign is reproducible from official-meta.json: a sample of slots (a plain one, one with a repaired
 * queue, one built with the clued-squeeze construction when the pack has one) is rebuilt from its provenance record
 * and must equal the shipped level. The full check is OfficialReplay's main.
 */
class OfficialReplayTest {
    @Test
    fun shippedLevelsReplayFromTheirProvenance() {
        val pack = LevelPackCodec.decode(File("../app/src/main/assets/levels.json").readText()).levels.map { it.level }
        val meta = Provenance.read(File("../levelgen/out/official-meta.json"))
        val records = pack.associateWith { meta[Provenance.hash(it)] }
        val sample = listOfNotNull(
            pack.first { it.id == 2 },
            pack.firstOrNull { (records[it]?.get("repairedElements")?.jsonPrimitive?.int ?: 0) > 0 },
            pack.firstOrNull { constructionOf(records[it]) != "off" },
        ).distinct()
        for (lv in sample) {
            val m = assertNotNull(records[lv], "L${lv.id}: no provenance record")
            assertEquals(lv, replay(lv.id, m), "L${lv.id} (${constructionOf(m)})")
        }
    }
}
