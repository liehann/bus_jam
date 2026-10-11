package com.phloots.busjam

import org.junit.Ignore
import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.busjam.data.Progress
import com.phloots.busjam.game.GameUiState
import com.phloots.busjam.ui.game.GameScreen
import com.phloots.busjam.ui.levels.LevelSelectScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders key screens with Robolectric native graphics and writes PNGs to `app/build/screenshots/`
 * (visual sanity check; no golden comparison). Uses a dense 10x12 board with all 8 colours.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val dense: Level = run {
        val vs = mutableListOf<Container>()
        fun add(t: ContainerSize, color: Int, r: Int, c: Int, d: Dir) { vs += Container(vs.size, t, color, r, c, d) }
        add(ContainerSize.L, 0, 0, 0, Dir.UP)
        add(ContainerSize.S, 1, 0, 2, Dir.RIGHT)
        add(ContainerSize.M, 2, 1, 1, Dir.LEFT)
        add(ContainerSize.S, 3, 0, 5, Dir.UP)
        add(ContainerSize.L, 4, 4, 9, Dir.DOWN)
        add(ContainerSize.M, 5, 2, 6, Dir.RIGHT)
        add(ContainerSize.S, 6, 5, 2, Dir.DOWN)
        add(ContainerSize.S, 7, 5, 4, Dir.LEFT)
        add(ContainerSize.L, 1, 11, 4, Dir.RIGHT)
        add(ContainerSize.M, 3, 7, 6, Dir.UP)
        add(ContainerSize.S, 0, 7, 8, Dir.RIGHT)
        add(ContainerSize.M, 2, 8, 0, Dir.UP)
        add(ContainerSize.S, 5, 8, 4, Dir.DOWN)
        add(ContainerSize.S, 6, 6, 7, Dir.LEFT)
        add(ContainerSize.M, 4, 9, 9, Dir.UP)
        add(ContainerSize.S, 7, 4, 0, Dir.UP)
        add(ContainerSize.M, 1, 6, 3, Dir.DOWN)
        add(ContainerSize.S, 2, 9, 1, Dir.LEFT)
        add(ContainerSize.L, 3, 3, 4, Dir.LEFT)
        val queue = (0 until 60).map { (it * 5 + it / 7) % 8 }
        Level(1, 10, 12, vs, queue)
    }

    @Test
    fun gameScreenDense() {
        val state = GameState(
            level = dense,
            onBoard = Bits128.firstN(dense.containers.size).without(7).without(3),
            dock = listOf(DockedContainer(7, 7, 2), null, DockedContainer(3, 3, 1), null),
            staging = dense.queue.take(7),
            queueIndex = 7,
        )
        rule.setContent {
            GameScreen(
                ui = GameUiState(10, isBoss = true, hasNext = true, state = state, hintContainerId = 5),
                onTap = {}, onAnimationFinished = {}, onUndo = {}, onRestart = {}, onHint = {},
                onNext = {}, onLevels = {}, onHome = {},
            )
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        save("game_dense")
    }

    @Test
    @Ignore("v2 pending")
    fun animationFramesAndWinDialog() {
        val level = Level(
            1, 5, 5,
            listOf(
                Container(0, ContainerSize.S, 0, 1, 1, Dir.UP),
                Container(1, ContainerSize.M, 1, 3, 4, Dir.RIGHT),
            ),
            listOf(0, 0, 0, 0, 1, 1, 1, 1, 1, 1),
        )
        val vm = com.phloots.busjam.game.GameViewModel({ _, _ -> })
        vm.start(level, 1, hasNext = true)
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ui?.let {
                GameScreen(
                    ui = it, onTap = vm::onContainerTapped, onAnimationFinished = vm::onAnimationFinished,
                    onUndo = vm::undo, onRestart = vm::restart, onHint = vm::hint,
                    onNext = {}, onLevels = {}, onHome = {},
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        vm.onContainerTapped(0)
        rule.mainClock.advanceTimeBy(160)
        save("anim_1_drive")
        rule.mainClock.advanceTimeBy(260)
        save("anim_2_boarding")
        rule.mainClock.advanceTimeBy(1000)
        vm.onContainerTapped(1)
        rule.mainClock.advanceTimeBy(2000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        save("win_dialog")
    }

    @Test
    fun levelSelect() {
        rule.setContent {
            LevelSelectScreen(
                count = 1000,
                progress = Progress(highestUnlocked = 14, stars = (1..12).associateWith { 1 + it % 3 }),
                onPlay = {}, onBack = {},
            )
        }
        save("level_select")
    }

    @Test
    fun homeScreen() {
        rule.setContent {
            com.phloots.busjam.ui.home.HomeScreen(nextLevel = 13, totalLevels = 1000, totalStars = 24, onPlay = {}, onLevels = {})
        }
        save("home")
    }
}
