package com.phloots.busjam

import android.os.Bundle
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.phloots.busjam.data.Progress
import com.phloots.busjam.game.GameViewModel
import com.phloots.busjam.ui.AppViewModel
import com.phloots.busjam.ui.game.GameScreen
import com.phloots.busjam.ui.home.HomeScreen
import com.phloots.busjam.ui.levels.LevelSelectScreen
import com.phloots.busjam.ui.theme.Palette

/** Single activity, portrait only (manifest). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
        factory = viewModelFactory { initializer { GameViewModel(app.progressStore) } },
    )
    val levels by app.levels.collectAsStateWithLifecycle()
    val progressOrNull by app.progress.collectAsStateWithLifecycle()
    val progress = progressOrNull ?: Progress()
    var screen by rememberSaveable { mutableIntStateOf(HOME) }

    BackHandler(enabled = screen != HOME) { screen = HOME }
    val openLevel: (Int) -> Unit = { n ->
        levels?.let { pack ->
            val number = n.coerceIn(1, pack.size)
            game.start(pack[number - 1], number, hasNext = number < pack.size, fresh = true)
            screen = number
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding()) {
        val pack = levels
        when {
            screen == HOME -> HomeScreen(
                nextLevel = pack?.let { progress.firstUnbeaten(it.size) }.takeIf { progressOrNull != null },
                totalLevels = pack?.size ?: 0,
                totalStars = progress.stars.values.sum(),
                onPlay = { pack?.let { openLevel(progress.firstUnbeaten(it.size)) } },
                onLevels = { screen = LEVELS },
            )
            screen == LEVELS && pack != null -> LevelSelectScreen(
                count = pack.size,
                progress = progress,
                onPlay = openLevel,
                onBack = { screen = HOME },
            )
            screen > 0 && pack != null -> {
                val number = screen.coerceIn(1, pack.size)
                LaunchedEffect(number, pack) {
                    game.start(pack[number - 1], number, hasNext = number < pack.size, fresh = false)
                }
                val ui by game.ui.collectAsStateWithLifecycle()
                val current = ui
                if (current != null && current.levelNumber == number) {
                    GameScreen(
                        ui = current,
                        onTap = game::onVehicleTapped,
                        onAnimationFinished = game::onAnimationFinished,
                        onUndo = game::undo,
                        onRestart = game::restart,
                        onHint = game::hint,
                        onNext = { openLevel(number + 1) },
                        onLevels = { screen = LEVELS },
                        onHome = { screen = HOME },
                    )
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
