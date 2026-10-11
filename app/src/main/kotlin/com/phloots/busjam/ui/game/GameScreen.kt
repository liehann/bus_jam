package com.phloots.busjam.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phloots.jam.core.model.Status
import com.phloots.busjam.game.GameUiState
import com.phloots.busjam.game.PhaseKind
import com.phloots.busjam.game.Snapshot
import com.phloots.busjam.ui.DebugHeader
import com.phloots.busjam.ui.GameButton
import com.phloots.busjam.ui.PackChip
import com.phloots.busjam.ui.StarRow
import com.phloots.busjam.ui.theme.Palette

@Composable
fun GameScreen(
    ui: GameUiState,
    onTap: (Int) -> Unit,
    onAnimationFinished: (Long) -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onHint: () -> Unit,
    onNext: () -> Unit,
    onLevels: () -> Unit,
    onHome: () -> Unit,
    /** Debug builds: pack chip, level metrics and motif tags under the title. */
    debugHeader: DebugHeader? = null,
    /** Staging spot count; defaults to the level's [com.phloots.jam.core.model.Level.stagingSpots]. */
    stagingSpots: Int = ui.state.level.stagingSpots,
) {
    val haptic = LocalHapticFeedback.current
    val anim = ui.anim

    // ---- playback of the current transition, phase by phase ----
    val progress = remember { Animatable(0f) }
    var playingSeq by remember { mutableLongStateOf(-1L) }
    var phaseIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(anim?.seq) {
        if (anim == null) return@LaunchedEffect
        anim.phases.forEachIndexed { i, ph ->
            progress.snapTo(0f)
            phaseIndex = i
            playingSeq = anim.seq
            when (ph.kind) {
                PhaseKind.BLOCK -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
                PhaseKind.FLOW -> if (ph.boardings.isNotEmpty()) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
                else -> Unit
            }
            progress.animateTo(1f, tween(ph.durationMs, easing = LinearEasing))
        }
        onAnimationFinished(anim.seq)
    }
    val phase = anim?.phases?.getOrNull(if (playingSeq == anim.seq) phaseIndex else 0)
    val playing = anim != null && playingSeq == anim.seq
    val idle = remember(ui.state) { Snapshot.of(ui.state) }
    val snapshot = phase?.before ?: idle
    val tProvider = { if (playing) progress.value else 0f }

    // ---- slots-full shake ----
    val shakeAnim = remember { Animatable(1f) }
    LaunchedEffect(ui.dockFullSignal) {
        if (ui.dockFullSignal > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            shakeAnim.snapTo(0f)
            shakeAnim.animateTo(1f, tween(280, easing = LinearEasing))
        }
    }

    // ---- hint pulse ----
    val pulse = if (ui.hintContainerId != null) {
        val inf = rememberInfiniteTransition(label = "hint")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    } else null

    LaunchedEffect(ui.showResult, ui.state.status) {
        if (ui.showResult && ui.state.status == Status.WON) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    Box(Modifier.fillMaxSize().background(Palette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            TopBar(ui.levelNumber, ui.isBoss, ui.potentialStars, debugHeader, onLevels)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PlayField(
                    logical = ui.state,
                    snapshot = snapshot,
                    phase = phase.takeIf { playing },
                    progress = tProvider,
                    hintContainerId = ui.hintContainerId,
                    hintPulse = { pulse?.value ?: 0f },
                    shakeProgress = { shakeAnim.value },
                    onTap = onTap,
                    stagingSpots = stagingSpots,
                    modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                )
                // floats over the bottom of the play field instead of reserving a row
                ui.hintMessage?.let {
                    Text(
                        it,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .background(Palette.boss, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                            .testTag("hint-message"),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameButton("Restart", onRestart, Modifier.weight(1f).testTag("restart"))
                GameButton("Undo", onUndo, Modifier.weight(1f).testTag("undo"), enabled = ui.canUndo)
                GameButton(
                    if (ui.hintLoading) "..." else "Hint",
                    onHint,
                    Modifier.weight(1f).testTag("hint"),
                    enabled = !ui.hintLoading && ui.state.status == Status.PLAYING,
                    container = Palette.gold,
                    content = Palette.ink,
                )
            }
        }
        if (ui.showResult) {
            ResultOverlay(ui.state.status, ui.earnedStars, ui.hasNext, ui.canUndo, onUndo, onRestart, onNext, onLevels, onHome)
        }
    }
}

@Composable
internal fun TopBar(
    levelNumber: Int,
    isBoss: Boolean,
    potentialStars: Int,
    header: DebugHeader?,
    onLevels: () -> Unit,
    /** Extra control before the stars (real-time pause button). */
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onLevels, modifier = Modifier.testTag("to-levels")) {
            Text("< Levels", color = Palette.ink, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Level ${levelNumber}",
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Palette.ink,
        )
        if (isBoss) {
            Spacer(Modifier.width(6.dp))
            Text(
                "BOSS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                modifier = Modifier
                    .background(Palette.boss, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
        StarRow(potentialStars, 18.dp, Modifier.padding(end = 12.dp))
    }
    if (header != null) {
        Column(
            Modifier.fillMaxWidth().testTag("debug-header"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PackChip("${header.packLabel} · L${levelNumber}", header.packColor, Modifier.testTag("pack-chip"))
            header.info?.let {
                Text(it, fontSize = 11.sp, color = Palette.ink.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp).testTag("debug-info"))
            }
            header.tags?.let {
                Text(it, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = header.packColor,
                    textAlign = TextAlign.Center, maxLines = 2,
                    modifier = Modifier.testTag("debug-tags"))
            }
        }
    }
}

@Composable
internal fun ResultOverlay(
    status: Status,
    earnedStars: Int,
    hasNext: Boolean,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onNext: () -> Unit,
    onLevels: () -> Unit,
    onHome: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures { } }, // swallow taps; no semantics merge
        contentAlignment = Alignment.Center,
    ) {
        val won = status == Status.WON
        Column(
            Modifier
                .padding(32.dp)
                .background(Color.White, RoundedCornerShape(24.dp))
                .padding(24.dp)
                .testTag(if (won) "win-dialog" else "lose-dialog"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (won) {
                Text("Level complete!", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.ink)
                StarRow(earnedStars, 44.dp)
                Text(
                    when (earnedStars) {
                        3 -> "Perfect - no undo, no hint"
                        2 -> "Used undo"
                        else -> "Used a hint"
                    },
                    color = Palette.ink.copy(alpha = 0.7f),
                )
                if (hasNext) {
                    GameButton("Next", onNext, Modifier.fillMaxWidth().testTag("next"), fontSize = 20)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GameButton("Replay", onRestart, container = Palette.slotFill, content = Palette.ink)
                    GameButton("Levels", onLevels, container = Palette.slotFill, content = Palette.ink)
                }
            } else {
                Text(
                    if (status == Status.DEADLOCK) "Dock full!" else "No moves left",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Palette.ink,
                )
                Text(
                    if (status == Status.DEADLOCK) "Nobody in line can board a docked container."
                    else "No container can leave and the queue is stuck.",
                    color = Palette.ink.copy(alpha = 0.7f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GameButton("Undo", onUndo, Modifier.testTag("lose-undo"), enabled = canUndo)
                    GameButton("Restart", onRestart, Modifier.testTag("lose-restart"), container = Palette.boss)
                }
                TextButton(onClick = onHome) { Text("Home", color = Palette.ink) }
            }
        }
    }
}
