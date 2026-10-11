package com.phloots.busjam

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.busjam.data.PackInfo
import com.phloots.busjam.data.Progress
import com.phloots.busjam.game.GameUiState
import com.phloots.busjam.ui.DebugHeader
import com.phloots.busjam.ui.game.GameScreen
import com.phloots.busjam.ui.home.HomeScreen
import com.phloots.busjam.ui.levels.LevelSelectScreen
import com.phloots.busjam.ui.packColor
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The pack being played is always named on screen (debug header, level select, home selector). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class PackHeaderTest {
    @get:Rule
    val rule = createComposeRule()

    private val level = Level(7, 10, 12, listOf(Container(0, ContainerSize.S, 0, 1, 1, Dir.UP)), listOf(0, 0, 0, 0))

    @Test
    fun gameHeaderShowsPackLevelMetricsAndTags() {
        rule.setContent {
            GameScreen(
                ui = GameUiState(7, isBoss = false, hasNext = true, state = GameState.initial(level)),
                onTap = {}, onAnimationFinished = {}, onUndo = {}, onRestart = {}, onHint = {},
                onNext = {}, onLevels = {}, onHome = {},
                debugHeader = DebugHeader("Reverse preview", packColor(2), "score 41.0 · 4 colours", "CapacityChoice M6|L10 @12"),
            )
        }
        rule.onNodeWithText("Reverse preview · L7").assertIsDisplayed()
        rule.onNodeWithText("score 41.0 · 4 colours").assertIsDisplayed()
        rule.onNodeWithText("CapacityChoice M6|L10 @12").assertIsDisplayed()
    }

    @Test
    fun releaseHeaderHasNoPackChip() {
        rule.setContent {
            GameScreen(
                ui = GameUiState(7, isBoss = false, hasNext = true, state = GameState.initial(level)),
                onTap = {}, onAnimationFinished = {}, onUndo = {}, onRestart = {}, onHint = {},
                onNext = {}, onLevels = {}, onHome = {},
            )
        }
        rule.onNodeWithTag("pack-chip").assertDoesNotExist()
    }

    @Test
    fun levelSelectTitleShowsThePack() {
        rule.setContent {
            LevelSelectScreen(
                count = 20, progress = Progress(), onPlay = {}, onBack = {},
                pack = "Motif preview" to packColor(1),
            )
        }
        rule.onNodeWithText("Motif preview").assertIsDisplayed()
    }

    @Test
    fun homeSelectorSelectsAPack() {
        var picked: String? = null
        val packs = listOf(PackInfo.CAMPAIGN, PackInfo("packs/motif-preview.json", "Motif preview", "Motifs"))
        rule.setContent {
            HomeScreen(
                nextLevel = 1, totalLevels = 10, totalStars = 0, onPlay = {}, onLevels = {},
                packs = packs, selectedPack = PackInfo.CAMPAIGN_FILE, onSelectPack = { picked = it },
            )
        }
        rule.onNodeWithText("Campaign").assertIsDisplayed()
        rule.onNodeWithTag("pack-packs/motif-preview.json").performClick()
        assertEquals("packs/motif-preview.json", picked)
    }
}
