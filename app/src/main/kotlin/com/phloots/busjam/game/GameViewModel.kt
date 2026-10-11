package com.phloots.busjam.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phloots.busjam.core.engine.Engine
import com.phloots.busjam.core.model.Event
import com.phloots.busjam.core.model.GameState
import com.phloots.busjam.core.model.IgnoreReason
import com.phloots.busjam.core.model.Level
import com.phloots.busjam.core.model.Move
import com.phloots.busjam.core.model.Status
import com.phloots.busjam.core.solver.Solver
import com.phloots.busjam.data.ProgressSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A transition the UI is currently playing back. [from] is the state before it. */
data class AnimJob(val seq: Long, val from: GameState, val events: List<Event>) {
    val phases: List<Phase> by lazy { planPhases(from, events) }
}

data class GameUiState(
    val levelNumber: Int,
    val isBoss: Boolean,
    val hasNext: Boolean,
    /** Latest logical state (all applied moves). While [anim] is non-null the screen shows its playback instead. */
    val state: GameState,
    val anim: AnimJob? = null,
    val hintVehicleId: Int? = null,
    val hintLoading: Boolean = false,
    val usedUndo: Boolean = false,
    val usedHint: Boolean = false,
    val canUndo: Boolean = false,
    /** Increments whenever a tap is refused because all slots are full (UI shakes the slots). */
    val slotsFullSignal: Int = 0,
    /** Stars earned (set once WON). */
    val earnedStars: Int = 0,
) {
    val potentialStars: Int get() = starsFor(usedUndo, usedHint)

    /** True once the game is over and its final animation has finished. */
    val showResult: Boolean get() = anim == null && state.status != Status.PLAYING
}

/** Stars rule: 3 = no undo/hint, 2 = used undo, 1 = used hint. */
fun starsFor(usedUndo: Boolean, usedHint: Boolean): Int = when {
    usedHint -> 1
    usedUndo -> 2
    else -> 3
}

/**
 * Game screen state holder. All rules go through [Engine.apply]; hints through [Solver.hint].
 *
 * Taps are queued: while a transition's animation plays, further taps are buffered and applied in order
 * once [onAnimationFinished] is called for the current job. All methods must be called on the main thread.
 */
class GameViewModel(
    private val progress: ProgressSink,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _ui = MutableStateFlow<GameUiState?>(null)
    val ui: StateFlow<GameUiState?> = _ui.asStateFlow()

    private val undoStack = ArrayDeque<GameState>()
    private val pending = ArrayDeque<Move>()
    private var seq = 0L
    private var hintJob: Job? = null
    private var level: Level? = null

    /**
     * Starts [level] as campaign level [levelNumber]. Unless [fresh], keeps an in-progress game of the same level
     * (e.g. after recomposition / configuration change).
     */
    fun start(level: Level, levelNumber: Int, hasNext: Boolean, fresh: Boolean = true) {
        val current = _ui.value
        if (!fresh && current != null && this.level == level && current.levelNumber == levelNumber) return
        this.level = level
        reset()
        _ui.value = GameUiState(
            levelNumber = levelNumber,
            isBoss = levelNumber % 10 == 0,
            hasNext = hasNext,
            state = GameState.initial(level),
        )
    }

    fun onVehicleTapped(vehicleId: Int) {
        if (_ui.value == null) return
        pending.addLast(Move(vehicleId))
        pump()
    }

    /** Called by the UI when it has finished playing back job [seq]. */
    fun onAnimationFinished(seq: Long) {
        val ui = _ui.value ?: return
        if (ui.anim?.seq != seq) return
        _ui.value = ui.copy(anim = null)
        pump()
    }

    fun undo() {
        val ui = _ui.value ?: return
        val prev = undoStack.removeLastOrNull() ?: return
        cancelInFlight()
        _ui.value = ui.copy(
            state = prev,
            anim = null,
            usedUndo = true,
            canUndo = undoStack.isNotEmpty(),
            hintVehicleId = null,
            hintLoading = false,
            earnedStars = 0,
        )
    }

    /** Back to the initial state; clears the undo stack and undo/hint penalties. */
    fun restart() {
        val ui = _ui.value ?: return
        val level = level ?: return
        reset()
        _ui.value = GameUiState(
            levelNumber = ui.levelNumber,
            isBoss = ui.isBoss,
            hasNext = ui.hasNext,
            state = GameState.initial(level),
        )
    }

    fun hint() {
        val ui = _ui.value ?: return
        if (ui.state.status != Status.PLAYING || ui.hintLoading) return
        // Computed for the latest logical state; discarded if the state changes before it arrives.
        val target = ui.state
        _ui.value = ui.copy(usedHint = true, hintLoading = true)
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            val move = withContext(computeDispatcher) { Solver.hint(target) }
            val now = _ui.value ?: return@launch
            if (now.state === target) {
                _ui.value = now.copy(hintVehicleId = move?.vehicleId, hintLoading = false)
            } else {
                _ui.value = now.copy(hintLoading = false)
            }
        }
    }

    private fun reset() {
        cancelInFlight()
        undoStack.clear()
    }

    private fun cancelInFlight() {
        pending.clear()
        hintJob?.cancel()
        hintJob = null
    }

    /** Applies queued taps until one produces something to animate. */
    private fun pump() {
        while (true) {
            val ui = _ui.value ?: return
            if (ui.anim != null) return
            val move = pending.removeFirstOrNull() ?: return
            val before = ui.state
            val t = Engine.apply(before, move)
            val ignored = t.events.singleOrNull() as? Event.Ignored
            if (ignored != null || t.events.isEmpty()) {
                if (ignored?.reason == IgnoreReason.SLOTS_FULL) {
                    _ui.value = ui.copy(slotsFullSignal = ui.slotsFullSignal + 1)
                }
                continue
            }
            val changed = t.state != before
            if (changed) undoStack.addLast(before)
            val won = t.state.status == Status.WON
            val stars = if (won) starsFor(ui.usedUndo, ui.usedHint) else 0
            _ui.value = ui.copy(
                state = t.state,
                anim = AnimJob(++seq, before, t.events),
                canUndo = undoStack.isNotEmpty(),
                hintVehicleId = if (changed) null else ui.hintVehicleId,
                earnedStars = stars,
            )
            if (won) {
                pending.clear()
                viewModelScope.launch { progress.recordWin(ui.levelNumber, stars) }
            }
        }
    }
}
