package com.phloots.busjam.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PackCatalogTest {
    private class FakeAssets(private val files: Map<String, String>) : AssetSource {
        override fun list(dir: String) =
            files.keys.filter { it.startsWith("$dir/") }.map { it.removePrefix("$dir/") }.filter { '/' !in it }
        override fun read(path: String) = files.getValue(path)
    }

    private val packFiles = mapOf(
        "levels.json" to "{}",
        "packs/reverse-preview.json" to "{}",
        "packs/motif-preview.json" to "{}",
        "packs/notes.txt" to "ignored",
    )

    @Test
    fun releaseBuildsOnlySeeTheCampaign() {
        assertEquals(listOf(PackInfo.CAMPAIGN), PackCatalog.discover(FakeAssets(packFiles), includeExtra = false))
    }

    @Test
    fun labelsFromFileNamesWithoutAnIndex() {
        val packs = PackCatalog.discover(FakeAssets(packFiles), includeExtra = true)
        assertEquals(listOf("levels.json", "packs/motif-preview.json", "packs/reverse-preview.json"), packs.map { it.file })
        assertEquals(listOf("Campaign", "Motif preview", "Reverse preview"), packs.map { it.label })
    }

    @Test
    fun indexProvidesLabelsAndDescriptions() {
        val index = """
            [
              {"file": "motif-preview.json", "label": "Motifs", "description": "Hand-placed motifs"},
              {"file": "packs/reverse-preview.json", "label": "Reverse", "description": "Reverse generator"},
              {"file": "packs/missing.json", "label": "Not shipped"},
              {"file": "levels.json", "description": "Current campaign"}
            ]
        """.trimIndent()
        val packs = PackCatalog.discover(FakeAssets(packFiles + ("packs/index.json" to index)), includeExtra = true)
        assertEquals(
            listOf(
                PackInfo("levels.json", "Campaign", "Current campaign"),
                PackInfo("packs/motif-preview.json", "Motifs", "Hand-placed motifs"),
                PackInfo("packs/reverse-preview.json", "Reverse", "Reverse generator"),
            ),
            packs,
        )
    }

    @Test
    fun malformedIndexFallsBackToFileNames() {
        val packs = PackCatalog.discover(FakeAssets(packFiles + ("packs/index.json" to "{oops")), includeExtra = true)
        assertEquals(listOf("Campaign", "Motif preview", "Reverse preview"), packs.map { it.label })
    }

    @Test
    fun noPacksDirectory() {
        assertEquals(listOf(PackInfo.CAMPAIGN), PackCatalog.discover(FakeAssets(mapOf("levels.json" to "{}")), true))
        assertEquals("Fork test 2", PackCatalog.labelFromFileName("fork_test-2.json"))
    }
}
