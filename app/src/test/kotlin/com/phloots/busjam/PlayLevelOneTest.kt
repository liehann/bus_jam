package com.phloots.busjam

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.busjam.core.io.LevelPackCodec
import com.phloots.busjam.core.solver.Solver
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plays level 1 to a win by tapping the board along the solver's move list. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayLevelOneTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun solverLineWinsLevelOne() {
        val json = rule.activity.assets.open("levels.json").bufferedReader().use { it.readText() }
        val level = LevelPackCodec.decode(json).levels.first().level
        val solution = Solver.solve(level)
        assertTrue("level 1 must be solvable", solution.solvable)

        rule.waitUntilAtLeastOneExists(hasTestTag("play"), timeoutMillis = 10_000)
        // Play is enabled once the level pack and progress have loaded.
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("play").assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag("play").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("board"), timeoutMillis = 10_000)

        for (move in solution.solution) {
            rule.onNodeWithTag("vehicle-${move.vehicleId}").performClick()
            rule.waitForIdle()
        }
        rule.waitUntilAtLeastOneExists(hasTestTag("win-dialog"), timeoutMillis = 10_000)
        rule.onNodeWithTag("win-dialog").assertIsDisplayed()
    }
}
