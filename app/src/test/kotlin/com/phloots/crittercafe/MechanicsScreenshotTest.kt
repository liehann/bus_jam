package com.phloots.crittercafe

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.crittercafe.game.rt.CoreRealTimeRules
import com.phloots.crittercafe.game.rt.RtBump
import com.phloots.crittercafe.game.rt.RtEvent
import com.phloots.crittercafe.game.rt.RtUiState
import com.phloots.crittercafe.ui.game.RealTimeGameScreen
import com.phloots.crittercafe.ui.skin.FlatSkin
import com.phloots.crittercafe.ui.skin.LocalSkin
import com.phloots.crittercafe.ui.skin.Skin
import com.phloots.crittercafe.ui.skin.critter.CafeThemes
import com.phloots.crittercafe.ui.skin.critter.CritterCafeSkin
import com.phloots.crittercafe.core.model.Cell
import com.phloots.crittercafe.core.model.Container
import com.phloots.crittercafe.core.model.ContainerSize
import com.phloots.crittercafe.core.model.Dir
import com.phloots.crittercafe.core.model.Gate
import com.phloots.crittercafe.core.model.Level
import com.phloots.crittercafe.core.realtime.RealTime
import com.phloots.crittercafe.core.realtime.RealTimeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

/**
 * The v4 mechanics on the real-time game screen (Pixel 9 Pro), from real core states: one board with every mechanic,
 * frames before and during a release's changes (turntable turn, reveal flip, gate swing, frozen countdown), a wide
 * ticket docked across two tables, a refused tap on a frozen ticket and a bump against a closed gate. Writes PNGs to
 * `app/build/screenshots/mech_*.png`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w427dp-h952dp-xxhdpi")
class MechanicsScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val rules = CoreRealTimeRules()

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        const val FROZEN = 0
        const val WAITING = 1
        const val PARTNER = 2
        const val HIDDEN = 3
        const val COVER = 4
        const val TURNTABLE = 5
        const val WIDE = 6
        const val GATED = 7

        /**
         * 10 x 12 board using every mechanic: frozen S (3 releases) at (3,2) under a closed top gate at column 2; M
         * [WAITING] tied to S [PARTNER]; hidden S at (1,5) covered by the free S [COVER] at (0,5); S on a turntable
         * at (4,7) facing right; free wide M at (1,8); S [GATED] at (1,3) under a second closed gate; an open gate on
         * the right edge at row 6; bench 1 takes colour 1 only. The rest is filled with plain containers.
         */
        fun mechanicsLevel(queuePreview: Int = 30, seats: Int = 40): Level {
            val width = 10
            val height = 12
            val special = listOf(
                Container(FROZEN, ContainerSize.S, 6, 3, 2, Dir.UP, frozen = 3),
                Container(WAITING, ContainerSize.M, 4, 6, 1, Dir.RIGHT, tiedTo = PARTNER),
                Container(PARTNER, ContainerSize.S, 7, 6, 4, Dir.UP),
                Container(HIDDEN, ContainerSize.S, 6, 1, 5, Dir.UP, hidden = true),
                Container(COVER, ContainerSize.S, 0, 0, 5, Dir.UP),
                Container(TURNTABLE, ContainerSize.S, 3, 4, 7, Dir.RIGHT),
                Container(WIDE, ContainerSize.M, 5, 1, 8, Dir.UP, wide = true),
                Container(GATED, ContainerSize.S, 1, 1, 3, Dir.UP),
            )
            val used = Array(height) { BooleanArray(width) }
            special.forEach { v -> v.cells().forEach { used[it.row][it.col] = true } }
            // keep the rays of the free tickets and the gates' columns clear
            for ((r, c) in listOf(0 to 2, 1 to 2, 2 to 2, 0 to 3, 0 to 8, 4 to 8, 4 to 9, 6 to 9, 6 to 8, 6 to 7, 6 to 6, 6 to 5, 0 to 4, 1 to 4, 2 to 4, 3 to 4, 4 to 4, 5 to 4)) {
                used[r][c] = true
            }
            val rnd = Random(5)
            val cs = special.toMutableList()
            for (r in 0 until height) for (c in 0 until width) {
                if (used[r][c] || rnd.nextFloat() < 0.15f) continue
                val size = ContainerSize.entries[rnd.nextInt(3)]
                for (d in Dir.entries.shuffled(rnd)) {
                    val v = Container(cs.size, size, rnd.nextInt(8), r, c, d)
                    val cells = v.cells()
                    if (cells.any { it.row !in 0 until height || it.col !in 0 until width || used[it.row][it.col] }) continue
                    cells.forEach { used[it.row][it.col] = true }
                    cs += v
                    break
                }
            }
            // free cells reserved for rays stay empty; queue: the cover's and the wide one's colours first
            val counts = IntArray(10)
            cs.forEach { counts[it.color] += it.size.capacity }
            val head = List(4) { 0 } + List(6) { 5 }
            head.forEach { counts[it]-- }
            val rest = (0 until 10).flatMap { col -> List(counts[col]) { col } }.shuffled(rnd)
            return Level(
                id = 1, width = width, height = height, containers = cs, queue = head + rest,
                stagingSpots = seats,
                benchColors = listOf(-1, 1, -1, -1),
                turntables = listOf(Cell(4, 7)),
                gates = listOf(Gate(Dir.UP, 2, open = false), Gate(Dir.UP, 3, open = false), Gate(Dir.RIGHT, 6)),
                queuePreview = queuePreview,
            )
        }
    }

    private data class Frame(val name: String?, val state: RealTimeState, val nowMs: Long, val bumps: List<RtBump> = emptyList())

    private fun show(skin: Skin, frames: List<Frame>) {
        var shown by mutableStateOf(frames.first())
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSkin provides skin) {
                val f = shown
                RealTimeGameScreen(
                    ui = RtUiState(7, false, true, rules.view(f.state), nowMs = f.nowMs, tickFraction = 0.5f, canUndo = true, bumps = f.bumps),
                    onFrame = {}, onHostPause = {}, onTogglePause = {}, onTap = {}, onUndo = {}, onRestart = {}, onHint = {},
                    onNext = {}, onLevels = {}, onHome = {},
                )
            }
        }
        for (f in frames) {
            shown = f
            rule.mainClock.advanceTimeBy(100)
            f.name?.let(::save)
        }
    }

    private fun run(s: RealTimeState, ticks: Int): RealTimeState {
        var st = s
        repeat(ticks) { st = rules.tick(st).state }
        return st
    }

    /** Start, the cover's release (mid change and after), then the wide ticket docked across two tables. */
    private fun frames(prefix: String, level: Level = mechanicsLevel()): List<Frame> {
        assertEquals(emptyList<String>(), level.validate())
        val start = RealTime.runUntilSettled(rules.initial(level)).first
        val afterCover = rules.tap(start, COVER).state
        val docked = run(afterCover, 40)
        val wide = run(rules.tap(docked, WIDE).state, 60)
        // refused tap on the frozen ticket, and the gated ticket bumping its closed gate
        val nudge = RtBump.nudge(level, FROZEN, 5000)
        val gateBump = (rules.tap(wide, GATED).events.single().let { it as? RtEvent.Blocked ?: error("$it dock=${rules.view(wide).slots}") }).let { RtBump(it, 5000) }
        return listOf(
            Frame("${prefix}_1_start", start, 0),
            Frame(null, afterCover, 1000),
            Frame("${prefix}_2_release_mid", afterCover, 1000 + 190),
            Frame("${prefix}_3_release_after", docked, 2000),
            Frame(null, wide, 2900),
            Frame("${prefix}_4_wide_docked", wide, 3500),
            Frame("${prefix}_5_frozen_nudge", wide, 5000 + 90, listOf(nudge)),
            Frame("${prefix}_6_gate_bump", wide, 5000 + 120, listOf(gateBump)),
        )
    }

    @Test
    fun critterCafeMechanics() = show(CritterCafeSkin, frames("mech_critter"))

    @Test
    fun flatMechanics() = show(FlatSkin, frames("mech_flat"))

    @Test
    fun luauMechanics() = show(CafeThemes.skin("luau_night")!!, frames("mech_luau").let { it.take(1) + it.subList(4, 7) })

    /** The longest preview the belt is asked for, on the smallest ring (largest plates). */
    @Test
    fun belt30OnRing20() {
        val level = mechanicsLevel(queuePreview = 30, seats = 20)
        show(CritterCafeSkin, listOf(Frame("mech_belt30_ring20", RealTime.runUntilSettled(rules.initial(level)).first, 0)))
    }

    @Test
    fun belt40OnRing40() {
        val level = mechanicsLevel(queuePreview = 40, seats = 40)
        show(CritterCafeSkin, listOf(Frame("mech_belt40_ring40", RealTime.runUntilSettled(rules.initial(level)).first, 0)))
    }

    @Test
    fun belt5Flat() {
        val level = mechanicsLevel(queuePreview = 5)
        show(FlatSkin, listOf(Frame("mech_flat_queue5", RealTime.runUntilSettled(rules.initial(level)).first, 0)))
    }
}
