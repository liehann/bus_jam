package com.phloots.busjam

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.busjam.data.PackInfo
import com.phloots.busjam.data.Progress
import com.phloots.busjam.ui.home.HomeScreen
import com.phloots.busjam.ui.levels.LevelSelectScreen
import com.phloots.busjam.ui.packColor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The pack is named on the level select and home screens (the game screen shows only the level). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class PackHeaderTest {
    @get:Rule
    val rule = createComposeRule()

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
