package com.phloots.crittercafe.levelgen.reverse

import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.model.Move
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.CRC32

/**
 * Where a generated level came from, keyed by its content hash so that any level seen in analytics can be traced
 * back to the generator run that made it.
 *
 * The hash is the app analytics' level id: CRC32 of `level.toString()` (UTF-8), 8 lower-case hex digits. It covers
 * every field of the [Level] as shipped (id, board, queue, ring size, mechanics).
 */
object Provenance {
    fun hash(level: Level): String {
        val c = CRC32()
        c.update(level.toString().toByteArray())
        return "%08x".format(c.value)
    }

    /** `git rev-parse --short HEAD`, plus "-dirty" when tracked files differ from HEAD; "unknown" outside git. */
    val commit: String by lazy {
        fun git(vararg a: String): String? = try {
            val p = ProcessBuilder(listOf("git") + a).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0) out else null
        } catch (e: Exception) { null }
        val head = git("rev-parse", "--short", "HEAD") ?: return@lazy "unknown"
        val dirty = git("status", "--porcelain", "--untracked-files=no")?.isNotEmpty() ?: false
        head + if (dirty) "-dirty" else ""
    }

    /**
     * One level's record: its slot ([level].id), the recipe, the pack seed, the director seed and try index of the
     * chosen try, the generator commit, its winning [line], and any [extra] fields (ring size, mechanics, ...).
     */
    fun entry(level: Level, recipe: String, seed: Long, directorSeed: Long, tryIndex: Int, line: List<Move>?,
              extra: Map<String, JsonElement> = emptyMap()): Pair<String, JsonObject> =
        hash(level) to JsonObject(linkedMapOf<String, JsonElement>(
            "level" to JsonPrimitive(level.id),
            "recipe" to JsonPrimitive(recipe),
            "seed" to JsonPrimitive(seed),
            "directorSeed" to JsonPrimitive(directorSeed),
            "try" to JsonPrimitive(tryIndex),
            "commit" to JsonPrimitive(commit),
        ).apply {
            putAll(extra)
            if (line != null) put("line", JsonArray(line.map { JsonPrimitive(it.containerId) }))
        })

    fun read(file: File): MutableMap<String, JsonObject> {
        if (!file.exists()) return LinkedHashMap()
        return Json.parseToJsonElement(file.readText()).jsonObject.mapValuesTo(LinkedHashMap()) { it.value.jsonObject }
    }

    /** Writes `{hash: record}` one record per line, ordered by level number. */
    fun write(file: File, entries: Map<String, JsonObject>) {
        file.parentFile?.mkdirs()
        val sorted = entries.entries.sortedWith(compareBy({ (it.value["level"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }, { it.key }))
        file.writeText(sorted.joinToString(",\n", "{\n", "\n}\n") { (h, o) -> "  \"$h\": $o" })
    }
}
