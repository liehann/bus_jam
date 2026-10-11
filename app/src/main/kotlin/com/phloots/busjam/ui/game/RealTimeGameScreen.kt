package com.phloots.busjam.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.phloots.busjam.game.rt.RtUiState
import com.phloots.busjam.ui.GameButton
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.skin.LocalSkin
import com.phloots.busjam.ui.skin.critter.CritterCafeSkin
import com.phloots.busjam.ui.skin.critter.CritterPalette
import com.phloots.busjam.ui.skin.critter.CritterPauseMenu
import com.phloots.busjam.ui.skin.critter.CritterResult
import com.phloots.busjam.ui.skin.critter.CritterSettings
import com.phloots.busjam.ui.skin.critter.CritterToast
import com.phloots.busjam.ui.skin.critter.CritterTopBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.phloots.jam.core.model.Status
import kotlinx.coroutines.isActive

/**
 * Real-time game screen: the carousel field driven by a frame loop ([onFrame] gets each frame's time in ms). The
 * game pauses with the pause button, while the app is not resumed ([onHostPause]) and once the result shows.
 */
@Composable
fun RealTimeGameScreen(
    ui: RtUiState,
    onFrame: (Long) -> Unit,
    onHostPause: (Boolean) -> Unit,
    onTogglePause: () -> Unit,
    onTap: (Int) -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onHint: () -> Unit,
    onNext: () -> Unit,
    onLevels: () -> Unit,
    onHome: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val frame by rememberUpdatedState(onFrame)
    LaunchedEffect(Unit) {
        while (isActive) withFrameMillis { frame(it) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { onHostPause(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onHostPause(false) }

    val shakeAnim = remember { Animatable(1f) }
    LaunchedEffect(ui.dockFullSignal) {
        if (ui.dockFullSignal > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.Reject)
            shakeAnim.snapTo(0f)
            shakeAnim.animateTo(1f, tween(280, easing = LinearEasing))
        }
    }
    LaunchedEffect(ui.bumpSignal) { if (ui.bumpSignal > 0) haptic.performHapticFeedback(HapticFeedbackType.Reject) }
    LaunchedEffect(ui.boardSignal) { if (ui.boardSignal > 0) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    LaunchedEffect(ui.showResult, ui.status) {
        if (ui.showResult && ui.status == Status.WON) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    val pulse = if (ui.hintContainerId != null) {
        rememberInfiniteTransition(label = "hint")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse")
    } else null

    val skin = LocalSkin.current
    if (skin === CritterCafeSkin) {
        CritterGameScreen(ui, { pulse?.value ?: 0f }, { shakeAnim.value }, onTogglePause, onTap, onUndo, onRestart, onHint, onNext, onLevels, onHome)
        return
    }
    Box(Modifier.fillMaxSize().background(skin.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            TopBar(ui.levelNumber, ui.isBoss, ui.potentialStars, onLevels) {
                PauseButton(ui.paused, onTogglePause)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RingField(
                    ui = ui,
                    hintPulse = { pulse?.value ?: 0f },
                    shakeProgress = { shakeAnim.value },
                    onTap = onTap,
                    modifier = Modifier.fillMaxSize().padding(vertical = 4.dp),
                )
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
                if (ui.paused && !ui.showResult) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0x88EEF1F4)).clickable(onClick = onTogglePause)
                            .testTag("paused-overlay"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Paused — tap to resume",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Palette.ink,
                            modifier = Modifier.background(Color.White, RoundedCornerShape(16.dp))
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
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
                    enabled = !ui.hintLoading && ui.status == Status.PLAYING,
                    container = Palette.gold,
                    content = Palette.ink,
                )
            }
        }
        if (ui.showResult) {
            ResultOverlay(ui.status, ui.earnedStars, ui.hasNext, ui.canUndo, onUndo, onRestart, onNext, onLevels, onHome)
        }
    }
}

@Composable
private fun PauseButton(paused: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 10.dp)
            .size(34.dp)
            .background(Palette.slotFill, CircleShape)
            .clickable(onClick = onClick)
            .testTag("pause")
            .semantics { contentDescription = if (paused) "Resume" else "Pause" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (paused) "▶" else "II", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Palette.ink)
    }
}

/**
 * Critter Café chrome around the same field: top bar with the level pill, pause and settings; restart, undo and hint
 * live in the pause menu; level select and home in the settings card (opening it pauses the game).
 */
@Composable
private fun CritterGameScreen(
    ui: RtUiState,
    hintPulse: () -> Float,
    shakeProgress: () -> Float,
    onTogglePause: () -> Unit,
    onTap: (Int) -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onHint: () -> Unit,
    onNext: () -> Unit,
    onLevels: () -> Unit,
    onHome: () -> Unit,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsPaused by remember { mutableStateOf(false) }
    fun closeSettings() {
        settingsOpen = false
        if (settingsPaused && ui.paused) onTogglePause()
        settingsPaused = false
    }
    Box(Modifier.fillMaxSize().background(CritterPalette.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            CritterTopBar(
                ui.levelNumber, ui.isBoss, ui.paused,
                onPause = onTogglePause,
                onSettings = {
                    settingsPaused = !ui.paused
                    if (!ui.paused) onTogglePause()
                    settingsOpen = true
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                RingField(
                    ui = ui,
                    hintPulse = hintPulse,
                    shakeProgress = shakeProgress,
                    onTap = onTap,
                    modifier = Modifier.fillMaxSize().padding(top = 4.dp, bottom = 8.dp),
                )
                ui.hintMessage?.let {
                    CritterToast(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).testTag("hint-message"))
                }
            }
        }
        when {
            ui.showResult -> CritterResult(ui.status, ui.earnedStars, ui.hasNext, ui.canUndo, onUndo, onRestart, onNext, onLevels)
            settingsOpen -> CritterSettings(::closeSettings, { settingsOpen = false; onLevels() }, { settingsOpen = false; onHome() })
            ui.paused -> CritterPauseMenu(
                canUndo = ui.canUndo,
                canHint = !ui.hintLoading && ui.status == Status.PLAYING,
                onResume = onTogglePause,
                onRestart = onRestart, // a restart starts unpaused
                onUndo = { onUndo(); onTogglePause() },
                onHint = { onHint(); onTogglePause() },
            )
        }
    }
}
