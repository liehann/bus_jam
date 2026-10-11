package com.phloots.jam.levelgen

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * Packs must be byte-identical across runs, thread counts and machines (all float math in generation/scoring is
 * StrictMath; selection uses node counts, never wall-clock). Small in-process runs, compared byte for byte.
 */
class DeterminismTest {
    private fun tmp(): File = Files.createTempDirectory("levelgen-det").toFile().also { it.deleteOnExit() }

    @Test
    fun campaignRunIsByteIdentical() {
        val dir = tmp()
        fun run(name: String, threads: Int): ByteArray {
            val out = File(dir, "$name.json")
            main(arrayOf("--cycles", "1", "--scale", "0.02", "--threads", "$threads",
                "--out", out.path, "--csv", File(dir, "$name.csv").path))
            return out.readBytes()
        }
        val a = run("a", 4)
        val b = run("b", 2)
        assertTrue(a.isNotEmpty())
        assertContentEquals(a, b)
    }

    @Test
    fun motifPreviewIsByteIdentical() {
        val dir = tmp()
        fun run(name: String, threads: Int): ByteArray {
            val out = File(dir, "$name.json")
            motifPreview(4, baseSeed = 3, threads = threads, seedsPerSlot = 2, outPath = out.path)
            return out.readBytes()
        }
        val a = run("a", 3)
        val b = run("b", 1)
        assertTrue(a.isNotEmpty())
        assertContentEquals(a, b)
    }
}
