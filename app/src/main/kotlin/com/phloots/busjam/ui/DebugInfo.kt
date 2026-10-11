package com.phloots.busjam.ui

import com.phloots.jam.core.io.PackedLevel
import java.util.Locale

/** Testing aids shown in debug builds only (all levels unlocked, scores + metrics on tiles and the game header). */
data class LevelTileInfo(val score: String, val metrics: String)

private fun PackedLevel.colours() = level.colors().size

/** Two short lines for a level tile, e.g. "42.1" and "5c 38n 10x12". */
fun PackedLevel.tileInfo(): LevelTileInfo = LevelTileInfo(
    score = String.format(Locale.US, "%.1f", score),
    metrics = "${colours()}c ${level.containers.size}n ${level.width}x${level.height}",
)

/** One line for the game header. */
fun PackedLevel.headerInfo(): String = String.format(
    Locale.US,
    "score %.1f · %d colours · %d containers · %dx%d · %d elements",
    score, colours(), level.containers.size, level.width, level.height, level.queue.size,
)
