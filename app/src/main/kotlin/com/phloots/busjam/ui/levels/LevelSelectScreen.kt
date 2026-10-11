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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
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
import com.phloots.busjam.ui.GameButton
import com.phloots.busjam.ui.LevelTileInfo
import com.phloots.busjam.ui.StarRow
import com.phloots.busjam.ui.theme.Palette

@Composable
fun LevelSelectScreen(
    count: Int,
    progress: Progress,
    onPlay: (Int) -> Unit,
    onBack: () -> Unit,
    /** Debug builds: every level is playable. */
    unlockAll: Boolean = false,
    /** Debug builds: difficulty score + metrics per level number (null hides them). */
    tileInfo: ((Int) -> LevelTileInfo?)? = null,
    /** Debug builds: "Go to level" number field. */
    showGoTo: Boolean = false,
) {
    val first = progress.firstUnbeaten(count)
    val columns = if (tileInfo != null) 4 else 5
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (first - 1 - 2 * columns).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    fun unlocked(n: Int) = unlockAll || progress.isUnlocked(n)
    Column(Modifier.fillMaxSize().background(Palette.background)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Home", color = Palette.ink, fontWeight = FontWeight.SemiBold) }
            Text("Levels", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.ink, modifier = Modifier.padding(start = 8.dp))
        }
        if (showGoTo) {
            var text by rememberSaveable { mutableStateOf("") }
            val go = {
                text.trim().toIntOrNull()?.let { raw ->
                    val n = raw.coerceIn(1, count)
                    if (unlocked(n)) onPlay(n) else scope.launch { grid.scrollToItem(n - 1) }
                }
                Unit
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Go to level") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.weight(1f).testTag("goto-field"),
                )
                GameButton("Go", go, Modifier.testTag("goto-button"))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().testTag("level-grid"),
        ) {
            items(count, key = { it }) { i ->
                val n = i + 1
                LevelCell(n, unlocked(n), progress.starsFor(n), n % 10 == 0, tileInfo?.invoke(n)) { onPlay(n) }
            }
        }
    }
}

@Composable
private fun LevelCell(n: Int, unlocked: Boolean, stars: Int, boss: Boolean, info: LevelTileInfo?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        !unlocked -> Color(0xFFCDD3D9)
        boss -> Color(0xFFFBE3DF)
        else -> Color.White
    }
    Box(
        Modifier
            .aspectRatio(if (info != null) 0.78f else 0.85f)
            .background(bg, shape)
            .then(if (boss) Modifier.border(2.dp, Palette.boss, shape) else Modifier)
            .then(if (unlocked) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("level-$n")
            .semantics {
                contentDescription = "Level $n" + (if (boss) ", boss" else "") +
                    (if (unlocked) ", $stars stars" else ", locked") +
                    (info?.let { ", score ${it.score}" } ?: "")
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
            if (info != null) {
                Text(info.score, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.ink, modifier = Modifier.padding(top = 2.dp))
                Text(info.metrics, fontSize = 9.sp, color = Palette.ink.copy(alpha = 0.7f), maxLines = 1)
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
