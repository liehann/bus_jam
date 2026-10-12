package com.phloots.busjam.ui.skin.critter

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.busjam.data.Progress

private val P = CritterPalette

/**
 * The Critter Café level list: a back button, the title and the star total on top; then the levels five to a row
 * (so each cycle of ten is two rows, its last level the boss), each a chunky tile in a plate colour with its stars.
 * Locked levels are greyed with a lock; the next level to play gently pulses.
 */
@Composable
fun CritterLevelSelect(count: Int, progress: Progress, onPlay: (Int) -> Unit, onBack: () -> Unit) {
    val next = progress.firstUnbeaten(count)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = (next - 1 - 10).coerceAtLeast(0))
    val pulse = rememberInfiniteTransition(label = "next")
    val beat by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "beat")

    Column(Modifier.fillMaxSize().background(P.background).padding(horizontal = 14.dp).testTag("critter-levels")) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CritterButton("Home", onBack, Modifier.width(104.dp).testTag("levels-back"), description = "Back to home")
            Spacer(Modifier.weight(1f))
            Text("Levels", fontSize = 26.sp, fontWeight = FontWeight.Black, color = P.ink)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(104.dp), contentAlignment = Alignment.CenterEnd) {
                Text("★ ${progress.stars.values.sum()}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = P.ink)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = grid,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("level-grid"),
        ) {
            for (cycle in 0 until (count + 9) / 10) {
                item(key = "h$cycle", span = { GridItemSpan(5) }) {
                    Text(
                        "Levels ${cycle * 10 + 1}-${minOf(count, cycle * 10 + 10)}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                        color = P.ink.copy(alpha = 0.55f), modifier = Modifier.padding(top = if (cycle == 0) 0.dp else 6.dp, start = 2.dp),
                    )
                }
                for (n in cycle * 10 + 1..minOf(count, cycle * 10 + 10)) {
                    item(key = n) {
                        LevelTile(n, progress.isUnlocked(n), progress.starsFor(n), n % 10 == 0, n == next, beat) { onPlay(n) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelTile(n: Int, unlocked: Boolean, stars: Int, boss: Boolean, current: Boolean, beat: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val rim = if (boss) P.color(6) else P.color((n - 1) % 6)
    val fill = when {
        !unlocked -> Color(0xFFE6DCCB)
        boss -> lerp(rim, Color.White, 0.72f)
        else -> lerp(rim, Color.White, 0.6f)
    }
    val border = if (unlocked) P.ink else P.ink.copy(alpha = 0.35f)
    Box(
        Modifier
            .aspectRatio(0.82f)
            .scale(if (current) 1f + 0.05f * beat else 1f)
            .testTag("level-$n")
            .semantics {
                contentDescription = "Level $n" + (if (boss) ", boss" else "") + (if (unlocked) ", $stars stars" else ", locked")
            },
    ) {
        // the lip under the tile, then the tile
        Box(Modifier.fillMaxSize().padding(top = 4.dp).offset(y = 0.dp).background(if (unlocked) lerp(rim, P.ink, 0.25f) else P.buttonLip.copy(alpha = 0.6f), shape))
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.86f)
                .background(fill, shape)
                .border(if (current) 3.dp else 2.dp, if (current) P.amber else border, shape)
                .then(if (unlocked) Modifier.clickable(onClick = onClick) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (boss) {
                Text(
                    "BOSS", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier.background(if (unlocked) rim else rim.copy(alpha = 0.45f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
                )
            }
            Text("$n", fontSize = 21.sp, fontWeight = FontWeight.Black, color = if (unlocked) P.ink else P.ink.copy(alpha = 0.4f))
            if (unlocked) Stars(stars) else Lock(Modifier.size(13.dp))
        }
    }
}

@Composable
private fun Stars(stars: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        for (i in 1..3) {
            Text("★", fontSize = 12.sp, color = if (i <= stars) P.halo else P.ink.copy(alpha = 0.18f))
        }
    }
}

@Composable
private fun Lock(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val c = P.ink.copy(alpha = 0.4f)
        drawRoundRect(c, Offset(w * 0.15f, h * 0.45f), Size(w * 0.7f, h * 0.55f), CornerRadius(w * 0.12f))
        drawArc(c, 180f, 180f, false, Offset(w * 0.28f, h * 0.08f), Size(w * 0.44f, h * 0.7f), style = Stroke(width = w * 0.13f))
    }
}
