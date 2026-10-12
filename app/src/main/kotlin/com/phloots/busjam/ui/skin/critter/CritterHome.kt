package com.phloots.busjam.ui.skin.critter

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.phloots.busjam.R
import com.phloots.busjam.data.PackInfo
import com.phloots.busjam.ui.home.HomeDebugOptions

private val P = CritterPalette

/**
 * The Critter Café home screen: star total and (debug) settings on top, the logo, the café hero illustration
 * (gently breathing), a big Play pill with the next level and a Levels pill. Debug builds keep the pack list and
 * the mode and skin switches in a settings dialog.
 */
@Composable
fun CritterHomeScreen(
    nextLevel: Int?,
    totalLevels: Int,
    totalStars: Int,
    onPlay: () -> Unit,
    onLevels: () -> Unit,
    packs: List<PackInfo> = emptyList(),
    selectedPack: String? = null,
    onSelectPack: (String) -> Unit = {},
    realTime: Boolean? = null,
    onRealTime: (Boolean) -> Unit = {},
    critterCafe: Boolean? = null,
    onCritterCafe: (Boolean) -> Unit = {},
) {
    var debugOpen by remember { mutableStateOf(false) }
    val hasDebug = packs.size > 1 || realTime != null || critterCafe != null
    val breath = rememberInfiniteTransition(label = "hero")
    val bob by breath.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")

    Column(
        Modifier.fillMaxSize().background(P.background).padding(horizontal = 20.dp, vertical = 12.dp).testTag("critter-home"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StarPill(if (totalLevels > 0) "$totalStars / ${totalLevels * 3}" else "...")
            Spacer(Modifier.weight(1f))
            if (hasDebug) {
                CritterButton(
                    null, { debugOpen = true }, Modifier.width(48.dp).testTag("home-settings"), icon = CritterIcon.SETTINGS,
                    description = "Settings",
                )
            }
        }
        Image(
            painterResource(R.drawable.critter_logo), contentDescription = "Critter Café",
            modifier = Modifier.fillMaxWidth(0.86f).height(118.dp).padding(top = 4.dp),
            contentScale = ContentScale.Fit,
        )
        Image(
            painterResource(R.drawable.critter_home_hero), contentDescription = null,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .graphicsLayer {
                    val s = 1f + 0.012f * bob
                    scaleX = s
                    scaleY = s
                    translationY = -3.dp.toPx() * bob
                },
            contentScale = ContentScale.Fit,
        )
        PlayPill(nextLevel, onPlay)
        Spacer(Modifier.height(14.dp))
        CritterButton(
            "Levels", onLevels, Modifier.fillMaxWidth(0.72f).testTag("levels"), fill = P.color(0),
            enabled = totalLevels > 0, height = 52.dp,
        )
        Spacer(Modifier.height(12.dp))
    }
    if (debugOpen) {
        Dialog(onDismissRequest = { debugOpen = false }) {
            val shape = RoundedCornerShape(22.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(P.card, shape)
                    .border(2.5.dp, P.ink, shape)
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("home-debug"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = P.ink)
                HomeDebugOptions(packs, selectedPack, onSelectPack, realTime, onRealTime, critterCafe, onCritterCafe)
                CritterButton("Close", { debugOpen = false }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The big mint Play pill: "Play" with the next level number under it. */
@Composable
private fun PlayPill(nextLevel: Int?, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(40.dp)
    val enabled = nextLevel != null
    Box(Modifier.fillMaxWidth(0.86f).height(84.dp).alpha(if (enabled) 1f else 0.45f)) {
        Box(Modifier.fillMaxWidth().height(78.dp).offset(y = 6.dp).background(lipOf(P.color(1)), shape))
        Column(
            Modifier
                .fillMaxWidth()
                .height(78.dp)
                .background(P.color(1), shape)
                .border(2.5.dp, P.ink, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onPlay)
                .semantics { contentDescription = if (nextLevel != null) "Play level $nextLevel" else "Play" }
                .testTag("play"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Play", fontSize = 30.sp, fontWeight = FontWeight.Black, color = P.ink)
            if (nextLevel != null) Text("Level $nextLevel", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = P.ink.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun StarPill(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Box {
        Box(Modifier.matchParentSize().offset(y = 4.dp).background(P.buttonLip, shape))
        Row(
            Modifier.background(P.button, shape).border(2.dp, P.ink, shape).padding(horizontal = 14.dp, vertical = 7.dp)
                .testTag("home-stars"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("★", fontSize = 20.sp, color = P.halo)
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = P.ink)
        }
    }
}

private fun lipOf(c: Color) = androidx.compose.ui.graphics.lerp(c, P.ink, 0.3f)
