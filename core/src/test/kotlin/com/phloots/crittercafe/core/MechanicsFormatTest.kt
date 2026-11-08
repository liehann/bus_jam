package com.phloots.crittercafe.core

import com.phloots.crittercafe.core.engine.Engine
import com.phloots.crittercafe.core.io.LevelPack
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.io.PackedLevel
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Event
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.GameState
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Limits
import com.phloots.crittercafe.core.model.Move
import com.phloots.crittercafe.core.model.Status
import com.phloots.crittercafe.core.realtime.RealTime
import com.phloots.crittercafe.core.solver.Solver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v4 level format (optional mechanic fields), validation, and the shipped pack behaving exactly as before. */
class MechanicsFormatTest {
    private val packFile = File("../app/src/main/assets/levels.json")
    private val pack by lazy { LevelPackCodec.decode(packFile.readText()) }

    private fun s(id: Int, color: Int, row: Int, col: Int, dir: Dir = Dir.UP) = Container(id, ContainerSize.S, color, row, col, dir)

    /** A valid 10x12 level using every mechanic. */
    private fun everything(): Level {
        val cs = listOf(
            s(0, 0, 0, 0).copy(frozen = 2),
            s(1, 1, 0, 2).copy(tiedTo = 2),
            s(2, 2, 0, 4),
            s(3, 0, 3, 3).copy(hidden = true), // 4 stands in front of it
            s(4, 1, 2, 3, Dir.LEFT).copy(wide = true),
            s(5, 2, 6, 6), // on a turntable
        )
        val queue = cs.flatMap { c -> List(c.size.capacity) { c.color } }
        return Level(
            9, 10, 12, cs, queue, 30,
            benchColors = listOf(-1, 1, -1, 2), turntables = listOf(Cell(6, 6)), gates = listOf(Gate(Dir.UP, 4, open = false), Gate(Dir.LEFT, 7)),
            queuePreview = 18,
        )
    }

    @Test
    fun roundTripWithEveryField() {
        val l = everything()
        assertEquals(emptyList(), l.validate())
        val p = LevelPack(levels = listOf(PackedLevel(l, 3.0)))
        val json = LevelPackCodec.encode(p)
        assertEquals(p, LevelPackCodec.decode(json))
        assertEquals(p, LevelPackCodec.decode(LevelPackCodec.encode(p, prettyPrint = true)))
        for (key in listOf(
            "\"frozen\":2", "\"tiedTo\":2", "\"hidden\":true", "\"wide\":true", "\"benchColors\":[-1,1,-1,2]",
            "\"turntables\":[{\"row\":6,\"col\":6}]", "\"gates\":[{\"side\":\"UP\",\"index\":4,\"open\":false},{\"side\":\"LEFT\",\"index\":7}]",
            "\"queuePreview\":18",
        )) assertTrue(key in json, "$key in $json")
        assertEquals(LevelPackCodec.CURRENT_VERSION, LevelPackCodec.decode(json).version)
    }

    @Test
    fun defaultsAreNeverWritten() {
        val l = Level(1, 10, 12, listOf(s(0, 0, 0, 0)), List(4) { 0 })
        val json = LevelPackCodec.encode(LevelPack(levels = listOf(PackedLevel(l))))
        for (key in listOf("frozen", "tiedTo", "hidden", "wide", "benchColors", "turntables", "gates", "queuePreview")) {
            assertFalse(key in json, "$key in $json")
        }
        val back = LevelPackCodec.decode(json).levels.single().level
        assertEquals(Limits.DEFAULT_QUEUE_PREVIEW, back.queuePreview)
        assertEquals(emptyList(), back.mechanics())
    }

    @Test
    fun shippedPackReencodesByteForByteAndUsesNoMechanics() {
        val text = packFile.readText()
        assertEquals(text.trim(), LevelPackCodec.encode(pack).trim())
        for (p in pack.levels) {
            assertEquals(emptyList(), p.level.mechanics(), "level ${p.level.id}")
            // the belt is a difficulty dial (levelgen OfficialPackF5 / beltFor): 10 by default, at most 20
            assertTrue(p.level.queuePreview in Limits.DEFAULT_QUEUE_PREVIEW..20, "level ${p.level.id} belt ${p.level.queuePreview}")
        }
    }

    @Test
    fun validationOfMechanics() {
        val ok = everything()
        fun errs(l: Level) = l.validate()
        fun Level.withC(i: Int, f: (Container) -> Container) = copy(containers = containers.mapIndexed { k, c -> if (k == i) f(c) else c })
        assertTrue(errs(ok.withC(0) { it.copy(frozen = -1) }).any { "frozen" in it })
        assertTrue(errs(ok.withC(0) { it.copy(frozen = 6) }).any { "never thaw" in it })
        assertTrue(errs(ok.withC(0) { it.copy(tiedTo = 0) }).any { "tied to itself" in it })
        assertTrue(errs(ok.withC(0) { it.copy(tiedTo = 99) }).any { "out of range" in it })
        assertTrue(errs(ok.withC(0) { it.copy(tiedTo = 1) }).any { "tied itself" in it }) // 1 is tied already
        assertTrue(errs(ok.withC(5) { it.copy(tiedTo = 2) }).any { "both tied to 2" in it })
        assertTrue(errs(ok.withC(2) { it.copy(hidden = true) }).any { "nothing in front" in it })
        assertTrue(errs(ok.withC(5) { it.copy(hidden = true) }).any { "turntable" in it })
        assertTrue(errs(ok.copy(benchColors = listOf(1, 2))).any { "benchColors" in it })
        assertTrue(errs(ok.copy(benchColors = listOf(1, 1, 1, 12))).any { "out of range" in it })
        // colour 0 fits no bench
        assertTrue(errs(ok.copy(benchColors = listOf(1, 1, 2, 2))).any { "fits no dock slot" in it })
        // the wide colour-1 container needs two adjacent colour-1 (or any) benches
        assertTrue(errs(ok.copy(benchColors = listOf(1, 0, 1, 0))).any { "wide" in it })
        assertTrue(errs(ok.copy(turntables = listOf(Cell(6, 6), Cell(6, 6)))).any { "duplicate turntable" in it })
        assertTrue(errs(ok.copy(turntables = listOf(Cell(20, 0)))).any { "out of bounds" in it })
        val m = ok.copy(containers = ok.containers + Container(6, ContainerSize.M, 0, 9, 9, Dir.UP), queue = ok.queue + List(6) { 0 })
        assertEquals(emptyList(), m.validate())
        assertTrue(errs(m.copy(turntables = listOf(Cell(9, 9)))).any { "not size S" in it })
        assertTrue(errs(ok.copy(gates = listOf(Gate(Dir.UP, 10)))).any { "gate" in it })
        assertTrue(errs(ok.copy(gates = listOf(Gate(Dir.LEFT, 11), Gate(Dir.LEFT, 11, false)))).any { "duplicate gate" in it })
        assertTrue(errs(ok.copy(queuePreview = 0)).any { "queuePreview" in it })
    }

    // ---------------------------------------------------------------- the shipped pack is unchanged

    private fun readLines(file: File, pattern: Regex): Map<Int, List<Int>> =
        pattern.findAll(file.readText()).associate { m ->
            m.groupValues[1].toInt() to m.groupValues[2].split(",").filter { it.isNotBlank() }.map { it.trim().toInt() }
        }

    /** Winning lines the solver found for every official level (before the v4 mechanics; replaced levels re-solved by OfficialPackF5). */
    private val baseline by lazy {
        readLines(File("src/test/resources/official-baseline-lines.txt"), Regex("(?m)^(\\d+): ([\\d,]*)$"))
    }

    private fun assertWins(level: Level, line: List<Int>, what: String) {
        var st = GameState.initial(level)
        for (id in line) {
            val t = Engine.apply(st, Move(id))
            assertTrue(t.events.first() is Event.Released, "$what: tap $id ${t.events.first()}")
            st = t.state
        }
        assertEquals(Status.WON, st.status, what)
    }

    @Test
    fun baselineLinesStillWin() {
        assertEquals(pack.levels.size, baseline.size)
        for (p in pack.levels) assertWins(p.level, baseline.getValue(p.level.id), "level ${p.level.id}")
    }

    @Test
    fun solverFindsTheSameLinesAsBefore() {
        // v2 levels take exactly the pre-v4 solver paths. If the solver's search is changed on purpose, regenerate
        // src/test/resources/official-baseline-lines.txt.
        val diff = pack.levels.parallelStream().filter { p ->
            Solver.solve(p.level, 2_000_000)?.solution?.map { it.containerId } != baseline[p.level.id]
        }.map { it.level.id }.toList()
        assertEquals(emptyList(), diff)
    }

    @Test
    fun officialLinesStillWinIfPresent() {
        val f = File("../levelgen/out/official-lines.json")
        if (!f.exists()) return
        val lines = readLines(f, Regex("\"(\\d+)\": \\[([\\d,]*)]"))
        val byId = pack.levels.associateBy { it.level.id }
        for ((id, line) in lines) byId[id]?.let { assertWins(it.level, line, "official line $id") }
    }

    @Test
    fun baselineLinesWinInRealTime() {
        // patient real-time play of every tenth level converges to the Engine's win
        for (p in pack.levels.filter { it.level.id % 10 == 1 }) {
            var rt = RealTime.runUntilSettled(RealTime.initial(p.level)).first
            for (id in baseline.getValue(p.level.id)) {
                rt = RealTime.runUntilSettled(RealTime.tap(rt, id).first).first
            }
            assertEquals(Status.WON, rt.status, "level ${p.level.id}")
        }
    }
}
