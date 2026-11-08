package com.phloots.crittercafe.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which level mechanics the player has been introduced to, as the game screen sees it. */
interface SeenMechanics {
    val seen: Flow<Set<String>>

    /** Records [id] as seen; returns at once (the write runs in the owner's scope, so leaving the screen keeps it). */
    fun markSeen(id: String)
}

/** Mechanic intros already shown on this install, stored next to the progress (shared by every pack). */
class SeenMechanicsStore(private val dataStore: DataStore<Preferences>) {
    val seen: Flow<Set<String>> = dataStore.data.map { it[SEEN] ?: emptySet() }

    suspend fun markSeen(id: String) {
        dataStore.edit { it[SEEN] = (it[SEEN] ?: emptySet()) + id }
    }

    /** Forgets every intro (debug builds: `adb shell am broadcast -a com.phloots.crittercafe.RESET_INTROS`). */
    suspend fun reset() {
        dataStore.edit { it.remove(SEEN) }
    }

    private companion object {
        val SEEN = stringSetPreferencesKey("seen_mechanics")
    }
}
