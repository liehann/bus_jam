package com.phloots.busjam.data

import android.content.Context
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Loads the campaign from `assets/levels.json`. Campaign order == list order; levels[0] is level 1. */
class LevelRepository(
    private val context: Context,
    private val assetName: String = "levels.json",
) {
    private val mutex = Mutex()
    @Volatile private var cache: List<PackedLevel>? = null

    /** The shipped levels with their difficulty score / metrics. */
    suspend fun levels(): List<PackedLevel> {
        cache?.let { return it }
        return mutex.withLock {
            cache ?: withContext(Dispatchers.IO) {
                val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
                LevelPackCodec.decode(json).levels
            }.also { cache = it }
        }
    }
}
