package com.phloots.crittercafe.ui.game

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.crittercafe.data.SeenMechanics
import com.phloots.crittercafe.game.Mechanics
import com.phloots.crittercafe.game.rt.CoreRealTimeRules
import com.phloots.crittercafe.game.rt.RealTimeViewModel
import com.phloots.crittercafe.ui.skin.LocalSkin
import com.phloots.crittercafe.ui.skin.critter.CritterCafeSkin
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.realtime.RealTimeState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * First-time mechanic intro cards on the real-time game screen (real ViewModel and core carousel; the level's
 * mechanics are injected, since core does not report them yet). The frame loop never idles, so the clock is driven
 * by hand.
 */
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
@RunWith(AndroidJUnit4::class)
class MechanicIntroTest {
    @get:Rule
    val rule = createComposeRule()

    private class FakeSeen(initial: Set<String> = emptySet()) : SeenMechanics {
        override val seen = MutableStateFlow(initial)
        override fun markSeen(id: String) {
            seen.value = seen.value + id
        }
    }

    private val level = Level(
        id = 1, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 1, 1, Dir.UP),
            Container(1, ContainerSize.S, 1, 3, 3, Dir.RIGHT),
        ),
        queue = listOf(0, 0, 0, 0, 1, 1, 1, 1),
    )

    private val vm = RealTimeViewModel<RealTimeState>({ _, _ -> }, CoreRealTimeRules())

    private fun show(mechanics: Set<String>, seen: SeenMechanics) {
        rule.mainClock.autoAdvance = false
        vm.start(level, 1, hasNext = true)
        rule.setContent {
            CompositionLocalProvider(LocalSkin provides CritterCafeSkin) {
                val ui by vm.ui.collectAsState()
                ui?.let {
                    RealTimeGameScreen(
                        ui = it, onFrame = vm::onFrame, onHostPause = vm::setHostPaused, onTogglePause = vm::togglePause,
                        onTap = vm::onContainerTapped, onUndo = vm::undo, onRestart = vm::restart, onHint = vm::hint,
                        onNext = {}, onLevels = {}, onHome = {},
                        mechanics = mechanics, seenMechanics = seen,
                    )
                }
            }
        }
        play(100)
    }

    private fun play(ms: Long) {
        var left = ms
        while (left > 0) {
            rule.mainClock.advanceTimeBy(50)
            left -= 50
        }
    }

    private fun introShown() = rule.onAllNodesWithTag("mechanic-intro").fetchSemanticsNodes().isNotEmpty()
    private fun shown(mechanic: String) = rule.onAllNodesWithTag("mechanic-intro-$mechanic").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun showsForAnUnseenMechanicAndRemembersIt() {
        val seen = FakeSeen()
        show(setOf(Mechanics.FROZEN), seen)
        rule.onNodeWithTag("mechanic-intro").assertIsDisplayed()
        rule.onNodeWithText("Frozen ticket").assertIsDisplayed()
        rule.onNodeWithText("The number shows how many other tickets must leave before this one can.").assertIsDisplayed()
        rule.onNodeWithTag("mechanic-art-frozen").assertIsDisplayed()
        assertTrue(seen.seen.value.isEmpty())
        rule.onNodeWithTag("intro-got-it").performClick()
        play(100)
        assertFalse(introShown())
        assertEquals(setOf(Mechanics.FROZEN), seen.seen.value)
    }

    @Test
    fun notShownAgainOnceSeen() {
        val seen = FakeSeen()
        show(setOf(Mechanics.TIED), seen)
        rule.onNodeWithTag("intro-got-it").performClick()
        play(100)
        // the next level uses the same mechanic: no card
        rule.runOnIdle { vm.start(level, 2, hasNext = true) }
        play(200)
        assertFalse(introShown())
        // a restart does not bring it back either
        rule.runOnIdle { vm.restart() }
        play(200)
        assertFalse(introShown())
    }

    @Test
    fun alreadySeenMechanicNeverShows() {
        show(setOf(Mechanics.GATE), FakeSeen(setOf(Mechanics.GATE)))
        assertFalse(introShown())
        rule.onNodeWithTag("ringfield").assertIsDisplayed()
    }

    @Test
    fun severalNewMechanicsShowOneAfterAnother() {
        val seen = FakeSeen(setOf(Mechanics.HIDDEN))
        show(setOf(Mechanics.TURNTABLE, Mechanics.HIDDEN, Mechanics.FROZEN), seen)
        assertTrue(shown(Mechanics.FROZEN))
        rule.onNodeWithText("1 of 2").assertIsDisplayed()
        rule.onNodeWithTag("intro-got-it").performClick()
        play(100)
        assertFalse(shown(Mechanics.FROZEN))
        assertTrue(shown(Mechanics.TURNTABLE))
        rule.onNodeWithText("2 of 2").assertIsDisplayed()
        assertFalse("seen before: skipped", shown(Mechanics.HIDDEN))
        rule.onNodeWithTag("intro-got-it").performClick()
        play(100)
        assertFalse(introShown())
        assertEquals(setOf(Mechanics.HIDDEN, Mechanics.FROZEN, Mechanics.TURNTABLE), seen.seen.value)
    }

    @Test
    fun gameIsPausedWhileTheCardIsUp() {
        show(setOf(Mechanics.WIDE, Mechanics.COLOUR_BENCH), FakeSeen())
        play(3_000)
        assertEquals("no carousel movement under the card", 0L, vm.ui.value!!.nowMs)
        assertEquals(0f, vm.ui.value!!.tickFraction)
        assertFalse("the player's pause is not used", vm.ui.value!!.paused)
        assertTrue(rule.onAllNodesWithTag("pause-menu").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("intro-got-it").performClick()
        play(3_000)
        assertEquals("still paused for the second card", 0L, vm.ui.value!!.nowMs)
        rule.onNodeWithTag("intro-got-it").performClick()
        play(3_000)
        assertTrue("play starts once the last card is gone", vm.ui.value!!.nowMs > 2_000)
    }

    @Test
    fun noMechanicsMeansNoCardAndNoWait() {
        show(emptySet(), FakeSeen())
        play(1_000)
        assertFalse(introShown())
        assertTrue(vm.ui.value!!.nowMs > 0)
    }
}
