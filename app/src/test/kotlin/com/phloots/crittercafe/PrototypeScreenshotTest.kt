package com.phloots.crittercafe

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phloots.crittercafe.data.LevelMeta
import com.phloots.crittercafe.data.Progress
import com.phloots.crittercafe.data.SeenMechanics
import com.phloots.crittercafe.game.levelMechanics
import com.phloots.crittercafe.game.rt.CoreRealTimeRules
import com.phloots.crittercafe.game.rt.RtUiState
import com.phloots.crittercafe.ui.game.RealTimeGameScreen
import com.phloots.crittercafe.ui.skin.LocalSkin
import com.phloots.crittercafe.ui.skin.critter.CafeThemes
import com.phloots.crittercafe.ui.skin.critter.CritterCafeSkin
import com.phloots.crittercafe.ui.skin.critter.CritterLevelSelect
import com.phloots.crittercafe.core.io.LevelPackCodec
import com.phloots.crittercafe.core.realtime.RealTime
import com.phloots.crittercafe.core.solver.Solver
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The prototype pack (app/src/prototype/assets/levels.json, tools/prototype_pack.py) on a Pixel 9 Pro: the second
 * level of each theme block a few moves in, drawn in its pack theme, the first level with its intro card, and the
 * grouped level list. Writes `app/build/screenshots/proto_*.png`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w427dp-h952dp-xxhdpi")
class PrototypeScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }
    private val rules = CoreRealTimeRules()
    private val json = File("src/prototype/assets/levels.json").readText()
    private val levels = LevelPackCodec.decode(json).levels
    private val meta = LevelMeta.parse(json)

    private fun save(name: String) {
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Level [number] after [moves] moves of its solver line, drawn in its theme; [intro] shows its intro card. */
    private fun showLevel(number: Int, moves: Int, name: String, intro: Boolean = false) {
        val level = levels[number - 1].level
        val theme = meta[number - 1].theme
        val skin = CafeThemes.skin(theme!!)!!
        val line = Solver.solve(level, 20_000_000)!!.solution
        var st = RealTime.runUntilSettled(rules.initial(level)).first
        for (m in line.take(moves)) {
            st = rules.tap(st, m.containerId).state
            repeat(24) { st = rules.tick(st).state }
        }
        val seen = object : SeenMechanics {
            override val seen = MutableStateFlow(if (intro) emptySet() else levelMechanics(level))
            override fun markSeen(id: String) {}
        }
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSkin provides skin) {
                RealTimeGameScreen(
                    ui = RtUiState(number, false, true, rules.view(st), nowMs = 0, tickFraction = 0.5f, canUndo = true),
                    onFrame = {}, onHostPause = {}, onTogglePause = {}, onTap = {}, onUndo = {}, onRestart = {}, onHint = {},
                    onNext = {}, onLevels = {}, onHome = {},
                    mechanics = levelMechanics(level), seenMechanics = seen,
                )
            }
        }
        rule.mainClock.advanceTimeBy(600)
        save(name)
    }

    @Test
    fun themesInOrder() {
        assertEquals(
            listOf("spa", "brunch", "luau_night", "dimsum", "hanami", "ramen_night", "camping_night", "jungle"),
            meta.map { it.theme }.distinct(),
        )
    }

    @Test fun spa() = showLevel(2, 3, "proto_02_spa")
    @Test fun spaIntro() = showLevel(1, 0, "proto_01_spa_intro", intro = true)
    @Test fun brunch() = showLevel(5, 3, "proto_05_brunch")
    @Test fun luauNight() = showLevel(8, 3, "proto_08_luau_night")
    @Test fun dimsum() = showLevel(11, 3, "proto_11_dimsum")
    @Test fun hanami() = showLevel(14, 3, "proto_14_hanami")
    @Test fun ramenNight() = showLevel(17, 3, "proto_17_ramen_night")
    @Test fun campingNight() = showLevel(20, 3, "proto_20_camping_night")
    @Test fun jungle() = showLevel(23, 3, "proto_23_jungle")

    @Test
    fun levelList() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalSkin provides CritterCafeSkin) {
                CritterLevelSelect(
                    count = levels.size, progress = Progress(highestUnlocked = Int.MAX_VALUE, stars = mapOf(1 to 3, 2 to 2)),
                    onPlay = {}, onBack = {}, groups = meta.map { it.group },
                )
            }
        }
        rule.mainClock.advanceTimeBy(300)
        save("proto_levels")
    }
}
