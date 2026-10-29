package com.phloots.crittercafe.data

import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.io.PackedLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * App-side fields of one pack entry, beside its "level" (core's codec ignores them): [theme] is a
 * [com.phloots.crittercafe.ui.skin.critter.CafeThemes] id the level is drawn in, [group] the level list heading the
 * level belongs under. Both null in the official campaign.
 */
data class LevelMeta(val theme: String? = null, val group: String? = null) {
    companion object {
        /** One [LevelMeta] per entry of pack [json] (all empty when it has none). */
        fun parse(json: String): List<LevelMeta> {
            val levels = (Json.parseToJsonElement(json) as? JsonObject)?.get("levels") as? JsonArray ?: return emptyList()
            return levels.map { e ->
                val o = e as? JsonObject
                fun str(k: String) = o?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                LevelMeta(str("theme"), str("group"))
            }
        }
    }
}

/** Loads level packs from the assets (cached per file). Pack order == list order; levels[0] is level 1. */
class LevelRepository(private val assets: AssetSource) {
    private val mutex = Mutex()
    private val cache = HashMap<String, List<PackedLevel>>()
    private val metaCache = HashMap<String, List<LevelMeta>>()

    /** The levels of pack [file] with their difficulty score / metrics / motif tags. */
    suspend fun levels(file: String = PackInfo.CAMPAIGN_FILE): List<PackedLevel> = mutex.withLock {
        cache[file] ?: withContext(Dispatchers.IO) { LevelPackCodec.decode(assets.read(file)).levels }
            .also { cache[file] = it }
    }

    /** The per-level [LevelMeta] of pack [file], one per level. */
    suspend fun meta(file: String = PackInfo.CAMPAIGN_FILE): List<LevelMeta> = mutex.withLock {
        metaCache[file] ?: withContext(Dispatchers.IO) { LevelMeta.parse(assets.read(file)) }
            .also { metaCache[file] = it }
    }
}
