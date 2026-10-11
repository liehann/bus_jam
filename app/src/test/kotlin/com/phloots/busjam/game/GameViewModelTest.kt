package com.phloots.busjam.game

import com.phloots.jam.core.model.Container
import com.phloots.jam.core.model.ContainerSize
import com.phloots.jam.core.model.Dir
import com.phloots.jam.core.model.Event
import com.phloots.jam.core.model.Level
import com.phloots.jam.core.model.Move
import com.phloots.jam.core.model.Status
import com.phloots.jam.core.solver.Solver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ViewModel behaviour against the real Engine / Solver on tiny hand-built boards (Engine does not validate sizes). */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val main = UnconfinedTestDispatcher(scheduler)
    private val wins = mutableListOf<Pair<Int, Int>>()
    private lateinit var vm: GameViewModel

    /** Two free S containers: 0 (colour 0) exits up, 1 (colour 1) exits right. */
    private val easy = Level(
        id = 1, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 1, 1, Dir.UP),
            Container(1, ContainerSize.S, 1, 3, 3, Dir.RIGHT),
        ),
        queue = listOf(0, 0, 0, 0, 1, 1, 1, 1),
    )

    /** S container 0 at (2,1) faces up; S container 1 at (1,1) sits on its exit ray. */
    private val blocked = Level(
        id = 2, width = 5, height = 5,
        containers = listOf(
            Container(0, ContainerSize.S, 0, 2, 1, Dir.UP),
            Container(1, ContainerSize.S, 1, 1, 1, Dir.RIGHT),
        ),
        queue = listOf(1, 1, 1, 1, 0, 0, 0, 0),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        vm = GameViewModel({ n, s -> wins += n to s }, main)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun ui() = vm.ui.value!!

    private fun finishAnimations() {
        while (true) {
            val a = vm.ui.value?.anim ?: return
            vm.onAnimationFinished(a.seq)
        }
    }

    private fun tap(id: Int) {
        vm.onContainerTapped(id)
        finishAnimations()
    }

    @Test
    fun cleanWinGivesThreeStarsAndRecordsProgressAfterTheAnimation() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.onContainerTapped(1)
        assertEquals(Status.WON, ui().state.status)
        assertFalse(ui().showResult)
        assertTrue("win must wait for the WON animation", wins.isEmpty())
        finishAnimations()
        assertTrue(ui().showResult)
        assertEquals(3, ui().earnedStars)
        assertEquals(listOf(1 to 3), wins)
    }

    @Test
    fun undoCostsOneStar() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        assertTrue(ui().canUndo)
        vm.undo()
        assertEquals(easy.containers.size, Snapshot.of(ui().state).onBoard.size)
        assertFalse(ui().canUndo)
        assertEquals(2, ui().potentialStars)
        tap(0)
        tap(1)
        assertEquals(Status.WON, ui().state.status)
        assertEquals(2, ui().earnedStars)
        assertEquals(listOf(1 to 2), wins)
    }

    @Test
    fun hintCostsTwoStarsAndHighlightsAContainer() {
        vm.start(easy, 1, hasNext = true)
        vm.hint()
        assertNotNull(ui().hintContainerId)
        assertTrue(ui().usedHint)
        assertEquals(1, ui().potentialStars)
        tap(ui().hintContainerId!!)
        assertNull(ui().hintContainerId)
        tap(if (ui().state.isOnBoard(0)) 0 else 1)
        assertEquals(Status.WON, ui().state.status)
        assertEquals(1, ui().earnedStars)
        assertEquals(listOf(1 to 1), wins)
    }

    @Test
    fun nullHintKeepsStarsAndExplains() {
        vm = GameViewModel({ n, s -> wins += n to s }, main, findHint = { _, _ -> null })
        vm.start(easy, 1, hasNext = true)
        vm.hint()
        assertFalse(ui().hintLoading)
        assertNull(ui().hintContainerId)
        assertEquals(NO_WINNING_MOVE, ui().hintMessage)
        assertFalse(ui().usedHint)
        assertEquals(3, ui().potentialStars)
        tap(0)
        assertNull("message clears on the next state change", ui().hintMessage)
        tap(1)
        assertEquals(3, ui().earnedStars)
    }

    @Test
    fun hintInvalidatedByATapIsFree() {
        val compute = StandardTestDispatcher(scheduler)
        vm = GameViewModel({ n, s -> wins += n to s }, compute, findHint = { st, c -> Solver.hint(st, c) })
        vm.start(easy, 1, hasNext = true)
        vm.hint()
        assertTrue(ui().hintLoading)
        tap(0) // state changes while the hint is computing
        scheduler.advanceUntilIdle()
        assertFalse(ui().hintLoading)
        assertNull(ui().hintContainerId)
        assertFalse(ui().usedHint)
        assertEquals(3, ui().potentialStars)
    }

    @Test
    fun restartClearsUndoStackAndPenalties() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.undo()
        vm.hint()
        assertEquals(1, ui().potentialStars)
        vm.restart()
        assertEquals(3, ui().potentialStars)
        assertFalse(ui().canUndo)
        assertFalse(ui().usedUndo)
        assertFalse(ui().usedHint)
        tap(0)
        tap(1)
        assertEquals(3, ui().earnedStars)
        assertEquals(listOf(1 to 3), wins)
    }

    @Test
    fun tapsDuringAnimationAreQueuedInOrder() {
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        vm.onContainerTapped(1)
        val first = ui().anim!!
        assertEquals(0, (first.events.first() as Event.Released).containerId)
        vm.onAnimationFinished(first.seq)
        val second = ui().anim!!
        assertEquals(1, (second.events.first() as Event.Released).containerId)
        assertFalse(ui().showResult) // result waits for the animation
        vm.onAnimationFinished(second.seq)
        assertTrue(ui().showResult)
        assertEquals(Status.WON, ui().state.status)
    }

    @Test
    fun undoDuringWinAnimationDiscardsTheWin() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.onContainerTapped(1)
        val winning = ui().anim!!
        assertEquals(Status.WON, ui().state.status)
        vm.undo()
        assertEquals(Status.PLAYING, ui().state.status)
        assertNull(ui().anim)
        vm.onAnimationFinished(winning.seq) // late callback from the cancelled playback
        assertTrue(wins.isEmpty())
        assertFalse(ui().showResult)
        tap(1)
        assertEquals(listOf(1 to 2), wins)
    }

    @Test
    fun undoIsDisabledOnceTheWinIsRecorded() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        tap(1)
        assertEquals(listOf(1 to 3), wins)
        vm.undo()
        assertEquals(Status.WON, ui().state.status)
        assertEquals(3, ui().earnedStars)
    }

    @Test
    fun blockAnimatesWithoutChangingStateOrUndo() {
        vm.start(blocked, 2, hasNext = false)
        val before = ui().state
        vm.onContainerTapped(0)
        val anim = ui().anim!!
        assertTrue(anim.events.single() is Event.Blocked)
        assertEquals(PhaseKind.BLOCK, anim.phases.single().kind)
        vm.onAnimationFinished(anim.seq)
        assertEquals(before, ui().state)
        assertFalse(ui().canUndo)
        tap(1)
        tap(0)
        assertEquals(Status.WON, ui().state.status)
        assertEquals(3, ui().earnedStars)
    }

    @Test
    fun phasesReplayToTheEngineState() {
        vm.start(easy, 1, hasNext = true)
        vm.onContainerTapped(0)
        val anim = ui().anim!!
        val kinds = anim.phases.map { it.kind }
        assertEquals(listOf(PhaseKind.RELEASE, PhaseKind.FLOW, PhaseKind.DISPATCH), kinds.take(3))
        assertEquals(Snapshot.of(ui().state), anim.phases.last().after)
    }

    @Test
    fun solverLineReplaysThroughTheViewModel() {
        vm.start(blocked, 2, hasNext = false)
        val line = Solver.solve(blocked)
        assertTrue(line.solvable)
        for (m: Move in line.solution) vm.onContainerTapped(m.containerId) // all queued during playback
        finishAnimations()
        assertEquals(Status.WON, ui().state.status)
        assertEquals(listOf(2 to 3), wins)
    }

    @Test
    fun leavingDuringTheWinAnimationStillRecordsTheWin() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.onContainerTapped(1) // winning move; its animation is "cancelled" by leaving the screen
        assertNotNull(ui().anim)
        vm.commitPendingWin() // Back / "< Levels" / ON_STOP
        assertEquals(listOf(1 to 3), wins)
        vm.undo() // the win is final now
        assertEquals(Status.WON, ui().state.status)
        finishAnimations() // late finish does not record twice
        assertEquals(listOf(1 to 3), wins)
    }

    @Test
    fun startingAnotherLevelDuringTheWinAnimationRecordsTheWin() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.onContainerTapped(1)
        vm.start(blocked, 2, hasNext = false) // openLevel(fresh = true) without the animation finishing
        assertEquals(listOf(1 to 3), wins)
        assertEquals(2, ui().levelNumber)
        assertEquals(Status.PLAYING, ui().state.status)
    }

    @Test
    fun undoThenLeavingRecordsNothing() {
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.onContainerTapped(1)
        vm.undo()
        vm.commitPendingWin()
        vm.start(blocked, 2, hasNext = false)
        assertTrue(wins.isEmpty())
    }

    @Test
    fun aTapCancelsTheRunningSearchForFree() {
        val compute = StandardTestDispatcher(scheduler)
        var sawCancel = false
        var first = true
        vm = GameViewModel(
            { n, s -> wins += n to s }, compute,
            findHint = { _, cancelled ->
                if (first) {
                    first = false
                    tap(0) // the player taps while the search runs: the state change cancels it
                    sawCancel = cancelled()
                }
                null // what Solver.hint returns when cancelled (and, for the second request, a lost position)
            },
        )
        vm.start(easy, 1, hasNext = true)
        vm.hint()
        assertTrue(ui().hintLoading)
        scheduler.advanceUntilIdle()
        assertFalse(ui().hintLoading)
        assertTrue("search saw the cancellation", sawCancel)
        assertNull(ui().hintMessage) // a cancelled search is not "no winning move"
        assertFalse(ui().usedHint)
        assertEquals(3, ui().potentialStars)
        vm.hint() // a new request works normally
        scheduler.advanceUntilIdle()
        assertEquals(NO_WINNING_MOVE, ui().hintMessage)
    }

    @Test
    fun undoAndRestartCancelTheSearch() {
        val compute = StandardTestDispatcher(scheduler)
        var searches = 0
        vm = GameViewModel({ n, s -> wins += n to s }, compute, findHint = { st, c -> searches++; Solver.hint(st, c) })
        vm.start(easy, 1, hasNext = true)
        tap(0)
        vm.hint()
        vm.undo()
        assertFalse(ui().hintLoading)
        vm.hint()
        vm.restart()
        scheduler.advanceUntilIdle()
        assertFalse(ui().usedHint)
        assertNull(ui().hintContainerId)
        assertNull(ui().hintMessage)
        assertEquals(3, ui().potentialStars)
    }

    @Test
    fun winsGoToTheSinkOfThePackBeingPlayed() {
        val motif = mutableListOf<Pair<Int, Int>>()
        vm.start(easy, 1, hasNext = true, sink = { n, st -> motif += n to st })
        tap(0)
        vm.onContainerTapped(1)
        vm.start(easy, 1, hasNext = true) // back to the default (campaign) sink mid-animation: commits to the motif pack
        assertEquals(listOf(1 to 3), motif)
        assertTrue(wins.isEmpty())
        tap(0)
        tap(1)
        assertEquals(listOf(1 to 3), wins)
        assertEquals(1, motif.size)
    }
}
