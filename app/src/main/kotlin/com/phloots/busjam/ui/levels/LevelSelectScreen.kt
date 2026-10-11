package com.phloots.busjam.ui.levels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.busjam.data.Progress
import com.phloots.busjam.ui.StarRow
import com.phloots.busjam.ui.theme.Palette

@Composable
fun LevelSelectScreen(
    count: Int,
    progress: Progress,
    onPlay: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val first = progress.firstUnbeaten(count)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (first - 1 - 10).coerceAtLeast(0))
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Home", color = Palette.ink, fontWeight = FontWeight.SemiBold) }
            Text("Levels", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.ink, modifier = Modifier.padding(start = 8.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = grid,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().testTag("level-grid"),
        ) {
            items(count, key = { it }) { i ->
                val n = i + 1
                LevelCell(n, progress.isUnlocked(n), progress.starsFor(n), n % 10 == 0) { onPlay(n) }
            }
        }
    }
}

@Composable
private fun LevelCell(n: Int, unlocked: Boolean, stars: Int, boss: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        !unlocked -> Color(0xFFCDD3D9)
        boss -> Color(0xFFFBE3DF)
        else -> Color.White
    }
    Box(
        Modifier
            .aspectRatio(0.85f)
            .background(bg, shape)
            .then(if (boss) Modifier.border(2.dp, Palette.boss, shape) else Modifier)
            .then(if (unlocked) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("level-$n")
            .semantics {
                contentDescription = "Level $n" + (if (boss) ", boss" else "") +
                    (if (unlocked) ", $stars stars" else ", locked")
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (boss) {
                Text("BOSS", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Palette.boss)
            }
            Text(
                "$n",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (unlocked) Palette.ink else Palette.ink.copy(alpha = 0.45f),
            )
            if (unlocked) {
                StarRow(stars, 12.dp, Modifier.padding(top = 2.dp))
            } else {
                Lock(Modifier.size(14.dp).padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun Lock(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = Palette.ink.copy(alpha = 0.45f)
        drawRoundRect(c, Offset(w * 0.15f, h * 0.45f), Size(w * 0.7f, h * 0.55f), CornerRadius(w * 0.1f))
        drawArc(
            c, 180f, 180f, false, Offset(w * 0.28f, h * 0.08f), Size(w * 0.44f, h * 0.7f),
            style = Stroke(width = w * 0.13f),
        )
    }
}
