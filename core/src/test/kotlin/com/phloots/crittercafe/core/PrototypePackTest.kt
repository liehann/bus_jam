package com.phloots.crittercafe.core

import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.model.Mechanic
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.realtime.RealTime
import com.phloots.crittercafe.core.solver.Solver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The prototype campaign (tools/prototype_pack.py): every level loads, validates, is solvable and wins in real time. */
class PrototypePackTest {
    private val packFile = File("../app/src/prototype/assets/levels.json")
    private val pack by lazy { LevelPackCodec.decode(packFile.readText()) }

    @Test
    fun loadsAndValidates() {
        // 7 mechanic blocks of 3 and 4 mixes, then the rebuilt levels in (old, new) pairs
        assertTrue(pack.levels.size >= 25 && (pack.levels.size - 25) % 2 == 0, "${pack.levels.size} levels")
        for ((i, p) in pack.levels.withIndex()) assertEquals(emptyList(), p.level.validate(), "level ${i + 1}")
    }

    @Test
    fun mechanicBlocksInOrder() {
        // three levels per mechanic, in Mechanic order, each using its mechanic (the first one shows the intro)
        for ((b, m) in Mechanic.entries.withIndex()) {
            for (k in 0 until 3) {
                val level = pack.levels[b * 3 + k].level
                assertEquals(listOf(m), level.mechanics(), "level ${b * 3 + k + 1}")
            }
        }
    }

    @Test
    fun rebuiltLevelsComeInOldNewPairs() {
        val rebuilt = pack.levels.drop(25)
        for (pair in rebuilt.chunked(2)) {
            val slot = pair[0].motifs.single { it.startsWith("Slot: L") }
            assertEquals(slot, pair[1].motifs.single { it.startsWith("Slot: L") })
            assertTrue("Prototype: old (shipped campaign)" in pair[0].motifs && "Prototype: new (rebuilt)" in pair[1].motifs, slot)
            for (p in pair) assertTrue(p.level.queuePreview <= 20, "$slot belt ${p.level.queuePreview}")
        }
    }

    @Test
    fun everyLevelIsSolvableAndWinsInRealTime() {
        val results = pack.levels.parallelStream().map { p -> p.level to Solver.solve(p.level, 20_000_000) }.toList()
        for ((i, r) in results.withIndex()) {
            val (level, sol) = r
            assertNotNull(sol, "level ${i + 1}: solver budget exceeded")
            assertTrue(sol.solvable, "level ${i + 1} not solvable")
            var rt = RealTime.runUntilSettled(RealTime.initial(level)).first
            for (m in sol.solution) rt = RealTime.runUntilSettled(RealTime.tap(rt, m.containerId).first).first
            assertEquals(Status.WON, rt.status, "level ${i + 1} real-time replay")
        }
    }
}
