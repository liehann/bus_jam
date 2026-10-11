package com.phloots.busjam.game.rt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phloots.busjam.data.ProgressSink
import com.phloots.busjam.game.NO_WINNING_MOVE
import com.phloots.busjam.game.starsFor
import com.phloots.jam.core.model.GameState
import com.phloots.jam.core.model.IgnoreReason
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shown when a hint is asked for while the carousel is still boarding / refilling. */
const val HINT_WAIT = "Wait for the carousel to settle…"

/**
 * A blocked tap's bump, the only effect not visible in the real-time state (releases, walks and dispatches are drawn
 * from the state's own tick timestamps). Timed on the game clock.
 */
data class RtBump(val event: RtEvent.Blocked, val startMs: Long) {
    val endMs: Long get() = startMs + DURATION_MS
    fun progress(nowMs: Long): Float = ((nowMs - startMs).toFloat() / DURATION_MS).coerceIn(0f, 1f)

    companion object {
        const val DURATION_MS = 260
    }
}

data class RtUiState(
    val levelNumber: Int,
    val isBoss: Boolean,
    val hasNext: Boolean,
    val view: RtView,
    /** Game clock (ms of unpaused play); times the bumps. */
    val nowMs: Long,
    /** Progress towards the next tick, 0..1 (for smooth carousel motion). */
    val tickFraction: Float,
    val bumps: List<RtBump> = emptyList(),
    val paused: Boolean = false,
    val usedUndo: Boolean = false,
    val usedHint: Boolean = false,
    val canUndo: Boolean = false,
    val hintContainerId: Int? = null,
    val hintLoading: Boolean = false,
    val hintMessage: String? = null,
    val dockFullSignal: Int = 0,
    val bumpSignal: Int = 0,
    val boardSignal: Int = 0,
    val earnedStars: Int = 0,
) {
    val status: Status get() = view.status
    val potentialStars: Int get() = starsFor(usedUndo, usedHint)
    /** Game over (the core only reports it once the carousel has settled). */
    val showResult: Boolean get() = status != Status.PLAYING
}

/**
 * Real-time game: maps wall-clock frames to integer ticks of [rules] (no game logic here). Taps go straight to
 * [RealTimeRules.tap] at any time. Undo rewinds to just before the last state-changing tap, including the carousel
 * phase (the state snapshot plus the partial tick). Stars, undo and hint rules match the turn-based game; a hint is
 * only computed on the settled projection, otherwise the player is asked to wait.
 */
class RealTimeViewModel<S : Any>(
    private val progress: ProgressSink,
    private val rules: RealTimeRules<S>,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val findHint: (GameState, () -> Boolean) -> Move? = { s, cancelled -> Solver.hint(s, cancelled) },
    private val recordScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {

    private data class Snap<S : Any>(val state: S, val accumMs: Double)

    private val _ui = MutableStateFlow<RtUiState?>(null)
    val ui: StateFlow<RtUiState?> = _ui.asStateFlow()

    private var level: Level? = null
    private var sink: ProgressSink = progress
    private var state: S? = null
    private var accumMs = 0.0
    private var clockMs = 0.0
    private var lastFrameMs: Long? = null
    private var hostPaused = false
    private val undoStack = ArrayDeque<Snap<S>>()
    private var generation = 0L
    private var hintJob: Job? = null
    private var winRecorded = false

    fun start(level: Level, levelNumber: Int, hasNext: Boolean, fresh: Boolean = true, sink: ProgressSink = progress) {
        val current = _ui.value
        if (!fresh && current != null && this.level == level && current.levelNumber == levelNumber && sink == this.sink) {
            return
        }
        commitPendingWin()
        this.level = level
        this.sink = sink
        val s = rules.initial(level)
        reset(s)
        _ui.value = RtUiState(levelNumber, levelNumber % 10 == 0, hasNext, rules.view(s), nowMs = 0, tickFraction = 0f)
    }

    // ------------------------------------------------------------------ clock

    /** Called every frame with the frame time; advances the game by the elapsed time unless paused. */
    fun onFrame(frameTimeMs: Long) {
        val last = lastFrameMs
        lastFrameMs = frameTimeMs
        if (last == null) return
        advance((frameTimeMs - last).coerceIn(0L, MAX_FRAME_MS).toDouble())
    }

    /** Pause from the host (app in background, dialog open). Resuming never jumps ahead. */
    fun setHostPaused(paused: Boolean) {
        hostPaused = paused
        lastFrameMs = null
    }

    fun togglePause() {
        val ui = _ui.value ?: return
        lastFrameMs = null
        _ui.value = ui.copy(paused = !ui.paused)
    }

    /** Advances the game clock by [ms] (frames, or tests with a fake clock). */
    fun advance(ms: Double) {
        val ui = _ui.value ?: return
        var s = state ?: return
        if (ui.paused || hostPaused || ui.showResult) return
        val tick = rules.tickMs
        clockMs += ms
        val now = clockMs.toLong()
        accumMs += ms
        var boards = 0
        var ticked = false
        while (accumMs >= tick - EPS) {
            accumMs -= tick
            val step = rules.tick(s)
            s = step.state
            ticked = true
            boards += step.events.count { it is RtEvent.BoardingStarted }
            if (rules.view(s).status != Status.PLAYING) break
        }
        val view = if (ticked) rules.view(s) else ui.view
        if (view.status != Status.PLAYING) accumMs = 0.0
        state = s
        var message = ui.hintMessage
        if (message == HINT_WAIT && rules.settled(s) != null) message = null
        publish(
            ui.copy(
                view = view,
                nowMs = now,
                tickFraction = (accumMs.coerceAtLeast(0.0) / tick).toFloat().coerceIn(0f, 1f),
                bumps = ui.bumps.filter { it.endMs > now },
                hintMessage = message,
                boardSignal = ui.boardSignal + boards,
            ),
        )
    }

    /** Advances exactly [n] ticks (tests). */
    fun stepTicks(n: Int) = advance(n * rules.tickMs)

    // ------------------------------------------------------------------ player actions

    fun onContainerTapped(containerId: Int) {
        val ui = _ui.value ?: return
        val s = state ?: return
        if (ui.status != Status.PLAYING) return
        val step = rules.tap(s, containerId)
        val ignored = step.events.singleOrNull() as? RtEvent.Ignored
        if (ignored != null || step.events.isEmpty()) {
            if (ignored?.reason == IgnoreReason.DOCK_FULL) _ui.value = ui.copy(dockFullSignal = ui.dockFullSignal + 1)
            return
        }
        val blocked = step.events.all { it is RtEvent.Blocked }
        if (blocked) {
            val bumps = step.events.filterIsInstance<RtEvent.Blocked>().map { RtBump(it, ui.nowMs) }
            _ui.value = ui.copy(bumps = ui.bumps + bumps, bumpSignal = ui.bumpSignal + 1)
            return
        }
        undoStack.addLast(Snap(s, accumMs))
        state = step.state
        generation++
        cancelHint()
        publish(
            ui.copy(
                view = rules.view(step.state),
                canUndo = true,
                hintContainerId = null,
                hintLoading = false,
                hintMessage = null,
            ),
        )
    }

    fun undo() {
        val ui = _ui.value ?: return
        if (winRecorded) return
        val snap = undoStack.removeLastOrNull() ?: return
        state = snap.state
        accumMs = snap.accumMs
        generation++
        cancelHint()
        _ui.value = ui.copy(
            view = rules.view(snap.state),
            tickFraction = (accumMs / rules.tickMs).toFloat().coerceIn(0f, 1f),
            bumps = emptyList(),
            usedUndo = true,
            canUndo = undoStack.isNotEmpty(),
            hintContainerId = null,
            hintLoading = false,
            hintMessage = null,
            earnedStars = 0,
        )
    }

    fun restart() {
        val ui = _ui.value ?: return
        val level = level ?: return
        commitPendingWin()
        val s = rules.initial(level)
        reset(s)
        _ui.value = RtUiState(ui.levelNumber, ui.isBoss, ui.hasNext, rules.view(s), nowMs = ui.nowMs, tickFraction = 0f)
    }

    fun hint() {
        val ui = _ui.value ?: return
        val s = state ?: return
        if (ui.status != Status.PLAYING || ui.hintLoading) return
        val settled = rules.settled(s)
        if (settled == null) {
            _ui.value = ui.copy(hintMessage = HINT_WAIT, hintContainerId = null)
            return
        }
        val gen = generation
        _ui.value = ui.copy(hintLoading = true, hintMessage = null, hintContainerId = null)
        hintJob?.cancel()
        hintJob = viewModelScope.launch {
            val move = withContext(computeDispatcher) {
                val ctx = coroutineContext
                findHint(settled) { !ctx.isActive }
            }
            ensureActive()
            val now = _ui.value ?: return@launch
            _ui.value = when {
                gen != generation -> now.copy(hintLoading = false)
                move == null -> now.copy(hintLoading = false, hintMessage = NO_WINNING_MOVE)
                else -> now.copy(hintLoading = false, hintContainerId = move.containerId, usedHint = true)
            }
        }
    }

    /** Records a win whose last effects are still playing (leaving the game / app stopped). */
    fun commitPendingWin() {
        val ui = _ui.value ?: return
        if (ui.status != Status.WON || winRecorded) return
        winRecorded = true
        val n = ui.levelNumber
        val stars = ui.earnedStars.takeIf { it > 0 } ?: starsFor(ui.usedUndo, ui.usedHint)
        val target = sink
        recordScope.launch { target.recordWin(n, stars) }
    }

    override fun onCleared() {
        commitPendingWin()
        super.onCleared()
    }

    // ------------------------------------------------------------------ internals

    private fun reset(s: S) {
        cancelHint()
        state = s
        accumMs = 0.0
        lastFrameMs = null
        undoStack.clear()
        generation++
        winRecorded = false
    }

    private fun cancelHint() {
        hintJob?.cancel()
        hintJob = null
    }

    /** Publishes [next]; sets the stars and records the win when the game is won (the core reports WON once settled). */
    private fun publish(next: RtUiState) {
        var ui = next
        if (ui.status == Status.WON && ui.earnedStars == 0) ui = ui.copy(earnedStars = starsFor(ui.usedUndo, ui.usedHint))
        _ui.value = ui
        if (ui.status == Status.WON) commitPendingWin()
    }

    private companion object {
        /** Longest frame gap applied at once (a hiccup never fast-forwards the carousel). */
        const val MAX_FRAME_MS = 100L
        const val EPS = 1e-6
    }
}
