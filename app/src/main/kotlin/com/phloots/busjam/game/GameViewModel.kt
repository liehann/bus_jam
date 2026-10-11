package com.phloots.busjam.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phloots.busjam.data.ProgressSink
import com.phloots.jam.core.engine.Engine
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.model.Transition
import com.phloots.jam.core.solver.Solver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A transition the UI is currently playing back. [from] is the state before it. */
data class AnimJob(val seq: Long, val from: GameState, val events: List<Event>) {
    val phases: List<Phase> by lazy { planPhases(from, events) }
}

/** Shown when the solver finds no winning continuation. */
const val NO_WINNING_MOVE = "No winning move — try Undo"

data class GameUiState(
    val levelNumber: Int,
    val isBoss: Boolean,
    val hasNext: Boolean,
    /** Latest logical state (all applied moves). While [anim] is non-null the screen shows its playback instead. */
    val state: GameState,
    val anim: AnimJob? = null,
    val hintContainerId: Int? = null,
    val hintLoading: Boolean = false,
    /** Transient hint feedback (e.g. [NO_WINNING_MOVE]); cleared by the next state change. */
    val hintMessage: String? = null,
    val usedUndo: Boolean = false,
    val usedHint: Boolean = false,
    val canUndo: Boolean = false,
    /** Increments whenever a tap is refused because all dock slots are occupied (UI shakes the dock). */
    val dockFullSignal: Int = 0,
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
 * once [onAnimationFinished] is called for the current job. A win is recorded only when the animation of the
 * winning transition has finished (an undo before that discards it); once recorded, undo is disabled.
 * A hint costs stars only when it actually highlights a move for the current state. All methods must be called on
 * the main thread.
 */
class GameViewModel(
    private val progress: ProgressSink,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Rules; [Engine.apply] in the app (tests may substitute a recorded engine). */
    private val applyMove: (GameState, Move) -> Transition = Engine::apply,
    /** Hint source; [Solver.hint] in the app. The second argument returns true once the search should stop. */
    private val findHint: (GameState, () -> Boolean) -> Move? = { s, cancelled -> Solver.hint(s, cancelled) },
    /**
     * Scope for progress writes. Not the viewModelScope: a win committed from [onCleared] must still be written
     * after the ViewModel is gone.
     */
    private val recordScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {

    private val _ui = MutableStateFlow<GameUiState?>(null)
    val ui: StateFlow<GameUiState?> = _ui.asStateFlow()

    private val undoStack = ArrayDeque<GameState>()
    private val pending = ArrayDeque<Move>()
    private var seq = 0L
    private var hintJob: Job? = null
    private var level: Level? = null
    private var winRecorded = false
    private var sink: ProgressSink = progress

    /**
     * Starts [level] as campaign level [levelNumber]. Unless [fresh], keeps an in-progress game of the same level
     * (e.g. after recomposition / configuration change).
     */
    fun start(
        level: Level,
        levelNumber: Int,
        hasNext: Boolean,
        fresh: Boolean = true,
        /** Where this game's win is recorded (e.g. the progress of the pack it belongs to); default [progress]. */
        sink: ProgressSink = progress,
    ) {
        val current = _ui.value
        if (!fresh && current != null && this.level == level && current.levelNumber == levelNumber && sink == this.sink) {
            return
        }
        reset() // commits a pending win to the previous game's sink
        this.level = level
        this.sink = sink
        _ui.value = GameUiState(
            levelNumber = levelNumber,
            isBoss = levelNumber % 10 == 0,
            hasNext = hasNext,
            state = GameState.initial(level),
        )
    }

    /**
     * Records a win whose animation has not finished yet (the player is leaving the game, or the app is being
     * stopped). Undo before this point still discards the win; after it the win is final.
     */
    fun commitPendingWin() {
        val ui = _ui.value ?: return
        if (ui.state.status != Status.WON || winRecorded) return
        winRecorded = true
        val n = ui.levelNumber
        val stars = ui.earnedStars
        val target = sink
        recordScope.launch { target.recordWin(n, stars) }
    }

    override fun onCleared() {
        commitPendingWin()
        super.onCleared()
    }

    fun onContainerTapped(containerId: Int) {
        val ui = _ui.value ?: return
        if (ui.state.status == Status.WON) return
        pending.addLast(Move(containerId))
        pump()
    }

    /** Called by the UI when it has finished playing back job [seq]. */
    fun onAnimationFinished(seq: Long) {
        val ui = _ui.value ?: return
        if (ui.anim?.seq != seq) return
        _ui.value = ui.copy(anim = null)
        commitPendingWin()
        pump()
    }

    fun undo() {
        val ui = _ui.value ?: return
        if (winRecorded) return
        val prev = undoStack.removeLastOrNull() ?: return
        cancelInFlight()
        _ui.value = ui.copy(
            state = prev,
            anim = null,
            usedUndo = true,
            canUndo = undoStack.isNotEmpty(),
            hintContainerId = null,
            hintLoading = false,
            hintMessage = null,
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

    /**
     * Highlights the next move of a winning line for the current state. Any state change (tap, undo, restart, new
     * level) cancels a running search through the solver's cancellation hook; a cancelled or outdated search costs
     * nothing and shows no message.
     */
    fun hint() {
        val ui = _ui.value ?: return
        if (ui.state.status != Status.PLAYING || ui.hintLoading) return
        // Computed for the latest logical state; discarded (free) if the state changes before it arrives.
        val target = ui.state
        _ui.value = ui.copy(hintLoading = true, hintMessage = null, hintContainerId = null)
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            val move = withContext(computeDispatcher) {
                val ctx = coroutineContext
                findHint(target) { !ctx.isActive }
            }
            ensureActive() // cancelled while searching: free, no message
            val now = _ui.value ?: return@launch
            _ui.value = when {
                now.state !== target -> now.copy(hintLoading = false)
                move == null -> now.copy(hintLoading = false, hintMessage = NO_WINNING_MOVE)
                else -> now.copy(hintLoading = false, hintContainerId = move.containerId, usedHint = true)
            }
        }
    }

    private fun reset() {
        commitPendingWin()
        cancelInFlight()
        undoStack.clear()
        winRecorded = false
    }

    /** Drops queued taps and stops a running hint search. */
    private fun cancelInFlight() {
        pending.clear()
        cancelHint()
    }

    private fun cancelHint() {
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
            val t = applyMove(before, move)
            val ignored = t.events.singleOrNull() as? Event.Ignored
            if (ignored != null || t.events.isEmpty()) {
                if (ignored?.reason == IgnoreReason.DOCK_FULL) {
                    _ui.value = ui.copy(dockFullSignal = ui.dockFullSignal + 1)
                }
                continue
            }
            val changed = t.state != before
            if (changed) {
                undoStack.addLast(before)
                cancelHint()
            }
            val won = t.state.status == Status.WON
            _ui.value = ui.copy(
                state = t.state,
                anim = AnimJob(++seq, before, t.events),
                canUndo = undoStack.isNotEmpty(),
                hintContainerId = if (changed) null else ui.hintContainerId,
                hintMessage = if (changed) null else ui.hintMessage,
                hintLoading = if (changed) false else ui.hintLoading,
                earnedStars = if (won) starsFor(ui.usedUndo, ui.usedHint) else 0,
            )
            if (won) pending.clear()
        }
    }
}
