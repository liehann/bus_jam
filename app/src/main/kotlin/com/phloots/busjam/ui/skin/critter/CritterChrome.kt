package com.phloots.busjam.ui.skin.critter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.busjam.ui.StarRow
import com.phloots.jam.core.model.Status
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Icons for the Critter Café buttons, drawn in ink into a square. */
enum class CritterIcon { PAUSE, PLAY, SETTINGS, UNDO, RESTART, HINT }

private val P = CritterPalette

/** Cream rounded button with a brown outline and a darker lip under it (the mock's chunky style). */
@Composable
fun CritterButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: CritterIcon? = null,
    fill: Color = P.button,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    description: String? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.height(height + 4.dp).alpha(if (enabled) 1f else 0.45f)) {
        Box(Modifier.fillMaxWidth().height(height).offset(y = 4.dp).background(P.buttonLip, shape))
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(fill, shape)
                .border(2.dp, P.ink, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Canvas(Modifier.size(height * 0.5f)) { drawCritterIcon(icon, size) }
                if (text != null) Spacer(Modifier.width(8.dp))
            }
            if (text != null) Text(text, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = P.ink)
        }
    }
}

/** Top bar: "Level N" pill on the left, pause and settings on the right. */
@Composable
fun CritterTopBar(levelNumber: Int, isBoss: Boolean, paused: Boolean, onPause: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box {
            Box(Modifier.matchParentSize().offset(y = 4.dp).background(P.buttonLip, shape))
            Row(
                Modifier.background(P.button, shape).border(2.dp, P.ink, shape).padding(horizontal = 18.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Level $levelNumber", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = P.ink)
                if (isBoss) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "BOSS", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White,
                        modifier = Modifier.background(P.color(6), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        CritterButton(
            null, onPause, Modifier.width(48.dp).testTag("pause"), icon = if (paused) CritterIcon.PLAY else CritterIcon.PAUSE,
            description = if (paused) "Resume" else "Pause",
        )
        Spacer(Modifier.width(10.dp))
        CritterButton(null, onSettings, Modifier.width(48.dp).testTag("settings"), icon = CritterIcon.SETTINGS, description = "Settings")
    }
}

/** Dimmed backdrop that swallows taps (or calls [onDismiss]) with a cream card in the middle. */
@Composable
private fun CritterDialog(tag: String, onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(P.scrim)
            .pointerInput(onDismiss) { detectTapGestures { onDismiss?.invoke() } },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .padding(36.dp)
                .widthIn(max = 320.dp)
                .background(P.card, shape)
                .border(2.5.dp, P.ink, shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = 22.dp, vertical = 20.dp)
                .testTag(tag),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = P.ink)

/** Pause menu: resume, restart, undo, hint. Tapping outside resumes. */
@Composable
fun CritterPauseMenu(
    canUndo: Boolean,
    canHint: Boolean,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
) = CritterDialog("pause-menu", onResume) {
    Title("Paused")
    CritterButton("Resume", onResume, Modifier.fillMaxWidth().testTag("resume"), icon = CritterIcon.PLAY, fill = P.color(1))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CritterButton("Undo", onUndo, Modifier.weight(1f).testTag("undo"), icon = CritterIcon.UNDO, enabled = canUndo)
        CritterButton("Restart", onRestart, Modifier.weight(1f).testTag("restart"), icon = CritterIcon.RESTART)
    }
    CritterButton("Hint", onHint, Modifier.fillMaxWidth().testTag("hint"), icon = CritterIcon.HINT, fill = P.color(3), enabled = canHint)
}

/** Settings card. No options yet: the way back to the level list and home lives here. */
@Composable
fun CritterSettings(onClose: () -> Unit, onLevels: () -> Unit, onHome: () -> Unit) = CritterDialog("settings-menu", onClose) {
    Title("Settings")
    CritterButton("Level select", onLevels, Modifier.fillMaxWidth().testTag("to-levels"))
    CritterButton("Home", onHome, Modifier.fillMaxWidth().testTag("to-home"))
    CritterButton("Close", onClose, Modifier.fillMaxWidth().testTag("settings-close"), fill = P.color(1))
}

/** Win / lose card. */
@Composable
fun CritterResult(
    status: Status,
    earnedStars: Int,
    hasNext: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onNext: () -> Unit,
    onLevels: () -> Unit,
) {
    val won = status == Status.WON
    CritterDialog(if (won) "win-dialog" else "lose-dialog", null) {
        if (won) {
            Title(if (earnedStars == 3) "Purrfect!" else "Delicious!")
            StarRow(earnedStars, 44.dp, empty = P.ink.copy(alpha = 0.15f))
            Text(
                when (earnedStars) {
                    3 -> "Every party served, no undo, no hint"
                    2 -> "Served, with an undo"
                    else -> "Served, with a hint"
                },
                color = P.ink.copy(alpha = 0.75f),
            )
            if (hasNext) CritterButton("Next", onNext, Modifier.fillMaxWidth().testTag("next"), fill = P.color(1))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CritterButton("Replay", onRestart, Modifier.weight(1f), icon = CritterIcon.RESTART)
                CritterButton("Levels", onLevels, Modifier.weight(1f))
            }
        } else {
            Title(if (status == Status.DEADLOCK) "Kitchen jammed!" else "All stuck!")
            Text(
                if (status == Status.DEADLOCK) "No plate on the belt suits a party at the tables."
                else "No reservation can leave and the belt is stuck.",
                color = P.ink.copy(alpha = 0.75f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CritterButton("Undo", onUndo, Modifier.weight(1f).testTag("lose-undo"), icon = CritterIcon.UNDO, enabled = canUndo)
                CritterButton("Restart", onRestart, Modifier.weight(1f).testTag("lose-restart"), icon = CritterIcon.RESTART, fill = P.color(0))
            }
        }
    }
}

/** Floating message over the field (hint feedback). */
@Composable
fun CritterToast(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = P.ink,
        modifier = modifier.background(P.button, shape).border(2.dp, P.ink, shape).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Draws [icon] into a square of [size]. */
fun DrawScope.drawCritterIcon(icon: CritterIcon, size: Size) {
    val s = size.minDimension
    val c = Offset(size.width / 2, size.height / 2)
    val ink = P.ink
    val line = Stroke(width = s * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (icon) {
        CritterIcon.PAUSE -> for (sx in listOf(-1f, 1f)) {
            drawRoundRect(
                ink, Offset(c.x + sx * s * 0.17f - s * 0.1f, c.y - s * 0.34f), Size(s * 0.2f, s * 0.68f),
                androidx.compose.ui.geometry.CornerRadius(s * 0.06f),
            )
        }
        CritterIcon.PLAY -> {
            val tri = Path().apply {
                moveTo(c.x - s * 0.24f, c.y - s * 0.36f)
                lineTo(c.x + s * 0.36f, c.y)
                lineTo(c.x - s * 0.24f, c.y + s * 0.36f)
                close()
            }
            drawPath(tri, ink)
            drawPath(tri, ink, style = Stroke(width = s * 0.1f, join = StrokeJoin.Round))
        }
        CritterIcon.SETTINGS -> {
            val teeth = 8
            for (i in 0 until teeth) {
                rotate(360f / teeth * i, c) {
                    drawRoundRect(
                        ink, Offset(c.x - s * 0.08f, c.y - s * 0.46f), Size(s * 0.16f, s * 0.2f),
                        androidx.compose.ui.geometry.CornerRadius(s * 0.04f),
                    )
                }
            }
            drawCircle(ink, s * 0.32f, c)
            drawCircle(P.button, s * 0.13f, c)
        }
        CritterIcon.UNDO -> {
            drawArc(ink, 200f, 250f, false, c - Offset(s * 0.3f, s * 0.26f), Size(s * 0.6f, s * 0.6f), style = line)
            val tip = Offset(c.x - s * 0.28f, c.y - s * 0.08f)
            drawPath(
                Path().apply {
                    moveTo(tip.x - s * 0.2f, tip.y - s * 0.08f)
                    lineTo(tip.x + s * 0.06f, tip.y - s * 0.24f)
                    lineTo(tip.x + s * 0.08f, tip.y + s * 0.08f)
                    close()
                },
                ink,
            )
        }
        CritterIcon.RESTART -> {
            drawArc(ink, -60f, 290f, false, c - Offset(s * 0.32f, s * 0.32f), Size(s * 0.64f, s * 0.64f), style = line)
            val a = (-60.0 * PI / 180).toFloat()
            val tip = Offset(c.x + cos(a) * s * 0.32f, c.y + sin(a) * s * 0.32f)
            drawPath(
                Path().apply {
                    moveTo(tip.x - s * 0.04f, tip.y - s * 0.24f)
                    lineTo(tip.x + s * 0.2f, tip.y + s * 0.04f)
                    lineTo(tip.x - s * 0.14f, tip.y + s * 0.1f)
                    close()
                },
                ink,
            )
        }
        CritterIcon.HINT -> {
            drawCircle(P.color(3), s * 0.28f, c - Offset(0f, s * 0.1f))
            drawCircle(ink, s * 0.28f, c - Offset(0f, s * 0.1f), style = Stroke(width = s * 0.09f))
            drawRoundRect(
                ink, Offset(c.x - s * 0.13f, c.y + s * 0.2f), Size(s * 0.26f, s * 0.2f),
                androidx.compose.ui.geometry.CornerRadius(s * 0.05f),
            )
        }
    }
}
