package com.phloots.jam.core.io

import com.phloots.jam.core.model.Level
import com.phloots.jam.core.scoring.DifficultyReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One shipped level: definition + its difficulty score (+ optional full metrics). */
@Serializable
data class PackedLevel(
    val level: Level,
    val score: Double = 0.0,
    val metrics: DifficultyReport? = null,
)

/** The levels.json payload. Campaign order == list order (levels[0] is level 1). */
@Serializable
data class LevelPack(
    val version: Int = LevelPackCodec.CURRENT_VERSION,
    val levels: List<PackedLevel>,
)

/**
 * JSON codec for `app/src/main/assets/levels.json`. Unknown keys are ignored on decode.
 * Versions: 1 = v1 rules (still decodable), 2 = v2 rules (staging area, S/M/L = 1/2/3 cells). Encode writes [CURRENT_VERSION].
 */
object LevelPackCodec {
    const val CURRENT_VERSION = 2

    private val compact = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val pretty = Json(compact) { prettyPrint = true }

    fun encode(pack: LevelPack, prettyPrint: Boolean = false): String =
        (if (prettyPrint) pretty else compact).encodeToString(LevelPack.serializer(), pack)

    /** @throws IllegalArgumentException on malformed JSON or unsupported version. */
    fun decode(json: String): LevelPack {
        val pack = try {
            compact.decodeFromString(LevelPack.serializer(), json)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IllegalArgumentException("malformed level pack: ${e.message}", e)
        }
        require(pack.version in 1..CURRENT_VERSION) { "unsupported level pack version ${pack.version}" }
        return pack
    }
}
