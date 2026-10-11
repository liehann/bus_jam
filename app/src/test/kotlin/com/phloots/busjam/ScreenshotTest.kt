package com.phloots.busjam

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.busjam.data.Progress
import com.phloots.busjam.game.AnimJob
import com.phloots.busjam.game.GameUiState
import com.phloots.busjam.game.NO_WINNING_MOVE
import com.phloots.busjam.ui.LevelTileInfo
import com.phloots.busjam.ui.game.GameScreen
import com.phloots.busjam.ui.levels.LevelSelectScreen
import com.phloots.jam.core.model.Bits128
import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.DockedContainer
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Status
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * Renders key screens with Robolectric native graphics and writes PNGs to `app/build/screenshots/`
 * (visual sanity check; no golden comparison). Animation frames use hand-written event lists (shaped like the
 * Engine's) so they do not depend on the engine or the level pack.
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

    /**
     * A densely filled board. Container 0 is an S of colour 0 on the top row facing up (free). The queue starts
     * c c c x c y z with c = 0 so container 0 boards four staged elements.
     */
    private fun denseLevel(width: Int, height: Int, seed: Int, colours: Int = 8): Level {
        val rnd = Random(seed)
        val used = Array(height) { BooleanArray(width) }
        val cs = mutableListOf<Container>()
        fun tryAdd(size: ContainerSize, color: Int, r: Int, c: Int, d: Dir): Boolean {
            val v = Container(cs.size, size, color, r, c, d)
            val cells = v.cells()
            if (cells.any { it.row !in 0 until height || it.col !in 0 until width || used[it.row][it.col] }) return false
            cells.forEach { used[it.row][it.col] = true }
            cs += v
            return true
        }
        tryAdd(ContainerSize.S, 0, 0, width / 2, Dir.UP)
        for (r in 0 until height) for (c in 0 until width) {
            if (used[r][c] || rnd.nextFloat() < 0.12f) continue
            val size = ContainerSize.entries[rnd.nextInt(3)]
            val dirs = Dir.entries.shuffled(rnd)
            for (d in dirs) if (tryAdd(size, rnd.nextInt(colours), r, c, d)) break
        }
        val prefix = listOf(0, 0, 0, 1, 0, 2, 3, 4, 5, 1, 2)
        val counts = IntArray(colours)
        cs.forEach { counts[it.color] += it.size.capacity }
        prefix.forEach { counts[it]-- }
        val rest = (0 until colours).flatMap { col -> List(counts[col].coerceAtLeast(0)) { col } }.shuffled(rnd)
        return Level(1, width, height, cs, prefix + rest)
    }

    /** Staging size rendered in the v2 frames (the new 40-spot rule; independent of the current Limits value). */
    private val spots = 40

    /** All containers on board, staging filled with the first [spots] elements. */
    private fun initial(level: Level): GameState {
        val n = minOf(spots, level.queue.size)
        return GameState(level, Bits128.firstN(level.containers.size), List(4) { null }, level.queue.take(n), n)
    }

    private fun stateWithDock(level: Level): GameState {
        // two containers already docked (removed from the board), partially filled
        val m = level.containers.first { it.color == 6 && it.size == ContainerSize.M }
        val l = level.containers.first { it.color == 7 && it.size == ContainerSize.L }
        val init = initial(level)
        return init.copy(
            onBoard = init.onBoard.without(m.id).without(l.id),
            dock = listOf(null, DockedContainer(m.id, 6, 4), DockedContainer(l.id, 7, 3), null),
        )
    }

    private fun show(ui: GameUiState, debugInfo: String? = null) {
        rule.setContent {
            GameScreen(
                ui = ui,
                onTap = {}, onAnimationFinished = {}, onUndo = {}, onRestart = {}, onHint = {},
                onNext = {}, onLevels = {}, onHome = {},
                debugInfo = debugInfo,
                stagingSpots = spots,
            )
        }
    }

    @Test
    fun gameScreen12x14() {
        val level = denseLevel(12, 14, seed = 7)
        val state = stateWithDock(level)
        val hint = level.containers.first { it.size == ContainerSize.L && state.isOnBoard(it.id) }.id
        rule.mainClock.autoAdvance = false
        show(
            GameUiState(10, isBoss = true, hasNext = true, state = state, hintContainerId = hint),
            debugInfo = "score 63.2 · 8 colours · ${level.containers.size} containers · 12x14 · ${level.queue.size} elements",
        )
        rule.mainClock.advanceTimeBy(300)
        save("v2_game_12x14")
    }

    @Test
    fun gameScreenTenColours() {
        val level = denseLevel(12, 14, seed = 11, colours = 10)
        val init = initial(level)
        val a = level.containers.first { it.color == 8 && it.size == ContainerSize.L }
        val b = level.containers.first { it.color == 9 && it.size == ContainerSize.M }
        val state = init.copy(
            onBoard = init.onBoard.without(a.id).without(b.id),
            dock = listOf(DockedContainer(a.id, 8, 4), DockedContainer(b.id, 9, 2), null, null),
        )
        rule.mainClock.autoAdvance = false
        show(GameUiState(30, isBoss = true, hasNext = true, state = state))
        rule.mainClock.advanceTimeBy(300)
        save("v2_game_10_colours")
    }

    @Test
    fun gameScreen10x12WithHintMessage() {
        val level = denseLevel(10, 12, seed = 3)
        rule.mainClock.autoAdvance = false
        show(GameUiState(2, isBoss = false, hasNext = true, state = initial(level), hintMessage = NO_WINNING_MOVE, canUndo = true))
        rule.mainClock.advanceTimeBy(300)
        save("v2_game_10x12")
    }

    @Test
    fun animationFrames() {
        val level = denseLevel(12, 14, seed = 7)
        val from = stateWithDock(level)
        // container 0 (S, colour 0) releases into slot 0; staged q0 q1 q2 q4 board (refilling from the queue),
        // it fills and dispatches, then staging refills with q10.
        val events = listOf(
            Event.Released(0, 0),
            Event.Boarded(0, 0, 0, 0), Event.Staged(spots, spots - 1),
            Event.Boarded(1, 0, 0, 0), Event.Staged(spots + 1, spots - 1),
            Event.Boarded(2, 0, 0, 0), Event.Staged(spots + 2, spots - 1),
            Event.Boarded(4, 1, 0, 0),
            Event.Dispatched(0, 0),
            Event.Staged(spots + 3, spots - 1),
        )
        rule.mainClock.autoAdvance = false
        show(GameUiState(1, isBoss = false, hasNext = true, state = from, anim = AnimJob(1, from, events)))
        rule.mainClock.advanceTimeBy(32)
        rule.mainClock.advanceTimeBy(90)
        save("v2_anim_1_release_drive")
        rule.mainClock.advanceTimeBy(110)
        save("v2_anim_2_release_glide")
        rule.mainClock.advanceTimeBy(230)
        save("v2_anim_3_flow")
        rule.mainClock.advanceTimeBy(250)
        save("v2_anim_4_dispatch")
        rule.mainClock.advanceTimeBy(400)
        save("v2_anim_5_after")
    }

    @Test
    fun blockedFrame() {
        val level = denseLevel(12, 14, seed = 7)
        val state = initial(level)
        // first container whose exit ray hits another container; stop = the cell before the blocker
        val occ = HashMap<Pair<Int, Int>, Int>()
        level.containers.forEach { v -> v.cells().forEach { occ[it.row to it.col] = v.id } }
        var block: Event.Blocked? = null
        var best = -1
        for (v in level.containers) {
            var r = v.row + v.dir.dRow
            var c = v.col + v.dir.dCol
            var steps = 0
            while (level.inBounds(r, c)) {
                val o = occ[r to c]
                if (o != null && o != v.id) {
                    if (steps > best) {
                        best = steps
                        block = Event.Blocked(v.id, r - v.dir.dRow, c - v.dir.dCol, o)
                    }
                    break
                }
                r += v.dir.dRow; c += v.dir.dCol; steps++
            }
        }
        rule.mainClock.autoAdvance = false
        show(GameUiState(1, isBoss = false, hasNext = true, state = state, anim = AnimJob(1, state, listOf(block!!))))
        rule.mainClock.advanceTimeBy(32 + 130)
        save("v2_anim_blocked")
    }

    @Test
    fun winDialog() {
        val level = denseLevel(10, 12, seed = 3)
        val won = GameState(level, Bits128.EMPTY, List(4) { null }, emptyList(), level.queue.size, Status.WON)
        show(GameUiState(4, isBoss = false, hasNext = true, state = won, earnedStars = 2, usedUndo = true))
        rule.waitForIdle()
        save("v2_win_dialog")
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
    fun levelSelectDebug() {
        rule.setContent {
            LevelSelectScreen(
                count = 1000,
                progress = Progress(highestUnlocked = 3, stars = mapOf(1 to 3, 2 to 1)),
                onPlay = {}, onBack = {},
                unlockAll = true,
                tileInfo = { n -> LevelTileInfo("%.1f".format(10.0 + n * 0.7), "${4 + n % 5}c ${30 + n}n 12x14") },
                showGoTo = true,
            )
        }
        save("v2_level_select_debug")
    }

    @Test
    fun homeScreen() {
        rule.setContent {
            com.phloots.busjam.ui.home.HomeScreen(nextLevel = 13, totalLevels = 1000, totalStars = 24, onPlay = {}, onLevels = {})
        }
        save("home")
    }
}
