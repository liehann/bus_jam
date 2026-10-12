package com.phloots.busjam

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phloots.busjam.data.Progress
import com.phloots.busjam.game.GameViewModel
import com.phloots.busjam.ui.AppViewModel
import com.phloots.busjam.data.PackInfo
import com.phloots.busjam.ui.packColor
import com.phloots.busjam.ui.tileInfo
import com.phloots.busjam.game.rt.CoreRealTimeRules
import com.phloots.busjam.game.rt.RealTimeViewModel
import com.phloots.busjam.ui.game.GameScreen
import com.phloots.busjam.ui.game.RealTimeGameScreen
import com.phloots.jam.core.realtime.RealTimeState
import com.phloots.busjam.ui.home.HomeScreen
import com.phloots.busjam.ui.levels.LevelSelectScreen
import com.phloots.busjam.ui.theme.Palette
import com.phloots.busjam.ui.skin.FlatSkin
import com.phloots.busjam.ui.skin.LocalSkin
import com.phloots.busjam.ui.skin.critter.CritterCafeSkin
import com.phloots.busjam.ui.skin.critter.CritterHomeScreen
import com.phloots.busjam.ui.skin.critter.CritterLevelSelect
import androidx.compose.runtime.CompositionLocalProvider

/** Single activity, portrait only (manifest). */
class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // the launch splash stays up until the skin, mode, pack and progress are read (so the first frame is the
        // right home screen), at most 4 s
        val started = SystemClock.uptimeMillis()
        installSplashScreen().setKeepOnScreenCondition { !app.isReady() && SystemClock.uptimeMillis() - started < 4000 }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                BusJamApp()
            }
        }
    }
}

private const val HOME = 0
private const val LEVELS = -1

/** Screen routing: [HOME], [LEVELS], or a positive level number for the game screen. */
@Composable
fun BusJamApp() {
    val app: AppViewModel = viewModel()
    val game: GameViewModel = viewModel(
        factory = viewModelFactory { initializer { GameViewModel(app.progressStore(PackInfo.CAMPAIGN)) } },
    )
    val rtGame: RealTimeViewModel<RealTimeState> = viewModel(
        factory = viewModelFactory {
            initializer { RealTimeViewModel(app.progressStore(PackInfo.CAMPAIGN), CoreRealTimeRules()) }
        },
    )
    val critterCafe by app.critterCafe.collectAsStateWithLifecycle()
    // not read yet counts as Critter Café (the default), so the flat look never flashes on launch
    val skin = if (critterCafe != false) CritterCafeSkin else FlatSkin
    val realTimeOrNull by app.realTime.collectAsStateWithLifecycle()
    val realTime = realTimeOrNull == true
    val commitWins = {
        game.commitPendingWin()
        rtGame.commitPendingWin()
    }
    val loadedPack by app.loaded.collectAsStateWithLifecycle()
    val selectedPack by app.pack.collectAsStateWithLifecycle()
    val levels = loadedPack?.levels?.takeIf { it.isNotEmpty() }
    val packInfo = loadedPack?.info
    val packIndex = app.packs.indexOfFirst { it.file == packInfo?.file }.coerceAtLeast(0)
    val showPack = BuildConfig.DEBUG && packInfo != null
    val progressOrNull by app.progress.collectAsStateWithLifecycle()
    val progress = progressOrNull ?: Progress()
    var screen by rememberSaveable { mutableIntStateOf(HOME) }
    // A win whose animation is still playing when the app goes to the background is recorded now: the process may
    // be killed before the animation can finish.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { commitWins() }

    BackHandler(enabled = screen != HOME) {
        commitWins()
        screen = HOME
    }
    val openLevel: (Int) -> Unit = { n ->
        levels?.let { pack ->
            val number = n.coerceIn(1, pack.size)
            val sink = app.progressStore(packInfo!!)
            if (realTime) {
                rtGame.start(pack[number - 1].level, number, hasNext = number < pack.size, fresh = true, sink = sink)
            } else {
                game.start(pack[number - 1].level, number, hasNext = number < pack.size, fresh = true, sink = sink)
            }
            screen = number
        }
    }

    CompositionLocalProvider(LocalSkin provides skin) {
    Box(Modifier.fillMaxSize().background(if ((screen > 0 && realTime) || (screen <= HOME && skin === CritterCafeSkin)) skin.background else Palette.background).safeDrawingPadding()) {
        val pack = levels
        when {
            screen == HOME && skin === CritterCafeSkin -> CritterHomeScreen(
                nextLevel = pack?.let { progress.firstUnbeaten(it.size) }.takeIf { progressOrNull != null && realTimeOrNull != null },
                totalLevels = pack?.size ?: 0,
                totalStars = progress.stars.values.sum(),
                onPlay = { pack?.let { openLevel(progress.firstUnbeaten(it.size)) } },
                onLevels = { screen = LEVELS },
            )
            screen == HOME -> HomeScreen(
                nextLevel = pack?.let { progress.firstUnbeaten(it.size) }.takeIf { progressOrNull != null && realTimeOrNull != null },
                totalLevels = pack?.size ?: 0,
                totalStars = progress.stars.values.sum(),
                onPlay = { pack?.let { openLevel(progress.firstUnbeaten(it.size)) } },
                onLevels = { screen = LEVELS },
                packs = if (BuildConfig.DEBUG) app.packs else emptyList(),
                selectedPack = selectedPack?.file,
                onSelectPack = app::selectPack,
                realTime = if (BuildConfig.DEBUG) realTimeOrNull else null,
                onRealTime = app::setRealTime,
                critterCafe = if (BuildConfig.DEBUG) critterCafe else null,
                onCritterCafe = app::setCritterCafe,
            )
            screen == LEVELS && pack != null && skin === CritterCafeSkin -> CritterLevelSelect(
                count = pack.size,
                progress = progress,
                onPlay = openLevel,
                onBack = { screen = HOME },
            )
            screen == LEVELS && pack != null -> LevelSelectScreen(
                count = pack.size,
                progress = progress,
                onPlay = openLevel,
                onBack = { screen = HOME },
                unlockAll = BuildConfig.DEBUG,
                tileInfo = if (BuildConfig.DEBUG) { n -> pack.getOrNull(n - 1)?.tileInfo() } else null,
                showGoTo = BuildConfig.DEBUG,
                pack = if (showPack) packInfo!!.label to packColor(packIndex) else null,
            )
            screen > 0 && pack != null && realTime -> {
                val number = screen.coerceIn(1, pack.size)
                LaunchedEffect(number, pack) {
                    rtGame.start(
                        pack[number - 1].level, number, hasNext = number < pack.size, fresh = false,
                        sink = app.progressStore(packInfo!!),
                    )
                }
                val ui by rtGame.ui.collectAsStateWithLifecycle()
                val current = ui
                if (current != null && current.levelNumber == number) {
                    RealTimeGameScreen(
                        ui = current,
                        onFrame = rtGame::onFrame,
                        onHostPause = rtGame::setHostPaused,
                        onTogglePause = rtGame::togglePause,
                        onTap = rtGame::onContainerTapped,
                        onUndo = rtGame::undo,
                        onRestart = rtGame::restart,
                        onHint = rtGame::hint,
                        onNext = { openLevel(number + 1) },
                        onLevels = { commitWins(); screen = LEVELS },
                        onHome = { commitWins(); screen = HOME },
                    )
                }
            }
            screen > 0 && pack != null -> {
                val number = screen.coerceIn(1, pack.size)
                LaunchedEffect(number, pack) {
                    game.start(
                        pack[number - 1].level, number, hasNext = number < pack.size, fresh = false,
                        sink = app.progressStore(packInfo!!),
                    )
                }
                val ui by game.ui.collectAsStateWithLifecycle()
                val current = ui
                if (current != null && current.levelNumber == number) {
                    GameScreen(
                        ui = current,
                        onTap = game::onContainerTapped,
                        onAnimationFinished = game::onAnimationFinished,
                        onUndo = game::undo,
                        onRestart = game::restart,
                        onHint = game::hint,
                        onNext = { openLevel(number + 1) },
                        onLevels = { commitWins(); screen = LEVELS },
                        onHome = { commitWins(); screen = HOME },
                    )
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
    }
}
