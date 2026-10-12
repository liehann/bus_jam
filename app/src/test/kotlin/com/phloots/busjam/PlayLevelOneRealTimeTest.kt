package com.phloots.busjam

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.jam.core.io.LevelPackCodec
import com.phloots.jam.core.solver.Solver
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Real-time mode (the debug default): plays level 1 along the solver's line, waiting patiently after each tap for the
 * carousel to finish boarding (one rotation is ~4 s), until the win dialog shows.
 */
@OptIn(ExperimentalTestApi::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
@RunWith(AndroidJUnit4::class)
class PlayLevelOneRealTimeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /** Runs the frame loop for [ms] of game time. */
    private fun play(ms: Long) {
        var left = ms
        while (left > 0) {
            rule.mainClock.advanceTimeBy(50)
            left -= 50
        }
    }

    @Test
    fun patientSolverLineWinsLevelOne() {
        val json = rule.activity.assets.open("levels.json").bufferedReader().use { it.readText() }
        val level = LevelPackCodec.decode(json).levels.first().level
        val solution = Solver.solve(level)
        assertTrue("level 1 must be solvable", solution.solvable)

        rule.waitUntilAtLeastOneExists(hasTestTag("play"), timeoutMillis = 10_000)
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag("play").assertIsEnabled() }.isSuccess }
        // the game's frame loop never idles: drive the clock by hand from here on
        rule.mainClock.autoAdvance = false
        // open level 1 explicitly ("Play" opens the first unbeaten level, which depends on stored progress)
        rule.onNodeWithTag("levels").performClick()
        play(200)
        rule.onNodeWithTag("level-1").performClick()
        var t = 0
        while (rule.onAllNodesWithTag("ringfield").fetchSemanticsNodes().isEmpty() && t++ < 200) play(50)
        for (move in solution.solution) {
            rule.onNodeWithTag("container-${move.containerId}").performClick()
            play(8_000) // two full rotations: everything boardable boards, full containers dispatch
        }
        var waited = 0
        while (rule.onAllNodesWithTag("win-dialog").fetchSemanticsNodes().isEmpty() && waited < 30_000) {
            play(500)
            waited += 500
        }
        rule.onNodeWithTag("win-dialog").assertIsDisplayed()
    }
}
