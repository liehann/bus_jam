package com.phloots.busjam.ui

import com.phloots.jam.core.io.PackedLevel
import java.util.Locale

/** Testing aids shown in debug builds only (all levels unlocked, scores + metrics on tiles). */
data class LevelTileInfo(val score: String, val metrics: String)

private fun PackedLevel.colours() = level.colors().size

/** Two short lines for a level tile, e.g. "42.1" and "5c 38n 10x12". */
fun PackedLevel.tileInfo(): LevelTileInfo = LevelTileInfo(
    score = String.format(Locale.US, "%.1f", score),
    metrics = "${colours()}c ${level.containers.size}n ${level.width}x${level.height}",
)

/** Distinct chip colours per pack position (campaign first). */
fun packColor(index: Int): androidx.compose.ui.graphics.Color = listOf(
    androidx.compose.ui.graphics.Color(0xFF263238),
    androidx.compose.ui.graphics.Color(0xFF0072B2),
    androidx.compose.ui.graphics.Color(0xFF7B4FC8),
    androidx.compose.ui.graphics.Color(0xFF00785A),
    androidx.compose.ui.graphics.Color(0xFFB0301F),
    androidx.compose.ui.graphics.Color(0xFF8A5A1E),
)[index.mod(6)]
