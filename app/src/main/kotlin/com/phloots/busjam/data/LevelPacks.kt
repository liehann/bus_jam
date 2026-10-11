package com.phloots.busjam.data

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A level pack shipped in the assets. [file] is the asset path ("levels.json" or "packs/<name>.json") and is also the
 * key progress is stored under.
 */
data class PackInfo(val file: String, val label: String, val description: String = "") {
    val isCampaign: Boolean get() = file == CAMPAIGN_FILE

    companion object {
        const val CAMPAIGN_FILE = "levels.json"
        val CAMPAIGN = PackInfo(CAMPAIGN_FILE, "Campaign", "The shipped campaign")
    }
}

/** Minimal asset access, so pack discovery can be tested without Android. */
interface AssetSource {
    /** File names directly inside [dir] (empty if it does not exist). */
    fun list(dir: String): List<String>
    fun read(path: String): String
}

class AndroidAssetSource(private val assets: AssetManager) : AssetSource {
    override fun list(dir: String): List<String> = runCatching { assets.list(dir)?.toList() }.getOrNull().orEmpty()
    override fun read(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
}

/**
 * Finds the level packs: always the campaign (`levels.json`) first; with [includeExtra] (debug builds) also every
 * `packs/<name>.json` in name order. Labels / descriptions come from `packs/index.json`
 * (`[{"file": "...", "label": "...", "description": "..."}]`, `file` with or without the `packs/` prefix) when it
 * lists the file, else from the file name ("motif-preview.json" -> "Motif preview"). An index entry for
 * `levels.json` may override the campaign's label / description.
 */
object PackCatalog {
    const val DIR = "packs"
    const val INDEX = "index.json"

    fun discover(assets: AssetSource, includeExtra: Boolean): List<PackInfo> {
        if (!includeExtra) return listOf(PackInfo.CAMPAIGN)
        val index = readIndex(assets)
        val campaign = index[PackInfo.CAMPAIGN_FILE]?.let {
            PackInfo.CAMPAIGN.copy(label = it.label.ifBlank { PackInfo.CAMPAIGN.label }, description = it.description)
        } ?: PackInfo.CAMPAIGN
        val extra = assets.list(DIR)
            .filter { it.endsWith(".json") && it != INDEX }
            .sorted()
            .map { name ->
                val file = "$DIR/$name"
                val entry = index[file]
                PackInfo(file, entry?.label?.takeIf { it.isNotBlank() } ?: labelFromFileName(name), entry?.description.orEmpty())
            }
        return listOf(campaign) + extra
    }

    /** "motif-preview.json" -> "Motif preview". */
    fun labelFromFileName(name: String): String {
        val base = name.substringAfterLast('/').removeSuffix(".json").replace('-', ' ').replace('_', ' ').trim()
        return base.replaceFirstChar { it.uppercaseChar() }.ifBlank { name }
    }

    private fun readIndex(assets: AssetSource): Map<String, PackInfo> {
        if (INDEX !in assets.list(DIR)) return emptyMap()
        val root = runCatching { Json.parseToJsonElement(assets.read("$DIR/$INDEX")) }.getOrNull() as? JsonArray
            ?: return emptyMap()
        val out = HashMap<String, PackInfo>()
        for (e in root) {
            val o = e as? JsonObject ?: continue
            fun str(k: String) = (o[k])?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val raw = str("file") ?: continue
            val file = when {
                raw == PackInfo.CAMPAIGN_FILE || raw.startsWith("$DIR/") -> raw
                else -> "$DIR/$raw"
            }
            out[file] = PackInfo(file, str("label").orEmpty(), str("description").orEmpty())
        }
        return out
    }
}
