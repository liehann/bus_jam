package com.phloots.busjam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

private val Context.progressDataStore: DataStore<Preferences> by preferencesDataStore(name = "progress")

/** DataStore Preferences backed progress: highest unlocked level and best stars per level. */
class ProgressStore(private val dataStore: DataStore<Preferences>) : ProgressSink {
    constructor(context: Context) : this(context.applicationContext.progressDataStore)

    val progress: Flow<Progress> = dataStore.data.map { prefs ->
        val stars = HashMap<Int, Int>()
        for ((key, value) in prefs.asMap()) {
            val name = key.name
            if (name.startsWith(STARS_PREFIX) && value is Int) {
                name.removePrefix(STARS_PREFIX).toIntOrNull()?.let { stars[it] = value }
            }
        }
        Progress(highestUnlocked = prefs[HIGHEST_UNLOCKED] ?: 1, stars = stars)
    }

    /** Keeps the best star count and unlocks the next level. */
    override suspend fun recordWin(levelNumber: Int, stars: Int) {
        dataStore.edit { prefs ->
            val key = starsKey(levelNumber)
            val best = maxOf(prefs[key] ?: 0, stars.coerceIn(1, 3))
            prefs[key] = best
            prefs[HIGHEST_UNLOCKED] = maxOf(prefs[HIGHEST_UNLOCKED] ?: 1, levelNumber + 1)
        }
    }

    private companion object {
        const val STARS_PREFIX = "stars_"
        val HIGHEST_UNLOCKED = intPreferencesKey("highest_unlocked")
        fun starsKey(levelNumber: Int) = intPreferencesKey(STARS_PREFIX + levelNumber)
    }
}
