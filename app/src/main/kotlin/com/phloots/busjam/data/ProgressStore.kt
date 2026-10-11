package com.phloots.busjam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Player progress. Level numbers are 1-based campaign positions. */
data class Progress(
    /** Highest playable level number (>= 1). */
    val highestUnlocked: Int = 1,
    /** Best stars (1..3) per beaten level number. */
    val stars: Map<Int, Int> = emptyMap(),
) {
    fun isUnlocked(levelNumber: Int) = levelNumber <= highestUnlocked
    fun starsFor(levelNumber: Int) = stars[levelNumber] ?: 0

    /** First unlocked level without stars, or the highest unlocked if all are beaten; clamped to [count]. */
    fun firstUnbeaten(count: Int): Int {
        if (count <= 0) return 1
        val top = highestUnlocked.coerceIn(1, count)
        return (1..top).firstOrNull { it !in stars } ?: top
    }
}

/** Something that can record a win; lets the ViewModel be tested without DataStore. */
fun interface ProgressSink {
    suspend fun recordWin(levelNumber: Int, stars: Int)
}

/** The app's single preferences file (progress of every pack + the selected pack). */
val Context.progressDataStore: DataStore<Preferences> by preferencesDataStore(name = "progress")

/**
 * DataStore Preferences backed progress of one level pack ([pack] = its asset file): highest unlocked level and best
 * stars per level. The campaign keeps the original un-prefixed keys (existing progress stays valid); every other pack
 * prefixes its keys with `pack[<file>]/`, so packs never collide.
 */
class ProgressStore(
    private val dataStore: DataStore<Preferences>,
    val pack: String = PackInfo.CAMPAIGN_FILE,
) : ProgressSink {
    constructor(context: Context) : this(context.applicationContext.progressDataStore)

    private val prefix = if (pack == PackInfo.CAMPAIGN_FILE) "" else "pack[$pack]/"
    private val starsPrefix = prefix + STARS
    private val highestUnlocked = intPreferencesKey(prefix + HIGHEST_UNLOCKED)
    private fun starsKey(levelNumber: Int) = intPreferencesKey(starsPrefix + levelNumber)

    val progress: Flow<Progress> = dataStore.data.map { prefs ->
        val stars = HashMap<Int, Int>()
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            if (name.startsWith(starsPrefix) && value is Int) {
                name.removePrefix(starsPrefix).toIntOrNull()?.let { stars[it] = value }
            }
        }
        Progress(highestUnlocked = prefs[highestUnlocked] ?: 1, stars = stars)
    }

    /** Keeps the best star count and unlocks the next level. */
    override suspend fun recordWin(levelNumber: Int, stars: Int) {
        dataStore.edit { prefs ->
            val key = starsKey(levelNumber)
            prefs[key] = maxOf(prefs[key] ?: 0, stars.coerceIn(1, 3))
            prefs[highestUnlocked] = maxOf(prefs[highestUnlocked] ?: 1, levelNumber + 1)
        }
    }

    private companion object {
        const val STARS = "stars_"
        const val HIGHEST_UNLOCKED = "highest_unlocked"
    }
}

/** Which pack the (debug) home screen has selected; stored next to the progress. */
class PackSelectionStore(private val dataStore: DataStore<Preferences>) {
    val selected: Flow<String?> = dataStore.data.map { it[SELECTED] }

    suspend fun select(file: String) {
        dataStore.edit { it[SELECTED] = file }
    }

    private companion object {
        val SELECTED = stringPreferencesKey("selected_pack")
    }
}

/** Debug setting: real-time carousel (true) or turn-based staging (false); null = not chosen yet. */
class GameModeStore(private val dataStore: DataStore<Preferences>) {
    val realTime: Flow<Boolean?> = dataStore.data.map { it[REAL_TIME] }

    suspend fun setRealTime(on: Boolean) {
        dataStore.edit { it[REAL_TIME] = on }
    }

    private companion object {
        val REAL_TIME = androidx.datastore.preferences.core.booleanPreferencesKey("real_time_mode")
    }
}

/** Debug setting: Critter Café skin (true) or the original flat look (false); null = not chosen yet. */
class SkinStore(private val dataStore: DataStore<Preferences>) {
    val critterCafe: Flow<Boolean?> = dataStore.data.map { it[CRITTER_CAFE] }

    suspend fun setCritterCafe(on: Boolean) {
        dataStore.edit { it[CRITTER_CAFE] = on }
    }

    private companion object {
        val CRITTER_CAFE = androidx.datastore.preferences.core.booleanPreferencesKey("critter_cafe_skin")
    }
}
