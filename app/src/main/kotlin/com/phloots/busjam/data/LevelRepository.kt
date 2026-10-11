package com.phloots.busjam.data

import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.io.PackedLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Loads level packs from the assets (cached per file). Pack order == list order; levels[0] is level 1. */
class LevelRepository(private val assets: AssetSource) {
    private val mutex = Mutex()
    private val cache = HashMap<String, List<PackedLevel>>()

    /** The levels of pack [file] with their difficulty score / metrics / motif tags. */
    suspend fun levels(file: String = PackInfo.CAMPAIGN_FILE): List<PackedLevel> = mutex.withLock {
        cache[file] ?: withContext(Dispatchers.IO) { LevelPackCodec.decode(assets.read(file)).levels }
            .also { cache[file] = it }
    }
}
