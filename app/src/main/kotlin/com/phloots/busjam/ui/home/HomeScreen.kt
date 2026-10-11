package com.phloots.busjam.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.jam.core.model.Dir
import com.phloots.busjam.ui.GameButton
import com.phloots.busjam.ui.game.drawContainer
import com.phloots.busjam.ui.theme.Palette

@Composable
fun HomeScreen(
    nextLevel: Int?,
    totalLevels: Int,
    totalStars: Int,
    onPlay: () -> Unit,
    onLevels: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Palette.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val cell = size.height / 4.2f
            val cx = size.width / 2
            drawContainer(Offset(cx - cell * 2.4f, size.height / 2), cell * 0.82f, cell * 1.84f, Dir.UP, 0, iconSize = cell * 0.46f)
            drawContainer(Offset(cx, size.height / 2), cell * 0.82f, cell * 3.84f, Dir.UP, 1, iconSize = cell * 0.46f)
            drawContainer(Offset(cx + cell * 2.4f, size.height / 2), cell * 0.82f, cell * 2.84f, Dir.DOWN, 2, iconSize = cell * 0.46f)
        }
        Spacer(Modifier.height(16.dp))
        Text("Bus Jam", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Palette.ink)
        Spacer(Modifier.height(8.dp))
        Text(
            if (totalLevels > 0) "$totalStars / ${totalLevels * 3} stars" else "Loading levels...",
            color = Palette.ink.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(40.dp))
        GameButton(
            text = if (nextLevel != null) "Play  -  Level $nextLevel" else "Play",
            onClick = onPlay,
            enabled = nextLevel != null,
            modifier = Modifier.fillMaxWidth().height(72.dp).testTag("play"),
            fontSize = 24,
        )
        Spacer(Modifier.height(16.dp))
        GameButton(
            text = "Levels",
            onClick = onLevels,
            enabled = totalLevels > 0,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("levels"),
            container = Palette.slotFill,
            content = Palette.ink,
            fontSize = 18,
        )
        Spacer(Modifier.size(48.dp))
    }
}
