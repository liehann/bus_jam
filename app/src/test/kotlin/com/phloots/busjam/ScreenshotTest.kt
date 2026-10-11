package com.phloots.busjam

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.busjam.core.model.Dir
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.ParkedVehicle
import com.phloots.busjam.core.model.Vehicle
import com.phloots.busjam.core.model.VehicleType
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
        val vs = mutableListOf<Vehicle>()
        fun add(t: VehicleType, color: Int, r: Int, c: Int, d: Dir) { vs += Vehicle(vs.size, t, color, r, c, d) }
        add(VehicleType.BUS, 0, 0, 0, Dir.UP)
        add(VehicleType.CAR, 1, 0, 2, Dir.RIGHT)
        add(VehicleType.VAN, 2, 1, 1, Dir.LEFT)
        add(VehicleType.CAR, 3, 0, 5, Dir.UP)
        add(VehicleType.BUS, 4, 4, 9, Dir.DOWN)
        add(VehicleType.VAN, 5, 2, 6, Dir.RIGHT)
        add(VehicleType.CAR, 6, 5, 2, Dir.DOWN)
        add(VehicleType.CAR, 7, 5, 4, Dir.LEFT)
        add(VehicleType.BUS, 1, 11, 4, Dir.RIGHT)
        add(VehicleType.VAN, 3, 7, 6, Dir.UP)
        add(VehicleType.CAR, 0, 7, 8, Dir.RIGHT)
        add(VehicleType.VAN, 2, 8, 0, Dir.UP)
        add(VehicleType.CAR, 5, 8, 4, Dir.DOWN)
        add(VehicleType.CAR, 6, 6, 7, Dir.LEFT)
        add(VehicleType.VAN, 4, 9, 9, Dir.UP)
        add(VehicleType.CAR, 7, 4, 0, Dir.UP)
        add(VehicleType.VAN, 1, 6, 3, Dir.DOWN)
        add(VehicleType.CAR, 2, 9, 1, Dir.LEFT)
        add(VehicleType.BUS, 3, 3, 4, Dir.LEFT)
        val queue = (0 until 60).map { (it * 5 + it / 7) % 8 }
        Level(1, 10, 12, vs, queue)
    }

    @Test
    fun gameScreenDense() {
        val state = GameState(
            level = dense,
            onBoard = ((1L shl dense.vehicles.size) - 1) and (1L shl 7).inv() and (1L shl 3).inv(),
            slots = listOf(ParkedVehicle(7, 7, 2, 0), null, ParkedVehicle(3, 3, 1, 1), null),
            queueIndex = 0,
            arrivalCounter = 2,
        )
        rule.setContent {
            GameScreen(
                ui = GameUiState(10, isBoss = true, hasNext = true, state = state, hintVehicleId = 5),
                onTap = {}, onAnimationFinished = {}, onUndo = {}, onRestart = {}, onHint = {},
                onNext = {}, onLevels = {}, onHome = {},
            )
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300)
        save("game_dense")
    }

    @Test
    fun animationFramesAndWinDialog() {
        val level = Level(
            1, 5, 5,
            listOf(
                Vehicle(0, VehicleType.CAR, 0, 1, 1, Dir.UP),
                Vehicle(1, VehicleType.VAN, 1, 3, 4, Dir.RIGHT),
            ),
            listOf(0, 0, 0, 0, 1, 1, 1, 1, 1, 1),
        )
        val vm = com.phloots.busjam.game.GameViewModel({ _, _ -> })
        vm.start(level, 1, hasNext = true)
        rule.setContent {
            val ui by vm.ui.collectAsState()
            ui?.let {
                GameScreen(
                    ui = it, onTap = vm::onVehicleTapped, onAnimationFinished = vm::onAnimationFinished,
                    onUndo = vm::undo, onRestart = vm::restart, onHint = vm::hint,
                    onNext = {}, onLevels = {}, onHome = {},
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        vm.onVehicleTapped(0)
        rule.mainClock.advanceTimeBy(160)
        save("anim_1_drive")
        rule.mainClock.advanceTimeBy(260)
        save("anim_2_boarding")
        rule.mainClock.advanceTimeBy(1000)
        vm.onVehicleTapped(1)
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
